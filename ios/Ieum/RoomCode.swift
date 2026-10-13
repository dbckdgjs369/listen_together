import Foundation

/// 방 코드 — 호스트 주소와 입장 비밀번호를 짧은 글자 몇 개로 접는다.
///
/// ★안드로이드 RoomCode.java · site/roomcode.js 와 한 글자도 다르면 안 된다.
///   한쪽만 고치면 코드가 엉뚱한 주소로 풀린다.
///
///   192.168.c.d      → 0 ~ 65535
///   172.16~31.x.x    → 그 뒤 2^20 칸
///   10.x.x.x         → 그 뒤 2^24 칸
///   그 외            → 그 뒤 2^32 칸
enum RoomCode {

    private static let alpha = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")
    private static let b172: UInt64 = 1 << 16
    private static let b10: UInt64 = b172 + (1 << 20)
    private static let bAny: UInt64 = b10 + (1 << 24)

    static func encode(ip: String, pw: String) -> String? {
        guard let idx = ipIndex(ip) else { return nil }
        let p = pw.count <= 4 && pw.allSatisfy(\.isNumber) ? (UInt64(pw) ?? 0) : 0
        var value = idx * 10000 + p
        var s: [Character] = []
        repeat {
            s.append(alpha[Int(value & 31)])
            value >>= 5
        } while value > 0
        return String(s.reversed())
    }

    /// 코드 → (ip, 4자리 비밀번호). 못 읽는 글자가 있으면 nil.
    static func decode(_ raw: String) -> (ip: String, pw: String)? {
        var s = raw.uppercased()
        s.removeAll { " \t\n-_.".contains($0) }
        s = s.replacingOccurrences(of: "O", with: "0")
            .replacingOccurrences(of: "I", with: "1")
            .replacingOccurrences(of: "L", with: "1")
        guard !s.isEmpty, s.count <= 11 else { return nil }
        var value: UInt64 = 0
        for c in s {
            guard let d = alpha.firstIndex(of: c) else { return nil }
            value = value * 32 + UInt64(d)
        }
        let idx = value / 10000
        guard idx < bAny + (1 << 32) else { return nil }
        return (ipFromIndex(idx), String(format: "%04d", Int(value % 10000)))
    }

    private static func ipFromIndex(_ idx: UInt64) -> String {
        if idx < b172 { return "192.168.\(idx >> 8).\(idx & 255)" }
        if idx < b10 {
            let n = idx - b172
            return "172.\(16 + (n >> 16)).\((n >> 8) & 255).\(n & 255)"
        }
        if idx < bAny {
            let n = idx - b10
            return "10.\(n >> 16).\((n >> 8) & 255).\(n & 255)"
        }
        let n = idx - bAny
        return "\(n >> 24).\((n >> 16) & 255).\((n >> 8) & 255).\(n & 255)"
    }

    private static func ipIndex(_ ip: String) -> UInt64? {
        let o = ip.split(separator: ".").compactMap { UInt64($0) }
        guard o.count == 4, o.allSatisfy({ $0 <= 255 }) else { return nil }
        if o[0] == 192 && o[1] == 168 { return o[2] << 8 | o[3] }
        if o[0] == 172 && (16...31).contains(o[1]) { return b172 + ((o[1] - 16) << 16 | o[2] << 8 | o[3]) }
        if o[0] == 10 { return b10 + (o[1] << 16 | o[2] << 8 | o[3]) }
        return bAny + (o[0] << 24 | o[1] << 16 | o[2] << 8 | o[3])
    }
}
