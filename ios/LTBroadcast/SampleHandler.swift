import ReplayKit
import os

/// Broadcast Upload Extension 본체.
///
/// **검증하려는 것은 단 하나다 — `audioApp` 이 유튜브뮤직 같은 "다른 앱"의 소리를
/// 주느냐.** 널리 퍼진 "AVPlayer·음악 앱은 제외된다"는 말의 출처를 따라가 보면
/// 2018년경 문서 하나로 수렴하고, 애플 공식 문서에는 그런 제외 조항이 없다.
/// 즉 "불가능"이 아니라 "아무도 확인해준 적이 없음"이다. 이 확장이 그걸 확인한다.
///
/// 판정은 **귀가 아니라 로그**로 한다. 무음이 나와도 "캡처가 막힌 것"과
/// "재생이 멈춘 것"은 귀로 구분되지 않는다. 안드로이드에서 같은 함정에 여러 번 빠졌다.
///
///   AUDIO buf=51 rms=12079 peak=30613 clients=1  ← 다른 앱 소리가 잡힌다
///   AUDIO buf=51 rms=0     peak=0     clients=1  ← 버퍼는 오는데 무음 = 막혔다
///   (AUDIO 줄이 아예 없음)                        ← audioApp 자체가 안 온다
class SampleHandler: RPBroadcastSampleHandler {

    private let converter = PCMConverter()
    private let server = StreamServer()
    private let beacon = Beacon()

    private var bufCount = 0
    private var sumSquares: Double = 0
    private var sampleCount = 0
    private var peak: Int16 = 0
    private var lastLog = Date()

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        server.start()
        beacon.start()
        let addrs = Net.interfaces().map { "\($0.name)=\($0.address)" }.joined(separator: " ")
        os_log("방송 시작 — 주소 %{public}@ 포트 %d",
               log: log, type: .info, addrs, Int(LTPort.stream))
    }

    override func broadcastPaused() { os_log("일시정지", log: log, type: .info) }
    override func broadcastResumed() { os_log("재개", log: log, type: .info) }

    override func broadcastFinished() {
        beacon.stop()
        server.stop()
        os_log("방송 종료 — audioApp 버퍼 %d개 수신", log: log, type: .info, bufCount)
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                      with sampleBufferType: RPSampleBufferType) {
        switch sampleBufferType {
        case .audioApp:
            handleAudio(sampleBuffer)
        case .audioMic:
            break                      // 마이크는 안 쓴다. 우리는 "앱 소리"만 필요하다.
        case .video:
            break                      // 화면은 버린다. 받긴 하지만 아무것도 안 한다.
        @unknown default:
            break
        }
    }

    private func handleAudio(_ sampleBuffer: CMSampleBuffer) {
        bufCount += 1
        guard let pcm = converter.convert(sampleBuffer) else { return }

        // RMS/peak 는 변환된 Int16 기준으로 잰다. 안드로이드 로그와 같은 척도라
        // 두 플랫폼 수치를 그대로 비교할 수 있다.
        pcm.withUnsafeBytes { raw in
            let p = raw.bindMemory(to: Int16.self)
            for v in p {
                let d = Double(v)
                sumSquares += d * d
                sampleCount += 1
                let a = v == Int16.min ? Int16.max : abs(v)
                if a > peak { peak = a }
            }
        }

        server.broadcast(pcm)

        let now = Date()
        if now.timeIntervalSince(lastLog) >= 1.0 {
            let rms = sampleCount > 0 ? Int(( sumSquares / Double(sampleCount) ).squareRoot()) : 0
            os_log("AUDIO buf=%d rms=%d peak=%d clients=%d drop=%d fmt=%{public}@",
                   log: log, type: .info,
                   bufCount, rms, Int(peak), server.clientCount, server.droppedTotal,
                   converter.describedFormat ?? "?")
            bufCount = 0; sumSquares = 0; sampleCount = 0; peak = 0
            lastLog = now
        }
    }
}
