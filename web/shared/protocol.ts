/**
 * web/shared/protocol.ts — "같이 듣기(listen-together)" 웹 포팅판 시그널링 프로토콜 **v1**
 *
 * 이 파일은 A(호스트 사이드)와 B(리스너 사이드) 사이의 **유일한 계약**이다.
 * Step 0 킥오프에서 동결하며, 필수 필드 추가·의미 변경은 양자 합의 + PROTOCOL_V 증가로만 한다.
 * (optional 필드 추가와 새 error code 추가는 v 증가 없이 허용 — 파일 하단 "변경 규칙" 주석 참조)
 *
 * ────────────────────────────────────────────────────────────────────────
 * 접속 토폴로지 (Durable Object 경계가 강제한 구조 — 여기서부터 읽을 것)
 * ────────────────────────────────────────────────────────────────────────
 * Hibernation WebSocket은 **DO 인스턴스 1개에 귀속**된다. 소켓 하나가 LobbyDO → RoomDO로
 * 이동할 수 없다. 그래서 채널을 물리적으로 둘로 나눈다.
 *
 *   리스너 = 소켓 2개
 *     (1) 로비 WS  GET /ws/lobby          → LobbyDO(싱글턴). 페이지가 열려 있는 내내 유지.
 *                                           갈아타기·나가기에도 **절대 닫지 않는다**.
 *                                           UDP 비콘(:7981) 수신의 직계 대체.
 *     (2) 룸 WS    GET /ws/room/:code     → RoomDO(방코드별). join 시 개설.
 *                                           갈아타기 = 옛 룸 WS를 close(4000)하고 새 코드로 새 소켓.
 *                                           나가기   = 룸 WS만 close(4001). 로비 WS는 그대로.
 *
 *   호스트 = 소켓 1개
 *     (2) 룸 WS    GET /ws/room/:code     → RoomDO. 호스트는 **LobbyDO에 직접 붙지 않는다.**
 *                                           host-announce(1초 하트비트)를 이 룸 WS로 보내면
 *                                           RoomDO가 DO-to-DO fetch로 LobbyDO에 중계한다.
 *
 *   한 사람이 "내가 틀기"와 "같이 듣기"를 동시에 쓰면 소켓 3개(로비 1 + 호스트 룸 1 + 리스너 룸 1)가 된다.
 *   이때 자기 방이 자기 발견 목록에 뜨는 것은 클라이언트가 걸러낸다(앱 isMine()의 대체).
 *
 *   DO 간 통신: RoomDO → LobbyDO 단방향 fetch 2종 (INTERNAL_ANNOUNCE_PATH / INTERNAL_OFFLINE_PATH).
 *   LobbyDO는 호스트 소켓을 하나도 갖지 않으며, 리스너 소켓만 들고 있다.
 */

/* ══════════════════════════════════════════════════════════════════════
 * 1. 버전과 타이밍 상수
 *    값은 전부 원본 안드로이드 앱의 실측/코드에서 가져왔다. 왜 이 값인지 각 줄에 남긴다.
 * ══════════════════════════════════════════════════════════════════════ */

/** 프로토콜 버전. 첫 프레임의 v가 다르면 서버가 error(bad-version) 후 close(4004). */
export const PROTOCOL_V = 1 as const;

/**
 * 호스트 프레즌스 하트비트 주기(ms).
 * 원본 CaptureService의 UDP 비콘이 정확히 1초 주기로 "LT1|모델명|IP"를 뿌렸다.
 * 그 주기를 그대로 가져와야 "친구가 공유를 시작하면 아래에 자동으로 뜹니다" 체감이 같아진다.
 */
export const HEARTBEAT_MS = 1000;

/**
 * 로비가 호스트를 살아있다고 보는 한계(ms). 3초 = 하트비트 3회 연속 누락.
 * 원본 MainActivity의 비콘 수신 소켓 타임아웃이 1.5초(= 비콘 1회 + 여유)였다.
 * 웹은 Wi-Fi 브로드캐스트가 아니라 WS라 유실이 드물지만, 브라우저 백그라운드 스로틀로
 * 하트비트가 밀리는 일이 있어 1.5초는 너무 예민하다. 3회 여유로 잡는다.
 */
export const HOST_TTL_MS = 3000;

/**
 * host-offline 이후 목록에서 완전히 지우기까지의 유예(ms).
 * 원본은 호스트가 꺼져도 목록에서 **지우지 않았다**(hosts LinkedHashMap에 남음).
 * 그대로 두면 죽은 방을 계속 탭하게 되므로, 10초 동안 회색 비활성으로 두었다가 제거한다.
 * 10초는 호스트 새로고침(ROOM_GRACE_MS 60초) 중 잠깐 끊긴 경우를 목록에 남겨두기 위한 값이 아니라,
 * "방금 사라진 게 보이긴 해야 한다"는 최소 가시 시간이다.
 */
export const LOBBY_FORGET_MS = 10_000;

/**
 * 상태 화면 갱신 주기(ms).
 * 원본 MainActivity가 joinTicker로 0.5초마다 PlayerService.currentHost/stateText를 비췄다.
 * README의 최대 교훈("조용한 재시도 금지")을 지탱하는 값이라 그대로 유지한다.
 */
export const POLL_MS = 500;

/**
 * 재시도 1회차 지연(ms). 원본 PlayerService는 실패 시 1000ms sleep 후 무한 재시도했다.
 */
export const RETRY_BASE_MS = 1000;

/**
 * 재시도 백오프 상한(ms). 원본 브라우저 참여 페이지가 min(1000×tries, 5000)의 **선형** 백오프를 썼다.
 * 지수 백오프를 쓰지 않는다 — "여행 중 재접속은 빠를수록 좋다"는 원본 판단을 그대로 계승한다.
 */
export const BACKOFF_MAX_MS = 5000;

/** 선형 백오프 계산. tries는 1부터. 원본: a.src 재시도 지연과 동일 식. */
export function backoffMs(tries: number): number {
  return Math.min(RETRY_BASE_MS * Math.max(1, tries), BACKOFF_MAX_MS);
}

/**
 * 재생 정지 감시견 임계(ms).
 * 원본 브라우저 페이지: 1초 인터벌로 currentTime이 4000ms 동안 안 늘면 "멈춤 감지 — 재접속".
 * 웹 포팅판은 currentTime 대신 getStats(inbound-rtp).packetsReceived 정체로 판정한다.
 */
export const WATCHDOG_MS = 4000;

/**
 * 연결 타임아웃(ms). 원본 PlayerService의 Socket.connect(4000ms)와 같은 값.
 * 웹에서는 "룸 WS open 또는 RTCPeerConnection connectionState=connected까지"의 제한으로 쓴다.
 */
export const CONNECT_TIMEOUT_MS = 4000;

/**
 * 리스너 진단 리포트 주기(ms). 원본 AUDIO/PLAY 로그가 1초 창이었다(gap≈1000ms 기준 산식).
 * 호스트 대시보드가 같은 1초 창으로 파생치를 계산하려면 리포트도 1초여야 한다.
 */
export const STATS_MS = 1000;

/**
 * 방 하나당 최대 리스너 수.
 * 원본 앱은 TCP 클라이언트 수 제한이 없었지만(README 실측은 2명), 웹은 호스트 1명이
 * RTCPeerConnection N개를 직접 들고 Opus를 N회 인코딩하는 mesh라 4가 현실적 상한이다.
 * (기획서 "친구 2~4명"과도 일치)
 */
export const MAX_LISTENERS = 4;

/**
 * 호스트 WS가 끊긴 뒤 방을 살려두는 유예(ms).
 * 호스트가 새로고침하면 방코드가 바뀌면 안 된다 — 이미 뿌린 QR·참여 링크가 전부 죽는다.
 * 60초면 새로고침·탭 크래시 복구·앱 전환 왕복을 전부 덮는다.
 * 이 유예 동안 기존 리스너는 룸 WS를 **닫지 않고** 대기하다가, 호스트가 hostToken으로
 * 재점유하면 서버가 peer-joined를 다시 쏴줘서 자동으로 다시 붙는다.
 */
export const ROOM_GRACE_MS = 60_000;

/**
 * hostToken 로컬 보관 유효기간(ms). 유예(60초)보다 훨씬 길게 잡아야
 * "브라우저를 닫았다 몇 분 뒤 다시 열어도 같은 코드"가 성립한다.
 */
export const HOST_TOKEN_TTL_MS = 6 * 60 * 60 * 1000;

/**
 * WS keepalive 주기(ms). Cloudflare 엣지 idle 타임아웃(약 100초)보다 충분히 짧게.
 * 서버는 setWebSocketAutoResponse로 응답하므로 DO를 깨우지 않는다(하이버네이션 유지).
 */
export const WS_PING_MS = 25_000;

/**
 * 오디오 컨텍스트 샘플레이트. 호스트·리스너 **양쪽 모두** 이 값으로 연다.
 *
 *   new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' })
 *
 * 열고 나서 `ctx.sampleRate`로 **실제 잡힌 값을 반드시 재확인**하고, 다르면 경고 배너를 띄운다.
 * 기본 생성자로 열면 기기에 따라 44100이 잡히는데, Opus는 내부적으로 48k 고정이라
 * 양쪽에 리샘플러가 하나씩 끼고 README가 없앴던 그 46ms가 그대로 돌아온다.
 *
 * 이건 가정이 아니라 측정값이다 — 맥 Chrome의 탭 오디오 캡처(getDisplayMedia)는
 * 트랙 설정이 실제로 `sampleRate: 44100`으로 잡힌다. 즉 이 상수를 안 쓰면 기본 경로가 곧 오작동이다.
 */
export const SAMPLE_RATE = 48000;

/**
 * Opus 패킷 1개의 시간 길이(ms). 리스너 수신율(net%) 기대치 계산의 분모.
 * 원본 CaptureService의 CHUNK_MS(20ms)와 우연이 아니라 정확히 같다 —
 * 앱의 "20ms 청크"와 웹의 "Opus 20ms 프레임"이 같은 입자라 진단 산식을 그대로 옮길 수 있다.
 */
export const OPUS_FRAME_MS = 20;

/**
 * 수신 지터버퍼 목표(ms). README의 "버퍼는 언더런 0을 유지하는 선까지만 줄인다" 원칙을
 * 웹으로 옮긴 출발값. RTCRtpReceiver.jitterBufferTarget은 **기능 감지 후에만** 대입한다
 * (iOS Safari 27 미만은 미지원 → 기본 적응형 NetEq에 맡긴다).
 */
export const JITTER_BUFFER_TARGET_MS = 40;

/**
 * 호스트가 answer SDP에 주입하는 opus fmtp 파라미터.
 * Chrome 기본값은 모노 ~32kbps 음성 모드라 음악이 뭉개진다. setLocalDescription 전 munging은
 * Chrome이 봉쇄 중이므로 **setRemoteDescription 직전**에만 손댄다(스펙 위반 아님).
 * 48000은 README의 "44100 → 48000 고속경로" 교훈과 같은 이유 — 리샘플링을 만들지 않는다.
 */
export const OPUS_FMTP =
  'stereo=1;sprop-stereo=1;maxaveragebitrate=256000;usedtx=0;cbr=1;maxplaybackrate=48000';

/**
 * ICE 서버. **TURN은 절대 넣지 않는다.**
 * TURN이 붙는 순간 미디어가 인터넷으로 새고 원본의 "참여자 데이터 소모 0"이 깨진다.
 * STUN 1개는 멀티캐스트(mDNS)만 차단된 망에서 srflx 헤어핀 폴백을 얻기 위한 보험이다.
 */
export const ICE_SERVERS: ReadonlyArray<RTCIceServerLike> = [
  { urls: 'stun:stun.cloudflare.com:3478' },
];

export interface RTCIceServerLike {
  urls: string | string[];
  username?: string;
  credential?: string;
}

/* ══════════════════════════════════════════════════════════════════════
 * 2. 방 코드 — 원본 normalizeHost()의 웹 대응물
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * Crockford Base32. I·L·O·U를 뺀 32자.
 * 원본은 "http://IP:7980"을 통째로 붙여넣는 사용자를 normalizeHost()로 구제했다.
 * 웹에서 같은 관대함을 주려면 사람이 손으로 옮겨 적을 때 헷갈리는 글자가 없어야 한다.
 * O↔0, I/L↔1은 normalizeCode()가 자동으로 화해시킨다(아래 LOOKALIKE).
 */
export const ROOM_CODE_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** 방 코드 길이. 4자 = 32^4 ≈ 104만. 4인 데모에서 충돌은 무시 가능하고, 서버가 room-taken으로 최종 판정한다. */
export const ROOM_CODE_LEN = 4;

/** normalizeCode가 붙잡아 두는 최대 길이. 링크를 통째로 붙여넣어도 무한정 길어지지 않게 자른다. */
export const MAX_CODE_INPUT = 12;

/** 사람이 잘못 옮겨 적기 쉬운 글자 → 알파벳 내 정답으로 화해. Crockford 규약 그대로. */
const LOOKALIKE: Readonly<Record<string, string>> = {
  O: '0',
  I: '1',
  L: '1',
  U: 'V',
};

/**
 * 새 방 코드 생성(클라이언트가 만든다). 거절 샘플링으로 균등 분포를 지킨다.
 * 서버는 이 코드를 신뢰하지 않는다 — 이미 점유된 코드면 room-taken을 돌려주고 호스트가 다시 뽑는다.
 */
export function newRoomCode(len: number = ROOM_CODE_LEN): string {
  const n = ROOM_CODE_ALPHABET.length; // 32 — 256의 약수라 사실 거절이 일어나지 않지만 규칙은 지킨다
  const limit = Math.floor(256 / n) * n;
  const out: string[] = [];
  const buf = new Uint8Array(len * 2);
  while (out.length < len) {
    crypto.getRandomValues(buf);
    for (let i = 0; i < buf.length && out.length < len; i++) {
      if (buf[i]! < limit) out.push(ROOM_CODE_ALPHABET[buf[i]! % n]!);
    }
  }
  return out.join('');
}

/**
 * 붙여넣기 입력에서 방 코드를 뽑아낸다 — 원본 PlayerService.normalizeHost()의 직계 이식.
 *
 * 원본이 해결한 문제: 알림에 뜬 "http://10.13.34.219:7980"을 그대로 입력하면
 * 그런 **이름**의 서버를 찾다가 UnknownHostException으로 조용히 실패했다(원인 찾는 데 30분).
 * 웹에서 같은 함정은 참여 링크 "https://lt.example.workers.dev/#A3F9"를 통째로 붙여넣는 것이다.
 *
 * 처리 순서(원본과 동형):
 *   1) 앞뒤 공백 제거
 *   2) "://" 이후로 잘라 스킴 제거            ← 원본 scheme 처리
 *   3) '#' 뒤 = 코드 / 없으면 ?r= ?room= ?code= / 없으면 마지막 '/' 뒤   ← 원본 path 처리
 *   4) 대문자화 + O→0, I·L→1, U→V 화해
 *   5) 알파벳에 없는 문자 전부 제거(공백·하이픈·따옴표 관대 허용)
 *   6) MAX_CODE_INPUT로 자르고, 비면 null
 *
 * **길이가 4가 아니어도 null을 돌려주지 않는다.** 엉뚱한 값이라도 서버까지 보내서
 * room-not-found("주소를 찾을 수 없음 — 재시도 중")로 눈에 보이게 실패시키는 쪽이,
 * 버튼이 아무 반응 없는 조용한 실패보다 낫다(README 교훈 3번).
 * 입력 중 힌트가 필요하면 isWellFormedCode()를 따로 쓴다.
 */
export function normalizeCode(raw: string | null | undefined): string | null {
  if (raw == null) return null;
  let s = String(raw).trim();
  if (!s) return null;

  const scheme = s.indexOf('://');
  if (scheme >= 0) s = s.slice(scheme + 3);

  const hash = s.lastIndexOf('#');
  if (hash >= 0) {
    s = s.slice(hash + 1);
  } else {
    const q = /[?&](?:r|room|code)=([^&]*)/i.exec(s);
    if (q) {
      s = q[1] ?? '';
    } else {
      // 마지막 **비어있지 않은** 경로 조각. "a3f9/" 처럼 끝에 슬래시가 붙어도 살아남아야 한다.
      const segs = s.split('/').filter((x) => x.length > 0);
      s = segs.length ? segs[segs.length - 1]! : '';
    }
  }
  // 조각에 남은 쿼리·프래그먼트 잔여물 절단. "#a3f9?x=1" → "a3f9"
  const cut = s.search(/[?&#]/);
  if (cut >= 0) s = s.slice(0, cut);

  let out = '';
  for (const ch of s.toUpperCase()) {
    const c = LOOKALIKE[ch] ?? ch;
    if (ROOM_CODE_ALPHABET.includes(c)) {
      out += c;
      if (out.length >= MAX_CODE_INPUT) break;
    }
  }
  return out.length ? out : null;
}

/** UI 힌트용 엄격 검사. join을 막는 데 쓰지 말 것(조용한 실패 금지). */
export function isWellFormedCode(code: string | null | undefined): boolean {
  if (!code || code.length !== ROOM_CODE_LEN) return false;
  for (const ch of code) if (!ROOM_CODE_ALPHABET.includes(ch)) return false;
  return true;
}

/* ══════════════════════════════════════════════════════════════════════
 * 3. 엔드포인트
 * ══════════════════════════════════════════════════════════════════════ */

/** 리스너 전용. LobbyDO 싱글턴. 페이지 수명 내내 유지한다. */
export const LOBBY_PATH = '/ws/lobby';

/** 호스트·리스너 공용. :code 별 RoomDO. */
export const ROOM_PATH_PREFIX = '/ws/room/';

/** LobbyDO 싱글턴의 idFromName 키. v를 박아두면 프로토콜 교체 시 로비가 통째로 갈린다. */
export const LOBBY_DO_NAME = `lobby-v${PROTOCOL_V}`;

/** RoomDO → LobbyDO 내부 fetch 경로(외부에 노출되지 않는다). */
export const INTERNAL_ANNOUNCE_PATH = '/_lobby/announce';
export const INTERNAL_OFFLINE_PATH = '/_lobby/offline';

export function roomWsPath(code: string): string {
  return ROOM_PATH_PREFIX + encodeURIComponent(code);
}

/** 참여 링크. 원본 status 2줄째 "내 주소: http://IP:7980"의 대체이자 QR 페이로드. */
export function joinUrl(origin: string, code: string): string {
  return `${origin.replace(/\/+$/, '')}/#${code}`;
}

/** WebSocket 절대 URL(http(s) origin → ws(s)). */
export function wsUrl(origin: string, path: string): string {
  return origin.replace(/^http/, 'ws').replace(/\/+$/, '') + path;
}

/* ══════════════════════════════════════════════════════════════════════
 * 4. 공통 타입
 * ══════════════════════════════════════════════════════════════════════ */

export type RoomId = string;
/** 서버가 발급하는 리스너 식별자. 방 안에서만 유일하면 된다(예: "p2"). */
export type PeerId = string;
/** 호스트가 방을 재점유할 때 제시하는 비밀값. 서버가 발급, 클라이언트는 localStorage 보관. */
export type HostToken = string;

export type Role = 'host' | 'listener';

/** 로비 목록 한 줄. 원본 비콘 "LT1|<Build.MODEL>|<IP>"가 담던 정보와 1:1 대응. */
export interface HostEntry {
  roomId: RoomId;
  /** 원본 Build.MODEL 자리. 리스너 화면의 "<이름> 님의 소리  (<코드>)". */
  deviceName: string;
  /** 현재 접속한 리스너 수(RoomDO가 세어 보낸 값. 호스트 자기 신고가 아니다). */
  listeners: number;
  /** 로비가 이 방을 처음 본 시각(ms). 목록 정렬 키 — 원본 LinkedHashMap의 "발견 순서 유지"를 재현. */
  since: number;
  /** 하트비트가 끊겨 회색 처리 중인지. host-offline을 받으면 true. */
  stale?: boolean;
  /**
   * 호스트가 실제로 소리를 실을 준비가 됐는지(소스 선택·재생 중).
   * false면 리스너 목록에 회색 접미 " (소리 준비 중)"를 붙인다 — §8.5.
   *
   * 이 필드가 v1에 있는 이유: 호스트가 새로고침하면 방은 hostToken으로 부활하지만
   * 파일 blob URL은 소멸한다. 방은 살아 있는데 소리가 없는 상태가 되고, 그건
   * README가 최악이라고 못 박은 "화면은 듣는 중인데 실제로는 아무것도 안 나옴"과
   * 같은 종류다. 리스너가 붙기 전에 알 수 있어야 한다.
   *
   * optional인 이유: 생산자가 서버(RoomDO→LobbyDO)라 나중에 추가하면 protocol.ts와
   * server/를 동시에 고쳐야 한다(= 동결 파기). 지금 넣어두고 값만 채운다.
   */
  sourceReady?: boolean;
}

export const ERROR_CODES = [
  /** 그 코드의 방이 없다. → 리스너 stateText "주소를 찾을 수 없음 — 재시도 중" (UnknownHostException 분기의 대체) */
  'room-not-found',
  /** 정원(MAX_LISTENERS) 초과. */
  'room-full',
  /** 이미 다른 호스트가 점유한 코드거나 hostToken 불일치. 호스트는 새 코드를 뽑아 재시도. */
  'room-taken',
  /** 첫 프레임의 v가 PROTOCOL_V와 다름. */
  'bad-version',
  /** JSON 파싱 실패, t 누락, 첫 프레임이 host-open/join/lobby-hello가 아님 등. */
  'bad-message',
  /** 호스트 전용 메시지를 리스너가 보냈다(그 반대도). */
  'not-allowed',
  /** to로 지정한 peerId가 방에 없다(이미 나감). 호스트는 해당 PC만 정리하면 된다. */
  'peer-not-found',
] as const;
export type ErrorCode = (typeof ERROR_CODES)[number];

/** 모르는 code가 오면 일반 실패로 처리한다 — 새 code 추가를 v 증가 없이 허용하기 위한 규약. */
export function isKnownErrorCode(code: string): code is ErrorCode {
  return (ERROR_CODES as readonly string[]).includes(code);
}

/** 호스트가 사라진 이유. 'stop'=명시적 공유 중지, 'gone'=탭 크래시/WS 단절. */
export type HostStoppedReason = 'stop' | 'gone';

/** 리스너가 빠진 이유. close code에서 유도하거나 leave 메시지가 명시한다. */
export type PeerLeftReason = 'leave' | 'switch' | 'gone' | 'timeout' | 'room-closed';

/** WebSocket close code(4000~4999 사용자 영역). 어느 쪽이 왜 끊었는지를 코드로 전달한다. */
export const CLOSE = {
  /** 리스너가 다른 호스트로 **갈아탄다**(나가기 없이). 서버는 peer-left reason:'switch'. */
  SWITCH: 4000,
  /** 리스너가 [나가기]를 눌렀다. 서버는 peer-left reason:'leave'. */
  LEAVE: 4001,
  /** 서버가 방을 닫았다(유예 만료 또는 명시 중지). */
  ROOM_CLOSED: 4002,
  /** 같은 hostToken을 가진 새 호스트 소켓이 들어와 이 소켓이 교체됐다(새로고침 경합). */
  REPLACED: 4003,
  /** 버전 불일치. */
  BAD_VERSION: 4004,
  /** 프로토콜 위반. */
  BAD_MESSAGE: 4005,
} as const;
export type CloseCode = (typeof CLOSE)[keyof typeof CLOSE];

/** 표준 SDP/ICE 형태만 쓴다(구조화 clone 가능한 평범한 객체). */
export interface SdpPayload {
  type: 'offer' | 'answer';
  sdp: string;
}
export interface IcePayload {
  candidate: string;
  sdpMid: string | null;
  sdpMLineIndex: number | null;
  usernameFragment?: string | null;
}

/* ══════════════════════════════════════════════════════════════════════
 * 5. 로비 채널 — GET /ws/lobby (LobbyDO). 리스너만 접속한다.
 *    UDP 비콘(:7981) 자동 발견의 직계 대체.
 * ══════════════════════════════════════════════════════════════════════ */

/** [C→S] 소켓의 **첫 프레임**. 이게 아니면 서버가 bad-message로 닫는다. */
export interface LobbyHelloMsg {
  t: 'lobby-hello';
  v: typeof PROTOCOL_V;
  /** 자기 방을 목록에서 빼기 위한 힌트(선택). 없으면 클라이언트가 직접 거른다. */
  selfRoomId?: RoomId | null;
}

/** [C→S] keepalive. 서버는 autoResponse로 즉답하며 DO를 깨우지 않는다. */
export interface LobbyPingMsg {
  t: 'ping';
}

export type LobbyClientMsg = LobbyHelloMsg | LobbyPingMsg;

/** [S→C] lobby-hello 직후 1회. 현재 살아있는 방 전체 스냅샷(since 오름차순). */
export interface HostsMsg {
  t: 'hosts';
  hosts: HostEntry[];
  /** 서버 시각(ms). 클라이언트 시계 오차 보정용. */
  now: number;
}

/** [S→C] 새 방이 처음 보였다. 목록 **맨 뒤에 추가**(발견 순서 유지). */
export interface HostOnlineMsg {
  t: 'host-online';
  host: HostEntry;
}

/** [S→C] 이미 있는 방의 listeners/deviceName/sourceReady만 바뀌었다. 순서는 건드리지 않고 라벨만 갱신. */
export interface HostUpdateMsg {
  t: 'host-update';
  roomId: RoomId;
  listeners: number;
  deviceName?: string;
  /** 값이 **실제로 바뀌었을 때만** 싣는다. 매 하트비트마다 보내면 1초짜리 무의미한 브로드캐스트가 된다. */
  sourceReady?: boolean;
}

/**
 * [S→C] 방이 사라졌다. 즉시 회색 비활성 처리하고 LOBBY_FORGET_MS 뒤 목록에서 제거.
 * reason 'timeout' = 하트비트 HOST_TTL_MS 미수신, 'stop' = 명시적 공유 중지, 'gone' = 호스트 WS 단절.
 */
export interface HostOfflineMsg {
  t: 'host-offline';
  roomId: RoomId;
  reason: 'timeout' | HostStoppedReason;
}

export interface LobbyPongMsg {
  t: 'pong';
}

export interface ErrorMsg {
  t: 'error';
  code: ErrorCode | string;
  /** 사람이 읽는 한국어 문구. UI는 이 문자열을 그대로 쓰지 않고 code로 분기한다. */
  msg: string;
}

export type LobbyServerMsg =
  | HostsMsg
  | HostOnlineMsg
  | HostUpdateMsg
  | HostOfflineMsg
  | LobbyPongMsg
  | ErrorMsg;

/* ══════════════════════════════════════════════════════════════════════
 * 6. 룸 채널 — GET /ws/room/:code (RoomDO). 호스트 1 + 리스너 최대 4.
 * ══════════════════════════════════════════════════════════════════════ */

/**
 * [호스트→S] 룸 소켓의 **첫 프레임**. 생성과 재점유를 겸한다(v0의 create-room을 대체).
 *
 * hostToken 없음        → 그 코드가 비어 있으면 새로 만든다. 점유 중이면 error(room-taken).
 * hostToken 있고 일치    → **재점유**. 같은 코드·같은 토큰 유지, 유예 알람 취소,
 *                          살아남은 리스너마다 peer-joined를 다시 보낸다(자동 재연결).
 * hostToken 있고 불일치  → error(room-taken).
 * hostToken 있으나 방 없음(유예 만료) → 그 코드로 새로 만들고 새 토큰 발급(QR은 계속 유효).
 */
export interface HostOpenMsg {
  t: 'host-open';
  v: typeof PROTOCOL_V;
  /** 원본 Build.MODEL 자리. 리스너 목록에 뜨는 이름. */
  deviceName: string;
  /** localStorage에 남아 있던 토큰(재점유 시도). */
  hostToken?: HostToken;
}

/** [호스트→S] HEARTBEAT_MS 주기. RoomDO가 LobbyDO로 중계한다. 비콘의 직계 대체. */
export interface HostAnnounceMsg {
  t: 'host-announce';
  /** 이름을 바꿨을 때만. 보내지 않으면 host-open 때 값을 유지한다. */
  deviceName?: string;
  /**
   * 지금 소리를 실을 수 있는 상태인가(소스가 선택됐고 재생 중인가).
   * RoomDO가 방 상태에 보관하고 InternalAnnounce로 그대로 중계한다.
   * 매 하트비트에 실어도 되고, LobbyDO가 변화분만 host-update로 내보낸다.
   */
  sourceReady?: boolean;
}

/** [호스트→S] 명시적 "공유 중지". 유예 없이 방을 즉시 닫는다. */
export interface StopShareMsg {
  t: 'stop-share';
}

/** [호스트→S] 특정 리스너에게 보낼 offer. 서버는 to를 벗기고 그 리스너에게만 전달. */
export interface HostOfferMsg {
  t: 'offer';
  to: PeerId;
  sdp: SdpPayload;
}

/** [양방향] ICE 후보. candidate:null = end-of-candidates(그대로 전달해야 ICE가 빨리 끝난다). */
export interface HostIceMsg {
  t: 'ice';
  to: PeerId;
  candidate: IcePayload | null;
}

export interface RoomPingMsg {
  t: 'ping';
}

/** [리스너→S] 룸 소켓의 **첫 프레임**. */
export interface JoinMsg {
  t: 'join';
  v: typeof PROTOCOL_V;
  deviceName: string;
}

/** [리스너→S] 호스트 offer에 대한 answer. 서버가 from:peerId를 찍어 호스트로 전달. */
export interface ListenerAnswerMsg {
  t: 'answer';
  sdp: SdpPayload;
}

export interface ListenerIceMsg {
  t: 'ice';
  candidate: IcePayload | null;
}

/**
 * [리스너→S] 명시적 이탈. 보내지 않고 소켓만 닫아도 서버가 close code로 이유를 유도한다.
 * 갈아타기는 reason:'switch'로 보내고 곧바로 close(CLOSE.SWITCH).
 */
export interface LeaveMsg {
  t: 'leave';
  reason?: Extract<PeerLeftReason, 'leave' | 'switch'>;
}

/**
 * [리스너→S→호스트] STATS_MS 주기 진단 리포트.
 *
 * **전부 원시 누적 카운터다.** netPct 같은 파생치를 리스너가 계산하지 않는다 —
 * 기대 패킷 수는 호스트의 outbound-rtp만이 알기 때문이다(리스너는 분모를 모른다).
 * 파생은 호스트가 deriveListenerStats()로 자기 송신 카운터와 대조해서 한다.
 *
 * 서버는 이 메시지를 **불투명 릴레이**한다: from만 찍고 나머지 필드는 읽지도 검증하지도 않는다.
 * 따라서 optional 필드 추가는 서버·프로토콜 버전 변경 없이 가능하다.
 */
export interface StatsMsg {
  t: 'stats';
  /** RTCInboundRtpStreamStats.packetsReceived (누적) */
  packetsReceived: number;
  /** RTCInboundRtpStreamStats.jitterBufferDelay (누적 초) */
  jitterBufferDelay: number;
  /** RTCInboundRtpStreamStats.jitterBufferEmittedCount (누적 샘플 수) */
  jitterBufferEmittedCount: number;
  /** RTCInboundRtpStreamStats.concealedSamples (누적). 원본 under=(언더런) 대응 */
  concealedSamples: number;
  /** 샘플을 뜬 시각 Date.now(). 원본 로그의 gap= 산출 기준. */
  ts: number;

  /* ↓ 여기부터 optional. 추가/삭제에 PROTOCOL_V를 올리지 않는다. */
  packetsLost?: number;
  concealmentEvents?: number;
  totalSamplesReceived?: number;
  /** AudioContext.outputLatency × 1000. README "buf만 보면 과소평가" 교훈의 웹 대응. */
  outputLatencyMs?: number;
  /** 접속 시점부터 첫 오디오 프레임까지(ms). 원본 "PLAY 첫 오디오까지 Nms". */
  firstAudioMs?: number;
  audioLevel?: number;
}

export type RoomClientMsg =
  // 호스트가 보낼 수 있는 것
  | HostOpenMsg
  | HostAnnounceMsg
  | StopShareMsg
  | HostOfferMsg
  | HostIceMsg
  // 리스너가 보낼 수 있는 것
  | JoinMsg
  | ListenerAnswerMsg
  | ListenerIceMsg
  | LeaveMsg
  | StatsMsg
  // 공통
  | RoomPingMsg;

/** [S→호스트] host-open 응답. */
export interface RoomCreatedMsg {
  t: 'room-created';
  roomId: RoomId;
  /** QR·status 2줄째에 그대로 쓴다. */
  joinUrl: string;
  /** localStorage에 저장할 것. 이게 있어야 새로고침해도 방코드가 유지된다. */
  hostToken: HostToken;
  /** true = 기존 방을 재점유했다(리스너가 남아 있을 수 있다). false = 새로 만들었다. */
  resumed: boolean;
  /** 재점유 시 살아남은 리스너들. 이 목록만큼 peer-joined가 곧바로 뒤따른다. */
  listeners: Array<{ peerId: PeerId; deviceName: string }>;
}

/** [S→호스트] 리스너 입장. 호스트는 여기서 그 peer용 RTCPeerConnection을 만들고 offer를 보낸다. */
export interface PeerJoinedMsg {
  t: 'peer-joined';
  peerId: PeerId;
  deviceName: string;
  /** true = 재점유 직후 되살린 리스너(새 입장이 아니다). 토스트를 띄우지 않는 근거. */
  resumed?: boolean;
}

/** [S→호스트] 리스너 이탈. 호스트는 **그 PC만** 정리한다(원본 "느린 1명이 전체를 못 막음" 원칙). */
export interface PeerLeftMsg {
  t: 'peer-left';
  peerId: PeerId;
  reason: PeerLeftReason;
}

/** [S→리스너] join 응답. */
export interface JoinedMsg {
  t: 'joined';
  peerId: PeerId;
  roomId: RoomId;
  hostDeviceName: string;
  /**
   * false = 방은 살아 있지만 호스트가 유예(ROOM_GRACE_MS) 중 부재.
   * 리스너는 끊지 말고 "스트림 끊김 — 재접속" 상태로 대기한다. 호스트가 돌아오면 offer가 온다.
   */
  hostPresent: boolean;
}

/** [S→리스너] 호스트가 보낸 offer(서버가 to를 벗기고 from:'host'를 찍는다). */
export interface OfferMsg {
  t: 'offer';
  from: 'host';
  sdp: SdpPayload;
}

/** [S→호스트] 리스너 answer. */
export interface AnswerMsg {
  t: 'answer';
  from: PeerId;
  sdp: SdpPayload;
}

/** [S→양방향] ICE 중계. */
export interface IceMsg {
  t: 'ice';
  from: PeerId | 'host';
  candidate: IcePayload | null;
}

/**
 * [S→전체 리스너] 호스트가 사라졌다.
 * reason:'stop' — 명시적 중지. 방이 즉시 닫히므로 close(4002)가 뒤따른다.
 * reason:'gone' — WS 단절/탭 크래시. **즉시** 브로드캐스트하되 방은 ROOM_GRACE_MS 동안 살아 있다.
 *                 리스너는 룸 WS를 닫지 말고 PC만 정리한 채 대기한다.
 */
export interface HostStoppedMsg {
  t: 'host-stopped';
  reason: HostStoppedReason;
  /** 'gone'일 때 남은 유예(ms). UI가 "잠시 기다리는 중"을 판단하는 근거. */
  graceMs?: number;
}

/** [S→호스트] 리스너 진단 리포트 릴레이(불투명 — 서버는 내용을 해석하지 않는다). */
export type StatsRelayMsg = StatsMsg & { from: PeerId };

export interface RoomPongMsg {
  t: 'pong';
}

export type RoomServerMsg =
  | RoomCreatedMsg
  | PeerJoinedMsg
  | PeerLeftMsg
  | JoinedMsg
  | OfferMsg
  | AnswerMsg
  | IceMsg
  | HostStoppedMsg
  | StatsRelayMsg
  | RoomPongMsg
  | ErrorMsg;

/* ── RoomDO → LobbyDO 내부 fetch 바디 (클라이언트는 절대 보지 못한다) ── */

export interface InternalAnnounce {
  roomId: RoomId;
  deviceName: string;
  listeners: number;
  /** RoomDO가 보관 중인 최신 값. LobbyDO는 이 값이 바뀌었을 때만 host-update에 싣는다. */
  sourceReady?: boolean;
  ts: number;
}
export interface InternalOffline {
  roomId: RoomId;
  reason: 'timeout' | HostStoppedReason;
}

/* ══════════════════════════════════════════════════════════════════════
 * 7. keepalive 프레임 (Hibernation autoResponse용 고정 문자열)
 *    서버는 setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING_FRAME, PONG_FRAME))
 *    를 걸어둔다. 바이트가 정확히 일치해야 DO를 깨우지 않고 응답한다 — 그래서 상수로 박는다.
 * ══════════════════════════════════════════════════════════════════════ */
export const PING_FRAME = '{"t":"ping"}';
export const PONG_FRAME = '{"t":"pong"}';

/* ══════════════════════════════════════════════════════════════════════
 * 8. 리스너 상태 문구 — 원본 앱 원문 그대로. 두 사람이 각자 타이핑하면 반드시 어긋난다.
 * ══════════════════════════════════════════════════════════════════════ */
export const LISTENER_STATE = {
  /** PlayerService 종료 후 빈 문자열 */
  IDLE: '',
  /** 시작·갈아타기 직후 */
  CONNECTING: '연결 중…',
  /** **실제로 오디오 프레임이 흐를 때만.** 낙관적 표시 금지 */
  LISTENING: '듣는 중',
  /** error code room-not-found (원본 UnknownHostException 분기) */
  NOT_FOUND: '주소를 찾을 수 없음 — 재시도 중',
  /** 그 밖의 실패 전부 (원본 catch-all) */
  FAILED: '연결 실패 — 재시도 중',
  /** host-stopped 수신, PC 단절, 호스트 부재 룸 대기 */
  STREAM_LOST: '스트림 끊김 — 재접속',
  /** 수신은 되는데 재생이 밀릴 때 */
  BUFFERING: '버퍼링…',
  /** WATCHDOG_MS 동안 packetsReceived 정체 */
  STALLED: '멈춤 감지 — 재접속',
  /** 신규 — 원본에 대응 문구 없음(원본은 인원 제한이 없었다). error code room-full */
  ROOM_FULL: '인원이 가득 찼습니다 — 재시도 중',
} as const;
export type ListenerState = (typeof LISTENER_STATE)[keyof typeof LISTENER_STATE];

/** 원본 브라우저 참여 페이지의 "재접속 중… (N회)" */
export function retryingText(tries: number): string {
  return `재접속 중… (${tries}회)`;
}
/** 원본 "재생 실패: NotAllowedError" — 에러 이름을 숨기지 않는다. */
export function playFailedText(errName: string): string {
  return `재생 실패: ${errName}`;
}

/** 미참여 줄. 원본 MainActivity joining 텍스트. */
export const NOT_JOINED_TEXT = '참여 중이 아닙니다.';

/** 참여 줄 2행. 원본 문구 그대로(호스트 IP → 방코드로만 치환). */
export function joiningText(hostLabel: string, stateText: string): string {
  return (
    `▶ ${hostLabel} 에 참여 중 — ${stateText}\n` +
    `다른 사람을 누르면 그쪽으로 옮겨갑니다. 그만 들으려면 [나가기].`
  );
}

/** 로비 목록 버튼 라벨. "님의 소리" 뒤 공백 2칸까지 원본과 동일. */
export function hostButtonText(deviceName: string, roomId: RoomId): string {
  return `${deviceName} 님의 소리  (${roomId})`;
}

/** error code → 리스너 stateText. 모르는 code는 전부 일반 실패로 떨어뜨린다(전방 호환). */
export function stateForError(code: string): ListenerState {
  switch (code) {
    case 'room-not-found':
      return LISTENER_STATE.NOT_FOUND;
    case 'room-full':
      return LISTENER_STATE.ROOM_FULL;
    default:
      return LISTENER_STATE.FAILED;
  }
}

/* ══════════════════════════════════════════════════════════════════════
 * 9. 파생 진단치 — 호스트가 계산한다. 원본 AUDIO/PLAY 로그 산식의 웹 이식.
 * ══════════════════════════════════════════════════════════════════════ */

export interface ListenerDerived {
  /** 창 길이(ms). 원본 로그의 gap=. 1000에서 벌어지면 리스너 탭이 스로틀되는 중이다. */
  gapMs: number;
  /** 지터버퍼 평균 체류(ms). 원본 PLAY buf= 대응. */
  bufMs: number;
  /** 지연 근사(ms) = bufMs + outputLatencyMs. 원본 PLAY true= 대응(그만큼 정확하진 않다). */
  trueMs: number | null;
  /** 수신율 %. 원본 net=. 분모를 호스트 outbound 증분으로 주면 원본과 같은 의미가 된다. */
  netPct: number;
  /** 은닉 샘플 증분. 원본 under= 대응. 0이 아니면 그 초에 소리가 메워졌다. */
  concealedDelta: number;
  /** 원본 판정표 그대로의 결론. */
  verdict: 'ok' | 'net' | 'listener-buffer';
}

/**
 * 원본 README 판정표의 웹 대응:
 *   net<100%            → 송신/네트워크가 못 따라감      ('net')
 *   net=100% & 은닉>0   → 리스너 버퍼/디코더 쪽 문제     ('listener-buffer')
 *   둘 다 정상          → 무죄                          ('ok')
 * (원본의 cap= 항목은 호스트 자기 소스 콜백 수신율이라 이 함수 밖에서 따로 잰다.)
 *
 * @param expectedPackets 호스트 outbound-rtp packetsSent의 같은 창 증분.
 *                        주지 않으면 gapMs/OPUS_FRAME_MS로 이론치를 쓴다.
 */
export function deriveListenerStats(
  prev: StatsMsg,
  cur: StatsMsg,
  expectedPackets?: number
): ListenerDerived | null {
  const gapMs = cur.ts - prev.ts;
  if (gapMs <= 0) return null;

  const dEmitted = cur.jitterBufferEmittedCount - prev.jitterBufferEmittedCount;
  const dDelay = cur.jitterBufferDelay - prev.jitterBufferDelay;
  const bufMs = dEmitted > 0 ? (dDelay / dEmitted) * 1000 : 0;

  const outMs = cur.outputLatencyMs;
  const trueMs = typeof outMs === 'number' ? bufMs + outMs : null;

  const expected =
    expectedPackets && expectedPackets > 0 ? expectedPackets : gapMs / OPUS_FRAME_MS;
  const dPackets = cur.packetsReceived - prev.packetsReceived;
  const netPct = expected > 0 ? Math.round((dPackets * 100) / expected) : 0;

  const concealedDelta = cur.concealedSamples - prev.concealedSamples;

  const verdict: ListenerDerived['verdict'] =
    netPct < 95 ? 'net' : concealedDelta > 0 ? 'listener-buffer' : 'ok';

  return { gapMs, bufMs, trueMs, netPct, concealedDelta, verdict };
}

/** 원본 로그 한 줄 형식을 그대로 흉내 낸 대시보드 문자열. */
export function statsLine(peerId: PeerId, d: ListenerDerived): string {
  const t = d.trueMs == null ? '-' : String(Math.round(d.trueMs));
  return (
    `PLAY ${peerId} buf=${Math.round(d.bufMs)}ms true=${t}ms ` +
    `net=${d.netPct}% under=${d.concealedDelta} gap=${d.gapMs}ms` +
    (d.verdict === 'net' ? '  <<< 송신 큐' : d.verdict === 'listener-buffer' ? '  <<< 수신 버퍼' : '')
  );
}

/* ══════════════════════════════════════════════════════════════════════
 * 10. 파싱 헬퍼 — 서버·클라이언트 공용.
 *     모르는 t는 **조용히 무시**한다(전방 호환). 단 파싱 자체 실패는 무시하지 않는다.
 * ══════════════════════════════════════════════════════════════════════ */

export function encode(msg: LobbyClientMsg | LobbyServerMsg | RoomClientMsg | RoomServerMsg): string {
  return JSON.stringify(msg);
}

/** 문자열 프레임을 {t:...} 객체로. 실패하면 null(호출자가 bad-message로 처리). */
export function decode(raw: unknown): { t: string; [k: string]: unknown } | null {
  if (typeof raw !== 'string') return null;
  let v: unknown;
  try {
    v = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof v !== 'object' || v === null) return null;
  const t = (v as { t?: unknown }).t;
  if (typeof t !== 'string' || !t) return null;
  return v as { t: string; [k: string]: unknown };
}

/** 첫 프레임 검증용. v가 없거나 다르면 false. */
export function isProtocolV(m: { v?: unknown }): boolean {
  return m.v === PROTOCOL_V;
}

/** close code → peer-left reason. 리스너가 leave를 못 보내고 죽어도 이유가 남는다. */
export function reasonFromCloseCode(code: number): PeerLeftReason {
  switch (code) {
    case CLOSE.SWITCH:
      return 'switch';
    case CLOSE.LEAVE:
      return 'leave';
    case CLOSE.ROOM_CLOSED:
      return 'room-closed';
    default:
      return 'gone';
  }
}

/* ══════════════════════════════════════════════════════════════════════
 * 변경 규칙 (동결 후)
 * ──────────────────────────────────────────────────────────────────────
 * [무합의 허용 — PROTOCOL_V 그대로]
 *   · StatsMsg에 optional 필드 추가/삭제 (서버가 불투명 릴레이하므로 서버 변경 불필요)
 *   · S→C 메시지에 optional 필드 추가 (수신측은 모르는 필드를 무시해야 한다)
 *   · ERROR_CODES에 새 code 추가 (모르는 code는 stateForError()가 '연결 실패 — 재시도 중'으로 흡수)
 *   · 상수 **값** 튜닝 중 타이밍 계열(JITTER_BUFFER_TARGET_MS, WATCHDOG_MS, BACKOFF_MAX_MS)
 *   · LISTENER_STATE 문구의 오타 수정
 *
 * [양자 합의 필요 — PROTOCOL_V 증가]
 *   · 새 t 추가, t 이름 변경, 필수 필드 추가/삭제/타입 변경
 *   · 엔드포인트 경로, 토폴로지(소켓 개수/소유), close code 의미 변경
 *   · HEARTBEAT_MS / HOST_TTL_MS / MAX_LISTENERS / ROOM_GRACE_MS (서버 동작과 직결)
 *   · ROOM_CODE_ALPHABET, ROOM_CODE_LEN, normalizeCode()의 결과가 달라지는 수정
 * ══════════════════════════════════════════════════════════════════════ */
