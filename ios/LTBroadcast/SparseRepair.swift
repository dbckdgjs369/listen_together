import Foundation

/// iOS 가 앱 소리를 **조각으로 흩뿌려** 줄 때 이어 붙인다.
///
/// 평소 ReplayKit 의 audioApp 버퍼는 1024프레임(44.1k 에서 23ms)이 실제 시간에 맞춰 온다.
/// 그런데 어떤 앱이 오디오 입출력 주기를 짧게 잡으면(사파리 Web Audio, 게임 등 — 이 주기는
/// 기기 전체가 같이 쓴다) 모양이 완전히 바뀐다. 실측(아이폰 14, iOS 27):
///   - 버퍼가 1.3ms 마다 온다. 1초에 770개 = 실제의 17배 분량. 시간표(pts)도 그만큼 빠르다.
///   - 버퍼 대부분은 0 이고, 그 안 어딘가에 **실제 소리 한 조각**(약 118프레임 = 48k 기준
///     128프레임, 입출력 한 주기)이 앉아 있다. 조각 앞뒤엔 리샘플러 꼬리(10~20프레임)가 붙는다.
///   - 조각끼리는 원음에서 **빈틈없이 이어진다** — 위치만 엉망이다.
/// 그대로 내보내면 17배 길이의 띄엄띄엄한 소리가 된다. 듣는 쪽엔 무음과 지글거림만 남는다.
///
/// 되붙이기: 0 사이에 뜬 '섬'(조각)을 하나씩 떼어, 직전 조각 뒤 **이음매가 가장 매끄러운
/// 자리**(2차 차분 에너지 최소, 1/4 샘플 단위)에 겹쳐 더한다. 꼬리는 겹쳐 더하면 서로 메워진다.
/// 1kHz 순음으로 잰 결과 SNR 34dB(정수 자리만 쓰면 28dB, 고정 간격이면 5~14dB).
///
/// 평소 모드에선 그대로 넘긴다(50ms 묶음으로 판정하느라 그만큼만 늦는다). 묶음 하나가
/// 실제의 2.5배를 넘으면 켜고, 1.6배 아래가 0.5초 이어지면 끈다.
final class SparseRepair {

    private(set) var sparse = false
    private(set) var placed = 0

    // ── 섬 모으기. 버퍼 경계에 걸친 섬도 있어 버퍼를 넘어 이어 모은다.
    private var iL: [Float] = [], iR: [Float] = []
    private var zeroRun = 0
    /// 0 이 이만큼 이어지면 섬이 끝났다고 본다. 조각 사이 간격은 수백 프레임이라 넉넉하다.
    private static let gap = 24
    private static let maxIsland = 4096

    // ── 겹쳐 더하는 작업대. acc*[0] 의 절대 위치가 accBase.
    private var accL: [Float] = [], accR: [Float] = [], accM: [Float] = []
    private var accBase = 0
    private var lastPlace = -1
    private var prevEnd = 0
    private var lastIslandAt = 0.0

    private static let fracs: [Float] = [0, 0.25, 0.5, 0.75]

    // ── 50ms 묶음. 판정이 날 때까지 붙잡아 둔다 — 판정 전에 흘린 조각은 그대로 소음이 된다.
    private var holdL: [Float] = [], holdR: [Float] = []
    private var holdStart = 0.0
    private var calmBlocks = 0
    private static let block = 0.05

    func process(_ l: [Float], _ r: [Float], rate: Double) -> ([Float], [Float]) {
        let now = CFAbsoluteTimeGetCurrent()
        if holdL.isEmpty { holdStart = now }
        holdL += l; holdR += r
        var oL: [Float] = [], oR: [Float] = []
        let el = now - holdStart
        guard el >= Self.block else { return (oL, oR) }

        // 이 묶음이 실제 시간의 몇 배 분량인가. 평소엔 1 안팎(몰려 와도 2 를 잘 안 넘는다), 조각 모드는 8~17.
        let ratio = Double(holdL.count) / (rate * el)
        if ratio > 2.5 { sparse = true; calmBlocks = 0 }
        else if sparse && ratio < 1.6 { calmBlocks += 1; if calmBlocks >= 10 { sparse = false; calmBlocks = 0 } }

        let bl = holdL, br = holdR
        holdL.removeAll(keepingCapacity: true); holdR.removeAll(keepingCapacity: true)

        if !sparse {
            if lastPlace >= 0 || !iL.isEmpty { flush(&oL, &oR, now: now) }
            oL += bl; oR += br
            return (oL, oR)
        }
        // 50ms 넘게 조각이 없었다 = 진짜 무음. 작업대를 비우고 다음 조각은 새로 시작한다.
        if lastPlace >= 0 && now - lastIslandAt > 0.1 { flush(&oL, &oR, now: now) }

        for i in 0..<bl.count {
            if bl[i] != 0 || br[i] != 0 {
                zeroRun = 0
                iL.append(bl[i]); iR.append(br[i])
            } else if !iL.isEmpty {
                zeroRun += 1
                iL.append(0); iR.append(0)
                if zeroRun >= Self.gap {
                    iL.removeLast(zeroRun); iR.removeLast(zeroRun)
                    place(now: now, &oL, &oR)
                }
            }
            if iL.count > Self.maxIsland { place(now: now, &oL, &oR) }
        }
        return (oL, oR)
    }

    // MARK: - 놓기

    private func place(now: Double, _ oL: inout [Float], _ oR: inout [Float]) {
        let sl = iL, sr = iR
        iL.removeAll(keepingCapacity: true); iR.removeAll(keepingCapacity: true); zeroRun = 0
        lastIslandAt = now
        let n = sl.count
        guard n >= 4 else { return }
        placed += 1

        if lastPlace < 0 {
            put(sl, sr, at: accBase)
            lastPlace = accBase; prevEnd = accBase + n
            return
        }

        let mono = zip(sl, sr).map { ($0 + $1) * 0.5 }
        let shifted = Self.fracs.map { Self.delay(mono, by: $0) }
        let lo = lastPlace + Int(Double(n) * 0.72)
        let hi = max(lo, lastPlace + Int(Double(n) * 0.95))
        var bestCost = Float.greatestFiniteMagnitude, bestQ = lo, bestF = 0
        for (fi, sm) in shifted.enumerated() {
            for q in lo...hi {
                let c = joinCost(sm, at: q, limit: bestCost)
                if c < bestCost { bestCost = c; bestQ = q; bestF = fi }
            }
        }
        let f = Self.fracs[bestF]
        put(Self.delay(sl, by: f), Self.delay(sr, by: f), at: bestQ)
        lastPlace = bestQ
        prevEnd = max(prevEnd, bestQ + n + 1)
        // 다음 조각은 bestQ + 0.72n 뒤에 놓이므로 bestQ 앞은 이제 확정이다.
        emit(upTo: bestQ, &oL, &oR)
    }

    /// 조각 sm 을 q 에 더했을 때 이음매 부근의 2차 차분 제곱합. 작을수록 매끄럽다.
    private func joinCost(_ sm: [Float], at q: Int, limit: Float) -> Float {
        let start = q - 2
        let end = max(prevEnd, q) + 16
        @inline(__always) func v(_ t: Int) -> Float {
            var x: Float = 0
            let ai = t - accBase
            if ai >= 0 && ai < accM.count { x += accM[ai] }
            let si = t - q
            if si >= 0 && si < sm.count { x += sm[si] }
            return x
        }
        var a0 = v(start), a1 = v(start + 1), c: Float = 0
        var t = start + 2
        while t < end {
            let a2 = v(t)
            let d = a2 - 2 * a1 + a0
            c += d * d
            if c >= limit { return c }
            a0 = a1; a1 = a2; t += 1
        }
        return c
    }

    private func put(_ l: [Float], _ r: [Float], at q: Int) {
        let off = q - accBase
        guard off >= 0 else { return }
        let need = off + l.count
        if accL.count < need {
            let extra = need - accL.count
            accL += repeatElement(0, count: extra)
            accR += repeatElement(0, count: extra)
            accM += repeatElement(0, count: extra)
        }
        for i in 0..<l.count {
            accL[off + i] += l[i]
            accR[off + i] += r[i]
            accM[off + i] += (l[i] + r[i]) * 0.5
        }
    }

    private func emit(upTo t: Int, _ oL: inout [Float], _ oR: inout [Float]) {
        let k = min(t - accBase, accL.count)
        guard k > 0 else { return }
        oL += accL[0..<k]; oR += accR[0..<k]
        accL.removeFirst(k); accR.removeFirst(k); accM.removeFirst(k)
        accBase += k
    }

    private func flush(_ oL: inout [Float], _ oR: inout [Float], now: Double) {
        if !iL.isEmpty { place(now: now, &oL, &oR) }
        emit(upTo: accBase + accL.count, &oL, &oR)
        lastPlace = -1
        prevEnd = accBase
    }

    /// 분수 지연(0 ≤ d < 1). 4점 라그랑주 보간. 결과는 한 칸 길다.
    private static func delay(_ x: [Float], by d: Float) -> [Float] {
        if d == 0 { return x + [0] }
        let n = x.count
        // y[i] = x(i - d). 기준점 base = i-1, 그 안의 위치 t = 1-d.
        let t = 1 - d
        let w0 = -t * (t - 1) * (t - 2) / 6
        let w1 = (t + 1) * (t - 1) * (t - 2) / 2
        let w2 = -(t + 1) * t * (t - 2) / 2
        let w3 = (t + 1) * t * (t - 1) / 6
        @inline(__always) func s(_ j: Int) -> Float { j >= 0 && j < n ? x[j] : 0 }
        var y = [Float](repeating: 0, count: n + 1)
        for i in 0...n {
            let b = i - 1
            y[i] = w0 * s(b - 1) + w1 * s(b) + w2 * s(b + 1) + w3 * s(b + 2)
        }
        return y
    }
}
