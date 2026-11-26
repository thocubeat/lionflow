import Foundation

/// Fuel figures worked out from the trips and the car's own counters: totals per month, fill-ups, records and the
/// average since new. For petrol, diesel and hybrid cars. (Same method as the Android app; see NOTICE.md.)
enum FuelStats {
    struct Month: Equatable {
        var startMillis: Int64
        var km: Double
        var trips: Int
        var liters: Double
        var kmWithFuel: Double
        var seconds: Double
        var lPer100: Double? { kmWithFuel > 0 && liters > 0 ? liters / kmWithFuel * 100 : nil }
    }

    /** The calendar that decides where a month and a day begin: the phone's own, unless a test sets another. */
    static var calendar = Calendar.current

    private static func date(_ millis: Int64) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }
    private static func millis(_ d: Date) -> Int64 { Int64((d.timeIntervalSince1970 * 1000).rounded()) }

    static func monthStart(_ ms: Int64) -> Int64 {
        let c = calendar.dateComponents([.year, .month], from: date(ms))
        return millis(calendar.date(from: c) ?? date(ms))
    }

    static func dayStart(_ ms: Int64) -> Int64 { millis(calendar.startOfDay(for: date(ms))) }

    /** Groups in the order their first member came, as Kotlin's `groupBy` does. */
    private static func grouped(_ trips: [Trip], by key: (Int64) -> Int64) -> [(Int64, [Trip])] {
        var order: [Int64] = []
        var groups: [Int64: [Trip]] = [:]
        for t in trips {
            guard let s = t.startMillis else { continue }
            let k = key(s)
            if groups[k] == nil { order.append(k) }
            groups[k, default: []].append(t)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }

    /** Totals per calendar month, newest first. */
    static func monthly(_ trips: [Trip]) -> [Month] {
        grouped(trips, by: monthStart).map { (start, list) -> Month in
            let fuelled = list.filter { $0.liters != nil }
            var km = 0.0, liters = 0.0, kmWithFuel = 0.0, seconds = 0.0
            for t in list {
                km += t.distanceKm
                seconds += t.durationSec ?? 0.0
            }
            for t in fuelled {
                liters += t.liters ?? 0.0
                kmWithFuel += t.distanceKm
            }
            return Month(startMillis: start, km: km, trips: list.count, liters: liters, kmWithFuel: kmWithFuel, seconds: seconds)
        }.sorted { $0.startMillis > $1.startMillis }
    }

    /** Litres used by these trips. */
    static func liters(_ trips: [Trip]) -> Double {
        var sum = 0.0
        for t in trips { sum += t.liters ?? 0.0 }
        return sum
    }

    /** What that fuel cost at `pricePerLiter` (nil without a price or without fuel figures). */
    static func cost(_ liters: Double?, _ pricePerLiter: Double) -> Double? {
        guard let l = liters, l > 0, pricePerLiter > 0 else { return nil }
        return l * pricePerLiter
    }

    struct FillUp: Equatable {
        var atMillis: Int64
        var fromPct: Double
        var toPct: Double
        var liters: Double?
        var kmSincePrevious: Double?
    }

    /** Fill-ups: the gauge at a trip's start is clearly higher than at the end of the trip before. Newest first. */
    static func fillUps(_ trips: [Trip], _ tankLiters: Double?, minJump: Double = 5.0) -> [FillUp] {
        let sorted = stableSorted(trips.filter { $0.startMillis != nil }) { $0.startMillis! < $1.startMillis! }
        var out: [FillUp] = []
        var kmSince = 0.0
        var seenOne = false
        if sorted.count > 1 {
            for i in 1..<sorted.count {
                let prev = sorted[i - 1]
                let cur = sorted[i]
                kmSince += prev.distanceKm
                guard let a = prev.fuelEndPct, let b = cur.fuelStartPct else { continue }
                if b - a < minJump { continue }
                out.append(FillUp(atMillis: cur.startMillis!, fromPct: a, toPct: b, liters: tankLiters.map { (b - a) / 100 * $0 }, kmSincePrevious: seenOne ? kmSince : nil))
                kmSince = 0.0
                seenOne = true
            }
        }
        return out.reversed()
    }

    /// Tank size from the fuel used against how far the gauge fell, over the trips where it moved. Needs at least
    /// 10 % of gauge movement, and gives nothing when the answer isn't a believable tank (20–100 l).
    static func tankEstimate(_ trips: [Trip]) -> Double? {
        var liters = 0.0
        var drop = 0.0
        for t in trips {
            guard let l = t.liters, let a = t.fuelStartPct, let b = t.fuelEndPct else { continue }
            if a <= b || a >= 100 { continue }
            liters += l
            drop += a - b
        }
        if drop < 10 { return nil }
        let tank = liters / drop * 100
        return tank >= 20.0 && tank <= 100.0 ? tank : nil
    }

    struct Records: Equatable {
        var longest: Trip?
        var longestTime: Trip?
        var mostEconomical: Trip?
        var thirstiest: Trip?
        var bestDayMillis: Int64?
        var bestDayKm: Double
    }

    /** Longest trip, longest time at the wheel, lowest and highest l/100 km (trips of 10 km or more), most km in one day. */
    static func records(_ trips: [Trip]) -> Records {
        let rated = trips.filter { $0.distanceKm >= 10 && $0.avgLPer100 != nil }
        var days: [(Int64, Double)] = []
        for (day, list) in grouped(trips, by: dayStart) {
            var km = 0.0
            for t in list { km += t.distanceKm }
            days.append((day, km))
        }
        let best = days.max { a, b in a.1 < b.1 }
        let longest = trips.max { $0.distanceKm < $1.distanceKm }
        let thirstiest = rated.max { $0.avgLPer100! < $1.avgLPer100! }
        return Records(
            longest: (longest?.distanceKm ?? 0) > 0 ? longest : nil,
            longestTime: trips.filter { $0.durationSec != nil }.max { $0.durationSec! < $1.durationSec! },
            mostEconomical: rated.min { $0.avgLPer100! < $1.avgLPer100! },
            thirstiest: rated.count > 1 ? thirstiest : nil,
            bestDayMillis: best?.0, bestDayKm: best?.1 ?? 0.0)
    }

    /// Litres per unit of the car's lifetime fuel counter. The unit isn't documented and differs between models, so the
    /// usual ones are tried against the odometer: the first that gives a believable lifetime average (3–12 l/100 km)
    /// wins. Nil when the car has driven too little to tell, or nothing fits.
    static func litersPerUnit(_ totalRaw: Double?, _ odometerKm: Double?) -> Double? {
        guard let total = totalRaw, total > 0, let km = odometerKm, km >= 300 else { return nil }
        return [0.01, 0.001, 1.0, 0.1, 0.004].first { let v = total * $0 / km * 100; return v >= 3.0 && v <= 12.0 }
    }

    /** Litres burned since the car was new, from its own counter. */
    static func lifetimeLiters(_ st: VehicleStatus?) -> Double? {
        guard let scale = litersPerUnit(st?.fuelTotalRaw, st?.odometerKm), let total = st?.fuelTotalRaw else { return nil }
        return total * scale
    }

    /** Average since new (l/100 km), from the car's fuel counter and odometer. */
    static func lifetimeLPer100(_ st: VehicleStatus?) -> Double? {
        guard let liters = lifetimeLiters(st), let km = st?.odometerKm else { return nil }
        return liters / km * 100
    }
}

/** A sort that keeps equal elements in the order they came, as Kotlin's `sortedBy` does. */
func stableSorted<T>(_ items: [T], by areInIncreasingOrder: (T, T) -> Bool) -> [T] {
    items.enumerated().sorted { a, b in
        if areInIncreasingOrder(a.element, b.element) { return true }
        if areInIncreasingOrder(b.element, a.element) { return false }
        return a.offset < b.offset
    }.map { $0.element }
}

/// How much of the driving of a plug-in hybrid was electric. The car only reports energy and fuel per trip:
/// trips without fuel are fully electric, and mixed trips are split using the driver's own electric consumption.
enum HybridSplit {
    struct Result: Equatable {
        var totalKm: Double
        var evKm: Double
        var engineKm: Double
        var kwh: Double
        /** kWh/100 km measured on fully electric trips, or nil if there were none. */
        var evKwhPer100: Double?
        var evShare: Double { totalKm > 0 ? evKm / totalKm : 0.0 }
    }

    private static let fallbackKwhPerKm = 0.2

    static func of(_ trips: [Trip]) -> Result {
        func usedEngine(_ t: Trip) -> Bool { (t.liters ?? 0.0) >= 0.05 }
        let pureEv = trips.filter { !usedEngine($0) && ($0.kwh ?? 0.0) > 0 && $0.distanceKm >= 1 }
        var evKm = 0.0
        var evKwh = 0.0
        for t in pureEv {
            evKm += t.distanceKm
            evKwh += t.kwh ?? 0.0
        }
        let rate: Double = evKm > 3 ? evKwh / evKm : fallbackKwhPerKm
        var ev = 0.0, eng = 0.0, kwh = 0.0
        for t in trips {
            let k = t.distanceKm
            kwh += t.kwh ?? 0.0
            if !usedEngine(t) { ev += k }
            else if (t.kwh ?? 0.0) < 0.05 { eng += k }
            else {
                let e = min(max((t.kwh ?? 0.0) / rate, 0.0), k)
                ev += e
                eng += k - e
            }
        }
        var total = 0.0
        for t in trips { total += t.distanceKm }
        return Result(totalKm: total, evKm: ev, engineKm: eng, kwh: kwh, evKwhPer100: evKm > 3 ? rate * 100 : nil)
    }
}
