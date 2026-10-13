import SwiftUI

@main
struct IeumApp: App {
    @StateObject private var player = Player()
    @StateObject private var discovery = Discovery()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(player)
                .environmentObject(discovery)
                .preferredColorScheme(.dark)
                .onOpenURL { url in openLink(url) }
        }
    }

    /// `ieum://join?code=XXXXXX` 또는 `ieum://join?host=1.2.3.4&pw=1234&name=방이름`,
    /// 그리고 초대 링크 `https://dbckdgjs369.github.io/listen_together/#XXXXXX`(유니버설 링크).
    private func openLink(_ url: URL) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func v(_ k: String) -> String? { items.first(where: { $0.name == k })?.value }
        if url.scheme == "https" {
            // 코드는 # 뒤에 있다. 틀린 코드면 그냥 앱만 열어 둔다.
            if let code = url.fragment?.removingPercentEncoding, let r = RoomCode.decode(code) {
                player.join(host: r.ip, pw: r.pw, name: "방 \(code.uppercased())")
            }
        } else if let code = v("code"), let r = RoomCode.decode(code) {
            player.join(host: r.ip, pw: r.pw, name: "방 \(code.uppercased())")
        } else if let host = v("host") {
            // 사파리 청취 페이지의 '이음 앱으로 듣기'가 방 이름까지 실어 보낸다.
            player.join(host: host, pw: v("pw") ?? "", name: v("name") ?? "이음")
        }
    }
}

enum Theme {
    static let bg = Color(hex: 0x0D0F14)
    static let surface = Color(hex: 0x171A22)
    static let surfaceAlt = Color(hex: 0x1F2430)
    static let stroke = Color(hex: 0x2A3040)
    static let text = Color(hex: 0xF2F4F8)
    static let dim = Color(hex: 0x8A93A6)
    static let faint = Color(hex: 0x5B6376)
    static let accent = Color(hex: 0x6C5CE7)
    static let live = Color(hex: 0x3DDC97)
    static let danger = Color(hex: 0xFF6B6B)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 255) / 255, green: Double(hex >> 8 & 255) / 255,
                  blue: Double(hex & 255) / 255)
    }
}

struct ContentView: View {
    enum Tab { case listen, share }
    @State private var tab: Tab = .listen

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Text("이음").font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.text)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)

                Picker("", selection: $tab) {
                    Text("듣기").tag(Tab.listen)
                    Text("공유하기").tag(Tab.share)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 20)
                .padding(.vertical, 12)

                switch tab {
                case .listen: ListenView()
                case .share: ShareView()
                }
            }
        }
    }
}

/// 둥근 카드 배경.
struct Card<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(Theme.stroke, lineWidth: 1))
    }
}

struct PrimaryButton: ButtonStyle {
    var color: Color = Theme.accent
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(color.opacity(configuration.isPressed ? 0.75 : 1), in: RoundedRectangle(cornerRadius: 14))
    }
}
