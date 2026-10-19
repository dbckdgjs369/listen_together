import Combine
import SwiftUI

/// 공유 중 '이음 잠금' 화면.
///
/// 아이폰이 잠기면 iOS 가 화면 방송을 끊는다(공유도 끝난다). 그래서 진짜 잠금 대신 이 화면을 띄운다.
/// - 자동 잠금을 막고, 밝기를 최저로 내려 배터리를 아낀다(검은 화면이라 OLED 는 거의 꺼진 것과 같다).
/// - 다른 탭·버튼을 덮어 앱 안에서 다른 화면으로 못 간다. 홈 제스처는 두 번 쓸어야 먹게 미룬다
///   (완전히 막는 건 iOS 가 허락하지 않는다).
/// - 해제는 가운데 버튼을 길게 눌러야만 된다. 주머니 속 터치로 풀리지 않게.
/// - 화면 아무 데나 누르면 몇 초간 조금 밝아져 버튼이 보인다.
struct ShareLockView: View {
    let listeners: Int
    let onUnlock: () -> Void

    @Environment(\.scenePhase) private var scenePhase
    @State private var savedBrightness: CGFloat?
    @State private var awake = false
    @State private var awakeUntil = Date.distantPast
    @State private var holding = false
    @State private var offset = CGSize.zero

    private static let holdSeconds = 1.5
    private static let dimLevel: CGFloat = 0.0
    private static let awakeLevel: CGFloat = 0.3
    private static let awakeSeconds: TimeInterval = 5
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { wake() }

            VStack(spacing: 28) {
                VStack(spacing: 6) {
                    HStack(spacing: 8) {
                        Circle().fill(Theme.live).frame(width: 8, height: 8)
                        Text("공유 중").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.live)
                    }
                    Text("듣는 사람 \(listeners)명").font(.system(size: 13)).foregroundStyle(Theme.faint)
                }
                unlockButton
                Text("길게 눌러 잠금 해제").font(.system(size: 12)).foregroundStyle(Theme.faint)
            }
            // 같은 자리에 오래 켜 두면 화면에 자국이 남을 수 있어 조금씩 옮긴다.
            .offset(offset)
            .opacity(awake ? 1 : 0.55)
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .defersSystemGestures(on: .all)
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            savedBrightness = UIScreen.main.brightness
            dim()
        }
        .onDisappear { restore() }
        // 밝기는 앱을 떠나도 그대로 남는다. 나갈 땐 돌려 두고, 돌아오면 다시 낮춘다.
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                UIApplication.shared.isIdleTimerDisabled = true
                savedBrightness = UIScreen.main.brightness
                dim()
            } else {
                restore()
            }
        }
        .onReceive(tick) { now in
            if awake && !holding && now > awakeUntil { dim() }
            if Int(now.timeIntervalSince1970) % 30 == 0 {
                withAnimation(.easeInOut(duration: 2)) {
                    offset = CGSize(width: .random(in: -40...40), height: .random(in: -80...80))
                }
            }
        }
    }

    private var unlockButton: some View {
        ZStack {
            Circle().stroke(Theme.stroke, lineWidth: 3)
            Circle()
                .trim(from: 0, to: holding ? 1 : 0)
                .stroke(Theme.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .animation(holding ? .linear(duration: Self.holdSeconds) : .easeOut(duration: 0.2), value: holding)
            Image(systemName: holding ? "lock.open.fill" : "lock.fill")
                .font(.system(size: 26))
                .foregroundStyle(Theme.text)
        }
        .frame(width: 84, height: 84)
        .contentShape(Circle())
        .onLongPressGesture(minimumDuration: Self.holdSeconds) {
            onUnlock()
        } onPressingChanged: { pressing in
            holding = pressing
            if pressing { wake() }
        }
    }

    private func wake() {
        awake = true
        awakeUntil = Date().addingTimeInterval(Self.awakeSeconds)
        UIScreen.main.brightness = max(savedBrightness.map { min($0, Self.awakeLevel) } ?? Self.awakeLevel, 0.1)
    }

    private func dim() {
        awake = false
        UIScreen.main.brightness = Self.dimLevel
    }

    private func restore() {
        if let b = savedBrightness { UIScreen.main.brightness = b }
        savedBrightness = nil
    }
}
