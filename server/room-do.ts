/**
 * server/room-do.ts — FROZEN [S0]
 *
 * 방 하나 = DO 인스턴스 하나(idFromName(방코드)). 호스트 1 + 리스너 최대 4.
 *
 * 핵심 성질 넷. 이게 무너지면 나머지는 의미가 없다.
 *
 *  1. **stats 는 불투명 릴레이다.** from 만 찍고 나머지 필드는 읽지도 검증하지도 않는다.
 *     덕분에 리스너가 optional 필드를 추가해도 서버를 재배포할 필요가 없다.
 *     이 원칙 자체가 계약이다 — 서버가 stats 를 해석하기 시작하면 동결이 깨진다.
 *
 *  2. **listeners 수는 서버가 직접 센다.** 호스트 자기 신고를 신뢰하지 않는다.
 *
 *  3. **호스트 WS 가 끊겨도 방은 ROOM_GRACE_MS 동안 살아 있다.** 리스너 소켓을 닫지 않고
 *     host-stopped(gone) 만 보낸다. 호스트가 같은 토큰으로 돌아오면 peer-joined 가 다시 나가
 *     자동 복구된다. 새로고침 한 번에 친구들이 전부 튕기지 않게 하는 장치다.
 *
 *  4. **candidate:null 도 그대로 릴레이한다.** end-of-candidates 를 삼키면 ICE 가 늦게 끝나
 *     첫 소리가 눈에 띄게 느려진다.
 */
import {
  CLOSE,
  HEARTBEAT_MS,
  INTERNAL_ANNOUNCE_PATH,
  INTERNAL_OFFLINE_PATH,
  MAX_LISTENERS,
  PING_FRAME,
  PONG_FRAME,
  PROTOCOL_V,
  ROOM_GRACE_MS,
  ROOM_PATH_PREFIX,
  decode,
  joinUrl,
  normalizeCode,
  reasonFromCloseCode,
} from '../web/shared/protocol';
import type {
  InternalAnnounce,
  InternalOffline,
  PeerId,
  PeerLeftReason,
  RoomServerMsg,
  SdpPayload,
} from '../web/shared/protocol';
import { HDR_LOBBY, HDR_ORIGIN } from './lobby-key';
import type { Env } from './env';

interface RoomState {
  roomId: string;
  deviceName: string;
  hostToken: string;
  sourceReady?: boolean;
  createdAt: number;
  stoppedAt?: number;
}

type SocketMeta =
  | { role: null }
  | { role: 'host' }
  | { role: 'listener'; peerId: PeerId; deviceName: string; leaveReason?: PeerLeftReason };

const K_ROOM = 'room';
const K_NEXT_PEER = 'nextPeer';
const K_LOBBY = 'lobby';
const K_ORIGIN = 'origin';

function token(): string {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
}

export class RoomDO {
  private room: RoomState | null = null;
  private nextPeer = 2; // p1 은 개념상 호스트다. 리스너는 p2 부터.
  private lobbyName = '';
  private origin = '';
  /** announce 중계 스로틀. HEARTBEAT_MS 에 1회를 넘기지 않는다. */
  private lastRelay = 0;

  constructor(
    private readonly state: DurableObjectState,
    private readonly env: Env,
  ) {
    this.state.blockConcurrencyWhile(async () => {
      this.room = (await this.state.storage.get<RoomState>(K_ROOM)) ?? null;
      this.nextPeer = (await this.state.storage.get<number>(K_NEXT_PEER)) ?? 2;
      this.lobbyName = (await this.state.storage.get<string>(K_LOBBY)) ?? '';
      this.origin = (await this.state.storage.get<string>(K_ORIGIN)) ?? '';
    });

    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING_FRAME, PONG_FRAME));
  }

  /* ════════════════════════════════════════════════════════════════
   * 진입점
   * ══════════════════════════════════════════════════════════════ */

  async fetch(request: Request): Promise<Response> {
    if ((request.headers.get('Upgrade') ?? '').toLowerCase() !== 'websocket') {
      return new Response('expected websocket', { status: 426 });
    }

    // DO 는 자기 idFromName 키를 읽을 수 없다. 방코드는 업그레이드 URL 에서 뽑는다.
    // Worker 가 isWellFormedCode 로 이미 걸렀으므로 여기서는 정상값만 온다.
    const p = new URL(request.url).pathname;
    if (p.startsWith(ROOM_PATH_PREFIX)) {
      const code = normalizeCode(decodeURIComponent(p.slice(ROOM_PATH_PREFIX.length)));
      if (code) this.pendingRoomId = code;
    }

    // 로비 이름과 origin 은 Worker 만 알 수 있다(RoomDO 는 접속자 IP 를 못 본다).
    // 알람 시점(유예 만료)에도 필요하므로 storage 에 남긴다.
    const lobby = request.headers.get(HDR_LOBBY) ?? '';
    const origin = request.headers.get(HDR_ORIGIN) ?? '';
    if (lobby && lobby !== this.lobbyName) {
      this.lobbyName = lobby;
      await this.state.storage.put(K_LOBBY, lobby);
    }
    if (origin && origin !== this.origin) {
      this.origin = origin;
      await this.state.storage.put(K_ORIGIN, origin);
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.state.acceptWebSocket(server);
    this.setMeta(server, { role: null });
    return new Response(null, { status: 101, webSocket: client });
  }

  /* ════════════════════════════════════════════════════════════════
   * 메시지
   * ══════════════════════════════════════════════════════════════ */

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const msg = decode(typeof raw === 'string' ? raw : new TextDecoder().decode(raw));
    if (!msg) return this.fail(ws, 'bad-message', 'JSON 을 읽을 수 없습니다', CLOSE.BAD_MESSAGE);

    const meta = this.getMeta(ws);

    /* ── 첫 프레임이 역할을 결정한다 ──────────────────────────── */
    if (meta.role === null) {
      if (msg.t === 'host-open') return this.onHostOpen(ws, msg);
      if (msg.t === 'join') return this.onJoin(ws, msg);
      return this.fail(
        ws,
        'bad-message',
        '첫 프레임은 host-open 또는 join 이어야 합니다',
        CLOSE.BAD_MESSAGE,
      );
    }

    /* ── 호스트 ───────────────────────────────────────────────── */
    if (meta.role === 'host') {
      switch (msg.t) {
        case 'host-announce':
          return this.onAnnounce(msg);
        case 'stop-share':
          return this.onStopShare();
        case 'offer':
          return this.relayOffer(ws, msg);
        case 'ice':
          return this.relayHostIce(ws, msg);
        case 'ping':
          return; // autoResponse 담당. 여기 오면 그냥 무시한다.
        case 'answer':
        case 'join':
        case 'leave':
        case 'stats':
          // 역할 밖 메시지는 거절하되 **소켓은 유지한다.**
          return this.send(ws, {
            t: 'error',
            code: 'not-allowed',
            msg: '호스트가 보낼 수 없는 메시지입니다',
          });
        default:
          return; // 모르는 t 는 조용히 무시(전방 호환)
      }
    }

    /* ── 리스너 ───────────────────────────────────────────────── */
    switch (msg.t) {
      case 'answer':
        return this.relayAnswer(meta.peerId, msg);
      case 'ice':
        return this.relayListenerIce(meta.peerId, msg);
      case 'stats':
        // ★불투명 릴레이. from 만 찍는다. 나머지는 읽지 않는다.
        return this.toHost({ ...msg, from: meta.peerId } as unknown as RoomServerMsg);
      case 'leave': {
        const r = msg['reason'];
        const reason: PeerLeftReason = r === 'switch' ? 'switch' : 'leave';
        this.setMeta(ws, { ...meta, leaveReason: reason });
        return;
      }
      case 'ping':
        return;
      case 'host-open':
      case 'host-announce':
      case 'stop-share':
        return this.send(ws, {
          t: 'error',
          code: 'not-allowed',
          msg: '리스너가 보낼 수 없는 메시지입니다',
        });
      default:
        return;
    }
  }

  /* ════════════════════════════════════════════════════════════════
   * host-open — 생성과 재점유를 겸한다 (4분기)
   * ══════════════════════════════════════════════════════════════ */

  private async onHostOpen(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    if (msg['v'] !== PROTOCOL_V) {
      return this.fail(ws, 'bad-version', '프로토콜 버전이 다릅니다', CLOSE.BAD_VERSION);
    }

    const deviceName = typeof msg['deviceName'] === 'string' ? msg['deviceName'] : '';
    const given = typeof msg['hostToken'] === 'string' ? msg['hostToken'] : undefined;
    const roomId = this.roomIdFromUrl();
    const existingHost = this.hostSocket();

    let resumed = false;

    if (this.room === null) {
      // 분기 1·2 — 방이 없다. 토큰이 있든(유예 만료 후) 없든 새로 만든다.
      // 토큰이 있었던 경우에도 **새 토큰**을 발급한다. 이미 뿌린 QR 은 코드 기반이라 계속 유효하다.
      this.room = { roomId, deviceName, hostToken: token(), createdAt: Date.now() };
      await this.state.storage.put(K_ROOM, this.room);
    } else if (given && given === this.room.hostToken) {
      // 분기 3 — 재점유. 기존 토큰을 유지하고 유예 알람을 취소한다.
      resumed = true;
      this.room.deviceName = deviceName || this.room.deviceName;
      delete this.room.stoppedAt;
      await this.state.storage.put(K_ROOM, this.room);
      await this.state.storage.deleteAlarm();

      // 같은 토큰을 든 옛 소켓이 아직 살아 있으면 교체한다.
      // 새로고침 때 옛 소켓의 close 이벤트가 늦게 도착하는 경합을 이걸로 흡수한다.
      if (existingHost) {
        this.setMeta(existingHost, { role: null }); // close 핸들러가 gone 처리를 하지 않도록
        try {
          existingHost.close(CLOSE.REPLACED, 'replaced');
        } catch {
          /* 이미 닫힘 */
        }
      }
    } else {
      // 분기 4 — 점유 중인데 토큰이 없거나 틀렸다. 기존 호스트는 건드리지 않는다.
      return this.fail(ws, 'room-taken', '이미 사용 중인 코드입니다', CLOSE.ROOM_CLOSED);
    }

    this.setMeta(ws, { role: 'host' });

    const listeners = this.listenerSockets().map((s) => {
      const m = this.getMeta(s) as Extract<SocketMeta, { role: 'listener' }>;
      return { peerId: m.peerId, deviceName: m.deviceName };
    });

    this.send(ws, {
      t: 'room-created',
      roomId: this.room.roomId,
      // ★joinUrl 을 상수로 박지 않는다. 슬롯(a/b/canonical)마다 origin 이 다르다.
      joinUrl: joinUrl(this.origin, this.room.roomId),
      hostToken: this.room.hostToken,
      resumed,
      listeners,
    });

    // 재점유로 되살린 리스너들에게 다시 붙으라고 알린다.
    // resumed:true 는 호스트가 '새 입장' 토스트를 띄우지 않을 근거다.
    if (resumed) {
      for (const l of listeners) {
        this.send(ws, { t: 'peer-joined', peerId: l.peerId, deviceName: l.deviceName, resumed: true });
      }
      // 유예 중 대기하던 리스너들에게도 호스트가 돌아왔음을 알린다.
      // (리스너는 룸 WS 를 닫지 않고 기다리고 있었다 — §7 전이 10)
    }
  }

  /* ════════════════════════════════════════════════════════════════
   * join
   * ══════════════════════════════════════════════════════════════ */

  private async onJoin(ws: WebSocket, msg: Record<string, unknown>): Promise<void> {
    if (msg['v'] !== PROTOCOL_V) {
      return this.fail(ws, 'bad-version', '프로토콜 버전이 다릅니다', CLOSE.BAD_VERSION);
    }
    if (this.room === null) {
      return this.fail(ws, 'room-not-found', '주소를 찾을 수 없습니다', CLOSE.ROOM_CLOSED);
    }
    if (this.listenerSockets().length >= MAX_LISTENERS) {
      return this.fail(ws, 'room-full', '인원이 가득 찼습니다', CLOSE.ROOM_CLOSED);
    }

    const deviceName = typeof msg['deviceName'] === 'string' ? msg['deviceName'] : '';
    const peerId = `p${this.nextPeer++}`;
    await this.state.storage.put(K_NEXT_PEER, this.nextPeer);
    this.setMeta(ws, { role: 'listener', peerId, deviceName });

    const host = this.hostSocket();

    this.send(ws, {
      t: 'joined',
      peerId,
      roomId: this.room.roomId,
      hostDeviceName: this.room.deviceName,
      // ★호스트가 유예 중 부재여도 거절하지 않는다. 낙관적으로 '연결 중' 이라 말하지도 않는다.
      hostPresent: host !== null,
    });

    if (host) this.send(host, { t: 'peer-joined', peerId, deviceName, resumed: false });
  }

  /* ════════════════════════════════════════════════════════════════
   * 하트비트 · 중지
   * ══════════════════════════════════════════════════════════════ */

  private async onAnnounce(msg: Record<string, unknown>): Promise<void> {
    if (!this.room) return;

    if (typeof msg['deviceName'] === 'string' && msg['deviceName']) {
      this.room.deviceName = msg['deviceName'];
    }
    if (typeof msg['sourceReady'] === 'boolean') {
      this.room.sourceReady = msg['sourceReady'];
    }

    const now = Date.now();
    if (now - this.lastRelay < HEARTBEAT_MS) return;
    this.lastRelay = now;

    await this.state.storage.put(K_ROOM, this.room);

    const body: InternalAnnounce = {
      roomId: this.room.roomId,
      deviceName: this.room.deviceName,
      // ★서버가 직접 센다. 호스트 자기 신고를 쓰지 않는다.
      listeners: this.listenerSockets().length,
      ...(this.room.sourceReady === undefined ? {} : { sourceReady: this.room.sourceReady }),
      ts: now,
    };
    await this.toLobby(INTERNAL_ANNOUNCE_PATH, body);
  }

  private async onStopShare(): Promise<void> {
    if (!this.room) return;
    // 명시적 중지는 **유예 없이** 닫는다. 재점유 대상이 아니다.
    this.broadcastToListeners({ t: 'host-stopped', reason: 'stop' });
    await this.toLobby(INTERNAL_OFFLINE_PATH, {
      roomId: this.room.roomId,
      reason: 'stop',
    } satisfies InternalOffline);
    await this.closeRoom();
  }

  /* ════════════════════════════════════════════════════════════════
   * 릴레이
   * ══════════════════════════════════════════════════════════════ */

  private relayOffer(ws: WebSocket, msg: Record<string, unknown>): void {
    const to = typeof msg['to'] === 'string' ? msg['to'] : null;
    const target = to ? this.listenerByPeerId(to) : null;
    if (!target) {
      return this.send(ws, {
        t: 'error',
        code: 'peer-not-found',
        msg: '그 참여자가 방에 없습니다',
      });
    }
    // to 를 벗기고 from:'host' 를 찍는다.
    this.send(target, { t: 'offer', from: 'host', sdp: msg['sdp'] as SdpPayload });
  }

  private relayHostIce(ws: WebSocket, msg: Record<string, unknown>): void {
    const to = typeof msg['to'] === 'string' ? msg['to'] : null;
    const target = to ? this.listenerByPeerId(to) : null;
    if (!target) {
      return this.send(ws, {
        t: 'error',
        code: 'peer-not-found',
        msg: '그 참여자가 방에 없습니다',
      });
    }
    // ★candidate:null(end-of-candidates)도 삼키지 않는다.
    this.send(target, {
      t: 'ice',
      from: 'host',
      candidate: (msg['candidate'] ?? null) as never,
    });
  }

  private relayAnswer(peerId: PeerId, msg: Record<string, unknown>): void {
    this.toHost({ t: 'answer', from: peerId, sdp: msg['sdp'] as SdpPayload });
  }

  private relayListenerIce(peerId: PeerId, msg: Record<string, unknown>): void {
    this.toHost({ t: 'ice', from: peerId, candidate: (msg['candidate'] ?? null) as never });
  }

  /* ════════════════════════════════════════════════════════════════
   * 소켓 종료
   * ══════════════════════════════════════════════════════════════ */

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    const meta = this.getMeta(ws);

    if (meta.role === 'host') {
      if (!this.room) return;
      // ★즉시 알린다. 다만 리스너 소켓은 닫지 않는다 — 유예 동안 기다리게 한다.
      this.broadcastToListeners({ t: 'host-stopped', reason: 'gone', graceMs: ROOM_GRACE_MS });
      await this.toLobby(INTERNAL_OFFLINE_PATH, {
        roomId: this.room.roomId,
        reason: 'gone',
      } satisfies InternalOffline);
      this.room.stoppedAt = Date.now();
      await this.state.storage.put(K_ROOM, this.room);
      await this.state.storage.setAlarm(Date.now() + ROOM_GRACE_MS);
      return;
    }

    if (meta.role === 'listener') {
      // leave 메시지가 먼저 왔으면 그 이유를 우선하고, 없으면 close code 에서 유도한다.
      const reason = meta.leaveReason ?? reasonFromCloseCode(code);
      this.toHost({ t: 'peer-left', peerId: meta.peerId, reason });
    }
  }

  webSocketError(): void {
    // close 가 뒤따른다.
  }

  /** 유예 만료. 방을 지우고 남은 소켓을 전부 닫는다. */
  async alarm(): Promise<void> {
    if (!this.room) return;
    await this.toLobby(INTERNAL_OFFLINE_PATH, {
      roomId: this.room.roomId,
      reason: 'timeout',
    } satisfies InternalOffline);
    await this.closeRoom();
  }

  private async closeRoom(): Promise<void> {
    this.room = null;
    await this.state.storage.delete(K_ROOM);
    await this.state.storage.deleteAlarm();
    for (const ws of this.state.getWebSockets()) {
      try {
        ws.close(CLOSE.ROOM_CLOSED, 'room-closed');
      } catch {
        /* 이미 닫힘 */
      }
    }
  }

  /* ════════════════════════════════════════════════════════════════
   * 유틸
   * ══════════════════════════════════════════════════════════════ */

  /** 방코드는 DO 이름과 같지만 DO 는 자기 이름을 못 읽는다. fetch() 가 URL 에서 채워둔다. */
  private pendingRoomId = '';

  private roomIdFromUrl(): string {
    return this.room?.roomId ?? this.pendingRoomId;
  }

  private async toLobby(path: string, body: InternalAnnounce | InternalOffline): Promise<void> {
    if (!this.lobbyName) return; // 로비를 모르면 중계하지 않는다(조용히 죽지 않게 이 분기를 남긴다)
    const stub = this.env.LOBBY.get(this.env.LOBBY.idFromName(this.lobbyName));
    await stub.fetch(
      new Request(`https://lt-lobby${path}`, {
        method: 'POST',
        body: JSON.stringify(body),
        headers: { 'Content-Type': 'application/json' },
      }),
    );
  }

  private hostSocket(): WebSocket | null {
    for (const ws of this.state.getWebSockets()) {
      if (this.getMeta(ws).role === 'host') return ws;
    }
    return null;
  }

  private listenerSockets(): WebSocket[] {
    return this.state.getWebSockets().filter((ws) => this.getMeta(ws).role === 'listener');
  }

  private listenerByPeerId(peerId: string): WebSocket | null {
    for (const ws of this.listenerSockets()) {
      const m = this.getMeta(ws);
      if (m.role === 'listener' && m.peerId === peerId) return ws;
    }
    return null;
  }

  private toHost(msg: RoomServerMsg): void {
    const host = this.hostSocket();
    if (host) this.send(host, msg);
  }

  private broadcastToListeners(msg: RoomServerMsg): void {
    const body = JSON.stringify(msg);
    for (const ws of this.listenerSockets()) {
      try {
        ws.send(body);
      } catch {
        /* 닫힌 소켓 */
      }
    }
  }

  private send(ws: WebSocket, msg: RoomServerMsg): void {
    try {
      ws.send(JSON.stringify(msg));
    } catch {
      /* 닫힌 소켓 */
    }
  }

  private fail(ws: WebSocket, code: string, msg: string, closeCode: number): void {
    this.send(ws, { t: 'error', code, msg });
    try {
      ws.close(closeCode, code);
    } catch {
      /* 이미 닫힘 */
    }
  }

  private getMeta(ws: WebSocket): SocketMeta {
    return (ws.deserializeAttachment() as SocketMeta | null) ?? { role: null };
  }

  private setMeta(ws: WebSocket, meta: SocketMeta): void {
    ws.serializeAttachment(meta);
  }
}
