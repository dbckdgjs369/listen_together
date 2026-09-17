/** server/env.d.ts — FROZEN [S0]. Worker 바인딩 타입만. (.d.ts 라 값은 넣지 않는다) */

export interface Env {
  /** LobbyDO 네임스페이스. idFromName(lobbyDoName(...)) 으로 서브넷별 버킷을 잡는다. */
  LOBBY: DurableObjectNamespace;
  /** RoomDO 네임스페이스. idFromName(normalizeCode(code)) — 방코드가 곧 이름이다. */
  ROOM: DurableObjectNamespace;
  /** wrangler assets 바인딩. dist/ 를 서빙한다. */
  ASSETS: Fetcher;
}
