import XCTest
@testable import LionFlowCore

/// Trip files from other apps (LionFlow's own CSV, the carmaker app's CSV in several languages, its .myp export) and
/// dates written in many ways, read by the iPhone app and compared with what the Android app's reader made of the
/// very same text. Android's answers were made with the clock on Norwegian time, so this runs on Norwegian time too.
final class ImportTests: ParityTestCase {
    override func setUp() {
        super.setUp()
        TripImport.timeZone = TimeZone(identifier: "Europe/Oslo")!
    }

    override func tearDown() {
        TripImport.timeZone = TimeZone.current
        super.tearDown()
    }

    func testTripFilesReadLikeAndroid() throws {
        let input = try JSONObject(text("import_inputs.json"))
        let want = try XCTUnwrap(expected("import_expected.json") as? [String: Any])
        XCTAssertEqual(want["zone"] as? String, "Europe/Oslo")
        let files = try input.getJSONArray("files").objects()
        let wantFiles = try XCTUnwrap(want["files"] as? [[String: Any]])
        XCTAssertEqual(files.count, wantFiles.count)
        var rowsSeen = 0
        for (f, w) in zip(files, wantFiles) {
            let name = f.optString("name")
            XCTAssertEqual(name, w["name"] as? String)
            let wantRows = w["rows"] as? [[String: Any]]
            do {
                let rows = try TripImport.parse(f.optString("text"))
                guard let wantRows = wantRows else {
                    miss(name, "\(rows.count) rows", "error \(w["error"] ?? "?")")
                    continue
                }
                same("\(name): rows", String(rows.count), String(wantRows.count))
                for (i, (r, wr)) in zip(rows, wantRows).enumerated() {
                    sameJSONText("\(name)[\(i)]", r.json.toString(), wr["json"] as? String)
                    same("\(name)[\(i)].tag", r.tag, wr["tag"] as? String)
                    rowsSeen += 1
                }
            } catch {
                if wantRows != nil { miss(name, "error \(error)", "\(wantRows!.count) rows") }
            }
        }
        XCTAssertGreaterThan(rowsSeen, 25)
    }

    func testDatesReadLikeAndroid() throws {
        let want = try XCTUnwrap(expected("import_expected.json") as? [String: Any])
        let dates = try XCTUnwrap(want["dates"] as? [[Any]])
        XCTAssertGreaterThan(dates.count, 80)
        var read = 0
        for d in dates {
            guard let s = d[0] as? String else { continue }
            let got = TripImport.date(s)
            sameLong("date «\(s)»", got, d[1])
            if got != nil { read += 1 }
        }
        XCTAssertGreaterThan(read, 40)
    }

    /// A file exported on one phone reads the same on another, whatever its clock is set to: the times are written
    /// without a time zone, and read back in the phone's own.
    func testOwnFileKeepsItsClockTimes() throws {
        for zone in ["UTC", "America/New_York", "Asia/Kolkata", "Pacific/Auckland"] {
            TripImport.timeZone = TimeZone(identifier: zone)!
            let rows = try TripImport.parse("id,start,end,distance_km\na,2026-06-01 10:00,2026-06-01 11:30,50")
            XCTAssertEqual(rows.count, 1)
            let start = rows[0].json.optLong("start")
            var cal = Calendar(identifier: .gregorian)
            cal.timeZone = TimeZone(identifier: zone)!
            let c = cal.dateComponents([.year, .month, .day, .hour, .minute], from: Date(timeIntervalSince1970: Double(start) / 1000))
            XCTAssertEqual([c.year, c.month, c.day, c.hour, c.minute], [2026, 6, 1, 10, 0], zone)
            XCTAssertEqual(rows[0].json.optLong("end") - start, 90 * 60_000, zone)
        }
    }
}
