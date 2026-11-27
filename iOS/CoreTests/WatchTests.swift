import XCTest
@testable import LionFlowCore

/// A stand-in for the carmaker's server: answers from a table, and remembers what was asked.
final class FakeServer: URLProtocol {
    private static let lock = NSLock()
    private static var table: [(needle: String, code: Int, body: String)] = []
    private static var seen: [String] = []

    static func answer(_ needle: String, _ body: String, code: Int = 200) {
        lock.lock()
        table.append((needle, code, body))
        lock.unlock()
    }

    static func reset() {
        lock.lock()
        table = []
        seen = []
        lock.unlock()
    }

    static var asked: [String] {
        lock.lock()
        defer { lock.unlock() }
        return seen
    }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeServer.self]
        return URLSession(configuration: c)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let url = request.url else { return }
        Self.lock.lock()
        Self.seen.append(url.absoluteString)
        let hit = Self.table.first { url.absoluteString.contains($0.needle) }
        Self.lock.unlock()
        let response = HTTPURLResponse(url: url, statusCode: hit?.code ?? 404, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data((hit?.body ?? "{}").utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

/// The watch and the phone, talking to each other. The phone's answers (a port of the Android app's watch service)
/// go straight into the watch app's own code, so both ends are tested together. No watch, no phone and no network:
/// the carmaker's server is a stand-in.
final class WatchTests: XCTestCase {
    private var suite = ""
    private var dir = URL(fileURLWithPath: NSTemporaryDirectory())
    private var realSession: URLSession?

    private let car1 = Vehicle(id: "id-one", vin: "VR3TESTCAR0000001", motorization: "Electric", brand: "Peugeot", pictureUrl: nil, maintenanceHref: nil)
    private let car2 = Vehicle(id: "id-two", vin: "VR3TESTCAR0000002", motorization: "Electric", brand: "Peugeot", pictureUrl: nil, maintenanceHref: nil)

    override func setUp() {
        super.setUp()
        suite = "lionflow-watch-tests-" + UUID().uuidString
        dir = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(suite)
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("cache"), withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: dir.appendingPathComponent("files"), withIntermediateDirectories: true)
        ChargeLog.clock = { nowMillis() }
        ChargePower.clock = { nowMillis() }
        L10n.setPreference("en")
        Brands.current = .peugeot
        let configs = ParityTestCase.assets.appendingPathComponent("stellantis_configs.json")
        if let text = try? String(contentsOf: configs, encoding: .utf8) { try? StellantisConfig.setText(text) }
        FakeServer.reset()
        realSession = Http.session
        Http.session = FakeServer.session()
    }

    override func tearDown() {
        if let s = realSession { Http.session = s }
        FakeServer.reset()
        ChargeLog.onChange = nil
        ChargeLog.addressLookup = nil
        ChargeLog.savedChargers = { nil }
        UserDefaults().removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: dir)
        Brands.current = .peugeot
        L10n.setPreference("en")
        super.tearDown()
    }

    private func makeServices() -> AppServices {
        let s = AppServices(defaults: UserDefaults(suiteName: suite)!, secrets: MemorySecrets(), log: MemoryStore(), demo: MemoryStore(),
                            demoLog: MemoryStore(), cacheDir: dir.appendingPathComponent("cache"), filesDir: dir.appendingPathComponent("files"))
        s.weather = { _, _ in throw AppError("offline") }
        s.chargersNear = { _, _ in [] }
        s.address = { _, _ in "Kongens gate 1, Trondheim" }
        return s
    }

    /** Signed in, with these cars already known: as after a normal sign-in. */
    private func signedIn(_ cars: [Vehicle]) -> AppServices {
        let s = makeServices()
        s.secure.put(StellantisApi.kRefresh, "refresh-token")
        s.secure.put(StellantisApi.kAccess, "access-token")
        s.secure.put(StellantisApi.kExpires, String(nowMillis() + 3_600_000))
        s.secure.put(StellantisApi.kVehicles, JSONArray(cars.map { $0.toJson() }).toString())
        ChargeLog.setVin(cars.first?.vin ?? "")
        ChargePower.setVin(cars.first?.vin ?? "")
        return s
    }

    private func statusJson(level: Double, range: Double, plugged: Bool, charging: String, minutesOld: Int64 = 5) -> String {
        """
        {"updatedAt": "\(isoUTC(nowMillis() - minutesOld * 60_000))",
         "lastPosition": {"geometry": {"coordinates": [10.3951, 63.4305, 12]}, "properties": {"heading": 287}},
         "energies": [{"type": "Electric", "level": \(level), "autonomy": \(range),
                       "extension": {"electric": {"charging": {"plugged": \(plugged), "status": "\(charging)", "chargingMode": "Slow"}}}}],
         "odometer": {"mileage": 13412.4},
         "environment": {"air": {"temp": 4.5}},
         "kinetic": {"moving": false},
         "doorsState": {"lockedStates": ["Locked"], "opening": [{"identifier": "Driver", "state": "Closed"}]},
         "maintenance": {"mileageBeforeMaintenance": 8200, "daysBeforeMaintenance": 141.6}}
        """
    }

    /** Three trips: one today, two yesterday and one the day before (local time). */
    private func tripsJson() -> String {
        let today = Int64((Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000).rounded())
        let day: Int64 = 86_400_000
        func trip(_ id: String, _ start: Int64, _ km: Double) -> String {
            "{\"id\": \"\(id)\", \"startedAt\": \"\(isoUTC(start))\", \"stoppedAt\": \"\(isoUTC(start + 900_000))\", \"distance\": \(km), \"duration\": 900}"
        }
        let list = [
            trip("t1", today + 60_000, 12.4),
            trip("t2", today - day + 2 * 3_600_000, 30.2),
            trip("t3", today - day + 5 * 3_600_000, 5.4),
            trip("t4", today - day - 3 * 3_600_000, 99.0),
            // The server can list a trip twice.
            trip("t1", today + 60_000, 12.4),
        ]
        return "{\"_embedded\": {\"trips\": [\(list.joined(separator: ", "))]}}"
    }

    private final class Replies: @unchecked Sendable {
        private let lock = NSLock()
        private var list: [(String, String)] = []
        func add(_ path: String, _ j: JSONObject) { lock.lock(); list.append((path, j.toString())); lock.unlock() }
        var all: [(String, String)] { lock.lock(); defer { lock.unlock() }; return list }
    }

    /** A watch whose messages to the phone are kept, and whose timers are run by the test. */
    @MainActor private func makeWatch() -> (WatchModel, () -> [(String, String)], () -> [(Double, @MainActor () -> Void)]) {
        final class Box {
            var sent: [(String, String)] = []
            var timers: [(Double, @MainActor () -> Void)] = []
        }
        let box = Box()
        let watch = WatchModel()
        watch.send = { path, payload in
            box.sent.append((path, payload))
            return true
        }
        watch.later = { seconds, work in box.timers.append((seconds, work)) }
        return (watch, { box.sent }, { box.timers })
    }

    // MARK: Not signed in

    @MainActor func testWithoutSignInTheWatchSaysWhatToDo() async {
        let link = WatchLink(makeServices())
        let (watch, sent, _) = makeWatch()
        watch.refresh()
        XCTAssertTrue(watch.busy)
        XCTAssertEqual(sent().map { $0.0 }, ["/lionflow/status"])

        let replies = Replies()
        await link.handle(sent()[0].0, sent()[0].1) { path, j in replies.add(path, j) }
        XCTAssertEqual(replies.all.count, 1)
        XCTAssertEqual(replies.all[0].0, "/lionflow/status/reply")
        watch.receive(replies.all[0].0, replies.all[0].1)
        XCTAssertFalse(watch.busy)
        XCTAssertNil(watch.state)
        XCTAssertEqual(watch.message, "Open LionFlow to sign in")
        XCTAssertTrue(FakeServer.asked.isEmpty, "nothing is asked of the carmaker without a sign-in")
    }

    @MainActor func testACommandNeedsRemoteControlSetUpOnThePhone() async {
        let link = WatchLink(signedIn([car1]))
        let (watch, sent, timers) = makeWatch()
        watch.command("unlock")
        XCTAssertTrue(watch.busy)
        XCTAssertEqual(sent().last?.0, "/lionflow/cmd")
        XCTAssertEqual(sent().last?.1, "unlock")

        let replies = Replies()
        await link.handle("/lionflow/cmd", "unlock") { path, j in replies.add(path, j) }
        XCTAssertEqual(replies.all.count, 1)
        XCTAssertEqual(replies.all[0].0, "/lionflow/cmd/reply")
        watch.receive(replies.all[0].0, replies.all[0].1)
        XCTAssertEqual(watch.message, "Set up remote control in LionFlow on your phone first")
        XCTAssertFalse(watch.busy, "an answer without \"final\" ends the waiting")
        // Three seconds after the answer the watch reads the car again.
        XCTAssertEqual(timers().last?.0, 3)
        timers().last?.1()
        XCTAssertEqual(sent().last?.0, "/lionflow/status")
        XCTAssertTrue(FakeServer.asked.isEmpty, "no command is sent without remote control")
    }

    @MainActor func testAnUnknownCommandIsIgnored() async {
        let services = signedIn([car1])
        services.secure.put(RemoteControl.kOtp, "{}")
        let replies = Replies()
        await WatchLink(services).handle("/lionflow/cmd", "open-sesame") { path, j in replies.add(path, j) }
        await WatchLink(services).handle("/lionflow/else", "") { path, j in replies.add(path, j) }
        XCTAssertTrue(replies.all.isEmpty)
        XCTAssertTrue(FakeServer.asked.isEmpty)
    }

    // MARK: A signed-in car

    @MainActor func testTheWatchShowsWhatThePhoneReadsFromTheCar() async throws {
        let services = signedIn([car1])
        FakeServer.answer("/vehicles/id-one/status", statusJson(level: 64.7, range: 275, plugged: true, charging: "Stopped"))
        FakeServer.answer("/vehicles/id-one/trips", tripsJson())
        let link = WatchLink(services)
        let j = await link.status()
        XCTAssertFalse(j.has("error"), j.toString())
        XCTAssertEqual(j.optString("name"), "My Peugeot")
        XCTAssertEqual(j.optInt("cars"), 1)
        XCTAssertEqual(j.optString("battery"), "64 %", "the level is cut, not rounded, as on Android")
        XCTAssertEqual(j.optString("range"), "≈ 275 km")
        XCTAssertEqual(j.opt("fuel"), .bool(false))
        XCTAssertEqual(j.opt("locked"), .bool(true))
        XCTAssertEqual(j.optString("status"), "Plugged in")
        XCTAssertEqual(j.optString("place"), "Kongens gate 1, Trondheim")
        XCTAssertEqual(j.optString("today"), "12 km")
        XCTAssertEqual(j.optString("yesterday"), "36 km")
        XCTAssertEqual(j.optString("updated"), "5 min ago")
        // Out of the box the watch leaves these four rows out.
        XCTAssertEqual(j.optJSONArray("hide")?.strings() ?? [], ["doors", "odometer", "outside", "service"])
        XCTAssertEqual(j.optJSONArray("extras")?.length(), 0)
        XCTAssertTrue(FakeServer.asked.allSatisfy { $0.hasPrefix("https://api.groupe-psa.com/connectedcar/v4/user/vehicles/id-one/") }, "\(FakeServer.asked)")

        // The same answer, as the watch app reads it.
        let (watch, _, _) = makeWatch()
        watch.refresh()
        watch.receive("/lionflow/status/reply", j.toString())
        XCTAssertFalse(watch.busy)
        XCTAssertNil(watch.message)
        let car = try XCTUnwrap(watch.state)
        XCTAssertEqual(car, WatchCar(name: "My Peugeot", battery: "64 %", range: "≈ 275 km", fuel: false, locked: true,
                                     place: "Kongens gate 1, Trondheim", today: "12 km", yesterday: "36 km", updated: "5 min ago",
                                     status: "Plugged in", cars: 1, hide: ["doors", "odometer", "outside", "service"], extras: []))
    }

    @MainActor func testTheRowsChosenOnThePhoneAndTheUnitsAndLanguageSetThere() async throws {
        let services = signedIn([car1])
        services.settings.update { $0.watchHidden = "distance,flash"; $0.distanceUnit = "mi"; $0.tempUnit = "f"; $0.carName = "Løva" }
        services.address = { _, _ in nil }
        FakeServer.answer("/vehicles/id-one/status", statusJson(level: 80, range: 100, plugged: false, charging: "Disconnected", minutesOld: 0))
        FakeServer.answer("/vehicles/id-one/trips", tripsJson())
        let j = await WatchLink(services).status()
        XCTAssertFalse(j.has("error"), j.toString())
        XCTAssertEqual(j.optString("name"), "Løva")
        XCTAssertEqual(j.optString("range"), "≈ 62 mi")
        XCTAssertEqual(j.optString("status"), "")
        XCTAssertEqual(j.optString("place"), "63.43050, 10.39510", "without an address the position itself is shown")
        XCTAssertEqual(j.optString("today"), "—")
        XCTAssertEqual(j.optString("yesterday"), "—")
        XCTAssertEqual(j.optString("updated"), "just now")
        XCTAssertFalse(FakeServer.asked.contains { $0.contains("/trips") }, "trips are not fetched when the watch doesn't show distance")

        let (watch, _, _) = makeWatch()
        watch.receive("/lionflow/status/reply", j.toString())
        let car = try XCTUnwrap(watch.state)
        XCTAssertEqual(car.hide, ["distance", "flash"])
        XCTAssertEqual(car.extras, [
            WatchRow(label: "Doors", value: "Locked"),
            WatchRow(label: "Odometer", value: "8,334 mi"),
            WatchRow(label: "Outside", value: "40°F"),
            WatchRow(label: "Next service", value: "5,095 mi · 142 days"),
        ])
    }

    @MainActor func testAnErrorFromTheServerReachesTheWatchInWords() async {
        let services = signedIn([car1])
        FakeServer.answer("/vehicles/id-one/status", "{\"message\": \"down for maintenance\"}", code: 503)
        let j = await WatchLink(services).status()
        XCTAssertTrue(j.has("error"))
        XCTAssertTrue(j.optString("error").contains("503"), j.optString("error"))
        let (watch, _, _) = makeWatch()
        watch.refresh()
        watch.receive("/lionflow/status/reply", j.toString())
        XCTAssertFalse(watch.busy)
        XCTAssertNil(watch.state)
        XCTAssertEqual(watch.message, j.optString("error"))
    }

    // MARK: Several cars

    @MainActor func testSwitchCarGoesToTheNextCarAndBackAgain() async throws {
        let services = signedIn([car1, car2])
        // With a security code the app starts locked, and reads nothing from the car on its own.
        services.secure.put(RemoteControl.kPin, "1234")
        services.settings.update { $0.watchHidden = "distance" }
        FakeServer.answer("/vehicles/id-one/status", statusJson(level: 64, range: 275, plugged: false, charging: "Disconnected"))
        FakeServer.answer("/vehicles/id-two/status", statusJson(level: 31, range: 120, plugged: false, charging: "Disconnected"))
        let model = AppModel(services)
        XCTAssertEqual(model.ui.phase, .locked)
        XCTAssertEqual(Garage.entries(services).map { $0.vehicle.vin }, [car1.vin, car2.vin])
        XCTAssertEqual(Garage.current(services)?.vehicle.vin, car1.vin)
        XCTAssertEqual(Garage.next(services)?.vehicle.vin, car2.vin)

        let link = WatchLink(services)
        link.switchToNextCar = { await MainActor.run { model.switchToNextCar() } }
        let (watch, sent, _) = makeWatch()
        watch.receive("/lionflow/status/reply", (await link.status()).toString())
        XCTAssertEqual(watch.state?.cars, 2)
        XCTAssertEqual(watch.state?.battery, "64 %")

        watch.nextCar()
        XCTAssertEqual(sent().last?.1, "nextcar")
        let replies = Replies()
        await link.handle("/lionflow/cmd", "nextcar") { path, j in replies.add(path, j) }
        XCTAssertEqual(replies.all.map { $0.0 }, ["/lionflow/status/reply"])
        watch.receive(replies.all[0].0, replies.all[0].1)
        XCTAssertFalse(watch.busy)
        XCTAssertEqual(watch.state?.battery, "31 %")
        XCTAssertEqual(services.api.cachedVehicle()?.vin, car2.vin)
        XCTAssertEqual(model.ui.vehicle?.vin, car2.vin, "the app's own screens follow")

        // And round again to the first car, which kept its name.
        await link.handle("/lionflow/cmd", "nextcar") { path, j in replies.add(path, j) }
        watch.receive(replies.all[1].0, replies.all[1].1)
        XCTAssertEqual(watch.state?.battery, "64 %")
        XCTAssertEqual(watch.state?.name, "My Peugeot")
        XCTAssertEqual(services.api.cachedVehicle()?.vin, car1.vin)
    }

    func testOneCarHasNoNextCar() {
        let services = signedIn([car1])
        XCTAssertEqual(Garage.entries(services).count, 1)
        XCTAssertNil(Garage.next(services))
    }

    // MARK: The watch on its own

    @MainActor func testTheWatchStopsWaitingWhenThePhoneDoesNotAnswer() {
        let (watch, _, timers) = makeWatch()
        watch.refresh()
        XCTAssertTrue(watch.busy)
        XCTAssertFalse(watch.noPhone)
        XCTAssertEqual(timers().last?.0, 30)
        timers().last?.1()
        XCTAssertFalse(watch.busy)
        XCTAssertTrue(watch.noPhone)
    }

    @MainActor func testTheWatchSaysSoWhenThereIsNoPhone() {
        let watch = WatchModel()
        watch.later = { _, _ in }
        watch.send = { _, _ in false }
        watch.refresh()
        XCTAssertFalse(watch.busy)
        XCTAssertTrue(watch.noPhone)
        watch.command("lock")
        XCTAssertFalse(watch.busy)
        watch.send = { _, _ in true }
        watch.refresh()
        XCTAssertTrue(watch.busy)
        XCTAssertFalse(watch.noPhone)
        watch.sendFailed()
        XCTAssertFalse(watch.busy)
        XCTAssertTrue(watch.noPhone)
    }

    @MainActor func testProgressLinesKeepTheWatchWaitingUntilTheLastOne() {
        let (watch, sent, timers) = makeWatch()
        watch.command("preheat")
        watch.receive("/lionflow/cmd/reply", "{\"text\": \"Pre-heating — Waking the car…\", \"final\": false}")
        XCTAssertTrue(watch.busy)
        XCTAssertEqual(watch.message, "Pre-heating — Waking the car…")
        XCTAssertTrue(timers().isEmpty)
        watch.receive("/lionflow/cmd/reply", "{\"text\": \"Pre-heating: done\", \"final\": true}")
        XCTAssertFalse(watch.busy)
        XCTAssertEqual(watch.message, "Pre-heating: done")
        XCTAssertEqual(timers().count, 1)
        watch.receive("/lionflow/cmd/reply", "not json at all")
        XCTAssertEqual(watch.message, "Pre-heating: done")
        XCTAssertEqual(sent().count, 1)
    }

    func testAFuelCarAndAnUnknownLockAreReadFromThePhonesAnswer() throws {
        let j = try XCTUnwrap(WatchWire.object("{\"name\": \"Opel\", \"battery\": \"58 %\", \"range\": \"≈ 520 km\", \"fuel\": true, \"locked\": null, \"cars\": 3}"))
        let car = WatchWire.car(j)
        XCTAssertTrue(car.fuel)
        XCTAssertNil(car.locked)
        XCTAssertEqual(car.cars, 3)
        XCTAssertEqual(car.place, "")
        XCTAssertTrue(car.hide.isEmpty)
        XCTAssertTrue(car.extras.isEmpty)
        let unlocked = WatchWire.car(try XCTUnwrap(WatchWire.object("{\"locked\": false}")))
        XCTAssertEqual(unlocked.locked, false)
        XCTAssertEqual(unlocked.cars, 1)
    }

    func testTheWatchHasItsWordsInEveryLanguageOfTheAndroidWatch() {
        let english = ["Battery", "Fuel", "Range", "Today", "Yesterday", "Unlock", "Lock", "Flash lights", "Pre-heat", "Car is at", "Refresh",
                       "Getting car status…", "Open LionFlow on your phone. The watch gets its data from there.", "Unlock the car?",
                       "Lock the car?", "Flash the lights?", "Start pre-heating?", "Yes", "No", "Sending to the car…", "Updated %s", "Switch car"]
        XCTAssertEqual(Set(WatchText.table.keys), ["nb", "fr", "de", "es", "fi", "da"])
        for (language, words) in WatchText.table {
            XCTAssertEqual(Set(words.keys), Set(english), language)
            XCTAssertTrue(words["Updated %s"]?.contains("%s") ?? false, language)
        }
        XCTAssertEqual(WatchText.languageCode(["nb-NO", "en-GB"]), "nb")
        XCTAssertEqual(WatchText.languageCode(["no"]), "nb")
        XCTAssertEqual(WatchText.languageCode(["fr_CA"]), "fr")
        XCTAssertEqual(WatchText.languageCode([]), "en")
        let before = WatchText.language
        defer { WatchText.language = before }
        WatchText.language = "nb"
        XCTAssertEqual(WatchText.t("Unlock the car?"), "Låse opp bilen?")
        XCTAssertEqual(WatchText.updated("2 min siden"), "Oppdatert 2 min siden")
        WatchText.language = "nl"
        XCTAssertEqual(WatchText.t("Unlock"), "Unlock", "a language the watch has no words for falls back to English")
    }
}
