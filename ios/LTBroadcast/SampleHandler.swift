import ReplayKit
import os

/// Broadcast Upload Extension 본체 — 아이폰이 호스트일 때 소리를 잡아 뿌린다.
///
/// 판정은 **귀가 아니라 로그**로 한다. 무음이 나와도 "캡처가 막힌 것"과
/// "재생이 멈춘 것"은 귀로 구분되지 않는다. 안드로이드에서 같은 함정에 여러 번 빠졌다.
///
///   AUDIO buf=51 rms=12079 peak=30613 clients=1  ← 다른 앱 소리가 잡힌다
///   AUDIO buf=51 rms=0     peak=0     clients=1  ← 버퍼는 오는데 무음 = 막혔다
///   (AUDIO 줄이 아예 없음)                        ← audioApp 자체가 안 온다
class SampleHandler: RPBroadcastSampleHandler {

    private let converter = PCMConverter()
    private let server = StreamServer(roomName: L.extDefaultRoom)
    private let beacon = Beacon()
    private let repair = SparseRepair()
    /// 실제 시간에 맞춰 내보낸다. 서버로 바로 흘리지 않고 이걸 거친다(Pacer.swift).
    private lazy var pacer = Pacer { [server] d in server.broadcast(d) }

    private var bufCount = 0
    private var sumSquares: Double = 0
    private var sampleCount = 0
    private var peak: Int16 = 0
    private var lastLog = Date()
    #if DEBUG
    private var handleMaxMs = 0.0, handleSumMs = 0.0, handleN = 0
    #endif

    override func broadcastStarted(withSetupInfo setupInfo: [String: NSObject]?) {
        // 앱의 '공유 중지'. 확장이 스스로 끝내는 길은 이것뿐이고, iOS 가 이 문구로 '방송 중지됨' 알림을 띄운다.
        // 끝내기 전에 청취자에게 '끝났다'(410)를 알릴 시간을 준다 — broadcastFinished 와 같은 정리.
        server.onStopRequest = { [weak self] in
            DispatchQueue.global().async {
                guard let self else { return }
                self.shutdown(farewell: 2)
                let e = NSError(domain: "app.ieum", code: 0,
                                userInfo: [NSLocalizedDescriptionKey: L.extStopped])
                self.finishBroadcastWithError(e)
            }
        }
        server.start()
        pacer.start()
        beacon.start(server: server)
        let addrs = Net.interfaces().map { "\($0.name)=\($0.address)" }.joined(separator: " ")
        os_log("방송 시작 — 주소 %{public}@ 포트 %d", log: log, type: .info, addrs, Int(LTPort.stream))
    }

    override func broadcastPaused() { os_log("일시정지", log: log, type: .info) }
    override func broadcastResumed() { os_log("재개", log: log, type: .info) }

    override func broadcastFinished() {
        // 청취자에게 "끝났다"를 알릴 몇 초. 이 함수가 돌아가면 확장이 곧 정리되므로 여기서 기다린다.
        shutdown(farewell: 4)
    }

    private let stopLock = NSLock()
    private var stopped = false

    /// 앱의 '공유 중지'와 시스템 종료가 겹쳐도 한 번만 정리한다.
    private func shutdown(farewell: TimeInterval) {
        stopLock.lock()
        let first = !stopped; stopped = true
        stopLock.unlock()
        guard first else { return }
        beacon.stop()
        pacer.stop()
        server.stop(farewell: farewell)
        os_log("방송 종료", log: log, type: .info)
    }

    override func processSampleBuffer(_ sampleBuffer: CMSampleBuffer,
                                      with sampleBufferType: RPSampleBufferType) {
        switch sampleBufferType {
        case .audioApp:
            #if DEBUG
            let t = CFAbsoluteTimeGetCurrent()
            handleAudio(sampleBuffer)
            let ms = (CFAbsoluteTimeGetCurrent() - t) * 1000
            handleMaxMs = max(handleMaxMs, ms); handleSumMs += ms; handleN += 1
            #else
            handleAudio(sampleBuffer)
            #endif
        case .audioMic, .video:
            break                      // 마이크·화면은 버린다. 우리는 "앱 소리"만 필요하다.
        @unknown default:
            break
        }
    }

    private func handleAudio(_ sampleBuffer: CMSampleBuffer) {
        bufCount += 1
        // 디코딩 → (조각 모드면) 되붙이기 → 48k 리샘플. 되붙이기가 리샘플 앞이어야 하는 건
        // 리샘플러가 버퍼를 넘어 상태를 잇기 때문이다 — 조각난 채로 넣으면 그 상태가 엉킨다.
        var converted: Data?
        if let dec = converter.decode(sampleBuffer) {
            let (l, r) = repair.process(dec.l, dec.r, rate: dec.rate)
            if !l.isEmpty { converted = converter.resample(l, r, rate: dec.rate) }
        }
        #if DEBUG
        Diag.shared.record(sampleBuffer, out: converted)
        Diag.shared.mode = "handle avg=\(String(format: "%.2f", handleN > 0 ? handleSumMs / Double(handleN) : 0))ms max=\(String(format: "%.2f", handleMaxMs))ms sparse=\(repair.sparse) placed=\(repair.placed) underruns=\(pacer.underruns) overflows=\(pacer.overflows)"
        #endif
        guard let pcm = converted else { return }

        // RMS/peak 는 변환된 Int16 기준으로 잰다. 안드로이드 로그와 같은 척도다.
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

        pacer.push(pcm)

        let now = Date()
        if now.timeIntervalSince(lastLog) >= 1.0 {
            let rms = sampleCount > 0 ? Int((sumSquares / Double(sampleCount)).squareRoot()) : 0
            os_log("AUDIO buf=%d rms=%d peak=%d clients=%d drop=%d sparse=%d fmt=%{public}@",
                   log: log, type: .info,
                   bufCount, rms, Int(peak), server.clientCount, server.droppedTotal,
                   repair.sparse ? 1 : 0, converter.describedFormat ?? "?")
            bufCount = 0; sumSquares = 0; sampleCount = 0; peak = 0
            lastLog = now
        }
    }
}
