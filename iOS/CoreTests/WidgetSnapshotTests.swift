import XCTest
@testable import LionFlowCore

/// The home-screen widget's "Updated … ago" line, put together in the widget from the app's translated patterns.
final class WidgetSnapshotTests: XCTestCase {
    private let t0: Int64 = 1_795_000_000_000

    func testUpdatedLineCountsOn() {
        var w = WidgetSnapshot()
        w.carTime = t0
        w.fetched = t0
        XCTAssertEqual(w.updatedLine(now: t0 + 20_000, short: false), "Updated just now")
        XCTAssertEqual(w.updatedLine(now: t0 + 7 * 60_000, short: false), "Updated 7 min ago")
        // The car has been quiet for a long time when the app last asked: say so.
        w.fetched = t0 + 3 * 3_600_000
        XCTAssertEqual(w.updatedLine(now: t0 + 3 * 3_600_000, short: false), "Car asleep · Last report 3 h ago")
        XCTAssertEqual(w.updatedLine(now: t0 + 2 * 86_400_000, short: true), "Asleep · 2 d ago")
        w.note = "Open LionFlow to sign in"
        XCTAssertEqual(w.updatedLine(now: t0, short: false), "Open LionFlow to sign in")
    }

    func testTranslatedPatterns() {
        var w = WidgetSnapshot()
        w.carTime = t0
        w.fetched = t0
        w.ago["updated.min"] = "Oppdatert for {n} min siden"
        XCTAssertEqual(w.updatedLine(now: t0 + 12 * 60_000, short: false), "Oppdatert for 12 min siden")
        XCTAssertEqual(WidgetSnapshot.english.count, 12)
    }

    func testSnapshotSurvivesTheTripThroughJson() throws {
        var w = WidgetSnapshot()
        w.hasData = true
        w.battery = 80
        w.place = "Home"
        let back = try JSONDecoder().decode(WidgetSnapshot.self, from: JSONEncoder().encode(w))
        XCTAssertEqual(back, w)
    }

    func testGroupNamesFromAProfile() {
        let plist = """
        <?xml version="1.0" encoding="UTF-8"?><plist version="1.0"><dict><key>Entitlements</key><dict>
        <key>com.apple.security.application-groups</key><array><string>group.no.lionflow.app.ABCDE12345</string></array>
        </dict></dict></plist>
        """
        let data = Data([0x30, 0x80]) + Data(plist.utf8) + Data([0, 1, 2])
        XCTAssertEqual(WidgetShelf.profileGroups(data), ["group.no.lionflow.app.ABCDE12345"])
    }
}
