# 이음 — iOS

아이폰 앱. **듣기**(같은 Wi-Fi 방 찾기 · 코드 입장)와 **공유하기**(화면 방송으로 다른 앱 소리 내보내기)를 한다.
안드로이드판과 포트 · PCM 포맷 · 비콘 · 청취 페이지가 같아서, 어느 쪽이 호스트든 서로 붙는다.

> "ReplayKit `audioApp` 이 다른 앱 소리를 주는가?" — **준다.** 아이폰 14(iOS 27)에서 확인했다.
> 다만 그대로 쓰면 무음 · 8배속이 된다(아래 '조각 모드'). 그걸 고친 게 지금 코드다.

## 빌드 · 설치

```bash
brew install xcodegen
cd ios
xcodegen                                   # project.yml → Ieum.xcodeproj (커밋하지 않는다)
xcodebuild -project Ieum.xcodeproj -scheme Ieum -destination 'id=<UDID>' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=<팀ID> build
xcrun devicectl device install app --device <UDID> \
  ~/Library/Developer/Xcode/DerivedData/Ieum-*/Build/Products/Debug-iphoneos/Ieum.app
```

- 팀 ID 는 커밋하지 않는다. 빌드할 때 넘긴다.
- 확장 번들 ID 는 반드시 `<앱ID>.broadcast` 다. 다르면 방송 피커 목록에 안 뜬다.
- 확장(`IeumBroadcast`)은 **Debug 에서도 `-O`** 로 빌드한다(project.yml). 최적화 없이 돌리면
  처리가 늦어 ReplayKit 이 버퍼를 버린다.
- 청취 페이지(`LTBroadcast/Page.swift`)는 빌드 전에 `scripts/gen_page.sh` 가 안드로이드
  `CaptureService.PAGE` 에서 뜬다. 손으로 고치지 않는다.
- 화면 문구는 저장소 루트 `i18n/` 이 원본이다. `npm run i18n` 이 `LTShared/L.swift`(문구 접근자),
  `*.lproj/Localizable.strings`·`InfoPlist.strings`, `LTBroadcast/PageText.swift`(청취 페이지 문구)를 만든다.
  코드에서는 `L.shareStart` 처럼 쓴다 — 키를 문자열로 치지 않는다.

## 구조

| 파일 | 역할 |
|---|---|
| `LTShared/Shared.swift` | 포트 · PCM 포맷(48000/16bit/스테레오/LE) · 로그 · 인터페이스 목록 |
| `Ieum/` | 앱 본체. `ListenView`·`Player`(듣기), `ShareView`(방송 피커), `Discovery`(비콘), `RoomCode` |
| `LTBroadcast/SampleHandler.swift` | 확장 본체. `audioApp` 만 받아 아래 파이프라인으로 흘린다 |
| `LTBroadcast/PCM.swift` | `decode`(CMSampleBuffer → 채널별 Float) / `resample`(→ 48k Int16) |
| `LTBroadcast/SparseRepair.swift` | 조각 모드 감지 · 되붙이기 |
| `LTBroadcast/Pacer.swift` | 실제 시간에 맞춘 송출(100ms 모아 두고 10ms 마다, 모자라면 무음) |
| `LTBroadcast/Server.swift` | HTTP :7980 — `/stream` `/info` `/check` `/host` `/stop`(앱의 공유 중지, 같은 폰만), Debug 전용 `/diag` `/diagraw` |
| `LTBroadcast/Beacon.swift` | UDP :7981 비콘 |
| `LTBroadcast/Diag.swift` | Debug 전용 진단 수치 |
| `IeumUITests/DriveTests.swift` | 실기기 자동 조작용 UI 테스트(공유 시작/중지, 사파리 재생 탭) |

**서버는 앱이 아니라 확장 안에서 돈다.** 앱 본체는 백그라운드로 가면 멈추지만
Broadcast Upload Extension 은 별도 프로세스로 살아 있다.

## 공유 파이프라인

```
audioApp → decode → SparseRepair → resample(48k) → Pacer → server.broadcast
```

### ReplayKit 이 주는 것 (실측)

| | 평소 | 조각 모드 |
|---|---|---|
| 언제 | 기본 | 어떤 앱이든 입출력 주기를 짧게 잡을 때(사파리 Web Audio, 게임 등). 기기 전체가 같이 바뀐다 |
| 포맷 | 44100Hz Int16 **빅엔디안** 인터리브 스테레오 | 같음 |
| 버퍼 | 1024프레임이 실제 시간대로(4개씩 몰아서) | 초당 770~1370개. 대부분 0, 그 안에 **진짜 소리 약 118프레임 한 조각** |
| pts | 믿을 만함 | **무의미**(실제보다 훨씬 빠르게 감) |

조각 모드를 그대로 내보내면 8~17배 길이의 띄엄띄엄한 소리가 되어 청취자에겐 무음 · 지글거림만 남는다.

- `SparseRepair`: 50ms 묶음이 실제의 2.5배를 넘으면 조각 모드로, 1.6배 아래가 10묶음 이어지면 평소로.
  조각(0 사이의 섬)을 떼어 직전 조각 뒤 이음매가 가장 매끄러운 자리(1/4 샘플 단위)에 겹쳐 더한다.
- `Pacer`: 들어오는 쪽이 어떻든 나가는 쪽은 정확히 초당 48000프레임. 300ms 넘게 쌓이면 100ms 로 줄인다.

### 결과 (아이폰 14 → 맥 / 노트9, 1kHz 순음)

| 상황 | 속도 | SNR |
|---|---|---|
| 조각 모드(사파리 Web Audio) | 1.00x | 44dB (20초) |
| 평소(사파리 `<audio>`) | 1.00x | 56dB |
| 무음 | 1.00x, 연결 유지 | — |
| 노트9 청취 | 언더런 0, 버퍼 ≈78ms, 수신률 99~100% | — |
| 아이폰 공유 중지 | 노트9 가 410 을 받고 0.05초 만에 종료 | — |

## 확인 · 디버깅

판정은 귀가 아니라 숫자로 한다. 무음이 들려도 '캡처가 막힘'과 '재생이 멈춤'은 귀로 구분이 안 된다.

```bash
# Debug 빌드 확장이 방송 중일 때, 같은 Wi-Fi 의 맥에서
curl http://<아이폰IP>:7980/diag            # 포맷, 초당 버퍼·프레임, rms, sparse/placed/underruns/overflows
curl "http://<아이폰IP>:7980/diag?rec=1"    # 원본 버퍼 기록 켜기(무겁다) → /diagraw 로 약 2초분 내려받기
```

- `lastSecond.outSecPerSec` 가 1 근처가 아니면 속도가 틀린 것이다.
- `mode` 의 `sparse=true` 면 조각 모드, `underruns` 가 계속 늘면 입력이 모자란 것이다.
- `/diag` · `/diagraw` 는 `#if DEBUG` 라 Release 에는 없다.

UI 테스트로 손대지 않고 공유를 켜고 끌 수 있다(기기 잠금 해제 상태여야 한다):

```bash
xcodebuild test-without-building -project Ieum.xcodeproj -scheme Ieum \
  -destination 'id=<UDID>' -only-testing:IeumUITests/DriveTests/testStartShare
```

## 알려진 제약

| 내용 | 비고 |
|---|---|
| **화면이 잠기면 방송이 끝난다** | iOS 동작이라 못 막는다. 공유 중엔 자동 잠금을 끄고(`isIdleTimerDisabled`) 화면에 안내한다 |
| 시작 마찰 | 매번 방송 피커 → 3초 카운트다운 |
| 확장 메모리 50MB | 클라이언트 큐 제한, Pacer 최대 300ms |
| 심사 | 타 앱 소리를 네트워크로 내보내는 게 통과할지 모른다 |

## 아직 확인 못 한 것

- 실제 음악 앱(유튜브뮤직 · 애플뮤직 등)으로 장시간 공유. 테스트는 사파리 순음으로만 했다.
- ReplayKit 이 버퍼를 1/4 만 준 경우가 한 번 있었다. 재현은 안 됐다.
- 카메라로 초대 QR 을 찍어 앱이 열리는지(유니버설 링크) 실기기 확인.
