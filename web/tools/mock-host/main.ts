/**
 * web/tools/mock-host/main.ts — FROZEN [S0]  (B의 디커플링 장치)
 *
 * B가 A 없이 리스너 전체 경로를 단독 개발하기 위한 최소 호스트다.
 * 440Hz 오실레이터 → MediaStreamDestination → peer-joined 마다 fan-out. 프로토콜 100% 준수.
 *
 * ★기능을 늘리지 않는다. 여긴 '상대의 최소 대역품' 이지 두 번째 호스트 구현이 아니다.
 *   그래서 SDP munging 도, 대시보드도, 소스 선택도 없다 — 전부 A의 몫이다.
 *
 * ★hostToken 저장 키를 name 별로 분리한다: localStorage['lt.shared.mock-host.<name>']
 *   키가 공용이면 B의 필수 시험 두 개가 서로를 배제한다. 재점유 시험은 토큰을 보관해야 하는데,
 *   키를 공유하면 두 번째 탭이 같은 토큰으로 같은 방을 잡아 첫 탭을 close(4003)로 밀어낸다.
 *   그러면 '방 두 개 만들어 갈아타기' 가 성립하지 않는다.
 *
 * 사용법
 *   ?name=mh1 / ?name=mh2   두 방을 만들어 갈아타기 시험
 *   같은 ?name= 으로 새로고침  재점유 시험
 *   ?code=A3F9              그 코드를 점유 시도(미지정이면 뽑아서 시도하고 room-taken 이면 다시 뽑는다)
 */
import {
  CLOSE,
  HEARTBEAT_MS,
  PROTOCOL_V,
  SAMPLE_RATE,
  joinUrl,
  newRoomCode,
  normalizeCode,
  roomWsPath,
  wsUrl,
} from '../../shared/protocol';
import type { PeerId, RoomServerMsg } from '../../shared/protocol';
import { RTC_CONFIG } from '../../shared/ice';
import { openWs } from '../../shared/ws';
import type { WsHandle } from '../../shared/ws';
import { ltLog } from '../../shared/log';

const TONE_HZ = 440;

export function boot(opts: { name: string; code?: string }): void {
  const tokenKey = `lt.shared.mock-host.${opts.name}`;

  /* ── 화면 ────────────────────────────────────────────────────── */
  document.title = `mock-host ${opts.name}`;
  const ui = document.createElement('div');
  ui.style.cssText = 'font:14px/1.7 monospace;padding:16px;max-width:640px';
  const h = document.createElement('h2');
  h.textContent = `mock-host — ${opts.name}`;
  const line = document.createElement('pre');
  line.style.cssText = 'white-space:pre-wrap;background:#f4f5f7;padding:12px;border-radius:8px';
  const bar = document.createElement('div');
  bar.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;margin:12px 0';
  ui.append(h, bar, line);
  document.body.append(ui);

  const log: string[] = [];
  const say = (s: string) => {
    log.unshift(`${new Date().toISOString().slice(11, 19)}  ${s}`);
    line.textContent = log.slice(0, 24).join('\n');
    ltLog('mock-host', s);
  };
  const button = (label: string, fn: () => void) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.style.cssText = 'padding:8px 14px;font:inherit;cursor:pointer';
    b.onclick = fn;
    bar.append(b);
    return b;
  };

  /* ── 오디오 소스 ─────────────────────────────────────────────── */
  let ctx: AudioContext | null = null;
  let track: MediaStreamTrack | null = null;

  function startTone(): MediaStreamTrack {
    // ★SAMPLE_RATE 로 연다. 기본 생성자로 열면 기기에 따라 44100 이 잡히고
    //   Opus 가 내부 48k 고정이라 리샘플러가 양쪽에 하나씩 낀다.
    ctx = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' });
    if (ctx.sampleRate !== SAMPLE_RATE) say(`경고: sampleRate=${ctx.sampleRate} (48000 아님)`);

    const osc = ctx.createOscillator();
    osc.type = 'sine';
    osc.frequency.value = TONE_HZ;
    const gain = ctx.createGain();
    gain.gain.value = 0.2;
    const dest = ctx.createMediaStreamDestination();
    osc.connect(gain).connect(dest);
    osc.start();

    const t = dest.stream.getAudioTracks()[0]!;
    t.contentHint = 'music';
    return t;
  }

  /* ── 상태 ────────────────────────────────────────────────────── */
  const peers = new Map<PeerId, RTCPeerConnection>();
  let ws: WsHandle<Record<string, unknown>> | null = null;
  let beat: ReturnType<typeof setInterval> | null = null;
  let tries = 0;

  function closeAllPeers(): void {
    for (const pc of peers.values()) pc.close();
    peers.clear();
  }

  function stopBeat(): void {
    if (beat !== null) {
      clearInterval(beat);
      beat = null;
    }
  }

  /* ── 연결 ────────────────────────────────────────────────────── */
  function connect(code: string): void {
    const url = wsUrl(location.origin, roomWsPath(code));
    say(`룸 WS 열기 ${code}`);

    ws = openWs<Record<string, unknown>, RoomServerMsg>(url, {
      onOpen() {
        const saved = localStorage.getItem(tokenKey);
        ws!.send({
          t: 'host-open',
          v: PROTOCOL_V,
          deviceName: opts.name,
          ...(saved ? { hostToken: saved } : {}),
        });
      },
      onMessage(m) {
        void handle(m);
      },
      onClose(code) {
        say(`룸 WS 닫힘 code=${code}`);
        stopBeat();
        closeAllPeers();
      },
    });
  }

  async function handle(m: RoomServerMsg): Promise<void> {
    switch (m.t) {
      case 'room-created': {
        tries = 0;
        localStorage.setItem(tokenKey, m.hostToken);
        say(`방 ${m.roomId} ${m.resumed ? '재점유' : '생성'} · ${m.listeners.length}명 남아있음`);
        say(`참여 링크 ${joinUrl(location.origin, m.roomId)}`);

        // 하트비트. 서버가 listeners 를 직접 세므로 여기선 신고하지 않는다.
        stopBeat();
        beat = setInterval(() => {
          ws?.send({ t: 'host-announce', deviceName: opts.name, sourceReady: track !== null });
        }, HEARTBEAT_MS);
        return;
      }

      case 'peer-joined': {
        say(`입장 ${m.peerId} (${m.deviceName})${m.resumed ? ' [재점유 복구]' : ''}`);
        await offerTo(m.peerId);
        return;
      }

      case 'peer-left': {
        say(`퇴장 ${m.peerId} (${m.reason})`);
        // ★그 PC 만 정리한다. 느린 1명이 전체를 막지 않는다는 원본 원칙의 웹 대응.
        peers.get(m.peerId)?.close();
        peers.delete(m.peerId);
        return;
      }

      case 'answer': {
        const pc = peers.get(m.from);
        if (!pc) return say(`answer 인데 PC 없음 ${m.from}`);
        await pc.setRemoteDescription(m.sdp as RTCSessionDescriptionInit);
        say(`answer 수신 ${m.from}`);
        return;
      }

      case 'ice': {
        const pc = peers.get(m.from as PeerId);
        if (!pc) return;
        // ★candidate:null(end-of-candidates)도 그대로 넣는다.
        await pc.addIceCandidate(m.candidate ?? undefined).catch(() => {});
        return;
      }

      case 'error': {
        if (m.code === 'room-taken') {
          // 코드를 지정받지 않았으면 새로 뽑아 재시도한다.
          tries += 1;
          if (opts.code || tries > 5) return say(`실패 room-taken (${tries}회)`);
          const next = newRoomCode();
          say(`room-taken → 새 코드 ${next} 로 재시도`);
          ws?.close();
          connect(next);
          return;
        }
        say(`에러 ${m.code}: ${m.msg}`);
        return;
      }

      default:
        return;
    }
  }

  async function offerTo(peerId: PeerId): Promise<void> {
    if (!track) return say(`offer 못 함 — 소스 없음 (${peerId})`);

    const pc = new RTCPeerConnection(RTC_CONFIG);
    peers.set(peerId, pc);
    pc.addTrack(track);

    pc.onicecandidate = (e) => {
      // null 도 보낸다. 삼키면 ICE 가 늦게 끝나 첫 소리가 느려진다.
      ws?.send({ t: 'ice', to: peerId, candidate: e.candidate ? e.candidate.toJSON() : null });
    };
    pc.onconnectionstatechange = () => say(`${peerId} ${pc.connectionState}`);

    const offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    ws?.send({ t: 'offer', to: peerId, sdp: { type: 'offer', sdp: offer.sdp ?? '' } });
    say(`offer 송신 ${peerId}`);
  }

  /* ── 버튼 3개 ────────────────────────────────────────────────── */
  const bStart = button('공유 시작', () => {
    if (track) return;
    track = startTone();
    say(`440Hz 시작 (ctx ${ctx?.sampleRate}Hz)`);
    bStart.disabled = true;
    connect(normalizeCode(opts.code ?? '') ?? newRoomCode());
  });

  button('공유 중지', () => {
    // 명시적 중지 — 서버가 유예 없이 방을 닫는다.
    ws?.send({ t: 'stop-share' });
    say('stop-share 송신');
  });

  button('강제 종료', () => {
    // stop 없이 소켓만 끊는다 → host-stopped(gone) + 60초 유예 경로 검증용.
    say('WS 강제 close (gone 경로)');
    ws?.close(CLOSE.LEAVE);
  });

  say(`준비됨. [공유 시작] 을 누르세요. 토큰 키 = ${tokenKey}`);
}
