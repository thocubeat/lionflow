import Foundation

/// Demo mode: a sample car with made-up data, for trying the app without an account.
/// Nothing is sent anywhere; commands only change the sample car. The numbers are the same as in the Android app.
enum Demo {
    static let VIN = "VR3DEMOLIONFLOW00"
    private static let kinds: Set<String> = ["electric", "hybrid", "fuel"]
    private static let lock = NSLock()

    /// Which sample car is shown: "electric", "hybrid" (plug-in hybrid) or "fuel" (petrol or diesel). Chosen when
    /// the demo starts.
    private(set) static var kind = "electric"
    static var clock: () -> Int64 = { nowMillis() }

    static func load(_ store: KeyValueStore) {
        let k = store.string("kind") ?? "electric"
        kind = kinds.contains(k) ? k : "electric"
    }
    static func choose(_ store: KeyValueStore, _ k: String) {
        kind = kinds.contains(k) ? k : "electric"
        store.set("kind", kind)
    }
    static func isOn(_ store: KeyValueStore) -> Bool { store.string("on") == "true" }
    static func setOn(_ store: KeyValueStore, _ on: Bool) { store.set("on", on ? "true" : "false") }

    /** The sample car, of the chosen kind and of the brand picked for the demo. */
    static var vehicle: Vehicle {
        Vehicle(id: "demo", vin: VIN, motorization: kind == "fuel" ? "Thermal" : (kind == "hybrid" ? "PHEV" : "Electric"),
                brand: Brands.current.display, pictureUrl: nil, maintenanceHref: nil)
    }

    // A neutral spot (Paris) for the sample car's home.
    private static let homeLat = 48.8566
    private static let homeLon = 2.3522

    // The sample car's state; commands change it.
    private static var battery = 64.0
    private static var locked = true
    private static var precond = false
    private static var charging = true
    private static var plugged = true
    private static var chargeType = "Partial"
    private static var programs = [
        Program(slot: 1, days: ["Mon", "Tue", "Wed", "Thu", "Fri"], hour: 7, minute: 15, enabled: true),
        Program(slot: 2, days: ["Sat"], hour: 9, minute: 0, enabled: false),
        Program(slot: 3, days: [], hour: 0, minute: 0, enabled: false),
        Program(slot: 4, days: [], hour: 0, minute: 0, enabled: false),
    ]

    static func reset() {
        battery = 64.0
        locked = true
        precond = false
        charging = true
        plugged = true
        chargeType = "Partial"
    }

    // The tank of the hybrid and the petrol/diesel sample car.
    private static let fuelPct = 58.0
    private static let fuelRange = 530.0

    static func status() -> VehicleStatus {
        let now = clock()
        let fuelOnly = kind == "fuel"
        let hybrid = kind == "hybrid"
        // A plug-in hybrid has a small battery: about 12 kWh and 55 km when full, charged at 3.7 kW.
        let cap = hybrid ? 12.4 : 73.0
        let evRange = hybrid ? battery * 0.55 : battery * 4.3
        let on = charging && !fuelOnly
        var remaining: Int? = nil
        if on { remaining = hybrid ? JSONValue.javaInt(max(100 - battery, 0.0) * 2) : JSONValue.javaInt(max(80 - battery, 0.0) * 9) }
        var fuelTotal: Double? = nil
        // The lifetime fuel counter, in hundredths of a litre: 5.9 l/100 km since new (3.6 for the plug-in hybrid).
        if fuelOnly { fuelTotal = 13_412.0 * 5.9 } else if hybrid { fuelTotal = 13_412.0 * 3.6 }
        return VehicleStatus(
            raw: raw(now, cap, evRange),
            batteryPct: fuelOnly ? fuelPct : battery, rangeKm: fuelOnly ? fuelRange : evRange, plugged: plugged && !fuelOnly,
            chargingStatus: fuelOnly ? nil : (charging ? "InProgress" : (plugged ? "Stopped" : "Disconnected")),
            chargingMode: fuelOnly ? nil : (charging ? "Slow" : "No"), chargingRateKmh: on ? (hybrid ? 16.0 : 48.0) : nil,
            remainingMinutes: remaining,
            nextDelayedHour: fuelOnly ? nil : 1, nextDelayedMinute: fuelOnly ? nil : 0,
            batteryHealthPct: fuelOnly ? nil : 98.0, odometerKm: 13_412.0,
            locked: locked, openDoors: [], outsideTempC: 6.0, preconditioningOn: precond && !fuelOnly,
            programs: fuelOnly ? [] : programs, lat: homeLat, lon: homeLon, ignition: "Stop", moving: false,
            updatedAtMillis: now - 2 * 60_000, kmToService: 11_588.0, daysToService: 270.0,
            residualKwh: fuelOnly ? nil : battery / 100 * cap, capacityKwh: fuelOnly ? nil : cap,
            energyAtMillis: fuelOnly ? nil : now, chargeType: fuelOnly ? nil : chargeType,
            energyType: fuelOnly ? "Fuel" : (hybrid ? "Hybrid" : "Electric"),
            fuelPct: (fuelOnly || hybrid) ? fuelPct : nil, fuelRangeKm: (fuelOnly || hybrid) ? fuelRange : nil,
            heading: 118.0, privacyState: "None",
            fuelTotalRaw: fuelTotal)
    }

    /** What the carmaker's server would have sent for the sample car, so "All car data" has something to show. */
    private static func raw(_ now: Int64, _ cap: Double, _ evRange: Double) -> JSONObject {
        let fuelOnly = kind == "fuel"
        let at = isoUTC(now - 2 * 60_000)
        let energies = JSONArray()
        if !fuelOnly {
            let chargingJson = JSONObject()
            chargingJson.put("plugged", plugged)
            chargingJson.put("status", charging ? "InProgress" : (plugged ? "Stopped" : "Disconnected"))
            chargingJson.put("chargingMode", charging ? "Slow" : "No")
            chargingJson.put("type", chargeType)
            let load = JSONObject()
            load.put("capacity", (cap * 1000).roundToLong)
            load.put("residual", (battery / 100 * cap * 1000).roundToLong)
            let health = JSONObject()
            health.put("capacity", 98)
            let batteryJson = JSONObject()
            batteryJson.put("load", load)
            batteryJson.put("health", health)
            let electric = JSONObject()
            electric.put("charging", chargingJson)
            electric.put("battery", batteryJson)
            let ext = JSONObject()
            ext.put("electric", electric)
            let e = JSONObject()
            e.put("type", "Electric")
            e.put("level", battery)
            e.put("autonomy", evRange.roundToLong)
            e.put("updatedAt", at)
            e.put("extension", ext)
            energies.put(e)
        }
        if kind != "electric" {
            let f = JSONObject()
            f.put("type", "Fuel")
            f.put("level", fuelPct)
            f.put("autonomy", fuelRange)
            f.put("updatedAt", at)
            energies.put(f)
        }
        func one(_ key: String, _ value: JSONValue) -> JSONObject { JSONObject().put(key, value) }
        let doors = JSONObject()
        doors.put("lockedStates", JSONArray().put(locked ? "Locked" : "Unlocked"))
        doors.put("opening", JSONArray())
        let kinetic = JSONObject()
        kinetic.put("moving", false)
        kinetic.put("speed", 0)
        let fogLight = JSONObject()
        fogLight.put("direction", "Front")
        fogLight.put("status", "Off")
        let geometry = JSONObject()
        geometry.put("type", "Point")
        geometry.put("coordinates", JSONArray().put(homeLon).put(homeLat))
        let position = JSONObject()
        position.put("type", "Feature")
        position.put("geometry", geometry)
        position.put("properties", one("heading", .int(118)))
        let j = JSONObject()
        j.put("demo", true)
        j.put("updatedAt", at)
        j.put("energies", energies)
        j.put("odometer", one("mileage", .double(13_412.0)))
        j.put("battery", one("voltage", .double(12.6)))
        j.put("doorsState", doors)
        j.put("environment", one("air", .object(one("temp", .double(6.0)))))
        j.put("ignition", one("type", .string("Stop")))
        j.put("kinetic", kinetic)
        j.put("privacy", one("state", .string("None")))
        j.put("lightingSystem", one("fog", .array(JSONArray().put(fogLight))))
        j.put("lastPosition", position)
        // Petrol and diesel cars have no pre-heating, so the Climate tab is left out for them, as on a real one.
        if !fuelOnly {
            j.put("preconditioning", one("airConditioning", .object(one("status", .string(precond ? "Enabled" : "Disabled")))))
        }
        return j
    }

    /** Applies a simulated command to the sample car. */
    static func apply(_ label: String, _ programsNew: [Program]? = nil) {
        switch label {
        case "Lock": locked = true
        case "Unlock": locked = false
        case "Pre-heating": precond = true
        case "Stop pre-heating": precond = false
        case "Charge now":
            plugged = true
            charging = true
        case "Stop charging": charging = false
        case "Daily charge limit": chargeType = "Partial"
        case "Full charge for a trip": chargeType = "Full"
        default: break
        }
        if let p = programsNew { programs = p }
    }

    /// A month of drives for the electric car. The other two get four months, so the fuel statistics have months and
    /// fill-ups to show: the petrol/diesel car burns fuel on every trip, the plug-in hybrid drives short trips on the
    /// battery and uses the engine on the long ones.
    static func trips() -> [Trip] {
        var r = KotlinRandom(42)
        let day: Int64 = 86_400_000
        let days = kind == "electric" ? 30 : 120
        let start = clock() - Int64(days) * day
        var out: [Trip] = []
        for d in 0..<days {
            let n = r.nextInt(1, 4)
            for i in 0..<n {
                let hour = 7 + i * 5 + r.nextInt(0, 3)
                let t = start + Int64(d) * day + Int64(hour) * 3_600_000 + Int64(r.nextInt(0, 50)) * 60_000
                let km = r.nextInt(10) == 0 ? r.nextDouble(60.0, 140.0) : r.nextDouble(3.0, 28.0)
                let speed = km > 50 ? r.nextDouble(70.0, 95.0) : r.nextDouble(28.0, 55.0)
                let sec = km / speed * 3600
                let per100 = r.nextDouble(15.5, 21.0)
                let end = t + JSONValue.javaLong(sec * 1000)
                let id = "demo-\(d)-\(i)"
                switch kind {
                case "fuel":
                    let l100 = km > 50 ? r.nextDouble(4.6, 5.8) : r.nextDouble(5.6, 8.4)
                    out.append(Trip(id: id, startMillis: t, endMillis: end, distanceKm: km, durationSec: sec, kwh: nil, avgKwhPer100: nil, avgSpeedKmh: speed,
                                    liters: km * l100 / 100, avgLPer100: l100))
                case "hybrid":
                    // The first 40 km of a trip on the battery, the rest on the engine.
                    let evKm = min(km, 40.0)
                    let liters: Double? = km > evKm ? (km - evKm) * r.nextDouble(5.8, 6.8) / 100 : nil
                    out.append(Trip(id: id, startMillis: t, endMillis: end, distanceKm: km, durationSec: sec, kwh: evKm * per100 / 100,
                                    avgKwhPer100: liters == nil ? per100 : nil, avgSpeedKmh: speed,
                                    liters: liters, avgLPer100: liters.map { $0 / km * 100 }))
                default:
                    out.append(Trip(id: id, startMillis: t, endMillis: end, distanceKm: km, durationSec: sec, kwh: km * per100 / 100, avgKwhPer100: per100, avgSpeedKmh: speed))
                }
            }
        }
        if kind == "electric" { return stableSorted(out) { ($0.startMillis ?? 0) > ($1.startMillis ?? 0) } }
        // The fuel gauge before and after each trip, with a fill-up whenever the tank gets low.
        let tank = kind == "hybrid" ? 43.0 : 47.0
        var level = 82.0
        var gauged: [Trip] = []
        for trip in stableSorted(out, by: { ($0.startMillis ?? 0) < ($1.startMillis ?? 0) }) {
            if level < 18 { level = 95.0 + Double(r.nextInt(0, 5)) }
            let after = max(level - (trip.liters ?? 0.0) / tank * 100, 3.0)
            var withGauge = trip
            withGauge.fuelStartPct = Double(level.roundToLong)
            withGauge.fuelEndPct = Double(after.roundToLong)
            level = after
            gauged.append(withGauge)
        }
        return stableSorted(gauged) { ($0.startMillis ?? 0) > ($1.startMillis ?? 0) }
    }

    static func places() -> PlacesConfig {
        PlacesConfig(
            home: ChargePlace(id: "home", name: "Home", address: "Sample street 1, Paris", lat: homeLat, lon: homeLon, price: 0.25),
            known: [ChargePlace(id: "k1", name: "Work", address: "Office car park", lat: homeLat + 0.03, lon: homeLon + 0.04, price: 0.0)],
            publicAcPrice: 0.45, publicDcPrice: 0.69, currency: "€")
    }

    /// Charging sessions for the log: none for the petrol/diesel car; the plug-in hybrid only charges slowly, at home
    /// and at work.
    static func sessions() -> [ChargeSession] {
        if kind == "fuel" { return [] }
        let hybrid = kind == "hybrid"
        let cap = hybrid ? 12.4 : 73.0
        var r = KotlinRandom(7)
        let day: Int64 = 86_400_000
        let now = clock()
        var out: [ChargeSession] = []
        for i in 1...14 {
            // 0 home, 1 work, 2 fast charger
            var place = 0
            if i % 5 == 0 { place = hybrid ? 0 : 2 } else if i % 5 == 3 { place = 1 }
            let s = now - Int64(i) * 2 * day - Int64(r.nextInt(0, 6)) * 3_600_000
            let from = r.nextDouble(18.0, 50.0)
            let to = place == 2 ? r.nextDouble(70.0, 82.0) : (hybrid ? 100.0 : 80.0)
            let kwh = (to - from) / 100 * cap
            let kw = place == 2 ? r.nextDouble(70.0, 110.0) : (hybrid ? r.nextDouble(3.3, 3.7) : r.nextDouble(6.5, 10.5))
            let lat = place == 0 ? homeLat : (place == 1 ? homeLat + 0.03 : homeLat + 0.4)
            let lon = place == 0 ? homeLon : (place == 1 ? homeLon + 0.04 : homeLon - 0.3)
            let addr = place == 0 ? "Sample street 1, Paris" : (place == 1 ? "Office car park" : "Motorway services A10")
            out.append(ChargeSession(id: s, start: s, end: s + JSONValue.javaLong(kwh / kw * 3_600_000), startPct: from, endPct: to, kwh: kwh,
                                     peakKw: kw * 1.15, mode: place == 2 ? "Quick" : "Slow", lat: lat, lon: lon, address: addr,
                                     source: place == 2 ? "Fast charger" : nil))
        }
        return out
    }
}
