import AVFoundation
import Foundation
import MediaPlayer
import Network
import os

/// 받은 PCM 을 모아 두는 고리 버퍼. 네트워크 스레드가 넣고 오디오 스레드가 뺀다.
///
/// 오디오 스레드에서 잠금을 쓰는 건 교과서적으로는 금기지만, 임계구역이 memcpy 몇 번이라
/// 실측상 문제가 없다(안드로이드 PlayerService 도 같은 구조로 0.11초 지연을 냈다).
final class PCMRing {
    private let capacity: Int
    private var l: UnsafeMutablePointer<Float>
    private var r: UnsafeMutablePointer<Float>
    private var readPos = 0
    private var count = 0
    private var lock = os_unfair_lock()

    /// 버퍼가 이만큼 차야 소리를 낸다. 너무 작으면 와이파이 출렁임에 끊기고,
    /// 크면 다른 사람과 어긋난다. 안드로이드 앱 청취(≈0.1초)와 비슷하게 맞춘다.
    let target: Int
    /// 이보다 쌓이면(네트워크가 한꺼번에 몰아 준 경우) target 까지 버려서 따라잡는다.
    let ceiling: Int
    private var primed = false
    private(set) var underruns = 0

    init(rate: Double) {
        capacity = Int(rate * 4)
        target = Int(rate * 0.15)
        ceiling = Int(rate * 0.6)
        l = .allocate(capacity: capacity)
        r = .allocate(capacity: capacity)
    }

    deinit { l.deallocate(); r.deallocate() }

    var buffered: Int {
        os_unfair_lock_lock(&lock); defer { os_unfair_lock_unlock(&lock) }
        return count
    }

    func reset() {
        os_unfair_lock_lock(&lock)
        readPos = 0; count = 0; primed = false
        os_unfair_lock_unlock(&lock)
    }

    /// 16bit 인터리브 PCM 을 넣는다.
    func write(_ data: Data, channels: Int) {
        let frames = data.count / (2 * channels)
        guard frames > 0 else { return }
        data.withUnsafeBytes { raw in
            let s = raw.bindMemory(to: Int16.self)
            os_unfair_lock_lock(&lock)
            for i in 0..<frames {
                if count == capacity { readPos = (readPos + 1) % capacity; count -= 1 }
                let w = (readPos + count) % capacity
                let a = Float(Int16(littleEndian: s[i * channels])) / 32768
                l[w] = a
                r[w] = channels > 1 ? Float(Int16(littleEndian: s[i * channels + 1])) / 32768 : a
                count += 1
            }
            if count > ceiling {
                let drop = count - target
                readPos = (readPos + drop) % capacity
                count -= drop
            }
            os_unfair_lock_unlock(&lock)
        }
    }

    /// 오디오 스레드. 모자라면 무음을 채우고 다시 target 까지 모일 때까지 기다린다.
    func read(into outL: UnsafeMutablePointer<Float>, _ outR: UnsafeMutablePointer<Float>, frames: Int) {
        os_unfair_lock_lock(&lock)
        if !primed && count >= target { primed = true }
        var n = 0
        if primed {
            n = min(frames, count)
            for i in 0..<n {
                let p = (readPos + i) % capacity
                outL[i] = l[p]; outR[i] = r[p]
            }
            readPos = (readPos + n) % capacity
            count -= n
            if n < frames { primed = false; underruns += 1 }
        }
        os_unfair_lock_unlock(&lock)
        for i in n..<frames { outL[i] = 0; outR[i] = 0 }
    }
}

/// /stream 연결 하나. 응답 헤더 → WAV 헤더 → PCM 순으로 읽어 버퍼에 붓는다.
/// 전부 네트워크 큐에서 돌고, 바깥에는 콜백 셋으로만 알린다.
final class StreamConn {
    var onStart: ((Double, Int) -> Void)?
    var onRefused: ((Int) -> Void)?
    var onDropped: (() -> Void)?
    /// onStart 안에서 채워진다. 그 뒤로 받은 소리는 여기로 간다.
    var ring: PCMRing?

    private let conn: NWConnection
    private let host: String
    private let path: String
    private let queue: DispatchQueue
    private var header = Data()
    private var gotHeaders = false
    private var wav = Data()
    private var channels = 2
    private var finished = false

    init(host: String, path: String, queue: DispatchQueue) {
        self.host = host
        self.path = path
        self.queue = queue
        conn = NWConnection(host: NWEndpoint.Host(host),
                            port: NWEndpoint.Port(rawValue: LTPort.stream)!, using: .tcp)
    }

    func start() {
        conn.stateUpdateHandler = { [weak self] st in
            guard let self else { return }
            switch st {
            case .ready:
                let req = "GET \(self.path) HTTP/1.1\r\nHost: \(self.host)\r\nConnection: close\r\n\r\n"
                self.conn.send(content: Data(req.utf8), completion: .contentProcessed { _ in })
                self.pump()
            case .failed, .waiting:
                self.finish { self.onDropped?() }
            default: break
            }
        }
        conn.start(queue: queue)
        // 연결 자체가 안 되는 주소(다른 망)는 .waiting 도 안 오고 한참 매달린다.
        queue.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self, !self.gotHeaders else { return }
            self.finish { self.onDropped?() }
        }
    }

    func cancel() {
        queue.async { [weak self] in self?.finish {} }
    }

    private func finish(_ then: () -> Void) {
        guard !finished else { return }
        finished = true
        conn.cancel()
        then()
    }

    private func pump() {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 32768) { [weak self] data, _, done, err in
            guard let self, !self.finished else { return }
            if var d = data, !d.isEmpty {
                if !self.gotHeaders {
                    self.header.append(d)
                    guard let end = self.header.range(of: Data("\r\n\r\n".utf8)) else {
                        if self.header.count > 8192 { self.finish { self.onDropped?() }; return }
                        self.pump(); return
                    }
                    let head = String(decoding: self.header[..<end.lowerBound], as: UTF8.self)
                    let code = Int(head.split(separator: " ", maxSplits: 2).dropFirst().first ?? "") ?? 0
                    d = Data(self.header[end.upperBound...])
                    self.header = Data()
                    self.gotHeaders = true
                    if code != 200 && code != 206 {
                        self.finish { self.onRefused?(code) }
                        return
                    }
                }
                if self.wav.count < 44 {
                    let take = min(44 - self.wav.count, d.count)
                    self.wav.append(d.prefix(take))
                    d = Data(d.dropFirst(take))
                    if self.wav.count == 44 {
                        let (rate, ch) = Self.parseWav(self.wav)
                        self.channels = ch
                        self.onStart?(rate, ch)
                    }
                }
                if !d.isEmpty, let ring = self.ring { ring.write(d, channels: self.channels) }
            }
            if done || err != nil {
                self.finish { self.onDropped?() }
                return
            }
            self.pump()
        }
    }

    /// 안드로이드는 48000, 예전 호스트는 44100 이다. 박아 두지 않고 헤더에서 읽는다.
    static func parseWav(_ d: Data) -> (Double, Int) {
        guard d.count >= 36, d.prefix(4) == Data("RIFF".utf8) else { return (PCMFormat.rate, 2) }
        let ch = Int(d[22]) | Int(d[23]) << 8
        let rate = UInt32(d[24]) | UInt32(d[25]) << 8 | UInt32(d[26]) << 16 | UInt32(d[27]) << 24
        return (rate > 0 ? Double(rate) : PCMFormat.rate, max(1, ch))
    }
}

/// 호스트의 /stream 을 받아 재생한다. 안드로이드 PlayerService 의 아이폰판.
///
/// 브라우저 <audio> 로 들으면 사파리가 2~5초를 버퍼링한다. 여기선 소켓을 직접 읽어
/// 0.15초만 모은다 — 같이 듣는 사람들과 덜 어긋난다. 그게 앱을 깔 이유다.
///
/// 끊겼을 때의 판단은 웹 페이지와 같다.
///   - 호스트가 410 → 공유가 끝났다. 더 시도하지 않는다
///   - 401 / 429 → 비밀번호 문제
///   - 기기가 오프라인 → 기다렸다가 돌아오면 즉시 다시 붙는다 (실패로 세지 않는다)
///   - 30초 내내 안 닿음 → "연결할 수 없어요", 그 뒤엔 15초마다 조용히 다시 시도
@MainActor
final class Player: ObservableObject {

    enum State: Equatable {
        case idle
        case connecting(tries: Int)
        case playing
        case wrongPassword
        case tooMany
        case ended
        case offline
        case lost
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var roomName = ""
    @Published private(set) var host = ""
    /// 지금 쌓여 있는 소리(초). 화면 아래 작은 글씨로 보여준다.
    @Published private(set) var bufferedMs = 0

    private(set) var pw = ""
    private var want = false
    private var conn: StreamConn?
    private var connGen = 0
    private var failSince: Date?
    private var tries = 0
    private var retryWork: DispatchWorkItem?
    private let netQ = DispatchQueue(label: "lt.player.net")

    private let engine = AVAudioEngine()
    private var source: AVAudioSourceNode?
    private var ring = PCMRing(rate: PCMFormat.rate)
    private var streamRate = 0.0
    private var streamChannels = 2

    private let pathMonitor = NWPathMonitor()
    private var online = true
    private var meter: Timer?

    private static let lostAfter: TimeInterval = 30
    private static let lostRetry: TimeInterval = 15

    init() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.pathChanged(path.status == .satisfied) }
        }
        pathMonitor.start(queue: DispatchQueue(label: "lt.path"))
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification,
                                               object: nil, queue: .main) { [weak self] n in
            Task { @MainActor in self?.interrupted(n) }
        }
        setupRemoteCommands()
    }

    // MARK: - 바깥에서 부르는 것

    func join(host: String, pw: String, name: String) {
        leave()
        self.host = host
        self.pw = pw
        roomName = name
        want = true
        tries = 0
        failSince = nil
        startAudio()
        connect()
        meter = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.bufferedMs = self.streamRate > 0 ? Int(Double(self.ring.buffered) * 1000 / self.streamRate) : 0
            }
        }
        updateNowPlaying()
    }

    /// 비밀번호를 다시 넣고 재시도.
    func retry(pw newPw: String? = nil) {
        if let newPw { pw = newPw }
        guard !host.isEmpty else { return }
        want = true
        tries = 0
        failSince = nil
        startAudio()
        connect()
    }

    func leave() {
        want = false
        retryWork?.cancel()
        connGen += 1
        conn?.cancel()
        conn = nil
        meter?.invalidate()
        meter = nil
        stopAudio()
        state = .idle
        // 방도 잊는다. 잠금화면 일시정지(pauseFromLockScreen)와 달리 '닫기'다 — 남겨 두면 카드가 안 사라진다.
        host = ""
        roomName = ""
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    // MARK: - 연결

    private func connect() {
        retryWork?.cancel()
        connGen += 1
        let gen = connGen
        conn?.cancel()
        conn = nil
        if !online { state = .offline; return }
        if state != .lost { state = .connecting(tries: tries) }

        let path = "/stream?pw=" + (pw.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")
            + "&t=ios-\(Int(Date().timeIntervalSince1970))-\(tries)"
        let c = StreamConn(host: host, path: path, queue: netQ)
        conn = c
        c.onStart = { [weak self] rate, ch in
            // 네트워크 스레드에서 동기로 부른다. 돌려준 버퍼에 이후 소리를 쓴다.
            DispatchQueue.main.sync {
                MainActor.assumeIsolated { self?.streamStarted(rate: rate, channels: ch, gen) }
            }
        }
        c.onRefused = { [weak self] code in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.refused(code, gen) } }
        }
        c.onDropped = { [weak self] in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.dropped(gen) } }
        }
        c.start()
    }

    private func streamStarted(rate: Double, channels: Int, _ gen: Int) {
        guard gen == connGen else { return }
        os_log("스트림 시작 %{public}@ %.0fHz %dch", log: log, type: .info, host, rate, channels)
        tries = 0
        failSince = nil
        if rate != streamRate {
            // 호스트 레이트에 맞춰 엔진을 다시 짠다. 아이폰이 리샘플해서 내보낸다.
            streamRate = rate
            stopAudio()
            ring = PCMRing(rate: rate)
            startAudio()
        } else {
            ring.reset()
        }
        conn?.ring = ring
        state = .playing
        updateNowPlaying()
        // 방 코드로 들어오면 이름을 모른다("방 T3N"). 호스트에게 진짜 이름을 물어 바꿔 단다.
        let h = host
        MiniHTTP.get(host: h, path: "/info", timeout: 2) { [weak self] r in
            guard let r, r.status == 200,
                  let j = try? JSONSerialization.jsonObject(with: r.body) as? [String: Any],
                  let name = j["name"] as? String, !name.isEmpty else { return }
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, self.host == h else { return }
                    self.roomName = name
                    self.updateNowPlaying()
                }
            }
        }
    }

    private func refused(_ code: Int, _ gen: Int) {
        guard gen == connGen else { return }
        switch code {
        case 401: want = false; state = .wrongPassword
        case 429: want = false; state = .tooMany
        case 410: want = false; state = .ended; stopAudio()
        default: dropped(gen)
        }
    }

    /// 스트림이 끊기거나 연결이 안 됐다. 왜 그랬는지 /check 로 물어본다.
    private func dropped(_ gen: Int) {
        guard gen == connGen, want else { return }
        connGen += 1
        conn?.cancel()
        conn = nil
        if !online { state = .offline; return }

        let q = "/check?pw=" + (pw.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")
        let checkGen = connGen
        MiniHTTP.get(host: host, path: q, timeout: 2.5) { [weak self] r in
            Task { @MainActor in
                guard let self, self.want, checkGen == self.connGen else { return }
                if let r, [401, 410, 429].contains(r.status) {
                    self.refused(r.status, checkGen)
                    return
                }
                if r == nil {
                    if self.failSince == nil { self.failSince = Date() }
                    if Date().timeIntervalSince(self.failSince!) >= Self.lostAfter { self.state = .lost }
                } else {
                    self.failSince = nil   // 호스트는 살아 있다. 스트림만 끊긴 것
                }
                self.tries += 1
                let delay = self.state == .lost ? Self.lostRetry : 1.0
                let work = DispatchWorkItem { [weak self] in Task { @MainActor in self?.connect() } }
                self.retryWork = work
                DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
                if self.state != .lost { self.state = .connecting(tries: self.tries) }
            }
        }
    }

    private func pathChanged(_ up: Bool) {
        let was = online
        online = up
        guard want else { return }
        if !up {
            state = .offline
            connGen += 1
            conn?.cancel()
        } else if !was {
            // 돌아왔다. 기다린 시간은 실패로 치지 않는다.
            failSince = nil
            tries = 0
            state = .connecting(tries: 0)
            connect()
        }
    }

    // MARK: - 오디오

    private func startAudio() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [])
        try? session.setPreferredIOBufferDuration(0.01)
        try? session.setActive(true)

        guard source == nil else {
            if !engine.isRunning { try? engine.start() }
            return
        }
        let rate = streamRate > 0 ? streamRate : PCMFormat.rate
        guard let fmt = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2) else { return }
        let ring = self.ring
        let node = AVAudioSourceNode(format: fmt) { _, _, frameCount, abl -> OSStatus in
            let bufs = UnsafeMutableAudioBufferListPointer(abl)
            guard bufs.count >= 2,
                  let l = bufs[0].mData?.assumingMemoryBound(to: Float.self),
                  let r = bufs[1].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            ring.read(into: l, r, frames: Int(frameCount))
            return noErr
        }
        source = node
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: fmt)
        engine.prepare()
        do { try engine.start() } catch {
            os_log("엔진 시작 실패 %{public}@", log: log, type: .error, String(describing: error))
        }
    }

    private func stopAudio() {
        engine.stop()
        if let source {
            engine.detach(source)
            self.source = nil
        }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func interrupted(_ n: Notification) {
        guard let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        if type == .ended && want {
            // 전화가 끝났다. 그 사이 쌓인 건 이미 지난 소리라 버리고 다시 붙는다.
            ring.reset()
            try? AVAudioSession.sharedInstance().setActive(true)
            if !engine.isRunning { try? engine.start() }
        }
    }

    // MARK: - 잠금화면

    private func updateNowPlaying() {
        MPNowPlayingInfoCenter.default().nowPlayingInfo = [
            MPMediaItemPropertyTitle: roomName.isEmpty ? "이음" : roomName,
            MPMediaItemPropertyArtist: "이음 · 같이 듣는 중",
            MPNowPlayingInfoPropertyIsLiveStream: true,
            MPNowPlayingInfoPropertyPlaybackRate: state == .playing ? 1.0 : 0.0,
        ]
    }

    private func setupRemoteCommands() {
        let cc = MPRemoteCommandCenter.shared()
        cc.pauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.pauseFromLockScreen() }
            return .success
        }
        cc.playCommand.addTarget { [weak self] _ in
            Task { @MainActor in self?.retry() }
            return .success
        }
        cc.togglePlayPauseCommand.addTarget { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.want { self.pauseFromLockScreen() } else { self.retry() }
            }
            return .success
        }
    }

    /// 잠금화면 일시정지. 방은 기억해 두고 소리만 멈춘다.
    private func pauseFromLockScreen() {
        want = false
        connGen += 1
        conn?.cancel()
        stopAudio()
        state = .idle
        updateNowPlaying()
    }
}
