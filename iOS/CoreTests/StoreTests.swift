import XCTest
@testable import LionFlowCore

final class StoreTests: XCTestCase {
    private func freshDefaults() -> UserDefaults {
        let name = "lionflow-test-" + UUID().uuidString
        let d = UserDefaults(suiteName: name)!
        d.removePersistentDomain(forName: name)
        return d
    }

    override func tearDown() { Brands.current = .peugeot }

    func testSettingsKeepTheirDefaultsAndSurviveSaving() {
        let s = AppSettings()
        XCTAssertEqual(s.theme, "dark")
        XCTAssertEqual(s.country, "NO")
        XCTAssertEqual(s.homeHidden, "charging,monthCost")
        XCTAssertEqual(s.chargeWindowFrom, 22 * 60)
        XCTAssertEqual(AppSettings(s.toJSON()), s)

        var changed = s
        changed.theme = "light"
        changed.brand = "opel"
        changed.autoRefresh = false
        changed.chargeLimitPct = 70
        changed.fuelPrice = 21.49
        changed.oilChangeMillis = 1_795_000_000_000
        changed.homeOrder = "cardata,parked"
        let back = AppSettings(changed.toJSON())
        XCTAssertEqual(back, changed)
        XCTAssertNotEqual(back, s)

        // A setting that isn't saved yet keeps its default; something unknown is ignored.
        let partial = AppSettings(JSONObject().put("theme", "system").put("somethingNew", 1))
        XCTAssertEqual(partial.theme, "system")
        XCTAssertEqual(partial.distanceUnit, "km")
        XCTAssertTrue(partial.bioUnlock)
    }

    func testSettingsStoreSavesAndTellsTheApp() {
        let d = freshDefaults()
        let store = SettingsStore(d)
        var told: [String] = []
        store.onChange = { told.append($0.brand) }
        store.update { $0.brand = "citroen"; $0.tempUnit = "f" }
        XCTAssertEqual(told, ["citroen"])
        XCTAssertEqual(Brands.current, .citroen)
        XCTAssertEqual(store.value.tempUnit, "f")

        let again = SettingsStore(d)
        XCTAssertEqual(again.value.brand, "citroen")
        XCTAssertEqual(again.value.tempUnit, "f")
        XCTAssertEqual(again.accounts(), [SettingsStore.Account(id: "", brand: "citroen", country: "NO")])

        again.saveAccounts([SettingsStore.Account(id: "", brand: "citroen", country: "NO"), SettingsStore.Account(id: "a2", brand: "opel", country: "DE")])
        XCTAssertEqual(again.accounts().map { $0.id }, ["", "a2"])
        XCTAssertNil(again.mildHybridOverride("VIN1"))
        again.setMildHybridOverride("VIN1", true)
        XCTAssertEqual(again.mildHybridOverride("VIN1"), true)
        again.setMildHybridOverride("VIN1", nil)
        XCTAssertNil(again.mildHybridOverride("VIN1"))

        XCTAssertTrue(again.firstRun())
        XCTAssertFalse(again.firstRun())
        again.lastRefresh = 1_795_000_000_123
        XCTAssertEqual(again.lastRefresh, 1_795_000_000_123)
        again.clearPersonal()
        XCTAssertEqual(again.lastRefresh, 0)
    }

    func testSecretsAreKeptPerAccount() {
        let store = SecureStore(MemorySecrets())
        store.put("refresh", "first")
        store.put("pin", "1234")
        store.profile = "a2"
        XCTAssertNil(store.get("refresh"))
        store.put("refresh", "second", now: true)
        XCTAssertEqual(store.get("refresh"), "second")
        XCTAssertEqual(store.get("refresh", ""), "first")
        // A token that arrives for another account than the one on screen goes to its own place.
        store.put("refresh", "first-renewed", "", now: true)
        XCTAssertEqual(store.get("refresh"), "second")
        XCTAssertEqual(store.get("refresh", ""), "first-renewed")

        store.clear()
        XCTAssertNil(store.get("refresh"))
        XCTAssertEqual(store.get("refresh", ""), "first-renewed")
        store.profile = ""
        XCTAssertEqual(store.get("pin"), "1234")
        store.put("pin", nil)
        XCTAssertNil(store.get("pin"))
        store.wipeAll()
        XCTAssertNil(store.get("refresh"))
    }

    func testAddressesAreEncodedLikeAndroid() {
        XCTAssertEqual(urlEncode("a b&c=d/é*~"), "a+b%26c%3Dd%2F%C3%A9*%7E")
        XCTAssertEqual(urlEncode("mymap://oauth2redirect/no"), "mymap%3A%2F%2Foauth2redirect%2Fno")
    }

    func testOddNumbersAsText() {
        XCTAssertEqual(JSONValue.javaParseDouble(" 4.5 "), 4.5)
        XCTAssertEqual(JSONValue.javaParseDouble("1e2"), 100)
        XCTAssertEqual(JSONValue.javaParseDouble(".5"), 0.5)
        XCTAssertEqual(JSONValue.javaParseDouble("7."), 7)
        XCTAssertEqual(JSONValue.javaParseDouble("-3"), -3)
        XCTAssertEqual(JSONValue.javaParseDouble("+2.5d"), 2.5)
        XCTAssertEqual(JSONValue.javaParseDouble("0x1p3"), 8)
        XCTAssertEqual(JSONValue.javaParseDouble("-Infinity"), -Double.infinity)
        XCTAssertTrue(JSONValue.javaParseDouble("NaN")?.isNaN ?? false)
        for bad in ["", " ", "0x10", "12abc", "1e", "e5", ".", "--1", "1 2", "nan", "infinity", "1,5"] {
            XCTAssertNil(JSONValue.javaParseDouble(bad), bad)
        }
    }
}
