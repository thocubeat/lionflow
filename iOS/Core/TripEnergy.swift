import Foundation

/**
 * Fills in the energy a trip used when the carmaker's trip list leaves it out (the E-5008 and other newer cars often send
 * no electric consumption, so the official app shows none either). In order:
 * 1. the car's own figure, rescaled if it came in kWh instead of Wh;
 * 2. the car's average × distance;
 * 3. the drop in battery level over the trip × the battery's usable size;
 * 4. the trip LionFlow logged from the car's reports at the same time.
 * Figures from 2–4 are marked as estimated. Results outside 3–60 kWh/100 km are left out.
 */
enum TripEnergy {
    private static func sane(_ kwh: Double, _ km: Double) -> Bool {
        guard km >= 0.5 else { return false }
        let p = kwh / km * 100
        return p >= 3.0 && p <= 60.0
    }

    /** Wh read as kWh shows up as a thousandth of a sane value. */
    private static func rescaled(_ v: Double?, _ km: Double, per100: Bool) -> Double? {
        guard let v = v, v > 0 else { return nil }
        let p: Double
        if per100 { p = v } else if km > 0 { p = v / km * 100 } else { return v }
        return (p >= 0.003 && p <= 0.06) ? v * 1000 : v
    }

    static func fill(_ trips: [Trip], batteryKwh: Double?, logged: [Trip] = []) -> [Trip] {
        trips.map { fill($0, batteryKwh: batteryKwh, logged: logged) }
    }

    static func fill(_ t: Trip, batteryKwh: Double?, logged: [Trip] = []) -> Trip {
        let km = t.distanceKm
        let own = rescaled(t.kwh, km, per100: false).flatMap { sane($0, km) ? $0 : nil }
        let avg = rescaled(t.avgKwhPer100, km, per100: true).flatMap { ($0 >= 3.0 && $0 <= 60.0) ? $0 : nil }
        var estimate: Double? = nil
        if own == nil {
            if let a = avg {
                estimate = a * km / 100
            } else if let s = t.batteryStartPct, let e = t.batteryEndPct, s - e > 0, let b = batteryKwh, b > 1 {
                estimate = (s - e) / 100 * b
            } else {
                estimate = matching(t, logged)?.kwh
            }
        }
        estimate = estimate.flatMap { sane($0, km) ? $0 : nil }
        var out = t
        guard let kwh = own ?? estimate else {
            out.avgKwhPer100 = avg
            return out
        }
        out.kwh = kwh
        out.avgKwhPer100 = avg ?? kwh / km * 100
        out.energyEstimated = own == nil
        return out
    }

    /** A trip logged from the car's reports that overlaps [t] and covers about the same distance. */
    private static func matching(_ t: Trip, _ logged: [Trip]) -> Trip? {
        guard let a = t.startMillis else { return nil }
        let b = t.endMillis ?? a
        let slack: Int64 = 20 * 60_000
        return logged.first { o in
            guard let oa = o.startMillis, (o.kwh ?? 0) > 0 else { return false }
            let ob = o.endMillis ?? oa
            return oa <= b + slack && ob >= a - slack && abs(o.distanceKm - t.distanceKm) <= max(1.0, t.distanceKm * 0.15)
        }
    }
}
