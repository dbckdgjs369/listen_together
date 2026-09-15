import UIKit
import ReplayKit
import Darwin
import os

/// 호스트 화면. 하는 일은 셋뿐이다.
///  1. 로컬 네트워크 권한을 **미리** 띄운다
///  2. 방송 시작 버튼(시스템 피커)을 보여준다
///  3. 친구에게 불러줄 주소를 보여준다
///
/// 실제 캡처·서버는 전부 Broadcast Extension 안에서 돈다. 이 화면은 꺼져도 된다.
final class HostViewController: UIViewController {

    private let title1 = UILabel()
    private let addressLabel = UILabel()
    private let hint = UILabel()
    private var picker: RPSystemBroadcastPickerView!

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        buildUI()
        triggerLocalNetworkPermission()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshAddress()
    }

    // MARK: - 화면

    private func buildUI() {
        title1.text = "같이 듣기"
        title1.font = .systemFont(ofSize: 32, weight: .bold)
        title1.textAlignment = .center

        addressLabel.numberOfLines = 0
        addressLabel.textAlignment = .center
        addressLabel.font = .monospacedSystemFont(ofSize: 18, weight: .medium)

        hint.numberOfLines = 0
        hint.textAlignment = .center
        hint.textColor = .secondaryLabel
        hint.font = .systemFont(ofSize: 14)
        hint.text = """
        음악 앱을 먼저 재생한 뒤 방송을 시작하세요.
        친구는 브라우저에서 위 주소를 열면 됩니다.
        """

        // 시스템 피커. 버튼 모양을 우리가 못 바꾸는 대신, 이 경로로만 방송을 켤 수 있다.
        picker = RPSystemBroadcastPickerView(
            frame: CGRect(x: 0, y: 0, width: 80, height: 80))
        picker.preferredExtension = "com.example.lt.broadcast"
        // 마이크는 쓰지 않는다. 우리가 필요한 건 "앱 소리"다.
        picker.showsMicrophoneButton = false

        let stack = UIStackView(arrangedSubviews: [title1, addressLabel, picker, hint])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)

        NSLayoutConstraint.activate([
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
        ])
    }

    private func refreshAddress() {
        let all = Net.interfaces()
        if all.isEmpty {
            addressLabel.text = "네트워크 없음\nWi-Fi 를 켜세요"
            return
        }
        // 인터페이스를 하나만 고르지 않고 전부 보여준다. 핫스팟을 켜면 어느 쪽이
        // 참여자에게 닿는 주소인지 기기가 알 수 없다 — 사람이 고르는 게 정확하다.
        addressLabel.text = all
            .map { "http://\($0.address):\(LTPort.stream)" }
            .joined(separator: "\n")
    }

    // MARK: - 로컬 네트워크 권한

    /// iOS 14+ 는 로컬 네트워크 접근에 사용자 동의를 요구한다.
    /// **확장 안에서는 이 권한 창을 띄울 방법이 마땅치 않다.** 그래서 앱 본체가
    /// 먼저 로컬 주소로 한 번 쏘아 동의 창을 유발한다. 권한은 앱 단위라 확장도
    /// 같이 혜택을 본다 — 다만 이 가정 자체가 검증 대상이다.
    private func triggerLocalNetworkPermission() {
        DispatchQueue.global().async {
            let fd = socket(AF_INET, SOCK_DGRAM, 0)
            guard fd >= 0 else { return }
            var yes: Int32 = 1
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes,
                       socklen_t(MemoryLayout<Int32>.size))
            var addr = sockaddr_in()
            addr.sin_family = sa_family_t(AF_INET)
            addr.sin_port = LTPort.beacon.bigEndian
            addr.sin_addr.s_addr = inet_addr("255.255.255.255")
            let msg = Data("LT0|probe".utf8)
            msg.withUnsafeBytes { raw in
                _ = withUnsafePointer(to: &addr) { ap in
                    ap.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
                        sendto(fd, raw.baseAddress, raw.count, 0,
                               sa, socklen_t(MemoryLayout<sockaddr_in>.size))
                    }
                }
            }
            Darwin.close(fd)
            os_log("로컬 네트워크 권한 유발 probe 전송", log: log, type: .info)
        }
    }
}
