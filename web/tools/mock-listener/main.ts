/**
 * web/tools/mock-listener/main.ts — FROZEN [S0]  (A의 디커플링 장치)
 *
 * 자동 join → offer 수신 → answer 회신 → srcObject 재생.
 * ?code=A3F9&name=mock-1 로 4탭을 띄우면 A 혼자 4인 mesh·munging·대시보드를 검증할 수 있다.
 *
 * ★stats 를 지어내지 않는다. getStats(inbound-rtp) 의 원시 카운터를 STATS_MS 주기로 실제
 *   측정해 보낸다. 이건 기능이 아니라 정확도 규정이다 — 가짜 숫자로는 A의 완료 기준 두 개가
 *   원리적으로 검증 불가다. drop 은 A의 packetsSent 증분과 여기 packetsReceived 증분의 차이이고,
 *   상호 오프셋은 jitterBufferDelay/EmittedCount + outputLatency 에서 나온다.
 *   값이 실측이 아니면 숫자가 그려지기만 할 뿐 '격리가 실제로 발동하는가' 를 증명하지 못한다.
 *
 * ★host-stopped(reason:'gone') 을 받아도 **룸 WS 를 닫지 않는다.** PC 만 정리하고 대기하다가
 *   재offer 를 수락한다(§7 전이 10). 이게 없으면 A는 재점유 경로를 혼자 검증할 수 없다.
 *
 * ?stall=<ms> 는 stats 보고를 의도적으로 정체시킨다 — '느린 청취자만 격리' 발동 시험용이며,
 * §3-13('mock 에 기능을 늘리지 않는다')의 유일한 명시적 예외다.
 */
import {
  CLOSE,
  JITTER_BUFFER_TARGET_MS,
  PROTOCOL_V,
  SAMPLE_RATE,
  STATS_MS,
  normalizeCode,
  roomWsPath,
  wsUrl,
} from '../../shared/protocol';
import type { RoomServerMsg, StatsMsg } from '../../shared/protocol';
import { RTC_CONFIG } from '../../shared/ice';
import { openWs } from '../../shared/ws';
import type { WsHandle } from '../../shared/ws';
import { ltLog } from '../../shared/log';

export function boot(opts: { code: string; name: string; stallMs?: number }): void {
  const code = normalizeCode(opts.code);

  /* ── 화면 ────────────────────────────────────────────────────── */
  document.title = `mock-listener ${opts.name}`;
  const ui = document.createElement('div');
  ui.style.cssText = 'font:14px/1.7 monospace;padding:16px;max-width:640px';
  const h = document.createElement('h2');
  h.textContent = `mock-listener — ${opts.name}${opts.stallMs ? ` (stall ${opts.stallMs}ms)` : ''}`;
  const bar = document.createElement('div');
  bar.style.cssText = 'display:flex;gap:8px;flex-wrap:wrap;margin:12px 0';
  const line = document.createElement('pre');
  line.style.cssText = 'white-space:pre-wrap;background:#f4f5f7;padding:12px;border-radius:8px';
  const audio = document.createElement('audio');
  audio.autoplay = true;
  audio.controls = true;
  ui.append(h, bar, audio, line);
  document.body.append(ui);

  const log: string[] = [];
  const say = (s: string) => {
    log.unshift(`${new Date().toISOString().slice(11, 19)}  ${s}`);
    line.textContent = log.slice(0, 24).join('\n');
    ltLog('mock-listener', s);
  };
  const button = (label: string, fn: () => void) => {
    const b = document.createElement('button');
    b.textContent = label;
    b.style.cssText = 'padding:8px 14px;font:inherit;cursor:pointer';
    b.onclick = fn;
    bar.append(b);
  };

  if (!code) {
    say('?code= 가 없습니다. 예: ?code=A3F9&name=mock-1');
    return;
  }

  /* ── 상태 ────────────────────────────────────────────────────── */
  let pc: RTCPeerConnection | null = null;
  let statsTimer: ReturnType<typeof setInterval> | null = null;
  let firstAudioMs: number | undefined;
  let joinedAt = 0;
  let outputLatencyMs: number | undefined;

  // outputLatency 를 얻으려면 AudioContext 가 필요하다. 재생은 <audio> 가 하고
  // 이 컨텍스트는 지연 수치를 읽는 용도로만 연다.
  try {
    const probe = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' });
    outputLatencyMs = Math.round(probe.outputLatency * 1000);
  } catch {
    /* 미지원. optional 필드라 안 실으면 그만이다. */
  }

  function dropPeer(): void {
    pc?.close();
    pc = null;
    if (statsTimer !== null) {
      clearInterval(statsTimer);
      statsTimer = null;
    }
  }

  /* ── 연결 ────────────────────────────────────────────────────── */
  const url = wsUrl(location.origin, roomWsPath(code));
  const ws: WsHandle<Record<string, unknown>> = openWs<Record<string, unknown>, RoomServerMsg>(url, {
    onOpen() {
      joinedAt = Date.now();
      ws.send({ t: 'join', v: PROTOCOL_V, deviceName: opts.name });
      say(`join 송신 ${code}`);
    },
    onMessage(m) {
      void handle(m);
    },
    onClose(c) {
      say(`룸 WS 닫힘 code=${c}`);
      dropPeer();
    },
  });

  async function handle(m: RoomServerMsg): Promise<void> {
    switch (m.t) {
      case 'joined':
        say(`입장 ${m.peerId} · 호스트 ${m.hostDeviceName} · hostPresent=${m.hostPresent}`);
        return;

      case 'offer': {
        // 재offer 가 올 수 있다(재점유 복구). 기존 PC 는 버리고 새로 만든다.
        dropPeer();
        pc = new RTCPeerConnection(RTC_CONFIG);

        pc.ontrack = (e) => {
          audio.srcObject = e.streams[0] ?? new MediaStream([e.track]);
          void audio.play().catch((err: Error) => say(`재생 실패: ${err.name}`));

          // 청취자 간 오프셋을 줄이려면 모두 같은 목표를 써야 한다. 기능 감지 후에만 대입.
          const rx = pc?.getReceivers().find((r) => r.track.kind === 'audio');
          if (rx && 'jitterBufferTarget' in rx) rx.jitterBufferTarget = JITTER_BUFFER_TARGET_MS;
        };
        pc.onicecandidate = (e) => {
          ws.send({ t: 'ice', candidate: e.candidate ? e.candidate.toJSON() : null });
        };
        pc.onconnectionstatechange = () => say(`pc ${pc?.connectionState}`);

        await pc.setRemoteDescription(m.sdp as RTCSessionDescriptionInit);
        const answer = await pc.createAnswer();
        await pc.setLocalDescription(answer);
        ws.send({ t: 'answer', sdp: { type: 'answer', sdp: answer.sdp ?? '' } });
        say('answer 송신');

        startStats();
        return;
      }

      case 'ice':
        await pc?.addIceCandidate(m.candidate ?? undefined).catch(() => {});
        return;

      case 'host-stopped':
        say(`host-stopped (${m.reason}${m.graceMs ? ` · 유예 ${m.graceMs}ms` : ''})`);
        // ★gone 이면 WS 를 닫지 않는다. PC 만 정리하고 재offer 를 기다린다.
        dropPeer();
        return;

      case 'error':
        say(`에러 ${m.code}: ${m.msg}`);
        return;

      default:
        return;
    }
  }

  /* ── 실측 stats ──────────────────────────────────────────────── */
  function startStats(): void {
    if (statsTimer !== null) return;
    statsTimer = setInterval(() => void report(), STATS_MS);
  }

  async function report(): Promise<void> {
    if (!pc) return;

    // ?stall= 은 보고를 의도적으로 늦춘다. 수신 자체는 멀쩡한데 리포트만 정체하는 상황을
    // 만들어서, A의 '느린 청취자만 격리' 가 실제로 발동하는지 본다.
    if (opts.stallMs) await new Promise((r) => setTimeout(r, opts.stallMs));

    const stats = await pc.getStats();
    let inbound: RTCInboundRtpStreamStats | null = null;
    stats.forEach((s) => {
      if (s.type === 'inbound-rtp' && (s as RTCInboundRtpStreamStats).kind === 'audio') {
        inbound = s as RTCInboundRtpStreamStats;
      }
    });
    if (!inbound) return;
    const r: RTCInboundRtpStreamStats = inbound;

    const received = r.packetsReceived ?? 0;
    if (received > 0 && firstAudioMs === undefined) {
      firstAudioMs = Date.now() - joinedAt;
      say(`첫 오디오까지 ${firstAudioMs}ms`);
    }

    const msg: StatsMsg = {
      t: 'stats',
      packetsReceived: received,
      jitterBufferDelay: r.jitterBufferDelay ?? 0,
      jitterBufferEmittedCount: r.jitterBufferEmittedCount ?? 0,
      concealedSamples: r.concealedSamples ?? 0,
      ts: Date.now(),
      ...(r.packetsLost === undefined ? {} : { packetsLost: r.packetsLost }),
      ...(r.concealmentEvents === undefined ? {} : { concealmentEvents: r.concealmentEvents }),
      ...(r.audioLevel === undefined ? {} : { audioLevel: r.audioLevel }),
      ...(outputLatencyMs === undefined ? {} : { outputLatencyMs }),
      ...(firstAudioMs === undefined ? {} : { firstAudioMs }),
    };
    ws.send(msg as unknown as Record<string, unknown>);
  }

  /* ── 버튼 2개 ────────────────────────────────────────────────── */
  button('나가기', () => {
    ws.send({ t: 'leave', reason: 'leave' });
    ws.close(CLOSE.LEAVE);
    dropPeer();
    say('나가기 (leave → close 4001)');
  });

  button('강제 종료', () => {
    // leave 없이 즉시 끊는다 = 탭 크래시 시뮬레이션. 서버가 close code 로 reason 을 유도한다.
    dropPeer();
    ws.close();
    say('강제 종료 (leave 없이 close)');
  });
}
