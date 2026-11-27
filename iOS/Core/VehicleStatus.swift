import Foundation

/** One of the car's four pre-heating schedules. */
struct Program: Equatable {
    var slot: Int
    var days: [String]
    var hour: Int
    var minute: Int
    var enabled: Bool
}

/// The car's state as the carmaker's server last had it. Read from the server's answer the same way the Android
/// app reads it, field for field.
struct VehicleStatus: Equatable {
    var raw: JSONObject
    var batteryPct: Double?
    var rangeKm: Double?
    var plugged: Bool
    var chargingStatus: String?       // InProgress | Finished | Stopped | Disconnected | Failure
    var chargingMode: String?         // Slow | Quick | No
    var chargingRateKmh: Double?
    var remainingMinutes: Int?
    var nextDelayedHour: Int?
    var nextDelayedMinute: Int?
    var batteryHealthPct: Double?
    var odometerKm: Double?
    var locked: Bool?
    var openDoors: [String]
    var outsideTempC: Double?
    var preconditioningOn: Bool
    var programs: [Program]
    var lat: Double?
    var lon: Double?
    var ignition: String?
    var moving: Bool
    var updatedAtMillis: Int64?
    var kmToService: Double?
    var daysToService: Double?
    var residualKwh: Double? = nil    // energy in the battery now
    var capacityKwh: Double? = nil    // usable capacity reported by the car
    var energyAtMillis: Int64? = nil  // when the battery reading was taken
    var rangeEstimated: Bool = false

    var chargeType: String? = nil
    var energyType: String = "Electric"   // Electric | Fuel | Hybrid
    var fuelPct: Double? = nil
    var fuelRangeKm: Double? = nil
    var isMildHybrid: Bool = false
    var heading: Double? = nil            // the way the car points, in degrees (0 = north)
    var privacyState: String? = nil       // None | Geolocation | Full
    var fogLightsOn: [String] = []        // "Front", "Rear" … for each fog light that is on
    var alarmOn: Bool? = nil
    var fuelTotalRaw: Double? = nil       // the car's lifetime fuel counter, in its own (undocumented) unit

    var isCharging: Bool { chargingStatus == "InProgress" }

    /** Privacy mode in the car: it blocks remote commands and can hide the position. */
    var privacyOn: Bool {
        guard let p = privacyState, !p.allSatisfy({ $0.isWhitespace }) else { return false }
        return p != "None"
    }

    /** The program block the remote-preconditioning command expects. */
    func programsJson() -> JSONObject { VehicleStatus.programsJsonOf(programs) }

    /** The car's 4 schedule slots. An empty slot is sent as 34:07, off (as MyPeugeot does). */
    static func programsJsonOf(_ programs: [Program]) -> JSONObject {
        let out = JSONObject()
        for slot in 1...4 {
            let p = programs.first { $0.slot == slot }
            let days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"].map { (p?.days.contains($0) ?? false) ? 1 : 0 }
            out.put("program\(slot)", JSONObject()
                .put("day", JSONArray(days))
                .put("hour", p?.hour ?? 34)
                .put("minute", p?.minute ?? 7)
                .put("on", p?.enabled == true ? 1 : 0))
        }
        return out
    }

    /// The battery level of the electric part. Some plug-in hybrids (seen on a 2017 DS 7) report "100 %" with
    /// 0 km of electric range while the battery is actually empty: a full plug-in battery always gives some
    /// range, so 100 % with 0 km (and not charging) is shown as empty.
    private static func hybridLevel(_ electric: JSONObject, _ fuel: JSONObject?, _ charging: JSONObject?) -> Double? {
        guard let level = electric.num("level") else { return fuel != nil ? 0.0 : nil }
        let plugged = charging?.optBoolean("plugged") == true || charging?.str("status") == "InProgress"
        if fuel != nil && !plugged && level >= 95 && electric.num("autonomy") == 0.0 { return 0.0 }
        return level
    }

    static func parse(_ j: JSONObject, _ maintenance: JSONObject?, mildHybrid: Bool = false, requireChargingEvidence: Bool = false) -> VehicleStatus {
        // "energy" is the older name, still seen from some cars.
        let energies = (j.optJSONArray("energies") ?? j.optJSONArray("energy"))?.objects() ?? []
        let reportedElectric = energies.first { $0.optString("type") == "Electric" }
        let reportedCharging = reportedElectric?.obj("extension", "electric", "charging")
        // Stellantis can report a full or partial charge target even when the car is unplugged.
        let rcStatus = reportedCharging?.str("status")
        let rcType = reportedCharging?.str("type")
        let supportsExternalCharging = reportedCharging?.optBoolean("plugged") == true ||
            (rcStatus == "InProgress" || rcStatus == "Finished") ||
            (rcType == "Full" || rcType == "Partial")
        let fuelOnly = mildHybrid || (requireChargingEvidence && !supportsExternalCharging)
        let electric: JSONObject? = fuelOnly ? nil : reportedElectric
        let fuel = energies.first { $0.optString("type") == "Fuel" }
        let charging = electric?.obj("extension", "electric", "charging")
        let health = electric?.obj("extension", "electric", "battery", "health")
        let load = electric?.obj("extension", "electric", "battery", "load")
        func kwh(_ k: String) -> Double? {
            guard let v = load?.num(k), v >= 1 else { return nil }
            return v / 1000.0
        }
        var cap = kwh("capacity")
        if let c = cap, !(c >= 30.0 && c <= 130.0) { cap = nil }
        var res = kwh("residual")
        if let r = res, let c = cap, !(r <= c * 1.05) { res = nil }
        let doors = j.optJSONObject("doorsState")
        let lockedStates: [String] = doors?.optJSONArray("lockedStates")?.strings() ?? []
        let openings = (doors?.optJSONArray("opening")?.objects() ?? [])
            .filter { $0.optString("state") == "Open" }.map { $0.optString("identifier") }
        let coords = j.obj("lastPosition", "geometry")?.optJSONArray("coordinates")
        let ac = j.obj("preconditioning", "airConditioning")
        let programs: [Program] = (ac?.optJSONArray("programs")?.objects() ?? []).compactMap { p in
            guard let start = parsePt(p.optString("start")) else { return nil }
            let days: [String] = p.optJSONObject("occurence")?.optJSONArray("day")?.strings() ?? []
            return Program(slot: p.optInt("slot"), days: days, hour: start.0, minute: start.1, enabled: p.optBoolean("enabled"))
        }
        let next = charging?.str("nextDelayedTime").flatMap { parsePt($0) }
        let remaining = charging?.str("remainingTime").flatMap { parsePt($0) }.map { $0.0 * 60 + $0.1 }
        let m = maintenance ?? j.optJSONObject("maintenance")

        // Petrol/diesel cars: the fuel tank stands in for the battery. A plug-in hybrid never shows the
        // tank as its battery: some (older DS7 / 3008 Hybrid4) leave out the battery level when it is empty.
        let batteryPct: Double?
        let rangeKm: Double?
        if let e = electric {
            batteryPct = hybridLevel(e, fuel, charging)
            rangeKm = e.num("autonomy") ?? ((fuel != nil && e.num("level") == nil) ? 0.0 : nil)
        } else {
            batteryPct = fuel?.num("level")
            rangeKm = fuel?.num("autonomy")
        }
        // Non-plug-in hybrids use the existing fuel-only display capabilities.
        let energyType: String
        if fuelOnly || (electric == nil && fuel != nil) { energyType = "Fuel" }
        else if electric != nil && fuel != nil { energyType = "Hybrid" }
        else { energyType = "Electric" }

        func finite(_ d: Double?) -> Double? { (d == nil || d!.isNaN) ? nil : d }
        var heading = j.obj("lastPosition", "properties")?.num("heading")
        if let h = heading, !(h >= 0.0 && h <= 360.0) { heading = nil }
        let fog = (j.optJSONObject("lightingSystem")?.optJSONArray("fog")?.objects() ?? [])
            .filter { $0.optString("status") == "On" }
            .map { [$0.str("direction"), $0.str("position")].compactMap { $0 }.joined(separator: " ") }
        var fuelTotal = fuel?.obj("extension", "fuel", "consumptions")?.num("total")
        if let t = fuelTotal, !(t > 0) { fuelTotal = nil }

        return VehicleStatus(
            raw: j,
            batteryPct: batteryPct,
            rangeKm: rangeKm,
            plugged: charging?.optBoolean("plugged") ?? false,
            chargingStatus: charging?.str("status"),
            chargingMode: charging?.str("chargingMode"),
            chargingRateKmh: charging?.num("chargingRate"),
            remainingMinutes: remaining,
            nextDelayedHour: next?.0,
            nextDelayedMinute: next?.1,
            batteryHealthPct: health?.num("capacity") ?? health?.num("resistance"),
            odometerKm: j.optJSONObject("odometer")?.num("mileage"),
            locked: lockedStates.isEmpty ? nil : lockedStates.contains { $0.contains("Locked") && !$0.contains("Unlocked") },
            openDoors: openings,
            outsideTempC: j.obj("environment", "air")?.num("temp"),
            preconditioningOn: ac?.optString("status") == "Enabled",
            programs: programs,
            lat: finite(coords?.optDouble(1)),
            lon: finite(coords?.optDouble(0)),
            ignition: j.optJSONObject("ignition")?.str("type"),
            moving: j.optJSONObject("kinetic")?.optBoolean("moving") ?? false,
            updatedAtMillis: parseIso(j.optString("updatedAt")),
            kmToService: m?.num("mileageBeforeMaintenance"),
            daysToService: m?.num("daysBeforeMaintenance"),
            residualKwh: res,
            capacityKwh: cap,
            energyAtMillis: parseIso(electric?.optString("updatedAt")),
            chargeType: charging?.str("type"),
            energyType: energyType,
            fuelPct: fuel?.num("level"),
            fuelRangeKm: fuel?.num("autonomy"),
            isMildHybrid: mildHybrid,
            heading: heading,
            privacyState: j.optJSONObject("privacy")?.str("state"),
            fogLightsOn: fog,
            alarmOn: j.obj("alarm", "status")?.str("activation").map { $0 == "Active" },
            fuelTotalRaw: fuelTotal
        )
    }

    /** "PT7H30M" -> (7, 30) */
    static func parsePt(_ s: String?) -> (Int, Int)? {
        guard let s = s, !s.allSatisfy({ $0.isWhitespace }), s.hasPrefix("PT") else { return nil }
        return (digitsBefore("H", in: s) ?? 0, digitsBefore("M", in: s) ?? 0)
    }

    /** The first run of digits that stands right before the given letter. */
    private static func digitsBefore(_ letter: Character, in s: String) -> Int? {
        let chars = Array(s)
        var i = 0
        while i < chars.count {
            if chars[i].isASCII && chars[i].isNumber {
                var j = i
                while j < chars.count && chars[j].isASCII && chars[j].isNumber { j += 1 }
                if j < chars.count && chars[j] == letter { return Int(String(chars[i..<j])) }
                i = j
            } else {
                i += 1
            }
        }
        return nil
    }

    /// A time from the server ("2026-11-26T09:08:40Z", with or without milliseconds, with "Z" or an offset like
    /// "+01:00") as milliseconds since 1970. Nil for anything else.
    ///
    /// Android reads these with Java's lenient date reader, and this follows it: digits after the point count as
    /// milliseconds as they stand (".5" is 5 ms), a field may have any number of digits or spaces in front, values
    /// past their range roll over (month 13 is January), and anything after the time zone is ignored.
    static func parseIso(_ s: String?) -> Int64? {
        guard let s = s, !s.allSatisfy({ $0.isWhitespace }) else { return nil }
        let c = Array(s.utf8)
        var p = 0
        func skipBlanks() { while p < c.count && (c[p] == 0x20 || c[p] == 0x09) { p += 1 } }
        func number() -> Int64? {
            skipBlanks()
            let start = p
            var v: Int64 = 0
            while p < c.count && c[p] >= 0x30 && c[p] <= 0x39 {
                v = v &* 10 &+ Int64(c[p] - 0x30)
                p += 1
            }
            return p > start ? v : nil
        }
        func skip(_ byte: UInt8) -> Bool {
            if p < c.count && c[p] == byte { p += 1; return true }
            return false
        }
        guard let year = number(), skip(0x2D), let month = number(), skip(0x2D), let day = number(), skip(0x54),
              let hour = number(), skip(0x3A), let minute = number(), skip(0x3A), let second = number() else { return nil }
        var millis: Int64 = 0
        if skip(0x2E) {
            guard let frac = number() else { return nil }
            millis = frac
        }
        var offsetMinutes: Int64 = 0
        if skip(0x5A) {
            // "Z": UTC
        } else {
            skipBlanks()
            if skip(0x5A) {
                // UTC after a space
            } else if p < c.count && (c[p] == 0x2B || c[p] == 0x2D) {
                let sign: Int64 = c[p] == 0x2D ? -1 : 1
                p += 1
                guard p + 5 <= c.count, c[p + 2] == 0x3A else { return nil }
                let digits = [c[p], c[p + 1], c[p + 3], c[p + 4]]
                guard digits.allSatisfy({ $0 >= 0x30 && $0 <= 0x39 }) else { return nil }
                let h = Int64(digits[0] - 0x30) * 10 + Int64(digits[1] - 0x30)
                let m = Int64(digits[2] - 0x30) * 10 + Int64(digits[3] - 0x30)
                if h > 23 || m > 59 { return nil }
                offsetMinutes = sign * (h * 60 + m)
            } else {
                return nil
            }
        }
        // Days since 1970 for a date in the Gregorian calendar; months outside 1…12 roll over into other years.
        var y = year
        var mo = month - 1
        y += mo >= 0 ? mo / 12 : -((11 - mo) / 12)
        mo = ((mo % 12) + 12) % 12
        let yy = mo < 2 ? y - 1 : y
        let era = (yy >= 0 ? yy : yy - 399) / 400
        let yoe = yy - era * 400
        let mp = (mo + 10) % 12
        let doy = (153 * mp + 2) / 5
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = era * 146097 + doe - 719468 + (day - 1)
        let seconds = days * 86400 + hour * 3600 + minute * 60 + second - offsetMinutes * 60
        return seconds * 1000 + millis
    }
}

/** One drive, as the car reported it. */
struct Trip: Equatable {
    var id: String
    var startMillis: Int64?
    var endMillis: Int64?
    var distanceKm: Double
    var durationSec: Double?
    var kwh: Double?
    var avgKwhPer100: Double?
    var avgSpeedKmh: Double?
    var liters: Double? = nil
    var avgLPer100: Double? = nil
    /** Fuel gauge (%) when the trip started and ended, when the car reports it: fill-ups show up between two trips. */
    var fuelStartPct: Double? = nil
    var fuelEndPct: Double? = nil

    private static func fuelLevel(_ t: JSONObject, _ key: String) -> Double? {
        let list = t.optJSONArray(key)?.objects() ?? []
        var tank = list.first { $0.optString("type") == "Fuel" }
        if tank == nil, list.count == 1, list[0].optString("type") != "Electric" { tank = list[0] }
        guard let level = tank?.num("level"), level >= 0.0, level <= 100.0 else { return nil }
        return level
    }

    /** Peugeot doesn't document fuel units; pick the scale that gives a sane l/100 km (2–30). */
    private static func litersOf(_ raw: Double?, _ km: Double) -> Double? {
        guard let raw = raw, raw > 0, km >= 0.5 else { return nil }
        return [1.0, 0.01, 0.001].map { raw * $0 }.first { let v = $0 / km * 100; return v >= 2.0 && v <= 30.0 }
    }

    private static func per100Of(_ raw: Double?) -> Double? {
        guard let raw = raw, raw > 0 else { return nil }
        return [1.0, 0.1, 0.01, 0.001].map { raw * $0 }.first { $0 >= 2.0 && $0 <= 30.0 }
    }

    static func parse(_ t: JSONObject, mildHybrid: Bool = false) -> Trip {
        let consumptions = t.optJSONArray("energyConsumptions")?.objects()
        let electric = consumptions?.first { !mildHybrid && $0.optString("type") == "Electric" }
        let fuel = consumptions?.first { $0.optString("type") == "Fuel" }
        let km = t.num("distance") ?? 0.0
        let liters = litersOf(fuel?.num("consumption"), km)
        // Work the speed out from distance and time: the car's own "avgSpeed" is in m/s on some models and km/h on others.
        var speed: Double? = nil
        if let d = t.num("duration"), d >= 60, km > 0 {
            speed = km / (d / 3600.0)
        } else if let v = t.optJSONObject("kinetic")?.num("avgSpeed"), km > 0 {
            speed = v
        }
        return Trip(
            id: t.optString("id"),
            startMillis: VehicleStatus.parseIso(t.str("startedAt") ?? t.str("createdAt")),
            endMillis: VehicleStatus.parseIso(t.str("stoppedAt")),
            distanceKm: km,
            durationSec: t.num("duration"),
            kwh: electric?.num("consumption").map { $0 / 1000.0 },
            avgKwhPer100: electric?.num("avgConsumption").map { $0 / 1000.0 },
            avgSpeedKmh: speed,
            liters: liters,
            // Work it out from fuel used and distance; the car's own average comes in different units (some trips were km/l).
            avgLPer100: liters.map { $0 / km * 100 } ?? per100Of(fuel?.num("avgConsumption")),
            fuelStartPct: fuelLevel(t, "startEnergies"),
            fuelEndPct: fuelLevel(t, "endEnergies")
        )
    }
}
