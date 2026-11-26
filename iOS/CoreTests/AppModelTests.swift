import XCTest
@testable import LionFlowCore

/// The app's state machine, run the way the screens run it: start, demo mode, commands, trips, settings. No phone
/// and no network: the weather, chargers and addresses are stand-ins.
final class AppModelTests: XCTestCase {
    private var suite = ""
    private var dir = URL(fileURLWithPath: NSTemporaryDirectory())

    override func setUp() {
        super.setUp()
        suite = "lionflow-tests-" + UUID().uuidString
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(suite)
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("cache"), withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("files"), withIntermediateDirectories: true)
        Demo.clock = { nowMillis() }
        ChargeLog.clock = { nowMillis() }
        ChargePower.clock = { nowMillis() }
        L10n.setPreference("en")
        let configs = ParityTestCase.assets.appendingPathComponent("stellantis_configs.json")
        if let text = try? String(contentsOf: configs, encoding: .utf8) { try? StellantisConfig.setText(text) }
    }

    override func tearDown() {
        // The sample car is one for the whole app, and remembers what was done to it: put it back as it was, so the
        // tests that compare it with Android's start from a fresh one.
        Demo.choose(MemoryStore(), "electric")
        Demo.reset()
        Demo.apply("", [
            Program(slot: 1, days: ["Mon", "Tue", "Wed", "Thu", "Fri"], hour: 7, minute: 15, enabled: true),
            Program(slot: 2, days: ["Sat"], hour: 9, minute: 0, enabled: false),
            Program(slot: 3, days: [], hour: 0, minute: 0, enabled: false),
            Program(slot: 4, days: [], hour: 0, minute: 0, enabled: false),
        ])
        ChargeLog.exitDemo()
        ChargeLog.onChange = nil
        ChargeLog.addressLookup = nil
        ChargeLog.savedChargers = { nil }
        UserDefaults().removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: dir)
        Brands.current = .peugeot
        super.tearDown()
    }

    private final class Counter: @unchecked Sendable {
        private let lock = NSLock()
        private var n = 0
        func add() { lock.lock(); n += 1; lock.unlock() }
        var value: Int { lock.lock(); defer { lock.unlock() }; return n }
    }

    @MainActor private func makeServices() -> AppServices {
        let s = AppServices(defaults: UserDefaults(suiteName: suite)!, secrets: MemorySecrets(), log: MemoryStore(), demo: MemoryStore(),
                            demoLog: MemoryStore(), cacheDir: dir.appendingPathComponent("cache"), filesDir: dir.appendingPathComponent("files"))
        s.weather = { _, _ in throw AppError("offline") }
        s.chargersNear = { _, _ in [] }
        s.address = { _, _ in nil }
        return s
    }

    @MainActor private func makeModel(_ services: AppServices? = nil) -> AppModel {
        let m = AppModel(services ?? makeServices())
        m.demoCommandNanos = 0
        return m
    }

    @MainActor private func until(_ what: String, _ done: () -> Bool) async {
        for _ in 0..<500 {
            if done() { return }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("Timed out waiting for: \(what)")
    }

    @MainActor func testStartsSignedOutAndDemoComesAndGoes() async {
        let m = makeModel()
        XCTAssertEqual(m.ui.phase, .signedOut)
        XCTAssertFalse(m.isDemo)
        XCTAssertFalse(m.remoteReady)
        XCTAssertFalse(m.remotePossible)
        m.update { $0.carName = "Lynet" }
        XCTAssertEqual(m.prefs.carName, "Lynet")

        m.startDemo("hybrid", "opel")
        XCTAssertEqual(m.ui.phase, .ready)
        XCTAssertTrue(m.isDemo)
        XCTAssertTrue(m.remoteReady)
        XCTAssertEqual(m.ui.vehicle?.vin, Demo.VIN)
        XCTAssertEqual(m.ui.vehicle?.brand, "Opel")
        XCTAssertEqual(m.ui.vehicles.count, 1)
        XCTAssertEqual(m.prefs.carName, "Demo car")
        XCTAssertEqual(m.prefs.brand, "opel")
        XCTAssertEqual(m.ui.status?.energyType, "Hybrid")
        XCTAssertFalse(m.ui.trips.isEmpty)
        XCTAssertEqual(ChargeLog.sessions.count, 14)
        XCTAssertEqual(ChargeLog.places.home?.name, "Home")
        XCTAssertEqual(m.vehicleKind(m.ui.vehicle!), Demo.vehicle.kind(nil))
        XCTAssertFalse(m.hasSeveralCars())

        // The demo is still there when the app is opened again.
        let again = makeModel(m.services)
        XCTAssertEqual(again.ui.phase, .ready)
        XCTAssertEqual(again.ui.status?.energyType, "Hybrid")

        m.exitDemo()
        XCTAssertEqual(m.ui.phase, .signedOut)
        XCTAssertFalse(m.isDemo)
        XCTAssertEqual(m.prefs.carName, "Lynet")
        XCTAssertNil(m.ui.status)
        XCTAssertTrue(m.ui.trips.isEmpty)
        XCTAssertTrue(ChargeLog.sessions.isEmpty)
    }

    @MainActor func testDemoCommandsChangeTheSampleCar() async {
        let m = makeModel()
        m.startDemo()
        XCTAssertEqual(m.ui.status?.locked, true)

        m.lockCar(false)
        XCTAssertEqual(m.ui.commandInProgress, "Unlock")
        XCTAssertEqual(m.ui.commandProgress, "Sending…")
        m.horn()                                    // one command at a time: this one is dropped
        XCTAssertEqual(m.ui.commandInProgress, "Unlock")
        await until("unlock") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.locked, false)
        XCTAssertEqual(m.ui.message, "Unlock — done (demo)")
        m.clearMessage()
        XCTAssertNil(m.ui.message)
        m.refresh()
        XCTAssertEqual(m.ui.status?.locked, false, "the sample car remembers")

        m.preconditioning(true)
        await until("pre-heating") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.preconditioningOn, true)
        XCTAssertEqual(m.ui.message, "Pre-heating — done (demo)")
        m.preconditioning(false)
        await until("stop pre-heating") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.preconditioningOn, false)

        XCTAssertEqual(m.ui.status?.isCharging, true)
        m.charging(false)
        await until("stop charging") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.chargingStatus, "Stopped")
        m.refresh()
        XCTAssertEqual(m.ui.status?.isCharging, false)
        m.charging(true)
        await until("charge now") { m.ui.commandInProgress == nil }
        m.refresh()
        XCTAssertEqual(m.ui.status?.isCharging, true)

        XCTAssertEqual(m.ui.status?.chargeType, "Partial")
        m.setCarChargeLimit(false)
        await until("full charge") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.chargeType, "Full")
        m.refresh()
        XCTAssertEqual(m.ui.status?.chargeType, "Full")

        var programs = m.userPrograms(m.ui.status)
        XCTAssertEqual(programs.count, 4)
        programs[1].enabled = true
        programs[1].hour = 6
        programs[1].minute = 40
        m.savePrograms(programs)
        await until("schedules") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.programs, programs)
        XCTAssertEqual(m.ui.message, "Saving schedule — done (demo)")
        m.refresh()
        XCTAssertEqual(m.ui.status?.programs, programs)

        for send in [m.lights, m.horn, m.wakeUp, m.clearScheduledStart] {
            send()
            await until("command") { m.ui.commandInProgress == nil }
        }
        XCTAssertEqual(m.ui.message, "Turn off scheduled start — done (demo)")
    }

    @MainActor func testPetrolCarHasNoChargingButKeepsTheRest() async {
        let m = makeModel()
        m.startDemo("fuel", "citroen")
        XCTAssertEqual(m.ui.status?.energyType, "Fuel")
        XCTAssertEqual(m.ui.tab, .home)
        m.setTab(.charging)
        XCTAssertEqual(m.ui.tab, .home, "a petrol car has no charging tab")
        m.setTab(.trips)
        XCTAssertEqual(m.ui.tab, .trips)
        m.setTab(.service)
        XCTAssertEqual(m.ui.tab, .service)
        m.charging(true)
        XCTAssertNil(m.ui.commandInProgress)
        m.setCarChargeLimit(true)
        XCTAssertNil(m.ui.commandInProgress)
        XCTAssertTrue(ChargeLog.sessions.isEmpty)
        m.lockCar(true)
        await until("lock") { m.ui.commandInProgress == nil }
        XCTAssertEqual(m.ui.status?.locked, true)
    }

    @MainActor func testOutsideDemoCommandsNeedRemoteControl() async {
        let m = makeModel()
        m.lockCar(true)
        XCTAssertNil(m.ui.commandInProgress)
        XCTAssertEqual(m.ui.message, "Set up remote control first (Settings › Security)")
        XCTAssertFalse(m.checkPin("1234"))
        XCTAssertFalse(m.hasPin)
    }

    @MainActor func testSignInAnswerWithoutACodeIsExplained() async {
        let m = makeModel()
        m.handleRedirect(URL(string: "mymap://oauth2redirect/no?error=access_denied&error_description=No%20thanks")!)
        XCTAssertEqual(m.ui.message, "No thanks")
        XCTAssertFalse(m.ui.signingIn)
        m.handleRedirect(URL(string: "mymap://oauth2redirect/no")!)
        XCTAssertEqual(m.ui.message, "Sign-in was cancelled")
        XCTAssertEqual(m.redirectScheme, "mymap")
        XCTAssertTrue(m.authorizeUrl().contains("redirect_uri=mymap"))
    }

    @MainActor func testParkedPlaceWeatherAndChargers() async {
        let s = makeServices()
        let asked = Counter()
        s.weather = { _, _ in Weather(tempC: 4.5, windMs: 3.0, symbol: "cloudy", hours: [], tomorrowMorningC: nil) }
        s.chargersNear = { lat, lon in
            asked.add()
            return [Charger(id: "c1", name: "Test charger", lat: lat + 0.001, lon: lon, maxKw: 150, points: 4, address: "Here 1", distanceM: nil)]
        }
        s.address = { _, _ in "Rue de Test 5" }
        let m = makeModel(s)
        m.startDemo()
        await until("weather") { m.ui.weather != nil }
        XCTAssertEqual(m.ui.weather?.tempC, 4.5)
        await until("chargers") { !m.ui.chargers.isEmpty }
        XCTAssertEqual(m.ui.chargers.first?.name, "Test charger")
        XCTAssertNil(m.ui.chargerError)
        XCTAssertEqual(asked.value, 1)
        // Asked again for the same area: the saved list is used, nothing is fetched.
        m.loadChargers()
        XCTAssertEqual(asked.value, 1)
        XCTAssertEqual(m.ui.chargers.count, 1)
        XCTAssertNotNil(ChargeLog.savedChargers())

        // The sample car stands at its home.
        let st = m.ui.status!
        m.updateParkedPlace(st.lat, st.lon)
        XCTAssertEqual(m.parkedAt, "@Home")
        m.updateParkedPlace(st.lat! + 0.03, st.lon! + 0.04)
        XCTAssertEqual(m.parkedAt, "@Work")
        m.updateParkedPlace(59.9, 10.7)
        await until("address") { m.parkedAt != nil }
        XCTAssertEqual(m.parkedAt, "Rue de Test 5")
        m.updateParkedPlace(nil, nil)
        XCTAssertNil(m.parkedAt)

        m.update { $0.chargerSource = "nobil" }
        m.loadChargers()
        XCTAssertEqual(m.ui.chargerError, "NOBIL isn't available in this build — switch to OpenStreetMap")
    }

    @MainActor func testChargerProblemsAreSaidInPlainWords() async {
        let s = makeServices()
        s.chargersNear = { _, _ in throw BusyException() }
        let m = makeModel(s)
        m.startDemo()
        await until("charger error") { m.ui.chargerError != nil }
        XCTAssertEqual(m.ui.chargerError, "Charger servers are busy — try again in a minute")
    }

    @MainActor func testServiceHistoryIsKept() async {
        let s = makeServices()
        let m = makeModel(s)
        m.addServiceEntry("Tyres", "Winter set on")
        m.addServiceEntry("Oil", "")
        XCTAssertEqual(m.ui.serviceHistory.count, 2)
        XCTAssertEqual(Set(m.ui.serviceHistory.map { $0.title }), ["Tyres", "Oil"])
        let again = makeModel(s)
        XCTAssertEqual(again.ui.serviceHistory, m.ui.serviceHistory)
        m.deleteServiceEntry(m.ui.serviceHistory.first { $0.title == "Tyres" }!)
        XCTAssertEqual(m.ui.serviceHistory.map { $0.title }, ["Oil"])
    }

    @MainActor func testTripsGoOutAsAFileAndComeBackIn() async throws {
        let m = makeModel()
        m.startDemo()
        let shown = m.ui.trips
        XCTAssertGreaterThan(shown.count, 3)
        let tag = m.tags.create("Work, \"daily\"", 2)
        m.tagTrip(shown[0], tag.id)
        XCTAssertEqual(m.tags.tagOf(shown[0].id)?.id, tag.id)

        let file = try m.exportTripsCsv()
        let text = try String(contentsOf: file, encoding: .utf8)
        let lines = text.components(separatedBy: "\n").filter { !$0.isEmpty }
        XCTAssertEqual(lines.count, shown.count + 1)
        XCTAssertEqual(lines[0], "id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag")
        XCTAssertTrue(lines[1].hasPrefix(shown[0].id + ","))
        XCTAssertTrue(lines[1].hasSuffix(",Work   daily "), lines[1])
        XCTAssertEqual(lines[1].components(separatedBy: ",").count, 9)

        // The same file read back: the trips are already there, so nothing new is added.
        m.importTripsCsv(text)
        XCTAssertEqual(m.ui.message, "Imported \(shown.count) trips")
        XCTAssertEqual(m.ui.trips.count, shown.count)

        // A file from another car adds its trips, newest first, with its tag.
        let other = "id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag\n"
            + "x1,2031-01-02 08:00,2031-01-02 08:30,25.0,30,4.50,18.0,50,Holiday\n"
            + "x2,2019-05-06 10:00,2019-05-06 10:20,10.0,20,,,30,\n"
        m.importTripsCsv(other)
        XCTAssertEqual(m.ui.message, "Imported 2 trips")
        XCTAssertEqual(m.ui.trips.count, shown.count + 2)
        XCTAssertEqual(m.ui.trips.first?.id, "x1")
        XCTAssertEqual(m.ui.trips.last?.id, "x2")
        XCTAssertEqual(m.ui.trips.first?.distanceKm, 25.0)
        XCTAssertEqual(m.ui.trips.first?.kwh, 4.5)
        XCTAssertEqual(m.tags.tagOf("x1")?.name, "Holiday")
        XCTAssertNil(m.tags.tagOf("x2"))

        m.importTripsCsv("nothing to see here")
        XCTAssertEqual(m.ui.message, "Couldn't read any trips from that file. Send it to me on GitHub and I'll add support for it.")
    }

    @MainActor func testBugReportDetailsCarryNothingPersonal() async {
        let m = makeModel()
        m.startDemo()
        let text = m.techDetails()
        XCTAssertTrue(text.hasPrefix("LionFlow 0.1.0 (iOS)\n"), text)
        XCTAssertTrue(text.contains("Energy Electric · battery 64 % · capacity 73.0 kWh"), text)
        XCTAssertTrue(text.contains("Remote control set up: true"), text)
        XCTAssertFalse(text.contains(Demo.VIN))
        XCTAssertFalse(text.contains("48.8566"))

        let scrubbed = AppModel.scrub("mail ola.nordmann@example.no vin VR3UHZKXZPT123456 at 63.4305, 10.3951 and -3.70379 "
            + "Authorization: Bearer abc.def-ghi id 0123456789abcdefghijklmnopqrstuv km 12.5 at 08:15")
        XCTAssertEqual(scrubbed, "mail [e-mail] vin [VIN] at [pos], [pos] and [pos] Authorization: Bearer [token] id [id] km 12.5 at 08:15")
        XCTAssertEqual(AppModel.oneCell("a,b\r\nc\"d"), "a b  c d")
    }

    @MainActor func testSettingsReachTheScreensAndStay() async {
        let s = makeServices()
        let m = makeModel(s)
        XCTAssertEqual(m.prefs, AppSettings())
        m.update {
            $0.theme = "light"
            $0.distanceUnit = "mi"
            $0.language = "nb"
            $0.timeFormat = "12"
        }
        XCTAssertEqual(m.prefs.theme, "light")
        XCTAssertEqual(L10n.lang, "nb")
        XCTAssertFalse(TimeFmt.use24)
        m.update {
            $0.language = "en"
            $0.timeFormat = "24"
        }
        XCTAssertEqual(L10n.lang, "en")
        XCTAssertTrue(TimeFmt.use24)

        // Changed behind the model's back (the car logic does this): the screens still follow.
        s.settings.update { $0.homeHidden = "fuel" }
        await until("settings") { m.prefs.homeHidden == "fuel" }

        m.openSetting("fuelPrice")
        XCTAssertEqual(m.focusSetting, "fuelPrice")
        XCTAssertEqual(m.ui.tab, .settings)

        // Opened again: the same settings.
        XCTAssertEqual(makeModel(s).prefs.distanceUnit, "mi")
        XCTAssertEqual(makeModel(s).prefs.homeHidden, "fuel")
    }

    @MainActor func testAnotherAccountCanBeStartedAndDropped() async {
        let m = makeModel()
        XCTAssertEqual(m.garage().count, 1)
        XCTAssertTrue(m.garage()[0].active)
        XCTAssertFalse(m.canCancelSignIn())
        m.addAccount()
        XCTAssertEqual(m.ui.phase, .signedOut)
        XCTAssertEqual(m.settings.activeAccount, "a2")
        XCTAssertEqual(m.settings.accounts().map { $0.id }, ["", "a2"])
        XCTAssertFalse(m.canCancelSignIn(), "the first account was never signed in")
        m.signOut()
        XCTAssertEqual(m.settings.activeAccount, "")
        XCTAssertEqual(m.settings.accounts().map { $0.id }, [""])
        XCTAssertEqual(m.ui.phase, .signedOut)
    }
}
