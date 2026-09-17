/**
 * web/shared/ice.ts — FROZEN [S0]
 *
 * ★TURN 금지. TURN 을 한 줄 넣는 순간 미디어가 인터넷으로 새고
 *   원본의 '참여자 데이터 소모 0' 이 깨진다. 같은 방에 있는 사람끼리 듣는 제품이
 *   갑자기 남의 서버로 음악을 왕복시키게 된다.
 *
 * STUN 1개만 둔다 — 멀티캐스트(mDNS)가 차단된 망에서 srflx 헤어핀 폴백을 얻기 위한 보험이고,
 * 그 경우에도 미디어 자체는 LAN 을 벗어나지 않는다.
 */
import { ICE_SERVERS } from './protocol';

export const RTC_CONFIG: RTCConfiguration = {
  // protocol.ts 는 DOM 타입을 쓰지 않으려고 RTCIceServerLike 로 선언돼 있다.
  // 모양이 같으므로 여기서 한 번만 브라우저 타입으로 건넨다.
  iceServers: ICE_SERVERS as readonly RTCIceServer[] as RTCIceServer[],

  // 번들 협상. 오디오 한 트랙뿐이라 실질 효과는 없지만, 협상 경로를 하나로 고정해두면
  // 브라우저별 기본값 차이로 SDP 모양이 갈리는 걸 막는다.
  bundlePolicy: 'max-bundle',
  rtcpMuxPolicy: 'require',

  // 후보를 모아두는 풀. 0 이면 offer 만들 때부터 수집을 시작해 첫 소리가 늦는다.
  iceCandidatePoolSize: 1,
};
