import CoreImage.CIFilterBuiltins
import ReplayKit
import SwiftUI

/// 공유하기 화면. 아이폰이 호스트가 된다.
///
/// 실제 캡처·서버는 Broadcast Upload Extension(별도 프로세스)이 한다. iOS 는 다른 앱의
/// 소리를 일반 앱에 주지 않고, '화면 방송' 확장에만 준다 — 그래서 시작은 반드시
/// 시스템 방송 버튼으로 해야 하고, 화면 위쪽에 빨간 녹화 표시가 뜬다(화면은 버린다).
///
/// 이 화면은 확장이 연 서버에 127.0.0.1 로 물어서 방 코드·청취자 수를 보여준다.
/// 앱과 확장이 값을 나눌 다른 길(App Group)은 서명 설정이 늘어서 피했다.
struct ShareView: View {
    @EnvironmentObject private var player: Player

    struct HostInfo: Equatable {
        var name: String
        var pw: String
        var addrs: [String]
        var listeners: Int
    }

    @State private var info: HostInfo?
    @AppStorage("roomName") private var roomName = ""
    @State private var timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    @State private var copied = false
    @State private var showQR = false
    @State private var locked = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if let info { liveCard(info) } else { startCard }
                howCard
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 30)
        }
        .onAppear(perform: poll)
        .onReceive(timer) { _ in poll() }
        // 화면이 잠기면 iOS 가 화면 방송을 끊는다 — 공유도 같이 꺼진다(실측). 이 화면을
        // 띄워 둔 동안이라도 자동 잠금을 막는다. 다른 앱으로 가면 iOS 가 다시 잠근다.
        .onChange(of: info != nil) { live in
            UIApplication.shared.isIdleTimerDisabled = live
            if !live { locked = false }
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
        .fullScreenCover(isPresented: $locked) {
            ShareLockView(listeners: info?.listeners ?? 0) { locked = false }
        }
    }

    private var startCard: some View {
        Card {
            Text("내 아이폰 소리 공유하기").font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.text)
            TextField("방 이름 (예: 창헌의 방)", text: $roomName)
                .padding(12)
                .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
            if player.state != .idle {
                Text("지금 다른 방을 듣고 있어요. 공유를 시작하면 듣기는 멈춰요.")
                    .font(.system(size: 12)).foregroundStyle(Theme.danger)
            }
            ZStack {
                RoundedRectangle(cornerRadius: 14).fill(Theme.accent)
                HStack(spacing: 8) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                    Text("공유 시작").font(.system(size: 16, weight: .semibold))
                }
                .foregroundStyle(.white)
                .allowsHitTesting(false)
                // 시스템 방송 버튼을 투명하게 깔아 둔다. 이 버튼만이 방송을 켤 수 있다.
                BroadcastPicker()
                    .opacity(0.02)
                    .simultaneousGesture(TapGesture().onEnded { if player.state != .idle { player.leave() } })
            }
            .frame(height: 52)
            Text("누르면 나오는 창에서 '방송 시작'을 누르세요. 3초 뒤 시작돼요.")
                .font(.system(size: 12)).foregroundStyle(Theme.faint)
        }
    }

    private func liveCard(_ info: HostInfo) -> some View {
        let ip = info.addrs.first ?? ""
        let code = RoomCode.encode(ip: ip, pw: info.pw) ?? "—"
        return Card {
            HStack(spacing: 8) {
                Circle().fill(Theme.live).frame(width: 8, height: 8)
                Text("공유 중 · 듣는 사람 \(info.listeners)명")
                    .font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.live)
            }
            Text(info.name).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.text)

            VStack(alignment: .leading, spacing: 4) {
                Text("방 코드").font(.system(size: 12)).foregroundStyle(Theme.dim)
                Text(code).font(.system(size: 40, weight: .heavy).monospaced()).foregroundStyle(Theme.text)
                    .textSelection(.enabled)
                Text("비밀번호 \(info.pw)").font(.system(size: 15, weight: .semibold).monospaced())
                    .foregroundStyle(Theme.dim)
            }
            .padding(.vertical, 4)

            HStack(spacing: 10) {
                Button(copied ? "복사했어요" : "초대 링크 복사") {
                    UIPasteboard.general.string = RoomCodeSite.link(code: code)
                    copied = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) { copied = false }
                }
                .buttonStyle(PrimaryButton())
                Button("QR 보기") { showQR = true }
                    .buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
            }
            .sheet(isPresented: $showQR) { QRSheet(code: code, pw: info.pw) }
            // 진짜 잠금은 방송을 끊으므로, 어둡게 덮는 이음 잠금을 쓴다(ShareLockView).
            Button {
                locked = true
            } label: {
                Label("화면 잠금", systemImage: "lock.fill")
            }
            .buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
            // 방송은 확장만 끝낼 수 있다. 시스템 방송 버튼은 공유 중엔 눌러도 창이 안 떠서(실측),
            // 확장 서버에 /stop 을 보내 스스로 끝내게 한다.
            Button("공유 중지") {
                MiniHTTP.get(host: "127.0.0.1", path: "/stop", timeout: 1) { _ in
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { poll() }
                }
            }
            .buttonStyle(PrimaryButton(color: Theme.danger))

            Text("친구는 이음 앱의 '방 코드'에 넣거나, 브라우저로 \(RoomCodeSite.url) 에서 코드를 넣으면 돼요.")
                .font(.system(size: 12)).foregroundStyle(Theme.dim)
            ForEach(info.addrs, id: \.self) { a in
                Text(verbatim: "http://\(a):\(LTPort.stream)/?pw=\(info.pw)")
                    .font(.system(size: 11).monospaced()).foregroundStyle(Theme.faint)
                    .textSelection(.enabled)
            }
            Text("아이폰 화면이 잠기면 공유가 꺼져요. 폰을 내려 둘 땐 '화면 잠금'을 눌러 주세요. 어둡게 꺼진 것처럼 보이지만 공유는 계속돼요.")
                .font(.system(size: 12)).foregroundStyle(Theme.faint)
        }
    }

    private var howCard: some View {
        Card {
            Text("알아 두세요").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
            VStack(alignment: .leading, spacing: 6) {
                bullet("음악 앱을 먼저 재생해 두면 바로 들려요.")
                bullet("화면이 잠기면 아이폰이 방송을 끊어요. 공유하는 동안은 측면 버튼 대신 이음의 '화면 잠금'을 쓰세요.")
                bullet("화면은 보내지 않아요. 아이폰이 '화면 방송'을 통해서만 다른 앱 소리를 내주기 때문에 녹화 표시가 떠요.")
                bullet("Apple Music 등 일부 앱은 저작권 보호로 소리가 안 잡힐 수 있어요.")
                bullet("친구와 같은 Wi-Fi 이거나, 친구가 내 개인용 핫스팟에 연결해야 해요.")
            }
        }
    }

    private func bullet(_ t: String) -> some View {
        HStack(alignment: .top, spacing: 6) {
            Text("•").foregroundStyle(Theme.faint)
            Text(t).font(.system(size: 13)).foregroundStyle(Theme.dim)
        }
    }

    /// 확장이 떠 있으면 127.0.0.1:7980/host 가 답한다. 답이 없으면 공유 중이 아니다.
    private func poll() {
        let name = roomName.trimmingCharacters(in: .whitespaces)
        let q = name.isEmpty ? "" : "?name=" + (name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "")
        MiniHTTP.get(host: "127.0.0.1", path: "/host" + q, timeout: 0.8) { r in
            var next: HostInfo?
            if let r, r.status == 200,
               let j = try? JSONSerialization.jsonObject(with: r.body) as? [String: Any] {
                next = HostInfo(name: j["name"] as? String ?? "",
                                pw: j["pw"] as? String ?? "",
                                addrs: j["addrs"] as? [String] ?? [],
                                listeners: j["listeners"] as? Int ?? 0)
            }
            DispatchQueue.main.async { if info != next { info = next } }
        }
    }
}

/// 친구 폰 카메라로 찍게 하는 초대 QR. 내용은 '초대 링크 복사'와 같은 `#코드` 링크다(안드로이드와 같다).
struct QRSheet: View {
    let code: String
    let pw: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Text("QR 로 들어오기").font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.text)
            Text("같은 Wi-Fi 에 있는 폰 카메라로 찍으면 바로 들어와요")
                .font(.system(size: 13)).foregroundStyle(Theme.dim).multilineTextAlignment(.center)
            if let img = Self.image(RoomCodeSite.link(code: code)) {
                // 흰 바탕·여백이 있어야 카메라가 잘 읽는다. 어두운 테마 위에 그대로 두면 못 읽는 폰이 있다.
                Image(uiImage: img).interpolation(.none).resizable().scaledToFit()
                    .padding(14).background(.white, in: RoundedRectangle(cornerRadius: 16))
                    .frame(maxWidth: 280)
            }
            Text("방 코드 \(code)  ·  비밀번호 \(pw)")
                .font(.system(size: 14, weight: .semibold).monospaced()).foregroundStyle(Theme.dim)
            Button("닫기") { dismiss() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg)
        .presentationDetents([.medium, .large])
    }

    static func image(_ text: String) -> UIImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let out = f.outputImage,
              let cg = CIContext().createCGImage(out, from: out.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

enum RoomCodeSite {
    static let url = "dbckdgjs369.github.io/listen_together"
    static func link(code: String) -> String { "https://\(url)/#\(code)" }
}

/// 시스템 방송 버튼. 모양은 못 바꾸므로 위에 우리 버튼을 그리고 이걸 투명하게 덮는다.
struct BroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let v = RPSystemBroadcastPickerView(frame: .zero)
        // project.yml 의 확장 번들 ID 와 같아야 한다. 다르면 목록에 우리 확장이 안 뜬다.
        v.preferredExtension = (Bundle.main.bundleIdentifier ?? "") + ".broadcast"
        v.showsMicrophoneButton = false
        // 버튼이 작게 그려지므로 영역 전체를 누를 수 있게 키운다.
        for sub in v.subviews {
            if let b = sub as? UIButton {
                b.frame = CGRect(x: 0, y: 0, width: 600, height: 52)
                b.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            }
        }
        return v
    }
    func updateUIView(_ v: RPSystemBroadcastPickerView, context: Context) {
        for sub in v.subviews { (sub as? UIButton)?.frame = v.bounds }
    }
}
