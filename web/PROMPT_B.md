# 작업 지시서 — B (리스너 사이드)

> 이 문서를 통째로 읽고 바로 작업을 시작한다.
> 너는 2인 병렬 작업의 **B**다. A(호스트 사이드) 세션이 어딘가에서 동시에 돌고 있지만, **너는 A의 존재와 진척을 모른다는 전제로 일한다.** A를 기다리는 일이 생기면 그건 설계가 잘못된 것이고, 아래 §5의 단독 검증 장치로 전부 우회할 수 있다.

---

## 1. 배경

`같이 듣기(listen-together)`는 **친구끼리 이어폰 한쪽씩 나눠 끼는 걸 없애려고** 만든 안드로이드 PoC다. 호스트 폰이 `AudioPlaybackCapture`로 재생 중인 오디오를 가로채 HTTP/WAV 무압축 PCM으로 `:7980`에 뿌리고, UDP 비콘(`:7981`)으로 자기 존재를 1초마다 알리면, 같은 Wi-Fi의 친구 2~4명이 각자 자기 이어폰으로 듣는다. **네이티브 지연 111ms 실측**, 참여자 데이터 소모 0, 화면을 꺼도 Doze 상태에서 무결. 원본 레포는 `/Users/yoochangheon/Desktop/listen-together`이고 소스는 `app/src/main/java/com/example/lt/{MainActivity,CaptureService,PlayerService}.java`, 교훈 문서는 `/Users/yoochangheon/Desktop/listen-together/README.md`다.

지금 하는 일은 **APK를 제출할 수 없어서 이 앱을 웹으로 그대로 옮기는 것**이다. 원칙은 한 줄이다 — **"앱을 웹에서 구동한다. 별도 웹 기획 없음. 웹에서 물리적으로 안 되는 것만 대체 기획한다."** 화면 구성, 한국어 문구, 상태 전이 순서, 타이밍 상수를 새로 짜지 않는다. 전부 원본에서 그대로 가져온다. 스택은 Vanilla TypeScript + Vite(프레임워크 0, 런타임 npm 의존성 0), 시그널링·배포는 Cloudflare Workers + Durable Objects, 미디어는 WebRTC 1→N mesh(최대 4명), **TURN 금지**(미디어를 LAN에 묶어 "참여자 데이터 소모 ~0"을 지킨다).

**네가 맡은 것은 리스너 사이드 전체다.** 원본의 `PlayerService.java`(끊겨도 계속 붙는 재시도 루프 + 원인별 정직한 상태 문구)와 `MainActivity.java`의 참여자 섹션(자동 발견 목록·갈아타기·0.5초 상태 폴링), 그리고 `CaptureService.page()`가 뿌리던 브라우저 참여 페이지 — 이 셋이 통째로 네 담당이다. **시연에서 제일 많이 비춰지는 화면이 전부 네 쪽이다.**

---

## 2. 네가 소유하는 파일 / 절대 만지지 않는 파일

### 쓰기 권한이 있는 경로 — `web/listener/**` 단 하나

```
web/listener/
├─ index.ts                 # [Step 0 스텁 존재] mount 시그니처는 FROZEN, 본문은 네 것
├─ listener.css             # [Step 0 빈 파일] 리스너 섹션 + 딥링크 다크뷰 스타일 전부 여기
├─ rtc/
│   ├─ machine.ts           # want 플래그 무한 재시도 + 갈아타기 (PlayerService 이식)
│   └─ answer.ts            # offer 수신 → answer, ontrack
├─ player/
│   ├─ gesture.ts           # play() + resume() + wakeLock 단일 제스처 동기 묶음
│   ├─ watchdog.ts          # 4초 정지 감시견 + visibilitychange 복구
│   └─ mediasession.ts      # title '같이 듣기' / artist '호스트의 소리'
├─ ui/
│   ├─ section.ts           # '같이 듣기' 섹션 (호스트 목록·수동입력·나가기)
│   ├─ joining-line.ts      # 0.5초 폴링 참여 상태 줄 (문구 원문 유지)
│   └─ deeplink-view.ts     # #코드 진입 시 다크 전체화면 — DOM·배경색 전부 여기
└─ metrics/
    ├─ inbound.ts           # inbound-rtp 원시 카운터 + outputLatency 가산 표기
    └─ sync.ts              # 청취자 간 오프셋 계측 + jitterBufferTarget 동일 고정
```

파일을 더 쪼개고 싶으면 **`web/listener/` 안에서는 마음대로 해도 된다.** 위 목록은 최소 골격이지 상한이 아니다.

### 절대 만지지 않는 파일 (shared-frozen) — **쓰기 금지, 읽기는 권장**

```
package.json          package-lock.json     tsconfig.json
tsconfig.web.json     tsconfig.server.json  vite.config.ts
wrangler.jsonc        .gitignore            scripts/**
server/**             web/index.html        web/main.ts
web/shared/**         web/tools/**          web/CONTRACT.md
web/host/**           (← A의 트리. 한 파일도 열지 않는다. vendor/ 포함)
```

**`npm i -D` 를 혼자 돌리지 않는다.** devDependency 추가·npm script 추가도 '수정'이고, 한쪽이 lockfile을 건드리는 순간 자동 머지가 사실상 불가능해진다. 필요한 devDep은 Step 0에서 전부 설치돼 있다.

### ★ shared-frozen을 고쳐야 할 것 같으면 — 고치지 말고 멈춰라

`protocol.ts`에 필드를 하나 추가해야 할 것 같거나, `shell.css`에 셀렉터를 하나만 넣고 싶거나, `server/room-do.ts`가 틀린 것 같아 보이는 순간이 온다. **딥링크 뷰를 만들다 보면 특히 `shell.css`를 건드리고 싶어질 것이다** — `#lt-deeplink`는 shared가 `position:fixed; inset:0; display:none`과 `[data-active]` 토글만 갖고 있고, **배경색조차 네 소유다.** 다크 배경 `#111`은 `listener.css`에 넣는다.

정말로 shared를 고쳐야 하면 자기 브랜치에서 고치지 않는다. **작업을 멈추고 사용자에게 보고한다.** 보고 형식:

1. 무엇을 고쳐야 하는가 (파일·심볼 단위로)
2. 왜 지금 방법으로는 안 되는가 (우회를 시도했는지 포함)
3. 이게 `PROTOCOL_V` 증가가 필요한 변경인지 아닌지 (CONTRACT.md §12 기준)

이걸 '미니 Sync'라고 부른다. 한 사람이 단독으로 main에서 고치고, 양쪽이 각자 rebase한다. **두 사람이 동시에 shared를 여는 상황 자체를 만들지 않는 게 이 규칙의 전부다.**

단, 다음은 **합의 없이 해도 된다** (CONTRACT.md §12) — **이게 네 쪽에 걸린 특권이다**:
- **`StatsMsg`에 optional 필드를 마음대로 추가할 수 있다.** 서버가 stats를 불투명 릴레이(내용을 읽지도 검증하지도 않음)하도록 설계했기 때문에 **서버 재배포조차 필요 없다.** `firstAudioMs`, `outputLatencyMs`, `audioLevel` 같은 걸 구현 중에 얼마든지 붙여라. (기존 필수 필드 5종의 삭제·의미 변경은 예외에 포함되지 않는다)
- 클라이언트 전용 타이밍 상수 튜닝 — `JITTER_BUFFER_TARGET_MS`, `WATCHDOG_MS`, `BACKOFF_MAX_MS`, `RETRY_BASE_MS`, `POLL_MS`, `STATS_MS`, `CONNECT_TIMEOUT_MS`, `LOBBY_FORGET_MS`. (단 **값 변경은 `protocol.ts` 수정이라 미니 Sync가 필요하다** — 실측으로 값을 바꿔야 한다는 결론이 나오면 그 근거 숫자와 함께 보고해라)

### 네임스페이스 (충돌 방지 — 반드시 지킬 것)

| 종류 | 네 접두사 |
|---|---|
| DOM id / class | `l-` (A는 `h-`, shared는 `lt-`) |
| localStorage 키 | `lt.listener.*` (A는 `lt.host.*`) |

---

## 3. 시작 전에 읽어야 할 것

### 필수 (순서대로)

| # | 파일 | 무엇을 얻는가 |
|---|---|---|
| 1 | `/Users/yoochangheon/Desktop/listen-together/web/CONTRACT.md` | **동결 계약 전문.** 이게 판정 기준이다. §2 소유권, §4 프로토콜, §5 `protocol.ts` 전문, §6 mount 시그니처, **§7 리스너 상태 전이표 17행(← 네 사양서 그 자체다)**, §8 한국어 문구 사전, §9 타이밍 상수표, §10 배포. **§8 전체를 한 번 통독한 뒤 자기 절로 내려가라. 네 문구는 §8.2(listener 소유 행)·§8.5·§8.6·§8.7이다** — §8.2에 `듣기를 종료했습니다`와 `자동 발견 실패 — 아래에 코드를 직접 입력하세요`가 들어 있다 |
| 2 | `/Users/yoochangheon/Desktop/listen-together/web/PLAN.md` | 왜 이렇게 만드는가. §1 동등성 표(21행), §2 아키텍처, 시연 대본. **시연 Act 3·4·5·7·9가 전부 네 화면이다 — 알고 짜라** |
| 3 | `/Users/yoochangheon/Desktop/listen-together/README.md` | 원본 교훈 문서. 특히 **"조용한 재시도 금지"**(= 네 모듈의 존재 이유), 지연 내역 표, 48kHz |
| 4 | `app/src/main/java/com/example/lt/PlayerService.java` | **네 1번 대응 원본.** 최소한 아래 표의 지점들은 직접 읽어라 |
| 5 | `app/src/main/java/com/example/lt/MainActivity.java` 참여자 섹션 | **네 2번 대응 원본.** 라인 150~200(섹션 구성), 541~570(`refreshJoining`/`joinTicker`), 580~640(비콘 수신·목록·`isMine`) |
| 6 | `app/src/main/java/com/example/lt/CaptureService.java` 의 `page()` (라인 ~649–695) | **네 3번 대응 원본.** 딥링크 다크 뷰의 직접 참고 대상. 문구 8종·스타일·재시도 로직이 전부 여기 있다 |
| 7 | `web/shared/protocol.ts` · `web/shared/strings.ts` | 실물 계약. 문서와 소스가 어긋나면 **소스가 이긴다** |
| 8 | `web/shared/mount.ts` · `web/shared/ice.ts` · `web/shared/ws.ts` · `web/shared/log.ts` | 네가 import할 것들의 실제 시그니처 |
| 9 | `web/tools/mock-host/main.ts` | 네 단독 검증 장치가 어떤 프레임을 보내는지 |

### 원본에서 반드시 볼 지점

| 파일:줄 | 무엇 | 네가 이식할 것 |
|---|---|---|
| `PlayerService.java:105–120` | `normalizeHost()` + 주석 | **알림에 뜬 `http://10.13.34.219:7980`을 통째로 붙여넣어 `UnknownHostException`으로 조용히 실패하던 걸 구제한 함수.** 웹 대응물 `normalizeCode()`가 `protocol.ts`에 이미 있다 — 직접 파싱하지 말고 그걸 써라 |
| `PlayerService.java:121–126` | `setState()` + **"상태를 한 곳에서만 바꾼다. 화면은 이 두 값만 읽는다"** | **이게 네 렌더링 계약 전문이다.** 화면은 `(currentRoomId, stateText)` 두 값만 읽는다 |
| `PlayerService.java:183–200` | `playLoop()` 진입부 + **"매 회차 현재 대상을 다시 읽는다"** | 갈아타기가 동작하는 원리. `want`를 매 바퀴 다시 읽는다 |
| `PlayerService.java:192` | `sock.connect(…, 4000)` | `CONNECT_TIMEOUT_MS`의 출처 |
| `PlayerService.java:288–298` | catch 블록 + **"실패를 화면까지 올린다. 조용히 재시도만 하면 '듣는 중'인데 소리만 안 나는 상태로 보인다. 그게 제일 나쁘다"** | **이 프로젝트 전체의 최대 교훈이고, 네 모듈이 존재하는 이유다.** `UnknownHostException` 분기가 웹의 `room-not-found`다 |
| `PlayerService.java:83` | `if (want.equals(host)) return;` | **같은 코드면 no-op.** 갈아타기의 유일한 예외 |
| `MainActivity.java:550–565` | `refreshJoining()` + `joinTicker` | `POLL_MS`(500) 폴링. 문구 원문이 여기 있다 |
| `MainActivity.java:587` | `if (f.length >= 3 && "LT1".equals(f[0]) && !isMine(f[2]))` | **자기 방을 목록에서 거르는 필터.** 웹에서는 `selfRoomId` 대조로 대체 |
| `MainActivity.java:614` | `button(e.getValue() + " 님의 소리  (" + e.getKey() + ")", …)` | **`님의 소리` 뒤 공백 2칸.** `protocol.ts`의 `hostButtonText()`를 써라 |
| `MainActivity.java:601` | `status.append("\n자동 발견 실패 — 주소를 직접 입력하세요")` | 비콘 실패 → 웹은 로비 WS 연결 실패 |
| `CaptureService.java:663–692` | `page()`의 JS — `want`/`tries`/선형 백오프/4초 감시견/`visibilitychange` | **딥링크 뷰 로직 전문이 여기 있다.** 8종 문구와 버튼 라벨 전이(`재생`→`재생 중`)까지 |

---

## 4. 구현할 모듈

시작 전 확인: `web/shared/protocol.ts`가 존재하고 `npm run typecheck`가 통과하는가. **없거나 실패하면 Step 0 스캐폴드가 아직 안 끝난 것이다 — 네가 shared를 만들지 말고 멈추고 사용자에게 보고하라.**

### 4.0 진입점 — `web/listener/index.ts`

```ts
export function mount(el: HTMLElement, ctx: MountContext): MountHandle
```

시그니처는 **FROZEN**이다. 바꾸려면 미니 Sync. **`panel`과 `deeplink` 두 모드가 이 함수 하나로 들어온다:**

| 모드 | 언제 | 컨테이너 | 무엇을 렌더 |
|---|---|---|---|
| `panel` | 일반 진입 | `#l-root` | '같이 듣기' 섹션 (§4.4) |
| `deeplink` | `#A3F9`로 진입 (QR 스캔 유입) | 빈 `#lt-deeplink` | **다크 전체화면 참여 전용 뷰 (§4.5).** 배경색 포함 내부 DOM·스타일 전부 네 것 |

`ctx.initialRoomCode`가 `normalizeCode()` 통과분으로 들어온다. `ctx.onTeardown()`에 정리 훅을 반드시 등록하고, `destroy()`에서 WS 2개·PC·AudioContext·타이머·Wake Lock을 전부 회수해라.

`ctx`에서 쓸 것: `setStatus('listener', text)`, `setBanner('listener', text | null)`, `wsUrl(path)`, `origin`. **`setStatus`의 첫 인자를 `'listener'`로 고정하는 게 계약이다** — 슬롯을 나눠 쓰지 않으면 네 `듣기를 종료했습니다`가 A의 `공유 중`을 지운다. 그리고 **`setRoomLine()`은 호스트 전용이다. 절대 호출하지 마라.**

**URL·origin을 하드코딩하지 않는다.** 배포 슬롯이 3개(a/b/canonical)다. 항상 `ctx.wsUrl()` / `protocol.ts`의 `LOBBY_PATH`·`roomWsPath()`만 쓴다.

---

### 4.1 소켓 토폴로지 — 리스너는 소켓 2개다

이 구조를 잘못 잡으면 나머지가 전부 어긋난다. **Hibernation WebSocket은 `acceptWebSocket()`을 호출한 DO 인스턴스 1개에 귀속되어 소켓 하나가 LobbyDO → RoomDO로 옮겨갈 수 없다.** 그래서 채널이 물리적으로 둘이다.

| 소켓 | 경로 | 수명 | 규칙 |
|---|---|---|---|
| **로비 WS** | `ctx.wsUrl(LOBBY_PATH)` | **페이지가 열려 있는 내내** | 첫 프레임은 반드시 `{t:'lobby-hello', v:PROTOCOL_V, selfRoomId}`. **`selfRoomId`는 `ctx.selfRoom.get()`에서 얻는다**(§6) — A의 `localStorage['lt.host.*']`를 읽지 마라, 네임스페이스 위반이고 A가 키를 바꾸면 조용히 깨진다. 호스트가 로비 소켓보다 늦게 방을 열 수 있으니 `ctx.selfRoom.subscribe()`로 나중 값도 받아라. **join·갈아타기·나가기 어느 경우에도 절대 닫지 않는다.** UDP 비콘 수신 스레드의 직계 대체 |
| **룸 WS** | `ctx.wsUrl(roomWsPath(code))` | 참여 중에만 | 첫 프레임은 반드시 `{t:'join', v:PROTOCOL_V, deviceName}`. **`deviceName`은 `ctx.deviceName.get()`에서 얻는다**(§6, 키는 `lt.shared.deviceName`) — 이름 입력 UI는 A 단독 소유이고 **너는 읽기만 한다.** 이름 입력 칸을 새로 만들지 마라(§8에 없는 한국어 라벨을 지어내게 된다). 빈 문자열을 보내도 안 된다 — 이 값이 그대로 A의 배너 `<이름> 연결 불량으로 끊었습니다`에 박힌다 |

- **갈아타기** = `{t:'leave', reason:'switch'}` 송신 → 옛 룸 WS `close(CLOSE.SWITCH /* 4000 */)` → 새 코드로 새 룸 WS open. **로비 WS는 건드리지 않는다.** 이 규칙 덕에 갈아타는 중에도 발견 목록이 끊기지 않는다
- **나가기** = `{t:'leave', reason:'leave'}` → 룸 WS만 `close(CLOSE.LEAVE /* 4001 */)`. 로비 WS 유지
- **원본은 `onPause`에서 비콘 수신을 멈췄지만, 웹은 `visibilitychange`에도 로비 소켓을 유지한다.** 복귀 시 `hosts` 스냅샷을 다시 받지 않는다 — `host-online/update/offline` 증분이 계속 들어온다
- 로비 소켓이 **실제로 끊기면**(네트워크 단절 등) 선형 백오프로 재연결하고, 새로 받은 `hosts` 스냅샷으로 목록을 교체한다
- `WS_PING_MS`(25000) 주기로 **양쪽 소켓 모두** `PING_FRAME` 상수를 **그대로** 송신한다. 한 글자라도 다르면 autoResponse가 안 먹어 DO가 매번 깨어난다
- 한 사람이 호스트와 리스너를 겸하면 소켓이 3개가 된다. **자기 방을 목록에서 거르는 최종 책임은 클라이언트에 있다** — `selfRoomId`는 서버 측 힌트일 뿐이고, 호스트가 로비 소켓보다 늦게 방을 열 수 있기 때문이다 (원본 `isMine()`의 대체)

---

### 4.2 `rtc/machine.ts` — 재시도 상태기계 (6h). **이 모듈이 프로젝트의 최대 교훈을 지탱한다**

CONTRACT.md **§7 전이표 17행이 이 모듈의 사양서 전문이다.** 그대로 구현해라. 요약:

| 이벤트 | → stateText |
|---|---|
| 초기 / 나가기 완료 | `''` (IDLE), `currentRoomId=null` |
| join 시작 (목록 탭 또는 `듣기 시작`) | `연결 중…` |
| `joined` + `hostPresent:true` | `연결 중…` **유지** |
| **`getStats()`의 `packetsReceived`가 실제로 증가** | **`듣는 중`** + `tries=0` |
| `error(room-not-found)` | `주소를 찾을 수 없음 — 재시도 중` |
| WS open 실패 / `connectionState='failed'` / 그 밖 | `연결 실패 — 재시도 중` |
| `error(room-full)` | `인원이 가득 찼습니다 — 재시도 중` |
| **모르는 error code** | `stateForError()`가 `연결 실패 — 재시도 중`으로 흡수 |
| `host-stopped(stop)` | `스트림 끊김 — 재접속` |
| `host-stopped(gone)` | `스트림 끊김 — 재접속`, **룸 WS를 닫지 않는다** |
| `joined` + `hostPresent:false` | `스트림 끊김 — 재접속` |
| `WATCHDOG_MS`(4초) 정체 | `멈춤 감지 — 재접속` |
| `concealedSamples` 급증 (재생은 진행) | `버퍼링…` |
| 재접속 중 | `재접속 중… (N회)` 병기 |
| 갈아타기 | `연결 중…` |

**반드시 지킬 네 가지:**

1. **`듣는 중` 전이 조건은 `packetsReceived`가 실제로 증가했을 때 하나뿐이다.** `ontrack`이나 `connectionState === 'connected'`만으로 전이하면 **원본이 3분 내내 거짓말했던 그 버그를 그대로 재현하게 된다.** 이게 이 프로젝트에서 사람을 제일 오래 태운 버그고, 시연 Act 4(1:35)에서 "연결 중…이 잠깐 뜨는 게 중요합니다"라고 손가락으로 짚어 보여줄 장면의 근거다.

2. **선형 백오프.** `backoffMs(tries) = min(1000 × tries, 5000)` — `protocol.ts`의 `backoffMs()`를 그대로 써라. **지수 백오프 금지.** 원본이 "여행 중 재접속은 빠를수록 좋다"로 선형을 택했다.

3. **`CONNECT_TIMEOUT_MS`(4000) 안에 붙지 않으면 버리고 재시도한다.** 이게 없으면 브라우저 기본 ICE 타임아웃(수십 초)까지 `연결 중…`에 머무는 침묵 구간이 생긴다 — **"조용한 재시도"의 재발이다.**

4. **갈아타기는 상태와 무관하게 항상 동작한다.** 원본 버그: 한 번 잘못된 주소로 붙으면 그 세션이 살아 있는 한 새 입력을 영원히 무시했다. 뭘 눌러도 안 먹혔다. **그 버그를 재현하지 마라.** 단 **같은 코드면 no-op**(원본 `if (want.equals(host)) return;`).

**`room-full`은 "무한 재시도" 원칙의 유일한 예외다.** `want`는 유지하되(자리가 빌 수 있다) 재시도 간격을 늘려 정원 찬 방을 1초마다 두드리지 않는다. 배너 `정원이 찼습니다 (최대 4명)`.

**`host-stopped(gone)`에서 룸 WS를 닫지 마라.** 방은 `ROOM_GRACE_MS`(60초) 동안 살아 있고, 호스트가 재점유하면 `peer-joined`가 다시 발사되어 **새 offer가 온다.** PC만 정리하고 대기하면 자동 복구된다. 유예가 만료되면 서버가 `close(4002)`하고, 그때부터 `room-not-found` 경로로 자연히 떨어진다. **시연 Act 8(8:00, 호스트 새로고침)이 정확히 이 경로다.**

---

### 4.3 `rtc/answer.ts` — offer 수신 → answer

- `offer` 수신 → `new RTCPeerConnection(RTC_CONFIG)`(← `shared/ice.ts`) → `setRemoteDescription(offer)` → `createAnswer()` → `setLocalDescription()` → `{t:'answer', sdp}` 송신.
- **리스너는 SDP를 munging하지 않는다.** opus fmtp munging은 A가 **받은 answer에** 한다. 네가 `setLocalDescription` 전에 자기 answer를 고치면 Chrome M138+가 봉쇄해 예외가 나거나 조용히 무시된다.
- `onicecandidate` → `{t:'ice', candidate: e.candidate ? e.candidate.toJSON() : null}`. **`null`(end-of-candidates)도 반드시 보낸다** — 삼키면 ICE가 늦게 끝나 첫 소리가 느려진다.
- **`addIceCandidate()`는 `setRemoteDescription()` 이후에만 유효하다.** 먼저 도착한 후보는 큐에 쌓아 뒀다가 remote description이 잡힌 뒤 flush해라. 이걸 빼먹으면 **가끔** 연결이 안 되는(재현이 안 되는) 버그가 생긴다.
- `ontrack` → `audio.srcObject = e.streams[0]`. `<audio playsinline>`를 **DOM에 실제로 붙인다.**
- **`jitterBufferTarget`은 기능 감지 후에만 대입한다:**
  ```ts
  const r = pc.getReceivers().find(r => r.track.kind === 'audio');
  if (r && 'jitterBufferTarget' in r) r.jitterBufferTarget = JITTER_BUFFER_TARGET_MS;  // ms
  else if (r && 'playoutDelayHint' in r) r.playoutDelayHint = JITTER_BUFFER_TARGET_MS / 1000;  // 초 단위 구명칭
  // 둘 다 없으면(iOS Safari 27 미만) 기본 적응형 NetEq에 맡긴다 — 예외를 던지지 마라
  ```
  **단위가 다르다**(`jitterBufferTarget`은 ms, `playoutDelayHint`는 초). 섞으면 지연이 1000배가 된다.

---

### 4.4 `ui/` — '같이 듣기' 섹션 (4h)

**문구는 전부 `web/shared/strings.ts`에서 import한다. 여기 없는 문구를 지어내지 않는다 — 필요해지면 미니 Sync다.** 전량은 CONTRACT.md §8.5에 표로 있다.

| 요소 | 요구사항 |
|---|---|
| 섹션 제목 | `같이 듣기` (18sp 대응) |
| 안내 힌트 | `친구가 공유를 시작하면 아래에 자동으로 뜹니다.` + **신규 보조 1줄** `같은 Wi-Fi에 있어야 보입니다 — 안 뜨면 아래에 코드를 직접 입력하세요.` (원본 UDP 브로드캐스트의 서브넷 한정 의미를 UX로 계승) |
| **호스트 목록** | `hostButtonText(deviceName, roomId)` → `<기기명> 님의 소리  (<코드>)`. **`님의 소리` 뒤 공백 2칸.** 직접 문자열을 조립하지 말고 `protocol.ts`의 함수를 써라 |
| `sourceReady:false` | 회색 접미 ` (소리 준비 중)` 를 붙인다 |
| 수동 입력 | placeholder `직접 입력 (예: A3F9)` |
| 버튼 `듣기 시작` | **입력이 비어 있으면 아무 동작 없음**(원본 동작 유지). `normalizeCode()` 결과가 `null`이어도 무동작. **단 길이가 4가 아니어도 서버로 보낸다** — 엉뚱한 값이라도 `room-not-found`로 **눈에 보이게** 실패시키는 쪽이 버튼이 무반응인 것보다 낫다 |
| 버튼 `나가기` | 원문 그대로 |
| 참여 상태 줄 (`ui/joining-line.ts`) | `POLL_MS`(500) 폴링. **`(currentRoomId, stateText)` 두 값만 읽는다.** 미참여 = `참여 중이 아닙니다.`(마침표 포함), 참여 = `joiningText(hostLabel, stateText)` — `▶ <기기명 또는 코드> 에 참여 중 — <상태>` + 2줄째 `다른 사람을 누르면 그쪽으로 옮겨갑니다. 그만 들으려면 [나가기].` **전문·공백 원문 유지** |

**로비 목록 렌더링 계약 (원본 `LinkedHashMap`의 "발견 순서 유지"를 그대로):**

- `hosts` 스냅샷 → 서버가 `since` 오름차순으로 준다. **그대로 그린다. 다시 정렬하지 마라**
- `host-online` → **맨 뒤에 추가.** 정렬하지 않는다
- `host-update` → **순서를 바꾸지 않고 라벨만 갱신**
- `host-offline` → **즉시 회색 비활성**, `LOBBY_FORGET_MS`(10초) 뒤 제거. 원본은 죽은 호스트를 목록에서 지우지 않았고 그게 '탭해도 안 붙는 유령 항목'을 만들었으므로 웹에서는 고친다
- **자기 방은 클라이언트가 거른다** (`selfRoomId` 대조)
- 로비 WS 연결 실패 → `setStatus('listener', '자동 발견 실패 — 아래에 코드를 직접 입력하세요')`

**CSS는 전부 `web/listener/listener.css`에 넣는다.** "shared CSS에 셀렉터 하나만 추가하고 싶다"는 생각이 들면 그건 `listener.css`로 갈 물건이다.

---

### 4.5 `ui/deeplink-view.ts` — 다크 전체화면 뷰. **QR 스캔 유입 경로 전부가 여기다**

원본 `CaptureService.page()`가 직접 참고 대상이다. **상태 어휘가 §4.2의 `LISTENER_STATE` 4종과 별개로 존재한다** — 두 벌이 공존하고 배치 규칙은 이렇다:

> **메인 섹션 참여 상태 줄 = `LISTENER_STATE` / 딥링크 다크 뷰 = `PLAYER_VIEW`**

**`PLAYER_VIEW` 8종** (전부 `strings.ts`에서 import):

| 문구 | 웹에서의 소스 |
|---|---|
| `버튼을 눌러주세요` | 초기 |
| `연결 중…` | join 송신 후 |
| `재접속 중… (N회)` | **N = `machine.ts`의 `tries`를 그대로 노출** |
| **`재생 중 — 화면을 켠 채 두세요`** | 원본은 `재생 중 — 화면을 꺼도 계속 들립니다`였다. **웹에서 그 문장은 거짓이 되므로 대체한다.** 문구표에 '원문 대체' 주석을 남길 것 |
| `재생 실패: <에러이름>` | `play()` 리젝트의 `err.name` 원문(`NotAllowedError` 등). **에러 이름을 숨기지 않는다** |
| `스트림 끊김 — 재접속` | `host-stopped` 수신 또는 `connectionState='disconnected'\|'failed'` |
| `버퍼링…` | **WebRTC에 `stalled` 이벤트가 없으므로** `packetsReceived` 정체 1~4초 구간으로 판정한다. 이 판정이 유일한 소스다 |
| `멈춤 감지 — 재접속` | 정체 4초 이상 (감시견) |

버튼 라벨 전이도 원본 그대로: **`재생` → 재생 후 `재생 중`.**

**스타일도 원본을 따른다** — 다크 배경 `#111` / 밝은 글자 `#eee`, 흰색 알약 버튼(`border-radius:999px`, padding `22px 52px`, font-size 22px, `font-weight:600`), 상태 텍스트 15px 회색 `#888` `min-height:22px`, 세로 중앙 정렬 flex, `gap:24px`, `-apple-system` 계열, 헤더 19px `🎧 같이 듣기`. **이 스타일 전부가 `listener.css`에 들어간다. shared에 넣지 않는다.**

**신규 전체화면 배너**: `화면을 탭해서 계속 듣기` — Wake Lock 자동 재요청이 실패했을 때의 **유일한 복구 수단**이다. 시연 Act 9(8:50)에서 이 배너를 탭해 복귀하는 장면이 클로징 직전 마지막 데모다. **자동 복구를 믿지 않고, 실패했다는 사실을 화면에 띄우고 사용자에게 탭을 받는다.**

---

### 4.6 `player/` — 재생과 생존 (6h)

**`player/gesture.ts` — 이 파일의 30줄이 iOS 성패를 가른다**

```
버튼 onclick 핸들러 안에서, await를 하나도 끼우지 않고:
  1) audio.play()               ← Promise를 받되 await하지 않는다
  2) audioCtx.resume()          ← 마찬가지
  3) navigator.wakeLock.request('screen')   ← 마찬가지
  세 개를 전부 "시작"한 뒤, 결과는 .then()/.catch()로 처리한다
```

**`await` 뒤로 미루면 제스처 컨텍스트를 잃어 iOS에서 전부 실패한다.** 이건 iOS Safari의 오래된 규칙이고 우회가 없다. 실패하면 `playFailedText(e.name)`으로 **별도 줄에** 에러 이름을 그대로 노출하고 재탭을 유도한다 — 조용한 실패 금지.

**`AudioContext`는 `new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' })`** 로 연다(`SAMPLE_RATE`는 `protocol.ts`에서 import, 48000). 기본 생성자면 기기에 따라 44100이 잡히고 Opus 내부 48k와의 사이에 리샘플러가 껴서 **README가 제거한 46ms가 그대로 돌아온다.** 생성 후 `ctx.sampleRate`를 재확인하고 `metrics`에 실린다.

**`player/watchdog.ts`**

- 1초 인터벌. `getStats()`의 `inbound-rtp.packetsReceived`가 **정체 1~4초** → `버퍼링…`, **4초 이상**(`WATCHDOG_MS`) → `멈춤 감지 — 재접속` 후 PC 재협상(룸 WS를 `close(4000)` → 재open이 가장 단순하다).
- 원본은 `<audio>.currentTime`이 4초 안 늘면 판정했다. 웹 포팅판은 `packetsReceived` 정체로 판정한다 — **이벤트가 안 뜬 채 재생만 멈추는 게 가장 흔한 실패이기 때문에** 감시견이 필요하다는 판단은 원본 그대로다.
- **`visibilitychange`**: `!document.hidden && want && audio.paused`면 즉시 복구 시도 + **Wake Lock 재요청**. sentinel의 `release` 이벤트에서도 재요청. 자동 재요청이 실패하면 전체화면 배너(§4.5).

**`player/mediasession.ts`**

- `metadata = new MediaMetadata({ title: '같이 듣기', artist: '호스트의 소리' })` — 원본 그대로.
- `playbackState`를 실제 상태와 맞추고, 나가기 액션을 핸들러로 연결한다. 원본 참여자 알림(제목 `같이 듣는 중` / 액션 `나가기`)의 대체다.

---

### 4.7 `metrics/` — 진단 리포트 (3h + 2h)

**`metrics/inbound.ts`** — `STATS_MS`(1000) 주기로 `{t:'stats', …}` 송신

**전부 원시 누적 카운터로만 보낸다.** `netPct` 같은 파생치를 계산하지 마라 — **기대 패킷 수라는 분모를 리스너는 모른다**(호스트의 `outbound-rtp`만 안다). 파생은 호스트가 `deriveListenerStats()`로 자기 송신 카운터와 대조해서 한다.

필수 5종 (`inbound-rtp`에서 그대로):
`packetsReceived` / `jitterBufferDelay`(누적 초) / `jitterBufferEmittedCount`(누적 샘플) / `concealedSamples`(누적) / `ts`(`Date.now()`)

optional — **합의 없이 추가할 수 있는 영역이다. 적극적으로 채워라:**
`packetsLost`, `concealmentEvents`, `totalSamplesReceived`, **`outputLatencyMs`**(`ctx.outputLatency × 1000` — README의 "buf만 보면 과소평가" 교훈의 웹 대응 가산치), **`firstAudioMs`**(접속 시점부터 첫 오디오 프레임까지. 원본 `PLAY 첫 오디오까지 Nms`), `audioLevel`

**`metrics/sync.ts`** — 청취자 간 상호 동기

원본은 **동일 PCM을 동시 송신해서 구조적으로** 참여자 간 동기를 보장했다. "청취자들끼리는 맞는다"는 게 이 제품의 전제 그 자체다(이어폰 한쪽씩 나눠 끼는 걸 대체한다). **웹은 청취자마다 NetEq가 각자 수렴하므로 보장이 아니다.** 두 사람이 나란히 앉아 듣는 시연에서 플랜징으로 즉시 들통난다.

- 매초 `(jitterBufferDelay ÷ jitterBufferEmittedCount)`와 `ctx.outputLatency`를 stats에 실어 올린다. **최대−최소 오프셋 계산은 호스트 대시보드가 한다** — 너는 정확한 원료를 올리는 책임만 진다.
- 전 청취자 `jitterBufferTarget`을 **동일한 값으로 고정**한다(`JITTER_BUFFER_TARGET_MS`). 기기마다 다른 값이면 오프셋이 벌어진다.
- **AP 격리 배너**: `connectionState='failed'`가 **2회 연속**이면 `setBanner('listener', '연결이 안 됩니다 — AP 격리 의심. 호스트 폰의 핫스팟을 켜고 모두 그리로 접속하세요')`.
- **`room-full` 배너**: `정원이 찼습니다 (최대 4명)`.

---

## 5. 단독 테스트 — A를 기다리지 않는다

**`web/tools/mock-host/`가 존재하는 이유가 정확히 이것이다.** 440Hz 오실레이터를 `MediaStreamDestination`으로 내보내 `peer-joined`마다 fan-out하며, 프로토콜을 100% 준수한다. **기능을 늘리지 마라** — 더 필요해졌다면 그건 본 모듈이 할 일이다.

### 절차

1. 탭 1: `/tools/mock-host/?name=mh1` → [공유 시작] → 방 코드 확인 (예: `A3F9`)
   - **`?name=`을 반드시 준다.** hostToken이 `lt.shared.mock-host.<name>`으로 분리 저장되기 때문이다. 안 주면 두 번째 탭이 같은 토큰으로 같은 방을 잡아 첫 탭을 밀어내고(`close(4003 REPLACED)`), 아래 '갈아타기'와 '강제 종료' 시험이 서로를 배제한다
2. 탭 2: 네 리스너로 그 코드에 붙는다 (목록 탭 / 수동 입력 / `#A3F9` 딥링크 세 경로 전부)
3. mock-host의 버튼 3개로 종료 경로를 때린다

### 이 조합으로 검증되는 것 전부

| 검증 항목 | 방법 |
|---|---|
| 수신 경로 | 440Hz가 실제로 들리는가 |
| **`듣는 중` 전이** | `ontrack` 직후가 아니라 `packetsReceived` 증가 뒤에 바뀌는가 (콘솔로 타이밍 확인) |
| 자동 발견 | mock-host가 [공유 시작]을 누른 뒤 **몇 초 만에** 목록에 뜨는가 (`HEARTBEAT_MS` 1초 / `HOST_TTL_MS` 3초 기준 1~3초) |
| 정상 종료 | **[공유 중지]** → `host-stopped(stop)` → `스트림 끊김 — 재접속` → 재시도가 `room-not-found`로 전이하는가 |
| **강제 종료** | **[강제 종료]**(stop 없이 소켓만 끊음) → `host-stopped(gone)` → **룸 WS를 닫지 않고** 대기하는가. 60초 안에 **같은 `?name=`으로** mock-host를 새로고침·재시작하면(같은 토큰 → 같은 코드 재점유) **자동 복귀**하는가 |
| 갈아타기 | mock-host를 `?name=mh1`·`?name=mh2`로 2탭 띄워 **방 두 개**를 만들고, 나가기 없이 목록에서 전환. `연결 중…`을 반드시 거치는가. 같은 방을 다시 탭하면 **no-op**인가 |
| 나가기 | `듣기를 종료했습니다` + `참여 중이 아닙니다.` 복귀 |
| 감시견 | mock-host 탭을 백그라운드로 보내 패킷을 정체시킨 뒤 `버퍼링…` → `멈춤 감지 — 재접속` 전이 |
| `room-not-found` | 존재하지 않는 코드(`ZZZZ`) 입력 → `주소를 찾을 수 없음 — 재시도 중`. **`연결 실패`가 아니어야 한다** |
| `room-full` | mock-listener를 4개 띄워 정원을 채운 뒤 네 리스너로 붙어 본다 |
| `normalizeCode` | 참여 URL을 **통째로** 붙여넣어도 코드만 뽑히는가 |
| 딥링크 | `#A3F9`로 직접 진입 → 다크 뷰 → 알약 [재생] 1탭 |
| 자동재생 정책 | 일부러 제스처 **밖에서** `play()`를 불러 `재생 실패: NotAllowedError`가 **화면에 그대로** 노출되는지 (조용한 실패 금지 원칙의 실증) |

### 어디서 돌리나

| 목적 | 명령 | 주의 |
|---|---|---|
| UI만 만질 때 | `npm run dev` (포트 7990) | **vite dev 서버는 정적 자산만 준다.** `vite.config.ts`에 `/ws`·`/_lobby` 프록시가 **Step 0에서 이미 들어가 있다**(target `http://127.0.0.1:8787`). 없어 보여도 그 파일을 고치지 말고 멈춰서 보고해라 — 동결 파일이고, 양쪽이 각자 다른 포트로 고치면 머지 충돌이 확정된다 |
| 시그널링 포함 로컬 | `npm run dev:worker:b` | DO까지 로컬에서 돈다. **`:b`만 쓴다** — `:a`는 A의 슬롯이다. 스크립트는 Step 0에서 `:a`/`:b` 쌍으로 동결돼 있으니 package.json에 줄을 추가하지 마라 |
| **실기기(iOS 필수)** | `npm run deploy:b` | **HTTPS가 필수다.** `http://192.168.x.x`는 secure context가 아니라 `navigator.mediaDevices`·Wake Lock이 통째로 죽는다. **iOS 동작은 반드시 실기기 Safari에서 확인한다 — 데스크탑 시뮬레이션으로는 아무것도 증명되지 않는다** |

**개발 중에는 로비 URL에 `?lobby=b`를 붙인다.** 안 붙이면 공인 IP 버킷이 같을 때 A의 방이 네 목록에 섞인다.

---

## 6. 배포 — `--env b`만 쓴다

| 슬롯 | 명령 | URL | 누가 |
|---|---|---|---|
| env.a | `npm run deploy:a` | `https://lt-web-a.<계정>.workers.dev` | A만. **절대 실행하지 마라** |
| **env.b** | `npm run deploy:b` | `https://lt-web-b.<계정>.workers.dev` | **너만.** 하루 몇 번이든 |
| canonical | `npm run deploy` | `https://lt-web.<계정>.workers.dev` | Sync 시점에 main에서 한 사람만. **절대 실행하지 마라** |

`npm run deploy`(canonical)는 `scripts/guard-canonical.mjs`가 브랜치·워킹트리·확인 입력을 검사해 막는다. **가드를 우회하지 마라.**

핵심은 **Durable Object 네임스페이스가 Worker 스크립트 단위로 갈린다**는 점이다. `lt-web-a`와 `lt-web-b`는 별개 스크립트라 LobbyDO·RoomDO 저장소가 완전히 독립된다. A의 로비에 네 유령 호스트가 뜨거나, 같은 방코드가 상대 방을 열어버리는 사고가 원천 차단된다.

---

## 7. 함정 목록 — 네가 반드시 밟을 것들

전부 실제로 사람을 태운 함정이다. 미리 읽어라.

| # | 함정 | 증상 | 대응 |
|---|---|---|---|
| 1 | **제스처 핸들러에서 `await` 뒤로 `play()`를 미룬다** | iOS에서 `NotAllowedError`. 데스크탑에서는 멀쩡해서 발견이 늦다 | **핸들러 안에서 `play()`·`resume()`·`wakeLock.request()`를 동기로 전부 시작**하고 결과만 `.then/.catch`로 |
| 2 | **`jitterBufferTarget`을 기능 감지 없이 대입** | 미지원 브라우저에서 **조용히 무시된다**(예외가 안 나서 더 찾기 어렵다) — 지연 튜닝이 안 먹는데 코드는 멀쩡해 보인다. 범위 밖(0~4000ms) 값만 `RangeError`를 던진다. **iOS에서 재생이 아예 안 붙으면 원인은 이게 아니라 제스처 컨텍스트 상실·`playsinline` 누락·자동재생 거부 쪽이다** | `'jitterBufferTarget' in receiver` 검사. 폴백 `playoutDelayHint`는 **초 단위**(단위 혼동 시 지연 1000배) |
| 3 | **`ontrack`만으로 `듣는 중` 전이** | **원본이 3분 내내 거짓말했던 그 버그의 완벽한 재현** | `packetsReceived` 증가 확인 후에만 |
| 4 | **`visibilitychange`에서 Wake Lock을 재요청 안 한다** | 탭 복귀 후 화면이 꺼지고 소리가 끊긴다 | 복귀 시 + sentinel `release` 이벤트 둘 다에서 재요청. 실패하면 전체화면 배너 |
| 5 | **`addIceCandidate`를 `setRemoteDescription` 전에 호출** | **가끔** 연결이 안 된다. 재현이 안 돼서 원인 찾기 최악 | remote description 잡히기 전 후보는 큐잉 후 flush |
| 6 | **`host-stopped(gone)`에서 룸 WS를 닫는다** | 호스트 새로고침 후 자동 복구가 안 된다. **시연 Act 8이 통째로 죽는다** | PC만 정리하고 소켓은 유지. 60초 유예 |
| 7 | **갈아타기에서 로비 WS를 닫는다** | 갈아타는 동안 발견 목록이 사라진다 | **로비 WS는 어떤 경우에도 닫지 않는다.** 룸 WS만 교체 |
| 8 | **close code를 구분하지 않는다** | 서버가 `peer-left` reason을 `gone`으로 오해해 호스트 대시보드가 틀린다 | 갈아타기 `4000`, 나가기 `4001`. `CLOSE` 상수를 쓴다 |
| 9 | **`ping`을 직접 문자열로 만든다** | autoResponse가 바이트 일치로만 매칭해서 **DO가 매번 깨어난다**(조용한 비용) | `PING_FRAME` 상수를 그대로 전송 |
| 10 | **`normalizeCode()`가 4자가 아니면 join을 막는다** | 버튼이 무반응 — **조용한 실패** | `null`이면 무동작, 그 외에는 **길이와 무관하게 서버로 보낸다.** `room-not-found`로 눈에 보이게 실패시킨다 |
| 11 | **`AudioContext`를 기본 생성자로 연다** | 44100이 잡혀 리샘플러가 끼고 README가 제거한 46ms가 돌아온다 | `{sampleRate:48000, latencyHint:'interactive'}` + `ctx.sampleRate` 재확인 |
| 12 | **`<audio>`를 DOM에 안 붙이거나 `playsinline`을 뺀다** | iOS에서 전체화면 플레이어로 튀거나 재생이 안 된다 | DOM 부착 + `playsinline` |
| 13 | **`room-full`에 무한 재시도를 그대로 적용** | 정원 찬 방을 1초마다 영원히 두드린다 | `want` 유지하되 간격을 늘린다. **무한 재시도 원칙의 유일한 예외** |
| 14 | **모르는 error code에서 멈춘다** | A가 새 code를 추가하면(합의 없이 허용된다) 네 리스너가 죽는다 | `stateForError()`가 `연결 실패 — 재시도 중`으로 흡수하게 둔다 |
| 15 | **iOS를 PWA(홈 화면에 추가)로 테스트** | Safari 탭보다 **더** 불안정하다(락스크린 30초 후 오디오 사망 보고) | **Safari 탭으로만** 테스트하고, 시연 체크리스트에도 그렇게 적혀 있다 |
| 16 | **iOS 잠금 시 정지를 버그로 취급해 우회를 시도** | 시간만 태운다 | **우회 방법이 없다.** Wake Lock으로 "화면이 안 꺼지게" 막고, 복구는 배너 1탭. 이 한계 자체가 시연 Act 9의 클라이맥스다 |
| 17 | **`setRoomLine()`을 호출** | A의 호스트 줄을 덮어쓴다 | **호스트 전용이다. 호출하지 마라** |
| 18 | **`setStatus`에 owner를 잘못 넘긴다** | 네 `듣기를 종료했습니다`가 A의 `공유 중`을 지운다 | 항상 `'listener'` |

---

## 8. 완료 기준 (Definition of Done)

전부 체크되어야 B 사이드가 끝난 것이다. **기분이 아니라 이 목록이 판정한다.**

### 상태기계 (CONTRACT.md §7 전이표 17행 전수)

- [ ] `npm run typecheck` 무에러, `npm run build` 성공
- [ ] **17행 전부**를 mock-host로 재현해 실제 문구를 눈으로 확인했다
- [ ] `듣는 중`이 `packetsReceived` 증가 뒤에만 뜬다 (`ontrack` 직후 아님)
- [ ] `room-not-found`와 `연결 실패`가 **구분되어** 표시된다
- [ ] 모르는 error code를 받아도 죽지 않고 `연결 실패 — 재시도 중`으로 흡수한다
- [ ] 선형 백오프(`min(1000×tries, 5000)`)이고 `재접속 중… (N회)`의 N이 실제 `tries`다
- [ ] `CONNECT_TIMEOUT_MS`(4초) 안에 안 붙으면 버리고 재시도한다
- [ ] 갈아타기가 **어떤 상태에서도** 동작하고, 같은 코드면 no-op이다
- [ ] `host-stopped(gone)`에서 룸 WS를 닫지 않고, 호스트 재점유 시 자동 복귀한다
- [ ] `room-full`은 재시도 간격을 늘린다 (1초마다 두드리지 않는다)

### 소켓·목록

- [ ] 로비 WS가 join·갈아타기·나가기 어느 경우에도 닫히지 않는다
- [ ] `host-online` 맨 뒤 추가 / `host-update` 라벨만 / `host-offline` 회색 → 10초 뒤 제거
- [ ] 자기 방이 목록에서 걸러진다
- [ ] `sourceReady:false`면 `(소리 준비 중)` 접미가 붙는다
- [ ] 로비 WS 실패 시 `자동 발견 실패 — 아래에 코드를 직접 입력하세요`

### 재생·생존

- [ ] 제스처 1회로 `play()`+`resume()`+`wakeLock`이 전부 시작된다 (코드를 눈으로 검증)
- [ ] `play()` 실패 시 `재생 실패: <에러이름>`이 화면에 그대로 나온다
- [ ] `ctx.sampleRate`를 metrics에 기록 (48000이 아니어도 배너를 띄우지 않는다 — 네 AudioContext는 `outputLatency` 계측 전용이고 재생 경로(`<audio srcObject>`)를 거치지 않는다. 리샘플러 제거가 걸린 건 호스트측이다)
- [ ] `jitterBufferTarget`이 기능 감지 후 설정되고, 미지원 기기에서 예외가 안 난다
- [ ] 감시견이 `버퍼링…`(1~4초) → `멈춤 감지 — 재접속`(4초+)로 전이한다
- [ ] `visibilitychange` 복귀 시 Wake Lock 재요청 + 실패 시 전체화면 배너
- [ ] MediaSession이 실제로 뜬다

### 딥링크 뷰

- [ ] `#A3F9` 진입 시 다크 전체화면이 뜨고 **배경색이 `listener.css`에서 온다**
- [ ] `PLAYER_VIEW` 8종이 전부 나오고 버튼 라벨이 `재생` → `재생 중`으로 전이한다
- [ ] `재생 중 — 화면을 켠 채 두세요` (원문 대체 확인)
- [ ] **iPhone 실기기 Safari에서** QR 스캔 → 1탭 재생이 실제로 된다

### 계측·경계

- [ ] stats 5종 필수 + optional(`outputLatencyMs`, `firstAudioMs`)이 1초마다 나간다
- [ ] 파생치(`netPct` 등)를 **계산하지 않는다** — 원시 카운터만
- [ ] 모든 한국어 문구가 `shared/strings.ts`에서 왔다. **지어낸 문구가 하나도 없다**
- [ ] 공백 2칸(`님의 소리  (`), 마침표(`참여 중이 아닙니다.`), `▶ ` 뒤 공백 같은 원문 디테일이 살아 있다
- [ ] `git status`에 `web/listener/**` 밖의 변경이 **하나도 없다**
- [ ] DOM id/class가 전부 `l-`, localStorage 키가 전부 `lt.listener.*`
- [ ] `npm run deploy:b`가 성공하고 **iPhone·Android 실기기 둘 다에서** 동작한다

---

## 9. Sync 시점에 준비해서 나올 것

| | 시점 | 네가 준비할 것 |
|---|---|---|
| **Sync 1** 시그널링 관통 | 각자 ~6h 후 / 30분 | **join → offer 수신 → answer → `connected`까지 가는 배포본**(`lt-web-b`). A가 준 방 코드에 즉시 붙을 수 있어야 한다. 실패하면 프로토콜 결함이므로 즉시 공동 수정 대상 |
| **Sync 2** 소리 E2E | ~12h / 1h | **실기기에서 실제로 소리가 나는 상태.** 갈아타기·나가기·호스트 새로고침 후 자동 복귀를 네 쪽에서 재현하고, **17행 전이표를 그 자리에서 대조**할 수 있게 준비한다. `jitterBufferTarget` 0/40/80 세 조건으로 바꿔 끼울 수 있는 스위치를 만들어 두면 에코 간격법 튜닝이 빨라진다 |
| **Sync 3** 풀 데모 조건 | ~16h / 1~2h | **청취자 3~4명 동시 청취 + 딥링크 뷰 완성.** 네가 직접 실측해 숫자를 가져올 것: ① Android Chrome 청취자가 탭 백그라운드·화면 잠금 후 얼마나 버티는가(30초/1분/2분, 배터리 '제한 없음' ON/OFF) ② **iOS Safari 잠금 시 즉시 정지하는가, 잠금 해제만으로 자동 복구되는가 아니면 배너 탭이 필요한가, 복구까지 몇 초인가** ③ 전화·알림 인터럽트 후 복구. **②가 Act 9의 마지막 장면을 확정한다 — 자동 복구를 대본에 넣었다가 안 되면 클로징이 무너진다.** ④ 청취자 2대 이어버드를 녹음기 앞에 나란히 두고 클릭 트랙 5초 녹음 → **상호 오프셋(ms) 파형 측정** |
| **Sync 4** 리허설 | 시연 전날 / 반나절 | 시연 장소 Wi-Fi에서 2회 통주. **QR 스캔 → 1탭 재생 → Act 9 잠금 → 배너 복구** 동선을 네가 소유한다. fallback F5(iOS 완전 실패 시 예비 안드로이드 30초 교체)를 스톱워치로 2회 |

**Sync 전에 반드시**: 자기 슬롯(`lt-web-b`)에 최신 배포를 올려두고, 그 URL을 공유할 수 있게 해 둔다. 로컬에서만 되는 건 Sync에서 쓸모가 없다(HTTPS 문제로 iOS가 아예 안 붙는다).

---

## 10. 작업 규칙

### git — **커밋·푸시·머지는 사용자가 명시적으로 지시할 때만**

절대로 스스로 `git commit` / `push` / `merge`를 실행하지 않는다. 파일을 만들고 고치는 데서 멈춘다. 사용자가 "커밋해줘"라고 명시적으로 말했을 때만 실행한다.

- **브랜치**: `feat/2` (형식 `type/번호`, type은 feat/fix/chore/style/design)
- **Step 0 커밋에서 브랜치를 딴다.** 그 이전 커밋에서 따면 shared가 없어 아무것도 컴파일되지 않는다
- **커밋 메시지**: `type(#번호): 설명`
  ```
  feat(#2): want 플래그 무한 재시도 + 원인별 상태 문구
  feat(#2): 딥링크 다크 뷰 — PLAYER_VIEW 8종 + 알약 재생 버튼
  fix(#2): 갈아타기 시 기존 PC를 닫지 않던 문제
  ```
- **`Co-Authored-By` 트레일러를 붙이지 않는다**
- **A의 브랜치를 rebase하거나 force-push하지 않는다.** 자기 브랜치만 건드린다
- 머지에서 충돌이 나면 그건 **누군가 소유권 경계를 넘었다는 신호다.** 머지로 때우지 말고 어디서 넘었는지부터 찾는다
- `dist/`, `node_modules/`, `.wrangler/`는 커밋하지 않는다

### 에러 보고 — 로그만 받으면 바로 고치지 않는다

사용자가 아무 말 없이 에러 코드나 로그만 붙여넣으면 **즉시 코드를 수정하지 않는다.** 반드시 먼저 두 가지를 보고한다:

1. **에러 원인 분석** — 왜 발생했는지
2. **수정 계획** — 어떻게 고칠 것인지, 어느 파일을 건드릴 것인지

사용자가 명시적으로 수정을 지시한 후에만 파일을 변경한다.

### 그 밖에

- 이모지를 쓰지 않는다 (UI 문구에 원본이 포함한 `🎧`·`▶`는 예외 — 그건 원본 계승이다)
- 문구는 **반드시** `shared/strings.ts`에서 import한다. 지어내지 않는다
- 상수는 **반드시** `shared/protocol.ts`에서 import한다. 숫자를 직접 쓰지 않는다
- 막히면 혼자 오래 붙잡지 말고, shared 수정이 필요한지부터 판단해 사용자에게 보고한다
