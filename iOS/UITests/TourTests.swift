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

    /** Scrolls down until the button can be tapped, and taps it. False (and a failed test) when it never shows up. */
    @discardableResult
    private func tap(_ text: String, scrolls: Int = 8) -> Bool {
        let found = buttons(text).firstMatch
        var tries = 0
        while !(found.exists && found.isHittable) && tries < scrolls {
            if tries == 0 && found.waitForExistence(timeout: 4) && found.isHittable { break }
            app.swipeUp(velocity: .slow)
            tries += 1
        }
        guard found.exists && found.isHittable else {
            snap("MISSING-" + text.replacingOccurrences(of: " ", with: "-"))
            XCTFail("nothing to tap with the text \"\(text)\"")
            return false
        }
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
        if tap("Car data") {
            see("All car data")
            page("car-data", swipes: 3)
        }
        demo("Home")
        if tap("Customize this screen") { page("home-editor", swipes: 1) }
        demo("Charging")
        if tap("Places & prices") { page("places", swipes: 2) }
        demo("Climate")
        if tap("Ready at") { page("schedule-editor", swipes: 1) }
        demo("Trips")
        if tap("km/h avg") { page("trip", swipes: 1) }
        demo("Trips")
        if tap("Month") { page("trips-month", swipes: 1) }
        demo("Service")
        if tap("tap to change") { page("service-interval", swipes: 0) }
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
        launch("-signedout")
        Thread.sleep(forTimeInterval: 2)
        snap("first-screen")
        see("Peugeot")
        if tap("Peugeot", scrolls: 2) {
            snap("sign-in")
            see("Sign in")
            if tap("Try without an account", scrolls: 3) {
                snap("demo-choice")
                if tap("Start the demo", scrolls: 3) {
                    see("Demo car", seconds: 12)
                    snap("demo-started")
                }
            }
        }
        // And out again: Settings, at the bottom.
        demo("Settings")
        if tap("Exit demo", scrolls: 12) {
            Thread.sleep(forTimeInterval: 1.5)
            snap("after-exit-demo")
            see("Peugeot")
        }
    }
}
