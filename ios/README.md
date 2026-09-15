# 같이 듣기 — iOS 호스트 (검증용)

**목적은 앱 출시가 아니라 질문 하나에 답하는 것이다.**

> ReplayKit 의 `audioApp` 이 유튜브뮤직 같은 **다른 앱**의 소리를 주는가?

"AVPlayer·음악 앱은 제외된다"는 말이 널리 퍼져 있지만, 출처를 따라가 보면
2018년경 문서 하나로 수렴하고 **애플 공식 문서에는 그런 제외 조항이 없다.**
즉 "불가능"이 아니라 "아무도 확인해준 적이 없음"이다. 이걸 확인한다.

동작하면 안드로이드판 클라이언트(브라우저 · 안드로이드 앱)가 **고치지 않고 그대로** 붙는다.
포트·PCM 포맷·비콘 페이로드를 전부 맞춰놨다.

## 준비물

| 필요한 것 | 비고 |
|---|---|
| Xcode | App Store, 10GB+. **현재 맥에 없음** |
| 아이폰 실기기 | 시뮬레이터는 불가 (App Store 없음 → 낼 소리가 없음, ReplayKit 미동작) |
| Apple ID | 무료 계정으로 충분. 7일마다 재설치 |

## 빌드

```bash
brew install xcodegen
cd ~/Desktop/listen-together-ios
xcodegen                      # project.yml → ListenTogether.xcodeproj
open ListenTogether.xcodeproj
```

Xcode 에서 **두 타겟 모두**(LTHost, LTBroadcast) Signing & Capabilities 탭에서
Team 을 본인 Apple ID 로 지정한다. 번들 ID 가 이미 쓰이고 있다고 하면
`com.example.lt` 를 `com.본인.lt` 로 바꾸되 **확장은 반드시 `<앱ID>.broadcast`**
로 맞춘다 — `HostViewController.preferredExtension` 과 다르면 피커 목록에 안 뜬다.

XcodeGen 이 싫으면 Xcode 에서 직접:
App 타겟 만들고 → File ▸ New ▸ Target ▸ **Broadcast Upload Extension** →
생성된 `SampleHandler.swift` 를 이 저장소 것으로 갈아끼우고 나머지 파일을 끌어다 놓는다.

## 검증 절차

1. 아이폰에 앱 설치 후 실행 → **로컬 네트워크 권한 허용**
2. **유튜브뮤직을 먼저 재생한다** (이게 핵심이다. 낼 소리가 있어야 한다)
3. 앱으로 돌아와 방송 버튼 → 우리 확장 선택 → 방송 시작 (3초 카운트다운)
4. 맥에서 로그를 본다

```bash
# 아이폰을 USB 로 연결한 상태에서
xcrun devicectl list devices                     # UDID 확인
log stream --device --predicate 'subsystem == "com.example.lt"' --info
```

## 판정 — 귀가 아니라 로그로 한다

무음이 나와도 "캡처가 막힌 것"과 "재생이 멈춘 것"은 귀로 구분되지 않는다.
안드로이드에서 같은 함정에 여러 번 빠졌다.

```
AUDIO buf=51 rms=12079 peak=30613 clients=1 drop=0 fmt=44100Hz 2ch float planar
```

| 로그 | 뜻 |
|---|---|
| `rms` 가 0 보다 크게 요동 | ✅ **다른 앱 소리가 잡힌다 — 가능하다** |
| `buf` 은 오는데 `rms=0` 고정 | ❌ 버퍼는 주는데 내용이 비었다 = 막혀 있다 |
| `AUDIO` 줄이 아예 안 뜸 | ❌ `audioApp` 자체가 안 온다 |
| `방송 시작` 도 안 뜸 | 확장이 안 붙은 것. 번들 ID 부터 확인 |

`rms` 가 살아 있으면 맥 브라우저에서 `http://<아이폰IP>:7980` 을 열어
실제로 들리는지까지 확인한다. 주소는 앱 화면에 다 떠 있다.

## 구조

| 파일 | 역할 |
|---|---|
| `LTShared/Shared.swift` | 포트·PCM 포맷·인터페이스 목록. 앱과 확장이 소스로 공유 |
| `LTBroadcast/SampleHandler.swift` | 확장 본체. `audioApp` 수신 + RMS 로그 |
| `LTBroadcast/PCM.swift` | ReplayKit 버퍼 → 44100/16bit/스테레오 변환 |
| `LTBroadcast/Server.swift` | HTTP/WAV 서버 :7980, 클라이언트별 큐 |
| `LTBroadcast/Beacon.swift` | UDP 비콘 :7981 (`LT1\|모델\|IP`) |
| `LTHost/HostViewController.swift` | 방송 피커 + 주소 표시 + 로컬 네트워크 권한 유발 |

**서버가 앱이 아니라 확장 안에서 돈다.** iOS 는 앱 본체를 홈 화면으로 나가는
순간 정지시키지만 Broadcast Upload Extension 은 별도 프로세스로 계속 살아 있다.
안드로이드에서 포그라운드 서비스가 하던 역할이 여기선 확장이다.

## 미리 알고 있는 위험

| 위험 | 대응 |
|---|---|
| **확장 메모리 50MB** jetsam 한도 | 링버퍼를 안 쓰고 받는 즉시 흘려보낸다. 클라이언트 큐도 5초로 제한 |
| 로컬 네트워크 권한을 확장이 못 띄움 | 앱 본체가 실행 시 probe 를 쏴서 미리 유발. **이 가정 자체가 미검증** |
| Float/Int, 44.1k/48k 포맷이 기기마다 다름 | 양쪽 다 받고 리샘플. 첫 버퍼 포맷을 `fmt=` 로 찍으니 틀리면 로그에 드러남 |
| 사파리가 무한 스트림을 거부 | `Range` 요청에 **206 + 아주 큰 Content-Range** 로 답한다. 안드로이드판엔 이 처리가 없다 |
| 시작 마찰 | 매 세션 피커 → 3초 카운트다운. 안드로이드(동의창 1번)보다 확실히 번거롭다 |

## 아직 확인 못 한 것

- **아무것도 실행해보지 않았다.** 맥에 Xcode 가 없어 빌드 자체를 못 했다
- 타입 체크는 `Shared` · `Server` · `Beacon` · `PCM` 네 파일만 통과했다
  (macOS SDK 로 검사 가능한 범위). `SampleHandler` · `HostViewController` 는
  ReplayKit/UIKit 이라 미검사
- 심사 통과 여부는 별개 문제다. 타 앱 오디오를 네트워크로 내보내는 게
  통과할지 불명
