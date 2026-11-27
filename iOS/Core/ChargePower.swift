import Foundation

/// Charging power in kW. The car only reports "km of range per hour", so LionFlow works it out:
///  - measured: energy in the battery between two car reports (4–40 min apart) ÷ time between them
///  - estimated (until there are two reports): energy still needed ÷ time left reported by the car
enum ChargePower {
    struct Reading: Equatable {
        var kw: Double
        var measured: Bool
    }

    private static let lock = NSRecursiveLock()
    private static var prefs: KeyValueStore?
    private static var settings: SettingsStore?
    private static var vin = ""
    static var clock: () -> Int64 = { nowMillis() }
    /** The time zone the log lines are written in: the phone's own, unless a test sets another. */
    static var timeZone = TimeZone.current

    private static func k(_ name: String) -> String { vin.isEmpty ? name : "\(name)_\(vin)" }

    static func start(_ store: KeyValueStore, _ s: SettingsStore, _ currentVin: String?) {
        lock.lock()
        prefs = store
        settings = s
        vin = currentVin ?? ""
        lock.unlock()
    }

    static func setVin(_ v: String) { lock.lock(); vin = v; lock.unlock() }
    static func store() -> KeyValueStore? { prefs }
    static func key(_ name: String) -> String { k(name) }

    /// Minutes left and the % they lead to: to LionFlow's limit when one is set and still ahead, otherwise the
    /// car's own time to full.
    static func timeLeft(_ st: VehicleStatus) -> (Int, Int?)? {
        guard let rem = st.remainingMinutes else { return nil }
        if let s = settings?.value, s.chargeLimitOn, let soc = st.batteryPct, soc < Double(s.chargeLimitPct), soc < 100 {
            let m = JSONValue.javaInt(Double(rem) * (Double(s.chargeLimitPct) - soc) / (100 - soc))
            return (max(m, 1), s.chargeLimitPct)
        }
        return (rem, nil)
    }

    static func leftText(_ st: VehicleStatus) -> String? {
        guard let left = timeLeft(st) else { return nil }
        let m = left.0
        return " · \(m / 60) h \(m % 60) min left" + (left.1.map { " to \($0) %" } ?? "")
    }

    /// Usable battery size: the user's setting, else what the car reports (or works out from energy left and %),
    /// else a default for the car type (plug-in hybrids ~11.5 kWh, electric E-5008 73 kWh).
    static func usableKwh(_ st: VehicleStatus) -> Double {
        if let own = settings?.value.batteryKwh, own > 0 { return own }
        if let cap = st.capacityKwh, cap > 1 { return cap }
        if let r = st.residualKwh, let pct = st.batteryPct, pct >= 10 {
            let worked = r / (pct / 100.0)
            if worked >= 5.0 && worked <= 150.0 { return worked }
        }
        return st.energyType == "Hybrid" ? 11.5 : 73.0
    }

    private static func energyIn(_ st: VehicleStatus) -> Double? {
        if let r = st.residualKwh { return r }
        return st.batteryPct.map { $0 / 100.0 * usableKwh(st) }
    }

    /** The last car reports, one line each, for the log (to see how the battery level moved). */
    static func history() -> [String] {
        (prefs?.string(k("hist")) ?? "").components(separatedBy: "\n").filter { !$0.allSatisfy({ $0.isWhitespace }) }
    }

    /** The text after the second space: a report line without its date and time. */
    private static func afterTwoSpaces(_ s: String) -> String {
        var rest = Substring(s)
        for _ in 0..<2 {
            guard let i = rest.firstIndex(of: " ") else { return String(rest) }
            rest = rest[rest.index(after: i)...]
        }
        return String(rest)
    }

    private static func remember(_ p: KeyValueStore, _ st: VehicleStatus, carLevel: Double? = nil) {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.day, .month, .hour, .minute], from: Date(timeIntervalSince1970: Double(st.updatedAtMillis ?? clock()) / 1000))
        let t = String(format: "%02d.%02d %02d:%02d", c.day ?? 0, c.month ?? 0, c.hour ?? 0, c.minute ?? 0)
        func text<T>(_ v: T?) -> String { v.map { "\($0)" } ?? "null" }
        let car = (carLevel.map { JSONValue.javaInt($0) }).flatMap { c in c != st.batteryPct.map({ JSONValue.javaInt($0) }) ? " (car says \(c))" : nil } ?? ""
        let line = "\(t) level=\(text(st.batteryPct.map { JSONValue.javaInt($0) }))\(car) residual=\(text(st.residualKwh.map { Fmt.fixed($0, 1) })) " +
            "status=\(text(st.chargingStatus)) type=\(text(st.chargeType)) plugged=\(st.plugged) preheat=\(st.preconditioningOn)"
        let old = history()
        if let first = old.first, afterTwoSpaces(first) == afterTwoSpaces(line) { return }
        p.set(k("hist"), ([line] + old).prefix(30).joined(separator: "\n"))
    }

    /// The car reports its scheduled start time even when it is set to charge right away (the API has no on/off for it),
    /// so LionFlow remembers what it last sent. Nil when there is no scheduled start, or LionFlow turned it off.
    static func scheduledStart(_ st: VehicleStatus) -> (hour: Int, minute: Int)? {
        guard let h = st.nextDelayedHour else { return nil }
        lock.lock(); defer { lock.unlock() }
        if prefs?.string(k("startMode")) == "immediate" { return nil }
        return (h, st.nextDelayedMinute ?? 0)
    }

    static func rememberStartMode(immediate: Bool) {
        lock.lock(); defer { lock.unlock() }
        prefs?.set(k("startMode"), immediate ? "immediate" : "delayed")
    }

    private static func doubles(_ text: String?) -> [Double] {
        (text ?? "").components(separatedBy: ",").compactMap { JSONValue.javaParseDouble($0) }
    }

    /// Some cars without a sunroof report one that is open ("RoofWindow": Open) all the time. A car that has one
    /// reports it closed now and then, so the sunroof only counts once this car has been seen reporting it closed.
    private static func fixRoof(_ st: VehicleStatus) -> VehicleStatus {
        guard let p = prefs else { return st }
        let roof = (st.raw.optJSONObject("doorsState")?.optJSONArray("opening")?.objects() ?? [])
            .filter { $0.optString("identifier").lowercased() == "roofwindow" }
        if roof.contains(where: { $0.optString("state") == "Closed" }) && p.string(k("roofSeen")) != "1" { p.set(k("roofSeen"), "1") }
        if p.string(k("roofSeen")) == "1" { return st }
        var out = st
        out.openDoors = st.openDoors.filter { $0.lowercased() != "roofwindow" }
        return out
    }

    /// Call on every fresh car report. Fixes a missing/0 range and remembers charging samples.
    /// Returns the status to use.
    static func record(_ raw: VehicleStatus) -> VehicleStatus {
        lock.lock()
        defer { lock.unlock() }
        let st = applyPendingPrograms(fixRange(fixRoof(raw)))
        ChargeLog.observe(st)
        CarTrips.observe(st)
        guard let p = prefs else { return st }
        remember(p, st, carLevel: raw.batteryPct)
        if !st.isCharging {
            p.set(k("s"), nil)
            return st
        }
        guard let t = st.energyAtMillis ?? st.updatedAtMillis, let e = energyIn(st) else { return st }
        let arr = load()
        if let newest = arr.last, newest.0 == t { return st }
        let keep = (arr + [(t, e)]).filter { t - $0.0 <= 45 * 60_000 }.suffix(12)
        let a = JSONArray()
        for s in keep { a.put(JSONArray().put(s.0).put(s.1)) }
        p.set(k("s"), a.toString())
        return st
    }

    // MARK: Pre-heating schedules just sent

    private static func key(_ p: Program) -> String {
        [p.days.sorted().joined(separator: ","), String(p.hour), String(p.minute), String(p.enabled)].joined(separator: "|")
    }

    /** Remember schedules sent to the car; the carmaker's status can show the old ones for a while. */
    static func setPendingPrograms(_ list: [Program]) {
        let programs = JSONArray()
        for p in list {
            programs.put(JSONObject().put("slot", p.slot).put("days", JSONArray(p.days)).put("hour", p.hour).put("minute", p.minute).put("on", p.enabled))
        }
        settings?.pendingPrograms = JSONObject().put("time", clock()).put("programs", programs).toString()
    }

    static func pendingPrograms() -> [Program]? {
        let raw = settings?.pendingPrograms ?? ""
        if raw.allSatisfy({ $0.isWhitespace }) { return nil }
        guard let j = try? JSONObject(raw), j.has("time"), let a = j.optJSONArray("programs") else { return nil }
        if clock() - j.optLong("time") > 2 * 3_600_000 {
            settings?.pendingPrograms = ""
            return nil
        }
        var out: [Program] = []
        for i in 0..<a.length() {
            guard let o = a.optJSONObject(i), let d = o.optJSONArray("days"), o.has("slot"), o.has("hour"), o.has("minute"), o.has("on") else { return nil }
            out.append(Program(slot: o.optInt("slot"), days: d.strings(), hour: o.optInt("hour"), minute: o.optInt("minute"), enabled: o.optBoolean("on")))
        }
        return out
    }

    /** Until the car's status shows the new schedules, use the ones we sent (so they don't vanish or get overwritten). */
    private static func applyPendingPrograms(_ st: VehicleStatus) -> VehicleStatus {
        guard let pend = pendingPrograms() else { return st }
        let car = Set(st.programs.map { key($0) })
        let want = Set(pend.map { key($0) })
        if car == want {
            settings?.pendingPrograms = ""
            return st
        }
        // The car's list is still the old one for a while. If it changes to something that is neither the old nor the new list,
        // someone else (another phone, the carmaker's app) edited the schedules: show the car's list instead of ours.
        guard let raw = try? JSONObject(settings?.pendingPrograms ?? "") else { return st }
        let carKey = car.sorted().joined(separator: ";")
        let base = raw.optString("base", "")
        if base.isEmpty {
            raw.put("base", carKey)
            settings?.pendingPrograms = raw.toString()
        } else if base != carKey {
            settings?.pendingPrograms = ""
            return st
        }
        var out = st
        out.programs = pend
        return out
    }

    /// The carmaker's API sometimes reports 0 km range (often when plugged in or asleep) even with a full battery.
    /// Then we use km-per-% learned from the last real reading, or a plain estimate (usable kWh at ~19 kWh/100 km).
    private static func fixRange(_ st: VehicleStatus) -> VehicleStatus {
        if st.energyType == "Fuel" { return st }
        guard let pct = st.batteryPct else { return st }
        if let range = st.rangeKm, range > 0 {
            if pct >= 5 {
                let perPct = range / pct
                if perPct >= 1.0 && perPct <= 10.0 { prefs?.set(k("kmPerPct"), "\(Float(perPct))") }
            }
            return st
        }
        if pct < 1 { return st }
        var perPct = usableKwh(st) / 19.0 * 100.0 / 100.0
        if let text = prefs?.string(k("kmPerPct")), let learned = Float(text), learned > 0 { perPct = Double(learned) }
        var out = st
        out.rangeKm = Double((pct * perPct).roundToLong)
        out.rangeEstimated = true
        return out
    }

    private static func load() -> [(Int64, Double)] {
        guard let a = try? JSONArray(prefs?.string(k("s")) ?? "[]") else { return [] }
        var out: [(Int64, Double)] = []
        for i in 0..<a.length() {
            guard let x = a.optJSONArray(i), x.length() >= 2 else { return [] }
            out.append((x.optLong(0), x.optDouble(1)))
        }
        return out
    }

    static func reading(_ st: VehicleStatus?) -> Reading? {
        guard let st = st, st.isCharging else { return nil }
        let max = st.chargingMode == "Quick" ? 250.0 : 23.0
        let samples = load()
        if let newest = samples.last {
            let base = samples.last { s in
                let gap = newest.0 - s.0
                return gap >= 4 * 60_000 && gap <= 40 * 60_000
            }
            if let base = base {
                let kw = (newest.1 - base.1) / (Double(newest.0 - base.0) / 3_600_000.0)
                if kw >= 0.5 && kw <= max { return Reading(kw: kw, measured: true) }
            }
        }
        guard let rem = st.remainingMinutes, rem > 0 else { return nil }
        let need: Double
        if let res = st.residualKwh, let cap = st.capacityKwh { need = cap - res }
        else if let pct = st.batteryPct { need = usableKwh(st) * (100 - pct) / 100.0 }
        else { return nil }
        let kw = need / (Double(rem) / 60.0)
        return kw >= 0.5 && kw <= max ? Reading(kw: kw, measured: false) : nil
    }

    static func format(_ r: Reading) -> String {
        Fmt.fixed(r.kw, r.kw < 10 ? 1 : 0) + " kW" + (r.measured ? "" : " (est.)")
    }

    static func label(_ st: VehicleStatus?) -> String? { reading(st).map { format($0) } }
}
