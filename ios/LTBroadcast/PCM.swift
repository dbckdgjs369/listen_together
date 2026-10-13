import CoreMedia
import Foundation

/// ReplayKit 이 주는 CMSampleBuffer 를 우리 포맷(48000/16bit/스테레오 리틀엔디안)으로 변환한다.
///
/// ReplayKit 의 audioApp 버퍼가 어떤 포맷으로 올지는 **문서에 보장이 없다.**
/// Float32 비인터리브, Int16 인터리브, 심지어 **빅엔디안 Int16** 으로 오는 경우도
/// 알려져 있어서 전부 받는다. 샘플레이트도 44.1k/48k 가 섞여 나올 수 있어 필요하면
/// 리샘플한다. 첫 버퍼에서 실제 포맷을 로그로 찍으니, 가정이 틀리면 거기서 드러난다.
final class PCMConverter {

    /// 리샘플 상태. 버퍼 경계에서 튀지 않도록 직전 샘플과 소수 위치를 들고 간다.
    private var lastL: Float = 0
    private var lastR: Float = 0
    private var pos: Double = 0
    private var primed = false

    private(set) var describedFormat: String?

    /// 디코딩 결과. 원본 레이트 그대로의 채널별 Float(-1..1).
    struct Decoded { var l: [Float]; var r: [Float]; var rate: Double }

    /// ── 1) CMSampleBuffer → 채널별 Float. 리샘플은 하지 않는다 — 그 전에 SparseRepair 가 끼어든다.
    func decode(_ sampleBuffer: CMSampleBuffer) -> Decoded? {
        guard let fd = CMSampleBufferGetFormatDescription(sampleBuffer),
              let asbdPtr = CMAudioFormatDescriptionGetStreamBasicDescription(fd) else { return nil }
        let asbd = asbdPtr.pointee

        // 비인터리브 스테레오면 버퍼가 2개라 AudioBufferList 하나 크기로는 모자란다
        // (-12737). 필요한 크기를 먼저 물어서 그만큼 잡는다.
        var needed = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer, bufferListSizeNeededOut: &needed, bufferListOut: nil, bufferListSize: 0,
            blockBufferAllocator: nil, blockBufferMemoryAllocator: nil, flags: 0, blockBufferOut: nil)
        guard needed > 0 else { return nil }
        let ablRaw = UnsafeMutableRawPointer.allocate(byteCount: needed,
                                                      alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { ablRaw.deallocate() }
        let ablPtr = ablRaw.bindMemory(to: AudioBufferList.self, capacity: 1)

        var blockBuffer: CMBlockBuffer?
        let status = CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer,
            bufferListSizeNeededOut: nil,
            bufferListOut: ablPtr,
            bufferListSize: needed,
            blockBufferAllocator: nil,
            blockBufferMemoryAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment,
            blockBufferOut: &blockBuffer)
        guard status == noErr else { return nil }

        // blockBuffer 가 살아 있어야 ablPtr 안의 포인터가 유효하다.
        return withExtendedLifetime(blockBuffer) { () -> Decoded? in
            let isFloat = asbd.mFormatFlags & kAudioFormatFlagIsFloat != 0
            let nonInterleaved = asbd.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
            let bigEndian = asbd.mFormatFlags & kAudioFormatFlagIsBigEndian != 0
            let srcChannels = Int(asbd.mChannelsPerFrame)
            let srcRate = asbd.mSampleRate > 0 ? asbd.mSampleRate : PCMFormat.rate
            let bits = Int(asbd.mBitsPerChannel)

            if describedFormat == nil {
                describedFormat = String(
                    format: "%.0fHz %dch %@ %@%@ bits=%d",
                    srcRate, srcChannels,
                    isFloat ? "float" : "int",
                    nonInterleaved ? "planar" : "interleaved",
                    bigEndian ? " BE" : "",
                    bits)
            }

            // ── 1) 채널별 Float 배열로 편다 ─────────────────────────
            var l: [Float] = []
            var r: [Float] = []
            let buffers = UnsafeMutableAudioBufferListPointer(ablPtr)
            guard buffers.count > 0, let b0 = buffers[0].mData else { return nil }

            if nonInterleaved {
                l = floats(b0, bytes: Int(buffers[0].mDataByteSize), isFloat: isFloat,
                           bits: bits, bigEndian: bigEndian, stride: 1, offset: 0)
                if buffers.count > 1, let b1 = buffers[1].mData {
                    r = floats(b1, bytes: Int(buffers[1].mDataByteSize), isFloat: isFloat,
                               bits: bits, bigEndian: bigEndian, stride: 1, offset: 0)
                } else {
                    r = l                                   // 모노 → 양쪽 같은 소리
                }
            } else {
                let ch = max(1, srcChannels)
                l = floats(b0, bytes: Int(buffers[0].mDataByteSize), isFloat: isFloat,
                           bits: bits, bigEndian: bigEndian, stride: ch, offset: 0)
                r = ch > 1
                    ? floats(b0, bytes: Int(buffers[0].mDataByteSize), isFloat: isFloat,
                             bits: bits, bigEndian: bigEndian, stride: ch, offset: 1)
                    : l
            }
            guard !l.isEmpty else { return nil }
            if r.count != l.count { r = l }
            return Decoded(l: l, r: r, rate: srcRate)
        }
    }

    /// ── 2) 레이트가 같으면 그대로, 다르면 선형 보간으로 리샘플하며 Int16 으로 굽는다.
    /// 버퍼 경계를 넘어 리샘플 상태를 이어 가므로 **이어진 소리**만 넣어야 한다(SparseRepair 뒤).
    func resample(_ l: [Float], _ r: [Float], rate srcRate: Double) -> Data? {
        guard !l.isEmpty, r.count == l.count else { return nil }
        var out = [Int16]()
        if srcRate == PCMFormat.rate {
            out.reserveCapacity(l.count * 2)
            for i in 0..<l.count { out.append(clamp(l[i])); out.append(clamp(r[i])) }
            return out.withUnsafeBufferPointer { Data(buffer: $0) }
        }

        if !primed { lastL = l[0]; lastR = r[0]; primed = true }
        let L = [lastL] + l
        let R = [lastR] + r
        let ratio = srcRate / PCMFormat.rate
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
                        bits: Int, bigEndian: Bool, stride: Int, offset: Int) -> [Float] {
        var out: [Float] = []
        if isFloat {
            let n = bytes / MemoryLayout<Float>.size
            let p = base.bindMemory(to: UInt32.self, capacity: n)
            out.reserveCapacity(n / stride)
            var i = offset
            while i < n {
                out.append(Float(bitPattern: bigEndian ? UInt32(bigEndian: p[i]) : p[i]))
                i += stride
            }
        } else if bits == 16 {
            let n = bytes / MemoryLayout<Int16>.size
            let p = base.bindMemory(to: Int16.self, capacity: n)
            out.reserveCapacity(n / stride)
            var i = offset
            while i < n {
                out.append(Float(bigEndian ? Int16(bigEndian: p[i]) : p[i]) / 32768.0)
                i += stride
            }
        } else if bits == 32 {
            let n = bytes / MemoryLayout<Int32>.size
            let p = base.bindMemory(to: Int32.self, capacity: n)
            out.reserveCapacity(n / stride)
            var i = offset
            while i < n {
                out.append(Float(bigEndian ? Int32(bigEndian: p[i]) : p[i]) / 2147483648.0)
                i += stride
            }
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
