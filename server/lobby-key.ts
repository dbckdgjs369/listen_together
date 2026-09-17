/**
 * server/lobby-key.ts — FROZEN [S0]
 *
 * ★전역 로비는 없다.
 *
 * 원본 앱의 자동 발견은 UDP 브로드캐스트였고, 그건 **구조적으로 같은 서브넷에만** 닿았다.
 * 웹으로 옮기면서 그 성질이 사라지면 "친구가 공유를 시작하면 자동으로 뜹니다" 가
 * "모르는 사람 방이 뜹니다" 로 변질된다. 그래서 접속자 IP 대역으로 로비를 쪼갠다.
 *
 * ── 계약서 모순 해소 ─────────────────────────────────────────────────
 * CONTRACT.md §4.1 은 "CF-Connecting-IP 를 해시해 idFromName(lobbyKey)" 라고 하고,
 * protocol.ts 의 LOBBY_DO_NAME 주석은 "LobbyDO 싱글턴의 idFromName 키" 라고 한다.
 * 문자 그대로는 둘이 충돌한다.
 *
 * 둘 다 살리는 읽기로 해소했다 — LOBBY_DO_NAME 은 **버전 접두사**이고,
 * 실제 이름은 `${LOBBY_DO_NAME}:${버킷}` 이다. 그러면
 *   · protocol.ts 의 의도(= v 를 박아둬서 프로토콜 교체 시 로비가 통째로 갈린다)
 *   · §4.1 의 의도(= 같은 NAT 뒤 = 같은 로비)
 * 가 동시에 성립한다. §5.1 이 '이 도출 규칙을 명문화하라' 고 지시한 자리가 여기다.
 */
import { LOBBY_DO_NAME } from '../web/shared/protocol';

/* ──────────────────────────────────────────────────────────────────────
 * Worker → DO 컨텍스트 전달 헤더
 *
 * DO 는 접속자 IP 도 요청 origin 도 스스로 알 수 없다. Worker 가 업그레이드 시점에
 * 계산해 헤더로 넘긴다. 이 두 상수가 index.ts 가 아니라 여기 있는 이유는 순환 import 때문이다 —
 * index.ts 는 DO 클래스를 re-export 하고 room-do.ts 는 이 상수가 필요하므로,
 * index.ts 에 두면 index ↔ room-do 가 서로를 import 하게 된다.
 *
 * 지금 코드로는 (상수를 메서드 안에서만 읽어서) 우연히 동작하지만,
 * 누군가 모듈 스코프나 클래스 필드 초기화에서 쓰는 순간 TDZ 로 조용히 깨진다.
 * 동결 스캐폴드에 그런 함정을 남기지 않으려고 의존 방향을 한쪽으로 폈다.
 * ──────────────────────────────────────────────────────────────────── */

/** RoomDO 가 어느 LobbyDO 로 announce/offline 을 중계할지. */
export const HDR_LOBBY = 'X-LT-Lobby';
/** joinUrl 용 origin. 상수로 박으면 env a/b/canonical 3슬롯에서 QR·참여링크가 전부 틀어진다. */
export const HDR_ORIGIN = 'X-LT-Origin';

/**
 * IP → 버킷 문자열.
 *   IPv4 는 /24 (192.168.219.51 → 192.168.219)
 *   IPv6 는 /64 (앞 4 하이텟)
 *
 * 공유기 하나 뒤의 사람들을 같은 값으로 모으는 게 목적이다. 정확한 서브넷 마스크를
 * 알 길이 없으므로 근사이며, 그 근사가 원본 UDP 브로드캐스트의 도달 범위와 비슷하다.
 */
export function ipBucket(ip: string | null): string {
  if (!ip) return 'unknown';
  const s = ip.trim();
  if (!s) return 'unknown';

  if (s.includes(':')) {
    // IPv6. ':: ' 축약이 섞여 있어도 앞에서부터 4조각이면 /64 근사로 충분하다.
    const parts = s.split(':').filter((p) => p.length > 0);
    return parts.slice(0, 4).join(':').toLowerCase();
  }

  const octets = s.split('.');
  if (octets.length === 4) return octets.slice(0, 3).join('.');

  return s.toLowerCase();
}

/**
 * 버킷 문자열을 짧고 안정적인 이름으로 접는다(FNV-1a 32비트).
 *
 * 익명화 장치가 아니다 — /24 는 경우의 수가 2^24 뿐이라 되돌릴 수 있다.
 * 목적은 오직 'DO 이름을 짧고 안정적으로 만드는 것' 이고, 그 이상을 기대하지 않는다.
 * 원본 IP 를 어디에도 저장하지 않는다는 점만 지킨다.
 */
function fold(s: string): string {
  let h = 0x811c9dc5;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193) >>> 0;
  }
  return h.toString(36);
}

/**
 * 최종 LobbyDO 이름.
 *
 * @param ip       CF-Connecting-IP (없으면 null)
 * @param override `?lobby=` 파라미터. 개발·시연 격리용 — A 는 `lobby=a`, B 는 `lobby=b`.
 *                 이게 없으면 두 사람이 같은 서브넷에서 개발할 때 서로의 방이 목록에 뜬다.
 */
export function lobbyDoName(ip: string | null, override?: string | null): string {
  const raw = override?.trim();
  if (raw) return `${LOBBY_DO_NAME}:ov:${raw.slice(0, 64)}`;
  return `${LOBBY_DO_NAME}:${fold(ipBucket(ip))}`;
}
