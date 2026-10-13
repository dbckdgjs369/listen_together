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
        .alert("비밀번호", isPresented: Binding(get: { askingPw != nil }, set: { if !$0 { askingPw = nil } })) {
            TextField("4자리 숫자", text: $pwInput).keyboardType(.numberPad)
            Button("취소", role: .cancel) { askingPw = nil }
            Button("입장") {
                if let r = askingPw { player.join(host: r.ip, pw: pwInput, name: r.name) }
                askingPw = nil
            }
        } message: {
            Text("호스트 화면에 보이는 4자리 숫자를 넣어 주세요.")
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
                    Text("버퍼 \(player.bufferedMs)ms").font(.system(size: 11)).foregroundStyle(Theme.faint)
                }
            }
            Text(player.roomName.isEmpty ? player.host : player.roomName)
                .font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.text)
            Text(player.host).font(.system(size: 12).monospaced()).foregroundStyle(Theme.dim)

            switch player.state {
            case .wrongPassword, .tooMany:
                HStack {
                    TextField("비밀번호 4자리", text: $pwInput)
                        .keyboardType(.numberPad)
                        .padding(10)
                        .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 10))
                    Button("다시 입장") { player.retry(pw: pwInput) }
                        .buttonStyle(PrimaryButton())
                        .frame(width: 120)
                }
            case .ended:
                // 끝난 방엔 다시 들어갈 수 없다. 호스트가 공유를 다시 켜면 코드·비밀번호가
                // 새로 바뀌므로, 같은 방 카드에서 '다시 시도'를 주면 헛돌기만 한다.
                Text("호스트가 공유를 다시 켜면 아래 목록에 새 방으로 떠요.")
                    .font(.system(size: 13)).foregroundStyle(Theme.dim)
                Button("닫기") { player.leave() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
            case .lost:
                HStack(spacing: 10) {
                    Button("다시 시도") { player.retry() }.buttonStyle(PrimaryButton())
                    Button("닫기") { player.leave() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
                }
            case .idle:
                HStack(spacing: 10) {
                    Button("다시 듣기") { player.retry() }.buttonStyle(PrimaryButton())
                    Button("닫기") { player.leave() }.buttonStyle(PrimaryButton(color: Theme.surfaceAlt))
                }
            default:
                Button("나가기") { player.leave() }.buttonStyle(PrimaryButton(color: Theme.danger))
            }
        }
    }

    private var statusText: String {
        switch player.state {
        case .idle: return "멈춤"
        case .connecting(let n): return n == 0 ? "연결 중…" : "재접속 중… (\(n)회)"
        case .playing: return "듣는 중"
        case .wrongPassword: return "비밀번호가 맞지 않아요"
        case .tooMany: return "시도가 너무 많아요. 잠시 후 다시 해 주세요"
        case .ended: return "호스트가 공유를 종료했어요"
        case .offline: return "Wi-Fi 연결을 확인해 주세요"
        case .lost: return "연결할 수 없어요 — 방이 끝났거나 다른 Wi-Fi 일 수 있어요"
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
                Text("같은 Wi-Fi 의 방").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
                Spacer()
                if discovery.scanning { ProgressView().scaleEffect(0.7) }
                Button { discovery.scan() } label: { Image(systemName: "arrow.clockwise") }
                    .foregroundStyle(Theme.dim)
            }
            if discovery.rooms.isEmpty {
                Text(discovery.myAddress == nil
                     ? "Wi-Fi 에 연결되어 있지 않아요. 호스트와 같은 Wi-Fi 나 호스트의 핫스팟에 연결해 주세요."
                     : "아직 찾은 방이 없어요. 호스트가 공유를 켰는지 확인해 주세요.\n카페 같은 공용 Wi-Fi 는 기기끼리 연결을 막기도 해요 — 그럴 땐 호스트의 '와이파이 없이 연결' 핫스팟을 쓰세요.")
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
                Text("내 주소 \(me)").font(.system(size: 11)).foregroundStyle(Theme.faint)
            }
        }
    }

    // MARK: 방 코드

    private var codeCard: some View {
        Card {
            Text("방 코드로 들어가기").font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.text)
            Text("호스트 화면에 보이는 6자리 코드예요. 목록에 방이 안 보일 때 쓰세요.")
                .font(.system(size: 13)).foregroundStyle(Theme.dim)
            HStack {
                TextField("예: 3F7K2A", text: $code)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .font(.system(size: 20, weight: .semibold).monospaced())
                    .padding(12)
                    .background(Theme.surfaceAlt, in: RoundedRectangle(cornerRadius: 12))
                Button("입장") { joinByCode() }
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
            codeError = "코드를 읽을 수 없어요. 다시 확인해 주세요."
            return
        }
        codeError = nil
        pwInput = r.pw
        player.join(host: r.ip, pw: r.pw, name: "방 \(code.uppercased())")
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
