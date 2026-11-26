import XCTest
@testable import LionFlowCore

/// What the tests that compare with the Android app share.
///
/// The files in Fixtures/ named *_expected.json were written by the Android app's Kotlin code (see iOS/oracle), run on
/// the inputs next to them. Each test gives the same inputs to the Swift code and compares, value by value.
class ParityTestCase: XCTestCase {
    static let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    static let fixtures = here.appendingPathComponent("Fixtures")
    static let assets = here.deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("Android/app/src/main/assets")

    var misses = 0

    override func setUp() {
        misses = 0
        Brands.current = .peugeot
        L10n.setPreference("en")
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        FuelStats.calendar = utc
    }

    override func tearDown() {
        if misses > 30 { XCTFail("… and \(misses - 30) more differences in this test") }
        Brands.current = .peugeot
        L10n.setPreference("en")
    }

    // MARK: helpers

    func text(_ name: String) throws -> String {
        try String(contentsOf: Self.fixtures.appendingPathComponent(name), encoding: .utf8)
    }

    /** Android's answers, read with Apple's own JSON reader so a mistake in ours can't hide itself. */
    func expected(_ name: String) throws -> Any {
        try JSONSerialization.jsonObject(with: Data(contentsOf: Self.fixtures.appendingPathComponent(name)), options: [.fragmentsAllowed])
    }

    /** Only the first 30 differences of a test are printed, so one mistake doesn't flood the log. */
    func miss(_ what: String, _ got: String, _ want: String, file: StaticString = #filePath, line: UInt = #line) {
        misses += 1
        if misses <= 30 { XCTFail("DIFF \(what): iPhone «\(got)» Android «\(want)»", file: file, line: line) }
    }

    func same(_ what: @autoclosure () -> String, _ got: String?, _ want: String?, file: StaticString = #filePath, line: UInt = #line) {
        if got != want { miss(what(), got ?? "nil", want ?? "nil", file: file, line: line) }
    }

    func same(_ what: @autoclosure () -> String, _ got: Bool, _ want: Any?, file: StaticString = #filePath, line: UInt = #line) {
        let w = want as? Bool
        if w == nil || got != w! { miss(what(), String(got), w.map { String($0) } ?? "nil", file: file, line: line) }
    }

    /** A number Android wrote as text (Java's `Double.toString`), or nil for its null. */
    func number(_ any: Any?) -> Double? {
        guard let s = any as? String else { return nil }
        return s == "NaN" ? Double.nan : Double(s)
    }

    func sameNumber(_ what: @autoclosure () -> String, _ got: Double?, _ want: Any?, file: StaticString = #filePath, line: UInt = #line) {
        let w = number(want)
        if got == nil && w == nil { return }
        if let g = got, let w = w, g == w || (g.isNaN && w.isNaN) { return }
        miss(what(), got.map { JSONValue.javaDouble($0) } ?? "nil", (want as? String) ?? "nil", file: file, line: line)
    }

    /// JSON text is compared with an escaped slash (backslash + "/") read as a plain "/".
    ///
    /// The fixtures were made on a desktop with open-json, a copy of Android's JSON classes that differs from
    /// Android's own in one thing: Android writes a backslash before every "/", open-json only in "</". The app
    /// writes it the way Android does (JSONTests.testSlashesAreWrittenLikeAndroid checks that by hand), so this
    /// one known difference in the reference is left out of the comparison here.
    func sameJSONText(_ what: @autoclosure () -> String, _ got: String?, _ want: String?, file: StaticString = #filePath, line: UInt = #line) {
        func plain(_ s: String?) -> String? { s?.replacingOccurrences(of: "\\/", with: "/") }
        same(what(), plain(got), plain(want), file: file, line: line)
    }

    func sameLong(_ what: @autoclosure () -> String, _ got: Int64?, _ want: Any?, file: StaticString = #filePath, line: UInt = #line) {
        same(what(), got.map { String($0) }, want as? String, file: file, line: line)
    }

    func sameList(_ what: @autoclosure () -> String, _ got: [String], _ want: Any?, file: StaticString = #filePath, line: UInt = #line) {
        let w = (want as? [Any])?.map { ($0 as? String) ?? "null" } ?? ["<not a list>"]
        if got.count != w.count { miss(what() + ".count", String(got.count), String(w.count), file: file, line: line) }
        for i in 0..<min(got.count, w.count) where got[i] != w[i] { miss(what() + "[\(i)]", got[i], w[i], file: file, line: line) }
    }

    /// For results of sin, cos, pow and the like: the phone's maths library and Java's can differ in the last
    /// digit, so these are compared to 12 significant digits. Plain arithmetic is compared exactly (`sameNumber`).
    func closeNumber(_ what: @autoclosure () -> String, _ got: Double?, _ want: Any?, file: StaticString = #filePath, line: UInt = #line) {
        let w = number(want)
        if got == nil && w == nil { return }
        if let g = got, let w = w, g == w || abs(g - w) <= 1e-12 * max(abs(g), abs(w)) { return }
        miss(what(), got.map { JSONValue.javaDouble($0) } ?? "nil", (want as? String) ?? "nil", file: file, line: line)
    }

    /** Kotlin writes a missing value as "null" inside a text. */
    func kt(_ d: Double?) -> String { d.map { JSONValue.javaDouble($0) } ?? "null" }
    func kt(_ s: String?) -> String { s ?? "null" }

    /** Two JSON texts that say the same, whatever the order of their names. */
    func sameJSONValue(_ what: @autoclosure () -> String, _ got: String, _ want: Any?, file: StaticString = #filePath, line: UInt = #line) {
        func canonical(_ any: Any?) -> String {
            guard let any = any, JSONSerialization.isValidJSONObject(any), let d = try? JSONSerialization.data(withJSONObject: any, options: [.sortedKeys]) else { return "<not JSON>" }
            return String(decoding: d, as: UTF8.self)
        }
        let mine = try? JSONSerialization.jsonObject(with: Data(got.utf8), options: [])
        same(what(), canonical(mine), canonical(want), file: file, line: line)
    }

    func sameStatus(_ at: String, _ st: VehicleStatus, _ w: [String: Any], file: StaticString = #filePath, line: UInt = #line) {
        sameNumber(at + ".batteryPct", st.batteryPct, w["batteryPct"], file: file, line: line)
        sameNumber(at + ".rangeKm", st.rangeKm, w["rangeKm"], file: file, line: line)
        same(at + ".plugged", st.plugged, w["plugged"], file: file, line: line)
        same(at + ".chargingStatus", st.chargingStatus, w["chargingStatus"] as? String, file: file, line: line)
        same(at + ".chargingMode", st.chargingMode, w["chargingMode"] as? String, file: file, line: line)
        sameNumber(at + ".chargingRateKmh", st.chargingRateKmh, w["chargingRateKmh"], file: file, line: line)
        same(at + ".remainingMinutes", st.remainingMinutes.map { String($0) }, w["remainingMinutes"] as? String, file: file, line: line)
        same(at + ".nextDelayedHour", st.nextDelayedHour.map { String($0) }, w["nextDelayedHour"] as? String, file: file, line: line)
        same(at + ".nextDelayedMinute", st.nextDelayedMinute.map { String($0) }, w["nextDelayedMinute"] as? String, file: file, line: line)
        sameNumber(at + ".batteryHealthPct", st.batteryHealthPct, w["batteryHealthPct"], file: file, line: line)
        sameNumber(at + ".odometerKm", st.odometerKm, w["odometerKm"], file: file, line: line)
        same(at + ".locked", st.locked.map { String($0) }, w["locked"] as? String, file: file, line: line)
        sameList(at + ".openDoors", st.openDoors, w["openDoors"], file: file, line: line)
        sameNumber(at + ".outsideTempC", st.outsideTempC, w["outsideTempC"], file: file, line: line)
        same(at + ".preconditioningOn", st.preconditioningOn, w["preconditioningOn"], file: file, line: line)
        sameList(at + ".programs", st.programs.map { "\($0.slot)|\($0.days.joined(separator: ","))|\($0.hour)|\($0.minute)|\($0.enabled)" }, w["programs"], file: file, line: line)
        sameNumber(at + ".lat", st.lat, w["lat"], file: file, line: line)
        sameNumber(at + ".lon", st.lon, w["lon"], file: file, line: line)
        same(at + ".ignition", st.ignition, w["ignition"] as? String, file: file, line: line)
        same(at + ".moving", st.moving, w["moving"], file: file, line: line)
        sameLong(at + ".updatedAtMillis", st.updatedAtMillis, w["updatedAtMillis"], file: file, line: line)
        sameNumber(at + ".kmToService", st.kmToService, w["kmToService"], file: file, line: line)
        sameNumber(at + ".daysToService", st.daysToService, w["daysToService"], file: file, line: line)
        sameNumber(at + ".residualKwh", st.residualKwh, w["residualKwh"], file: file, line: line)
        sameNumber(at + ".capacityKwh", st.capacityKwh, w["capacityKwh"], file: file, line: line)
        sameLong(at + ".energyAtMillis", st.energyAtMillis, w["energyAtMillis"], file: file, line: line)
        same(at + ".rangeEstimated", st.rangeEstimated, w["rangeEstimated"], file: file, line: line)
        same(at + ".chargeType", st.chargeType, w["chargeType"] as? String, file: file, line: line)
        same(at + ".energyType", st.energyType, w["energyType"] as? String, file: file, line: line)
        sameNumber(at + ".fuelPct", st.fuelPct, w["fuelPct"], file: file, line: line)
        sameNumber(at + ".fuelRangeKm", st.fuelRangeKm, w["fuelRangeKm"], file: file, line: line)
        same(at + ".isMildHybrid", st.isMildHybrid, w["isMildHybrid"], file: file, line: line)
        sameNumber(at + ".heading", st.heading, w["heading"], file: file, line: line)
        same(at + ".privacyState", st.privacyState, w["privacyState"] as? String, file: file, line: line)
        sameList(at + ".fogLightsOn", st.fogLightsOn, w["fogLightsOn"], file: file, line: line)
        same(at + ".alarmOn", st.alarmOn.map { String($0) }, w["alarmOn"] as? String, file: file, line: line)
        sameNumber(at + ".fuelTotalRaw", st.fuelTotalRaw, w["fuelTotalRaw"], file: file, line: line)
        sameJSONText(at + ".raw", st.raw.toString(), w["raw"] as? String, file: file, line: line)
    }

    func sameTrip(_ what: String, _ t: Trip, _ w: [String: Any], file: StaticString = #filePath, line: UInt = #line) {
        same(what + ".id", t.id, w["id"] as? String, file: file, line: line)
        sameLong(what + ".start", t.startMillis, w["start"], file: file, line: line)
        sameLong(what + ".end", t.endMillis, w["end"], file: file, line: line)
        sameNumber(what + ".km", t.distanceKm, w["km"], file: file, line: line)
        sameNumber(what + ".dur", t.durationSec, w["dur"], file: file, line: line)
        sameNumber(what + ".kwh", t.kwh, w["kwh"], file: file, line: line)
        sameNumber(what + ".avgKwh", t.avgKwhPer100, w["avgKwh"], file: file, line: line)
        sameNumber(what + ".speed", t.avgSpeedKmh, w["speed"], file: file, line: line)
        sameNumber(what + ".liters", t.liters, w["liters"], file: file, line: line)
        sameNumber(what + ".avgL", t.avgLPer100, w["avgL"], file: file, line: line)
        sameNumber(what + ".fuelStart", t.fuelStartPct, w["fs"], file: file, line: line)
        sameNumber(what + ".fuelEnd", t.fuelEndPct, w["fe"], file: file, line: line)
    }
}

/// Checks the iPhone app's logic against the Android app's own answers: reading the car's status, trips and the
/// figures worked out from them, texts, numbers and translations.
final class AndroidParityTests: ParityTestCase {
    // MARK: JSON

    func testJSONReadsAndWritesLikeAndroid() throws {
        let input = try JSONObject(text("json_inputs.json"))
        let texts = input.optJSONArray("texts")?.strings() ?? []
        let keys = input.optJSONArray("keys")?.strings() ?? []
        let want = try XCTUnwrap(expected("json_expected.json") as? [[String: Any]])
        XCTAssertEqual(texts.count, want.count)
        XCTAssertFalse(keys.isEmpty)
        for (i, t) in texts.enumerated() where i < want.count {
            let j = try JSONObject(t)
            let w = want[i]
            sameList("json[\(i)].keys", j.keys(), w["keys"])
            sameJSONText("json[\(i)].toString", j.toString(), w["toString"] as? String)
            sameJSONText("json[\(i)].toString(2)", j.toString(2), w["toString2"] as? String)
            let probes = try XCTUnwrap(w["probes"] as? [String: [String: Any]])
            for k in keys {
                let p = try XCTUnwrap(probes[k])
                let at = "json[\(i)].\(k)"
                same(at + ".has", j.has(k), p["has"])
                same(at + ".isNull", j.isNull(k), p["isNull"])
                sameJSONText(at + ".optString", j.optString(k), p["optString"] as? String)
                same(at + ".optDouble", JSONValue.javaDouble(j.optDouble(k)), p["optDouble"] as? String)
                same(at + ".optInt", String(j.optInt(k)), p["optInt"] as? String)
                same(at + ".optLong", String(j.optLong(k)), p["optLong"] as? String)
                same(at + ".optBoolean", j.optBoolean(k), p["optBoolean"])
                same(at + ".optBoolean(true)", j.optBoolean(k, true), p["optBooleanT"])
                sameJSONText(at + ".str", j.str(k), p["str"] as? String)
                same(at + ".num", j.num(k).map { JSONValue.javaDouble($0) }, p["num"] as? String)
                same(at + ".isObject", j.optJSONObject(k) != nil, p["isObject"])
                same(at + ".isArray", j.optJSONArray(k) != nil, p["isArray"])
            }
            let items = try XCTUnwrap(w["arr"] as? [[String: Any]])
            guard let a = j.optJSONArray("arr") else {
                XCTAssertTrue(items.isEmpty)
                continue
            }
            XCTAssertEqual(items.count, a.length() + 1)     // one past the end as well
            for (x, p) in items.enumerated() {
                let at = "json[\(i)].arr[\(x)]"
                same(at + ".optString", a.optString(x), p["optString"] as? String)
                same(at + ".optDouble", JSONValue.javaDouble(a.optDouble(x)), p["optDouble"] as? String)
                same(at + ".optInt", String(a.optInt(x)), p["optInt"] as? String)
                same(at + ".optBoolean", a.optBoolean(x), p["optBoolean"])
                same(at + ".isNull", a.isNull(x), p["isNull"])
            }
        }
    }

    // MARK: The car's status

    func testCarStatusIsReadLikeAndroid() throws {
        let cases = try JSONArray(text("status_inputs.json")).objects()
        let want = try XCTUnwrap(expected("status_expected.json") as? [[String: Any]])
        XCTAssertEqual(cases.count, want.count)
        XCTAssertGreaterThan(cases.count, 8)
        for (i, c) in cases.enumerated() where i < want.count {
            let w = want[i]
            let name = c.optString("name")
            same("status[\(i)].name", name, w["name"] as? String)
            let st = VehicleStatus.parse(c.optJSONObject("status") ?? JSONObject(), c.optJSONObject("maintenance"),
                                         mildHybrid: c.optBoolean("mildHybrid"), requireChargingEvidence: c.optBoolean("requireChargingEvidence"))
            func at(_ key: String) -> String { "\(name).\(key)" }
            sameNumber(at("batteryPct"), st.batteryPct, w["batteryPct"])
            sameNumber(at("rangeKm"), st.rangeKm, w["rangeKm"])
            same(at("plugged"), st.plugged, w["plugged"])
            same(at("chargingStatus"), st.chargingStatus, w["chargingStatus"] as? String)
            same(at("chargingMode"), st.chargingMode, w["chargingMode"] as? String)
            sameNumber(at("chargingRateKmh"), st.chargingRateKmh, w["chargingRateKmh"])
            same(at("remainingMinutes"), st.remainingMinutes.map { String($0) }, w["remainingMinutes"] as? String)
            same(at("nextDelayedHour"), st.nextDelayedHour.map { String($0) }, w["nextDelayedHour"] as? String)
            same(at("nextDelayedMinute"), st.nextDelayedMinute.map { String($0) }, w["nextDelayedMinute"] as? String)
            sameNumber(at("batteryHealthPct"), st.batteryHealthPct, w["batteryHealthPct"])
            sameNumber(at("odometerKm"), st.odometerKm, w["odometerKm"])
            same(at("locked"), st.locked.map { String($0) }, w["locked"] as? String)
            sameList(at("openDoors"), st.openDoors, w["openDoors"])
            sameNumber(at("outsideTempC"), st.outsideTempC, w["outsideTempC"])
            same(at("preconditioningOn"), st.preconditioningOn, w["preconditioningOn"])
            sameList(at("programs"), st.programs.map { "\($0.slot)|\($0.days.joined(separator: ","))|\($0.hour)|\($0.minute)|\($0.enabled)" }, w["programs"])
            sameNumber(at("lat"), st.lat, w["lat"])
            sameNumber(at("lon"), st.lon, w["lon"])
            same(at("ignition"), st.ignition, w["ignition"] as? String)
            same(at("moving"), st.moving, w["moving"])
            sameLong(at("updatedAtMillis"), st.updatedAtMillis, w["updatedAtMillis"])
            sameNumber(at("kmToService"), st.kmToService, w["kmToService"])
            sameNumber(at("daysToService"), st.daysToService, w["daysToService"])
            sameNumber(at("residualKwh"), st.residualKwh, w["residualKwh"])
            sameNumber(at("capacityKwh"), st.capacityKwh, w["capacityKwh"])
            sameLong(at("energyAtMillis"), st.energyAtMillis, w["energyAtMillis"])
            same(at("chargeType"), st.chargeType, w["chargeType"] as? String)
            same(at("energyType"), st.energyType, w["energyType"] as? String)
            sameNumber(at("fuelPct"), st.fuelPct, w["fuelPct"])
            sameNumber(at("fuelRangeKm"), st.fuelRangeKm, w["fuelRangeKm"])
            same(at("isMildHybrid"), st.isMildHybrid, w["isMildHybrid"])
            sameNumber(at("heading"), st.heading, w["heading"])
            same(at("privacyState"), st.privacyState, w["privacyState"] as? String)
            sameList(at("fogLightsOn"), st.fogLightsOn, w["fogLightsOn"])
            same(at("alarmOn"), st.alarmOn.map { String($0) }, w["alarmOn"] as? String)
            sameNumber(at("fuelTotalRaw"), st.fuelTotalRaw, w["fuelTotalRaw"])
            same(at("isCharging"), st.isCharging, w["isCharging"])
            same(at("privacyOn"), st.privacyOn, w["privacyOn"])
            sameJSONText(at("programsJson"), st.programsJson().toString(), w["programsJson"] as? String)
            let caps: [Bool] = [Caps.battery(st), Caps.fuel(st), Caps.position(st), Caps.climate(st), Caps.lockState(st)] + AppTab.allCases.map { Caps.tab($0, st) }
            let wantCaps = (w["caps"] as? [Bool]) ?? []
            same(at("caps"), caps.map { String($0) }.joined(separator: ","), wantCaps.map { String($0) }.joined(separator: ","))
            sameList(at("flatten"), CarData.flatten(st.raw).map { $0.0 + " = " + $0.1 }, w["flatten"])
            sameJSONText(at("pretty"), CarData.pretty(st.raw), w["pretty"] as? String)
            sameNumber(at("lifetimeLiters"), FuelStats.lifetimeLiters(st), w["lifetimeLiters"])
            sameNumber(at("lifetimeLPer100"), FuelStats.lifetimeLPer100(st), w["lifetimeLPer100"])
        }
    }

    // MARK: Trips and what is worked out from them

    func testTripsAndFuelFiguresMatchAndroid() throws {
        let objs = try JSONArray(text("trips_inputs.json")).objects()
        let w = try XCTUnwrap(expected("trips_expected.json") as? [String: Any])
        let trips = objs.map { Trip.parse($0) }
        let wantTrips = try XCTUnwrap(w["trips"] as? [[String: Any]])
        let wantMild = try XCTUnwrap(w["mild"] as? [[String: Any]])
        XCTAssertEqual(trips.count, wantTrips.count)
        XCTAssertGreaterThan(trips.count, 50)
        for (i, t) in trips.enumerated() where i < wantTrips.count {
            sameTrip("trip[\(i)]", t, wantTrips[i])
            sameTrip("mildHybridTrip[\(i)]", Trip.parse(objs[i], mildHybrid: true), wantMild[i])
        }

        let months = FuelStats.monthly(trips)
        let wantMonths = try XCTUnwrap(w["monthly"] as? [[String: Any]])
        XCTAssertEqual(months.count, wantMonths.count)
        for (i, m) in months.enumerated() where i < wantMonths.count {
            let wm = wantMonths[i]
            sameLong("month[\(i)].start", m.startMillis, wm["start"])
            sameNumber("month[\(i)].km", m.km, wm["km"])
            same("month[\(i)].trips", String(m.trips), (wm["trips"] as? Int).map { String($0) })
            sameNumber("month[\(i)].liters", m.liters, wm["liters"])
            sameNumber("month[\(i)].kmWithFuel", m.kmWithFuel, wm["kmWithFuel"])
            sameNumber("month[\(i)].seconds", m.seconds, wm["seconds"])
            sameNumber("month[\(i)].lPer100", m.lPer100, wm["lPer100"])
        }

        let tank = FuelStats.tankEstimate(trips)
        sameNumber("tankEstimate", tank, w["tank"])
        sameNumber("liters", FuelStats.liters(trips), w["liters"])
        sameNumber("cost", FuelStats.cost(FuelStats.liters(trips), 21.49), w["cost"])
        sameNumber("cost without fuel", FuelStats.cost(0.0, 20.0), w["noCost"])

        let variants: [(String, Double?)] = [("fillUps", tank), ("fillUps50", 50.0), ("fillUpsNoTank", nil)]
        for (name, size) in variants {
            let got = FuelStats.fillUps(trips, size)
            let want = try XCTUnwrap(w[name] as? [[String: Any]])
            XCTAssertEqual(got.count, want.count, name)
            for (i, f) in got.enumerated() where i < want.count {
                sameLong("\(name)[\(i)].at", f.atMillis, want[i]["at"])
                sameNumber("\(name)[\(i)].from", f.fromPct, want[i]["from"])
                sameNumber("\(name)[\(i)].to", f.toPct, want[i]["to"])
                sameNumber("\(name)[\(i)].liters", f.liters, want[i]["liters"])
                sameNumber("\(name)[\(i)].km", f.kmSincePrevious, want[i]["km"])
            }
        }

        let r = FuelStats.records(trips)
        let wr = try XCTUnwrap(w["records"] as? [String: Any])
        same("records.longest", r.longest?.id, wr["longest"] as? String)
        same("records.longestTime", r.longestTime?.id, wr["longestTime"] as? String)
        same("records.mostEconomical", r.mostEconomical?.id, wr["mostEconomical"] as? String)
        same("records.thirstiest", r.thirstiest?.id, wr["thirstiest"] as? String)
        sameLong("records.bestDay", r.bestDayMillis, wr["bestDay"])
        sameNumber("records.bestDayKm", r.bestDayKm, wr["bestDayKm"])
        let none = FuelStats.records([])
        XCTAssertEqual(w["recordsEmpty"] as? String, "null|null|null|null|null|0.0")
        XCTAssertNil(none.longest)
        XCTAssertNil(none.longestTime)
        XCTAssertNil(none.mostEconomical)
        XCTAssertNil(none.thirstiest)
        XCTAssertNil(none.bestDayMillis)
        XCTAssertEqual(none.bestDayKm, 0.0)

        let h = HybridSplit.of(trips)
        let wh = try XCTUnwrap(w["hybrid"] as? [String: Any])
        sameNumber("hybrid.totalKm", h.totalKm, wh["totalKm"])
        sameNumber("hybrid.evKm", h.evKm, wh["evKm"])
        sameNumber("hybrid.engineKm", h.engineKm, wh["engineKm"])
        sameNumber("hybrid.kwh", h.kwh, wh["kwh"])
        sameNumber("hybrid.evKwhPer100", h.evKwhPer100, wh["evKwhPer100"])
        sameNumber("hybrid.evShare", h.evShare, wh["evShare"])

        let pairs: [(Double, Double)] = [(84_000.0, 14_000.0), (210_000.0, 14_000.0), (10.0, 100.0), (900.0, 9000.0), (5.5, 100000.0), (600.0, 299.0), (0.0, 5000.0)]
        let wantUnits = try XCTUnwrap(w["perUnit"] as? [Any])
        XCTAssertEqual(pairs.count, wantUnits.count)
        for (i, p) in pairs.enumerated() where i < wantUnits.count {
            sameNumber("litersPerUnit[\(i)]", FuelStats.litersPerUnit(p.0, p.1), wantUnits[i])
        }
    }

    // MARK: Warnings from the car

    func testWarningsMatchAndroid() throws {
        let list = try [
            #"{"type":"TyrePressureFault","startedAt":"2026-11-20T10:00:00Z"}"#, #"{"type":"LOW_OIL_LEVEL","active":false}"#,
            #"{"label":"ABSFault2Detected","createdAt":"2026-11-21T10:00:00.000Z"}"#, #"{"code":"P0420","endAt":null,"active":true}"#,
            #"{"type":"TyrePressureFault"}"#, #"{"type":"Ended","endAt":"2026-11-22T10:00:00Z"}"#, #"{"type":null,"label":" ","code":""}"#,
            #"{"type":"washerFluid_low","startedAt":"","createdAt":"2026-11-23T08:00:00+01:00"}"#,
        ].map { try JSONObject($0) }
        let got = CarAlert.parse(list).map { "\($0.type)|\($0.label)|\($0.atMillis.map { String($0) } ?? "null")" }
        sameList("alerts", got, try expected("alerts_expected.json"))
        XCTAssertEqual(got.count, 4)
    }

    // MARK: Numbers and times as text

    func testNumbersAndTimesAreWrittenLikeAndroid() throws {
        let w = try XCTUnwrap(expected("fmt_expected.json") as? [String: Any])
        func rows(_ key: String) throws -> [[Any]] { try XCTUnwrap(w[key] as? [[Any]], key) }

        let fixed = try rows("fixed")
        XCTAssertGreaterThan(fixed.count, 1000)
        for r in fixed {
            let v = try XCTUnwrap(number(r[0]))
            let places = try XCTUnwrap(r[1] as? Int)
            same("fixed(\(r[0]), \(places))", Fmt.fixed(v, places), r[2] as? String)
        }
        for r in try rows("grouped") {
            same("grouped(\(r[0]))", Fmt.grouped(try XCTUnwrap(Int64(r[0] as? String ?? ""))), r[1] as? String)
        }
        for r in try rows("round") {
            let v = try XCTUnwrap(number(r[0]))
            same("round(\(r[0]))", String(v.roundToLong), r[1] as? String)
        }
        for r in try rows("javaDouble") {
            let s = try XCTUnwrap(r[0] as? String)
            same("javaDouble(\(s))", JSONValue.javaDouble(try XCTUnwrap(Double(s))), s)
        }
        for r in try rows("distance") {
            var s = AppSettings()
            s.distanceUnit = try XCTUnwrap(r[1] as? String)
            same("distance(\(r[0]), \(s.distanceUnit))", fmtDistance(number(r[0]), s), r[2] as? String)
        }
        for r in try rows("temp") {
            var s = AppSettings()
            s.tempUnit = try XCTUnwrap(r[1] as? String)
            let unit = try XCTUnwrap(r[2] as? Bool)
            same("temp(\(r[0]), \(s.tempUnit), \(unit))", fmtTemp(number(r[0]), s, unit: unit), r[3] as? String)
        }
        let now = try XCTUnwrap(Int64(w["now"] as? String ?? ""))
        for r in try rows("minutesAgo") {
            same("minutesAgo(\(r[0]))", minutesAgo(try XCTUnwrap(Int64(r[0] as? String ?? "")), now), r[1] as? String)
        }
        for r in try rows("pt") {
            let input = r[0] as? String
            same("parsePt(\(input ?? "nil"))", VehicleStatus.parsePt(input).map { "\($0.0):\($0.1)" }, r[1] as? String)
        }
        for r in try rows("iso") {
            let input = r[0] as? String
            sameLong("parseIso(\(input ?? "nil"))", VehicleStatus.parseIso(input), r[1])
        }
        for r in try rows("compass") {
            same("compass(\(r[0]))", CarData.compass(try XCTUnwrap(number(r[0]))), r[1] as? String)
        }
    }

    // MARK: Home screen choices

    func testHomeLayoutMatchesAndroid() throws {
        let w = try XCTUnwrap(expected("layout_expected.json") as? [String: Any])
        let orders = ["", "parked,battery", "cardata,unknown, service ,warnings", "fuel,charging,monthCost,cardata,service,tripsClimate,parked,actions,lock,battery,warnings"]
        sameList("ordered", orders.map { HomeLayout.ordered(HomeLayout.phone, $0).map { $0.id }.joined(separator: ",") }, w["ordered"])
        same("orderedWatch", HomeLayout.ordered(HomeLayout.watch, "service,doors").map { $0.id }.joined(separator: ","), w["orderedWatch"] as? String)

        let moves = try XCTUnwrap(w["moves"] as? [[Any]])
        XCTAssertEqual(moves.count, 48)
        for m in moves {
            let order = try XCTUnwrap(m[0] as? String)
            let id = try XCTUnwrap(m[1] as? String)
            let up = try XCTUnwrap(m[2] as? Bool)
            same("move(«\(order)», \(id), up: \(up))", HomeLayout.move(HomeLayout.phone, order, id, up: up, applies: { $0.needs != "fuel" }), m[3] as? String)
        }
        for t in try XCTUnwrap(w["toggle"] as? [[Any]]) {
            let csv = try XCTUnwrap(t[0] as? String)
            let id = try XCTUnwrap(t[1] as? String)
            let show = try XCTUnwrap(t[2] as? Bool)
            same("toggle(«\(csv)», \(id), show: \(show))", HomeLayout.toggle(csv, id, show: show), t[3] as? String)
        }
        var custom = AppSettings()
        custom.homeOrder = "cardata,parked"
        custom.homeHidden = "battery,lock, fuel"
        var nothingHidden = AppSettings()
        nothingHidden.homeHidden = ""
        sameList("phoneCards", [AppSettings(), custom, nothingHidden].map { HomeLayout.phoneCards($0).joined(separator: ",") }, w["phoneCards"])
        func describe(_ items: [HomeLayout.Item]) -> String { items.map { "\($0.id)|\($0.title)|\($0.note)|\($0.needs)" }.joined(separator: ";") }
        sameList("defaults", [HomeLayout.phoneHidden, HomeLayout.watchHidden, HomeLayout.carHidden, describe(HomeLayout.phone), describe(HomeLayout.watch)], w["defaults"])
    }

    // MARK: HTTP errors in words

    func testHttpErrorsReadLikeAndroid() throws {
        let cases = try XCTUnwrap(expected("http_expected.json") as? [[Any]])
        XCTAssertEqual(cases.count, 10)
        for c in cases {
            let code = try XCTUnwrap(c[0] as? Int)
            let body = try XCTUnwrap(c[1] as? String)
            let host = try XCTUnwrap(c[2] as? String)
            same("describeHttpError(\(code), \(host))", Http.describeHttpError(code, body, host), c[3] as? String)
        }
    }

    // MARK: Translations

    func testEveryTextTranslatesLikeAndroid() throws {
        let tsv = try String(contentsOf: Self.assets.appendingPathComponent("l10n.tsv"), encoding: .utf8)
        L10n.load([tsv])
        XCTAssertTrue(L10n.isLoaded)
        let w = try XCTUnwrap(expected("l10n_expected.json") as? [String: Any])
        let inputs = try XCTUnwrap(w["inputs"] as? [String])
        XCTAssertGreaterThan(inputs.count, 1000)
        for lang in ["en", "fr", "de", "es", "nb", "nl"] {
            L10n.setPreference(lang)
            XCTAssertEqual(L10n.lang, lang)
            let want = try XCTUnwrap(w[lang] as? [String])
            XCTAssertEqual(want.count, inputs.count)
            for (i, s) in inputs.enumerated() where i < want.count {
                same("tr[\(lang)](«\(s)»)", L10n.tr(s), want[i])
            }
        }

        let brandInputs = try XCTUnwrap(w["brandInputs"] as? [String])
        let brands = try XCTUnwrap(w["brands"] as? [String: [String: Any]])
        XCTAssertEqual(brands.count, Brand.allCases.count)
        for brand in Brand.allCases {
            Brands.current = brand
            let wb = try XCTUnwrap(brands[brand.id])
            for lang in ["en", "nb", "fr"] {
                L10n.setPreference(lang)
                sameList("brand \(brand.id) [\(lang)]", brandInputs.map { L10n.tr($0) }, wb[lang])
            }
            same("subscriptionNotice(\(brand.id))", Brands.subscriptionNotice(), wb["notice"] as? String)
            same("fields(\(brand.id))", "\(brand.id)|\(brand.jsonKey)|\(brand.display)|\(brand.app)|\(brand.workshopQuery)|\(brand.eRemotePack)|\(brand.doorsPack)", wb["fields"] as? String)
        }
        Brands.current = .peugeot
        for r in try XCTUnwrap(w["fromCar"] as? [[Any]]) {
            let input = r[0] as? String
            same("fromCar(\(input ?? "nil"))", Brand.fromCar(input)?.id, r[1] as? String)
        }
    }
}
