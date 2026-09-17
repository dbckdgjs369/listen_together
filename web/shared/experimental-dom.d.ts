// web/shared/experimental-dom.d.ts — FROZEN [S0]
//
// 실험적/부분지원 DOM API 를 **한 곳에서만** 선언한다.
// 흩어지면 한쪽에서만 컴파일 에러가 나고, 그 순간 각자 루트에 global.d.ts 를 만들기 시작한다.
//
// 규칙: 반드시 '상류(lib.dom)와 동일한 타입' 으로 선언한다.
//       optional/required 나 타입이 어긋나면 인터페이스 병합이 그 자리에서 에러가 된다.
//       package.json 이 typescript 를 정확 버전으로 핀하므로 양쪽 lib.dom 이 달라지지 않는다.
//
// ── CONTRACT.md §6 목록에서 줄어든 이유 ────────────────────────────────
// 계약서가 열거한 선언 대부분은 **핀된 typescript 7.0.2 의 lib.dom 에 이미 들어와 있다.**
// 확인한 것(= 여기서 다시 선언하지 않는다):
//
//   AudioContext.outputLatency / baseLatency          ✓ 상류에 있음
//   MediaStreamTrack.contentHint                      ✓
//   WakeLock / WakeLockSentinel / Navigator.wakeLock   ✓
//   RTCInboundRtpStreamStats
//     jitterBufferDelay / jitterBufferEmittedCount
//     concealedSamples / concealmentEvents
//     audioLevel / totalAudioEnergy                   ✓ 전부
//   RTCOutboundRtpStreamStats
//     targetBitrate / totalPacketSendDelay
//     totalSamplesDuration                            ✓ 전부
//
// 이미 있는 걸 굳이 다시 적으면 얻는 게 없고, 상류가 다음 버전에서 modifier 를 하나만 바꿔도
// 병합 에러로 빌드가 멈춘다. 그래서 **진짜 없는 것만** 남긴다.
// (typescript 를 올릴 때 이 목록을 다시 확인한다 — 그때 이 주석이 체크리스트가 된다.)
export {};

declare global {
  interface RTCRtpReceiver {
    /**
     * 수신 지터버퍼 목표(ms). Chrome/Edge 124+, FF 115+, Safari 27+.
     * 상류 lib.dom 에는 아직 없다(stats 쪽 jitterBufferTargetDelay 와 혼동 주의 — 다른 것이다).
     * ★기능 감지 후에만 대입한다. 미지원 브라우저에서는 기본 적응형 NetEq 에 맡긴다.
     */
    jitterBufferTarget: number | null;
    /** 구명칭(초 단위) — 폴백용 */
    playoutDelayHint?: number;
  }

  interface DisplayMediaStreamOptions {
    /**
     * Chromium 전용. 캡처 중에도 호스트 본인 스피커로 계속 나오게 하려면 false.
     * 기본값이 true 라서 안 주면 "호스트만 소리가 안 들리는" 상태가 된다.
     */
    suppressLocalAudioPlayback?: boolean;
    preferCurrentTab?: boolean;
    systemAudio?: 'include' | 'exclude';
  }
}
