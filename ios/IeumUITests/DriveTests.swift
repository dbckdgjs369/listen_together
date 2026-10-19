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

    /// 언어별 화면을 남긴다(다국어 확인용). 기기 언어와 상관없이 앱만 그 언어로 띄운다.
    /// 결과 묶음(.xcresult)의 첨부 파일에 en-listen, ko-share … 이 쌓인다.
    func testLocalizedScreens() {
        for lang in ["en", "ko"] {
            ieum.terminate()
            ieum.launchArguments = ["-AppleLanguages", "(\(lang))", "-AppleLocale", lang]
            ieum.launch()
            sleep(2); mark("\(lang)-listen")
            ieum.segmentedControls.buttons.element(boundBy: 1).tap()
            sleep(1); mark("\(lang)-share")
            ieum.swipeUp()
            sleep(1); mark("\(lang)-share-tips")
        }
        ieum.terminate()
        ieum.launchArguments = []
    }

    /// 영어로 띄워 공유 ▸ 공유 중 화면 ▸ 화면 잠금 ▸ 해제 ▸ 청취 페이지 언어별 응답 ▸ 공유 중지.
    /// 청취 페이지는 같은 폰의 127.0.0.1 로 Accept-Language 만 바꿔 받아 본다(맥과 망이 달라도 된다).
    func testShareLocalized() {
        ieum.terminate()
        ieum.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en"]
        ieum.launch()
        defer { ieum.launchArguments = [] }
        ieum.segmentedControls.buttons.element(boundBy: 1).tap()
        let start = ieum.staticTexts["Start sharing"]
        XCTAssertTrue(start.waitForExistence(timeout: 5), "이미 공유 중이거나 화면이 다르다")
        start.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        tapSystemButton(["방송 시작", "공유 시작", "Start Broadcast"])
        sleep(4)
        ieum.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
        XCTAssertTrue(ieum.staticTexts["Room code"].waitForExistence(timeout: 15), "공유 중 화면으로 안 바뀜")
        sleep(1); mark("en-live")
        ieum.swipeUp()
        sleep(1); mark("en-live-bottom")

        ieum.buttons["Lock screen"].tap()
        let hint = ieum.staticTexts["Press and hold to unlock"]
        XCTAssertTrue(hint.waitForExistence(timeout: 5), "잠금 화면이 안 뜸")
        ieum.tap()   // 살짝 밝혀서 보이게
        sleep(1); mark("en-lock")
        // 해제 버튼은 안내 글 바로 위에 있다.
        hint.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0)).withOffset(CGVector(dx: 0, dy: -70))
            .press(forDuration: 2.5)
        XCTAssertTrue(ieum.buttons["Stop sharing"].waitForExistence(timeout: 5), "잠금이 안 풀림")

        var report = ""
        for (lang, expect) in [("en-US,en;q=0.9", "You&#39;re invited to"), ("ko-KR,ko;q=0.9", "초대받은 방"),
                               ("ja-JP", "You&#39;re invited to")] {
            let html = fetch("http://127.0.0.1:7980/", lang: lang) ?? ""
            let ok = html.contains(expect) && html.contains("var T={")
            report += "\(ok ? "OK  " : "FAIL") [\(lang)] \(expect)\n"
            XCTAssertTrue(ok, "청취 페이지 언어가 틀림: \(lang)")
        }
        for (i, lang) in ["en", "ko"].enumerated() {
            if i > 0 { sleep(2) }   // 연속 실패는 잠시 막히므로(429) 사이를 둔다
            report += "[\(lang)] /check 틀린 비밀번호 → \(fetch("http://127.0.0.1:7980/check?pw=0000", lang: lang) ?? "nil")\n"
        }
        print(report)
        let a = XCTAttachment(string: report); a.name = "page-report"; a.lifetime = .keepAlways; add(a)

        ieum.buttons["Stop sharing"].tap()
        XCTAssertTrue(ieum.staticTexts["Start sharing"].waitForExistence(timeout: 10), "공유가 안 꺼짐")
        sleep(1); mark("en-stopped")
        for app in [ieum, springboard] { for l in ["확인", "OK", "닫기"] where app.buttons[l].exists { app.buttons[l].tap() } }
    }

    /// 같은 폰의 서버에 Accept-Language 를 붙여 GET. 본문을 돌려준다.
    private func fetch(_ url: String, lang: String) -> String? {
        var req = URLRequest(url: URL(string: url)!, timeoutInterval: 5)
        req.setValue(lang, forHTTPHeaderField: "Accept-Language")
        let done = DispatchSemaphore(value: 0)
        var body: String?
        URLSession.shared.dataTask(with: req) { d, _, _ in
            body = d.flatMap { String(data: $0, encoding: .utf8) }
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 6)
        return body
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
