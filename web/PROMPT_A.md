# 작업 지시서 — A (호스트 사이드)

> 이 문서를 통째로 읽고 바로 작업을 시작한다.
> 너는 2인 병렬 작업의 **A**다. B(리스너 사이드) 세션이 어딘가에서 동시에 돌고 있지만, **너는 B의 존재와 진척을 모른다는 전제로 일한다.** B를 기다리는 일이 생기면 그건 설계가 잘못된 것이고, 아래 §5의 단독 검증 장치로 전부 우회할 수 있다.

---

## 1. 배경

`같이 듣기(listen-together)`는 **친구끼리 이어폰 한쪽씩 나눠 끼는 걸 없애려고** 만든 안드로이드 PoC다. 호스트 폰이 `AudioPlaybackCapture`로 재생 중인 오디오를 가로채 HTTP/WAV 무압축 PCM으로 `:7980`에 뿌리고, UDP 비콘(`:7981`)으로 자기 존재를 1초마다 알리면, 같은 Wi-Fi의 친구 2~4명이 각자 자기 이어폰으로 듣는다. **네이티브 지연 111ms 실측**, 참여자 데이터 소모 0, 화면을 꺼도 Doze 상태에서 무결. 원본 레포는 `/Users/yoochangheon/Desktop/listen-together`이고 소스는 `app/src/main/java/com/example/lt/{MainActivity,CaptureService,PlayerService}.java`, 교훈 문서는 `/Users/yoochangheon/Desktop/listen-together/README.md`다.

지금 하는 일은 **APK를 제출할 수 없어서 이 앱을 웹으로 그대로 옮기는 것**이다. 원칙은 한 줄이다 — **"앱을 웹에서 구동한다. 별도 웹 기획 없음. 웹에서 물리적으로 안 되는 것만 대체 기획한다."** 화면 구성, 한국어 문구, 상태 전이 순서, 타이밍 상수를 새로 짜지 않는다. 전부 원본에서 그대로 가져온다. 스택은 Vanilla TypeScript + Vite(프레임워크 0, 런타임 npm 의존성 0), 시그널링·배포는 Cloudflare Workers + Durable Objects, 미디어는 WebRTC 1→N mesh(최대 4명), **TURN 금지**(미디어를 LAN에 묶어 "참여자 데이터 소모 ~0"을 지킨다).

**네가 맡은 것은 호스트 사이드 전체다.** 원본의 `CaptureService.java`가 하던 일 — 소리를 만들어서, 여러 청취자에게 동시에 뿌리고, 자기 존재를 알리고, 진단 숫자를 뱉는 것 — 이 통째로 네 담당이다.

---

## 2. 네가 소유하는 파일 / 절대 만지지 않는 파일

### 쓰기 권한이 있는 경로 — `web/host/**` 단 하나

```
web/host/
├─ index.ts                 # [Step 0 스텁 존재] mount 시그니처는 FROZEN, 본문은 네 것
├─ host.css                 # [Step 0 빈 파일] 호스트 섹션 스타일 전부 여기
├─ source/
│   ├─ file.ts              # 로컬 파일 → <audio blob> → MediaElementSource → 2분기
│   ├─ display.ts           # getDisplayMedia 탭 오디오 (데스크탑 Chrome 한정, 기능감지)
│   ├─ mic.ts               # getUserMedia (AEC/NS/AGC 전부 false)
│   └─ index.ts             # 소스 3종 공통 인터페이스 + 선택 영구저장 + 48k 컨텍스트
├─ rtc/
│   ├─ fanout.ts            # peer-joined마다 PC 생성 → addTrack → offer
│   ├─ munge.ts             # answer SDP opus fmtp munging (setRemote 직전)
│   └─ candidates.ts        # selected candidate pair local↔local 판정 (AP isolation 진단)
├─ ui/
│   ├─ section.ts           # '내가 틀기' 섹션 조립 + 기기 이름 입력
│   ├─ source-radio.ts      # 소리 소스 라디오 3종 + '지금: …' 요약
│   ├─ qr.ts                # vendor/qrcode로 참여 URL QR + 폴백 '안 되면 직접: <코드>'
│   ├─ hotspot-card.ts      # '핫스팟 켜기' 대체 안내 카드
│   └─ statusbar.ts         # 청취자 수·상태 고정 바 + Wake Lock + 호스트 MediaSession
└─ stats/
    ├─ analyser.ts          # rms/peak (32바이트 간격 서브샘플링 산식 이식)
    └─ dashboard.ts         # outbound-rtp + 리스너 stats 수합 → AUDIO 한 줄 + 판정표
```

파일을 더 쪼개고 싶으면 **`web/host/` 안에서는 마음대로 해도 된다.** 위 목록은 최소 골격이지 상한이 아니다.

### 읽기만 — 편집 금지 (업스트림 원본)

```
web/host/vendor/qrcode.js        # MIT 단일 파일 QR 인코더. 원본 그대로 쓴다
web/host/vendor/qrcode.d.ts
web/host/vendor/LICENSE-qrcode.txt
```

버전 교체가 필요하면 파일 전체를 갈아끼우고 LICENSE를 유지한다. 부분 수정 금지.

### 절대 만지지 않는 파일 (shared-frozen) — **쓰기 금지, 읽기는 권장**

```
package.json          package-lock.json     tsconfig.json
tsconfig.web.json     tsconfig.server.json  vite.config.ts
wrangler.jsonc        .gitignore            scripts/**
server/**             web/index.html        web/main.ts
web/shared/**         web/tools/**          web/CONTRACT.md
web/listener/**       (← B의 트리. 한 파일도 열지 않는다)
```

**`npm i -D` 를 혼자 돌리지 않는다.** devDependency 추가·npm script 추가도 '수정'이고, 한쪽이 lockfile을 건드리는 순간 자동 머지가 사실상 불가능해진다. 필요한 devDep은 Step 0에서 전부 설치돼 있다.

### ★ shared-frozen을 고쳐야 할 것 같으면 — 고치지 말고 멈춰라

`protocol.ts`에 필드를 하나 추가해야 할 것 같거나, `shell.css`에 셀렉터를 하나만 넣고 싶거나, `server/room-do.ts`가 틀린 것 같아 보이는 순간이 온다. 그때 자기 브랜치에서 고치면 안 된다.

**작업을 멈추고 사용자에게 보고한다.** 보고 형식:

1. 무엇을 고쳐야 하는가 (파일·심볼 단위로)
2. 왜 지금 방법으로는 안 되는가 (우회를 시도했는지 포함)
3. 이게 `PROTOCOL_V` 증가가 필요한 변경인지 아닌지 (CONTRACT.md §12 기준)

이걸 '미니 Sync'라고 부른다. 한 사람이 단독으로 main에서 고치고, 양쪽이 각자 rebase한다. **두 사람이 동시에 shared를 여는 상황 자체를 만들지 않는 게 이 규칙의 전부다.**

단, 다음 두 가지는 **합의 없이 해도 된다** (CONTRACT.md §12):
- `StatsMsg`의 optional 필드는 B가 마음대로 추가할 수 있다 → **너는 모르는 필드를 무시하도록 짜라.**
- `ERROR_CODES`에 새 code가 추가될 수 있다 → 모르는 code를 받아도 죽지 마라.

### 네임스페이스 (충돌 방지 — 반드시 지킬 것)

| 종류 | 네 접두사 |
|---|---|
| DOM id / class | `h-` (B는 `l-`, shared는 `lt-`) |
| localStorage 키 | `lt.host.*` (B는 `lt.listener.*`) |

---

## 3. 시작 전에 읽어야 할 것

### 필수 (순서대로)

| # | 파일 | 무엇을 얻는가 |
|---|---|---|
| 1 | `/Users/yoochangheon/Desktop/listen-together/web/CONTRACT.md` | **동결 계약 전문.** 이게 판정 기준이다. §2 소유권, §4 프로토콜, §5 `protocol.ts` 전문, §6 mount 시그니처, §8 한국어 문구 사전, §9 타이밍 상수표, §10 배포. **§8.4·§8.7·§8.8이 네 문구 전량이다** |
| 2 | `/Users/yoochangheon/Desktop/listen-together/web/PLAN.md` | 왜 이렇게 만드는가. §1 동등성 표(21행), §2 아키텍처, 시연 대본. **네 작업이 시연에서 어떤 장면이 되는지 알고 짜라** |
| 3 | `/Users/yoochangheon/Desktop/listen-together/README.md` | 원본 교훈 문서. 특히 **"조용한 재시도 금지"**, **cap/net/drop 판정표**, **48kHz 고속경로**, 무음 keepalive |
| 4 | `app/src/main/java/com/example/lt/CaptureService.java` | **네 대응 원본.** 1200줄 남짓. 최소한 이 지점들은 직접 읽어라 (아래 표) |
| 5 | `web/shared/protocol.ts` · `web/shared/strings.ts` | 실물 계약. 문서와 소스가 어긋나면 **소스가 이긴다** |
| 6 | `web/shared/mount.ts` · `web/shared/ice.ts` · `web/shared/ws.ts` · `web/shared/log.ts` | 네가 import할 것들의 실제 시그니처 |
| 7 | `web/tools/mock-listener/main.ts` | 네 단독 검증 장치가 어떤 프레임을 보내는지 |

### `CaptureService.java`에서 반드시 볼 지점

| 줄 | 무엇 | 네가 이식할 것 |
|---|---|---|
| ~68 | `SAMPLE_RATE = 48000` + 주석 | **44100→48000이 README 최대 성과의 근거.** `AudioContext({sampleRate:48000})`로 옮긴다 |
| ~76 | `CHUNK_MS = 20` | Opus 프레임 20ms와 같은 입자라 진단 산식이 그대로 옮겨진다 (`OPUS_FRAME_MS`) |
| ~100 | `Client` 이너클래스 주석 | **"느린 1명이 전체를 못 막는다"** — 웹에서는 PC가 청취자마다 따로라 공짜로 나오지만, **끊는 조건은 네가 구현해야 한다** |
| ~300–321 | `keepAlive` AudioTrack (볼륨 0 무음 재생) | **웹에서는 정확히 역효과다.** 이식하지 말고, 대신 "호스트 음소거 금지" 경고를 UI로 강제한다 |
| ~478–487 | 32바이트 간격 서브샘플링 rms/peak | `analyser.ts`가 같은 산식을 쓴다 |
| ~495–525 | `AUDIO rms= peak= clients= gap= cap= fill= net= drop=` 로그 + `<<< 캡처 유실` | `dashboard.ts`의 출력 형식. **`fill=`만 제거**(웹에서는 구조적으로 항상 0) |
| ~590–600 | UDP 비콘 `"LT1|" + Build.MODEL + "|" + ip` | `host-announce` 1초 하트비트의 직계 대체. **`Build.MODEL` 등가물이 웹에 없다** → 이름 입력 1칸 |
| ~649–695 | `page()` 브라우저 참여 페이지 | 이건 **B의 참고 대상**이다. 네가 구현하지 않는다 |

---

## 4. 구현할 모듈

시작 전 확인: `web/shared/protocol.ts`가 존재하고 `npm run typecheck`가 통과하는가. **없거나 실패하면 Step 0 스캐폴드가 아직 안 끝난 것이다 — 네가 shared를 만들지 말고 멈추고 사용자에게 보고하라.**

### 4.0 진입점 — `web/host/index.ts`

```ts
export function mount(el: HTMLElement, ctx: MountContext): MountHandle
```

시그니처는 **FROZEN**이다. 바꾸려면 미니 Sync. `mode === 'panel'`일 때만 호출된다(딥링크 모드에서 호스트는 아예 마운트되지 않는다). `ctx.onTeardown()`에 정리 훅을 반드시 등록하고, `destroy()`에서 WS·PC·AudioContext·타이머·Wake Lock을 전부 회수해라.

`ctx`에서 쓸 것: `setStatus('host', text)`, `setRoomLine({code, joinUrl} | null)`, `setBanner('host', text | null)`, `wsUrl(path)`, `origin`. **`setStatus`의 첫 인자를 `'host'`로 고정하는 게 계약이다** — 슬롯을 나눠 쓰지 않으면 B의 `듣기를 종료했습니다`가 네 `공유 중`을 지운다.

**URL·origin을 하드코딩하지 않는다.** 배포 슬롯이 3개(a/b/canonical)라 하드코딩하는 순간 QR이 남의 슬롯을 가리킨다. 항상 `ctx.origin` / `ctx.wsUrl()` / `protocol.ts`의 `roomWsPath()`·`joinUrl()`만 쓴다.

---

### 4.1 `source/` — 소리 소스 3종 (6h)

**공통 (`source/index.ts`)**

- `AudioContext`는 **하나만** 만들고 전 소스가 공유한다. 반드시 `new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'interactive' })` — `SAMPLE_RATE`는 `protocol.ts`에서 import(48000).
- 생성 직후 **`ctx.sampleRate`로 실제 잡힌 값을 재확인**한다. 48000이 아니면 `setBanner('host', …)`로 `기기가 48kHz를 주지 않았습니다 (<N>Hz) — 지연이 늘 수 있습니다`. 원본의 "요청값만 믿지 말고 실제 잡힌 값을 확인하라"(README)의 직계 이식이다.
- 공통 인터페이스 예시(네 재량으로 다듬어도 됨):
  ```ts
  interface HostSource {
    id: 'file' | 'display' | 'mic';
    label: string;           // strings.ts의 라디오 라벨
    stream: MediaStream;     // fan-out에 그대로 넘긴다
    ready: boolean;          // host-announce의 sourceReady
    capPct(gapMs: number): number;   // 소스별 정의는 §4.5
    teardown(): void;
  }
  ```
- **모든 오디오 트랙에 `track.contentHint = 'music'`** 을 건다. 이게 없으면 브라우저가 음성 최적화(DTX·대역 축소)를 걸어 음악이 뭉개진다.
- 선택한 소스를 `localStorage['lt.host.source']`에 영구 저장하고 다음 방문 시 복원한다(원본의 앱 화이트리스트 저장 패턴과 같은 톤).
- 소스 전환 시 안내: `  · 적용하려면 공유를 다시 시작하세요` — **앞 공백 2칸 포함, 원문 그대로.**

**`source/file.ts` — 기본 경로. 전 플랫폼에서 유일하게 확실히 되는 소스다.**

- `<input type="file" accept="audio/*">` → `URL.createObjectURL(file)` → `<audio>` 엘리먼트에 물린다.
- `ctx.createMediaElementSource(audioEl)` → **두 갈래로 동시 연결**: ① `ctx.destination`(호스트 본인도 들려야 한다 — 시연의 에코 간격법과 Act 7이 여기에 달려 있다) ② `ctx.createMediaStreamDestination()`(이게 fan-out에 나가는 스트림).
- `<audio>`를 **DOM에 실제로 붙이고** `controls`를 노출한다(호스트가 곡을 제어할 수 있어야 하고, 일부 브라우저는 DOM에 없는 엘리먼트의 재생을 신뢰하지 않는다).
- 파일명과 `currentTime`을 `localStorage['lt.host.lastFile']`에 저장한다 — **새로고침 복구(§4.6)에서 이 값이 필요하다.**
- `cap%` = `audio.currentTime` 증분 ÷ 실경과 `gapMs`.

**`source/display.ts` — 고급 경로. 데스크탑 Chrome 전용.**

- **기능 감지가 먼저다.** `navigator.mediaDevices?.getDisplayMedia`가 없거나 모바일이면 **라디오 항목 자체를 `disabled`로 두고 라벨에 `브라우저 탭 (모바일 불가)`을 그대로 쓴다.** Android Chrome은 API가 있는데 호출하면 항상 거부한다 — 눌러서 실패하게 두지 말고 처음부터 못 누르게 한다.
- 호출:
  ```ts
  await navigator.mediaDevices.getDisplayMedia({
    video: true,                       // ★ audio만 요청하면 대부분 거부된다
    audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    systemAudio: 'include',
    suppressLocalAudioPlayback: false, // ★ true면 호스트 본인 스피커가 죽는다
  });
  ```
- **받자마자 video 트랙을 `stop()` + `stream.removeTrack()`** 한다. 안 그러면 화면 캡처가 계속 돌아 CPU·배터리를 태우고, "화면은 쓰지 않습니다"라는 UI 카피가 거짓이 된다.
- `getAudioTracks().length === 0`이면 `setBanner('host', '탭 오디오도 공유를 체크하세요 — 소리 트랙이 오지 않았습니다')`. **이게 시연에서 제일 자주 밟는 함정이고, F3 fallback의 트리거다.**
- 사용자가 다이얼로그를 취소하면 `setStatus('host', '화면 캡처 동의가 취소되었습니다')` — 원본 문구 그대로.
- 다이얼로그를 띄우기 **전에** 선제 설명 카피를 표시한다 (§8.4): `탭을 고르라는 창이 뜹니다. 왼쪽 아래 '탭 오디오도 공유'를 꼭 체크하세요.` / `화면은 쓰지 않습니다 — 브라우저가 소리만 주는 방법을 따로 두지 않았을 뿐입니다.` 원본이 MediaProjection 동의 전에 "화면은 가져가지 않습니다"를 미리 풀어준 그 자리다.
- `cap%` = `outbound-rtp.totalSamplesDuration` 증분 ÷ `gapMs`.

**`source/mic.ts` — 비상용.**

- `getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 2 } })`. **세 개를 전부 끄지 않으면 음악이 통화 음질로 뭉개진다.**
- `NotAllowedError` → `setStatus('host', '권한이 거부되었습니다')` (원본 문구).
- 라벨은 `마이크 (음질 나쁨 — 비상용)`. **한계를 라벨에 적는 톤이 원본 계승 포인트다**(원본 `Spotify (캡처 거부됨)`).

---

### 4.2 `rtc/` — fan-out과 SDP munging (5h)

**`rtc/fanout.ts`**

- `Map<PeerId, {pc, sender, failCount, …}>` 하나로 관리한다. **peerId가 키다** — 서버는 재점유 시에도 살아남은 리스너의 peerId를 바꾸지 않는다.
- `peer-joined` 수신 → `new RTCPeerConnection(RTC_CONFIG)`(← `shared/ice.ts`) → `addTrack(audioTrack, stream)` → `createOffer()` → `setLocalDescription()` → `{t:'offer', to: peerId, sdp}` 송신.
- `onicecandidate` → `{t:'ice', to: peerId, candidate: e.candidate ? e.candidate.toJSON() : null}`. **`null`(end-of-candidates)도 반드시 보낸다** — 삼키면 ICE가 늦게 끝나 첫 소리가 느려진다.
- `answer` 수신 → **`munge()`를 통과시킨 뒤** `setRemoteDescription()`.
- `peer-left` 수신 → **그 PC만** `close()` + Map에서 제거. 절대 전체를 재시작하지 않는다(원본 `Client` 주석의 원칙).
- `peer-not-found` 에러 → 그 peerId의 PC만 정리. 소켓은 유지.
- `connectionState === 'failed'`가 **2회 연속**이면 `setBanner('host', '연결이 안 됩니다 — AP 격리 의심. 호스트 폰의 핫스팟을 켜고 모두 그리로 접속하세요')`.
- `CONNECT_TIMEOUT_MS`(4000) 안에 `connected`에 도달하지 못한 peer는 그 PC를 버리고 재협상한다. **이 타임아웃이 없으면 브라우저 기본 ICE 타임아웃(수십 초)까지 아무 말 없는 침묵 구간이 생긴다 — 원본이 최악이라 못 박은 "조용한 재시도"의 재발이다.**
- 같은 track 객체를 PC N개에 `addTrack`하는 건 정상이다. 대가는 Opus를 N회 인코딩하는 CPU이고, `MAX_LISTENERS=4`가 그 상한의 근거다.

**`rtc/munge.ts` — 이 프로젝트에서 가장 미묘한 30줄이다**

- 받은 **answer** SDP 문자열에서 `a=rtpmap:(\d+) opus/48000/2`로 payload type을 찾고, 그 pt의 `a=fmtp:<pt> …` 줄을 `OPUS_FMTP`(← `protocol.ts`)로 치환한다. 줄이 없으면 rtpmap 바로 뒤에 삽입한다.
- **반드시 `setRemoteDescription()` 직전에만 한다.** `setLocalDescription()` 전 munging은 Chrome M138부터 봉쇄 중이다.
- 방향 함정: `stereo=1`은 **"SDP를 보내는 쪽이 스테레오 수신을 원한다"**는 뜻이다. 따라서 송신자인 **호스트가 받은 answer에** `stereo=1`이 있어야 스테레오로 인코딩한다. 이걸 거꾸로 이해하면 munging을 했는데 아무 효과가 없고, 원인을 찾는 데 몇 시간이 간다.
- Chrome 기본값은 **모노 ~32kbps 음성 모드**다. munging을 안 하는 건 선택이 아니라 사고다.
- 먹었는지 추측하지 말고 확인해라 — `getStats()`의 `codec.sdpFmtpLine`에 `stereo=1`이 들어갔는지, `outbound-rtp.targetBitrate`가 256k에 근접하는지.

**`rtc/candidates.ts`**

- `getStats()` → `transport.selectedCandidatePairId` → 그 `candidate-pair`의 local/remote `candidateType`을 읽는다.
- `host ↔ host`면 LAN 직결(정상). `srflx`면 라우터 헤어핀(동작하지만 시연 전 확인 필요). 실패/없음이면 AP 격리 의심.
- 이 판정을 대시보드에 한 줄로 띄운다. **시연 T-20분 프리플라이트의 유일한 판정 근거이고, F1(핫스팟 전환) 발동 여부를 여기서 결정한다.**

---

### 4.3 룸 WS와 방 생명주기 (`ui/section.ts` 또는 별도 `room.ts`)

호스트는 **소켓 1개**(룸 WS)만 쓴다. 로비 WS에 직접 붙지 않는다 — `host-announce`를 룸 WS로 보내면 RoomDO가 LobbyDO로 중계한다.

| 순서 | 할 것 |
|---|---|
| 1 | `localStorage['lt.host.token']`에서 `{roomId, hostToken, exp}`를 읽는다. `exp` 만료면 버린다(`HOST_TOKEN_TTL_MS` 6h) |
| 2 | 토큰이 있으면 그 `roomId`로, 없으면 `newRoomCode()`로 코드를 뽑아 `ctx.wsUrl(roomWsPath(code))` 접속 |
| 3 | **첫 프레임은 반드시** `{t:'host-open', v:PROTOCOL_V, deviceName, hostToken?}` |
| 4 | `room-created` 수신 → `hostToken`·`roomId`를 localStorage에 저장, `ctx.setRoomLine({code, joinUrl})`, QR 갱신. `resumed:true`면 뒤따라 오는 `peer-joined(resumed:true)`들에 **입장 토스트를 띄우지 않는다** |
| 5 | `error(room-taken)` → `setStatus('host', '방을 만들지 못했습니다 — 재시도 중')` 후 **새 코드를 뽑아 재시도**. 저장된 토큰이 원인이면 토큰을 버린다 |
| 6 | `HEARTBEAT_MS`(1000) 주기로 `{t:'host-announce', deviceName?, sourceReady}` 송신. **listeners 수는 보내지 않는다** — 서버가 직접 센다 |
| 7 | `WS_PING_MS`(25000) 주기로 `PING_FRAME` 상수를 **그대로** 송신. 한 글자라도 다르면 autoResponse가 안 먹어 DO가 매번 깨어난다 |
| 8 | [공유 중지] → `{t:'stop-share'}` → `setStatus('host', '공유를 중지했습니다')`. 유예 없이 방이 즉시 닫힌다 |
| 9 | `close(4003 REPLACED)`를 받으면 **새로고침 경합으로 교체된 것이다.** 조용히 정리하고 재접속하지 않는다(새 소켓이 이미 방을 잡았다) |

---

### 4.4 `ui/` — '내가 틀기' 섹션 (5h)

**문구는 전부 `web/shared/strings.ts`에서 import한다. 여기 없는 문구를 지어내지 않는다 — 필요해지면 미니 Sync다.** 전량은 CONTRACT.md §8.4에 표로 있다.

| 요소 | 요구사항 |
|---|---|
| 섹션 제목 | `내가 틀기` (18sp 대응) |
| 안내 힌트 4줄 | 파일 모드/탭 캡처 모드별로 다르다. §8.4 표 그대로. **선제 설명 카피라는 성격을 계승할 것** |
| **기기 이름 입력 1칸** ✚ | label `이 기기 이름 (친구 목록에 보입니다)`. 기본값 = `navigator.userAgentData?.getHighEntropyValues(['model'])` 시도 → 실패 시 플랫폼 추정명(`iPhone`/`Android 폰`/`노트북`). 1~16자. `localStorage['lt.host.deviceName']` 영구 저장. **웹에 `Build.MODEL` 등가물이 없어서 생긴 신규 요소다** — 그대로 두면 리스너 목록이 `  님의 소리`가 되고 호스트 2명 구분이 불가능해져 시연의 갈아타기 데모가 통째로 성립하지 않는다 |
| 소스 라디오 3종 | `음악 파일 (내 기기에서 고르기)` / `브라우저 탭 (모바일 불가)` / `마이크 (음질 나쁨 — 비상용)`. 제목은 `소리 소스 — 고른 소스의 소리만 나갑니다.` 요약은 `지금: <소스명>의 소리가 나갑니다` |
| 버튼 | `공유 시작` / `공유 중지` (원문 그대로). **`배터리 최적화 제외` 버튼은 삭제** |
| QR (`ui/qr.ts`) | `vendor/qrcode.js`로 **`room-created.joinUrl`을 그대로** 인코딩. 힌트 `친구 카메라로 이 QR 을 찍으면 접속됩니다.`(`QR 을` 사이 공백 유지) + `안 되면 직접: <코드>`. 실패 시 `QR 생성 실패 — 직접 입력: <코드>`. **폴백을 빼지 마라 — 원본이 QR 실패에 대비해 SSID/비번을 병기했던 그 패턴이다** |
| 핫스팟 카드 (`ui/hotspot-card.ts`) | 버튼이 아니라 **안내 카드**다(웹은 AP를 못 연다). 제목 `Wi-Fi 없을 때: 호스트 폰의 핫스팟을 직접 켜세요` / 본문 `설정 > 모바일 핫스팟을 켜고, 친구들이 그 Wi-Fi에 붙으면 됩니다.` |
| 고정 바 (`ui/statusbar.ts`) | 청취자 수 + 상태. 원본 포그라운드 서비스 상시 알림의 대체. 제목 `같이듣기 — 내 소리 공유 중`, 2줄째 참여 링크, 버튼 `공유 중지` |
| **Wake Lock** | `navigator.wakeLock.request('screen')`. `visibilitychange`(복귀 시)와 sentinel의 `release` 이벤트에서 **재요청**한다. 실패해도 조용히 넘어가지 말고 로그를 남긴다 |
| **호스트 MediaSession** ✚ | `metadata = {title:'같이듣기 — 내 소리 공유 중', artist: <참여 링크>}`, `playbackState='playing'`, `setActionHandler('stop', 공유중지)`. **원본 알림 액션의 직계 대체이자, Chrome의 "가청 오디오 탭 우대"를 받는 조건이다.** 이게 없으면 Android 호스트가 백그라운드에서 죽는 시점이 훨씬 빨라진다 |

**CSS는 전부 `web/host/host.css`에 넣는다.** "shared CSS에 셀렉터 하나만 추가하고 싶다"는 생각이 들면 그건 `host.css`로 갈 물건이다.

---

### 4.5 `stats/` — 진단 대시보드 (3h)

**`stats/analyser.ts`**

- `AnalyserNode`(fftSize 2048)를 MediaStreamDestination 직전에 물리고 `getFloatTimeDomainData()`로 창을 뜬다.
- **원본과 같은 서브샘플링**: 전 샘플을 보지 않고 16샘플(원본 기준 32바이트)마다 하나만 본다. rms/peak를 16bit 스케일(×32767)로 환산해 **원본 로그와 같은 자릿수**가 나오게 한다 — `rms=12079 peak=30613`처럼.
- **rms가 3초 연속 0이면** `setBanner('host', '소리가 나가지 않습니다 — 재생/음량을 확인하세요')`. 이게 §4.6 무음 방어의 핵심이다.

**`stats/dashboard.ts`** — `STATS_MS`(1000) 주기

출력 형식은 원본 `AUDIO` 로그 한 줄을 그대로 흉내 낸다. **`fill=`만 제거**한다(웹에서는 AudioContext 실시간 클럭이 무음을 자동 공급하므로 구조적으로 항상 0 — 누락이 아니라 의도적 제거다):

```
AUDIO rms=12079 peak=30613 clients=2 gap=1000ms cap=100% net=100% drop=0
```

`cap < 95%`면 뒤에 `  <<< 캡처 유실`을 붙이는 규칙도 그대로.

- **`cap%`는 소스별로 정의가 다르다** — 파일 모드는 `audio.currentTime` 증분÷`gapMs`, 캡처/마이크 모드는 `outbound-rtp.totalSamplesDuration` 증분÷`gapMs`. (원본의 "링버퍼 유실"에 해당하는 웹 지표)
- `net%` / `drop`은 청취자별로 낸다. `drop` = 내 `outbound-rtp.packetsSent` 증분 − 그 리스너 `stats.packetsReceived` 증분.
- 청취자별 줄은 `protocol.ts`의 `deriveListenerStats(prev, cur, expectedPackets)` + `statsLine(peerId, d)`를 **그대로 쓴다**(직접 계산하지 마라). `expectedPackets`에 네 `packetsSent` 증분을 넘겨야 원본과 같은 의미의 `net%`가 나온다:
  ```
  PLAY p2 buf=42ms true=72ms net=100% under=0 gap=1000ms  <<< 수신 버퍼
  ```
- **상호 오프셋 ✚** — 청취자별 `(bufMs + outputLatencyMs)`의 최대−최소를 `상호 오프셋 ≈ 38ms (p2↔p3)` 한 줄로 띄운다. 원본은 동일 PCM 동시 송신으로 이걸 **구조적으로** 보장했지만 웹은 청취자마다 NetEq가 각자 수렴하므로 보장이 아니다. **100ms를 넘으면 시연에서 플랜징으로 즉시 들통난다.**
- **느린 청취자 격리** — 특정 peer의 `drop > 0`이 **5초 연속**이면 그 PC만 `close()`하고 `setBanner('host', '<이름> 연결 불량으로 끊었습니다')`. 원본 `MAX_DROPS`(5초치)의 직계 이식이다.
- 리스너 `stats`의 **모르는 필드는 무시**한다. B는 optional 필드를 합의 없이 추가할 권리가 있다(CONTRACT.md §12). `outputLatencyMs`·`firstAudioMs`가 없을 수도 있다고 가정하고 짜라 — `trueMs`가 `null`로 오는 경우를 `statsLine`이 이미 `-`로 처리한다.

판정표 3행을 툴팁이나 접힌 블록으로 화면에 둔다. **"귀로 판단하지 말 것"** — 무음이 나와도 셋이 구분되지 않는다:

| cap | net | drop | 범인 |
|---|---|---|---|
| <100% | — | — | 소스가 밀림 (파일 재생 스로틀 / 캡처 유실) |
| 100% | <100% | >0 | 송신 큐 (네트워크가 못 따라감) |
| 100% | 100% | 0 | 폰은 무죄 — 받는 쪽 지터버퍼 |

---

### 4.6 회복력 — 흩어져 있지만 빠뜨리면 시연이 죽는 것들 (2h)

| # | 상황 | 해야 할 일 |
|---|---|---|
| 1 | **호스트 새로고침 후 소스 없음** | 방은 `hostToken`으로 부활하지만 blob URL은 소멸한다. **이때 `공유 중`이라고 쓰면 안 된다.** `setBanner('host', '소리 소스를 다시 고르세요 — 마지막 곡: <파일명>')`를 띄우고 `sourceReady:false`로 announce한다. 이걸 빠뜨리면 "방은 부활 → 리스너 재연결 성공 → connectionState=connected → 그런데 소리가 없음"이 되는데, **이게 정확히 README가 최악이라 못 박은 상태다** |
| 2 | 소스 없이 announce | `host-announce`의 `sourceReady:false` → 리스너 목록에 `(소리 준비 중)` 회색 접미가 붙는다 |
| 3 | 무음 방어 | rms 3초 연속 0 → 경보 배너 (§4.5) |
| 4 | **음소거 함정** | 출력 게인이 0이거나 `<audio>.muted`면 `setBanner('host', '음소거 상태에서는 화면을 끄면 송출이 끊깁니다')`. Chrome은 **실제로 들리는 오디오**가 있는 탭만 스로틀링에서 빼준다. 네이티브에서 캡처 유실을 10건→0건으로 만든 무음 keepalive 트릭이 **웹에서는 정확히 반대로 작동한다** |
| 5 | `공유 중지 → 재시작` | 같은 resume 경로를 타서 **QR·참여 링크가 세션 내내 불변**임을 보장한다 |
| 6 | 샘플레이트 불일치 | `ctx.sampleRate !== 48000` → 배너 (§4.1) |

---

## 5. 단독 테스트 — B를 기다리지 않는다

**`web/tools/mock-listener/`가 존재하는 이유가 정확히 이것이다.** 이 장치가 없으면 네 `rtc/`(5h)와 `stats/`(3h), 합쳐서 8시간이 B의 진척에 매달린다. 기다리지 마라.

### 4인 mesh를 혼자 검증하는 절차

1. 호스트 섹션에서 방을 열고 코드를 확인한다 (예: `A3F9`)
2. 새 탭 4개를 연다:
   ```
   /tools/mock-listener/?code=A3F9&name=mock-1
   /tools/mock-listener/?code=A3F9&name=mock-2
   /tools/mock-listener/?code=A3F9&name=mock-3
   /tools/mock-listener/?code=A3F9&name=mock-4
   ```
   각 탭은 자동으로 join → offer 수신 → answer 회신 → 재생하고, 1초마다 가짜 `stats`를 보낸다.
3. 5번째 탭을 열면 `room-full` 에러가 나야 한다 (정원 검증)

### 이 조합으로 검증되는 것 전부

| 검증 항목 | 방법 |
|---|---|
| fan-out | 4탭에서 동시에 소리가 나는가 |
| SDP munging | `getStats()`의 `codec.sdpFmtpLine`에 `stereo=1`이 있는가. `targetBitrate`가 256k 근처인가 |
| 청취자별 PC 정리 | mock의 **[나가기]** → `peer-left reason:'leave'` → 그 PC만 닫히고 나머지 3탭은 멀쩡한가 |
| 강제 종료 경로 | mock의 **[강제 종료]**(leave 없이 소켓만 끊음) → `peer-left reason:'gone'` → 같은 결과인가 |
| 대시보드 | `AUDIO` 한 줄 + 청취자 4줄 + 상호 오프셋이 뜨는가 |
| 느린 청취자 격리 | 탭 하나를 백그라운드로 보내 `drop`을 만든 뒤 5초 뒤 그 PC만 끊기는가 |
| 재점유 | 호스트 탭 F5 → 같은 코드로 부활 + mock 4탭이 자동 복귀하는가 |
| room-full | 5번째 mock이 거절되는가 |

### 어디서 돌리나

| 목적 | 명령 | 주의 |
|---|---|---|
| UI만 만질 때 | `npm run dev` (포트 7990) | **vite dev 서버는 정적 자산만 준다.** WS 시그널링이 필요한 순간부터는 아래로 간다. `vite.config.ts`에 `/ws` 프록시가 있는지 먼저 확인해라 |
| 시그널링 포함 로컬 | `npm run dev:worker` (`wrangler dev --env a`) | DO까지 로컬에서 돈다. 빌드 산출물이 필요하면 `npm run build` 먼저 |
| **실기기** | `npm run deploy:a` | **HTTPS가 필수다.** `http://192.168.x.x`는 secure context가 아니라 `navigator.mediaDevices` 자체가 `undefined`가 되고 탭 캡처·마이크가 통째로 죽는다 |

**개발 중에는 로비 URL에 `?lobby=a`를 붙인다.** 안 붙이면 공인 IP 버킷이 같을 때 B의 목 데이터 방(440Hz 오실레이터)이 네 목록에 섞인다.

---

## 6. 배포 — `--env a`만 쓴다

| 슬롯 | 명령 | URL | 누가 |
|---|---|---|---|
| **env.a** | `npm run deploy:a` | `https://lt-web-a.<계정>.workers.dev` | **너만.** 하루 몇 번이든 |
| env.b | `npm run deploy:b` | `https://lt-web-b.<계정>.workers.dev` | B만. **절대 실행하지 마라** |
| canonical | `npm run deploy` | `https://lt-web.<계정>.workers.dev` | Sync 시점에 main에서 한 사람만. **절대 실행하지 마라** |

`npm run deploy`(canonical)는 `scripts/guard-canonical.mjs`가 브랜치·워킹트리·확인 입력을 검사해 막는다. **가드를 우회하지 마라.**

핵심은 **Durable Object 네임스페이스가 Worker 스크립트 단위로 갈린다**는 점이다. `lt-web-a`와 `lt-web-b`는 별개 스크립트라 LobbyDO·RoomDO 저장소가 완전히 독립된다. 네 로비에 B의 유령 호스트가 뜨거나, 같은 방코드가 상대 방을 열어버리는 사고가 원천 차단된다.

`joinUrl`은 서버가 `new URL(request.url).origin`으로 만들어 준다. **네 QR은 `room-created.joinUrl`을 그대로 인코딩하므로 자기 슬롯에서 찍은 QR이 자기 슬롯으로 간다.** 여기에 문자열을 덧붙이거나 고쳐 쓰지 마라.

---

## 7. 함정 목록 — 네가 반드시 밟을 것들

전부 실제로 사람을 태운 함정이다. 미리 읽어라.

| # | 함정 | 증상 | 대응 |
|---|---|---|---|
| 1 | **SDP munging을 `setLocalDescription` 전에 한다** | Chrome M138+가 봉쇄해 예외가 나거나 조용히 무시된다 | **`setRemoteDescription()` 직전에만.** 받은 answer를 고친다 |
| 2 | **munging 방향을 거꾸로 이해한다** | munging은 했는데 여전히 모노 32kbps | `stereo=1`은 "보내는 쪽이 스테레오 **수신**을 원한다". 송신자인 호스트가 **받은** answer에 있어야 스테레오로 인코딩한다 |
| 3 | **`getDisplayMedia({audio:true})`만 호출** | 대부분의 브라우저가 거부 | **`video: true`를 반드시 동반**하고, 받자마자 video 트랙을 `stop()` + `removeTrack()` |
| 4 | **`suppressLocalAudioPlayback`을 안 넣는다** | 캡처를 시작하는 순간 호스트 본인 스피커가 죽는다 | 명시적으로 `false`. **이게 죽으면 에코 간격법 실측(Act 6)과 탭 캡처 데모(Act 7)가 동시에 깨진다** |
| 5 | **크로스오리진 미디어에 `createMediaElementSource`** | 예외도 안 나고 **그냥 무음**. 원인 찾기 최악 | `blob:` URL(같은 오리진)만 쓴다. 원격 URL을 `<audio src>`에 물리지 마라 |
| 6 | **모바일에서 탭 캡처 라디오를 그냥 열어둔다** | Android Chrome은 API가 있는데 호출하면 항상 거부 → 사용자가 원인을 모른다 | **기능 감지로 `disabled` 처리** + 라벨 `브라우저 탭 (모바일 불가)` |
| 7 | **호스트를 음소거하거나 볼륨 0으로 둔다** | 백그라운드 스로틀에 걸려 송출이 조용히 죽는다 | Chrome은 **가청 오디오가 있는 탭만** 우대한다. 네이티브의 무음 keepalive와 **정반대**다. 코드로도 경고 배너를 강제한다 (§4.6) |
| 8 | **`AudioContext`를 기본 생성자로 연다** | 기기에 따라 44100이 잡히고 Opus 내부 48k와의 사이에 리샘플러가 낀다 → README가 제거한 46ms가 그대로 돌아온다 | `{sampleRate:48000, latencyHint:'interactive'}` + **`ctx.sampleRate` 재확인** |
| 9 | **`AudioContext`를 제스처 없이 만들고 끝** | `suspended` 상태로 남아 아무 소리도 안 나간다 | 사용자 제스처 안에서 `resume()` |
| 10 | **`candidate: null`을 릴레이 안 한다** | ICE가 늦게 끝나 첫 소리가 느려진다 | end-of-candidates도 그대로 보낸다 |
| 11 | **`contentHint`를 안 건다** | 브라우저가 음성 최적화를 걸어 음악이 뭉개진다 | 모든 트랙에 `'music'` |
| 12 | **`peer-left`에 전체를 재시작** | 한 명 나갈 때마다 전원의 소리가 끊긴다 | **그 PC만** close. 원본 `Client` 독립 큐 원칙 |
| 13 | **`hostToken` 만료를 안 본다** | 6시간 지난 토큰으로 `room-taken`을 계속 맞는다 | `exp` 검사 후 버린다. `room-taken`을 받으면 토큰을 버리고 새 코드 |
| 14 | **Firefox/Safari에서 탭 캡처를 시도** | Firefox는 오디오를 **조용히** 무시하고 Safari는 아예 없다 | 기능 감지. 시연은 Chrome 고정 |
| 15 | **`<audio>`를 DOM에 안 붙인다** | 일부 브라우저에서 재생이 안 되거나 스로틀된다 | DOM에 붙이고 `controls` 노출 |
| 16 | **B가 보낸 `stats`의 필드를 필수로 가정** | B가 필드를 추가/삭제하면 네 대시보드가 터진다 | 모르는 필드 무시, optional은 없을 수 있다고 가정. `deriveListenerStats`가 이미 그렇게 짜여 있다 |

---

## 8. 완료 기준 (Definition of Done)

전부 체크되어야 A 사이드가 끝난 것이다. **기분이 아니라 이 목록이 판정한다.**

### 기능

- [ ] `npm run typecheck` 무에러, `npm run build` 성공
- [ ] 소스 3종이 전부 동작하고, 모바일에서는 탭 캡처가 `disabled`로 보인다
- [ ] `ctx.sampleRate === 48000`이 실제로 확인되고, 아니면 배너가 뜬다
- [ ] 모든 트랙에 `contentHint='music'`
- [ ] 방 생성 → `room-created` → status 2줄 + QR + 참여 링크가 뜬다
- [ ] 호스트 탭 F5 → **같은 방 코드로 부활**하고 기존 리스너가 자동 복귀한다
- [ ] 재점유 후 소스가 없으면 `공유 중`이 아니라 **소스 복구 배너**가 뜬다
- [ ] `stop-share` → `공유를 중지했습니다`, 방이 즉시 닫힌다
- [ ] `room-taken` → 새 코드로 재시도 + `방을 만들지 못했습니다 — 재시도 중`
- [ ] mock-listener 4탭에서 동시에 소리가 나고, 5번째는 `room-full`
- [ ] mock의 [나가기]/[강제 종료] 둘 다 **그 PC만** 정리된다
- [ ] `drop>0` 5초 연속 → 그 PC만 끊기고 배너가 뜬다
- [ ] Wake Lock 획득 + `visibilitychange`/`release`에서 재요청
- [ ] 호스트 MediaSession이 실제로 뜨고 잠금화면 'stop'이 동작한다

### 계측

- [ ] `AUDIO rms= peak= clients= gap= cap= net= drop=` 한 줄이 1초마다 갱신된다 (`fill=` 없음)
- [ ] `cap<95%`면 `  <<< 캡처 유실`이 붙는다
- [ ] 청취자별 `PLAY …` 줄이 `statsLine()` 형식 그대로 나온다
- [ ] 상호 오프셋 한 줄이 나온다
- [ ] `getStats()`의 `codec.sdpFmtpLine`에 `stereo=1`이 **실제로** 들어갔음을 확인했다 (추측 금지)
- [ ] selected candidate pair가 `host↔host`임을 화면에서 읽을 수 있다

### 문구·경계

- [ ] 모든 한국어 문구가 `shared/strings.ts`에서 왔다. **지어낸 문구가 하나도 없다**
- [ ] 공백 2칸(`님의 소리  (`, `  · 적용하려면…`), `QR 을` 사이 공백 같은 원문 디테일이 살아 있다
- [ ] `git status`에 `web/host/**` 밖의 변경이 **하나도 없다**
- [ ] DOM id/class가 전부 `h-`, localStorage 키가 전부 `lt.host.*`
- [ ] `npm run deploy:a`가 성공하고 폰에서 그 URL로 실제 동작한다

---

## 9. Sync 시점에 준비해서 나올 것

| | 시점 | 네가 준비할 것 |
|---|---|---|
| **Sync 1** 시그널링 관통 | 각자 ~6h 후 / 30분 | **파일 재생 소스 + fan-out이 도는 배포본**(`lt-web-a`). 방 코드 하나를 미리 열어 두고, B의 리스너가 그 코드로 붙었을 때 `connectionState='connected'`와 **selected candidate pair가 `host↔host`**임을 그 자리에서 보여줄 수 있어야 한다. 실패하면 프로토콜 결함이므로 즉시 공동 수정 대상 |
| **Sync 2** 소리 E2E | ~12h / 1h | **폰 호스트에서 파일 재생이 실제로 나가는 상태.** 갈아타기·나가기·**호스트 새로고침 후 방코드 유지**를 네 쪽에서 재현할 수 있어야 한다. 에코 간격법 1차 실측에 쓸 **클릭 트랙(2초 간격 wav)을 미리 준비**해 소스로 걸 수 있게 해 둔다 |
| **Sync 3** 풀 데모 조건 | ~16h / 1~2h | **노트북 탭 캡처가 되는 상태 + 대시보드 완성.** 청취자 3~4명 mesh에서 net%가 실시간으로 갱신되는 걸 보여준다. **호스트 백그라운드·화면 끔 실측**(30초/5분, Chrome 배터리 '제한 없음' ON/OFF 두 조건)을 네가 직접 재고 숫자를 가져온다 — **이 숫자가 시연 Act 9 멘트의 근거가 된다.** 호스트 MediaSession 알림이 실제로 떴는지도 확인 항목 |
| **Sync 4** 리허설 | 시연 전날 / 반나절 | 시연 장소 Wi-Fi에서 2회 통주. **프리플라이트 판정(candidate pair)을 20분 안에 끝내는 동선**을 네가 소유한다. fallback F1(핫스팟)·F3(탭 캡처 실패)의 호스트 측 조작을 스톱워치로 각 2회 |

**Sync 전에 반드시**: 자기 슬롯(`lt-web-a`)에 최신 배포를 올려두고, 그 URL을 공유할 수 있게 해 둔다. 로컬에서만 되는 건 Sync에서 쓸모가 없다(HTTPS 문제로 실기기가 안 붙는다).

---

## 10. 작업 규칙

### git — **커밋·푸시·머지는 사용자가 명시적으로 지시할 때만**

절대로 스스로 `git commit` / `push` / `merge`를 실행하지 않는다. 파일을 만들고 고치는 데서 멈춘다. 사용자가 "커밋해줘"라고 명시적으로 말했을 때만 실행한다.

- **브랜치**: `feat/1` (형식 `type/번호`, type은 feat/fix/chore/style/design)
- **Step 0 커밋에서 브랜치를 딴다.** 그 이전 커밋에서 따면 shared가 없어 아무것도 컴파일되지 않는다
- **커밋 메시지**: `type(#번호): 설명`
  ```
  feat(#1): 로컬 음악 파일 소스 — MediaStreamDestination 이중 연결
  feat(#1): answer SDP opus munging + 청취자별 PC 정리
  fix(#1): 재점유 후 소스 없이 '공유 중'을 쓰던 문제
  ```
- **`Co-Authored-By` 트레일러를 붙이지 않는다**
- **B의 브랜치를 rebase하거나 force-push하지 않는다.** 자기 브랜치만 건드린다
- 머지에서 충돌이 나면 그건 **누군가 소유권 경계를 넘었다는 신호다.** 머지로 때우지 말고 어디서 넘었는지부터 찾는다
- `dist/`, `node_modules/`, `.wrangler/`는 커밋하지 않는다

### 에러 보고 — 로그만 받으면 바로 고치지 않는다

사용자가 아무 말 없이 에러 코드나 로그만 붙여넣으면 **즉시 코드를 수정하지 않는다.** 반드시 먼저 두 가지를 보고한다:

1. **에러 원인 분석** — 왜 발생했는지
2. **수정 계획** — 어떻게 고칠 것인지, 어느 파일을 건드릴 것인지

사용자가 명시적으로 수정을 지시한 후에만 파일을 변경한다.

### 그 밖에

- 이모지를 쓰지 않는다 (UI 문구에 원본이 포함한 `🎧`는 예외 — 그건 원본 계승이다)
- 문구는 **반드시** `shared/strings.ts`에서 import한다. 지어내지 않는다
- 상수는 **반드시** `shared/protocol.ts`에서 import한다. 숫자를 직접 쓰지 않는다
- 막히면 혼자 오래 붙잡지 말고, shared 수정이 필요한지부터 판단해 사용자에게 보고한다
