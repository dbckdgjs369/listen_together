# 같이 듣기 (listen-together)

친구 2~4명이 **각자 자기 이어폰으로 같은 소리를 듣는** 앱. 이어폰 한쪽씩 나눠 끼는 걸 대체하는 게 목표다.

호스트(안드로이드)가 재생 중인 오디오를 `AudioPlaybackCapture` 로 가로채 로컬 네트워크에 뿌리고,
참여자는 **브라우저로 접속하거나**(설치 불필요) 이 앱으로 듣는다.

> PoC 단계다. Java, 외부 의존성 없음.

## 동작 방식

```
[호스트 폰]  유튜브뮤직 등 재생
      │  AudioPlaybackCapture (MediaProjection 동의 필요)
      ▼
  CaptureService ──┬── HTTP/WAV 스트림  :7980  → 참여자 브라우저 / PlayerService
                   └── UDP 비콘        :7981  → 참여자 앱이 호스트를 자동 발견
```

- 오디오는 무압축 PCM 44.1kHz/16bit/스테레오 = **1.41 Mbps/명**. 재인코딩이 없어 원본과 비트 동일
- 전부 로컬이라 **참여자 데이터 소모 0**, 호스트가 오프라인 저장곡을 틀면 완전 오프라인 동작
- 호스트가 핫스팟(`startLocalOnlyHotspot`)을 열 수 있어 같은 Wi-Fi가 없어도 된다

## 빌드 / 설치

```bash
JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.19+10/Contents/Home \
  ./gradlew assembleDebug

adb install -r --user 0 app/build/outputs/apk/debug/app-debug.apk
```

`--user 0` 은 Secure Folder 가 있는 삼성 기기에서 필요하다.

minSdk 29 (AudioPlaybackCapture 요구), targetSdk 36.

## 구성

| 파일 | 역할 |
|---|---|
| `CaptureService` | 오디오 캡처 + HTTP 서버 + UDP 비콘. 클라이언트별 큐로 느린 청취자가 전체를 막지 않게 한다 |
| `PlayerService` | 참여자 네이티브 재생. 소켓을 직접 읽어 브라우저 `<audio>` 의 2~5초 버퍼를 피한다 |
| `MainActivity` | 호스트/참여자 전환, 공유 대상 앱 선택, 핫스팟 QR |

## 검증된 것

- ✅ 유튜브뮤직 · 지니 캡처됨 (스포티파이는 앱이 자체 차단)
- ✅ **화면을 꺼도 유지됨** — `Dozing` 상태에서 무결. 핵심 요구사항
- ✅ 맥·브라우저에서 실제 청취
- ✅ `addMatchingUid()` 로 **선택한 앱의 소리만** 공유 (호출하는 순간 화이트리스트가 된다)
- ✅ STA+AP 동시 동작 — 기존 Wi-Fi 를 유지한 채 핫스팟 제공

## 알려진 문제

**앱 전환 시 순간 끊김.** 원인을 실측으로 좁혔다.

캡처 스레드가 포그라운드 서비스인데도 앱이 백그라운드로 가면
`cpuset:/moderate` + `cpu:/background` 로 강등된다. 그 상태에서 최근앱 전환
애니메이션이 CPU 를 몰아 쓰면 스레드가 1~2초 스케줄을 못 받고, 링버퍼가 넘쳐
그 구간 오디오가 영구히 사라진다. 폰 자체 소리는 멀쩡하다 — audioserver 는
별도 프로세스에 실시간 우선순위라 영향을 안 받기 때문이다.

`THREAD_PRIORITY_URGENT_AUDIO` 는 걸려 있지만 효과가 없다.
**cgroup `cpu.shares` 가 nice 값보다 상위 제약**이라 우선순위로는 풀리지 않는다.

대응(검증 중): 링버퍼 3초 + 송신 큐 5초, ADPF 성능 힌트, FGS 타입에 `mediaPlayback` 추가.

진단은 logcat 태그 `LT` 한 줄로 본다:

```
AUDIO rms=12079 peak=30613 clients=1 gap=1058ms cap=9%  net=9% drop=0  <<< 캡처 유실
                                                        │      │       └ 우리 큐가 버린 청크
                                                        │      └ 실제로 소켓에 나간 양
                                                        └ 캡처가 읽은 양 (기대치 대비)
```

| cap | net | drop | 범인 |
|---|---|---|---|
| <100% | — | — | 캡처 유실 (링버퍼 넘침) |
| 100% | <100% | >0 | 송신 큐 (네트워크가 못 따라감) |
| 100% | 100% | 0 | 폰은 무죄 — 브라우저 `<audio>` 버퍼 |

**귀로 판단하지 말 것.** 무음이 나와도 "캡처 차단"과 "재생 멈춤"이 구분되지 않는다.

## 미검증

- 1시간+ 장시간 안정성 · 배터리
- `PlayerService` 실지연 측정
- 아이폰 수신, 곡 전환 시 스트림 지속
- 멜론 (앱 미설치)

## 한계

- **호스트는 안드로이드 전용.** iOS 에는 시스템 오디오 캡처 API 가 없다
- MediaProjection 동의가 매 세션 필요하다. 토큰은 프로세스에 묶여 있어 저장할 수 없다
- 동의 창이 "화면 공유"를 말한다. 화면은 쓰지 않지만 안드로이드가 오디오 캡처에도 같은 동의를 요구한다
