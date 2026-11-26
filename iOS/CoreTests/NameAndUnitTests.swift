import XCTest
@testable import LionFlowCore

/// The car's default name follows the account's brand, and speeds follow the distance unit.
final class NameAndUnitTests: XCTestCase {
    func testTheUntouchedDefaultNameFollowsTheBrand() throws {
        let suite = "lionflow-name-test-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite); Brands.current = .peugeot }
        let store = SettingsStore(defaults)
        XCTAssertEqual(store.value.carName, "My Peugeot")
        store.update { $0.brand = "vauxhall" }
        XCTAssertEqual(store.value.carName, "My Vauxhall")
        // A name the user typed stays, whatever the brand.
        store.update { $0.carName = "Family car" }
        store.update { $0.brand = "opel" }
        XCTAssertEqual(store.value.carName, "Family car")
        // A name saved for a car before this fix is read in the brand of the account now in use.
        store.setNameFor("VIN1", "My Peugeot")
        XCTAssertEqual(store.nameFor("VIN1"), "My Opel")
        // Settings saved with the old default on a Vauxhall account are put right when the app starts.
        store.update { $0.brand = "vauxhall" }
        defaults.set(defaults.string(forKey: "settings")?.replacingOccurrences(of: "Family car", with: "My Peugeot"), forKey: "settings")
        XCTAssertEqual(SettingsStore(defaults).value.carName, "My Vauxhall")
        // On a Peugeot account nothing changes.
        XCTAssertEqual(SettingsStore.brandName("My Peugeot", .peugeot), "My Peugeot")
    }

    func testSpeedsFollowTheDistanceUnit() {
        var s = AppSettings()
        s.distanceUnit = "km"
        XCTAssertEqual(fmtSpeed(80, s), "80 km/h")
        s.distanceUnit = "mi"
        XCTAssertEqual(fmtSpeed(80, s), "50 mph")
        XCTAssertEqual(fmtSpeed(112.7, s), "70 mph")
    }
}
