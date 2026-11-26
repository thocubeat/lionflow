import XCTest
@testable import LionFlowCore

/// The parts that remember things between car reports (charging power, the charging log, trips logged from the
/// car). These are written by hand from the Android code's rules, since they need the phone's storage there.
final class StateTests: XCTestCase {
    private var now: Int64 = 1_795_000_000_000
    private var store = MemoryStore()
    private var settings: SettingsStore!

    override func setUp() {
        now = 1_795_000_000_000
        store = MemoryStore()
        let name = "lionflow-state-" + UUID().uuidString
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        settings = SettingsStore(d)
        ChargePower.clock = { [unowned self] in self.now }
        ChargeLog.clock = { [unowned self] in self.now }
        CarTrips.clock = { [unowned self] in self.now }
        ChargePower.timeZone = TimeZone(identifier: "UTC")!
        ChargePower.start(store, settings, "VIN1")
        ChargeLog.start(MemoryStore(), "VIN1")
    }

    override func tearDown() {
        ChargePower.clock = { nowMillis() }
        ChargeLog.clock = { nowMillis() }
        CarTrips.clock = { nowMillis() }
        ChargeLog.savedChargers = { nil }
        Brands.current = .peugeot
    }

    /** A status as the server would send it for an electric car, read the normal way. */
    private func status(pct: Double, range: Double? = nil, charging: Bool = false, plugged: Bool? = nil, residual: Double? = nil, capacity: Double? = 73_000,
                        remaining: String? = nil, mode: String = "Slow", odo: Double? = nil, moving: Bool = false, ignition: String = "Stop",
                        lat: Double? = nil, lon: Double? = nil, programs: [Program] = []) -> VehicleStatus {
        let at = isoUTC(now)
        let chargingJson = JSONObject()
        chargingJson.put("plugged", plugged ?? charging)
        chargingJson.put("status", charging ? "InProgress" : ((plugged ?? false) ? "Stopped" : "Disconnected"))
        chargingJson.put("chargingMode", charging ? mode : "No")
        if let r = remaining { chargingJson.put("remainingTime", r) }
        let electric = JSONObject().put("charging", chargingJson)
        if residual != nil || capacity != nil {
            let load = JSONObject()
            if let c = capacity { load.put("capacity", c) }
            if let r = residual { load.put("residual", r * 1000) }
            electric.put("battery", JSONObject().put("load", load))
        }
        let e = JSONObject().put("type", "Electric").put("level", pct).put("updatedAt", at)
        if let r = range { e.put("autonomy", r) }
        e.put("extension", JSONObject().put("electric", electric))
        let j = JSONObject().put("updatedAt", at).put("energies", JSONArray().put(e))
        if let o = odo { j.put("odometer", JSONObject().put("mileage", o)) }
        j.put("kinetic", JSONObject().put("moving", moving))
        j.put("ignition", JSONObject().put("type", ignition))
        if let la = lat, let lo = lon {
            j.put("lastPosition", JSONObject().put("geometry", JSONObject().put("coordinates", JSONArray().put(lo).put(la))))
        }
        var st = VehicleStatus.parse(j, nil)
        st.programs = programs
        return st
    }

    // MARK: Charging power

    func testRangeIsLearnedAndFilledInWhenTheCarSaysZero() {
        var st = ChargePower.record(status(pct: 80, range: 320))
        XCTAssertEqual(st.rangeKm, 320)
        XCTAssertFalse(st.rangeEstimated)
        now += 60_000
        st = ChargePower.record(status(pct: 50, range: 0))
        XCTAssertEqual(st.rangeKm, 200)          // 4 km per % learned from the reading before
        XCTAssertTrue(st.rangeEstimated)
        // Without anything learned: usable kWh at 19 kWh/100 km.
        ChargePower.setVin("VIN2")
        st = ChargePower.record(status(pct: 50, range: nil))
        XCTAssertEqual(st.rangeKm, Double((50 * (73.0 / 19.0 * 100.0 / 100.0)).roundToLong))
        XCTAssertTrue(st.rangeEstimated)
        // Under 1 % nothing is made up.
        XCTAssertNil(ChargePower.record(status(pct: 0.5, range: nil)).rangeKm)
    }

    func testChargingPowerIsMeasuredBetweenTwoReports() {
        var st = ChargePower.record(status(pct: 40, range: 160, charging: true, residual: 29.2, remaining: "PT4H"))
        // One report only: energy still needed over the time the car says is left.
        var r = ChargePower.reading(st)
        XCTAssertEqual(r?.measured, false)
        XCTAssertEqual(r?.kw ?? 0, (73.0 - 29.2) / 4.0, accuracy: 1e-9)
        XCTAssertEqual(ChargePower.label(st), "11 kW (est.)")
        XCTAssertEqual(ChargePower.leftText(st), " · 4 h 0 min left")

        now += 10 * 60_000
        st = ChargePower.record(status(pct: 42, range: 168, charging: true, residual: 30.4, remaining: "PT3H50M"))
        r = ChargePower.reading(st)
        XCTAssertEqual(r?.measured, true)
        XCTAssertEqual(r?.kw ?? 0, (30.4 - 29.2) / (10.0 / 60.0), accuracy: 1e-9)   // 7.2 kW
        XCTAssertEqual(ChargePower.label(st), "7.2 kW")

        // With LionFlow's own limit ahead, the time left is to that limit.
        settings.update { $0.chargeLimitOn = true; $0.chargeLimitPct = 80 }
        let left = ChargePower.timeLeft(st)
        XCTAssertEqual(left?.0, Int(Double(230) * (80.0 - 42.0) / (100.0 - 42.0)))
        XCTAssertEqual(left?.1, 80)
        XCTAssertEqual(ChargePower.leftText(st), " · 2 h 30 min left to 80 %")

        // Not charging any more: the samples go, and there is no reading.
        now += 60_000
        st = ChargePower.record(status(pct: 43, range: 172, plugged: true, residual: 31.0))
        XCTAssertNil(ChargePower.reading(st))
        XCTAssertNil(store.string("s_VIN1"))
        XCTAssertEqual(ChargePower.history().count, 3)
        XCTAssertTrue(ChargePower.history()[0].hasSuffix("level=43 residual=31.0 status=Stopped type=null plugged=true preheat=false"), ChargePower.history()[0])
    }

    /// The car's level can drop 5 points or more with the same energy in the battery; LionFlow keeps the old number
    /// for a while, but never while the car is plugged in: there the car's own number decides where charging stops.
    func testLevelFixStopsWhenPluggedIn() {
        _ = ChargePower.record(status(pct: 89, residual: 68.5))
        now += 60_000
        var st = ChargePower.record(status(pct: 79, residual: 68.5))
        XCTAssertEqual(st.batteryPct, 89)
        XCTAssertTrue(ChargePower.history()[0].contains("level=89 (car says 79) residual"), ChargePower.history()[0])
        now += 60_000
        st = ChargePower.record(status(pct: 79, plugged: true, residual: 68.5))
        XCTAssertEqual(st.batteryPct, 79)
        XCTAssertTrue(ChargePower.history()[0].contains("level=79 residual"), ChargePower.history()[0])
        // Unplugged again: the old number doesn't come back.
        now += 60_000
        st = ChargePower.record(status(pct: 79, residual: 68.4))
        XCTAssertEqual(st.batteryPct, 79)
    }

    /// The car reports its scheduled start time also when it is set to charge right away: after LionFlow's
    /// "Turn off" the time isn't shown as a scheduled start any more.
    func testScheduledStartIsHiddenAfterTurningItOff() {
        var st = status(pct: 50, plugged: true)
        st.nextDelayedHour = 2
        st.nextDelayedMinute = 0
        XCTAssertEqual(ChargePower.scheduledStart(st)?.hour, 2)
        ChargePower.rememberStartMode(immediate: true)
        XCTAssertNil(ChargePower.scheduledStart(st))
        ChargePower.rememberStartMode(immediate: false)
        XCTAssertEqual(ChargePower.scheduledStart(st)?.minute, 0)
        st.nextDelayedHour = nil
        XCTAssertNil(ChargePower.scheduledStart(st))
    }

    func testBatterySizeFallsBackSensibly() {
        XCTAssertEqual(ChargePower.usableKwh(status(pct: 50)), 73.0)
        XCTAssertEqual(ChargePower.usableKwh(status(pct: 50, residual: 30, capacity: nil)), 60.0)
        XCTAssertEqual(ChargePower.usableKwh(status(pct: 5, residual: 30, capacity: nil)), 73.0)      // too low a level to work it out
        var hybrid = status(pct: 50, capacity: nil)
        hybrid.energyType = "Hybrid"
        XCTAssertEqual(ChargePower.usableKwh(hybrid), 11.5)
        settings.update { $0.batteryKwh = 50 }
        XCTAssertEqual(ChargePower.usableKwh(status(pct: 50)), 50.0)
    }

    func testASuddenDropInLevelWithTheSameEnergyIsEvenedOut() {
        var st = ChargePower.record(status(pct: 89, range: 380, residual: 68.5))
        XCTAssertEqual(st.batteryPct, 89)
        now += 5 * 60_000
        st = ChargePower.record(status(pct: 79, range: 340, residual: 68.5))
        XCTAssertEqual(st.batteryPct, 89)          // the level from before the drop is kept
        now += 30 * 60_000
        st = ChargePower.record(status(pct: 78, range: 335, residual: 64.85))
        XCTAssertEqual(st.batteryPct, 84)          // and moved by the change in energy: 89 - 3.65/73*100
        now += 30 * 60_000
        st = ChargePower.record(status(pct: 77, range: 330, residual: 58.0))
        XCTAssertEqual(st.batteryPct, 77)          // the car's own number has caught up (within 2 points)
    }

    func testSchedulesJustSentAreShownUntilTheCarHasThem() {
        let old = [Program(slot: 1, days: ["Mon"], hour: 7, minute: 0, enabled: true)]
        let new = [Program(slot: 1, days: ["Mon", "Tue"], hour: 7, minute: 30, enabled: true)]
        XCTAssertNil(ChargePower.pendingPrograms())
        ChargePower.setPendingPrograms(new)
        XCTAssertEqual(ChargePower.pendingPrograms(), new)
        // The car still shows the old ones: ours are shown.
        XCTAssertEqual(ChargePower.record(status(pct: 50, range: 200, programs: old)).programs, new)
        XCTAssertEqual(ChargePower.record(status(pct: 50, range: 200, programs: old)).programs, new)
        // Something else than the old or the new list: someone else edited them, the car's list wins.
        let other = [Program(slot: 2, days: ["Sat"], hour: 9, minute: 0, enabled: false)]
        XCTAssertEqual(ChargePower.record(status(pct: 50, range: 200, programs: other)).programs, other)
        XCTAssertNil(ChargePower.pendingPrograms())
        // The car has caught up: nothing is pending any more.
        ChargePower.setPendingPrograms(new)
        XCTAssertEqual(ChargePower.record(status(pct: 50, range: 200, programs: new)).programs, new)
        XCTAssertNil(ChargePower.pendingPrograms())
        // After two hours they are forgotten.
        ChargePower.setPendingPrograms(new)
        now += 2 * 3_600_000 + 1
        XCTAssertNil(ChargePower.pendingPrograms())
    }

    // MARK: The charging log

    func testAChargingSessionIsLoggedFromTheCarsReports() {
        ChargeLog.savedChargers = { "{\"items\":[{\"name\":\"Fast Corner\",\"lat\":63.4305,\"lon\":10.3951},{\"name\":\"Far away\",\"lat\":63.5,\"lon\":10.5}]}" }
        XCTAssertTrue(ChargeLog.sessions.isEmpty)
        _ = ChargePower.record(status(pct: 30, range: 120, charging: true, residual: 21.9, remaining: "PT5H", lat: 63.43051, lon: 10.39512))
        XCTAssertEqual(ChargeLog.active?.startPct, 30)
        now += 20 * 60_000
        _ = ChargePower.record(status(pct: 34, range: 136, charging: true, residual: 24.3, remaining: "PT4H40M", lat: 63.43051, lon: 10.39512))
        XCTAssertEqual(ChargeLog.active?.endPct, 34)
        XCTAssertEqual(ChargeLog.active?.kwh ?? 0, 2.4, accuracy: 1e-9)
        XCTAssertNil(ChargeLog.active?.peakKw)      // the power between two reports is known from the report after
        now += 20 * 60_000
        _ = ChargePower.record(status(pct: 38, range: 152, charging: true, residual: 26.7, remaining: "PT4H20M", lat: 63.43051, lon: 10.39512))
        XCTAssertEqual(ChargeLog.active?.peakKw ?? 0, 7.2, accuracy: 1e-9)
        now += 3 * 3_600_000
        // Stopped, at a higher level than last seen: that level is where the session ended.
        _ = ChargePower.record(status(pct: 80, range: 320, plugged: true, residual: 58.4, lat: 63.43051, lon: 10.39512))
        XCTAssertNil(ChargeLog.active)
        XCTAssertEqual(ChargeLog.sessions.count, 1)
        let s = ChargeLog.sessions[0]
        XCTAssertEqual(s.startPct, 30)
        XCTAssertEqual(s.endPct, 80)
        XCTAssertEqual(s.kwh, 58.4 - 21.9, accuracy: 1e-9)
        XCTAssertTrue(s.measured)
        XCTAssertEqual(s.end - s.start, 3 * 3_600_000 + 40 * 60_000)
        XCTAssertEqual(s.peakKw ?? 0, 7.2, accuracy: 1e-9)
        XCTAssertEqual(s.source, "Fast Corner")
        XCTAssertEqual(s.mode, "Slow")
        XCTAssertEqual(ChargeLog.match(s).label, "Fast Corner")

        ChargeLog.savePlaces(PlacesConfig(home: ChargePlace(id: "home", name: "Home", address: "", lat: 63.4305, lon: 10.3951, price: 1.5)))
        XCTAssertEqual(ChargeLog.match(s), PlaceMatch(kind: "home", label: "Home", price: 1.5))

        // A session that added next to nothing is not logged.
        now += 3_600_000
        _ = ChargePower.record(status(pct: 80, range: 320, charging: true, residual: 58.4))
        now += 60_000
        _ = ChargePower.record(status(pct: 80, range: 320, plugged: true, residual: 58.4))
        XCTAssertEqual(ChargeLog.sessions.count, 1)

        ChargeLog.delete(s.id)
        XCTAssertTrue(ChargeLog.sessions.isEmpty)
    }

    func testDemoModeHasItsOwnLog() {
        _ = ChargePower.record(status(pct: 30, range: 120, charging: true, residual: 21.9))
        now += 3_600_000
        _ = ChargePower.record(status(pct: 50, range: 200, plugged: true, residual: 36.5))
        XCTAssertEqual(ChargeLog.sessions.count, 1)
        let real = ChargeLog.sessions
        Demo.clock = { 1_795_000_000_000 }
        Demo.choose(MemoryStore(), "electric")
        ChargeLog.enterDemo(MemoryStore(), Demo.VIN, Demo.sessions(), Demo.places())
        XCTAssertEqual(ChargeLog.sessions.count, 14)
        XCTAssertEqual(ChargeLog.places.currency, "€")
        ChargeLog.exitDemo()
        XCTAssertEqual(ChargeLog.sessions, real)
        XCTAssertEqual(ChargeLog.places.currency, "kr")
        Demo.clock = { nowMillis() }
    }

    // MARK: Trips logged from the car

    func testADriveIsLoggedWhenTheOdometerHasMoved() {
        _ = ChargePower.record(status(pct: 80, range: 320, residual: 58.4, odo: 1000, lat: 63.43, lon: 10.39))
        XCTAssertTrue(CarTrips.list().isEmpty)
        // Seen while driving.
        now += 10 * 60_000
        _ = ChargePower.record(status(pct: 78, range: 310, residual: 56.9, odo: 1008, moving: true, ignition: "Drive", lat: 63.45, lon: 10.42))
        XCTAssertTrue(CarTrips.list().isEmpty)
        // Parked again.
        now += 10 * 60_000
        _ = ChargePower.record(status(pct: 76, range: 300, residual: 55.4, odo: 1016, lat: 63.47, lon: 10.45))
        let trips = CarTrips.list()
        XCTAssertEqual(trips.count, 1)
        XCTAssertEqual(trips[0].distanceKm, 16)
        XCTAssertEqual(trips[0].kwh ?? 0, 3.0, accuracy: 1e-9)
        XCTAssertEqual(trips[0].durationSec, 20 * 60)
        XCTAssertEqual(trips[0].avgSpeedKmh ?? 0, 48, accuracy: 1e-9)       // seen live: the speed is real
        XCTAssertEqual(trips[0].id, "car-\(now)")

        // A drive only seen afterwards: the start is estimated (45 km/h), and no speed is given.
        now += 5 * 3_600_000
        _ = ChargePower.record(status(pct: 70, range: 280, residual: 51.1, odo: 1046, lat: 63.60, lon: 10.80))
        let both = CarTrips.list()
        XCTAssertEqual(both.count, 2)
        XCTAssertEqual(both[0].distanceKm, 30)
        XCTAssertEqual(both[0].durationSec ?? 0, 30.0 / 45.0 * 3600, accuracy: 1e-6)
        XCTAssertNil(both[0].avgSpeedKmh)

        // The odometer of another car (much lower): a fresh start, nothing logged.
        now += 3_600_000
        _ = ChargePower.record(status(pct: 70, range: 280, residual: 51.1, odo: 200))
        XCTAssertEqual(CarTrips.list().count, 2)
    }

    // MARK: Answers from the weather and map services

    func testWeatherIsReadFromMetsAnswer() throws {
        let hour: Int64 = 3_600_000
        let start: Int64 = 1_795_003_200_000     // 2026-11-18T12:00:00Z
        XCTAssertEqual(isoUTC(start), "2026-11-18T12:00:00Z")
        let series = JSONArray()
        for i in 0..<40 {
            let details = JSONObject().put("air_temperature", Double(i) - 3.0).put("wind_speed", 2.5)
            let data = JSONObject().put("instant", JSONObject().put("details", details))
            if i % 2 == 0 { data.put("next_1_hours", JSONObject().put("summary", JSONObject().put("symbol_code", "lightsnow"))) }
            else { data.put("next_6_hours", JSONObject().put("summary", JSONObject().put("symbol_code", "fair_day"))) }
            series.put(JSONObject().put("time", isoUTC(start + Int64(i - 2) * hour)).put("data", data))
        }
        let j = JSONObject().put("properties", JSONObject().put("timeseries", series))
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let w = try WeatherApi.parse(j, now: start - 20 * 60_000, calendar: utc)
        XCTAssertEqual(w.tempC, -2.0)            // the hour that started at 11:00 is still "now"
        XCTAssertEqual(w.windMs, 2.5)
        XCTAssertEqual(w.symbol, "fair_day")
        XCTAssertEqual(w.hours.count, 12)
        XCTAssertEqual(w.hours[1].symbol, "lightsnow")
        XCTAssertEqual(w.tomorrowMorningC, 18.0)   // 07:00 the next day is 19 hours after 12:00
        XCTAssertThrowsError(try WeatherApi.parse(JSONObject(), now: start, calendar: utc))
    }

    func testChargersAreReadFromOpenStreetMap() throws {
        let j = try JSONObject("""
        {"elements":[
          {"type":"node","id":11,"lat":63.43,"lon":10.39,"tags":{"name":"Torget","socket:type2_combo:output":"150 kW","socket:type2:output":"22kW","capacity":"6","addr:street":"Kongens gate","addr:housenumber":"1","addr:city":"Trondheim"}},
          {"type":"way","id":12,"center":{"lat":63.44,"lon":10.40},"tags":{"operator":"Lader AS","maxpower":"50000"}},
          {"type":"node","id":13,"lat":63.45,"lon":10.41,"tags":{"charging_station:output":"0,35 MW"}},
          {"type":"relation","id":14,"tags":{"name":"no position"}},
          {"type":"node","id":15,"lat":63.46,"lon":10.42}
        ]}
        """)
        let c = OsmChargers.parse(j, "charging_station")
        XCTAssertEqual(c.map { $0.id }, ["osm-node-11", "osm-way-12", "osm-node-13", "osm-node-15"])
        XCTAssertEqual(c.map { $0.name }, ["Torget", "Lader AS", "Charging station", "Charging station"])
        XCTAssertEqual(c.map { $0.maxKw }, [150, 50, 350, 0])
        XCTAssertEqual(c[0].points, 6)
        XCTAssertEqual(c[0].address, "Kongens gate 1 Trondheim")
        XCTAssertEqual(c[1].lat, 63.44)
        XCTAssertEqual(OsmChargers.parse(j, "fuel")[3].name, "Fuel station")
        XCTAssertEqual(Charger.fromJson(c[0].toJson()), c[0])
    }

    func testAddressesAreShortened() throws {
        XCTAssertEqual(Geocoder.address(try JSONObject("{\"display_name\":\"Long, long\",\"address\":{\"road\":\"Storgata\",\"house_number\":\"12\",\"town\":\"Levanger\",\"suburb\":\"Sentrum\"}}")), "Storgata 12, Levanger")
        XCTAssertEqual(Geocoder.address(try JSONObject("{\"display_name\":\"Somewhere\",\"address\":{\"country\":\"Norway\"}}")), "Somewhere")
        XCTAssertEqual(Geocoder.address(try JSONObject("{\"display_name\":\"Only this\"}")), "Only this")
        XCTAssertNil(Geocoder.address(try JSONObject("{}")))
    }
}
