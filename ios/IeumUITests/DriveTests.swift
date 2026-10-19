import XCTest

/// 기기 조작 대행. 테스트라기보다 **손가락**이다.
///
/// 아이폰 공유는 시스템 '방송 시작' 창을 사람이 눌러야만 켜진다(코드로 못 켠다).
/// 개발 중 매번 사람이 누를 수 없어서, UI 테스트로 그 창과 사파리 재생 버튼을 대신 누른다.
///   xcodebuild test ... -only-testing:IeumUITests/DriveTests/testStartShare
/// 판정(소리가 맞게 나가는지)은 여기서 하지 않는다 — 맥이 /diag 와 /stream 으로 잰다.
final class DriveTests: XCTestCase {

    private let ieum = XCUIApplication(bundleIdentifier: "io.github.dbckdgjs369.ieum")
    private let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() { continueAfterFailure = false }

    /// 공유하기 탭 ▸ 공유 시작 ▸ 시스템 창의 '방송 시작'.
    func testStartShare() {
        openShareTab()
        startShare()
    }

    private func startShare() {
        XCTAssertTrue(ieum.staticTexts["공유 시작"].waitForExistence(timeout: 5), "이미 공유 중이거나 화면이 다르다")
        ieum.staticTexts["공유 시작"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapSystemButton(["방송 시작", "공유 시작", "Start Broadcast"])
        // 시작한 뒤에도 시스템 창이 '방송 중단' 으로 바뀐 채 남는다. 창 바깥(위쪽)을 눌러 닫는다.
        sleep(4)
        ieum.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        // 3초 카운트다운 뒤 확장이 뜨고, 앱이 127.0.0.1/host 로 그걸 알아챈다.
        XCTAssertTrue(ieum.staticTexts["방 코드"].waitForExistence(timeout: 15), "공유 중 화면으로 안 바뀜")
    }

    /// 공유 중이면 끈다. 공유 중 화면엔 시스템 버튼이 없어서 상태 막대의 빨간 표시를 쓴다.
    func testStopShare() {
        openShareTab()
        guard ieum.staticTexts["방 코드"].waitForExistence(timeout: 3) else { return }
        // 화면 기록 중 표시(왼쪽 위 시계 자리)를 누르면 '중단' 확인이 뜬다.
        let clock = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.02))
        clock.tap()
        tapSystemButton(["중단", "Stop"])
        XCTAssertTrue(ieum.staticTexts["공유 시작"].waitForExistence(timeout: 10), "공유가 안 꺼짐")
    }

    /// 앱 안 '공유 중지' 버튼. iOS 가 띄우는 '방송 중지됨' 알림은 확인을 눌러 닫는다.
    func testStopShareButton() {
        openShareTab()
        if !ieum.buttons["공유 중지"].waitForExistence(timeout: 3) { startShare() }
        ieum.buttons["공유 중지"].tap()
        XCTAssertTrue(ieum.staticTexts["공유 시작"].waitForExistence(timeout: 10), "공유가 안 꺼짐")
        sleep(1)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "stop"; shot.lifetime = .keepAlways
        add(shot)
        for app in [ieum, springboard] { for l in ["확인", "OK", "닫기"] where app.buttons[l].exists { app.buttons[l].tap() } }
    }

    /// 공유를 켜고 25초 뒤 앱의 '공유 중지'로 끈다. 그 사이 맥이 청취자를 붙여, 끌 때 청취자가 410 을 받는지 본다.
    /// (테스트를 새로 띄우면 진행 중인 방송이 끊기므로 켜기·끄기를 한 테스트 안에서 한다.)
    func testShareThenStop() {
        openShareTab()
        if ieum.buttons["공유 중지"].waitForExistence(timeout: 3) { ieum.buttons["공유 중지"].tap(); sleep(3) }
        startShare()
        sleep(25)
        ieum.buttons["공유 중지"].tap()
        XCTAssertTrue(ieum.staticTexts["공유 시작"].waitForExistence(timeout: 10), "공유가 안 꺼짐")
    }

    /// 듣기 카드의 나가기·닫기를 누르면 카드가 사라져야 한다.
    func testCloseRoom() {
        ieum.activate()
        let tab = ieum.buttons["듣기"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        let field = ieum.textFields["예: 3F7K2A"]
        field.tap(); field.typeText("ZZZZ")
        ieum.buttons["입장"].tap()
        XCTAssertTrue(ieum.staticTexts["방 ZZZZ"].waitForExistence(timeout: 5), "카드가 안 뜸")
        for l in ["나가기", "닫기"] where ieum.buttons[l].exists { ieum.buttons[l].tap(); break }
        sleep(1)
        XCTAssertFalse(ieum.staticTexts["방 ZZZZ"].exists, "닫았는데 카드가 남아 있음")
    }

    /// 공유 중 화면에서 초대 QR 창을 띄워 화면을 남긴다(맥이 결과 번들에서 꺼내 QR 을 읽어 본다).
    /// 테스트 실행을 새로 띄우면 앱이 다시 뜨면서 방송이 끊기므로, 여기서 공유부터 켠다.
    func testShowQR() {
        openShareTab()
        if !ieum.buttons["QR 보기"].waitForExistence(timeout: 3) { startShare() }
        XCTAssertTrue(ieum.buttons["QR 보기"].waitForExistence(timeout: 5), "공유 중이 아니다")
        ieum.buttons["QR 보기"].tap()
        XCTAssertTrue(ieum.staticTexts["QR 로 들어오기"].waitForExistence(timeout: 5), "QR 창이 안 뜸")
        sleep(1)
        let shot = XCTAttachment(screenshot: ieum.screenshot())
        shot.name = "qr"; shot.lifetime = .keepAlways
        add(shot)
        ieum.buttons["닫기"].tap()
    }

    /// 초대 링크를 시스템에 열게 한다(카메라·메시지에서 누른 것과 같은 길). 유니버설 링크가 살아 있으면 이음이,
    /// 아니면 사파리가 앞에 온다. 어느 쪽인지 화면을 남긴다.
    func testOpenInviteLink() {
        let url = ProcessInfo.processInfo.environment["LINK"] ?? "https://dbckdgjs369.github.io/listen_together/#UUUU"  // U 는 코드에 없는 글자라 방에 들어가지 않는다
        XCUIDevice.shared.system.open(URL(string: url)!)
        sleep(4)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "link"; shot.lifetime = .keepAlways
        add(shot)
        XCTAssertEqual(ieum.state, .runningForeground, "이음이 아니라 다른 앱(사파리)이 열림")
    }

    /// 볼륨 실험: 최대 15초 → 0 15초 → 한 칸 15초. 공유는 켜 둔 상태에서 돌리고, 맥이 /diag 로 소리 크기를 잰다.
    /// 구간이 바뀔 때마다 시각을 출력한다(맥 기록과 맞춰 본다).
    func testVolumeSweep() {
        func press(_ b: XCUIDevice.Button, _ n: Int) { for _ in 0..<n { XCUIDevice.shared.press(b); usleep(150_000) } }
        func stamp(_ s: String) { print("VOL \(s) @\(Int(Date().timeIntervalSince1970))"); mark("vol-" + s) }
        press(.volumeUp, 18); stamp("max"); sleep(15)
        press(.volumeDown, 18); stamp("zero"); sleep(15)
        press(.volumeUp, 1); stamp("one"); sleep(15)
        stamp("end")
    }

    /// 화면을 남기며 이름에 시각을 붙인다.
    private func mark(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = "\(name)@\(Int(Date().timeIntervalSince1970))"; a.lifetime = .keepAlways
        add(a)
    }

    /// 사파리에 이미 떠 있는 페이지의 '재생' 버튼을 누른다(맥이 devicectl 로 띄워 둔다).
    func testTapPlayInSafari() {
        safari.activate()
        let play = safari.buttons["재생"]
        XCTAssertTrue(play.waitForExistence(timeout: 10), "재생 버튼 없음")
        play.tap()
        sleep(2)
    }

    private func openShareTab() {
        ieum.activate()
        let tab = ieum.buttons["공유하기"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
    }

    /// 시스템 창의 버튼은 앱 안(원격 뷰)에 잡히기도, 스프링보드에 잡히기도 한다. 둘 다 본다.
    private func tapSystemButton(_ labels: [String]) {
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            for app in [ieum, springboard] {
                for l in labels where app.buttons[l].exists {
                    app.buttons[l].tap(); return
                }
            }
            usleep(300_000)
        }
        let seen = [ieum, springboard].flatMap { $0.buttons.allElementsBoundByIndex.map { "\($0.label)@\($0.frame)" } }
        XCTFail("시스템 버튼 \(labels) 을 못 찾음. 보이는 버튼: \(seen)")
    }
}
