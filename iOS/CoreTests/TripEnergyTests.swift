import XCTest
@testable import LionFlowCore

final class TripEnergyTests: XCTestCase {
    private func trip(_ json: String, mildHybrid: Bool = false) throws -> Trip { Trip.parse(try JSONObject(json), mildHybrid: mildHybrid) }

    func testTheCarsOwnFigureIsKept() throws {
        let t = TripEnergy.fill(try trip(#"{"id":"a","distance":12.4,"energyConsumptions":[{"type":"Electric","consumption":2150,"avgConsumption":17340}]}"#), batteryKwh: 73)
        XCTAssertEqual(t.kwh!, 2.15, accuracy: 1e-9)
        XCTAssertEqual(t.avgKwhPer100!, 17.34, accuracy: 1e-9)
        XCTAssertFalse(t.energyEstimated)
    }

    func testKwhSentInsteadOfWhIsRescaled() throws {
        let t = TripEnergy.fill(try trip(#"{"id":"a","distance":20,"energyConsumptions":[{"type":"Electric","consumption":3.6}]}"#), batteryKwh: 73)
        XCTAssertEqual(t.kwh!, 3.6, accuracy: 1e-9)
        XCTAssertEqual(t.avgKwhPer100!, 18.0, accuracy: 1e-9)
        XCTAssertFalse(t.energyEstimated)
    }

    func testBatteryDropGivesKwhWhenTheCarSendsNone() throws {
        let t = TripEnergy.fill(try trip(#"{"id":"a","distance":40,"energyConsumptions":[{"type":"Electric","consumption":0}],"startEnergies":[{"type":"Electric","level":80}],"endEnergies":[{"type":"Electric","level":70}]}"#), batteryKwh: 73)
        XCTAssertEqual(t.kwh!, 7.3, accuracy: 1e-9)
        XCTAssertEqual(t.avgKwhPer100!, 18.25, accuracy: 1e-9)
        XCTAssertTrue(t.energyEstimated)
    }

    func testAverageTimesDistance() throws {
        let t = TripEnergy.fill(try trip(#"{"id":"a","distance":50,"energyConsumptions":[{"type":"Electric","avgConsumption":16000}]}"#), batteryKwh: nil)
        XCTAssertEqual(t.kwh!, 8.0, accuracy: 1e-9)
        XCTAssertTrue(t.energyEstimated)
    }

    func testALoggedTripFillsTheGap() {
        let logged = Trip(id: "car-1", startMillis: 1_000_000, endMillis: 2_800_000, distanceKm: 15.2, durationSec: 1800, kwh: 2.9, avgKwhPer100: 19.1, avgSpeedKmh: nil)
        let t = TripEnergy.fill(Trip(id: "s", startMillis: 1_100_000, endMillis: 2_700_000, distanceKm: 15, durationSec: 1600, kwh: nil, avgKwhPer100: nil, avgSpeedKmh: nil), batteryKwh: nil, logged: [logged])
        XCTAssertEqual(t.kwh!, 2.9, accuracy: 1e-9)
        XCTAssertTrue(t.energyEstimated)
    }

    func testNonsenseIsLeftOut() throws {
        let t = TripEnergy.fill(try trip(#"{"id":"a","distance":2,"startEnergies":[{"type":"Electric","level":80}],"endEnergies":[{"type":"Electric","level":60}]}"#), batteryKwh: 73)
        XCTAssertNil(t.kwh)
    }

    func testAMildHybridGetsNoBatteryEstimate() throws {
        let t = TripEnergy.fill(try trip(#"{"id":"a","distance":40,"startEnergies":[{"type":"Electric","level":80}],"endEnergies":[{"type":"Electric","level":70}]}"#, mildHybrid: true), batteryKwh: 0.9)
        XCTAssertNil(t.kwh)
    }
}
