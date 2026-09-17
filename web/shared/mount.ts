/**
 * web/shared/mount.ts — FROZEN (Step 0 확정). 변경은 미니 Sync 로만. (CONTRACT.md §6)
 *
 * A(호스트)와 B(리스너)가 셸과 만나는 유일한 접점이다.
 * 여기 없는 방법으로 셸을 건드리면 그건 소유권 위반이다.
 */

export type MountMode = 'panel' | 'deeplink';
export type StatusOwner = 'host' | 'listener';

export interface MountContext {
  /** 'panel' = 단일 스크롤 화면의 섹션 / 'deeplink' = #코드 진입 시 전체화면 참여 전용 뷰(B만 호출됨) */
  readonly mode: MountMode;

  /** #A3F9 로 들어온 경우 normalizeCode() 통과분, 아니면 null */
  readonly initialRoomCode: string | null;

  /** location.origin. joinUrl()·wsUrl() 의 유일한 출처 — ★하드코딩 금지(env a/b/canonical 3슬롯) */
  readonly origin: string;

  /**
   * status 1줄째. ★owner별 슬롯이다 — 마지막 호출자 승리가 아니다.
   * 원본 MainActivity.render() 는 TextView 하나를 독점했지만, 웹은 A와 B가 같은 줄을 공유한다.
   * owner 를 안 받으면 B의 '듣기를 종료했습니다' 가 A의 '공유 중' 을 지우는,
   * 원본에는 존재하지 않던 버그가 확정적으로 난다.
   *
   * ★렌더 규칙: **두 슬롯을 항상 같이 보여준다.** 채워진 것만 ' · ' 로 이어 붙인다
   * (예: '공유 중 · 듣기를 종료했습니다'). 둘 다 비면 shell 이 shared 기본값 '대기 중' 을 그린다.
   *
   * 한쪽 우선이면 안 되는 이유: panel 모드에서는 청취 전용 기기에서도 host mount 가 돌아
   * 호스트 슬롯이 '대기 중' 으로 항상 차 있다. '호스트 우선' 이면 B의 문구가 단 한 번도
   * 화면에 못 나오고, 로비 WS 가 죽어 자동 발견이 통째로 실패해도 화면엔 '대기 중' 만 남는다.
   * 이 프로젝트가 README 에 못 박은 '조용한 실패 금지' 를 계약이 직접 어기는 셈이 된다.
   * null 을 주면 그 슬롯을 비운다.
   */
  setStatus(owner: StatusOwner, text: string | null): void;

  /** status 2줄째. 호스트 전용. null 이면 '내 방: 아직 없음 (공유를 시작하면 코드가 생깁니다)' */
  setRoomLine(room: { code: string; joinUrl: string } | null): void;

  /**
   * 내 방 코드 읽기. **setRoomLine() 이 쓴 값을 shell 이 그대로 중계한다** — 호스트는 이미
   * setRoomLine 을 부르므로 A쪽 추가 작업은 없다.
   *
   * B가 쓴다: 로비 첫 프레임 lobby-hello 의 selfRoomId 를 채우고, 발견 목록에서 자기 방을
   * 걸러낸다(원본 MainActivity.isMine() 의 IP 대조 필터 대체). 시연에서 한 대가 호스트와
   * 리스너를 겸하는 순간(지연 실측, 노트북 단독 경로) 자기 방이 목록에 뜨는 걸 막는 유일한 수단이다.
   *
   * 이게 없으면 B는 A 네임스페이스인 localStorage['lt.host.*'] 를 훔쳐보거나
   * shared 를 고치는 수밖에 없다 — 둘 다 규칙 위반이다. 서버 힌트로도 못 푼다.
   * 그 힌트를 채워 보내는 주체가 바로 B이기 때문이다.
   *
   * 호스트가 로비 소켓보다 늦게 방을 열 수 있으므로 subscribe 로 나중 값도 받는다.
   * 최종 필터 책임은 여전히 클라이언트(B)에 있다.
   */
  readonly selfRoom: {
    get(): string | null;
    /** 반환값은 구독 해제 함수. onTeardown 에 걸어두면 된다. */
    subscribe(fn: (roomId: string | null) => void): () => void;
  };

  /**
   * 이 기기의 이름. 원본 Build.MODEL 자리이며 A의 host-open·host-announce 와
   * B의 join 에 **같은 값**이 실려야 한다.
   *
   * shared 로 올린 이유: join 의 deviceName 은 필수 필드이고, 그 값이 서버를 거쳐
   * peer-joined 로 A에게 가서 A의 배너 '<이름> 연결 불량으로 끊었습니다' 에 그대로 노출된다.
   * 그런데 이름 입력 UI 는 §8.4 에서 호스트 섹션 전용으로만 정의돼 있어서, 이게 없으면
   * B는 라벨을 새로 지어내거나(§8 위반) A의 키를 읽거나(네임스페이스 위반) 빈 문자열을
   * 보내야 한다(배너가 '  연결 불량으로…' 가 된다).
   *
   * 저장 키는 localStorage['lt.shared.deviceName'] — A·B 공용이다.
   * 입력 UI 는 §8.4 그대로 A가 단독 소유하고, B는 읽기만 한다.
   * 값이 없으면 shell 이 추정명을 만든다(UA-CH model → 'iPhone' / 'Android 폰' / '노트북').
   */
  readonly deviceName: {
    get(): string;
    /** A(입력 UI 소유자)만 호출한다. B는 절대 호출하지 않는다. */
    set(name: string): void;
    subscribe(fn: (name: string) => void): () => void;
  };

  /** status 아래 경고 배너 슬롯. owner별 1개씩. null 이면 제거. 내용은 각 소유자가 렌더한다 */
  setBanner(owner: StatusOwner, text: string | null): void;

  /** WS 절대 URL. 경로는 protocol.ts 의 LOBBY_PATH / roomWsPath() 로만 만든다 */
  wsUrl(path: string): string;

  /** 페이지 이탈/모드 전환 시 호출될 정리 훅 */
  onTeardown(fn: () => void): void;
}

export interface MountHandle {
  destroy(): void;
}

export type MountFn = (el: HTMLElement, ctx: MountContext) => MountHandle;
