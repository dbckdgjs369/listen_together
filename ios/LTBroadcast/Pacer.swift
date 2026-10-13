import Foundation

/// 실제 시간에 맞춰 내보낸다. 들어오는 쪽이 어떻든 나가는 쪽은 **정확히 초당 48000프레임**이다.
///
/// ReplayKit 은 소리를 고르게 주지 않는다 — 평소에도 23ms 버퍼를 4개씩 몰아서 주고 80ms 쉬며,
/// 조각 모드(SparseRepair)에선 빠진 조각만큼 모자라고, 소리가 아예 없으면 아무것도 안 줄 때도 있다.
/// 그걸 그대로 흘리면 청취자 쪽 버퍼가 비었다 넘쳤다 하고, 오래 비면 '멈춤'으로 보고 재접속한다.
///
/// 그래서 100ms 를 모아 두고 10ms 마다 시계로 정확한 양만 꺼낸다.
///   - 모자라면 무음으로 채우고 다시 100ms 를 모은다(그동안도 무음을 보낸다 — 연결은 살아 있다).
///   - 300ms 넘게 쌓이면 오래된 것을 버리고 100ms 로 줄인다(지연이 늘어나지 않게).
final class Pacer {
    private let lock = NSLock()
    private var fifo: [UInt8] = []
    private var running = false
    private var buffering = true
    private let out: (Data) -> Void

    private static let bytesPerFrame = PCMFormat.channels * PCMFormat.bits / 8
    private static let rate = Int(PCMFormat.rate)
    private static let target = rate / 10 * bytesPerFrame            // 100ms
    private static let ceiling = rate * 3 / 10 * bytesPerFrame       // 300ms

    private(set) var underruns = 0
    private(set) var overflows = 0

    init(out: @escaping (Data) -> Void) { self.out = out }

    func push(_ d: Data) {
        lock.lock()
        fifo.append(contentsOf: d)
        if fifo.count > Self.ceiling {
            let drop = (fifo.count - Self.target) / Self.bytesPerFrame * Self.bytesPerFrame
            fifo.removeFirst(drop)
            overflows += 1
        }
        lock.unlock()
    }

    func start() {
        lock.lock(); running = true; lock.unlock()
        Thread.detachNewThread { [weak self] in self?.loop() }
    }

    func stop() { lock.lock(); running = false; lock.unlock() }

    private func loop() {
        let t0 = CFAbsoluteTimeGetCurrent()
        var sent = 0
        while true {
            usleep(10_000)
            let now = CFAbsoluteTimeGetCurrent()
            var due = Int((now - t0) * Double(Self.rate)) - sent
            if due <= 0 { continue }
            // 스레드가 한참 멈췄다 깨어나도 한 번에 0.2초 넘게 몰아 보내지 않는다.
            if due > Self.rate / 5 { sent += due - Self.rate / 5; due = Self.rate / 5 }

            let want = due * Self.bytesPerFrame
            var chunk = [UInt8]()
            lock.lock()
            guard running else { lock.unlock(); return }
            if buffering && fifo.count >= Self.target { buffering = false }
            if !buffering {
                let take = min(want, fifo.count / Self.bytesPerFrame * Self.bytesPerFrame)
                chunk.append(contentsOf: fifo[0..<take])
                fifo.removeFirst(take)
                if take < want { buffering = true; underruns += 1 }
            }
            lock.unlock()
            if chunk.count < want { chunk += repeatElement(0, count: want - chunk.count) }
            sent += due
            out(Data(chunk))
        }
    }
}
