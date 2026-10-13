import Foundation
import Darwin
import os

/// 앱을 깐 참여자가 주소를 타이핑하지 않게, 존재를 1초마다 브로드캐스트한다.
/// 페이로드는 안드로이드판과 **동일**(`LT2|방ID|방이름|주소|잠김`)이라 안드로이드 앱이 그대로 찾는다.
///
/// ★iOS 는 브로드캐스트 송신에 애플이 승인하는 멀티캐스트 권한이 필요하다. 권한이 없으면
///   sendto 가 조용히 실패한다(EHOSTUNREACH 등). 그래도 해롭지 않으니 계속 시도하고,
///   안드로이드 청취자는 방 코드로도 들어올 수 있다.
final class Beacon {

    private var running = false
    private var fd: Int32 = -1
    private var loggedFailure = false

    func start(server: StreamServer) {
        fd = socket(AF_INET, SOCK_DGRAM, 0)
        guard fd >= 0 else { return }
        var yes: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, socklen_t(MemoryLayout<Int32>.size))
        running = true

        Thread.detachNewThread { [weak self, weak server] in
            while self?.running == true, let server {
                self?.sweep(server)
                Thread.sleep(forTimeInterval: 1.0)
            }
        }
    }

    /// 인터페이스마다 그 서브넷으로 따로 보낸다. 비콘에 담는 IP 도 해당
    /// 인터페이스 주소로 맞춘다 — 참여자는 자기가 붙어 있는 망의 주소를 받아야 접속된다.
    private func sweep(_ server: StreamServer) {
        for nif in Net.lanInterfaces() {
            guard let bcast = nif.broadcast else { continue }
            let name = server.roomName.replacingOccurrences(of: "|", with: " ")
            let msg = "LT2|\(server.roomId)|\(name)|\(nif.address)|\(server.roomPw.isEmpty ? 0 : 1)"
            var addr = sockaddr_in()
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = LTPort.beacon.bigEndian
            addr.sin_addr.s_addr = inet_addr(bcast)
            guard addr.sin_addr.s_addr != INADDR_NONE else { continue }

            let n = Data(msg.utf8).withUnsafeBytes { raw in
                withUnsafePointer(to: &addr) { ap in
                    ap.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        sendto(fd, raw.baseAddress, raw.count, 0,
                               sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            if n < 0 && !loggedFailure {
                loggedFailure = true
                os_log("비콘 송신 실패 errno=%d (멀티캐스트 권한 없음일 가능성)", log: log, type: .info, errno)
            }
        }
    }

    func stop() {
        running = false
        if fd >= 0 { Darwin.close(fd); fd = -1 }
    }
}
