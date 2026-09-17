/**
 * web/shared/strings.ts — FROZEN [S0]. 한국어 문구 전량. (CONTRACT.md §8)
 *
 * 이 파일의 존재 이유는 하나다: **두 사람이 각자 문구를 지어내는 걸 막는다.**
 * 문구가 갈라지는 건 코드가 갈라지는 것보다 훨씬 자주 일어나고 훨씬 늦게 발견된다.
 *
 * 규칙
 *   · 여기 없는 문구를 새로 지어내지 않는다. 필요해지면 미니 Sync.
 *   · 오타 수정 외에 예외가 없다(§12). 추가·변경은 양자 합의 대상이다.
 *   · **공백까지 원문이다.** `님의 소리  (` 의 두 칸, `  · 적용하려면` 의 앞 두 칸,
 *     `이 QR 을` 의 사이 한 칸은 전부 원본 앱 그대로다. 보기 싫다고 고치지 않는다.
 *
 * LISTENER_STATE / retryingText / playFailedText / joiningText / hostButtonText /
 * NOT_JOINED_TEXT 는 **protocol.ts 에 이미 있다.** 여기에 중복 정의하지 않는다 —
 * 리스너 상태는 서버 error code 와 짝이 맞아야 해서 계약 파일 쪽이 집이다.
 */

/* ══════════════════════════════════════════════════════════════════════
 * 8.1 앱 이름 · 제목
 * ════════════════════════════════════════════════════════════════════ */

export const TITLE = {
  /** document.title. 원본 런처 라벨 '같이듣기 PoC' 의 웹 대체 */
  document: '같이 듣기',
  /** 화면 최상단 26sp */
  app: '🎧 같이 듣기',
  /** 섹션 제목 18sp */
  hostSection: '내가 틀기',
  /** 섹션 제목 18sp */
  listenerSection: '같이 듣기',
} as const;

/* ══════════════════════════════════════════════════════════════════════
 * 8.2 status 1줄째 — 상태문구
 *
 * 원본 15종 중 유지 6 / 삭제 7(핫스팟·배터리 — 웹에 대응물 없음) / 개작 1 / 신규 1.
 * 삭제된 7종은 여기 없다. 없는 게 맞다 — 목록에 없는 status 문구는 쓰지 않는다.
 * ════════════════════════════════════════════════════════════════════ */

export const STATUS = {
  /**
   * ★ shared 소유. **양 슬롯이 모두 빈 동안 shell 이 그리는 기본값이다.**
   * A 의 초기값이 아니다 — A 가 이걸 자기 슬롯에 넣으면 호스트 슬롯이 페이지 수명 내내
   * 차 있게 되고, 그 순간 B 의 문구가 단 한 번도 화면에 못 나온다.
   */
  idle: '대기 중',

  /** host. 방 개설 + 소스 준비 완료. **소스가 없으면 이 문구를 쓰지 않는다**(→ BANNER.hostNoSource) */
  sharing: '공유 중',
  /** host. stop-share 후 */
  shareStopped: '공유를 중지했습니다',
  /** host. getUserMedia NotAllowedError */
  permissionDenied: '권한이 거부되었습니다',
  /** host. getDisplayMedia 취소/거부 */
  captureCancelled: '화면 캡처 동의가 취소되었습니다',
  /** host. 신규 — room-taken 수신 후 새 코드로 재시도 중 */
  roomCreateFailed: '방을 만들지 못했습니다 — 재시도 중',

  /** listener. [나가기] 후 */
  listenStopped: '듣기를 종료했습니다',
  /**
   * listener. 로비 WS 연결 실패.
   * 원본 `자동 발견 실패 — 주소를 직접 입력하세요` 의 개작 — 웹은 IP 가 아니라 코드를 받는다.
   */
  discoveryFailed: '자동 발견 실패 — 아래에 코드를 직접 입력하세요',
} as const;

/* ══════════════════════════════════════════════════════════════════════
 * 8.3 status 2줄째 — setRoomLine() 이 단독 렌더한다. 아무도 이 줄을 직접 그리지 않는다.
 * ════════════════════════════════════════════════════════════════════ */

export const ROOM_LINE_EMPTY = '내 방: 아직 없음 (공유를 시작하면 코드가 생깁니다)';

/** `내 방: A3F9 · https://…/#A3F9` — joinUrl 은 반드시 ctx.origin 에서 만든 값을 넘긴다 */
export function roomLineText(code: string, joinUrl: string): string {
  return `내 방: ${code} · ${joinUrl}`;
}

/* ══════════════════════════════════════════════════════════════════════
 * 8.4 호스트 섹션 ('내가 틀기') — A 소유
 * ════════════════════════════════════════════════════════════════════ */

export const HOST = {
  /** 파일 모드 안내(13sp 회색). 원본 4줄 힌트의 웹 대체 */
  hintFile: [
    '내 기기에서 나는 소리를 친구들에게 보냅니다.',
    '여기서 고른 곡만 나갑니다 — 알림음·통화는 가지 않습니다.',
  ],
  /**
   * 탭 캡처 모드 안내. **다이얼로그가 뜨기 전에** 보여준다.
   * 원본 힌트가 '동의 창이 왜 뜨는지'를 미리 설명하는 선제 카피였던 성격을 그대로 계승한다.
   */
  hintDisplay: [
    "탭을 고르라는 창이 뜹니다. 왼쪽 아래 '탭 오디오도 공유'를 꼭 체크하세요.",
    '화면은 쓰지 않습니다 — 브라우저가 소리만 주는 방법을 따로 두지 않았을 뿐입니다.',
  ],

  sourceLabel: '소리 소스 — 고른 소스의 소리만 나갑니다.',

  /**
   * 라디오 3종. 원본 앱 후보 목록이 `Spotify (캡처 거부됨)` 처럼
   * **한계를 라벨에 그대로 적던** 톤을 계승한다.
   */
  sourceFile: '음악 파일 (내 기기에서 고르기)',
  sourceDisplay: '브라우저 탭 (모바일 불가)',
  sourceMic: '마이크 (음질 나쁨 — 비상용)',

  /** `지금: <소스명>의 소리가 나갑니다` */
  nowText: (sourceName: string) => `지금: ${sourceName}의 소리가 나갑니다`,
  /** ★앞 공백 2칸 포함. 원본 그대로 */
  reapplyHint: '  · 적용하려면 공유를 다시 시작하세요',

  btnStart: '공유 시작',
  btnStop: '공유 중지',

  /** 기기 이름 입력. 값 저장은 반드시 ctx.deviceName 을 통해서만 한다 */
  deviceNameLabel: '이 기기 이름 (친구 목록에 보입니다)',

  /** 핫스팟 — 웹은 AP 를 못 여니 버튼이 아니라 안내 카드다 */
  hotspotTitle: 'Wi-Fi 없을 때: 호스트 폰의 핫스팟을 직접 켜세요',
  hotspotBody: '설정 > 모바일 핫스팟을 켜고, 친구들이 그 Wi-Fi에 붙으면 됩니다.',

  /** ★`이 QR 을` 사이 공백 포함. 원본 그대로 */
  qrHint: '친구 카메라로 이 QR 을 찍으면 접속됩니다.',
  qrFallback: (code: string) => `안 되면 직접: ${code}`,
  qrFailed: (code: string) => `QR 생성 실패 — 직접 입력: ${code}`,

  /** 고정 바 1줄째 + MediaSession title (잠금화면에 보인다) */
  barTitle: '같이듣기 — 내 소리 공유 중',
  /**
   * 고정 바 2줄째. `듣는 사람 <N>명 · <참여 링크>`
   * 숫자를 감쌀 문구가 없으면 A 가 지어낸다. 시연 대본이 이 화면을 직접 비춘다(2→1→2).
   */
  listenerCountText: (n: number, joinUrl: string) => `듣는 사람 ${n}명 · ${joinUrl}`,
} as const;

/* ══════════════════════════════════════════════════════════════════════
 * 8.5 리스너 섹션 ('같이 듣기') — B 소유
 * ════════════════════════════════════════════════════════════════════ */

export const LISTENER = {
  discoverHint: '친구가 공유를 시작하면 아래에 자동으로 뜹니다.',
  /** 신규 보조 1줄. 원본 UDP 브로드캐스트가 서브넷에만 닿던 성질을 UX 로 계승 */
  discoverSubHint: '같은 Wi-Fi에 있어야 보입니다 — 안 뜨면 아래에 코드를 직접 입력하세요.',

  /**
   * 호스트 목록 버튼. protocol.ts 의 hostButtonText() 와 같은 모양이며
   * `님의 소리` 뒤 **공백 2칸**까지 원본과 동일하다.
   */
  /** sourceReady:false 인 방에 회색으로 붙이는 접미 */
  sourceNotReadySuffix: ' (소리 준비 중)',

  inputPlaceholder: '직접 입력 (예: A3F9)',
  btnJoin: '듣기 시작',
  btnLeave: '나가기',

  /** 참여 줄 2번째 행. 원문 전문 유지 */
  joinedHint: '다른 사람을 누르면 그쪽으로 옮겨갑니다. 그만 들으려면 [나가기].',
} as const;

/* ══════════════════════════════════════════════════════════════════════
 * 8.6 딥링크 다크 뷰 (#<코드> 진입) — B 소유
 *
 * ★ 메인 섹션의 상태 어휘(LISTENER_STATE, protocol.ts)와 **별개의 한 벌**이다.
 *   두 벌이 공존하며 섞어 쓰지 않는다:
 *     메인 섹션 참여 상태 줄 = LISTENER_STATE
 *     딥링크 다크 뷰        = PLAYER_VIEW
 * ════════════════════════════════════════════════════════════════════ */

export const PLAYER_VIEW = {
  header: '🎧 같이 듣기',
  btnPlay: '재생',
  /** 재생 후 라벨 전이 */
  btnPlaying: '재생 중',

  pressButton: '버튼을 눌러주세요',
  connecting: '연결 중…',
  /** N = listener-rtc 의 tries 를 그대로 노출 */
  retrying: (tries: number) => `재접속 중… (${tries}회)`,
  /**
   * 원본은 `재생 중 — 화면을 꺼도 계속 들립니다` 였다.
   * 웹에서는 그 문장이 **거짓**이 되므로 바꾼다. 조용한 실패 금지 원칙의 연장이다.
   */
  playing: '재생 중 — 화면을 켠 채 두세요',
  /** ★에러 이름을 숨기지 않는다 */
  playFailed: (errName: string) => `재생 실패: ${errName}`,
  streamLost: '스트림 끊김 — 재접속',
  buffering: '버퍼링…',
  stalled: '멈춤 감지 — 재접속',

  /** 신규 전체화면 배너. Wake Lock 자동 재요청이 실패했을 때의 유일한 복구 수단 */
  tapToResume: '화면을 탭해서 계속 듣기',

  mediaSessionTitle: '같이 듣기',
  mediaSessionArtist: '호스트의 소리',
} as const;

/* ══════════════════════════════════════════════════════════════════════
 * 8.7 경고 배너 — setBanner(owner, text)
 *
 * 전부 웹에서 새로 생긴 실패 모드라, **배정 누락이 눈에 안 띄는 항목**이다.
 * ════════════════════════════════════════════════════════════════════ */

export const BANNER = {
  /** 양쪽. connectionState='failed' 2회 연속 */
  apIsolation:
    '연결이 안 됩니다 — AP 격리 의심. 호스트 폰의 핫스팟을 켜고 모두 그리로 접속하세요',

  /** host. getDisplayMedia 결과에 오디오 트랙이 0개 */
  noAudioTrack: '탭 오디오도 공유를 체크하세요 — 소리 트랙이 오지 않았습니다',
  /** host. rms 가 3초 연속 0 */
  hostNoSource: '소리가 나가지 않습니다 — 재생/음량을 확인하세요',
  /** host. 출력 게인 0 또는 <audio>.muted */
  hostMuted: '음소거 상태에서는 화면을 끄면 송출이 끊깁니다',
  /** host. 재점유 직후 소스 없음 */
  hostResumedNoSource: (lastFile: string) => `소리 소스를 다시 고르세요 — 마지막 곡: ${lastFile}`,
  /** host. 특정 peer 의 drop>0 이 5초 연속 */
  peerDropped: (name: string) => `${name} 연결 불량으로 끊었습니다`,
  /** host. ctx.sampleRate !== SAMPLE_RATE */
  sampleRateMismatch: (hz: number) =>
    `기기가 48kHz를 주지 않았습니다 (${hz}Hz) — 지연이 늘 수 있습니다`,

  /** listener. error(room-full) */
  roomFull: '정원이 찼습니다 (최대 4명)',
} as const;

/* ══════════════════════════════════════════════════════════════════════
 * 8.8 진단 로그 · 대시보드
 *
 * 원본의 `fill=` 은 뺐다 — 웹에서는 구조적으로 항상 0 이라 자리만 차지한다.
 * `<<< 캡처 유실` 부착 규칙은 그대로 살린다.
 *
 * **귀로 판단하지 말 것.** 무음이 나와도 아래 세 범인은 구분되지 않는다.
 *   cap<100%              → 소스가 밀림 (파일 재생 스로틀 / 캡처 유실)
 *   cap=100% net<100% drop>0 → 송신 큐 (네트워크가 못 따라감)
 *   cap=100% net=100% drop=0 → 폰은 무죄 — 받는 쪽 지터버퍼
 * ════════════════════════════════════════════════════════════════════ */

export const DIAG = {
  /** `AUDIO rms=12079 peak=30613 clients=2 gap=1000ms cap=100% net=100% drop=0` */
  audioLine: (d: {
    rms: number;
    peak: number;
    clients: number;
    gapMs: number;
    capPct: number;
    netPct: number;
    drop: number;
  }) =>
    `AUDIO rms=${d.rms} peak=${d.peak} clients=${d.clients} gap=${d.gapMs}ms` +
    ` cap=${d.capPct}% net=${d.netPct}% drop=${d.drop}`,

  /** cap<95% 일 때 뒤에 붙인다. 원본 규칙 그대로 */
  captureLoss: '  <<< 캡처 유실',
  sendQueue: '  <<< 송신 큐',
  recvBuffer: '  <<< 수신 버퍼',

  /** 신규. 청취자별 (bufMs + outputLatencyMs) 의 최대−최소 */
  mutualOffset: (ms: number, a: string, b: string) => `상호 오프셋 ≈ ${ms}ms (${a}↔${b})`,
} as const;
