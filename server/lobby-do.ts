/**
 * server/lobby-do.ts — FROZEN [S0]
 *
 * 원본 앱의 UDP 비콘 수신 스레드(:7981)를 그대로 옮긴 것이다.
 * 리스너만 붙는다 — **호스트 WebSocket 을 절대 받지 않는다.** 호스트는 RoomDO 에만 붙고,
 * RoomDO 가 DO-to-DO fetch 로 여기에 announce/offline 을 중계한다.
 *
 * 상태는 Map<roomId, HostEntry> 하나뿐이다.
 *
 * ── 하이버네이션과 상태 보존 ──────────────────────────────────────────
 * hosts 가 비면 알람을 다시 걸지 않으므로 DO 는 잠들 수 있고, 그때 인메모리 상태는 사라진다.
 * 그래서 hosts 는 **값이 실제로 바뀔 때만** storage 에 쓴다(= 브로드캐스트하는 그 순간).
 *
 * lastSeen 만은 storage 에 넣지 않는다. 1초마다 바뀌는 값이라 매번 쓰면 의미 없는 쓰기가 되고,
 * 복구 시점에 '지금 막 봤다' 로 초기화하는 편이 오히려 옳다 — 깨어나자마자 TTL 로
 * 멀쩡한 방을 죽이면 목록이 깜빡인다.
 *
 * 이 처리가 없으면 DO 가 재시작할 때마다 이미 목록에 있는 방에 host-online 이 다시 나가서
 * 클라이언트 목록에 같은 방이 두 줄로 쌓인다(§7 '맨 뒤에 추가' 규칙 때문에).
 */
import {
  HEARTBEAT_MS,
  HOST_TTL_MS,
  INTERNAL_ANNOUNCE_PATH,
  INTERNAL_OFFLINE_PATH,
  PING_FRAME,
  PONG_FRAME,
  PROTOCOL_V,
  decode,
} from '../web/shared/protocol';
import type {
  HostEntry,
  InternalAnnounce,
  InternalOffline,
  LobbyServerMsg,
} from '../web/shared/protocol';
import type { Env } from './env';

interface SocketMeta {
  /** 첫 프레임(lobby-hello)을 통과했는가. 통과 전에는 아무것도 보내지 않는다. */
  hello: boolean;
}

const STORAGE_KEY = 'hosts';

export class LobbyDO {
  private hosts = new Map<string, HostEntry>();
  private lastSeen = new Map<string, number>();

  constructor(
    private readonly state: DurableObjectState,
    _env: Env,
  ) {
    this.state.blockConcurrencyWhile(async () => {
      const saved = await this.state.storage.get<HostEntry[]>(STORAGE_KEY);
      if (saved) {
        const now = Date.now();
        for (const h of saved) {
          this.hosts.set(h.roomId, h);
          // 깨어난 직후에 TTL 로 죽이지 않는다. 진짜로 죽었다면 다음 스윕에서 걸린다.
          this.lastSeen.set(h.roomId, now);
        }
      }
    });

    // keepalive 는 DO 를 깨우지 않고 엣지에서 처리된다. 바이트가 정확히 일치해야 매칭된다.
    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING_FRAME, PONG_FRAME));
  }

  /* ════════════════════════════════════════════════════════════════
   * 진입점
   * ══════════════════════════════════════════════════════════════ */

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === INTERNAL_ANNOUNCE_PATH) {
      return this.onAnnounce((await request.json()) as InternalAnnounce);
    }
    if (url.pathname === INTERNAL_OFFLINE_PATH) {
      return this.onOffline((await request.json()) as InternalOffline);
    }

    if ((request.headers.get('Upgrade') ?? '').toLowerCase() !== 'websocket') {
      return new Response('expected websocket', { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    this.state.acceptWebSocket(server);
    this.setMeta(server, { hello: false });
    return new Response(null, { status: 101, webSocket: client });
  }

  /* ════════════════════════════════════════════════════════════════
   * WebSocket — 리스너 전용
   * ══════════════════════════════════════════════════════════════ */

  webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): void {
    const msg = decode(typeof raw === 'string' ? raw : new TextDecoder().decode(raw));
    if (!msg) return this.fail(ws, 'bad-message', 'JSON 을 읽을 수 없습니다', 4005);

    const meta = this.getMeta(ws);

    // ★첫 프레임 강제. lobby-hello 가 아니면 소켓을 닫는다.
    if (!meta.hello) {
      if (msg.t !== 'lobby-hello') {
        return this.fail(ws, 'bad-message', '첫 프레임은 lobby-hello 여야 합니다', 4005);
      }
      if (msg['v'] !== PROTOCOL_V) {
        return this.fail(ws, 'bad-version', '프로토콜 버전이 다릅니다', 4004);
      }
      this.setMeta(ws, { hello: true });

      // selfRoomId 는 서버 측 필터 '힌트' 일 뿐이다. 최종 필터 책임은 클라이언트에 있다 —
      // 호스트가 로비 소켓보다 늦게 방을 열 수 있기 때문에 서버가 단정할 수 없다.
      const self = typeof msg['selfRoomId'] === 'string' ? msg['selfRoomId'] : null;

      // 스냅샷은 정확히 1회. **since 오름차순 보장은 서버 책임이다**
      // (원본 LinkedHashMap 의 '발견 순서 유지' 를 여기서 재현한다).
      const hosts = [...this.hosts.values()]
        .filter((h) => h.roomId !== self)
        .sort((a, b) => a.since - b.since);

      this.send(ws, { t: 'hosts', hosts, now: Date.now() });
      return;
    }

    // ping 은 autoResponse 가 처리하므로 여기까지 오지 않는 게 정상이다.
    // 모르는 t 는 조용히 무시한다(전방 호환).
  }

  webSocketClose(): void {
    // 리스너가 빠지는 건 로비 상태에 아무 영향이 없다. hosts 는 호스트 하트비트만이 바꾼다.
  }

  webSocketError(): void {
    // close 가 뒤따른다. 여기서 할 일이 없다.
  }

  /* ════════════════════════════════════════════════════════════════
   * RoomDO → LobbyDO 내부 fetch
   * ══════════════════════════════════════════════════════════════ */

  private async onAnnounce(a: InternalAnnounce): Promise<Response> {
    const now = Date.now();
    const prev = this.hosts.get(a.roomId);
    this.lastSeen.set(a.roomId, now);

    if (!prev) {
      const entry: HostEntry = {
        roomId: a.roomId,
        deviceName: a.deviceName,
        listeners: a.listeners,
        since: now,
        ...(a.sourceReady === undefined ? {} : { sourceReady: a.sourceReady }),
      };
      this.hosts.set(a.roomId, entry);
      await this.persist();
      this.broadcast({ t: 'host-online', host: entry });
      await this.ensureAlarm();
      return new Response(null, { status: 204 });
    }

    // ★값이 실제로 바뀌었을 때만 브로드캐스트한다.
    // 안 그러면 1초마다 아무 내용 없는 host-update 가 모든 리스너에게 나간다.
    const changed =
      prev.listeners !== a.listeners ||
      prev.deviceName !== a.deviceName ||
      (a.sourceReady !== undefined && prev.sourceReady !== a.sourceReady);

    if (!changed) {
      // stale 표시만 풀어준다(offline 후 다시 살아난 경우).
      if (prev.stale) {
        delete prev.stale;
        await this.persist();
      }
      return new Response(null, { status: 204 });
    }

    prev.listeners = a.listeners;
    prev.deviceName = a.deviceName;
    if (a.sourceReady !== undefined) prev.sourceReady = a.sourceReady;
    delete prev.stale;
    await this.persist();

    this.broadcast({
      t: 'host-update',
      roomId: prev.roomId,
      listeners: prev.listeners,
      deviceName: prev.deviceName,
      ...(prev.sourceReady === undefined ? {} : { sourceReady: prev.sourceReady }),
    });
    await this.ensureAlarm();
    return new Response(null, { status: 204 });
  }

  private async onOffline(o: InternalOffline): Promise<Response> {
    if (!this.hosts.has(o.roomId)) return new Response(null, { status: 204 });

    // ★TTL 만료를 기다리지 않고 **즉시** 알린다.
    // 원본이 죽은 호스트를 목록에 남겨두던 문제(= 탭해도 안 붙는 유령 항목)의 교정이다.
    this.hosts.delete(o.roomId);
    this.lastSeen.delete(o.roomId);
    await this.persist();
    this.broadcast({ t: 'host-offline', roomId: o.roomId, reason: o.reason });
    return new Response(null, { status: 204 });
  }

  /* ════════════════════════════════════════════════════════════════
   * TTL 스윕
   * ══════════════════════════════════════════════════════════════ */

  async alarm(): Promise<void> {
    const now = Date.now();
    let removed = false;

    for (const [roomId, seen] of this.lastSeen) {
      if (now - seen > HOST_TTL_MS) {
        this.hosts.delete(roomId);
        this.lastSeen.delete(roomId);
        this.broadcast({ t: 'host-offline', roomId, reason: 'timeout' });
        removed = true;
      }
    }
    if (removed) await this.persist();

    // ★hosts 가 비면 알람을 다시 걸지 않는다 — 그래야 DO 가 잠든다.
    if (this.hosts.size > 0) await this.state.storage.setAlarm(now + HEARTBEAT_MS);
  }

  private async ensureAlarm(): Promise<void> {
    if (this.hosts.size === 0) return;
    const existing = await this.state.storage.getAlarm();
    if (existing === null) await this.state.storage.setAlarm(Date.now() + HEARTBEAT_MS);
  }

  /* ════════════════════════════════════════════════════════════════
   * 유틸
   * ══════════════════════════════════════════════════════════════ */

  private async persist(): Promise<void> {
    await this.state.storage.put(STORAGE_KEY, [...this.hosts.values()]);
  }

  private broadcast(msg: LobbyServerMsg): void {
    const body = JSON.stringify(msg);
    for (const ws of this.state.getWebSockets()) {
      // hello 를 아직 안 보낸 소켓에는 아무것도 주지 않는다.
      if (!this.getMeta(ws).hello) continue;
      try {
        ws.send(body);
      } catch {
        // 이미 닫힌 소켓. close 핸들러가 정리한다.
      }
    }
  }

  private send(ws: WebSocket, msg: LobbyServerMsg): void {
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
    return (ws.deserializeAttachment() as SocketMeta | null) ?? { hello: false };
  }

  private setMeta(ws: WebSocket, meta: SocketMeta): void {
    ws.serializeAttachment(meta);
  }
}
