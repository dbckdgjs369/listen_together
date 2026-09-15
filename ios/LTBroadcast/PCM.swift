import CoreMedia
import Foundation

/// ReplayKit 이 주는 CMSampleBuffer 를 우리 포맷으로 변환한다.
///
/// ReplayKit 의 audioApp 버퍼가 어떤 포맷으로 올지는 **문서에 보장이 없다.**
/// Float32 비인터리브로 오는 기기도, Int16 인터리브로 오는 기기도 있다고 알려져
/// 있어서 양쪽을 다 받는다. 샘플레이트도 44.1k/48k 가 섞여 나올 수 있어 필요하면
/// 리샘플한다. 첫 버퍼에서 실제 포맷을 로그로 찍으니, 가정이 틀리면 거기서 드러난다.
final class PCMConverter {

    /// 리샘플 상태. 버퍼 경계에서 튀지 않도록 직전 샘플과 소수 위치를 들고 간다.
    private var lastL: Float = 0
    private var lastR: Float = 0
    private var pos: Double = 0
    private var primed = false

    private(set) var describedFormat: String?

    func convert(_ sampleBuffer: CMSampleBuffer) -> Data? {
        guard let fd = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(fd) else { return nil }
        let asbd = asbdPtr.pointee

        var blockBuffer: CMBlockBuffer?
        var abl = AudioBufferList()
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: &abl,
            bufferListSize: MemoryLayout<AudioBufferList>.size,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer)
        guard status == noErr else { return nil }
        // blockBuffer 를 살려둬야 abl 안의 포인터가 유효하다.
        withExtendedLifetime(blockBuffer) { }

        let isFloat = asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let nonInterleaved = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        let srcChannels = Int(asbd.mChannelsPerFrame)
        let srcRate = asbd.mSampleRate > 0 ? asbd.mSampleRate : PCMFormat.rate

        if describedFormat == nil {
            describedFormat = String(
                format: "%.0fHz %dch %@ %@ bits=%d",
                srcRate, srcChannels,
                isFloat ? "float" : "int",
                nonInterleaved ? "planar" : "interleaved",
                asbd.mBitsPerChannel)
        }

        // ── 1) 채널별 Float 배열로 편다 ─────────────────────────
        var l: [Float] = []
        var r: [Float] = []
        let buffers = UnsafeMutableAudioBufferListPointer(&abl)

        if nonInterleaved {
            guard let b0 = buffers[0].mData else { return nil }
            l = floats(b0, bytes: Int(buffers[0].mDataByteSize), isFloat: isFloat,
                       bits: Int(asbd.mBitsPerChannel), stride: 1, offset: 0)
            if buffers.count > 1, let b1 = buffers[1].mData {
                r = floats(b1, bytes: Int(buffers[1].mDataByteSize), isFloat: isFloat,
                           bits: Int(asbd.mBitsPerChannel), stride: 1, offset: 0)
            } else {
                r = l                                   // 모노 → 양쪽 같은 소리
            }
        } else {
            guard let b0 = buffers[0].mData else { return nil }
            let ch = max(1, srcChannels)
            l = floats(b0, bytes: Int(buffers[0].mDataByteSize), isFloat: isFloat,
                       bits: Int(asbd.mBitsPerChannel), stride: ch, offset: 0)
            r = ch > 1
                ? floats(b0, bytes: Int(buffers[0].mDataByteSize), isFloat: isFloat,
                         bits: Int(asbd.mBitsPerChannel), stride: ch, offset: 1)
                : l
        }
        guard !l.isEmpty else { return nil }
        if r.count != l.count { r = l }

        // ── 2) 44100 으로 리샘플하며 Int16 으로 굽는다 ───────────
        if !primed { lastL = l[0]; lastR = r[0]; primed = true }

        let L = [lastL] + l
        let R = [lastR] + r
        let ratio = srcRate / PCMFormat.rate

        var out = [Int16]()
        out.reserveCapacity(Int(Double(l.count) / ratio) * 2 + 4)

        var p = pos
        let limit = Double(L.count - 1)
        while p < limit {
            let i = Int(p)
            let f = Float(p - Double(i))
            out.append(clamp(L[i] + (L[i + 1] - L[i]) * f))
            out.append(clamp(R[i] + (R[i + 1] - R[i]) * f))
            p += ratio
        }
        // 다음 버퍼의 0번은 이번 버퍼의 마지막 샘플이다. 그 기준으로 위치를 옮긴다.
        pos = p - limit
        lastL = l[l.count - 1]
        lastR = r[r.count - 1]

        return out.withUnsafeBufferPointer { Data(buffer: $0) }
    }

    /// 원시 바이트에서 stride 간격으로 한 채널을 뽑아 Float(-1..1) 로 만든다.
    private func floats(_ base: UnsafeMutableRawPointer, bytes: Int, isFloat: Bool,
                        bits: Int, stride: Int, offset: Int) -> [Float] {
        var out: [Float] = []
        if isFloat {
            let n = bytes / MemoryLayout<Float>.size
            let p = base.bindMemory(to: Float.self, capacity: n)
            out.reserveCapacity(n / stride)
            var i = offset
            while i < n { out.append(p[i]); i += stride }
        } else if bits == 16 {
            let n = bytes / MemoryLayout<Int16>.size
            let p = base.bindMemory(to: Int16.self, capacity: n)
            out.reserveCapacity(n / stride)
            var i = offset
            while i < n { out.append(Float(p[i]) / 32768.0); i += stride }
        } else if bits == 32 {
            let n = bytes / MemoryLayout<Int32>.size
            let p = base.bindMemory(to: Int32.self, capacity: n)
            out.reserveCapacity(n / stride)
            var i = offset
            while i < n { out.append(Float(p[i]) / 2147483648.0); i += stride }
        }
        return out
    }

    private func clamp(_ v: Float) -> Int16 {
        let s = v * 32767.0
        if s >= 32767 { return 32767 }
        if s <= -32768 { return -32768 }
        return Int16(s)
    }
}
