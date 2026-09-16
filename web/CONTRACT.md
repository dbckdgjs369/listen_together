# 같이 듣기 — A·B 동결 계약 (v1)

> 계획과 근거는 [PLAN.md](./PLAN.md)에 있다. **이 문서는 규칙이다.**

---

## 1. 이 문서의 지위

**Step 0 커밋 이후 동결한다. 변경은 양자 합의 + `PROTOCOL_V` 증가로만 한다.**

여기 적힌 것은 두 사람이 같은 파일을 열지 않고도 같은 프로그램을 만들 수 있게 하는 최소 합의다. 파일 소유권, 메시지 모양, 한국어 문구, 상수값 — 네 가지가 전부고, 이 넷이 어긋나면 파일이 안 겹쳐도 프로그램은 깨진다.

분쟁이 나면 **이 파일이 판정 기준**이다. "내 쪽에선 이게 맞는 것 같았다"는 근거가 되지 않는다.

읽는 건 자유고 오히려 권장한다. **금지되는 건 쓰기뿐이다.**

동결 예외는 두 개뿐이고 아래 [§12 변경 정책](#12-변경-정책)에 목록으로 박아 뒀다. 그 목록에 없으면 전부 합의 대상이다.

---

## 2. 파일 트리와 소유권

저장소 루트 = `/Users/yoochangheon/Desktop/listen-together` (기존 안드 PoC와 같은 레포. 웹 포팅은 `web/`·`server/`로 새로 붙인다)

범례
- `# FROZEN` = shared-frozen. Step 0에서 단독 세션이 만들고 동결, 이후 양쪽 **읽기 전용**
- `# A` = 호스트 사이드 담당자 전용 쓰기 / `# B` = 리스너 사이드 담당자 전용 쓰기
- `[S0]` = Step 0에서 파일이 생성됨(스텁 포함) / `[A작성]`·`[B작성]` = 각자 브랜치에서 새로 만듦

```
listen-together/
├─ app/ … ios/ … build.gradle gradlew settings.gradle   # FROZEN (안드/iOS 원본 — 웹 포팅에서 읽기 전용, 수정 금지)
├─ README.md                                            # FROZEN (원본 교훈 문서. 웹 내용은 web/ 아래 두 문서에 쓴다)
├─ .gitignore                                           # FROZEN [S0] (node_modules/ dist/ .wrangler/ .dev.vars 추가)
├─ package.json                                         # FROZEN [S0] 루트 매니페스트 1/6 — devDep·script 추가도 동결 대상
├─ package-lock.json                                    # FROZEN [S0] 루트 매니페스트 2/6 — 한쪽만 npm i 하면 자동머지 불가
├─ tsconfig.json                                        # FROZEN [S0] 루트 매니페스트 3/6 — solution 파일, references만
├─ tsconfig.web.json                                    # FROZEN [S0] 루트 매니페스트 4/6 — lib DOM+ES2022, include web/**
├─ tsconfig.server.json                                 # FROZEN [S0] 루트 매니페스트 5/6 — @cloudflare/workers-types, include server/**
├─ vite.config.ts                                       # FROZEN [S0] 루트 매니페스트 6/6 — root=web, 3엔트리, port 7990
├─ wrangler.jsonc                                       # FROZEN [S0] env.a / env.b / canonical 3슬롯
├─ scripts/
│   └─ guard-canonical.mjs                              # FROZEN [S0] canonical 배포 가드: main 아님·워킹트리 더러움·확인 입력 없음 → exit 1
├─ server/                                              # FROZEN [S0] 전체 — Step 0 이후 A도 B도 열지 않는다
│   ├─ index.ts                                         #   Workers 엔트리: /ws/lobby, /ws/room/:code, 나머지는 assets
│   ├─ lobby-do.ts                                      #   LobbyDO: 리스너 전용 소켓, hosts 스냅샷 + host-online/update/offline (TTL 3s)
│   ├─ room-do.ts                                       #   RoomDO: 호스트1+리스너4, 시그널 릴레이, hostToken 재점유, 60s 유예
│   ├─ lobby-key.ts                                     #   CF-Connecting-IP → 로비 버킷 해시 (+ ?lobby= 오버라이드)
│   └─ env.d.ts                                         #   Env 바인딩 타입 (LOBBY, ROOM, ASSETS)
└─ web/
    ├─ PLAN.md                                          #   살아있는 문서 — 시연 전까지 계속 고친다
    ├─ CONTRACT.md                                      # FROZEN [S0] 이 문서
    ├─ index.html                                       # FROZEN [S0] Vite 엔트리. <script type=module src=/main.ts> 한 줄
    ├─ main.ts                                          # FROZEN [S0] ★엔트리 배선 완결 — host/listener mount import·호출까지 끝
    ├─ shared/                                          # FROZEN [S0] 전체
    │   ├─ protocol.ts                                  #   ★A·B 간 유일한 계약. 메시지 전 타입 + 상수 + normalizeCode()
    │   ├─ strings.ts                                   #   ★한국어 문구 전량(§8). 두 사람이 문구를 지어내는 걸 막는 장치
    │   ├─ mount.ts                                     #   MountContext / MountHandle / MountFn 고정 시그니처
    │   ├─ shell.ts                                     #   셸 DOM(제목·status 2줄·배너 슬롯·섹션 2개·빈 딥링크 컨테이너) + 해시 라우팅
    │   ├─ ws.ts                                        #   타입드 WS 래퍼(ping/pong 25s). 재접속 정책은 호출자 소유
    │   ├─ ice.ts                                       #   protocol.ts의 ICE_SERVERS로 RTCConfiguration 조립. TURN 금지 주석 고정
    │   ├─ log.ts                                       #   ltLog(tag, obj) — 콘솔 접두사 통일
    │   ├─ experimental-dom.d.ts                        #   ★실험 DOM API 전량 선언 한 곳(§6)
    │   └─ styles/
    │       ├─ tokens.css                               #   색·타이포(26/18/14/13)·간격 토큰만. 컴포넌트 스타일 금지
    │       └─ shell.css                                #   페이지 골격·status 2줄·배너 슬롯·섹션 박스 + #lt-deeplink의 position/display만
    ├─ host/                                            # ── A 전용 트리 (B는 한 파일도 열지 않는다)
    │   ├─ index.ts                                     # A [S0 스텁] mount 시그니처는 FROZEN, 본문은 A 소유
    │   ├─ host.css                                     # A [S0 빈 파일] 호스트 섹션 스타일 전부 여기
    │   ├─ source/
    │   │   ├─ file.ts                                  # A [A작성] 로컬 파일 → <audio blob> → MediaElementSource → 2분기
    │   │   ├─ display.ts                               # A [A작성] getDisplayMedia 탭 오디오(데스크탑 Chrome 한정, 기능감지)
    │   │   ├─ mic.ts                                   # A [A작성] getUserMedia (AEC/NS/AGC 전부 false)
    │   │   └─ index.ts                                 # A [A작성] 소스 3종 공통 인터페이스 + 선택 영구저장 + 48k 컨텍스트
    │   ├─ rtc/
    │   │   ├─ fanout.ts                                # A [A작성] peer-joined마다 PC 생성 → addTrack → offer
    │   │   ├─ munge.ts                                 # A [A작성] answer SDP opus fmtp munging (setRemote 직전)
    │   │   └─ candidates.ts                            # A [A작성] selected candidate pair local↔local 판정(AP isolation 진단)
    │   ├─ ui/
    │   │   ├─ section.ts                               # A [A작성] '내가 틀기' 섹션 조립 + 기기 이름 입력
    │   │   ├─ source-radio.ts                          # A [A작성] 소리 소스 라디오 3종 + '지금: …' 요약
    │   │   ├─ qr.ts                                    # A [A작성] vendor/qrcode로 참여 URL QR + 폴백 '안 되면 직접: <코드>'
    │   │   ├─ hotspot-card.ts                          # A [A작성] '핫스팟 켜기' 대체 안내 카드
    │   │   └─ statusbar.ts                             # A [A작성] 청취자 수·상태 고정 바 + Wake Lock + 호스트 MediaSession
    │   ├─ stats/
    │   │   ├─ analyser.ts                              # A [A작성] rms/peak (32바이트 간격 서브샘플링 산식 이식)
    │   │   └─ dashboard.ts                             # A [A작성] outbound-rtp + 리스너 stats 수합 → AUDIO 한 줄 + 판정표
    │   └─ vendor/                                      # A 트리 안에 위치(=B와 충돌 0). 단 업스트림 원본이라 편집 금지
    │       ├─ qrcode.js                                # FROZEN-vendored [S0] MIT 단일파일 QR 인코더 원본 그대로
    │       ├─ qrcode.d.ts                              # FROZEN-vendored [S0] 최소 타입 선언
    │       └─ LICENSE-qrcode.txt                       # FROZEN-vendored [S0] MIT 전문 보존
    ├─ listener/                                        # ── B 전용 트리 (A는 한 파일도 열지 않는다)
    │   ├─ index.ts                                     # B [S0 스텁] mount 시그니처는 FROZEN, 본문은 B 소유
    │   ├─ listener.css                                 # B [S0 빈 파일] 리스너 섹션 + 딥링크 다크뷰 스타일 전부 여기
    │   ├─ rtc/
    │   │   ├─ machine.ts                               # B [B작성] want 플래그 무한 재시도 + 갈아타기(PlayerService 이식)
    │   │   └─ answer.ts                                # B [B작성] offer 수신 → answer, ontrack
    │   ├─ player/
    │   │   ├─ gesture.ts                               # B [B작성] play()+resume()+wakeLock 단일 제스처 동기 묶음
    │   │   ├─ watchdog.ts                              # B [B작성] 4초 정지 감시견 + visibilitychange 복구
    │   │   └─ mediasession.ts                          # B [B작성] title '같이 듣기' / artist '호스트의 소리'
    │   ├─ ui/
    │   │   ├─ section.ts                               # B [B작성] '같이 듣기' 섹션(호스트 목록·수동입력·나가기)
    │   │   ├─ joining-line.ts                          # B [B작성] 0.5초 폴링 참여 상태 줄(문구 원문 유지)
    │   │   └─ deeplink-view.ts                         # B [B작성] #코드 진입 시 다크 전체화면 — DOM·배경색 전부 여기
    │   └─ metrics/
    │       ├─ inbound.ts                               # B [B작성] inbound-rtp 원시 카운터 + outputLatency 가산 표기
    │       └─ sync.ts                                  # B [B작성] 청취자 간 오프셋 계측 + jitterBufferTarget 동일 고정
    └─ tools/                                           # FROZEN [S0] 전체 — '상대의 최소 대역품'. 기능 추가 금지
        ├─ mock-host/
        │   ├─ index.html                               #   B가 A 없이 리스너 전체 경로를 단독 개발하는 장치
        │   └─ main.ts                                  #   440Hz 오실레이터 송출 + [공유 중지](stop) + [강제 종료](gone)
        └─ mock-listener/
            ├─ index.html                               #   A가 B 없이 fan-out/munging/대시보드를 단독 검증하는 장치
            └─ main.ts                                  #   자동 join→offer수신→answer→재생 + 1s 가짜 stats + [나가기]/[강제 종료]
```

### 겹침 재검사

A가 쓰는 경로 = `web/host/**` **단 하나**. (그중 `vendor/` 3파일은 업스트림 원본이라 편집만 금지, 위치는 A 트리 안)
B가 쓰는 경로 = `web/listener/**` **단 하나**.
**교집합 = ∅.** 두 사람이 같은 파일을 여는 경로가 없다.

1차 설계에서 숨어 있던 공유 자원 4곳은 전부 Step 0에서 선점·동결했다.

| 숨은 공유 자원 | Step 0 처리 |
|---|---|
| 엔트리 배선 `web/main.ts` | mount import·호출까지 완결하고 동결. 이후 아무도 열지 않는다 |
| 공통 CSS `web/shared/styles/**` | 토큰과 골격만. 컴포넌트 스타일은 각자 `host.css`/`listener.css` |
| 딥링크 뷰 컨테이너 `#lt-deeplink` | shared는 빈 div와 `position/display`만. **배경색조차 B 소유** |
| 루트 매니페스트 6종 + 실험 DOM 타입 1종 | Step 0에서 devDep 전량 `-E` 정확 핀 설치. 실험 API는 `experimental-dom.d.ts` 한 곳에만 선언 |

### 1차 트리에서 바뀐 것 3가지

기록해 둔다. 왜 바꿨는지를 모르면 나중에 되돌리는 사람이 생긴다.

| 바꾼 것 | 이유 |
|---|---|
| `web/shared/protocol/` 디렉터리 6분할 → **단일 파일 `protocol.ts`** | 동결 대상이 6개로 흩어지면 "계약 전문이 어디인가"가 흐려진다. 계약은 파일 하나여야 한다 |
| `docs/protocol.md` + `docs/ownership.md` + `docs/demo-checklist.md` → **`web/PLAN.md` + `web/CONTRACT.md` 2개** | 문서가 5개면 어느 게 최신인지 아무도 모른다. 규칙은 CONTRACT, 나머지는 PLAN |
| `POST /api/room`(HTTP 방 생성) → **`host-open` 룸 WS 첫 프레임** | HTTP 한 번 + WS 한 번이면 그 사이에 방이 사라지는 경합이 생긴다. 소켓 하나에서 생성·재점유·하트비트를 다 처리한다 |

---

## 3. 소유권 규칙

### 절대 만지지 않는 목록 (shared-frozen)

Step 0 커밋 이후 **A도 B도 쓰기 금지**다.

```
package.json          package-lock.json     tsconfig.json
tsconfig.web.json     tsconfig.server.json  vite.config.ts
wrangler.jsonc        .gitignore            scripts/**
server/**             web/index.html        web/main.ts
web/shared/**         web/tools/**          web/CONTRACT.md
web/host/vendor/**    (A 트리 안이지만 업스트림 원본 — 편집 금지, 교체만 가능)
```

### 규칙

1. **디렉터리** — A는 `web/host/**`, B는 `web/listener/**`. 그 밖의 모든 경로는 읽기 전용이다. 읽는 건 권장한다. 금지는 쓰기뿐이다.

2. **루트 매니페스트** — devDependency 추가와 npm script 추가도 '수정'이다. 한쪽이 혼자 `npm i -D`를 도는 순간 lockfile 충돌이 확정되고 자동 머지가 사실상 불가능해진다. 필요한 devDep은 Step 0에서 전부 설치해 이후 소요를 0으로 만든다.

3. **동결 해제 절차 — '미니 Sync'** — shared를 고쳐야 하면 자기 브랜치에서 고치지 않는다. ①작업을 멈추고 상대에게 알린다(무엇을, 왜) ②한 사람이 단독으로 main에 브랜치를 따 수정 ③main 반영 ④양쪽이 각자 브랜치를 main 위로 rebase. **두 사람이 동시에 shared를 여는 상황 자체를 만들지 않는 것**이 이 규칙의 전부다.

4. **프로토콜 버전** — 메시지의 의미·필수 필드·채널 소속이 바뀌면 `PROTOCOL_V`를 올리고 이 문서를 같이 고친다. 예외는 [§12](#12-변경-정책).

5. **WS 토폴로지** — 리스너는 소켓 2개(로비 상시 + 룸은 join마다), 호스트는 룸 소켓 1개. 호스트는 로비에 직접 붙지 않는다. **하나의 소켓이 로비→룸으로 이동하는 설계는 Hibernation 제약상 불가능하므로 금지한다.**

6. **엔트리 배선** — `web/main.ts`가 host/listener의 mount를 import·호출하는 배선은 Step 0에서 완결·동결된다. **A도 B도 main.ts를 열지 않는다.** 새 화면이나 모드가 필요하면 자기 mount 함수 안에서 렌더한다.

7. **mount 시그니처** — 파일의 소유자는 각각 A와 B지만, export 이름과 시그니처를 바꾸려면 미니 Sync가 필요하다. [§6](#6-모듈-진입점-고정-시그니처) 참조.

8. **CSS** — shared는 `tokens.css`(색·26/18/14/13 타이포·간격)와 `shell.css`(페이지 골격·status 2줄·배너 슬롯·섹션 박스·`#lt-deeplink`의 position/display만)만 갖는다. **"shared CSS에 셀렉터를 하나만 추가하고 싶다"는 생각이 들면 그건 자기 css로 갈 물건이다.**

9. **딥링크 다크 뷰** — shared는 빈 컨테이너 `<div id="lt-deeplink">`와 해시 라우팅(표시/숨김, `mode='deeplink'`로 listener mount 호출)만 제공한다. **배경색을 포함한 내부 DOM·스타일 전부가 B 소유다.** deeplink 모드에서 host mount는 아예 호출되지 않는다.

10. **네임스페이스** — DOM id/class 접두사는 A=`h-`, B=`l-`, shared=`lt-`. localStorage 키는 A=`lt.host.*`, B=`lt.listener.*`, shared=`lt.shared.*`. 같은 id 충돌과 CSS 누수를 구조적으로 막는다. **상대 네임스페이스의 키를 읽지 않는다** — 읽어야 할 것 같으면 그건 `MountContext`에 있어야 할 값이다. 현재 공유 키는 둘뿐이다: `lt.shared.deviceName`(A만 쓰고 B는 읽기만, §6 `ctx.deviceName` 경유), `lt.shared.mock-host.<name>`(mock 전용).

11. **실험 DOM 타입** — `jitterBufferTarget`, `suppressLocalAudioPlayback`, `contentHint`, `outputLatency`, WakeLock 등 lib.dom 편차가 있는 API는 `web/shared/experimental-dom.d.ts` **한 곳에만** 선언한다. 컴파일 에러가 나도 루트에 `global.d.ts`를 새로 만들거나 tsconfig의 lib/target을 고치지 않는다 — **그게 정확히 같은 파일 머지 충돌을 만드는 경로다.** TypeScript는 정확 버전으로 핀되어 있어 한쪽에서만 lib.dom이 바뀌는 일이 없다.

12. **서버** — `server/**`는 Step 0 이후 손대지 않는다. A는 `tools/mock-listener` 4탭으로, B는 `tools/mock-host`로 각자 단독 검증한다. **상대 진척을 기다리다 서버를 고치는 상황 자체를 만들지 않는다.**

13. **mock 도구** — '상대 모듈의 최소 대역품'이다. **기능을 늘리지 않는다** — 더 필요해졌다면 그건 본 모듈이 할 일이다. 두 mock 모두 '정상 종료'와 '강제 종료'(leave/stop 없이 소켓만 끊어 서버의 timeout/gone 경로를 때린다) 버튼을 갖는다. 예외는 `mock-listener`의 `?stall=<ms>` 하나뿐이다 — A의 '느린 청취자만 격리' 완료 기준은 실제로 stats를 정체시키지 않으면 발동 여부를 볼 수 없다. **mock-listener의 stats는 지어낸 값이 아니라 실측 카운터다**(§6 주석 참조). 가짜 숫자는 숫자를 그릴 뿐 격리가 실제로 도는지를 증명하지 못한다.

14. **배포 슬롯** — 배포 타깃에도 소유권이 있다. A는 `--env a`에만, B는 `--env b`에만. canonical은 Sync 시점에 main 브랜치에서 한 사람만. [§10](#10-배포-전략) 참조.

15. **산출물** — `dist/`, `node_modules/`, `.wrangler/`는 커밋하지 않는다. 빌드 산출물 커밋은 파일이 안 겹쳐도 매번 충돌하는 대표적 숨은 공유 자원이다.

16. **vendor** — QR 라이브러리는 업스트림 원본 그대로 두고 편집하지 않는다. 버전 교체가 필요하면 파일 전체를 갈아끼우고 LICENSE를 유지한다.

---

## 4. 시그널링 프로토콜 v1

### 4.1 왜 채널이 둘로 쪼개지는가

Cloudflare Hibernation WebSocket은 `acceptWebSocket()`을 호출한 **DO 인스턴스 1개에 귀속**된다. 소켓 하나가 LobbyDO → RoomDO로 옮겨갈 수 없다. 따라서 "WS 1개가 로비와 룸을 겸한다"는 1차 설계는 **구현 불가**이며, 채널을 물리적으로 둘로 분리한다.

**리스너 = 소켓 2개**

1. **로비 WS** — `GET /ws/lobby` → LobbyDO. 페이지가 열려 있는 내내 유지한다. **join·갈아타기·나가기 어느 경우에도 절대 닫지 않는다.** 원본 UDP 비콘(:7981) 수신 스레드의 직계 대체이며, 이 소켓이 살아 있어야 "친구가 공유를 시작하면 아래에 자동으로 뜹니다"가 성립한다.
   원본은 `onPause`에서 비콘 수신을 멈췄지만, 웹은 `visibilitychange` 시에도 소켓을 유지하고 복귀 시 `hosts` 스냅샷을 다시 받지 않는다 — 대신 `host-online/update/offline` 증분이 계속 들어온다.
2. **룸 WS** — `GET /ws/room/:code` → RoomDO(방코드별, `idFromName(normalizeCode(code))`). join 시 개설한다.

**호스트 = 소켓 1개**

룸 WS만. 호스트는 **LobbyDO에 직접 붙지 않는다.** `host-announce`(1초 하트비트)를 룸 WS로 보내면 RoomDO가 DO-to-DO fetch로 LobbyDO에 중계한다. 즉 `host-announce`가 닿는 DO는 RoomDO이고, LobbyDO에는 fetch로 도달한다. **LobbyDO는 호스트 소켓을 하나도 들고 있지 않다.**

**갈아타기 시 닫는 소켓**

"나가기 없이 다른 호스트를 탭" = 옛 룸 WS를 `close(4000 SWITCH)` → 새 코드로 새 룸 WS open. **로비 WS는 건드리지 않는다.** 같은 코드면 no-op(원본 `PlayerService`의 `if (want.equals(host)) return;`과 동일). 나가기 = 룸 WS만 `close(4001 LEAVE)`, 로비 WS 유지. 이 규칙 덕에 갈아타는 중에도 발견 목록이 끊기지 않는다.

**한 사람이 호스트+리스너를 겸할 때**

단일 스크롤 화면에 두 섹션이 다 있으므로 소켓 3개(로비 1 + 호스트 룸 1 + 리스너 룸 1)가 동시에 열릴 수 있다. 자기 방이 자기 발견 목록에 뜨는 것은 **클라이언트가 `selfRoomId`로 거른다**(원본 `MainActivity.isMine()` IP 필터의 대체). `lobby-hello`의 `selfRoomId`는 서버 측 필터 힌트일 뿐이고, **최종 필터 책임은 클라이언트에 있다** — 호스트가 로비 소켓보다 늦게 방을 열 수 있기 때문이다.

**DO 간 통신**

RoomDO → LobbyDO 단방향 fetch 2종만 존재한다. 역방향(LobbyDO → RoomDO)은 없다. LobbyDO의 상태는 `Map<roomId, HostEntry>` 하나뿐이며 hosts가 비면 알람을 걸지 않는다(하이버네이션 복귀).

**로비 스코프 — 전역 로비는 없다**

`/ws/lobby` 핸들러가 `CF-Connecting-IP`(IPv4는 /24, IPv6는 /64)를 해시해 `idFromName(lobbyKey)`로 라우팅한다. 같은 NAT 뒤 = 같은 로비 = LAN 근사다. 원본의 UDP 브로드캐스트가 **구조적으로 같은 서브넷에만** 도달했던 성질을 재현하기 위한 것이며, 이게 없으면 "친구가 공유를 시작하면 자동으로 뜹니다"가 "모르는 사람 방이 뜹니다"로 변질된다.
개발·시연 격리용 `?lobby=<임의문자열>` 오버라이드를 v1에 포함한다(A는 `lobby=a`, B는 `lobby=b`로 개발).

**정적 자산**

같은 Worker가 `/*`로 `web/` 빌드 산출물을 서빙한다(HTTPS 자동 → `getDisplayMedia`/`getUserMedia`의 secure context 충족). 참여 링크는 `https://<origin>/#<코드>`이며 QR 페이로드도 같다.

### 4.2 엔드포인트

| 경로 | 대상 | 설명 |
|---|---|---|
| `GET /ws/lobby` (Upgrade) | LobbyDO, `idFromName(lobbyKey)` | **리스너 전용.** 첫 프레임은 반드시 `{t:'lobby-hello',v:1}`. 페이지 수명 내내 유지, 갈아타기·나가기에도 닫지 않는다 |
| `GET /ws/room/:code` (Upgrade) | RoomDO, `idFromName(normalizeCode(code))` | **호스트·리스너 공용.** 호스트의 첫 프레임은 `host-open`, 리스너의 첫 프레임은 `join`. 둘 다 아니면 `error(bad-message)` + `close(4005)` |
| `GET /ws/room/:code` — 코드 형식 불량 | (DO 미생성) | `isWellFormedCode()`가 false면 Worker가 **DO를 깨우지 않고** 직접 accept 후 `{t:'error',code:'room-not-found'}` 1프레임 + `close(4002)`. 쓰레기 코드로 DO 인스턴스를 만들지 않기 위한 방어. **리스너 입장에선 정상 room-not-found와 구분되지 않는다(의도된 동일 취급)** |
| `POST /_lobby/announce` | RoomDO → LobbyDO 내부 fetch 전용 | `{roomId, deviceName, listeners, sourceReady, ts}`. 하트비트 1초 1회. LobbyDO가 upsert 후 host-online/host-update 브로드캐스트 |
| `POST /_lobby/offline` | RoomDO → LobbyDO 내부 fetch 전용 | `{roomId, reason:'stop'\|'gone'}`. LobbyDO가 host-offline 브로드캐스트 |
| `GET /#<코드>` | 정적 페이지 | 참여 딥링크. QR 페이로드와 동일. 진입 시 리스너 딥링크 뷰로 자동 전환하고 `normalizeCode(location.hash)`로 코드를 뽑는다 |
| `GET /*` | Workers Assets | `web/` 빌드 산출물 서빙(HTTPS 자동 = secure context 확보) |

### 4.3 로비 채널 메시지

| 방향 | 메시지 | 규칙 |
|---|---|---|
| C→S | `{"t":"lobby-hello","v":1,"selfRoomId":"A3F9"}` | **첫 프레임(필수).** `v!==1`이면 `error(bad-version)`+`close(4004)`, 다른 `t`가 먼저 오면 `error(bad-message)`+`close(4005)`. `selfRoomId`는 자기 방을 목록에서 빼기 위한 힌트(optional, null 가능). **최종 필터 책임은 클라이언트에 있다** |
| S→C | `{"t":"hosts","hosts":[…],"now":1757990001234}` | `lobby-hello` 직후 **정확히 1회**. `since` 오름차순(원본 LinkedHashMap의 '발견 순서 유지'를 서버가 보장). `now`는 클라이언트 시계 오차 보정용 |
| S→C | `{"t":"host-online","host":{…}}` | 처음 보인 방. 목록 **맨 뒤에 추가**한다(정렬하지 않는다). UDP 비콘이 처음 도착한 순간의 대체 |
| S→C | `{"t":"host-update","roomId":"A3F9","listeners":2,"deviceName":"…","sourceReady":true}` | 이미 목록에 있는 방의 값만 변했다. **순서를 바꾸지 않고 라벨만 갱신.** `deviceName`·`sourceReady`는 optional |
| S→C | `{"t":"host-offline","roomId":"A3F9","reason":"timeout"}` | reason: `timeout`(HOST_TTL_MS 미수신) / `stop`(명시 중지) / `gone`(WS 단절). 수신 즉시 **회색 비활성** 처리하고 `LOBBY_FORGET_MS`(10초) 뒤 제거. 원본은 죽은 호스트를 목록에서 지우지 않았고, 그게 '탭해도 안 붙는 유령 항목'을 만들었으므로 웹에선 회색→제거로 고친다 |
| C→S | `{"t":"ping"}` | `WS_PING_MS`(25초) 주기. **바이트가 정확히 `PING_FRAME`과 같아야** autoResponse가 DO를 깨우지 않고 응답한다 |
| S→C | `{"t":"pong"}` | 위의 자동 응답(`PONG_FRAME` 고정 문자열) |
| S→C | `{"t":"error","code":"bad-version","msg":"…"}` | 로비 채널의 error code는 `bad-version` / `bad-message` **두 개뿐**. 로비에는 room-not-found가 없다 — 방 조회는 룸 채널의 일이다 |

### 4.4 룸 채널 메시지

| 방향 | 메시지 | 규칙 |
|---|---|---|
| 호스트→S | `{"t":"host-open","v":1,"deviceName":"…","hostToken":"9f3c…"}` | **첫 프레임. 생성과 재점유를 겸한다.** 토큰 없음→빈 코드면 생성/점유 중이면 `room-taken`. 토큰 일치→재점유(유예 알람 취소). 토큰 불일치→`room-taken`. 토큰은 있으나 방 없음(유예 만료)→그 코드로 새로 생성 + 새 토큰(**이미 뿌린 QR이 계속 유효**) |
| S→호스트 | `{"t":"room-created","roomId":"A3F9","joinUrl":"…","hostToken":"…","resumed":true,"listeners":[…]}` | `hostToken`은 localStorage에 `{roomId,hostToken,exp}`로 저장(TTL 6시간). `resumed:true`면 listeners 수만큼 `peer-joined`가 곧바로 뒤따른다. `joinUrl`은 status 2줄째와 QR 페이로드에 그대로 쓴다 |
| 호스트→S | `{"t":"host-announce","deviceName":"…","sourceReady":true}` | `HEARTBEAT_MS`(1초) 주기. **RoomDO가 listeners 수를 자기가 세어** 채운 뒤 LobbyDO로 중계한다 — 호스트 자기 신고를 신뢰하지 않는다. 원본 UDP 비콘 `LT1\|<Build.MODEL>\|<IP>`의 직계 대체 |
| 호스트→S | `{"t":"stop-share"}` | 명시적 '공유 중지'. **유예 없이** 방을 즉시 닫는다 |
| S→리스너 전체 | `{"t":"host-stopped","reason":"stop"}` | 직후 전 소켓 `close(4002)`와 LobbyDO offline(`stop`) |
| S→리스너 전체 | `{"t":"host-stopped","reason":"gone","graceMs":60000}` | 호스트 WS close 감지 **즉시** 브로드캐스트(탭 크래시·네트워크 단절 포함). 방은 `ROOM_GRACE_MS` 동안 살아 있고, **리스너는 룸 WS를 닫지 않고** PC만 정리한 채 대기한다. 호스트가 재점유하면 `peer-joined`가 다시 와서 자동 복구된다 |
| 리스너→S | `{"t":"join","v":1,"deviceName":"…"}` | **첫 프레임.** 방이 없으면 `room-not-found`+`close(4002)`, 정원 초과면 `room-full`+`close(4002)` |
| S→리스너 | `{"t":"joined","peerId":"p2","roomId":"A3F9","hostDeviceName":"…","hostPresent":true}` | `hostPresent:false`면 방은 살아 있으나 호스트가 유예 중 부재. **끊지 말고** `스트림 끊김 — 재접속` 상태로 대기 |
| S→호스트 | `{"t":"peer-joined","peerId":"p2","deviceName":"…","resumed":false}` | 호스트는 여기서 그 peer 전용 PC를 만들고 addTrack→offer. `resumed:true`는 재점유로 되살린 리스너(새 입장 토스트를 띄우지 않는 근거) |
| 호스트→S→리스너 | `{"t":"offer","to":"p2","sdp":{…}}` → `{"t":"offer","from":"host","sdp":{…}}` | 서버가 `to`를 벗기고 `from:'host'`를 찍어 그 리스너에게만 전달. peer가 없으면 호스트에게 `peer-not-found` |
| 리스너→S→호스트 | `{"t":"answer","sdp":{…}}` → `{"t":"answer","from":"p2","sdp":{…}}` | 호스트는 **`setRemoteDescription` 직전에** opus fmtp를 munging한다. `setLocalDescription` munging은 Chrome이 봉쇄 중이라 쓰지 않는다 |
| 양방향 | `{"t":"ice","to":"p2"\|—,"candidate":{…}\|null}` → `{"t":"ice","from":"p2"\|"host","candidate":{…}}` | **`candidate:null`(end-of-candidates)도 그대로 릴레이한다** — 삼키면 ICE가 늦게 끝나 첫 소리가 느려진다 |
| 리스너→S | `{"t":"leave","reason":"switch"}` | reason: `leave`([나가기]) / `switch`(갈아타기). 보내고 곧바로 `close(4001)` 또는 `close(4000)`. 메시지를 못 보내고 죽어도 서버가 close code로 이유를 유도한다 |
| S→호스트 | `{"t":"peer-left","peerId":"p2","reason":"switch"}` | reason: `leave`/`switch`/`gone`/`timeout`/`room-closed`. 호스트는 **그 PC만** close한다(원본 '느린 1명이 전체를 못 막는다' 원칙의 웹 대응) |
| 리스너→S→호스트 | `{"t":"stats",…}` → 같은 객체 + `{"from":"p2"}` | `STATS_MS`(1초) 주기. **전부 원시 누적 카운터**이며 netPct 같은 파생치는 들어 있지 않다 — 기대 패킷 수라는 분모를 리스너는 모른다. 서버는 `from`만 찍고 나머지 필드는 **읽지도 검증하지도 않는 불투명 릴레이**다 |
| C→S / S→C | `{"t":"ping"}` / `{"t":"pong"}` | `WS_PING_MS`. 고정 문자열이라 autoResponse가 DO를 깨우지 않는다 |
| S→C | `{"t":"error","code":"room-not-found","msg":"…"}` | 룸 채널 code: `room-not-found` / `room-full` / `room-taken` / `bad-version` / `bad-message` / `not-allowed` / `peer-not-found`. **모르는 code는 클라이언트가 `stateForError()`로 '연결 실패 — 재시도 중'에 흡수한다**(새 code 추가를 v 증가 없이 허용하기 위한 규약) |

### 4.5 서버 규칙

**공통**

- **첫 프레임 강제** — 로비 소켓은 `lobby-hello`, 룸 소켓은 `host-open` 또는 `join`이 반드시 첫 프레임이어야 한다. 아니면 `error(bad-message)`+`close(4005)`. `v!==PROTOCOL_V`면 `error(bad-version)`+`close(4004)`. **첫 프레임의 `t`가 역할을 결정하며**, 이후 역할 밖 메시지(리스너가 offer, 호스트가 answer 등)는 `error(not-allowed)`로 거절하고 **소켓은 유지한다.**
- keepalive는 `state.setWebSocketAutoResponse(new WebSocketRequestResponsePair(PING_FRAME, PONG_FRAME))`로 처리한다. 바이트가 정확히 `{"t":"ping"}`일 때만 매칭되므로 클라이언트는 상수를 그대로 보내야 하고, 이 경로는 DO를 깨우지 않는다.
- **모르는 `t`는 조용히 무시한다**(전방 호환). 단 JSON 파싱 실패·`t` 누락은 무시하지 않고 `error(bad-message)`.

**Worker**

- `/ws/room/:code` 진입 시 `isWellFormedCode()`가 false면 **DO를 생성하지 않고** 직접 accept → `room-not-found` 1프레임 → `close(4002)`.
- `/ws/lobby` 진입 시 `?lobby=` 파라미터가 있으면 그 값을, 없으면 `CF-Connecting-IP` 해시를 `idFromName`에 쓴다.
- `joinUrl`은 **반드시 `new URL(request.url).origin`으로 만든다.** 상수로 박으면 env a/b/canonical 3슬롯에서 QR·참여링크가 전부 틀어진다.

**RoomDO**

- 방 상태는 storage에 `{roomId, deviceName, hostToken, sourceReady, createdAt, stoppedAt?}`.
- `host-open` 처리 4분기:

  | 상태 | 토큰 | 결과 |
  |---|---|---|
  | 없음 | 없음 | 새로 생성, `hostToken` 32자 발급, `resumed:false` |
  | 없음 | 있음 (유예 만료 후) | 그 코드로 새로 생성 + **새 토큰** 발급, `resumed:false` |
  | 있음 | 일치 | **재점유.** 기존 토큰 유지, `resumed:true`, 유예 알람 취소, 살아남은 리스너를 `room-created.listeners`에 담고 각각 `peer-joined(resumed:true)` 즉시 발사 |
  | 있음 | 불일치/없음 | `error(room-taken)`+`close(4002)` |

- **호스트 소켓 중복** — 이미 활성 호스트 소켓이 있는데 토큰이 **일치하는** `host-open`이 오면 옛 소켓을 `close(4003 REPLACED)`하고 교체한다. 새로고침 시 옛 소켓의 close 이벤트가 늦게 도착하는 경합을 이걸로 흡수한다. 토큰 불일치면 새 소켓을 거절하고 기존 호스트는 건드리지 않는다.
- `host-announce` 수신 시 listeners 수를 **서버가 직접 세어** LobbyDO로 중계한다. 중계는 최대 `HEARTBEAT_MS`에 1회로 스로틀. `sourceReady`가 실려 오면 방 상태에 보관하고 `InternalAnnounce`에 **그대로 실어 보낸다** — 서버는 이 값을 해석하지 않는다.
- **정원** — 리스너가 `MAX_LISTENERS`(4)를 넘으면 `error(room-full)`+`close(4002)`. 호스트 소켓은 정원에 포함되지 않는다.
- `peerId`는 서버가 발급한다(`p2`,`p3`,… 단조 증가). **재점유 시에도 살아남은 리스너의 peerId는 바뀌지 않는다** — 호스트가 PC 맵을 peerId로 관리하기 때문.
- **릴레이** — offer는 `to`를 벗기고 `from:'host'`로. answer/ice는 `from:peerId`로 호스트에게만. `to`가 없는 peer면 발신자에게 `peer-not-found`를 돌려주고 소켓은 유지. `candidate:null`도 삼키지 않는다.
- **stats는 불투명 릴레이다.** `from`만 스탬프하고 나머지 필드는 읽지도, 검증하지도, 스키마로 거르지도 않는다. 따라서 리스너가 optional 필드를 추가해도 **서버 배포가 필요 없다**(동결 파기 방지 장치).
- 호스트 WS close 감지 → **즉시** `host-stopped(gone)` 브로드캐스트 + LobbyDO offline(`gone`) + `storage.setAlarm(now+ROOM_GRACE_MS)`. **리스너 소켓은 닫지 않는다.**
- 알람 발화(유예 만료) → 방 상태 삭제 + 전 소켓 `close(4002 ROOM_CLOSED)`.
- `stop-share` → `host-stopped(stop)` 브로드캐스트 → LobbyDO offline(`stop`) → **유예 없이** 상태 삭제 + 전 소켓 close. 명시적 중지는 재점유 대상이 아니다.
- 리스너 소켓 close 시 close code로 `peer-left` reason을 유도한다(`reasonFromCloseCode`). `leave` 메시지가 먼저 왔으면 그 reason을 우선한다.
- **호스트가 없는 방(유예 중)에 새 리스너가 join하면 거절하지 않고** `{t:'joined', hostPresent:false}`로 받아들인다.

**LobbyDO**

- 상태는 `Map<roomId, HostEntry>` 하나.
- announce upsert 시 처음 보는 roomId면 `since=now`로 넣고 `host-online` 브로드캐스트, 이미 있으면 lastSeen 갱신 후 **값이 실제로 변했을 때만** `host-update` 브로드캐스트(초당 무의미한 방송 금지).
- **TTL 스윕** — hosts가 비어 있지 않을 때만 `HEARTBEAT_MS` 주기 알람. `now-lastSeen > HOST_TTL_MS`면 `host-offline(timeout)` 후 제거. hosts가 비면 알람을 다시 걸지 않는다.
- `/_lobby/offline` 수신 시 **즉시** 브로드캐스트 + 제거. TTL 만료를 기다리지 않는다 — 원본이 죽은 호스트를 목록에 남겨두던 문제의 교정.
- `lobby-hello` 직후 스냅샷을 `since` 오름차순으로 1회. **발견 순서 보장은 서버 책임이다.**
- **호스트 WebSocket을 절대 받지 않는다.** 로비로 들어온 소켓은 모두 리스너로 취급한다.

### 4.6 close code

| 값 | 이름 | 의미 |
|---|---|---|
| 4000 | `SWITCH` | 리스너가 다른 호스트로 **갈아탄다**(나가기 없이). 서버는 `peer-left reason:'switch'` |
| 4001 | `LEAVE` | 리스너가 [나가기]를 눌렀다 |
| 4002 | `ROOM_CLOSED` | 서버가 방을 닫았다(유예 만료 또는 명시 중지 또는 입장 거절) |
| 4003 | `REPLACED` | 같은 hostToken을 가진 새 호스트 소켓이 들어와 교체됐다(새로고침 경합) |
| 4004 | `BAD_VERSION` | 버전 불일치 |
| 4005 | `BAD_MESSAGE` | 프로토콜 위반 |

### 4.7 error code

| code | 발생 | 리스너 상태 전이 |
|---|---|---|
| `room-not-found` | 그 코드의 방이 없다 (형식 불량 포함) | `주소를 찾을 수 없음 — 재시도 중` |
| `room-full` | 정원 초과 | `인원이 가득 찼습니다 — 재시도 중` |
| `room-taken` | 점유된 코드거나 hostToken 불일치 | (호스트 전용) 새 코드를 뽑아 재시도 |
| `bad-version` | 첫 프레임의 `v` 불일치 | `연결 실패 — 재시도 중` |
| `bad-message` | JSON 실패, `t` 누락, 첫 프레임 위반 | `연결 실패 — 재시도 중` |
| `not-allowed` | 역할 밖 메시지 | 소켓 유지, 무시 |
| `peer-not-found` | `to`의 peerId가 방에 없다 | (호스트 전용) 해당 PC만 정리 |
| **모르는 code** | 미래 추가분 | **`연결 실패 — 재시도 중`으로 흡수** |

---

## 5. `web/shared/protocol.ts` 전문

이게 계약의 실물이다. 아래 소스와 이 문서가 어긋나면 **소스가 이긴다.**

```ts
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
```

### 5.1 동결 직전 확정된 보강

위 소스에 더해, **같은 Step 0 커밋에서** 다음이 들어간다. 동결 후 추가가 아니라 동결 내용의 일부다.

| 보강 | 내용 | 왜 |
|---|---|---|
| `SAMPLE_RATE = 48000` | `protocol.ts` 상수에 추가. A·B 양쪽이 `new AudioContext({sampleRate:48000, latencyHint:'interactive'})`로 열고 `ctx.sampleRate`로 **실제 잡힌 값을 재확인**한다. 불일치면 경고 배너 | README의 단일 최대 지연 개선(그 아래 구간 57ms→11ms)이 정확히 이것이었다. 기본 생성자로 열면 기기에 따라 44100이 잡히고, Opus가 내부 48k 고정이라 양쪽에 리샘플러가 끼어 그 46ms가 그대로 돌아온다 |
| `lobbyKey` 도출 규칙 | `server/lobby-key.ts` + protocol.ts 주석에 '전역 로비 없음'을 명문화. `?lobby=` 오버라이드 포함 | [§4.1](#41-왜-채널이-둘로-쪼개지는가) 로비 스코프 참조 |
| `strings.ts` 분리 | `LISTENER_STATE`·`joiningText` 등 protocol.ts에 이미 있는 것은 그대로 두고, **STATUS 15종 / PLAYER_VIEW 8종 / 배너 8종**을 `web/shared/strings.ts`로 분리해 동결 | 문구가 두 파일에 흩어지는 건 나쁘지만, 문구가 **아예 없어서 두 사람이 각자 지어내는 것**이 훨씬 나쁘다. 전량은 [§8](#8-한국어-문구-사전) |
| `sourceReady` 4곳 | `HostEntry` / `HostUpdateMsg` / `HostAnnounceMsg` / `InternalAnnounce`에 `sourceReady?: boolean` | 산문에만 있고 타입에는 없었다. 생산자가 서버라 나중에 붙이려면 `protocol.ts`와 `server/`를 동시에 고쳐야 한다 — **동결 파기가 확정되는 종류의 누락**이다. 하필 '방은 부활했는데 소리가 없음'의 유일한 방어 장치다 |
| `listenerCountText(n)` | `strings.ts`에 추가 — 고정 바 2줄째 `듣는 사람 <N>명 · <참여 링크>` | 시연 대본이 이 화면을 직접 비추는데 숫자를 감쌀 문구가 사전에 없었다. 없으면 A가 지어낸다 |

---

## 6. 모듈 진입점 고정 시그니처

```ts
// web/shared/mount.ts — FROZEN (Step 0 확정). 변경은 미니 Sync로만.

export type MountMode = 'panel' | 'deeplink';
export type StatusOwner = 'host' | 'listener';

export interface MountContext {
  /** 'panel' = 단일 스크롤 화면의 섹션 / 'deeplink' = #코드 진입 시 전체화면 참여 전용 뷰(B만 호출됨) */
  readonly mode: MountMode;

  /** #A3F9 로 들어온 경우 normalizeCode() 통과분, 아니면 null */
  readonly initialRoomCode: string | null;

  /** location.origin. joinUrl()·wsUrl()의 유일한 출처 — ★하드코딩 금지(env a/b/canonical 3슬롯) */
  readonly origin: string;

  /**
   * status 1줄째. ★owner별 슬롯이다 — 마지막 호출자 승리가 아니다.
   * 원본 MainActivity.render()는 TextView 하나를 독점했지만, 웹은 A와 B가 같은 줄을 공유한다.
   * owner를 안 받으면 B의 '듣기를 종료했습니다'가 A의 '공유 중'을 지우는,
   * 원본에는 존재하지 않던 버그가 확정적으로 난다.
   *
   * ★렌더 규칙: **두 슬롯을 항상 같이 보여준다.** 채워진 것만 ' · '로 이어 붙인다
   * (예: '공유 중 · 듣기를 종료했습니다'). 둘 다 비면 shell이 shared 기본값 '대기 중'을 그린다.
   *
   * 한쪽 우선이면 안 되는 이유: panel 모드에서는 청취 전용 기기에서도 host mount가 돌아
   * 호스트 슬롯이 '대기 중'으로 항상 차 있다. '호스트 우선'이면 B의 문구가 단 한 번도
   * 화면에 못 나오고, 로비 WS가 죽어 자동 발견이 통째로 실패해도 화면엔 '대기 중'만 남는다.
   * 이 프로젝트가 README에 못 박은 '조용한 실패 금지'를 계약이 직접 어기는 셈이 된다.
   * null을 주면 그 슬롯을 비운다.
   */
  setStatus(owner: StatusOwner, text: string | null): void;

  /** status 2줄째. 호스트 전용. null이면 '내 방: 아직 없음 (공유를 시작하면 코드가 생깁니다)' */
  setRoomLine(room: { code: string; joinUrl: string } | null): void;

  /**
   * 내 방 코드 읽기. **setRoomLine()이 쓴 값을 shell이 그대로 중계한다** — 호스트는 이미
   * setRoomLine을 부르므로 A쪽 추가 작업은 없다.
   *
   * B가 쓴다: 로비 첫 프레임 lobby-hello의 selfRoomId를 채우고, 발견 목록에서 자기 방을
   * 걸러낸다(원본 MainActivity.isMine()의 IP 대조 필터 대체). 시연에서 한 대가 호스트와
   * 리스너를 겸하는 순간(지연 실측, 노트북 단독 경로) 자기 방이 목록에 뜨는 걸 막는 유일한 수단이다.
   *
   * 이게 없으면 B는 A 네임스페이스인 localStorage['lt.host.*']를 훔쳐보거나
   * shared를 고치는 수밖에 없다 — 둘 다 규칙 위반이다. 서버 힌트로도 못 푼다.
   * 그 힌트를 채워 보내는 주체가 바로 B이기 때문이다.
   *
   * 호스트가 로비 소켓보다 늦게 방을 열 수 있으므로 subscribe로 나중 값도 받는다.
   * 최종 필터 책임은 여전히 클라이언트(B)에 있다.
   */
  readonly selfRoom: {
    get(): string | null;
    /** 반환값은 구독 해제 함수. onTeardown에 걸어두면 된다. */
    subscribe(fn: (roomId: string | null) => void): () => void;
  };

  /**
   * 이 기기의 이름. 원본 Build.MODEL 자리이며 A의 host-open·host-announce와
   * B의 join에 **같은 값**이 실려야 한다.
   *
   * shared로 올린 이유: join의 deviceName은 필수 필드이고, 그 값이 서버를 거쳐
   * peer-joined로 A에게 가서 A의 배너 '<이름> 연결 불량으로 끊었습니다'에 그대로 노출된다.
   * 그런데 이름 입력 UI는 §8.4에서 호스트 섹션 전용으로만 정의돼 있어서, 이게 없으면
   * B는 라벨을 새로 지어내거나(§8 위반) A의 키를 읽거나(네임스페이스 위반) 빈 문자열을
   * 보내야 한다(배너가 '  연결 불량으로…'가 된다).
   *
   * 저장 키는 localStorage['lt.shared.deviceName'] — A·B 공용이다.
   * 입력 UI는 §8.4 그대로 A가 단독 소유하고, B는 읽기만 한다.
   * 값이 없으면 shell이 추정명을 만든다(UA-CH model → 'iPhone' / 'Android 폰' / '노트북').
   */
  readonly deviceName: {
    get(): string;
    /** A(입력 UI 소유자)만 호출한다. B는 절대 호출하지 않는다. */
    set(name: string): void;
    subscribe(fn: (name: string) => void): () => void;
  };

  /** status 아래 경고 배너 슬롯. owner별 1개씩. null이면 제거. 내용은 각 소유자가 렌더한다 */
  setBanner(owner: StatusOwner, text: string | null): void;

  /** WS 절대 URL. 경로는 protocol.ts의 LOBBY_PATH / roomWsPath()로만 만든다 */
  wsUrl(path: string): string;

  /** 페이지 이탈/모드 전환 시 호출될 정리 훅 */
  onTeardown(fn: () => void): void;
}

export interface MountHandle { destroy(): void }
export type MountFn = (el: HTMLElement, ctx: MountContext) => MountHandle;
```

```ts
// web/host/index.ts — [S0 스텁] 시그니처 FROZEN / 본문 A 소유
import './host.css';
import type { MountContext, MountHandle } from '../shared/mount';

/** panel 모드에서만 호출된다(deeplink 모드에서는 호출되지 않음). */
export function mount(el: HTMLElement, ctx: MountContext): MountHandle {
  el.textContent = '내가 틀기 — 준비 중';           // TODO(A): host-ui
  ctx.setRoomLine(null);
  return { destroy() { el.replaceChildren(); } };
}
```

```ts
// web/listener/index.ts — [S0 스텁] 시그니처 FROZEN / 본문 B 소유
import './listener.css';
import type { MountContext, MountHandle } from '../shared/mount';

/** panel·deeplink 두 모드 모두 이 함수 하나로 들어온다.
 *  deeplink 모드의 컨테이너는 shared가 빈 div만 준다 — 배경색 포함 내부 DOM·스타일 전부 B가 렌더. */
export function mount(el: HTMLElement, ctx: MountContext): MountHandle {
  el.textContent = ctx.mode === 'deeplink' ? '같이 듣기 — 준비 중' : '참여 중이 아닙니다.';  // TODO(B)
  return { destroy() { el.replaceChildren(); } };
}
```

```ts
// web/main.ts — FROZEN [S0]. ★엔트리 배선은 여기서 끝난다. A·B는 이 파일을 열지 않는다.
import './shared/styles/tokens.css';
import './shared/styles/shell.css';
import { bootShell } from './shared/shell';
import { mount as mountHost } from './host/index';
import { mount as mountListener } from './listener/index';

bootShell({ mountHost, mountListener });
```

```ts
// web/shared/shell.ts — FROZEN [S0]
import type { MountFn } from './mount';
export interface ShellDeps { mountHost: MountFn; mountListener: MountFn }

/** 셸 DOM('🎧 같이 듣기' 제목 + status 2줄 + 배너 슬롯 2개 + #h-root/#l-root 섹션
 *  + 빈 #lt-deeplink)을 만들고, location.hash가 유효 방코드면 deeplink 모드로 listener만,
 *  아니면 panel 모드로 둘 다 마운트한다. */
export function bootShell(deps: ShellDeps): void;
```

```ts
// web/shared/experimental-dom.d.ts — FROZEN [S0]
// 규칙: 반드시 '상류(lib.dom)와 동일한 타입'으로 선언한다(optional/required가 어긋나면 병합 에러).
// package.json이 typescript를 정확 버전으로 핀하므로 양쪽의 lib.dom이 달라지지 않는다.
export {};
declare global {
  interface RTCRtpReceiver {
    jitterBufferTarget: number | null;        // Chrome/Edge 124+, FF 115+, Safari 27+
    playoutDelayHint?: number;                // 구명칭(초 단위) — 폴백용
  }
  interface DisplayMediaStreamOptions {
    suppressLocalAudioPlayback?: boolean;     // Chromium: 캡처 중 호스트 로컬 재생을 유지하려면 false
    preferCurrentTab?: boolean;
    systemAudio?: 'include' | 'exclude';
  }
  interface MediaStreamTrack { contentHint: string }          // 'music'
  interface AudioContext {
    readonly outputLatency: number;           // 'buf만 보면 과소평가' 교훈의 웹 대응 가산치
    readonly baseLatency: number;
  }
  interface RTCInboundRtpStreamStats {
    jitterBufferDelay?: number; jitterBufferEmittedCount?: number;
    concealedSamples?: number;  concealmentEvents?: number;
    audioLevel?: number;        totalAudioEnergy?: number;
  }
  interface RTCOutboundRtpStreamStats {
    targetBitrate?: number; totalPacketSendDelay?: number;
    totalSamplesDuration?: number;
  }
  interface WakeLockSentinel extends EventTarget {
    readonly released: boolean; readonly type: 'screen';
    release(): Promise<void>;
    onrelease: ((this: WakeLockSentinel, ev: Event) => unknown) | null;
  }
  interface WakeLock { request(type: 'screen'): Promise<WakeLockSentinel> }
  interface Navigator { readonly wakeLock: WakeLock }
}
```

```ts
// web/tools/mock-host/main.ts — FROZEN [S0]  (B의 디커플링 장치)
// 440Hz 오실레이터 → MediaStreamDestination → peer-joined마다 fan-out. 프로토콜 100% 준수.
// 버튼 3개: [공유 시작] / [공유 중지]=stop 전송(host-stopped reason:'stop')
//          / [강제 종료]=stop 없이 WS 즉시 close(host-stopped reason:'gone' + 60초 유예 경로 검증)
//
// ★hostToken 저장 키를 name별로 분리한다: localStorage['lt.shared.mock-host.<name>']
//   안 그러면 B의 필수 시험 두 개가 서로를 배제한다 — 재점유 시험(같은 토큰으로 같은 방
//   되찾기)이 되려면 토큰을 보관해야 하는데, 키가 공용이면 두 번째 탭이 같은 토큰으로 같은 방을
//   잡아 첫 탭을 close(4003 REPLACED)로 밀어내서 '방 두 개 만들어 갈아타기'가 성립하지 않는다.
// 사용법: ?name=mh1 / ?name=mh2 로 두 방을 만들어 갈아타기를 시험하고,
//        재점유는 같은 ?name= 으로 새로고침·재시작해서 시험한다.
//        code를 주면 그 코드를 점유하려 시도한다(미지정이면 서버가 발급).
export function boot(opts: { name: string; code?: string }): void;

// web/tools/mock-listener/main.ts — FROZEN [S0]  (A의 디커플링 장치)
// 자동 join → offer 수신 → answer 회신 → srcObject 재생.
//
// ★stats는 지어내지 않는다. getStats(inbound-rtp)의 원시 카운터 5종을 STATS_MS 주기로 실제
//   측정해 보낸다. 이건 기능 추가가 아니라 정확도 규정이다 — 가짜 숫자로는 A의 완료 기준 두 개가
//   원리적으로 검증 불가다. drop은 A의 packetsSent 증분과 리스너 packetsReceived 증분의 차이이고,
//   상호 오프셋은 jitterBufferDelay/EmittedCount + outputLatency에서 나온다. 값이 실측이 아니면
//   숫자가 그려지기만 할 뿐 '격리가 실제로 발동하는가'를 증명하지 못한다.
//
// ★host-stopped(reason:'gone') 수신 시 룸 WS를 닫지 않는다. PC만 정리하고 대기하다가
//   재offer를 수락한다(§7 전이 10과 동일). 이게 없으면 A는 재점유 경로를 혼자 검증할 수 없다.
//
// 버튼 2개: [나가기]=leave 후 정상 종료 / [강제 종료]=leave 없이 WS·PC 즉시 close(탭 크래시 시뮬레이션)
// 사용법: ?code=A3F9&name=mock-1 로 4탭을 띄우면 4인 mesh·munging·대시보드를 A 혼자 검증할 수 있다.
//        ?stall=<ms> 는 stats 보고를 의도적으로 정체시킨다 — '느린 청취자만 격리' 발동 시험용이며,
//        §3-13('mock에 기능을 늘리지 않는다')의 유일한 명시적 예외다.
export function boot(opts: { code: string; name: string; stallMs?: number }): void;
```

```ts
// web/shared/ice.ts — FROZEN [S0]
// ★TURN 금지. TURN을 한 줄 넣는 순간 미디어가 인터넷으로 새고 '참여자 데이터 소모 0'이 깨진다.
//   STUN 1개만 둔다(멀티캐스트 차단망에서 srflx 폴백용).
export const RTC_CONFIG: RTCConfiguration;

// web/shared/ws.ts — FROZEN [S0]
// 타입드 WS 래퍼. WS_PING_MS(25초) 주기 ping을 자동으로 보낸다(Hibernation autoResponse 대상).
export interface WsHandlers<M> {
  onMessage(msg: M): void;
  onOpen?(): void;
  /** code는 close code. 재시도 판단은 호출자 몫이다 — 래퍼는 재시도하지 않는다. */
  onClose?(code: number): void;
}
export interface WsHandle<S> { send(msg: S): void; close(code?: number): void }
export function openWs<S, M>(url: string, h: WsHandlers<M>): WsHandle<S>;
```

---

## 7. 리스너 상태 전이표

`stateText` 원문은 [§8](#8-한국어-문구-사전)과 `protocol.ts`의 `LISTENER_STATE`에 있다. 여기는 **무엇이 그 값을 바꾸는가**다.

| # | 이벤트 | → stateText | 비고 |
|---|---|---|---|
| 1 | 초기 / 나가기 완료 | `''` (IDLE) | `currentRoomId=null`, 참여 줄은 `참여 중이 아닙니다.` 원본 PlayerService 종료 계약 그대로 |
| 2 | join 시작 (목록 탭 또는 '듣기 시작') | `연결 중…` | `normalizeCode()` 결과가 null이면 **아무 동작 없음**(원본과 동일). **여기서 '듣는 중'이라 단정하지 않는다** — 원본 MainActivity가 join 직후 `refreshJoining()`만 부른 이유 |
| 3 | `joined` + `hostPresent:true` | `연결 중…` 유지 | offer 수신 → answer → `connectionState='connected'`까지도 **아직** 연결 중 |
| 4 | **getStats의 `packetsReceived`가 실제로 증가** | `듣는 중` | **이것만이 유일한 전이 조건이다.** ontrack이나 connectionState만으로 전이하면 원본이 3분 내내 거짓말했던 그 버그를 그대로 재현하게 된다. 동시에 `tries=0` 리셋 |
| 5 | `error(room-not-found)` | `주소를 찾을 수 없음 — 재시도 중` | 원본 `UnknownHostException` 분기의 정확한 대체. **이 에러 코드가 프로토콜에 있어야만 '연결 실패'와 구분된다** |
| 6 | WS open 실패 / onerror / `connectionState='failed'` / 그 밖의 예외 | `연결 실패 — 재시도 중` | 원본 catch-all 분기 |
| 7 | `error(room-full)` | `인원이 가득 찼습니다 — 재시도 중` | 신규 문구(원본은 인원 제한이 없었다). **want는 유지하되 재시도 간격을 늘린다** — 자리가 빌 수 있으므로 포기하지 않지만, 정원 찬 방을 1초마다 두드리지도 않는다 |
| 8 | 모르는 error code | `연결 실패 — 재시도 중` | `stateForError()`가 흡수. 새 code가 추가돼도 리스너가 멈추지 않게 하는 전방 호환 규칙 |
| 9 | `host-stopped(stop)` | `스트림 끊김 — 재접속` | PC 정리. 곧 `close(4002)`가 오고, 재시도 join은 room-not-found → #5로 자연히 전이 |
| 10 | `host-stopped(gone)` | `스트림 끊김 — 재접속` | **룸 WS를 닫지 않는다.** PC만 정리하고 대기. 호스트가 60초 안에 재점유하면 새 offer가 와서 #2→#4로 올라간다. 유예가 만료되면 서버가 close하고 room-not-found 경로로 떨어진다 |
| 11 | `joined` + `hostPresent:false` | `스트림 끊김 — 재접속` | 방은 있는데 소리가 없는 상태를 **낙관적으로 '연결 중…'이라 말하지 않는다** |
| 12 | PC는 살아있는데 `WATCHDOG_MS`(4초) 동안 `packetsReceived` 정체 | `멈춤 감지 — 재접속` | 후 PC 재협상. 룸 WS를 `close(4000)`→재open이 가장 단순. 원본 브라우저 페이지의 `currentTime` 4초 감시견 대체 |
| 13 | 일시적 수신 지연 (`concealedSamples` 급증, 재생은 진행) | `버퍼링…` | 원본 `onstalled` 대체. WebRTC에는 stalled 이벤트가 없으므로 이 판정이 유일한 소스다 |
| 14 | 재접속 시도 중 | `재접속 중… (N회)` 병기 | 지연은 `backoffMs(tries)=min(1000×tries, 5000)` **선형**. **지수 백오프 금지** — 원본이 '여행 중 재접속은 빠를수록 좋다'로 선형을 택했다 |
| 15 | 갈아타기 (다른 호스트 탭 / 새 코드 입력) | `연결 중…` | 같은 코드면 **no-op**. 다르면 `leave(switch)` → 옛 룸 WS `close(4000)` → 새 코드로 open. 로비 WS는 닫지 않는다. **실패한 방을 붙잡은 세션이 새 입력을 영원히 무시하는 원본의 버그를 재현하지 말 것 — 상태와 무관하게 항상 갈아탄다** |
| 16 | 나가기 | `''` | `leave(leave)` → `close(4001)` → 완전 초기화. status 줄에 `듣기를 종료했습니다` |
| 17 | `audio.play()` 거부 | (stateText 아님) | **별도 줄에** `재생 실패: NotAllowedError`처럼 **에러 이름을 그대로** 노출하고 재탭을 유도한다. 원본 문구 그대로. 조용한 실패 금지 |

### 화면 렌더링 계약

화면은 **`(currentRoomId, stateText)` 두 값만 읽고** `POLL_MS`(500ms)마다 다시 그린다. 이 두 값 외의 것으로 화면을 그리기 시작하면 "화면이 말하는 것"과 "실제 상태"가 갈라질 수 있는 틈이 생긴다.

### 로비 목록 렌더링 계약

`host-online` = **맨 뒤에 추가**(발견 순서 유지, 정렬 금지). `host-update` = **순서를 바꾸지 않고 라벨만 갱신**. `host-offline` = 즉시 회색 비활성 후 `LOBBY_FORGET_MS`(10초) 뒤 제거. 자기 방은 클라이언트가 거른다.

---

## 8. 한국어 문구 사전

**이 절이 이 문서에서 가장 많이 참조될 곳이다.**

두 사람이 각자 문구를 지어내면 톤이 반드시 갈라진다. 원본 앱의 문구는 전부 여기 있고, 웹에서 바뀌는 것은 `→ 웹:`으로 변경 후 문구를 병기했다. **여기 없는 문구를 새로 지어내지 않는다.** 필요해지면 미니 Sync다.

전량은 `web/shared/strings.ts`에 상수로 들어간다.

### 8.1 앱 이름·제목

| 원본 | 웹 |
|---|---|
| 런처 라벨 `같이듣기 PoC` | → 웹: `document.title` = `같이 듣기` |
| 화면 최상단 제목(26sp) `🎧 같이 듣기` | 그대로 |
| 섹션 제목(18sp) `내가 틀기` | 그대로 |
| 섹션 제목(18sp) `같이 듣기` | 그대로 |

### 8.2 status 1줄째 — 상태문구 15종

원본은 `MainActivity.render()` 하나가 status TextView를 독점하고 호스트 이벤트 12종·참여자 이벤트 2종·비콘 실패 1종이 전부 그리로 모였다. 웹은 A와 B가 같은 줄을 공유하므로 `setStatus(owner, text)`로 슬롯을 나눈다([§6](#6-모듈-진입점-고정-시그니처)).

**두 슬롯은 항상 같이 그려진다** — 채워진 것만 ` · `로 이어 붙인다(예: `공유 중 · 듣기를 종료했습니다`). 어느 한쪽을 우선하면 안 되는 이유는 §6 `setStatus` 주석에 있다.

| # | 원본 문구 | 웹 | 소유 | 언제 |
|---|---|---|---|---|
| 1 | `대기 중` | 유지 | **shared** | **양 슬롯이 모두 빈 동안 shell이 그리는 기본값.** A의 초기값이 아니다 — A가 이걸 자기 슬롯에 넣어두면 호스트 슬롯이 페이지 수명 내내 차 있게 되고, 그 순간 B의 문구가 영영 안 보인다 |
| 2 | `공유 중` | 유지 | host | 방 개설 + 소스 준비 완료. **소스가 없으면 이 문구를 쓰지 않는다**(→ 8.7 배너) |
| 3 | `공유를 중지했습니다` | 유지 | host | `stop-share` 후 |
| 4 | `권한이 거부되었습니다` | 유지 | host | `getUserMedia` NotAllowedError |
| 5 | `화면 캡처 동의가 취소되었습니다` | 유지 | host | `getDisplayMedia` 취소/거부 |
| 6 | `이미 배터리 최적화에서 제외되어 있습니다` | **삭제** | — | 웹에 대응물 없음 |
| 7 | `핫스팟 요청 중…` | **삭제** | — | 웹은 AP를 못 연다 |
| 8 | `핫스팟 켜짐` | **삭제** | — | 〃 |
| 9 | `핫스팟은 이미 켜져 있습니다` | **삭제** | — | 〃 |
| 10 | `핫스팟은 켜져 있으나 설정을 읽을 수 없습니다 — <예외>` | **삭제** | — | 〃 |
| 11 | `핫스팟 실패 — <사유>` (사유 5종) | **삭제** | — | 〃 |
| 12 | `핫스팟이 중지되었습니다` | **삭제** | — | 〃 |
| 13 | `핫스팟 호출 예외 — <예외>` | **삭제** | — | 〃 |
| 14 | `핫스팟을 껐습니다` | **삭제** | — | 〃 |
| 15 | `듣기를 종료했습니다` | 유지 | listener | [나가기] 후 |
| — | `자동 발견 실패 — 주소를 직접 입력하세요` (비콘 예외 시 append) | → 웹: `자동 발견 실패 — 아래에 코드를 직접 입력하세요` | listener | 로비 WS 연결 실패 |
| — | (원본 없음) | **신규**: `방을 만들지 못했습니다 — 재시도 중` | host | `room-taken` 수신 후 새 코드로 재시도 중 |

유지 6종 / 삭제 7종 / 개작 1종 / 신규 1종. **목록에 없는 status 문구를 쓰지 않는다.**

### 8.3 status 2줄째

| 원본 | 웹 |
|---|---|
| `내 주소: http://<IP>:7980` | 방 없음 → `내 방: 아직 없음 (공유를 시작하면 코드가 생깁니다)` |
| | 방 있음 → `내 방: A3F9 · https://lt-web.<계정>.workers.dev/#A3F9` |

`setRoomLine()`이 단독 렌더한다. A·B 누구도 이 줄을 직접 그리지 않는다.

### 8.4 호스트 섹션 ('내가 틀기') — A 소유

| 원본 | 웹 |
|---|---|
| 호스트 안내 힌트(13sp 회색) 4줄:<br>`내 폰에서 나는 소리를 친구들에게 보냅니다.`<br>`화면은 가져가지 않습니다 — 안드로이드가 소리 공유에도`<br>`같은 동의를 요구할 뿐입니다.`<br>`알림음·통화는 전달되지 않고, 음악·영상 소리만 갑니다.` | → 웹(파일 모드):<br>`내 기기에서 나는 소리를 친구들에게 보냅니다.`<br>`여기서 고른 곡만 나갑니다 — 알림음·통화는 가지 않습니다.`<br>→ 웹(탭 캡처 모드, 다이얼로그 뜨기 **전에** 표시):<br>`탭을 고르라는 창이 뜹니다. 왼쪽 아래 '탭 오디오도 공유'를 꼭 체크하세요.`<br>`화면은 쓰지 않습니다 — 브라우저가 소리만 주는 방법을 따로 두지 않았을 뿐입니다.`<br>**선제 설명 카피라는 성격을 그대로 계승한다** |
| `공유할 앱 — 선택한 앱의 소리만 나갑니다.` | → 웹: `소리 소스 — 고른 소스의 소리만 나갑니다.` |
| 체크박스 `모든 앱 허용 (고르지 않음)` | **삭제** (라디오라 배타 선택이 기본) |
| 앱 후보 11종 (`YouTube Music` / `멜론` / `Spotify (캡처 거부됨)` …) | → 웹 라디오 3종:<br>`음악 파일 (내 기기에서 고르기)`<br>`브라우저 탭 (모바일 불가)`<br>`마이크 (음질 나쁨 — 비상용)`<br>**`(캡처 거부됨)`처럼 한계를 라벨에 적는 톤을 계승한다** |
| `지금: 모든 앱의 음악·영상 소리가 나갑니다` | → 웹: `지금: <소스명>의 소리가 나갑니다` |
| `지금: <N>개 앱의 소리만 나갑니다` | **삭제** (위에 흡수) |
| `  · 적용하려면 공유를 다시 시작하세요` | 그대로 (**앞 공백 2칸 포함**) |
| 버튼 `공유 시작` / `공유 중지` | 그대로 |
| 버튼 `배터리 최적화 제외 (장시간 필수)` | **삭제** |
| 버튼 `핫스팟 켜기 (Wi-Fi 없을 때)` / `핫스팟 끄기` | → 웹: 버튼 삭제, 안내 카드 제목으로 전환<br>`Wi-Fi 없을 때: 호스트 폰의 핫스팟을 직접 켜세요`<br>`설정 > 모바일 핫스팟을 켜고, 친구들이 그 Wi-Fi에 붙으면 됩니다.` |
| QR 힌트<br>`친구 카메라로 이 QR 을 찍으면 접속됩니다.`<br>`안 되면 직접: <SSID> / <비밀번호>` | → 웹:<br>`친구 카메라로 이 QR 을 찍으면 접속됩니다.`<br>`안 되면 직접: <코드>`<br>(`QR 을` 사이 공백까지 원문 유지) |
| QR 생성 실패 `QR 생성 실패 — 직접 입력: <SSID> / <비밀번호>` | → 웹: `QR 생성 실패 — 직접 입력: <코드>` |
| (원본 없음 — `Build.MODEL` 자동) | **신규**: 이름 입력 1칸.<br>label `이 기기 이름 (친구 목록에 보입니다)`<br>기본값 = UA-CH model 시도 → 실패 시 플랫폼 추정명(`iPhone`/`Android 폰`/`노트북`).<br>**저장은 `ctx.deviceName`을 통해서만** 한다 — 실제 키는 `lt.shared.deviceName`이고 B도 같은 값을 읽는다(§6). 입력 UI 자체는 A 단독 소유이고 B는 라벨을 만들지 않는다.<br>**웹에는 `Build.MODEL` 등가물이 없다** — iOS Safari·데스크탑은 모델명을 절대 주지 않아서, 그대로 두면 `  님의 소리`가 되고 호스트 2명 구분이 불가능해져 갈아타기 데모가 성립하지 않는다 |
| 호스트 알림 제목 `같이듣기 — 내 소리 공유 중` | → 웹: 고정 바 1줄째 + **호스트 MediaSession `title`** (잠금화면에서 보인다) |
| 호스트 알림 본문 `http://<IP>:7980` | → 웹: 고정 바 2줄째 `듣는 사람 <N>명 · <참여 링크>` + MediaSession `artist` = `<참여 링크>`<br>**숫자를 감싸는 문구를 A가 지어내지 않게 `strings.ts`에 `listenerCountText(n)`으로 넣어 동결한다.** 시연 대본이 이 화면을 직접 비추는데(청취자 수 2→1→2), 대시보드의 `clients=2`는 진단 로그 한 줄이라 고정 바 라벨을 대신하지 못한다 |
| 호스트 알림 액션 `공유 중지` | → 웹: 고정 바 버튼 + **MediaSession `setActionHandler('stop')`** (원본 알림 액션의 직계 대체) |

### 8.5 리스너 섹션 ('같이 듣기') — B 소유

| 원본 | 웹 |
|---|---|
| `친구가 공유를 시작하면 아래에 자동으로 뜹니다.` | 그대로 + **신규 보조 1줄**:<br>`같은 Wi-Fi에 있어야 보입니다 — 안 뜨면 아래에 코드를 직접 입력하세요.`<br>(원본 UDP 브로드캐스트의 서브넷 한정 의미를 UX로 계승) |
| 호스트 목록 버튼 `<기기모델명> 님의 소리  (<IP>)` | → 웹: `<기기명> 님의 소리  (<코드>)`<br>**`님의 소리` 뒤 공백 2칸까지 원본과 동일** |
| (원본 없음) | **신규 접미**: ` (소리 준비 중)` — `sourceReady:false`인 방에 회색으로 붙인다 |
| placeholder `직접 입력 (예: 192.168.219.51)` (15sp) | → 웹: `직접 입력 (예: A3F9)` |
| 버튼 `듣기 시작` | 그대로. **입력이 비어 있으면 아무 동작 없음**(원본 동작 유지) |
| 버튼 `나가기` | 그대로 |
| 참여 줄 (미참여) `참여 중이 아닙니다.` | 그대로 (**마침표 포함**) |
| 참여 줄 (참여)<br>`▶ <호스트IP> 에 참여 중 — <상태>`<br>`다른 사람을 누르면 그쪽으로 옮겨갑니다. 그만 들으려면 [나가기].` | 그대로. `<호스트IP>` 자리에만 **기기명 또는 코드**가 들어간다.<br>(`▶` 뒤 공백, `에 참여 중 —` 앞뒤 공백, 2줄째 전문까지 원문 유지) |
| 참여자 알림 제목 `같이 듣는 중` / 본문 `<호스트IP>` / 액션 `나가기` | → 웹: 고정 바 + MediaSession(8.6) |

### 8.6 딥링크 다크 뷰 (`#<코드>` 진입) — B 소유

원본 `CaptureService.page()`의 브라우저 참여 페이지가 **웹 버전의 직접 참고 대상**이다. 상태 어휘가 8.5의 `stateText` 4종과 **별개로 존재한다** — 두 벌이 공존하며, 배치 규칙은 다음과 같다.

> **메인 섹션 참여 상태 줄 = `LISTENER_STATE`(§7) / 딥링크 다크 뷰 = 아래 `PLAYER_VIEW`**

| 원본 | 웹 |
|---|---|
| `<title>` `같이 듣기` | 그대로 |
| 헤더(19px) `🎧 같이 듣기` | 그대로 |
| 버튼 초기 `재생` → 재생 후 `재생 중` | 그대로 (**라벨 전이 포함**) |
| 상태 초기 `버튼을 눌러주세요` | 그대로 |
| `연결 중…` | 그대로 |
| `재접속 중… (N회)` | 그대로. **N = listener-rtc의 `tries`를 그대로 노출** |
| `재생 중 — 화면을 꺼도 계속 들립니다` | → 웹: **`재생 중 — 화면을 켠 채 두세요`**<br>원본 대체. 웹에서 이 문장은 거짓이 된다 |
| `재생 실패: <에러이름>` (예: `재생 실패: NotAllowedError`) | 그대로. **에러 이름을 숨기지 않는다** |
| `스트림 끊김 — 재접속` | 그대로. 소스: `host-stopped` 수신 또는 `connectionState='disconnected'\|'failed'` |
| `버퍼링…` | 그대로. 소스: **WebRTC에 `stalled` 이벤트가 없으므로** `packetsReceived` 정체 1~4초 구간으로 판정 |
| `멈춤 감지 — 재접속` | 그대로. 소스: 정체 4초 이상(감시견) |
| MediaSession `title: '같이 듣기'` / `artist: '호스트의 소리'` | 그대로 |
| (원본 없음) | **신규 전체화면 배너**: `화면을 탭해서 계속 듣기`<br>Wake Lock 자동 재요청이 실패했을 때의 유일한 복구 수단 |

**스타일도 원본을 따른다**: 다크 배경 `#111` / 밝은 글자 `#eee`, 흰색 알약 버튼(`border-radius:999px`, 패딩 22px/52px, 폰트 22px), 상태 텍스트 15px 회색 `#888`, 세로 중앙 정렬 flex, `gap:24px`, `-apple-system` 계열. **이 스타일은 전부 `web/listener/listener.css`에 들어간다 — shared에 넣지 않는다.**

### 8.7 경고 배너 (웹 전용 신규) — `setBanner(owner, text)`

원본에 대응 문구가 없는 것들이다. 전부 웹에서 새로 생긴 실패 모드라, **배정 누락이 눈에 안 띄는 항목**이다.

| 소유 | 트리거 | 문구 |
|---|---|---|
| 양쪽 | `connectionState='failed'` 2회 연속 | `연결이 안 됩니다 — AP 격리 의심. 호스트 폰의 핫스팟을 켜고 모두 그리로 접속하세요` |
| host | `getDisplayMedia` 결과의 `getAudioTracks().length === 0` | `탭 오디오도 공유를 체크하세요 — 소리 트랙이 오지 않았습니다` |
| host | rms가 3초 연속 0 | `소리가 나가지 않습니다 — 재생/음량을 확인하세요` |
| host | 출력 게인 0 또는 `<audio>.muted` | `음소거 상태에서는 화면을 끄면 송출이 끊깁니다` |
| host | 재점유 직후 소스 없음 | `소리 소스를 다시 고르세요 — 마지막 곡: <파일명>` |
| host | 특정 peer의 drop>0이 5초 연속 | `<이름> 연결 불량으로 끊었습니다` |
| host | `ctx.sampleRate !== 48000` | `기기가 48kHz를 주지 않았습니다 (<N>Hz) — 지연이 늘 수 있습니다` |
| listener | `error(room-full)` | `정원이 찼습니다 (최대 4명)` |

### 8.8 진단 로그 · 대시보드

| 원본 | 웹 |
|---|---|
| `AUDIO rms=%5d peak=%5d clients=%d gap=%dms cap=%d%% fill=%d%%  net=%d%% drop=%d`<br>cap<95%면 뒤에 `  <<< 캡처 유실` 부착 | → 웹: **`fill=` 제거**(웹에서는 구조적으로 항상 0)<br>`AUDIO rms=12079 peak=30613 clients=2 gap=1000ms cap=100% net=100% drop=0`<br>`  <<< 캡처 유실` 부착 규칙은 그대로 |
| `PLAY buf= true= net= under=` (참여자 로그) | → 웹: 호스트 대시보드의 청취자별 줄<br>`PLAY p2 buf=42ms true=72ms net=100% under=0 gap=1000ms`<br>+ `  <<< 송신 큐` / `  <<< 수신 버퍼` |
| 판정표 3행 | → 웹: cap 정의만 소스별로 재정의. 파일 모드는 `audio.currentTime` 증분÷gapMs, 캡처 모드는 `totalSamplesDuration` 증분÷gapMs |
| (원본 없음) | **신규**: `상호 오프셋 ≈ 38ms (p2↔p3)` — 청취자별 `(bufMs + outputLatencyMs)`의 최대−최소 |

판정표 원문:

| cap | net | drop | 범인 |
|---|---|---|---|
| <100% | — | — | 소스가 밀림 (파일 재생 스로틀 / 캡처 유실) |
| 100% | <100% | >0 | 송신 큐 (네트워크가 못 따라감) |
| 100% | 100% | 0 | 폰은 무죄 — 받는 쪽 지터버퍼 |

**귀로 판단하지 말 것.** 무음이 나와도 셋이 구분되지 않는다. 원본 README의 그 문장이 웹에서도 그대로 유효하다.

---

## 9. 타이밍 상수표

전부 `protocol.ts`에 있다. **값을 직접 쓰지 않는다 — 상수를 import한다.**

| 상수 | 값 | 원본 근거 | 변경에 합의 필요? |
|---|---|---|---|
| `PROTOCOL_V` | `1` | — | **필요** |
| `HEARTBEAT_MS` | 1000 | UDP 비콘이 정확히 1초 주기로 `LT1\|모델\|IP`를 뿌렸다. 그 주기를 그대로 가져와야 발견 체감이 같아진다 | **필요** (서버 동작 직결) |
| `HOST_TTL_MS` | 3000 | 원본 비콘 수신 타임아웃은 1.5초였다. 웹은 브라우저 백그라운드 스로틀로 하트비트가 밀려서 1.5초는 너무 예민하다 — 3회 여유 | **필요** |
| `LOBBY_FORGET_MS` | 10000 | 원본은 죽은 호스트를 목록에서 **지우지 않았다.** 그게 유령 항목을 만들었으므로 10초 회색 후 제거로 고친다 | 불필요 |
| `POLL_MS` | 500 | `joinTicker`가 0.5초마다 `currentHost`/`stateText`를 비췄다. **README 최대 교훈을 지탱하는 값** | 불필요 |
| `RETRY_BASE_MS` | 1000 | PlayerService가 실패 시 1000ms sleep 후 무한 재시도 | 불필요 |
| `BACKOFF_MAX_MS` | 5000 | 원본 참여 페이지가 `min(1000×tries, 5000)`의 **선형** 백오프. **지수 금지** — "여행 중 재접속은 빠를수록 좋다" | 불필요 |
| `WATCHDOG_MS` | 4000 | 1초 인터벌로 `currentTime`이 4초 안 늘면 "멈춤 감지". 웹은 `packetsReceived` 정체로 판정 | 불필요 |
| `CONNECT_TIMEOUT_MS` | 4000 | `Socket.connect(4000)`과 같은 값. **이게 없으면 브라우저 기본 ICE 타임아웃(수십 초)까지 '연결 중…'에 머무는 침묵 구간이 생긴다 — 조용한 재시도의 재발** | 불필요 |
| `STATS_MS` | 1000 | 원본 AUDIO/PLAY 로그가 1초 창이었다(`gap≈1000ms` 기준 산식) | 불필요 |
| `MAX_LISTENERS` | 4 | 원본은 제한이 없었지만(실측 2명), 웹은 호스트가 PC N개로 Opus를 N회 인코딩하는 mesh라 4가 현실적 상한. 기획 "친구 2~4명"과도 일치 | **필요** |
| `ROOM_GRACE_MS` | 60000 | 호스트 새로고침 시 방코드가 바뀌면 **이미 뿌린 QR·참여 링크가 전부 죽는다.** 60초면 새로고침·탭 크래시 복구·앱 전환 왕복을 전부 덮는다 | **필요** |
| `HOST_TOKEN_TTL_MS` | 6h | 유예보다 훨씬 길어야 "브라우저를 닫았다 몇 분 뒤 다시 열어도 같은 코드"가 성립 | 불필요 |
| `WS_PING_MS` | 25000 | Cloudflare 엣지 idle 타임아웃(약 100초)보다 충분히 짧게 | **필요** |
| `PING_FRAME`/`PONG_FRAME` | `{"t":"ping"}` / `{"t":"pong"}` | autoResponse가 **바이트 일치**로만 매칭한다. 한 글자라도 다르면 DO가 매번 깨어난다 | **필요** |
| `OPUS_FRAME_MS` | 20 | 원본 `CHUNK_MS`(20ms)와 **우연이 아니라 정확히 같다** — 앱의 20ms 청크와 Opus 20ms 프레임이 같은 입자라 진단 산식을 그대로 옮길 수 있다 | 불필요 |
| `JITTER_BUFFER_TARGET_MS` | 40 | "버퍼는 언더런 0을 유지하는 선까지만 줄인다"의 출발값. **기능 감지 후에만** 대입(iOS Safari 27 미만 미지원) | 불필요 |
| `SAMPLE_RATE` ✚ | 48000 | README의 단일 최대 지연 개선. 기본 생성자면 44100이 잡히고 양쪽에 리샘플러가 낀다 | 불필요 (값은 고정) |
| `ROOM_CODE_ALPHABET` | Crockford Base32 (I·L·O·U 제외) | 사람이 손으로 옮겨 적을 때 헷갈리는 글자가 없어야 한다 | **필요** (이미 뿌린 QR·링크가 죽는다) |
| `ROOM_CODE_LEN` | 4 | 32⁴ ≈ 104만. 4인 데모에서 충돌은 무시 가능하고 서버가 `room-taken`으로 최종 판정 | **필요** |
| `MAX_CODE_INPUT` | 12 | 링크를 통째로 붙여넣어도 무한정 길어지지 않게 | 불필요 |
| `ICE_SERVERS` | STUN 1개 | **TURN은 절대 넣지 않는다.** 넣는 순간 미디어가 인터넷으로 새고 "데이터 소모 0"이 깨진다 | **필요** |
| `OPUS_FMTP` | `stereo=1;sprop-stereo=1;maxaveragebitrate=256000;usedtx=0;cbr=1;maxplaybackrate=48000` | Chrome 기본값이 모노 ~32kbps 음성 모드라 음악이 뭉개진다 | 불필요 (값 튜닝은 A 재량) |

---

## 10. 배포 전략

### 배포 슬롯에도 소유권이 있다

실기기 테스트는 HTTPS가 필수다 — `http://LAN-IP`는 secure context가 아니라 `getUserMedia`/`getDisplayMedia`가 통째로 막힌다. 그래서 A도 B도 개발 내내 하루에 몇 번씩 배포한다.

워커가 하나뿐이면 **나중에 배포한 사람의 작업본에 섞여 있던 상대의 낡은 코드가 상대 최신 배포를 덮어쓴다.** 파일은 안 겹치는데 매일 충돌하는 숨은 공유 자원이다. wrangler env 3슬롯으로 구조적으로 끊는다.

| 슬롯 | 명령 | URL | 쓰는 사람 | 언제 |
|---|---|---|---|---|
| `env.a` | `npm run deploy:a` | `https://lt-web-a.<계정>.workers.dev` | **A만** | 아무 때나, 하루 몇 번이든 |
| `env.b` | `npm run deploy:b` | `https://lt-web-b.<계정>.workers.dev` | **B만** | 아무 때나 |
| canonical | `npm run deploy` (가드 통과 필요) | `https://lt-web.<계정>.workers.dev` | 한 사람이, main에서 | Sync 0~4 시점에만 |

핵심은 **Durable Object 네임스페이스가 Worker 스크립트 단위로 갈린다**는 점이다. `lt-web-a` / `lt-web-b` / `lt-web`은 이름이 다른 별개 스크립트라 LobbyDO·RoomDO 저장소가 완전히 독립된다. A의 로비에 B의 유령 호스트가 뜨거나, 같은 방코드가 상대 쪽 방을 열어버리는 사고가 원천 차단된다. **Step 0 검증 관문 (d)가 정확히 이걸 확인한다** — a에서 만든 방코드를 b에 넣으면 `room-not-found`여야 한다.

### joinUrl은 절대 하드코딩하지 않는다

```ts
// server/room-do.ts
const origin = new URL(request.url).origin;   // lt-web-a / lt-web-b / lt-web 자동 대응
send({ t: 'room-created', roomId, hostToken, joinUrl: `${origin}/#${roomId}`, resumed, listeners });
```

클라이언트도 마찬가지로 `ctx.origin` / `ctx.wsUrl()`만 쓴다. A의 QR도 이 joinUrl을 그대로 인코딩하므로 **자기 슬롯에서 찍은 QR이 자기 슬롯으로 간다.**

### wrangler.jsonc 핵심 (Step 0에서 작성·동결)

```jsonc
{
  "$schema": "./node_modules/wrangler/config-schema.json",
  "name": "lt-web",                       // ← canonical
  "main": "server/index.ts",
  "compatibility_date": "2026-09-01",
  "workers_dev": true,
  "observability": { "enabled": true },

  // ★ SPA 폴백이 /ws 업그레이드를 삼키지 않게 run_worker_first를 반드시 함께 준다.
  //    빠뜨리면 시그널링이 index.html을 받고 조용히 죽는다(디버깅 최악 유형).
  "assets": {
    "directory": "dist",
    "binding": "ASSETS",
    "html_handling": "auto-trailing-slash",        // /tools/mock-host/ 가 그대로 열린다
    "not_found_handling": "single-page-application",
    "run_worker_first": ["/ws/*", "/_lobby/*"]
  },

  "durable_objects": {
    "bindings": [
      { "name": "LOBBY", "class_name": "LobbyDO" },
      { "name": "ROOM",  "class_name": "RoomDO"  }
    ]
  },
  // migrations는 top-level 하나만. env에 다시 쓰면 상속이 아니라 override가 된다.
  "migrations": [{ "tag": "v1", "new_sqlite_classes": ["LobbyDO", "RoomDO"] }],

  "env": {
    // ★ durable_objects·assets는 '비상속 키'다. env마다 통째로 다시 적지 않으면
    //    바인딩이 사라진 채 배포되거나 검증에서 떨어진다. 중복이 정상이다.
    "a": {
      "name": "lt-web-a",
      "workers_dev": true,
      "assets": { /* 위와 동일 전문 */ },
      "durable_objects": { /* 위와 동일 전문 */ }
    },
    "b": {
      "name": "lt-web-b",
      "workers_dev": true,
      "assets": { /* 위와 동일 전문 */ },
      "durable_objects": { /* 위와 동일 전문 */ }
    }
  }
}
```

### package.json scripts (동결)

```json
{
  "dev":          "vite",
  "build":        "vite build",
  "typecheck":    "tsc -b",
  "dev:worker:a": "wrangler dev --env a",
  "dev:worker:b": "wrangler dev --env b",
  "dev:lan:a":    "concurrently \"wrangler dev --env a --ip 0.0.0.0\" \"vite --host\"",
  "dev:lan:b":    "concurrently \"wrangler dev --env b --ip 0.0.0.0\" \"vite --host\"",
  "deploy:a":     "npm run build && wrangler deploy --env a",
  "deploy:b":     "npm run build && wrangler deploy --env b",
  "deploy":       "node scripts/guard-canonical.mjs && npm run build && wrangler deploy",
  "preview:a":    "npm run build && wrangler versions upload --env a",
  "preview:b":    "npm run build && wrangler versions upload --env b"
}
```

**로컬 개발 스크립트도 슬롯별로 둘씩 둔다.** 하나만 두고 `--env a`를 기본값으로 박으면 B는 매번 손으로 플래그를 붙이거나 결국 동결된 package.json에 스크립트 한 줄을 추가하고 싶어진다 — 규칙 2가 금지한 바로 그 행동이고, lockfile은 안 건드려도 package.json 충돌은 난다. A는 `:a`만, B는 `:b`만 쓴다.

`scripts/guard-canonical.mjs`는 ①현재 브랜치가 main인지 ②워킹트리가 깨끗한지 ③터미널에 `CANONICAL`을 타이핑했는지를 검사하고 하나라도 어긋나면 `exit 1`. **canonical 오배포를 사람 기억이 아니라 스크립트로 막는다.**

### devDependency (Step 0에서 한 번에, 전부 `-E` 정확 핀)

| 패키지 | 왜 정확 핀인가 |
|---|---|
| `typescript` | lib.dom이 한쪽에서만 바뀌면 `experimental-dom.d.ts` 병합 선언이 한쪽에서만 컴파일 에러를 내고, 그 순간 각자 루트에 `global.d.ts`를 만들게 된다 |
| `vite` | dev 서버 7990 + 3엔트리 빌드. 포트 7980·7981은 원본 네이티브 앱이 쓰므로 피한다 |
| `wrangler` | 버전이 바뀌면 config 스키마 검증이 달라진다 |
| `@cloudflare/workers-types` | wrangler와 세트로 같은 날 핀 |
| `@types/node` | `vite.config.ts`와 `guard-canonical.mjs`용. 없으면 나중에 한쪽이 혼자 설치한다 |
| `vitest` | **지금 테스트를 안 써도 설치해 둔다.** "유닛 테스트 하나만 붙이자"가 나오는 순간 lockfile이 통째로 흔들리는 걸 선제 차단하는 보험 |
| `concurrently` | `dev:lan`용. 이것도 '나중에 누가 혼자 추가할 것' 목록의 대표 항목 |

**QR 라이브러리는 npm 의존성으로 넣지 않는다.** MIT 단일 파일을 `web/host/vendor/qrcode.js`로 복사 + LICENSE 전문 보존 — "런타임 npm 의존성 0" 유지와 QR 자체구현 4~8h 제거를 동시에 얻는다.

### 비상 경로

시연장 인터넷이 죽으면 `npm run dev:lan`으로 노트북에서 LAN 서빙한다. **단 http LAN은 secure context가 아니라 탭 캡처·마이크가 전부 죽고 파일 재생 모드만 산다** — Sync 4 리허설에서 이 사실을 실제로 한 번 확인해 두고, 시연 대본에 "비상 시 파일 재생 전용"이라고 못 박는다.

---

## 11. git 전략

### 대전제 — 커밋·푸시·머지는 사용자가 명시적으로 지시할 때만

에이전트는 파일을 만들고 고칠 뿐, `git commit` / `push` / `merge`를 스스로 실행하지 않는다. 아래는 **지시가 왔을 때 따라야 할 규칙**과 제안할 메시지 형식이다.

### 브랜치 — `type/번호`

| 브랜치 | 담당 | 내용 |
|---|---|---|
| `chore/0` | Step 0 단독 세션 | 스캐폴드 전체. **이것만 main에 들어간 뒤** A·B가 갈라진다 |
| `feat/1` | A | `web/host/**` |
| `feat/2` | B | `web/listener/**` |

A와 B는 반드시 **Step 0 커밋에서** 각자 브랜치를 딴다. 그 이전 커밋에서 따면 shared가 없어 아무것도 컴파일되지 않는다.

### 커밋 메시지 — `type(#번호): 설명`

type은 feat/fix/chore/style/design. **Co-Authored-By 트레일러는 붙이지 않는다.**

```
chore(#0): Step 0 스캐폴드 — shared 동결, 배포 env 분리, mock 2종
feat(#1): 로컬 음악 파일 소스 — MediaStreamDestination 이중 연결
feat(#1): answer SDP opus munging + 청취자별 PC 정리
fix(#2): 갈아타기 시 기존 PC를 닫지 않던 문제
feat(#2): want 플래그 무한 재시도 + 원인별 상태 문구
```

**Step 0은 단일 커밋 1개로 끝낸다.** 여러 커밋으로 쪼개면 A·B가 어느 커밋에서 브랜치를 따야 하는지가 흐려진다.

### 머지

- Sync 시점(1~4)에 각자 브랜치를 main으로 `--no-ff` 머지. 순서는 그때 정하고, 뒤에 들어가는 쪽이 main 위로 rebase한 뒤 머지한다.
- **상대 브랜치를 rebase하거나 force-push하지 않는다.** 자기 브랜치만 건드린다.
- A/B 트리가 한 파일도 겹치지 않으므로 정상적으로는 충돌이 0이어야 한다. **충돌이 났다면 그건 누군가 소유권 규칙을 어긴 신호다 — 머지로 때우지 말고 어디서 경계를 넘었는지부터 찾는다.**
- lockfile 충돌은 자동 머지가 사실상 불가능하다. **양쪽 모두 `package-lock.json`을 수정하지 않는 것이 유일한 방어다.**

### 커밋하지 않는 것

`dist/`, `node_modules/`, `.wrangler/`. 빌드 산출물은 파일이 안 겹쳐도 매 머지마다 충돌하는 전형적인 숨은 공유 자원이다.

---

## 12. 변경 정책

### 무합의 허용 — `PROTOCOL_V` 그대로, 상대에게 통보만

- **`StatsMsg`에 optional 필드 추가·삭제.** 서버가 불투명 릴레이라 **서버 배포조차 필요 없다.** (B가 `firstAudioMs`, `outputLatencyMs`, `audioLevel` 등을 구현 중에 얼마든지 붙일 수 있다)
- **S→C 메시지에 optional 필드 추가.** 수신측은 모르는 필드를 무시해야 한다는 규칙이 이미 박혀 있다.
- **`ERROR_CODES`에 새 code 추가.** 리스너의 `stateForError()`가 모르는 code를 흡수하므로 상대 코드가 깨지지 않는다.
- **클라이언트 전용 타이밍 상수 튜닝** — `JITTER_BUFFER_TARGET_MS`, `WATCHDOG_MS`, `BACKOFF_MAX_MS`, `RETRY_BASE_MS`, `POLL_MS`, `STATS_MS`, `CONNECT_TIMEOUT_MS`, `LOBBY_FORGET_MS`.
- **`LISTENER_STATE` 문구의 오타 수정**(의미가 같은 범위에서).
- 모르는 `t`를 무시하는 규칙 덕에, 한쪽이 실험적 `t`를 임시로 쏘는 것도 상대를 깨뜨리지 않는다(단 정식 채택은 아래 합의 대상).

### 양자 합의 필요 — `PROTOCOL_V` 증가 + shared 파일 동시 수정

- 새 `t` 추가, `t` 이름 변경, **필수** 필드 추가·삭제·타입 변경.
- 엔드포인트 경로, **접속 토폴로지**(소켓 개수·소유·어느 쪽이 닫는지), close code 의미 변경.
- 서버 동작과 직결된 상수 — `HEARTBEAT_MS`, `HOST_TTL_MS`, `MAX_LISTENERS`, `ROOM_GRACE_MS`, `WS_PING_MS`, `PING_FRAME`/`PONG_FRAME`.
- `ROOM_CODE_ALPHABET`, `ROOM_CODE_LEN`, `normalizeCode()`의 **출력이 달라지는** 수정(이미 뿌린 QR·링크가 죽는다).
- **릴레이 규칙 변경** — 특히 서버가 stats를 해석하기 시작하는 것. **불투명 릴레이 원칙 자체가 계약이다.**
- `strings.ts`의 문구 추가·변경. (문구 갈라짐을 막는 게 이 파일의 존재 이유이므로, 여기만은 오타 수정 외 예외가 없다)
- devDependency 추가·버전 변경.

### 변경 절차

1. 먼저 상대에게 알린다(무엇을, 왜).
2. `protocol.ts` + `server/`를 **함께** 고친다.
3. `PROTOCOL_V`를 올린다.
4. 양쪽이 각자 env(`lt-web-a` / `lt-web-b`)에 재배포해 각자 검증한다.
5. canonical에 합의 배포한다.

**v가 다른 클라이언트는 서버가 `bad-version`으로 즉시 끊으므로, 반쪽만 올라간 상태가 '조용히 이상하게 동작'하지 않는다.** 이것도 README의 조용한 실패 금지 원칙의 연장이다.

---

## 13. Step 0 체크리스트

한 사람(한 세션)이 단독으로 수행한다. **두 사람이 Step 0을 공동 편집하면 그 자체가 첫 번째 충돌원이다.** 브랜치 `chore/0`을 main에서 딴다.

### 작업

| # | 항목 |
|---|---|
| 1 | 디렉터리 골격: `web/{shared,host,listener,tools}`, `server/`, `scripts/`. **A/B 트리는 빈 디렉터리라도 이때 만들어 소유 경계를 눈에 보이게 한다** |
| 2 | `.gitignore`에 `node_modules/`, `dist/`, `.wrangler/`, `.dev.vars` 추가 |
| 3 | `package.json` 작성: `type=module`, engines, scripts 12종([§10](#10-배포-전략)). **`dev:worker`·`dev:lan`·`preview`는 반드시 `:a`/`:b` 쌍으로 넣는다** — 한쪽만 있으면 나머지 한 사람이 동결된 파일을 열게 된다 |
| 4 | devDependency 전량 설치 — 반드시 `npm i -D -E`(**캐럿 금지**). 이 설치 이후 lockfile은 두 번 다시 바뀌지 않는다 |
| 5 | tsconfig 3종. **DOM lib과 workers-types를 한 프로그램에 섞지 않는다** |
| 6 | `web/shared/experimental-dom.d.ts` — [§6](#6-모듈-진입점-고정-시그니처)의 선언 전량. 상류와 동일 타입으로 병합 선언 |
| 7 | `vite.config.ts` — root=web, publicDir=false, port 7990 + host:true, outDir=../dist, input 3개(app / mock-host / mock-listener). **포트 7980·7981은 원본 앱이 쓰므로 피한다**.<br>★`server.proxy`를 **여기서 확정해 동결한다**: `{'/ws': {target:'http://127.0.0.1:8787', ws:true}, '/_lobby': {target:'http://127.0.0.1:8787'}}`. 안 넣어두면 A도 B도 "프록시가 있는지 확인"하다가 동결 파일을 동시에 열고, 각자 다른 target 포트로 고쳐 머지 충돌까지 확정된다. 8787은 `wrangler dev` 기본 포트다 |
| 8 | `web/shared/protocol.ts` — [§5](#5-websharedprotocolts-전문) 전문 + 보강 3건 |
| 9 | `web/shared/strings.ts` — [§8](#8-한국어-문구-사전) 전량 |
| 10 | `web/shared/` 나머지: `mount.ts` / `shell.ts` / `ws.ts` / `ice.ts` / `log.ts`. **export 시그니처는 [§6](#6-모듈-진입점-고정-시그니처)에 고정된 이름 그대로** — `RTC_CONFIG`, `openWs()`를 다른 이름으로 만들면 두 프롬프트가 동시에 틀리고, 그 순간 양쪽 모두 동결 파일을 들여다본다. `mount.ts`의 `selfRoom`·`deviceName` 접근자와 `shell.ts`의 status 2슬롯 합성(` · ` 연결, 둘 다 비면 `대기 중`)까지 이때 배선을 끝낸다 |
| 11 | `styles/tokens.css` + `shell.css`. **컴포넌트 스타일을 한 줄도 넣지 않는다.** `#lt-deeplink`는 `position:fixed; inset:0; display:none`과 `[data-active]` 토글만 — **배경색조차 넣지 않는다** |
| 12 | `web/index.html` + `web/main.ts` — **배선까지 완결.** 이후 아무도 이 파일을 열지 않는다 |
| 13 | `web/host/index.ts` + `host.css`, `web/listener/index.ts` + `listener.css` 스텁. **이 시점에 `npm run dev`로 화면이 뜨고 typecheck가 통과해야 한다** |
| 14 | MIT 단일 파일 QR을 `web/host/vendor/`로 벤더링 + `qrcode.d.ts` + LICENSE 전문 |
| 15 | `server/` 작성: `index.ts`(라우팅 + WS 업그레이드 + lobbyKey), `lobby-do.ts`, `room-do.ts`, `lobby-key.ts`, `env.d.ts`. **WebSocket Hibernation(`acceptWebSocket`) 사용.** joinUrl은 `new URL(request.url).origin` |
| 16 | RoomDO에 에러 케이스를 **v1으로** 구현: 호스트 WS close → 즉시 `host-stopped(gone)`, 60초 유예 후 삭제, 유예 중 동일 토큰 재점유 시 같은 코드. 5번째 리스너에게 `room-full`. **`sourceReady` 중계도 여기서 끝낸다** — RoomDO가 `host-announce`의 값을 보관해 `InternalAnnounce`에 싣고, LobbyDO가 변화 시에만 `host-update`에 담는다. 이 필드는 나중에 못 붙인다(생산자가 서버라 protocol.ts와 server/를 동시에 고쳐야 한다 = 동결 파기) |
| 17 | `wrangler.jsonc` — `env.a` / `env.b`를 **미리** 넣는다. `durable_objects`·`assets`는 비상속 키라 env마다 통째로 다시 적는다. `migrations`는 top-level 하나만. `run_worker_first`를 빼면 SPA 폴백이 `/ws` 업그레이드를 삼켜 **시그널링이 조용히 죽는다** |
| 18 | `scripts/guard-canonical.mjs` |
| 19 | `web/tools/mock-host/` — 440Hz + [공유 중지] + **[강제 종료]**. `?name=`·선택적 `?code=` 수용, **hostToken 키를 `lt.shared.mock-host.<name>`으로 name별 분리**. 키가 공용이면 두 번째 탭이 첫 탭을 밀어내서 B의 '방 두 개 갈아타기'와 '재점유' 시험이 서로를 배제한다 |
| 20 | `web/tools/mock-listener/` — `?code=&name=`로 자동 join→answer→재생, **실측 getStats 원시 카운터 5종**을 `STATS_MS` 주기로 송신(가짜 값 금지), `host-stopped(gone)` 때 룸 WS를 닫지 않고 재offer 수락, `?stall=<ms>` 스위치, [나가기]/[강제 종료] |
| 21 | `web/PLAN.md` + `web/CONTRACT.md` 확정 |
| 22 | **동결 선언** — A·B 두 사람이 [§3 절대 만지지 않는 목록](#3-소유권-규칙)을 **함께 읽는다.** 이후 shared 수정은 미니 Sync로만 |
| 23 | 커밋은 **사용자가 명시적으로 지시할 때만.** 지시가 오면 단일 커밋 1개: `chore(#0): Step 0 스캐폴드 — shared 동결, 배포 env 분리, mock 2종` |

### 검증 관문 — 전부 통과해야 Step 0 종료

| | 확인 |
|---|---|
| **(a)** | `npm run typecheck` 무에러 |
| **(b)** | `npm run dev`로 셸과 두 섹션 스텁이 뜬다 |
| **(c)** | `npm run deploy:a`와 `deploy:b`가 각각 성공하고 **서로 다른 URL**을 뱉는다 |
| **(d)** | 두 URL이 **서로 다른 DO 저장소**를 쓴다 — a에서 만든 방코드를 b에 넣으면 `room-not-found`여야 한다 |
| **(e)** | mock-host 1탭 + mock-listener 2탭으로 **440Hz가 실제로 들린다.** 이게 되면 프로토콜·서버·배포가 전부 살아있다는 뜻이다 |
| **(f)** | 폰에서 자기 배포 URL 접속 시 `getUserMedia`가 **secure context로 인정된다** |

---

## 부록 A — 적대 검증 21건의 처리 내역

계획을 두 번 공격했다. 한 번은 "병렬 작업이 충돌할 지점"(10건), 한 번은 "원본 앱 대비 빠진 것"(11건)이다. 전부 수용했고, 어디로 갔는지를 기록해 둔다. **무엇을 고쳤는지보다 왜 놓쳤는지가 다음에 쓸모 있다.**

### 병렬 작업 충돌 10건

| 등급 | 문제 | 처리 |
|---|---|---|
| critical | 배포 타깃이 단일 슬롯 — 파일은 안 겹치는데 매일 덮어쓴다 | wrangler env 3슬롯 ([§10](#10-배포-전략)) |
| critical | 프로토콜이 동결 불가능 — WS 1개가 로비와 룸을 겸한다는 설계가 Hibernation 제약상 구현 불가 | 채널 2분할 ([§4.1](#41-왜-채널이-둘로-쪼개지는가)) |
| major | `create-room`이 항상 새 코드를 발급 → 호스트 새로고침 시 QR 전멸 | `hostToken` 재점유를 **v1에** 포함 |
| major | A의 디커플링 장치 부재 → host-rtc+host-stats 8h가 B에 직렬화 | `tools/mock-listener` 추가 |
| major | QR 자체 구현 4~8h가 어느 노력치에도 없었다 → A 19h→23h로 50:50 붕괴 | MIT 단일 파일 벤더링 (0.5h) |
| major | 엔트리 배선·공통 CSS·딥링크 뷰가 숨은 공유 파일 | Step 0에서 4곳 전부 선점·동결 ([§2](#2-파일-트리와-소유권)) |
| minor | 실험 DOM 타입을 각자 선언 → 같은 파일 충돌 | `experimental-dom.d.ts` 한 곳 + typescript 정확 핀 |
| minor | stats의 `netPct`를 리스너가 계산 불가(분모를 모름) | **원시 카운터로 재정의 + 서버 불투명 릴레이** |
| minor | 호스트 탭 크래시 시 서버 동작이 미정 | `host-stopped(gone)` + 60초 유예를 v1에 |
| minor | 루트 매니페스트 소유자 미지정 → lockfile 충돌 | shared-frozen에 6종 명시 |

### 원본 대비 누락 11건

| 등급 | 문제 | 처리 |
|---|---|---|
| critical | 로비 스코프 미정의 — 전역 로비는 "모르는 사람 방이 뜹니다"가 된다 | 공인 IP 버킷별 LobbyDO + `?lobby=` ([§4.1](#41-왜-채널이-둘로-쪼개지는가)) |
| critical | status 15종의 웹 매핑표 부재 + A/B가 같은 줄을 동시에 쓰는 경합 | `strings.ts` + `setStatus(owner, text)` ([§8.2](#82-status-1줄째--상태문구-15종)) |
| major | 딥링크 뷰 문구 8종 미배정 — QR 유입 경로가 문구 없이 비어 있었다 | `PLAYER_VIEW` 8종 + 배치 규칙 ([§8.6](#86-딥링크-다크-뷰-코드-진입--b-소유)) |
| major | 48kHz 고정 원칙이 어느 모듈에도 없음 — README 최대 성과의 소실 | `SAMPLE_RATE=48000` + 실제값 재확인 + 불일치 배너 |
| major | 호스트 새로고침 시 blob URL 소멸 → 방은 부활, 소리는 없음 (README가 최악이라 못 박은 그 상태) | `sourceReady` + 소스 복구 배너 + rms=0 경보 |
| major | `deviceName` 산출 방법 미정의 — 웹에 `Build.MODEL` 등가물이 없다 | 이름 입력 1칸 + 플랫폼 추정 기본값 ([§8.4](#84-호스트-섹션-내가-틀기--a-소유)) |
| major | cap%/fill 웹 산식 부재 → 판정표 3행 중 1행이 산출 불가 | cap을 소스별로 재정의, fill은 **의도적 제거**로 명문화 ([§8.8](#88-진단-로그--대시보드)) |
| major | 호스트 측 백그라운드 완화책이 통째로 비어 있음 | 호스트 MediaSession + Wake Lock 대칭 배치 + 음소거 경고 |
| major | 참여자 간 상호 동기 — 제품 전제가 무검증 통과 | 오프셋 계측 + jitterBufferTarget 동일 고정 + Sync 3 귀 검증 |
| minor | `CONNECT_TIMEOUT_MS` 등 상수 2종 누락 → 침묵 구간 발생 | protocol.ts 상수에 포함 ([§9](#9-타이밍-상수표)) |
| minor | 웹 전용 실패 2종(AP 격리 / room-full)의 문구·소유자 미지정 | 배너 슬롯 + 문구 8종 ([§8.7](#87-경고-배너-웹-전용-신규--setbannerowner-text)) |

### 문서 정합성 검증 15건 (3차)

계약과 두 프롬프트를 다 쓴 뒤 한 번 더 공격했다. "A가 시키는 일과 B가 시키는 일이 실제로 같은 파일을 만지는가, 계약이 양쪽에 같은 것을 약속하는가"를 파일·필드 단위로 대조한 결과다. **앞의 두 라운드가 설계를 공격했다면 이번엔 문서 자신을 공격했고, 그래서 critical이 3건 더 나왔다.**

| 등급 | 문제 | 처리 |
|---|---|---|
| critical | `sourceReady`가 산문 4곳에 있는데 `protocol.ts`에는 없음 — 서버가 필드를 실어 나르지 않으니 B의 완료 기준이 구현 불가 | 타입 4곳에 추가 ([§5.1](#51-동결-직전-확정된-보강)), 중계 규칙을 [§4.5](#45-서버-규칙)·체크리스트 16에 명시 |
| critical | 리스너가 `selfRoomId`를 알 방법이 없음 — 방 코드는 A 네임스페이스에만 있고 `MountContext`에 읽기 경로가 없었다. B에게 남은 길이 네임스페이스 위반 아니면 동결 파기뿐 | `ctx.selfRoom` 접근자 추가 ([§6](#6-모듈-진입점-고정-시그니처)). `setRoomLine()`을 shell이 중계하므로 A쪽 추가 작업 0 |
| critical | `setStatus`의 '호스트 우선' 합성 규칙이 B 문구를 **영구히** 가림 — panel 모드에선 호스트 슬롯이 `대기 중`으로 항상 차 있다. 로비가 통째로 죽어도 화면엔 `대기 중`만 남는다 | 2슬롯 동시 표시(` · ` 연결)로 변경. `대기 중`을 shared 기본값으로 재정의. **계약이 '조용한 실패 금지'를 직접 어기고 있었다** |
| major | `vite.config.ts`의 `/ws` 프록시가 미규정인데 양쪽 프롬프트가 "있는지 확인해라"고 지시 — 동결 파일을 동시에 열게 만드는 유도 문구 | 프록시를 체크리스트 7에 확정, 두 프롬프트를 확정 서술로 교체 |
| major | 리스너 `deviceName`의 출처가 어디에도 없음 — `join`의 필수 필드인데 입력 UI는 호스트 전용이었다 | `lt.shared.deviceName` + `ctx.deviceName`로 승격. 입력 UI는 A 단독 소유, B는 읽기만 |
| major | mock-host 동결 스펙이 B의 필수 시험 2개를 **동시에 만족시킬 수 없음** — 토큰을 보관하면 2탭이 서로를 밀어내고, 안 하면 재점유 시험이 불가 | `boot({name, code?})` + 토큰 키를 `name`별 분리 |
| major | mock-listener의 '가짜 stats'로는 A의 완료 기준 2개(격리 발동·상호 오프셋)가 원리적으로 검증 불가 | 실측 `getStats` 카운터로 규정 + `?stall=` 예외 + `gone` 때 WS 유지 |
| minor | 프롬프트의 "문구 전량" 포인터가 §8.2·§8.3을 빠뜨림 — 가장 기본적인 상태 문구를 지어내게 되는 경로 | 포인터 교정 + "§8 통독 후 자기 절로" |
| minor | 고정 바의 청취자 수 라벨이 사전에 없음 | `listenerCountText(n)` 동결 |
| minor | `ice.ts`·`ws.ts`의 export 이름이 미고정인데 두 프롬프트가 기정사실로 사용 | [§6](#6-모듈-진입점-고정-시그니처)에 시그니처 고정 |
| minor | 호스트 MediaSession을 '가청 오디오 우대를 받는 조건'이라 단정 — 조사 verdict는 partial이고 면제 트리거는 가청 오디오다 | 보조책으로 격하, 생존은 가청 오디오+Wake Lock에 귀속 |
| minor | `jitterBufferTarget` 미지원 시 '예외'라 서술 — 실제로는 조용히 무시된다 | 증상 정정. **틀린 함정 목록은 진짜 원인에서 눈을 돌리게 한다** |
| minor | 리스너측 48k를 '리샘플러 제거'라 서술 — B의 재생 경로는 AudioContext를 거치지 않는다 | 계측 전용으로 명시, DoD를 기록 항목으로 완화 |
| minor | `dev:worker`가 `--env a` 기본값 하나뿐이라 B가 동결 파일을 고치고 싶어짐 | 스크립트를 `:a`/`:b` 쌍으로 |
| minor | PLAN 내부 모순 — 아이폰 호스트를 Q&A만 단정, 본문·리스크·실측 항목은 조건부 | U4 실측 결과에 연동되는 조건부로 |

---

**관련 문서**: [PLAN.md](./PLAN.md) — 동등성 표, 모듈 배분, 시연 대본, 체크리스트, 리스크, 포기한 것
