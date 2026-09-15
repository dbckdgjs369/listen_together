import Foundation
import Darwin
import os

/// 앱 본체와 확장이 같이 쓰는 것들.
/// 확장은 별도 프로세스라 런타임에 아무것도 공유되지 않는다 — 소스만 공유한다.

let log = OSLog(subsystem: "com.example.lt", category: "LT")

/// 안드로이드판과 같은 포트를 쓴다. 클라이언트(브라우저·안드로이드 앱)를 고치지
/// 않아도 아이폰 호스트에 그대로 붙는다.
enum LTPort {
    static let stream: UInt16 = 7980
    static let beacon: UInt16 = 7981
}

/// 안드로이드판과 **같은 포맷**. 브라우저 페이지도, 안드로이드 PlayerService 도
/// 44100Hz/16bit/스테레오 리틀엔디안을 전제로 짜여 있다.
enum PCMFormat {
    static let rate = 44100.0
    static let channels = 2
    static let bits = 16
    static let bytesPerSec = Int(rate) * channels * bits / 8   // 176400
}

/// 인터페이스 하나의 IPv4 주소와 그 서브넷의 브로드캐스트 주소.
struct IPv4Interface {
    let name: String
    let address: String
    let broadcast: String?
}

enum Net {

    /// 올라와 있는 IPv4 인터페이스를 전부 훑는다.
    ///
    /// 하나만 고르면 안 된다. 안드로이드판에서 핫스팟을 켠 순간 Wi-Fi 참여자가
    /// 닿을 수 없는 주소를 광고해서 세션이 실제로 깨진 적이 있다.
    static func interfaces() -> [IPv4Interface] {
        var out: [IPv4Interface] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, head != nil else { return out }
        defer { freeifaddrs(head) }

        var cur = head
        while let p = cur {
            defer { cur = p.pointee.ifa_next }
            let flags = Int32(p.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            guard let sa = p.pointee.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }

            let name = String(cString: p.pointee.ifa_name)
            guard let addr = string(from: sa) else { continue }
            let bcast = (flags & IFF_BROADCAST != 0)
                ? p.pointee.ifa_dstaddr.flatMap { string(from: $0) }
                : nil
            out.append(IPv4Interface(name: name, address: addr, broadcast: bcast))
        }
        return out
    }

    /// 사람에게 보여줄 대표 주소. Wi-Fi(en0) 를 우선한다.
    static func primaryAddress() -> String? {
        let all = interfaces()
        return all.first(where: { $0.name == "en0" })?.address ?? all.first?.address
    }

    private static func string(from sa: UnsafeMutablePointer<sockaddr>) -> String? {
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let ok = getnameinfo(sa, socklen_t(sa.pointee.sa_len),
                             &host, socklen_t(host.count),
                             nil, 0, NI_NUMERICHOST) == 0
        return ok ? String(cString: host) : nil
    }
}

/// 확장 안에서 UIKit 을 피하려고 sysctl 로 모델명을 읽는다.
let deviceModelName: String = {
    var size = 0
    sysctlbyname("hw.machine", nil, &size, nil, 0)
    var buf = [CChar](repeating: 0, count: max(size, 1))
    sysctlbyname("hw.machine", &buf, &size, nil, 0)
    return String(cString: buf)
}()
