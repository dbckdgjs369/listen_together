/**
 * server/index.ts — FROZEN [S0]. Workers 엔트리.
 *
 * 라우팅은 네 갈래뿐이다.
 *   GET /ws/lobby      (Upgrade) → LobbyDO  — 리스너 전용
 *   GET /ws/room/:code (Upgrade) → RoomDO   — 호스트·리스너 공용
 *       /_lobby/*                → 403      — RoomDO→LobbyDO 내부 전용 경로다
 *       그 밖                     → ASSETS   — dist/ 정적 서빙
 *
 * ★wrangler.jsonc 의 run_worker_first 에 /ws/* 와 /_lobby/* 가 들어 있어야 한다.
 *   빠지면 SPA 폴백이 업그레이드 요청을 삼켜서 시그널링이 index.html 을 200 으로 받는다.
 *   소켓이 '안 열리는' 게 아니라 '열렸는데 HTML 이 오는' 상태라 제일 찾기 어렵다.
 */
import {
  CLOSE,
  LOBBY_PATH,
  ROOM_PATH_PREFIX,
  isWellFormedCode,
  normalizeCode,
} from '../web/shared/protocol';
import { HDR_LOBBY, HDR_ORIGIN, lobbyDoName } from './lobby-key';
import type { Env } from './env';

function isUpgrade(request: Request): boolean {
  return (request.headers.get('Upgrade') ?? '').toLowerCase() === 'websocket';
}

/**
 * DO 를 깨우지 않고 Worker 가 직접 소켓을 열어 에러 한 프레임만 주고 닫는다.
 * 쓰레기 코드로 DO 인스턴스를 만들지 않기 위한 방어다 —
 * 리스너 입장에선 정상 room-not-found 와 구분되지 않으며, 그건 의도된 동일 취급이다.
 */
function rejectSocket(code: string, msg: string, closeCode: number): Response {
  const pair = new WebSocketPair();
  const client = pair[0];
  const server = pair[1];
  server.accept();
  server.send(JSON.stringify({ t: 'error', code, msg }));
  server.close(closeCode, code);
  return new Response(null, { status: 101, webSocket: client });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;

    /* ── 내부 전용 경로 ─────────────────────────────────────────── */
    // RoomDO → LobbyDO 는 DO 스텁 fetch 로 직접 간다. 바깥에서 들어온 건 전부 거절이다.
    if (path.startsWith('/_lobby/')) {
      return new Response('not found', { status: 404 });
    }

    /* ── 로비 ───────────────────────────────────────────────────── */
    if (path === LOBBY_PATH) {
      if (!isUpgrade(request)) return new Response('expected websocket', { status: 426 });

      const name = lobbyDoName(request.headers.get('CF-Connecting-IP'), url.searchParams.get('lobby'));
      const stub = env.LOBBY.get(env.LOBBY.idFromName(name));
      return stub.fetch(request);
    }

    /* ── 룸 ─────────────────────────────────────────────────────── */
    if (path.startsWith(ROOM_PATH_PREFIX)) {
      if (!isUpgrade(request)) return new Response('expected websocket', { status: 426 });

      const raw = decodeURIComponent(path.slice(ROOM_PATH_PREFIX.length));
      const code = normalizeCode(raw);

      // ★형식 불량은 DO 를 깨우지 않고 여기서 끝낸다.
      if (!isWellFormedCode(code)) {
        return rejectSocket('room-not-found', '주소를 찾을 수 없습니다', CLOSE.ROOM_CLOSED);
      }

      const lobby = lobbyDoName(request.headers.get('CF-Connecting-IP'), url.searchParams.get('lobby'));
      const stub = env.ROOM.get(env.ROOM.idFromName(code!));

      // 원본 요청을 그대로 넘기면 안 된다 — 헤더를 못 붙인다. 새 Request 로 감싼다.
      const fwd = new Request(request.url, request);
      fwd.headers.set(HDR_LOBBY, lobby);
      fwd.headers.set(HDR_ORIGIN, url.origin);
      return stub.fetch(fwd);
    }

    /* ── 정적 자산 ──────────────────────────────────────────────── */
    return env.ASSETS.fetch(request);
  },
} satisfies ExportedHandler<Env>;

export { LobbyDO } from './lobby-do';
export { RoomDO } from './room-do';
