import SwiftUI

/// 듣기 화면. 위에서부터 — 지금 듣는 방 / 같은 Wi-Fi 의 방 / 방 코드로 들어가기.
struct ListenView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var discovery: Discovery

    @State private var code = ""
    @State private var codeError: String?
    /// 잠긴 방을 눌렀을 때 비밀번호를 묻는다.
    @State private var askingPw: FoundRoom?
    @State private var pwInput = ""

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if player.state != .idle || !player.host.isEmpty { nowPlaying }
                roomsCard
                codeCard
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 30)
        }
        .onAppear { discovery.start() }
        .onDisappear { discovery.stop() }
        .alert(L.listenPwTitle, isPresented: Binding(get: { askingPw != nil }, set: { if !$0 { askingPw = nil } })) {
            TextField(L.listenPwPlaceholder, text: $pwInput).keyboardType(.numberPad)
            Button(L.cancel, role: .cancel) { askingPw = nil }
            Button(L.listenEnter) {
                if let r = askingPw { player.join(host: r.ip, pw: pwInput, name: r.name) }
                askingPw = nil
            }
        } message: {
            Text(L.listenPwMessage)
        }
    }

    // MARK: 지금 듣는 방

    private var nowPlaying: some View {
        Card {
            HStack(spacing: 8) {
                Circle().fill(statusColor).frame(width: 8, height: 8)
                Text(statusText).font(.system(size: 13, weight: .medium)).foregroundStyle(statusColor)
                Spacer()
                if player.state == .playing {
                    Text(L.listenBuffer(ms: player.bufferedMs)).font(.system(size: 11)).foregroundStyle(Theme.faint)
                }
            }
            Text(player.roomName.isEmpty ? player.host : player.roomName)
                .font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.text)
            Text(player.host).font(.system(size: 12).monospaced()).foregroundStyle(Theme.dim)

            switch player.state {
            case .wrongPassword, .tooMany:
                HStack {
                    TextField(L.listenPwField, text: $pwInput)
                        .keyboardType(.numberPad)
                        .padding(10)
                        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 10))
                    Button(L.listenRejoin) { player.retry(pw: pwInput) }
                        .buttonStyle(PrimaryButton())
                        .frame(width: 120)
                }
            case .ended:
                // 끝난 방엔 다시 들어갈 수 없다. 호스트가 공유를 다시 켜면 코드·비밀번호가
                // 새로 바뀌므로, 같은 방 카드에서 '다시 시도'를 주면 헛돌기만 한다.
                Text(L.listenEndedHint)
                    .font(.system(size: 13)).foregroundStyle(Theme.dim)
                Button(L.close) { player.leave() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
            case .lost:
                HStack(spacing: 10) {
                    Button(L.listenTryAgain) { player.retry() }.buttonStyle(PrimaryButton())
                    Button(L.close) { player.leave() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
                }
            case .idle:
                HStack(spacing: 10) {
                    Button(L.listenListenAgain) { player.retry() }.buttonStyle(PrimaryButton())
                    Button(L.close) { player.leave() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
                }
            default:
                Button(L.listenLeave) { player.leave() }.buttonStyle(PrimaryButton(color: Theme.danger))
            }
        }
    }

    private var statusText: String {
        switch player.state {
        case .idle: return L.listenStateIdle
        case .connecting(let n): return n == 0 ? L.listenStateConnecting : L.listenStateReconnecting(n: n)
        case .playing: return L.listenStatePlaying
        case .wrongPassword: return L.listenStateWrongPw
        case .tooMany: return L.listenStateTooMany
        case .ended: return L.listenStateEnded
        case .offline: return L.listenStateOffline
        case .lost: return L.listenStateLost
        }
    }

    private var statusColor: Color {
        switch player.state {
        case .playing: return Theme.live
        case .connecting, .idle: return Theme.dim
        default: return Theme.danger
        }
    }

    // MARK: 같은 Wi-Fi 의 방

    private var roomsCard: some View {
        Card {
            HStack {
                Text(L.listenRoomsTitle).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                Spacer()
                if discovery.scanning { ProgressView().scaleEffect(0.7) }
                Button { discovery.scan() } label: { Image(systemName: "arrow.clockwise") }
                    .foregroundStyle(Theme.dim)
            }
            if discovery.rooms.isEmpty {
                Text(discovery.myAddress == nil
                     ? L.listenNoWifi
                     : L.listenNoRooms)
                    .font(.system(size: 13)).foregroundStyle(Theme.dim)
            } else {
                ForEach(discovery.rooms) { room in
                    Button {
                        if room.locked { pwInput = ""; askingPw = room }
                        else { player.join(host: room.ip, pw: "", name: room.name) }
                    } label: {
                        HStack {
                            Image(systemName: "dot.radiowaves.left.and.right").foregroundStyle(Theme.live)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(room.name).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.text)
                                Text(room.ip).font(.system(size: 12).monospaced()).foregroundStyle(Theme.faint)
                            }
                            Spacer()
                            if room.locked { Image(systemName: "lock.fill").foregroundStyle(Theme.dim) }
                            Image(systemName: "chevron.right").foregroundStyle(Theme.faint)
                        }
                        .padding(12)
                        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
                    }
                }
            }
            if let me = discovery.myAddress {
                Text(L.listenMyAddress(ip: me)).font(.system(size: 11)).foregroundStyle(Theme.faint)
            }
        }
    }

    // MARK: 방 코드

    private var codeCard: some View {
        Card {
            Text(L.listenCodeTitle).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
            Text(L.listenCodeLead)
                .font(.system(size: 13)).foregroundStyle(Theme.dim)
            HStack {
                TextField(L.listenCodePlaceholder, text: $code)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(size: 20, weight: .semibold).monospaced())
                    .padding(12)
                    .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
                Button(L.listenEnter) { joinByCode() }
                    .buttonStyle(PrimaryButton())
                    .frame(width: 90)
            }
            if let codeError {
                Text(codeError).font(.system(size: 12)).foregroundStyle(Theme.danger)
            }
        }
    }

    private func joinByCode() {
        guard let r = RoomCode.decode(code) else {
            codeError = L.listenCodeError
            return
        }
        codeError = nil
        pwInput = r.pw
        player.join(host: r.ip, pw: r.pw, name: L.roomFallback(code: code.uppercased()))
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
