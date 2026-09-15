import Foundation
import Darwin
import os

/// 클라이언트 한 명. 큐를 따로 둬서 **느린 청취자가 전체를 막지 않게** 한다.
/// 안드로이드에서 같은 이유로 클라이언트별 큐를 도입했고, 여기서도 전제가 같다.
private final class Client {
    let fd: Int32
    private let lock = NSCondition()
    private var queue: [Data] = []
    private var closed = false
    /// 청크 1개 ≈ 0.1초. 50개 = 5초.
    private let maxChunks = 50
    private(set) var dropped = 0

    init(fd: Int32) { self.fd = fd }

    func push(_ d: Data) {
        lock.lock()
        if queue.count >= maxChunks {
            queue.removeFirst()
            dropped += 1
        }
        queue.append(d)
        lock.signal()
        lock.unlock()
    }

    /// 보낼 게 생길 때까지 기다렸다 하나 꺼낸다. 닫히면 nil.
    func pop() -> Data? {
        lock.lock()
        while queue.isEmpty && !closed { lock.wait() }
        let d = queue.isEmpty ? nil : queue.removeFirst()
        lock.unlock()
        return d
    }

    func close() {
        lock.lock(); closed = true; lock.broadcast(); lock.unlock()
        Darwin.shutdown(fd, SHUT_RDWR)
        Darwin.close(fd)
    }
}

/// 캡처한 PCM 을 로컬 네트워크에 WAV 스트림으로 뿌리는 최소 HTTP 서버.
///
/// **이게 앱이 아니라 확장(Extension) 안에서 돈다.** iOS 는 앱 본체를 홈 화면으로
/// 나가는 순간 정지시키지만 Broadcast Upload Extension 은 별도 프로세스로 계속
/// 살아 있다. 안드로이드에서 포그라운드 서비스가 하던 역할이 여기선 확장이다.
final class StreamServer {

    private var listenFd: Int32 = -1
    private let clientsLock = NSLock()
    private var clients: [Client] = []
    private var running = false

    var clientCount: Int {
        clientsLock.lock(); defer { clientsLock.unlock() }
        return clients.count
    }

    var droppedTotal: Int {
        clientsLock.lock(); defer { clientsLock.unlock() }
        return clients.reduce(0) { $0 + $1.dropped }
    }

    func start() {
        listenFd = socket(AF_INET, SOCK_STREAM, 0)
        guard listenFd >= 0 else {
            os_log("소켓 생성 실패", log: log, type: .error); return
        }
        var yes: Int32 = 1
        setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = LTPort.stream.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(listenFd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(listenFd, 8) == 0 else {
            os_log("bind/listen 실패 errno=%d", log: log, type: .error, errno)
            Darwin.close(listenFd); listenFd = -1
            return
        }

        running = true
        os_log("HTTP 서버 시작 :%d", log: log, type: .info, Int(LTPort.stream))

        Thread.detachNewThread { [weak self] in self?.acceptLoop() }
    }

    private func acceptLoop() {
        while running {
            let fd = accept(listenFd, nil, nil)
            if fd < 0 { if running { usleep(50_000) }; continue }
            var yes: Int32 = 1
            setsockopt(fd, Int32(IPPROTO_TCP), TCP_NODELAY, &yes, socklen_t(MemoryLayout<Int32>.size))
            Thread.detachNewThread { [weak self] in self?.handle(fd: fd) }
        }
    }

    private func handle(fd: Int32) {
        guard let request = readRequest(fd) else { Darwin.close(fd); return }
        let path = requestPath(request)

        if path != "/stream" {
            sendAll(fd, Data(indexPage.utf8))
            Darwin.close(fd)
            return
        }

        // ── Range 대응 ────────────────────────────────────────
        // 사파리는 미디어를 받을 때 `Range: bytes=0-` 를 보내고 206 을 기대하는
        // 성향이 강하다. 끝이 없는 스트림이라 진짜 길이는 없지만, 아주 큰 값을
        // 총 길이로 적어주면 "받다 만 파일"로 취급해 계속 재생한다.
        // 안드로이드판 서버에는 이 처리가 없다 — 아이폰 청취가 거기서 막힌다면 범인이다.
        let hasRange = request.range(of: "\r\nrange:", options: .caseInsensitive) != nil
        let huge: Int64 = 0x7FFF_FFFF_FFFF
        var headers = hasRange
            ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-\(huge - 1)/\(huge)\r\n"
            : "HTTP/1.1 200 OK\r\n"
        headers += "Content-Type: audio/wav\r\n"
        headers += "Accept-Ranges: bytes\r\n"
        headers += "Cache-Control: no-cache\r\n"
        headers += "Connection: close\r\n\r\n"

        guard sendAll(fd, Data(headers.utf8)), sendAll(fd, wavHeader()) else {
            Darwin.close(fd); return
        }

        let client = Client(fd: fd)
        clientsLock.lock(); clients.append(client); clientsLock.unlock()
        os_log("클라이언트 접속 (총 %d)", log: log, type: .info, clientCount)

        while running {
            guard let chunk = client.pop() else { break }
            if !sendAll(fd, chunk) { break }
        }

        clientsLock.lock(); clients.removeAll { $0 === client }; clientsLock.unlock()
        client.close()
        os_log("클라이언트 종료 (남은 %d)", log: log, type: .info, clientCount)
    }

    /// 모든 클라이언트에게 뿌린다. 막힌 클라이언트가 있어도 여기서 기다리지 않는다.
    func broadcast(_ data: Data) {
        clientsLock.lock()
        let snapshot = clients
        clientsLock.unlock()
        for c in snapshot { c.push(data) }
    }

    func stop() {
        running = false
        clientsLock.lock(); let snapshot = clients; clients = []; clientsLock.unlock()
        for c in snapshot { c.close() }
        if listenFd >= 0 { Darwin.close(listenFd); listenFd = -1 }
        os_log("HTTP 서버 종료", log: log, type: .info)
    }

    // ── 유틸 ──────────────────────────────────────────────────

    private func readRequest(_ fd: Int32) -> String? {
        var buf = [UInt8](repeating: 0, count: 2048)
        var total = 0
        while total < buf.count {
            let n = recv(fd, &buf[total], buf.count - total, 0)
            if n <= 0 { break }
            total += n
            if let s = String(bytes: buf[0..<total], encoding: .utf8),
               s.contains("\r\n\r\n") { return s }
        }
        return total > 0 ? String(bytes: buf[0..<total], encoding: .utf8) : nil
    }

    private func requestPath(_ req: String) -> String {
        let parts = req.split(separator: "\r\n", maxSplits: 1).first?
            .split(separator: " ") ?? []
        return parts.count >= 2 ? String(parts[1]) : "/"
    }

    @discardableResult
    private func sendAll(_ fd: Int32, _ data: Data) -> Bool {
        return data.withUnsafeBytes { raw -> Bool in
            guard let base = raw.baseAddress else { return false }
            var sent = 0
            while sent < raw.count {
                // SIGPIPE 로 프로세스가 죽지 않게 MSG_NOSIGNAL 대신 SO_NOSIGPIPE 를
                // 못 쓰는 상황이라 send 반환값으로만 판단한다.
                let n = send(fd, base.advanced(by: sent), raw.count - sent, 0)
                if n <= 0 { return false }
                sent += n
            }
            return true
        }
    }

    /// 길이를 모르는 스트림이라 크기 필드를 최대값으로 채운다. 브라우저는 이걸
    /// "아주 긴 파일"로 보고 계속 재생한다.
    private func wavHeader() -> Data {
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); u32(0xFFFF_FFFF)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16)
        u16(1)                                            // PCM
        u16(UInt16(PCMFormat.channels))
        u32(UInt32(PCMFormat.rate))
        u32(UInt32(PCMFormat.bytesPerSec))
        u16(UInt16(PCMFormat.channels * PCMFormat.bits / 8))
        u16(UInt16(PCMFormat.bits))
        d.append(contentsOf: Array("data".utf8)); u32(0xFFFF_FFFF)
        return d
    }

    private var indexPage: String {
        let body = """
        <!doctype html><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>같이 듣기</title>
        <style>body{font:16px -apple-system,sans-serif;text-align:center;padding:3rem 1rem}
        audio{width:100%;max-width:420px;margin-top:2rem}</style>
        <h2>같이 듣기</h2>
        <p>재생을 누르면 호스트의 소리가 들립니다.</p>
        <audio controls autoplay playsinline src="/stream"></audio>
        """
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: \(body.utf8.count)\r\nConnection: close\r\n\r\n" + body
    }
}
