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
    /// ReplayKit 버퍼 하나 ≈ 20ms. 100개 ≈ 2초. 넘치면 오래된 것부터 버린다 —
    /// 늦게 들리는 것보다 잠깐 끊기는 게 낫다(같이 듣기라 다른 사람과 어긋난다).
    private let maxChunks = 100
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
        let d = closed ? nil : queue.removeFirst()
        lock.unlock()
        return d
    }

    func close() {
        lock.lock(); closed = true; lock.broadcast(); lock.unlock()
        Darwin.shutdown(fd, SHUT_RDWR)
    }
}

/// 캡처한 PCM 을 로컬 네트워크에 WAV 스트림으로 뿌리는 최소 HTTP 서버.
/// 안드로이드 `CaptureService.handleClient` 와 **같은 길**을 낸다 — 브라우저 페이지,
/// 안드로이드 앱, 아이폰 앱이 호스트가 무슨 폰인지 몰라도 붙는다.
///
///   /info    방 소개(이름·잠김). 비밀번호를 안 묻는다 — 아이폰 앱이 주소를 훑어 방을 찾는 창구
///   /check   비밀번호 확인만. 204 / 401 / 429
///   /stream  소리. 비밀번호 검사는 여기서 한다
///   /host    이 폰(127.0.0.1)에서만. 앱 화면이 방 코드·청취자 수를 읽는다
///   그 외    재생 페이지
///
/// **이게 앱이 아니라 확장(Extension) 안에서 돈다.** iOS 는 앱 본체를 홈 화면으로
/// 나가는 순간 정지시키지만 Broadcast Upload Extension 은 별도 프로세스로 계속
/// 살아 있다. 안드로이드에서 포그라운드 서비스가 하던 역할이 여기선 확장이다.
final class StreamServer {

    private var listenFd: Int32 = -1
    private let clientsLock = NSLock()
    private var clients: [Client] = []
    private var running = false
    /// 공유를 끈 뒤 작별 인사 중. 모든 요청에 410 으로 '끝났다'고 답한다.
    private var ended = false

    let roomId: String
    var roomName: String
    let roomPw: String
    /// 앱 화면의 '공유 중지'. 방송은 확장만 끝낼 수 있어서 앱이 /stop 으로 부탁한다.
    var onStopRequest: (() -> Void)?

    /// 틀린 비밀번호 기록. 주소 → (실패 횟수, 이 시각 전까지 거절)
    private var pwFails: [String: (Int, Date)] = [:]
    private let pwLock = NSLock()
    private static let pwMaxFails = 10

    init(roomName: String) {
        roomId = String(format: "%06x", UInt32.random(in: 0...0xFFFFFF))
        self.roomName = roomName
        roomPw = String(format: "%04d", Int.random(in: 0...9999))
    }

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
        // 청취자가 끊긴 소켓에 쓰다가 SIGPIPE 로 확장이 통째로 죽지 않게.
        setsockopt(listenFd, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = LTPort.stream.bigEndian
        addr.sin_addr.s_addr = INADDR_ANY

        let bound = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(listenFd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, listen(listenFd, 16) == 0 else {
            os_log("bind/listen 실패 errno=%d", log: log, type: .error, errno)
            Darwin.close(listenFd); listenFd = -1
            return
        }

        running = true
        os_log("HTTP 서버 시작 :%d", log: log, type: .info, Int(LTPort.stream))

        let fd = listenFd
        Thread.detachNewThread { [weak self] in self?.acceptLoop(fd) }
    }

    private func acceptLoop(_ lfd: Int32) {
        while running {
            var peer = sockaddr_in()
            var len = socklen_t(MemoryLayout<sockaddr_in>.size)
            let fd = withUnsafeMutablePointer(to: &peer) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { accept(lfd, $0, &len) }
            }
            if fd < 0 { if running { usleep(50_000) }; continue }
            var yes: Int32 = 1
            setsockopt(fd, Int32(IPPROTO_TCP), TCP_NODELAY, &yes, socklen_t(MemoryLayout<Int32>.size))
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &yes, socklen_t(MemoryLayout<Int32>.size))
            // 요청 한 줄을 5초 안에 안 보내는 연결은 닫는다. 브라우저 preconnect 가
            // 빈 연결을 열어두는데, 그걸 영원히 물고 있으면 스레드가 쌓인다.
            var tv = timeval(tv_sec: 5, tv_usec: 0)
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
            let ip = Net.uint32ToIPv4(UInt32(bigEndian: peer.sin_addr.s_addr))
            // 받는 스레드에서 직접 처리하지 않는다 — 위의 빈 연결 하나가 뒤의 모든 접속을 줄 세운다.
            Thread.detachNewThread { [weak self] in self?.handle(fd: fd, peer: ip) }
        }
    }

    private func handle(fd: Int32, peer: String) {
        guard let request = readRequest(fd) else { Darwin.close(fd); return }
        let path = requestPath(request)
        // 청취자는 호스트와 다른 언어를 쓸 수 있다. 문구는 이 폰 설정이 아니라 요청을 따른다.
        let (lang, t) = pageText(request)

        if ended {
            reply(fd, "410 Gone", "text/plain; charset=utf-8", Data((t["http_ended"] ?? "").utf8))
            return
        }

        if path.hasPrefix("/info") {
            let body: [String: Any] = ["app": "ieum", "id": roomId, "name": roomName,
                                       "locked": !roomPw.isEmpty, "os": "ios"]
            let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
            reply(fd, "200 OK", "application/json; charset=utf-8", data)
            return
        }

        #if DEBUG
        if path.hasPrefix("/diagraw") {
            reply(fd, "200 OK", "application/octet-stream", Diag.shared.rawDump())
            return
        }
        if path.hasPrefix("/diag") {
            if let r = param(path, "rec") { Diag.shared.recording = r == "1" }
            // 개발용. 캡처가 실제로 받는 포맷·양을 맥에서 curl 로 본다(Diag.swift).
            let body = Diag.shared.json(extra: ["clients": clientCount, "dropped": droppedTotal, "pw": roomPw])
            reply(fd, "200 OK", "application/json; charset=utf-8", body)
            return
        }
        #endif

        if path.hasPrefix("/host") {
            // 방 코드엔 비밀번호가 들어 있다. 같은 폰(앱 화면)에게만 알려준다.
            guard peer == "127.0.0.1" else {
                reply(fd, "403 Forbidden", "text/plain", Data()); return
            }
            if let n = param(path, "name"), !n.isEmpty { roomName = String(n.prefix(24)) }
            let addrs = Net.lanInterfaces().map { $0.address }
            let body: [String: Any] = ["id": roomId, "name": roomName, "pw": roomPw,
                                       "addrs": addrs, "listeners": clientCount]
            let data = (try? JSONSerialization.data(withJSONObject: body)) ?? Data("{}".utf8)
            reply(fd, "200 OK", "application/json; charset=utf-8", data)
            return
        }

        if path.hasPrefix("/stop") {
            // 같은 폰(앱 화면)만 끌 수 있다.
            guard peer == "127.0.0.1" else {
                reply(fd, "403 Forbidden", "text/plain", Data()); return
            }
            reply(fd, "204 No Content", nil, nil)
            onStopRequest?()
            return
        }

        if path.hasPrefix("/check") {
            let deny = checkPw(peer, param(path, "pw") ?? "")
            if deny != 0 { writeDenied(fd, deny, t) }
            else { reply(fd, "204 No Content", nil, nil) }
            return
        }

        if !path.hasPrefix("/stream") {
            reply(fd, "200 OK", "text/html; charset=utf-8", Data(page(lang, t).utf8))
            return
        }

        // ★검사는 반드시 여기다. 소리가 나가는 길은 이 한 곳뿐이다.
        let deny = checkPw(peer, param(path, "pw") ?? "")
        if deny != 0 {
            os_log("입장 거부 %{public}@ → %d", log: log, type: .info, peer, deny)
            writeDenied(fd, deny, t)
            return
        }

        // ── Range 대응 ── 안드로이드 handleClient 와 같은 규칙.
        // 사파리는 `bytes=0-1` 로 떠본 뒤 `bytes=0-` 를 보내고 206 을 기대한다.
        // 끝이 정해진 작은 요청에는 "WAV 헤더 + 무음" 가상 파일에서 정확히 그만큼 잘라준다.
        let huge: Int64 = 0x7FFF_FFFF_FFFF
        let wav = wavHeader()
        let range = parseRange(request)
        let start = range.map { min($0.0, huge - 1) } ?? 0
        let end = range?.1 ?? -1

        if range != nil, end >= start, end - start < 64 * 1024 {
            let len = Int(end - start + 1)
            var body = Data(count: len)
            for i in 0..<len where Int(start) + i < wav.count { body[i] = wav[Int(start) + i] }
            let head = "HTTP/1.1 206 Partial Content\r\n"
                + "Content-Range: bytes \(start)-\(end)/\(huge)\r\n"
                + "Content-Length: \(len)\r\nContent-Type: audio/wav\r\nAccept-Ranges: bytes\r\n"
                + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"
            _ = sendAll(fd, Data(head.utf8)) && sendAll(fd, body)
            Darwin.close(fd)
            return
        }

        var headers = range != nil
            ? "HTTP/1.1 206 Partial Content\r\nContent-Range: bytes \(start)-\(huge - 1)/\(huge)\r\n"
            : "HTTP/1.1 200 OK\r\n"
        headers += "Content-Type: audio/wav\r\nAccept-Ranges: bytes\r\n"
        headers += "Cache-Control: no-cache\r\nConnection: close\r\n\r\n"

        var first = Data(headers.utf8)
        if start < wav.count {
            first.append(wav.subdata(in: Int(start)..<wav.count))
        } else {
            let frame = Int64(PCMFormat.channels * PCMFormat.bits / 8)
            let pad = (frame - (start - Int64(wav.count)) % frame) % frame
            first.append(Data(count: Int(pad)))
        }
        guard sendAll(fd, first) else { Darwin.close(fd); return }

        let client = Client(fd: fd)
        clientsLock.lock(); clients.append(client); clientsLock.unlock()
        os_log("청취자 접속 %{public}@ (총 %d)%{public}@", log: log, type: .info,
               peer, clientCount, range != nil ? " range=206" : "")

        while running {
            guard let chunk = client.pop() else { break }
            if !sendAll(fd, chunk) { break }
        }

        clientsLock.lock(); clients.removeAll { $0 === client }; clientsLock.unlock()
        client.close()
        Darwin.close(fd)
        os_log("청취자 나감 (남은 %d)", log: log, type: .info, clientCount)
    }

    /// 모든 클라이언트에게 뿌린다. 막힌 클라이언트가 있어도 여기서 기다리지 않는다.
    func broadcast(_ data: Data) {
        clientsLock.lock()
        let snapshot = clients
        clientsLock.unlock()
        for c in snapshot { c.push(data) }
    }

    /// 스트림은 바로 끊고, 서버는 `farewell` 초 동안 남아 모든 요청에 410 으로 답한다.
    /// 청취자 페이지는 끊기면 /check 를 물어본다 — 이게 없으면 "호스트가 껐다" 와
    /// "Wi-Fi 가 끊겼다" 를 구분 못 하고 영원히 재접속만 한다.
    func stop(farewell: TimeInterval) {
        ended = true
        clientsLock.lock(); let snapshot = clients; clients = []; clientsLock.unlock()
        for c in snapshot { c.close() }
        if farewell > 0 { Thread.sleep(forTimeInterval: farewell) }
        running = false
        if listenFd >= 0 { Darwin.shutdown(listenFd, SHUT_RDWR); Darwin.close(listenFd); listenFd = -1 }
        os_log("HTTP 서버 종료", log: log, type: .info)
    }

    // ── 비밀번호 ── 안드로이드 checkPw 와 같은 규칙. 0 이면 통과, 아니면 HTTP 상태 코드.
    private func checkPw(_ peer: String, _ given: String) -> Int {
        if roomPw.isEmpty { return 0 }
        pwLock.lock(); defer { pwLock.unlock() }
        // 맞는 비밀번호는 지연보다 먼저 통과시킨다. 오타 직후 바로 고쳐 친 사람을 막지 않게.
        if given == roomPw { pwFails[peer] = nil; return 0 }
        let now = Date()
        if let st = pwFails[peer] {
            if st.0 >= Self.pwMaxFails || now < st.1 { return 429 }
        }
        // 첫 실패엔 지연이 없다. 페이지가 실패 직후 /check 로 이유를 되묻기 때문이다.
        let fails = (pwFails[peer]?.0 ?? 0) + 1
        let wait = fails <= 1 ? 0 : min(Double(1 << min(fails - 2, 3)), 8)
        pwFails[peer] = (fails, now.addingTimeInterval(wait))
        return 401
    }

    private func writeDenied(_ fd: Int32, _ code: Int, _ t: [String: String]) {
        if code == 401 {
            reply(fd, "401 Unauthorized", "text/plain; charset=utf-8", Data((t["http_wrong_pw"] ?? "").utf8))
        } else {
            reply(fd, "429 Too Many Requests", "text/plain; charset=utf-8",
                  Data((t["http_too_many"] ?? "").utf8))
        }
    }

    // ── 청취 페이지 문구 ── 안드로이드 CaptureService.page() 와 같은 규칙.

    /// 언어별 문구(PageText.swift — i18n/ 에서 생성). 처음 한 번만 푼다.
    private static let pageTexts: [String: [String: String]] = PageText.json.compactMapValues {
        (try? JSONSerialization.jsonObject(with: Data($0.utf8))) as? [String: String]
    }

    /// 요청의 Accept-Language 에서 우리가 가진 첫 언어. 없으면 영어.
    /// 브라우저는 선호 순서대로 적어 보내므로 q 값은 보지 않는다.
    private func pageText(_ req: String) -> (String, [String: String]) {
        for line in req.components(separatedBy: "\r\n") {
            guard let c = line.firstIndex(of: ":"),
                  line[..<c].trimmingCharacters(in: .whitespaces).lowercased() == "accept-language" else { continue }
            for part in line[line.index(after: c)...].split(separator: ",") {
                let tag = part.split(separator: ";").first.map { $0.trimmingCharacters(in: .whitespaces).lowercased() } ?? ""
                let lang = String(tag.split(separator: "-").first ?? "")
                if let t = Self.pageTexts[lang] { return (lang, t) }
            }
            break
        }
        return ("en", Self.pageTexts["en"] ?? [:])
    }

    private static let token = try! NSRegularExpression(pattern: #"\{\{(\w+)\}\}"#)

    /// 본문 속 {{키}} 는 그 자리에 HTML 로, 스크립트가 쓰는 문구는 T 객체 하나로 통째로 넣는다.
    private func page(_ lang: String, _ t: [String: String]) -> String {
        let html = NSMutableString(string: Page.html)
        // 뒤에서부터 바꿔야 앞쪽 위치가 안 밀린다.
        for m in Self.token.matches(in: Page.html, range: NSRange(location: 0, length: html.length)).reversed() {
            let key = html.substring(with: m.range(at: 1))
            html.replaceCharacters(in: m.range, with: escapeHTML(t[key] ?? ""))
        }
        var withLang = t
        withLang["lang"] = lang
        // 기본 설정의 JSONSerialization 은 "/" 를 "\/" 로 적으므로 문구가 </script> 를 품어도 안 끊긴다.
        let json = (try? JSONSerialization.data(withJSONObject: withLang)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return (html as String)
            .replacingOccurrences(of: "%LANG%", with: lang)
            .replacingOccurrences(of: "%T%", with: json)
            .replacingOccurrences(of: "%ROOM%", with: escapeHTML(roomName.isEmpty ? (t["app_name"] ?? "") : roomName))
    }

    // ── 유틸 ──────────────────────────────────────────────────

    private func reply(_ fd: Int32, _ status: String, _ type: String?, _ body: Data?) {
        var head = "HTTP/1.1 \(status)\r\n"
        if let type { head += "Content-Type: \(type)\r\n" }
        if let body { head += "Content-Length: \(body.count)\r\n" }
        head += "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        var d = Data(head.utf8)
        if let body { d.append(body) }
        sendAll(fd, d)
        Darwin.close(fd)
    }

    private func readRequest(_ fd: Int32) -> String? {
        var buf = [UInt8](repeating: 0, count: 4096)
        var total = 0
        while total < buf.count {
            let n = buf.withUnsafeMutableBytes { recv(fd, $0.baseAddress! + total, $0.count - total, 0) }
            if n <= 0 { break }
            total += n
            if let s = String(bytes: buf[0..<total], encoding: .utf8), s.contains("\r\n\r\n") { return s }
        }
        return total > 0 ? String(decoding: buf[0..<total], as: UTF8.self) : nil
    }

    private func requestPath(_ req: String) -> String {
        let parts = req.split(separator: "\r\n", maxSplits: 1).first?
            .split(separator: " ") ?? []
        return parts.count >= 2 ? String(parts[1]) : "/"
    }

    private func param(_ path: String, _ key: String) -> String? {
        URLComponents(string: "http://x" + path)?.queryItems?.first(where: { $0.name == key })?.value
    }

    /// `Range: bytes=시작-끝` → (시작, 끝). 끝이 없으면 -1.
    private func parseRange(_ req: String) -> (Int64, Int64)? {
        for line in req.components(separatedBy: "\r\n") {
            guard let c = line.firstIndex(of: ":"),
                  line[..<c].trimmingCharacters(in: .whitespaces).lowercased() == "range" else { continue }
            var v = line[line.index(after: c)...].trimmingCharacters(in: .whitespaces).lowercased()
            guard v.hasPrefix("bytes=") else { return nil }
            v.removeFirst(6)
            if let comma = v.firstIndex(of: ",") { v = String(v[..<comma]) }
            guard let dash = v.firstIndex(of: "-") else { return nil }
            let a = v[..<dash].trimmingCharacters(in: .whitespaces)
            let b = v[v.index(after: dash)...].trimmingCharacters(in: .whitespaces)
            if a.isEmpty { return (0, -1) }
            guard let s = Int64(a) else { return nil }
            return (s, b.isEmpty ? -1 : (Int64(b) ?? -1))
        }
        return nil
    }

    private func escapeHTML(_ t: String) -> String {
        t.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&#39;")
    }

    @discardableResult
    private func sendAll(_ fd: Int32, _ data: Data) -> Bool {
        return data.withUnsafeBytes { raw -> Bool in
            guard let base = raw.baseAddress else { return true }
            var sent = 0
            while sent < raw.count {
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
}
