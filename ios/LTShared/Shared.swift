import Foundation
import Darwin
import os

/// 앱 본체와 확장이 같이 쓰는 것들.
/// 확장은 별도 프로세스라 런타임에 아무것도 공유되지 않는다 — 소스만 공유한다.

let log = OSLog(subsystem: "app.ieum", category: "LT")

/// 안드로이드판과 같은 포트를 쓴다. 클라이언트(브라우저·안드로이드 앱)를 고치지
/// 않아도 아이폰 호스트에 그대로 붙는다.
enum LTPort {
    static let stream: UInt16 = 7980
    static let beacon: UInt16 = 7981
}

/// 안드로이드판과 **같은 포맷**. 안드로이드가 44100 → 48000 으로 올라갔다
/// (기기 네이티브 레이트라 리샘플이 없어 지연이 줄었다). 청취자는 WAV 헤더에서
/// 레이트를 읽으므로 맞추지 않아도 들리긴 하지만, 같은 값이어야 비교가 쉽다.
enum PCMFormat {
    static let rate = 48000.0
    static let channels = 2
    static let bits = 16
    static let bytesPerSec = Int(rate) * channels * bits / 8   // 192000
}

/// 인터페이스 하나의 IPv4 주소와 그 서브넷.
struct IPv4Interface {
    let name: String
    let address: String
    let netmask: String?
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
            // 169.254 는 DHCP 를 못 받은 자리표시 주소다. 아무에게도 안 닿는다.
            if addr.hasPrefix("169.254.") { continue }
            // 넷마스크 sockaddr 는 sa_family 가 0 으로 오기도 해서 주소칸만 직접 읽는다.
            let mask = p.pointee.ifa_netmask.map { m -> String in
                m.withMemoryRebound(to: sockaddr_in.self, capacity: 1) {
                    uint32ToIPv4(UInt32(bigEndian: $0.pointee.sin_addr.s_addr))
                }
            }
            let bcast = (flags & IFF_BROADCAST != 0)
                ? p.pointee.ifa_dstaddr.flatMap { string(from: $0) }
                : nil
            out.append(IPv4Interface(name: name, address: addr, netmask: mask, broadcast: bcast))
        }
        return out
    }

    /// 로컬 네트워크(Wi-Fi·핫스팟)에 붙은 인터페이스만. 셀룰러(pdp_ip)·VPN(utun)은 뺀다.
    static func lanInterfaces() -> [IPv4Interface] {
        interfaces().filter { $0.name.hasPrefix("en") || $0.name.hasPrefix("bridge") }
    }

    /// 사람에게 보여줄 대표 주소. Wi-Fi(en0) 를 우선한다.
    static func primaryAddress() -> String? {
        let all = lanInterfaces()
        return all.first(where: { $0.name == "en0" })?.address ?? all.first?.address
    }

    static func ipv4ToUInt32(_ s: String) -> UInt32? {
        let parts = s.split(separator: ".").compactMap { UInt32($0) }
        guard parts.count == 4, parts.allSatisfy({ $0 <= 255 }) else { return nil }
        return parts[0] << 24 | parts[1] << 16 | parts[2] << 8 | parts[3]
    }

    static func uint32ToIPv4(_ v: UInt32) -> String {
        "\(v >> 24 & 255).\(v >> 16 & 255).\(v >> 8 & 255).\(v & 255)"
    }

    private static func string(from sa: UnsafeMutablePointer<sockaddr>) -> String? {
        guard sa.pointee.sa_family == UInt8(AF_INET) else { return nil }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let ok = getnameinfo(sa, socklen_t(MemoryLayout<sockaddr_in>.size),
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
