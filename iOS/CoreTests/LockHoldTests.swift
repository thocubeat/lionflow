import XCTest
@testable import LionFlowCore

/// What the app shows after a lock or unlock from the app, while the car's report catches up (LockHold). The same
/// cases as the Android app's LockHoldTest.
final class LockHoldTests: XCTestCase {
    private let sent: Int64 = 1_764_000_000_000   // when the command was done

    private func iso(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    private func status(_ lock: String, doorsAt: Int64?, reportAt: Int64? = nil) throws -> VehicleStatus {
        let doorsTime = doorsAt.map { #", "updatedAt": "\#(iso($0))""# } ?? ""
        let text = #"{"updatedAt": "\#(iso(reportAt ?? doorsAt ?? sent))", "doorsState": {"lockedStates": ["\#(lock)"]\#(doorsTime)}}"#
        return VehicleStatus.parse(try JSONObject(text), nil)
    }

    func testTheOldReportDoesNotFlipTheScreenBack() throws {
        let h = LockHold(want: true, since: sent)
        XCTAssertTrue(h.holds(try status("Unlocked", doorsAt: sent - 60_000), now: sent + 3_000))
        XCTAssertTrue(h.holds(try status("Unlocked", doorsAt: sent + 10_000), now: sent + 30_000))
    }

    func testTheCarLockingItselfAgainIsShown() throws {
        let h = LockHold(want: false, since: sent)
        XCTAssertFalse(h.holds(try status("Locked", doorsAt: sent + 35_000), now: sent + 90_000))
    }

    func testLockedWithTheKeyAfterAnUnlockFromTheAppIsShown() throws {
        let h = LockHold(want: false, since: sent)
        XCTAssertFalse(h.holds(try status("SuperLocked", doorsAt: sent + 120_000), now: sent + 150_000))
    }

    func testTheWishIsLetGoWhenTheCarConfirmsItOrAfterFiveMinutes() throws {
        let h = LockHold(want: true, since: sent)
        XCTAssertFalse(h.holds(try status("Locked", doorsAt: sent - 60_000), now: sent + 3_000))
        XCTAssertFalse(h.holds(try status("Unlocked", doorsAt: sent - 60_000), now: sent + 300_001))
    }

    func testWithoutATimeOnTheDoorsTheWholeReportsTimeCounts() throws {
        let h = LockHold(want: false, since: sent)
        XCTAssertTrue(h.holds(try status("Locked", doorsAt: nil, reportAt: sent - 5_000), now: sent + 3_000))
        XCTAssertFalse(h.holds(try status("Locked", doorsAt: nil, reportAt: sent + 40_000), now: sent + 90_000))
        XCTAssertEqual(LockHold.reportedAt(try status("Locked", doorsAt: nil, reportAt: sent + 40_000)), sent + 40_000)
    }

    func testFinnishIsOfferedAndTranslates() throws {
        XCTAssertTrue(L10n.options.contains { $0.0 == "fi" && $0.1 == "Suomi" })
        XCTAssertEqual(L10n.columns.last, "fi")
        let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let android = here.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Android/app/src/main/assets/l10n.tsv")
        let ios = here.deletingLastPathComponent().appendingPathComponent("l10n-ios.tsv")
        L10n.load([try String(contentsOf: android, encoding: .utf8), try String(contentsOf: ios, encoding: .utf8)])
        defer { L10n.setPreference("en") }
        L10n.setPreference("fi")
        XCTAssertEqual(L10n.lang, "fi")
        XCTAssertEqual(tr("Lock"), "Lukitse")
        XCTAssertEqual(tr("Locked · Doors closed"), "Lukittu · Ovet kiinni")
        XCTAssertEqual(tr("Stop at 80 %"), "Pysäytä 80 %:ssa")
        XCTAssertEqual(tr("Unlock with Face ID"), "Avaa Face ID:llä")
        // The other languages are where they were.
        L10n.setPreference("nb")
        XCTAssertEqual(tr("Lock"), "Lås")
        L10n.setPreference("nl")
        XCTAssertEqual(tr("Later"), "Later")
    }
}
