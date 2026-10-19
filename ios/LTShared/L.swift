// 자동 생성 — i18n/*.json 을 고치고 `npm run i18n` 을 돌린다. 직접 고치지 않는다.
import Foundation

/// 화면 문구. 키가 빠지거나 빈칸이 바뀌면 컴파일에서 걸린다 — 문자열 키를 손으로 치지 않는다.
/// 주석은 영어 원문이다.
enum L {
    /// IEUM
    static var appName: String { tr("app_name") }
    /// Cancel
    static var cancel: String { tr("cancel") }
    /// Close
    static var close: String { tr("close") }
    /// Room {code}
    static func roomFallback(code: Any) -> String { tr("room_fallback", code) }
    /// Unnamed room
    static var roomUnnamed: String { tr("room_unnamed") }
    /// Listen
    static var tabListen: String { tr("tab_listen") }
    /// Share
    static var tabShare: String { tr("tab_share") }
    /// Password
    static var listenPwTitle: String { tr("listen_pw_title") }
    /// 4 digits
    static var listenPwPlaceholder: String { tr("listen_pw_placeholder") }
    /// Enter the 4-digit number shown on the host's screen.
    static var listenPwMessage: String { tr("listen_pw_message") }
    /// Join
    static var listenEnter: String { tr("listen_enter") }
    /// Buffer {ms}ms
    static func listenBuffer(ms: Any) -> String { tr("listen_buffer", ms) }
    /// 4-digit password
    static var listenPwField: String { tr("listen_pw_field") }
    /// Join again
    static var listenRejoin: String { tr("listen_rejoin") }
    /// When the host starts sharing again, it shows up in the list below as a new room.
    static var listenEndedHint: String { tr("listen_ended_hint") }
    /// Try again
    static var listenTryAgain: String { tr("listen_try_again") }
    /// Listen again
    static var listenListenAgain: String { tr("listen_listen_again") }
    /// Leave
    static var listenLeave: String { tr("listen_leave") }
    /// Stopped
    static var listenStateIdle: String { tr("listen_state_idle") }
    /// Connecting…
    static var listenStateConnecting: String { tr("listen_state_connecting") }
    /// Reconnecting… (attempt {n})
    static func listenStateReconnecting(n: Any) -> String { tr("listen_state_reconnecting", n) }
    /// Listening
    static var listenStatePlaying: String { tr("listen_state_playing") }
    /// Wrong password
    static var listenStateWrongPw: String { tr("listen_state_wrong_pw") }
    /// Too many attempts. Please try again later
    static var listenStateTooMany: String { tr("listen_state_too_many") }
    /// The host stopped sharing
    static var listenStateEnded: String { tr("listen_state_ended") }
    /// Check your Wi-Fi connection
    static var listenStateOffline: String { tr("listen_state_offline") }
    /// Can't connect — the room may have ended, or you may be on a different Wi-Fi
    static var listenStateLost: String { tr("listen_state_lost") }
    /// Rooms on this Wi-Fi
    static var listenRoomsTitle: String { tr("listen_rooms_title") }
    /// Not connected to Wi-Fi. Connect to the same Wi-Fi as the host, or to the host's hotspot.
    static var listenNoWifi: String { tr("listen_no_wifi") }
    /// No rooms found yet. Check that the host has started sharing. Public Wi-Fi in places like cafés can block devices from reaching each other — if so, use the host's ‘Connect without Wi-Fi’ hotspot.
    static var listenNoRooms: String { tr("listen_no_rooms") }
    /// My address {ip}
    static func listenMyAddress(ip: Any) -> String { tr("listen_my_address", ip) }
    /// Join with a room code
    static var listenCodeTitle: String { tr("listen_code_title") }
    /// It's the code shown on the host's screen. Use it when the room doesn't appear in the list.
    static var listenCodeLead: String { tr("listen_code_lead") }
    /// e.g. 3F7K2A
    static var listenCodePlaceholder: String { tr("listen_code_placeholder") }
    /// Can't read this code. Please check it again.
    static var listenCodeError: String { tr("listen_code_error") }
    /// IEUM · Listening together
    static var nowPlayingArtist: String { tr("now_playing_artist") }
    /// Sharing
    static var lockSharing: String { tr("lock_sharing") }
    /// Listeners: {n}
    static func lockListeners(n: Any) -> String { tr("lock_listeners", n) }
    /// Press and hold to unlock
    static var lockHint: String { tr("lock_hint") }
    /// Share my iPhone's sound
    static var shareTitle: String { tr("share_title") }
    /// Room name
    static var shareNamePlaceholder: String { tr("share_name_placeholder") }
    /// You're listening to another room. Starting to share will stop listening.
    static var shareListeningWarn: String { tr("share_listening_warn") }
    /// Start sharing
    static var shareStart: String { tr("share_start") }
    /// In the window that opens, tap ‘Start Broadcast’. Sharing starts after 3 seconds.
    static var shareStartHint: String { tr("share_start_hint") }
    /// Sharing · Listeners: {n}
    static func shareLive(n: Any) -> String { tr("share_live", n) }
    /// Room code
    static var shareCodeLabel: String { tr("share_code_label") }
    /// Password {pw}
    static func sharePw(pw: Any) -> String { tr("share_pw", pw) }
    /// Copied
    static var shareCopied: String { tr("share_copied") }
    /// Copy invite link
    static var shareCopyLink: String { tr("share_copy_link") }
    /// Show QR
    static var shareShowQr: String { tr("share_show_qr") }
    /// Lock screen
    static var shareLock: String { tr("share_lock") }
    /// Stop sharing
    static var shareStop: String { tr("share_stop") }
    /// Friends can enter the code under ‘Room code’ in the IEUM app, or in a browser at {site}.
    static func shareFriendHint(site: Any) -> String { tr("share_friend_hint", site) }
    /// Sharing stops if your iPhone locks. When you put your phone down, tap ‘Lock screen’. It goes dark as if it were off, but sharing keeps going.
    static var shareLockHint: String { tr("share_lock_hint") }
    /// Good to know
    static var shareTipsTitle: String { tr("share_tips_title") }
    /// Start playing in your music app first so friends hear it right away.
    static var shareTipPlayFirst: String { tr("share_tip_play_first") }
    /// If the screen locks, iPhone ends the broadcast. While sharing, use IEUM's ‘Lock screen’ instead of the side button.
    static var shareTipLock: String { tr("share_tip_lock") }
    /// Your screen is never sent. iPhone only provides other apps' sound through ‘Screen Broadcast’, which is why the recording indicator appears.
    static var shareTipNoScreen: String { tr("share_tip_no_screen") }
    /// Some apps, like Apple Music, may not be captured because of copy protection.
    static var shareTipDrm: String { tr("share_tip_drm") }
    /// Friends need to be on the same Wi-Fi as you, or connected to your Personal Hotspot.
    static var shareTipWifi: String { tr("share_tip_wifi") }
    /// Join with QR
    static var qrTitle: String { tr("qr_title") }
    /// Scan with the camera on a phone that's on the same Wi-Fi to join right away
    static var qrLead: String { tr("qr_lead") }
    /// Room code {code}  ·  Password {pw}
    static func qrFoot(code: Any, pw: Any) -> String { tr("qr_foot", code, pw) }
    /// Sharing stopped.
    static var extStopped: String { tr("ext_stopped") }
    /// iPhone's room
    static var extDefaultRoom: String { tr("ext_default_room") }

    private static func tr(_ key: String, _ args: Any...) -> String {
        let format = NSLocalizedString(key, comment: "")
        return args.isEmpty ? format : String(format: format, arguments: args.map { String(describing: $0) })
    }
}
