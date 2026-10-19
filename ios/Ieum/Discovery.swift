import Foundation
import Network

/// 짧은 HTTP GET 한 번. 호스트는 언제나 `Connection: close` 로 답하므로 끝까지 읽으면 된다.
///
/// URLSession 을 안 쓰는 이유: 사설 IP 의 http:// 는 ATS 예외가 필요하고, 수백 개를
/// 동시에 두드릴 때 연결 시간 제한을 짧게 거는 게 NWConnection 쪽이 훨씬 단순하다.
enum MiniHTTP {

    struct Response { let status: Int; let body: Data }

    static func get(host: String, path: String, timeout: TimeInterval,
                    completion: @escaping (Response?) -> Void) {
        let conn = NWConnection(host: NWEndpoint.Host(host),
                                port: NWEndpoint.Port(rawValue: LTPort.stream)!, using: .tcp)
        let q = DispatchQueue(label: "lt.http")
        var done = false
        var buf = Data()
        func finish(_ r: Response?) {
            guard !done else { return }
            done = true
            conn.cancel()
            completion(r)
        }
        func parse() -> Response? {
            guard let headEnd = buf.range(of: Data("\r\n\r\n".utf8)) else { return nil }
            let head = String(decoding: buf[..<headEnd.lowerBound], as: UTF8.self)
            let parts = head.split(separator: " ", maxSplits: 2)
            guard parts.count >= 2, let code = Int(parts[1]) else { return nil }
            return Response(status: code, body: Data(buf[headEnd.upperBound...]))
        }
        func receive() {
            conn.receive(minimumIncompleteLength: 1, maximumLength: 16384) { data, _, isComplete, error in
                if let data { buf.append(data) }
                if isComplete || error != nil || buf.count > 65536 { finish(parse()); return }
                receive()
            }
        }
        conn.stateUpdateHandler = { st in
            switch st {
            case .ready:
                let req = "GET \(path) HTTP/1.1\r\nHost: \(host)\r\nConnection: close\r\n\r\n"
                conn.send(content: Data(req.utf8), completion: .contentProcessed { _ in })
                receive()
            case .failed, .cancelled:
                finish(nil)
            case .waiting:
                // 대개 '연결 거부' — 그 주소엔 아무도 없다. 기다리지 않는다.
                finish(nil)
            default: break
            }
        }
        conn.start(queue: q)
        q.asyncAfter(deadline: .now() + timeout) { finish(nil) }
    }
}

/// 같은 망에서 찾은 방 하나.
struct FoundRoom: Identifiable, Equatable {
    let ip: String
    let roomId: String
    let name: String
    let locked: Bool
    var id: String { ip + "|" + roomId }
}

/// 같은 Wi-Fi 의 방을 찾는다.
///
/// 안드로이드 앱은 호스트의 UDP 비콘(:7981)을 듣지만, **아이폰은 브로드캐스트 수신에
/// 애플이 따로 승인하는 멀티캐스트 권한이 필요하다.** 그래서 내 서브넷의 주소를
/// 하나씩 `/info` 로 두드린다. 254개를 수십 개씩 병렬로 치면 1~2초면 끝난다.
///
/// /check 가 아니라 /info 를 두드리는 이유: /check 는 비밀번호 실패로 세어져서
/// 훑기만 해도 그 방에서 쫓겨난다.
@MainActor
final class Discovery: ObservableObject {

    @Published private(set) var rooms: [FoundRoom] = []
    @Published private(set) var scanning = false
    /// 마지막으로 훑은 내 주소. 화면에 "어느 망을 보고 있는지" 보여준다.
    @Published private(set) var myAddress: String?

    private var timer: Timer?
    private var generation = 0

    func start() {
        guard timer == nil else { return }
        scan()
        timer = Timer.scheduledTimer(withTimeInterval: 6, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.scan() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        generation += 1
        scanning = false
    }

    func scan() {
        guard !scanning else { return }
        let targets = Self.targets()
        myAddress = Net.primaryAddress()
        guard !targets.isEmpty else { rooms = []; return }
        scanning = true
        generation += 1
        let gen = generation

        var found: [FoundRoom] = []
        let group = DispatchGroup()
        let lock = NSLock()
        // 동시에 너무 많이 열면 iOS 가 소켓을 거절한다. 48개씩 나눠 친다.
        let sem = DispatchSemaphore(value: 48)
        DispatchQueue.global(qos: .userInitiated).async {
            for ip in targets {
                sem.wait()
                group.enter()
                MiniHTTP.get(host: ip, path: "/info", timeout: 1.5) { r in
                    defer { sem.signal(); group.leave() }
                    guard let r, r.status == 200,
                          let j = try? JSONSerialization.jsonObject(with: r.body) as? [String: Any],
                          j["app"] as? String == "ieum" else { return }
                    let room = FoundRoom(ip: ip, roomId: j["id"] as? String ?? ip,
                                         name: (j["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? L.roomUnnamed,
                                         locked: j["locked"] as? Bool ?? true)
                    lock.lock(); found.append(room); lock.unlock()
                }
            }
            group.notify(queue: .main) { [weak self] in
                guard let self, gen == self.generation else { return }
                self.scanning = false
                // 같은 방이 두 주소(Wi-Fi·핫스팟)로 잡히면 하나만.
                var seen = Set<String>()
                self.rooms = found.sorted { $0.name < $1.name }.filter { seen.insert($0.roomId).inserted }
            }
        }
    }

    /// 훑을 주소 목록. 서브넷이 /22 보다 넓으면(공용 Wi-Fi 등) 내 주소 주변 /24 만 본다 —
    /// 수만 개를 두드리는 건 의미도 없고 망에도 실례다.
    nonisolated static func targets() -> [String] {
        var out: [String] = []
        for nif in Net.lanInterfaces() {
            guard let me = Net.ipv4ToUInt32(nif.address) else { continue }
            var mask = nif.netmask.flatMap(Net.ipv4ToUInt32) ?? 0xFFFF_FF00
            if mask == 0 || ~mask > 1023 { mask = 0xFFFF_FF00 }
            let net = me & mask
            let bcast = net | ~mask
            guard bcast > net + 1 else { continue }
            for v in (net + 1)..<bcast where v != me {
                out.append(Net.uint32ToIPv4(v))
            }
        }
        return out
    }
}
