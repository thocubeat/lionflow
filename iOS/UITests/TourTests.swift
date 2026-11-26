import UIKit
import XCTest

/// A walk through the whole app, the way a person would use it: every tab from top to bottom, the sheets and
/// editors, commands to the sample car, the first screens. It runs in the simulator in demo mode (a sample car, no
/// account), fails when a screen doesn't open or the app stops, and takes a picture at every step.
final class TourTests: XCTestCase {
    private var app = XCUIApplication()
    private static var count = 0

    override func setUp() {
        super.setUp()
        continueAfterFailure = true
        app = XCUIApplication()
    }

    // MARK: Helpers

    private func launch(_ arguments: String...) {
        app.terminate()
        app.launchArguments = arguments
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "the app did not start with \(arguments)")
    }

    /// The sample car on one tab. Waits until the screen is really there.
    private func demo(_ tab: String, kind: String = "electric", _ more: String...) {
        app.terminate()
        app.launchArguments = ["-demo", kind, "-tab", tab, "-clean"] + more
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "the app did not start on \(tab)")
        Thread.sleep(forTimeInterval: 2.5)
    }

    /** A picture of the screen, kept with the test and written to the folder the workflow asked for. */
    private func snap(_ name: String) {
        Self.count += 1
        let shot = XCUIScreen.main.screenshot()
        let title = String(format: "tour-%03d-%@", Self.count, name)
        let attachment = XCTAttachment(screenshot: shot)
        attachment.name = title
        attachment.lifetime = .keepAlways
        add(attachment)
        if let folder = ProcessInfo.processInfo.environment["TOUR_OUT"], !folder.isEmpty {
            try? FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
            try? shot.pngRepresentation.write(to: URL(fileURLWithPath: folder).appendingPathComponent(title + ".png"))
        }
    }

    private func alive(_ after: String) {
        XCTAssertEqual(app.state, .runningForeground, "the app stopped after: \(after)")
    }

    private func buttons(_ text: String) -> XCUIElementQuery {
        app.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", text))
    }

    private func texts(_ text: String) -> XCUIElementQuery {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", text))
    }

    /** Moves the page a little: up for a negative number, down for a positive one (in parts of the screen's height). */
    private func nudge(_ part: CGFloat) {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.55 + part))
        from.press(forDuration: 0.05, thenDragTo: to)
        Thread.sleep(forTimeInterval: 0.4)
    }

    /// Scrolls until the button stands well inside the screen. (A tap right at the top would land on the clock
    /// instead, which sends the page back to its start.) Nil, and a failed test, when it never shows up.
    private func reach(_ text: String, scrolls: Int = 12) -> XCUIElement? {
        let found = buttons(text).firstMatch
        _ = found.waitForExistence(timeout: 5)
        let screen = app.frame
        var tries = 0
        while tries < scrolls {
            if found.exists && found.isHittable {
                let y = found.frame.midY
                if y < screen.height * 0.16 {
                    nudge(0.25)
                } else if y > screen.height * 0.80 {
                    nudge(-0.25)
                } else {
                    break
                }
            } else {
                nudge(-0.4)
            }
            tries += 1
        }
        guard found.exists && found.isHittable else {
            snap("MISSING-" + text.replacingOccurrences(of: " ", with: "-"))
            XCTFail("nothing to tap with the text \"\(text)\"")
            return nil
        }
        return found
    }

    @discardableResult
    private func tap(_ text: String, scrolls: Int = 12) -> Bool {
        guard let found = reach(text, scrolls: scrolls) else { return false }
        found.tap()
        Thread.sleep(forTimeInterval: 1.2)
        alive("tapping \"\(text)\"")
        return true
    }

    private func see(_ text: String, seconds: TimeInterval = 8) {
        let found = texts(text).firstMatch.waitForExistence(timeout: seconds) || buttons(text).firstMatch.exists
        if !found { snap("MISSING-" + text.replacingOccurrences(of: " ", with: "-")) }
        XCTAssertTrue(found, "the text \"\(text)\" is not on the screen")
    }

    /** Pictures of a whole page: the top, then one per swipe down. */
    private func page(_ name: String, swipes: Int) {
        snap(name)
        for i in 0..<swipes {
            app.swipeUp(velocity: .slow)
            Thread.sleep(forTimeInterval: 0.6)
            snap("\(name)-\(i + 2)")
        }
        alive("scrolling \(name)")
    }

    /** The button at the bottom of the screen with this name (a card further up can have the same word in it). */
    private func bar(_ name: String) {
        let all = buttons(name).allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
        guard let lowest = all.max(by: { $0.frame.midY < $1.frame.midY }) else {
            snap("MISSING-bar-" + name)
            XCTFail("no \"\(name)\" in the bar at the bottom")
            return
        }
        lowest.tap()
        Thread.sleep(forTimeInterval: 1.0)
        alive("opening \(name) from the bar")
    }

    /** Something worth knowing that is not a failure: said in the log and kept next to the pictures. */
    private func note(_ line: String) {
        print("LionFlow tour: \(line)")
        guard let folder = ProcessInfo.processInfo.environment["TOUR_OUT"], !folder.isEmpty else { return }
        try? FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
        let file = URL(fileURLWithPath: folder).appendingPathComponent("notes.txt")
        let before = (try? String(contentsOf: file, encoding: .utf8)) ?? ""
        try? (before + line + "\n").write(to: file, atomically: true, encoding: .utf8)
    }

    /// Settings > "Auto-refresh when open". While it is on, the sample car is read again every few seconds, so
    /// nothing on the screen can tell whether a pull did it.
    private func autoRefresh(_ on: Bool) {
        demo("Settings")
        guard let toggle = reach("Auto-refresh when open", scrolls: 20) else { return }
        if (toggle.value as? String) != (on ? "On" : "Off") {
            toggle.tap()
            Thread.sleep(forTimeInterval: 0.8)
        }
        XCTAssertEqual(toggle.value as? String, on ? "On" : "Off", "the switch \"Auto-refresh when open\" did not change")
    }

    /** Pulls the page a long way down and lets go. */
    private func pullDown() {
        let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
        let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.92))
        from.press(forDuration: 0.05, thenDragTo: to, withVelocity: .slow, thenHoldForDuration: 0.3)
        Thread.sleep(forTimeInterval: 1.0)
        alive("pulling the page down")
    }

    /// The sample car's report is two minutes old whenever it has just been read, and the clock on Home keeps
    /// counting from there. So "3 min ago" means it has not been read for a minute.
    private func waitUntilTheReportIsOld() -> Bool {
        let old = texts("Updated 3 min ago").firstMatch.waitForExistence(timeout: 110)
        if !old { snap("MISSING-Updated-3-min-ago") }
        return old
    }

    /// Right after the sample car has been read, its report is two minutes old, or one: the clock it was read with
    /// can be a millisecond behind the report's own, and minutes are counted whole.
    private func seeAFreshReport(seconds: TimeInterval = 6) {
        let two = texts("Updated 2 min ago").firstMatch, one = texts("Updated 1 min ago").firstMatch
        let deadline = Date().addingTimeInterval(seconds)
        while Date() < deadline && !two.exists && !one.exists { Thread.sleep(forTimeInterval: 0.5) }
        let found = two.exists || one.exists
        if !found { snap("MISSING-a-fresh-report") }
        XCTAssertTrue(found, "the report did not say it was fresh (\"Updated 2 min ago\" or \"1 min ago\")")
    }

    /// The strip at the very top of the screen, where the clock is, as plain numbers (red, green, blue and alpha
    /// for every dot).
    private func topStrip() -> [UInt8] {
        guard let image = XCUIScreen.main.screenshot().image.cgImage, app.frame.width > 0 else { return [] }
        let scale = CGFloat(image.width) / app.frame.width
        let width = image.width, height = max(1, Int(20 * scale))
        guard let top = image.cropping(to: CGRect(x: 0, y: 0, width: width, height: height)) else { return [] }
        var dots = [UInt8](repeating: 0, count: width * height * 4)
        dots.withUnsafeMutableBytes { raw in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return }
            context.draw(top, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return dots
    }

    /** How much of two strips is the same colour, from 0 to 1. */
    private func alike(_ a: [UInt8], _ b: [UInt8]) -> Double {
        guard !a.isEmpty, a.count == b.count else { return 0 }
        var same = 0
        for i in stride(from: 0, to: a.count, by: 4) {
            if abs(Int(a[i]) - Int(b[i])) <= 5 && abs(Int(a[i + 1]) - Int(b[i + 1])) <= 5 && abs(Int(a[i + 2]) - Int(b[i + 2])) <= 5 { same += 1 }
        }
        return Double(same) / Double(a.count / 4)
    }

    // MARK: Every tab, top to bottom

    func test01EveryTabOfTheElectricCar() {
        demo("Home")
        see("Demo car")
        page("home", swipes: 4)
        demo("Charging")
        page("charging", swipes: 6)
        demo("Climate")
        page("climate", swipes: 3)
        demo("Trips")
        page("trips", swipes: 3)
        demo("Service")
        page("service", swipes: 3)
        demo("Settings")
        page("settings", swipes: 7)
        demo("Map")
        Thread.sleep(forTimeInterval: 6)
        snap("map")
        alive("the map")
    }

    func test02TheBarAtTheBottomOpensEveryTab() {
        demo("Home")
        bar("Charging")
        see("Reaches")
        bar("Climate")
        see("Pre-heating")
        bar("Trips")
        see("km driven")
        for tab in ["Map", "Service", "Settings"] {
            bar("More")
            snap("more-menu")
            let item = app.buttons[tab].firstMatch
            XCTAssertTrue(item.waitForExistence(timeout: 5), "\(tab) is not in the More menu")
            if item.exists { item.tap() }
            Thread.sleep(forTimeInterval: 1.5)
            alive("opening \(tab) from More")
            snap("via-more-" + tab)
        }
        see("Dark mode")
        bar("Home")
        see("Demo car")
    }

    // MARK: Commands to the sample car

    func test03CommandsChangeTheSampleCar() {
        demo("Home")
        see("Locked")
        tap("Unlock")
        see("done (demo)")
        snap("after-unlock")
        see("Unlocked", seconds: 6)
        tap("Flash")
        see("Flash lights", seconds: 6)
        snap("after-flash")
        demo("Climate")
        tap("Start pre-heating now")
        see("Pre-heating is on", seconds: 8)
        snap("pre-heating-on")
        tap("Stop pre-heating")
        see("Pre-heating is off", seconds: 8)
        demo("Charging")
        page("charging-while-charging", swipes: 0)
    }

    // MARK: Sheets and editors

    func test04SheetsAndEditorsOpen() {
        demo("Home")
        if tap("Every value the car reports") {
            see("All car data")
            page("car-data", swipes: 3)
        }
        demo("Home")
        if tap("Customize this screen") { page("home-editor", swipes: 1) }
        demo("Charging")
        if tap("Places & prices") { page("places", swipes: 2) }
        demo("Charging")
        if tap("peak") { page("charging-session", swipes: 1) }
        demo("Climate")
        if tap("Ready at") { page("schedule-editor", swipes: 1) }
        demo("Trips")
        if tap("km/h avg") { page("trip", swipes: 1) }
        demo("Trips")
        if tap("Month") { page("trips-month", swipes: 1) }
        demo("Service")
        if tap("tap to change") { page("service-interval", swipes: 0) }
        demo("Service")
        if tap("Add") { page("service-history", swipes: 0) }
        demo("Settings")
        if tap("Edit", scrolls: 3) { page("settings-name", swipes: 0) }
        demo("Settings")
        if tap("Choose the cards") { page("settings-home-editor", swipes: 1) }
        demo("Settings")
        if tap("Battery size") { page("settings-battery", swipes: 0) }
        demo("Settings")
        if tap("Command & error log") { page("settings-log", swipes: 0) }
        demo("Settings")
        if tap("All car data") { page("settings-car-data", swipes: 1) }
        demo("Settings")
        if tap("Send feedback") { page("settings-feedback", swipes: 0) }
        demo("Settings")
        if tap("Language") { page("settings-language", swipes: 0) }
    }

    // MARK: Fuel car and plug-in hybrid

    func test05FuelCarAndHybrid() {
        demo("Home", kind: "fuel", "-brand", "opel")
        see("Fuel")
        page("fuel-home", swipes: 4)
        demo("Trips", kind: "fuel", "-brand", "opel")
        page("fuel-trips", swipes: 3)
        demo("Trips", kind: "fuel", "-brand", "opel")
        if tap("Months, fill-ups and records") { page("fuel-statistics", swipes: 2) }
        demo("Home", kind: "hybrid", "-brand", "citroen")
        page("hybrid-home", swipes: 4)
        demo("Trips", kind: "hybrid", "-brand", "citroen")
        page("hybrid-trips", swipes: 4)
        demo("Charging", kind: "hybrid", "-brand", "citroen")
        page("hybrid-charging", swipes: 3)
    }

    // MARK: Light colours and another language

    func test06LightColoursAndNorwegian() {
        demo("Home", "-theme", "light")
        page("light-home", swipes: 3)
        demo("Settings", "-theme", "light")
        page("light-settings", swipes: 2)
        demo("Home", "-theme", "dark", "-lang", "nb")
        see("Batteri", seconds: 6)
        page("nb-home", swipes: 3)
        demo("Charging", "-lang", "nb")
        page("nb-charging", swipes: 3)
        demo("Settings", "-lang", "nb")
        page("nb-settings", swipes: 6)
        // Back to English and dark for whoever runs after this.
        demo("Home", "-theme", "dark", "-lang", "en")
    }

    // MARK: The first screens

    func test07FirstScreensAndStartingTheDemo() {
        // The first screen: pick a brand, look at the sign-in screen, and go back.
        launch("-signedout")
        Thread.sleep(forTimeInterval: 2)
        snap("first-screen")
        see("Choose your car brand")
        if tap("Peugeot", scrolls: 2) {
            snap("sign-in")
            see("Remember me")
            if tap("Change brand", scrolls: 3) { see("Choose your car brand") }
        }
        // Trying it without an account: choose a sample car, and it opens.
        if tap("Try without an account", scrolls: 6) {
            snap("demo-choice")
            if tap("Start the demo", scrolls: 6) {
                see("Demo car", seconds: 12)
                snap("demo-started")
            }
        }
        // And out again: Settings, at the bottom.
        demo("Settings")
        if tap("Exit demo", scrolls: 20) {
            Thread.sleep(forTimeInterval: 1.5)
            snap("after-exit-demo")
            see("Choose your car brand")
        }
    }

    // MARK: The clock at the top stays readable

    /// Whatever is scrolled up must not show through the clock. The strip at the top of a page that stands still
    /// (the page's own colour, the clock and the battery) is the measure: every scrolled page has to look the same
    /// there.
    func test08NothingShowsThroughTheClock() {
        for look in ["dark", "light"] {
            demo("Charging", "-theme", look)
            let still = topStrip()
            XCTAssertFalse(still.isEmpty, "no picture of the top of the screen")
            for tab in ["Home", "Charging", "Trips", "Settings"] {
                demo(tab, "-theme", look)
                // In the dark look the top of Home's picture stands behind the clock for as long as the page is at
                // its top: that is how it is meant. On a screen the whole page fits on (an iPad), it never leaves.
                let title = texts("Updated ").firstMatch
                let top: CGFloat? = tab == "Home" && look == "dark" && title.exists ? title.frame.minY : nil
                for step in 1...3 {
                    app.swipeUp(velocity: .slow)
                    Thread.sleep(forTimeInterval: 0.9)
                    if let top = top, title.exists, abs(title.frame.minY - top) < 4 {
                        note("Home fits on this screen without scrolling: nothing can pass behind the clock there")
                        break
                    }
                    var share = alike(still, topStrip())
                    if share < 0.985 {
                        // The system can lay a notice of its own over the top for a few seconds: look once more.
                        Thread.sleep(forTimeInterval: 7)
                        share = alike(still, topStrip())
                    }
                    if share < 0.985 { snap("SHOWS-THROUGH-\(look)-\(tab)-\(step)") }
                    XCTAssertGreaterThanOrEqual(share, 0.985, "\(tab) (\(look)), scrolled \(step) times: something shows through the clock")
                }
            }
        }
        demo("Home", "-theme", "dark")
    }

    // MARK: Pulling a page down reads the car again

    func test09PullingHomeDownReadsTheCarAgain() {
        autoRefresh(false)
        demo("Home")
        if waitUntilTheReportIsOld() {
            pullDown()
            seeAFreshReport()
            snap("after-pull")
        } else {
            XCTFail("the sample car's report never got old: nothing to tell a pull by")
        }
        autoRefresh(true)
    }

    func test10PullingAnotherTabDownReadsTheCarAgain() {
        autoRefresh(false)
        demo("Home")
        if waitUntilTheReportIsOld() {
            // Opening another tab and coming back reads nothing by itself...
            bar("Charging")
            bar("Home")
            see("Updated 3 min ago", seconds: 4)
            // ...pulling that tab down does.
            bar("Charging")
            pullDown()
            bar("Home")
            seeAFreshReport()
        } else {
            XCTFail("the sample car's report never got old: nothing to tell a pull by")
        }
        autoRefresh(true)
    }

    /// A picture of the small circle that comes down while a page is pulled: taken from the side while the finger
    /// is still held down.
    func test11ThePullCircle() {
        for (tab, look) in [("Charging", "dark"), ("Home", "dark"), ("Home", "light")] {
            demo(tab, "-theme", look)
            let from = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45))
            let to = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.92))
            let taken = expectation(description: "a picture during the pull")
            let held = Held()
            DispatchQueue.global().asyncAfter(deadline: .now() + 3.0) {
                held.shot = XCUIScreen.main.screenshot()
                taken.fulfill()
            }
            from.press(forDuration: 0.05, thenDragTo: to, withVelocity: .fast, thenHoldForDuration: 4.5)
            wait(for: [taken], timeout: 20)
            if let shot = held.shot {
                Self.count += 1
                let title = String(format: "tour-%03d-pull-circle-%@-%@", Self.count, tab, look)
                if let folder = ProcessInfo.processInfo.environment["TOUR_OUT"], !folder.isEmpty {
                    try? FileManager.default.createDirectory(atPath: folder, withIntermediateDirectories: true)
                    try? shot.pngRepresentation.write(to: URL(fileURLWithPath: folder).appendingPathComponent(title + ".png"))
                }
            }
            alive("holding the page down")
        }
        demo("Home", "-theme", "dark")
    }

    // MARK: Notes about the app itself

    /// A newer build is out and the signature runs out within two days ("-sample-updates" pretends both, without
    /// asking anyone): Home says so, "Later" puts the update note away, and Settings tells the same.
    func test12UpdateAndSignatureNotes() {
        demo("Home", "-sample-updates")
        see("Update available")
        see("0.1.9999 is ready")
        see("Refresh LionFlow soon")
        snap("app-notes")
        if tap("Later", scrolls: 2) {
            Thread.sleep(forTimeInterval: 1.0)
            XCTAssertFalse(texts("Update available").firstMatch.exists, "the update note stayed after \"Later\"")
            see("Refresh LionFlow soon")
            snap("app-notes-after-later")
        }
        demo("Settings", "-sample-updates")
        if reach("App signature", scrolls: 20) != nil {
            see("Valid until")
            see("0.1.9999 is ready")
            snap("settings-app-section")
        }
        demo("Home")
    }
}

/** A picture taken beside the test while it holds a finger down. */
private final class Held: @unchecked Sendable {
    var shot: XCUIScreenshot?
}
