import CoreMedia
import Foundation

/// 개발용 진단. **Debug 빌드에서만** 서버가 `/diag` 로 내보낸다.
///
/// 확장의 os_log 는 맥에서 읽으려면 관리자 권한(log collect)이나 Console.app 이 필요하다.
/// 그래서 캡처가 실제로 무엇을 받고 있는지를 같은 Wi-Fi 의 맥이 curl 로 물어볼 수 있게 한다.
/// "소리가 이상하다" 를 귀가 아니라 숫자로 가르는 창구다 — 포맷·초당 프레임·RMS.
final class Diag {
    static let shared = Diag()
    private let lock = NSLock()

    // 1초 창. SampleHandler 가 버퍼마다 더하고, 1초마다 last 로 넘긴다.
    private var bufs = 0, frames = 0, outBytes = 0, nilConverts = 0
    private var sumSq: Double = 0, nSamp = 0, peak = 0, nonZeroBufs = 0, nonZeroFrames = 0
    private var ptsFirst: Double = .nan, ptsLast: Double = .nan
    private var windowStart = Date()
    private var last: [String: Any] = [:]
    private var total = 0
    private var asbdText = "?"
    private var sample: [String: Any] = [:]
    /// 최근 버퍼 24개의 (pts, 프레임 수, 앞 64바이트 해시). 같은 소리가 되풀이되는지 본다.
    private var recent: [String] = []
    /// 연속 버퍼 8개의 왼쪽 채널 원본. 버퍼끼리 겹치는지(슬라이딩 창인지) 맞춰 본다.
    private var raws: [[Int]] = []
    /// 되붙이기·송출 상태 한 줄. SampleHandler 가 채운다.
    var mode = ""
    /// 원본 버퍼를 통째로 담는 무거운 기록. /diag?rec=1 로 켜고 rec=0 으로 끈다.
    var recording = false
    /// 최근 버퍼 원본 700개(약 2초). /diagraw 로 통째로 내려받아 맥에서 분석한다.
    private var ring: [Data] = []

    func record(_ sb: CMSampleBuffer, out: Data?) {
        lock.lock(); defer { lock.unlock() }
        bufs += 1; total += 1
        frames += CMSampleBufferGetNumSamples(sb)
        if let out {
            outBytes += out.count
            var any = false
            out.withUnsafeBytes { raw in
                let p = raw.bindMemory(to: Int16.self)
                for i in stride(from: 0, to: p.count, by: 4) {
                    let v = p[i]
                    if v != 0 { any = true }
                    let d = Double(v); sumSq += d * d; nSamp += 1
                    peak = max(peak, abs(Int(v)))
                }
            }
            if any { nonZeroBufs += 1; nonZeroFrames += CMSampleBufferGetNumSamples(sb) }
        } else { nilConverts += 1 }
        let pts = CMSampleBufferGetPresentationTimeStamp(sb).seconds
        // 0 이 아닌 프레임 구간(첫·끝). 버퍼 안 어디에 진짜 소리가 있는지 본다.
        var first = -1, lastNZ = -1
        if recording, let bb = CMSampleBufferGetDataBuffer(sb) {
            let len = CMBlockBufferGetDataLength(bb)
            var tmp = [UInt8](repeating: 0, count: len)
            if CMBlockBufferCopyDataBytes(bb, atOffset: 0, dataLength: len, destination: &tmp) == noErr {
                var f = 0, i = 0
                var left: [Int] = []
                var j = 0
                while j + 1 < len { left.append(Int(Int16(bitPattern: UInt16(tmp[j]) << 8 | UInt16(tmp[j + 1])))); j += 4 }
                raws.append(left); if raws.count > 8 { raws.removeFirst() }
                // 머리: pts(Double) + 프레임 수(Int32) + 바이트 수(Int32) + 도착 시각(Double)
                var rec = Data()
                var p = pts, nf = Int32(CMSampleBufferGetNumSamples(sb)), nb = Int32(len)
                var at = Date().timeIntervalSince1970
                withUnsafeBytes(of: &p) { rec.append(contentsOf: $0) }
                withUnsafeBytes(of: &nf) { rec.append(contentsOf: $0) }
                withUnsafeBytes(of: &nb) { rec.append(contentsOf: $0) }
                withUnsafeBytes(of: &at) { rec.append(contentsOf: $0) }
                rec.append(contentsOf: tmp)
                ring.append(rec); if ring.count > 700 { ring.removeFirst() }
                while i + 3 < len {
                    if tmp[i] | tmp[i + 1] | tmp[i + 2] | tmp[i + 3] != 0 { if first < 0 { first = f }; lastNZ = f }
                    i += 4; f += 1
                }
            }
        }
        if recording { recent.append(String(format: "%.4f n=%d nz=%d-%d t=%.4f", pts, CMSampleBufferGetNumSamples(sb), first, lastNZ,
                             Date().timeIntervalSince1970.truncatingRemainder(dividingBy: 100)))
        }
        if recent.count > 24 { recent.removeFirst() }
        if ptsFirst.isNaN { ptsFirst = pts }
        ptsLast = pts
        if asbdText == "?", let fd = CMSampleBufferGetFormatDescription(sb),
           let a = CMAudioFormatDescriptionGetStreamBasicDescription(fd)?.pointee {
            asbdText = String(format: "rate=%.0f fmt=%@ flags=0x%x bytesPerPacket=%d framesPerPacket=%d bytesPerFrame=%d ch=%d bits=%d",
                              a.mSampleRate, fourCC(a.mFormatID), a.mFormatFlags, a.mBytesPerPacket,
                              a.mFramesPerPacket, a.mBytesPerFrame, a.mChannelsPerFrame, a.mBitsPerChannel)
        }
        if recording && (sample.isEmpty || total % 50 == 0) {
            var dump: [Int] = []
            if let bb = CMSampleBufferGetDataBuffer(sb) {
                let len = CMBlockBufferGetDataLength(bb)
                var tmp = [UInt8](repeating: 0, count: len)
                if CMBlockBufferCopyDataBytes(bb, atOffset: 0, dataLength: len, destination: &tmp) == noErr {
                    // 원본 그대로(빅엔디안 Int16 가정) 왼쪽 채널만
                    var i = 0
                    while i + 1 < len { dump.append(Int(Int16(bitPattern: UInt16(tmp[i]) << 8 | UInt16(tmp[i + 1])))); i += 4 }
                }
            }
            var listBytes: [Int] = []
            if let bb = CMSampleBufferGetDataBuffer(sb) { listBytes.append(CMBlockBufferGetDataLength(bb)) }
            sample = ["numSamples": CMSampleBufferGetNumSamples(sb),
                      "dataBufferBytes": listBytes,
                      "duration": CMSampleBufferGetDuration(sb).seconds,
                      "outBytes": out?.count ?? -1, "dumpL": dump]
        }
        let now = Date()
        let dt = now.timeIntervalSince(windowStart)
        if dt >= 1 {
            last = ["window": dt, "bufs": bufs, "framesIn": frames, "outBytes": outBytes,
                    "outSecPerSec": Double(outBytes) / 192_000 / dt,
                    "ptsSpan": ptsLast - ptsFirst, "nonZeroBufs": nonZeroBufs, "nonZeroFrames": nonZeroFrames, "nilConverts": nilConverts,
                    "rms": nSamp > 0 ? Int((sumSq / Double(nSamp)).squareRoot()) : 0, "peak": peak]
            bufs = 0; frames = 0; outBytes = 0; nilConverts = 0
            sumSq = 0; nSamp = 0; peak = 0; nonZeroBufs = 0; nonZeroFrames = 0
            ptsFirst = .nan; ptsLast = .nan
            windowStart = now
        }
    }

    func json(extra: [String: Any]) -> Data {
        lock.lock()
        var body: [String: Any] = ["asbd": asbdText, "lastSecond": last, "sampleBuffer": sample,
                                   "totalBufs": total, "mode": mode, "recent": recent, "raws": raws]
        lock.unlock()
        body.merge(extra) { a, _ in a }
        return (try? JSONSerialization.data(withJSONObject: body, options: [.prettyPrinted, .sortedKeys]))
            ?? Data("{}".utf8)
    }

    func rawDump() -> Data {
        lock.lock(); defer { lock.unlock() }
        return ring.reduce(into: Data()) { $0.append($1) }
    }

    private func fourCC(_ v: UInt32) -> String {
        let b = [24, 16, 8, 0].map { UInt8((v >> UInt32($0)) & 0xFF) }
        return String(bytes: b, encoding: .ascii) ?? "\(v)"
    }
}
