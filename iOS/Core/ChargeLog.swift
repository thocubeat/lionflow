import Foundation

/** A place where you charge: Home or one of up to 5 known chargers. Price per kWh (0 = free). */
struct ChargePlace: Equatable {
    var id: String          // "home" or "k1".."k5"
    var name: String
    var address: String
    var lat: Double?
    var lon: Double?
    var price: Double       // per kWh, 0 = free

    func toJson() -> JSONObject {
        let j = JSONObject()
        j.put("id", id)
        j.put("name", name)
        j.put("address", address)
        j.put("lat", lat.map { JSONValue.double($0) } ?? JSONValue.null)
        j.put("lon", lon.map { JSONValue.double($0) } ?? JSONValue.null)
        j.put("price", price)
        return j
    }

    static func fromJson(_ j: JSONObject) -> ChargePlace {
        ChargePlace(id: j.optString("id"), name: j.optString("name"), address: j.optString("address"),
                    lat: j.isNull("lat") ? nil : j.optDouble("lat"), lon: j.isNull("lon") ? nil : j.optDouble("lon"),
                    price: j.optDouble("price", 0.0))
    }
}

struct PlacesConfig: Equatable {
    var home: ChargePlace? = nil
    var known: [ChargePlace] = []       // up to 5
    var publicAcPrice = 0.0             // estimate for unknown AC chargers, 0 = don't estimate
    var publicDcPrice = 0.0             // estimate for unknown fast chargers
    var currency = "kr"
}

/** One charging session, built from the car's own status reports. */
struct ChargeSession: Equatable, Identifiable {
    var id: Int64
    var start: Int64
    var end: Int64
    var startPct: Double
    var endPct: Double
    var kwh: Double
    var peakKw: Double?
    var mode: String?        // Slow (AC) | Quick (DC)
    var lat: Double?
    var lon: Double?
    var address: String?     // looked up from the car's position
    var source: String?      // charger name/operator if a known public charger is right there
    /** True when `kwh` comes from the energy the car reported in the battery (not from % × battery size). */
    var measured = false

    var hours: Double { Double(end - start) / 3_600_000.0 }
    var avgKw: Double? { hours > 0.05 ? kwh / hours : nil }

    private static func orNull(_ d: Double?) -> JSONValue { d.map { JSONValue.double($0) } ?? JSONValue.null }
    private static func orNull(_ s: String?) -> JSONValue { s.map { JSONValue.string($0) } ?? JSONValue.null }

    func toJson() -> JSONObject {
        let j = JSONObject()
        j.put("id", id)
        j.put("start", start)
        j.put("end", end)
        j.put("startPct", startPct)
        j.put("endPct", endPct)
        j.put("kwh", kwh)
        j.put("peakKw", ChargeSession.orNull(peakKw))
        j.put("mode", ChargeSession.orNull(mode))
        j.put("lat", ChargeSession.orNull(lat))
        j.put("lon", ChargeSession.orNull(lon))
        j.put("address", ChargeSession.orNull(address))
        j.put("source", ChargeSession.orNull(source))
        j.put("measured", measured)
        return j
    }

    static func fromJson(_ j: JSONObject) -> ChargeSession? {
        guard j.has("id"), j.has("start"), j.has("end"), let startPct = j.num("startPct"), let endPct = j.num("endPct"), let kwh = j.num("kwh") else { return nil }
        return ChargeSession(
            id: j.optLong("id"), start: j.optLong("start"), end: j.optLong("end"), startPct: startPct, endPct: endPct, kwh: kwh,
            peakKw: j.isNull("peakKw") ? nil : j.optDouble("peakKw"),
            mode: j.isNull("mode") ? nil : j.optString("mode"),
            lat: j.isNull("lat") ? nil : j.optDouble("lat"), lon: j.isNull("lon") ? nil : j.optDouble("lon"),
            address: j.isNull("address") ? nil : j.optString("address"), source: j.isNull("source") ? nil : j.optString("source"),
            measured: j.optBoolean("measured", false))
    }

    /// Battery health worked out from charging sessions: how many kWh went into the battery per 100 %, compared
    /// with the battery's size when new (`newKwh`). Uses sessions where the car reported the energy in the battery
    /// and the level rose by at least 15 %; needs 3 of them. Returns (health %, sessions used), or nil.
    /// Not an official number: it depends on how exactly the car reports energy and level.
    static func healthEstimate(_ sessions: [ChargeSession], _ newKwh: Double?) -> (Double, Int)? {
        guard let new = newKwh, new >= 5 else { return nil }
        // The 20 newest usable sessions, so the figure follows the battery as it ages.
        let usable = stableSorted(sessions.filter { $0.measured && $0.endPct - $0.startPct >= 15 && $0.kwh > 0 }) { $0.start > $1.start }
        let caps = Array(usable.map { $0.kwh / (($0.endPct - $0.startPct) / 100.0) }.filter { $0 >= new * 0.5 && $0 <= new * 1.15 }.prefix(20))
        if caps.count < 3 { return nil }
        let sorted = caps.sorted()
        let median = sorted.count % 2 == 1 ? sorted[sorted.count / 2] : (sorted[sorted.count / 2 - 1] + sorted[sorted.count / 2]) / 2
        return (min(median / new * 100, 100.0), caps.count)
    }
}

/** Where a session happened and what it cost. `kind`: home | known | public | unknown. */
struct PlaceMatch: Equatable {
    var kind: String
    var label: String
    var price: Double?
}

/// Charging log. The car has no charging history, so LionFlow watches its status reports and turns
/// "charging started → stopped" into sessions. Everything stays on the phone.
enum ChargeLog {
    private static let lock = NSRecursiveLock()
    private static var prefs: KeyValueStore?
    private static var vin = ""
    private static var realPrefs: KeyValueStore?
    private static var realVin = ""

    private(set) static var sessions: [ChargeSession] = []
    /** The session in progress (end = last report so far). */
    private(set) static var active: ChargeSession?
    private(set) static var places = PlacesConfig()

    /** Told (on any thread) when the sessions, the session in progress or the places changed. */
    static var onChange: (() -> Void)?
    /** The chargers last saved for the map (JSON text), to name a public charger a session happened at. */
    static var savedChargers: () -> String? = { nil }
    /** Looks up a street address for a position. The app plugs in the network call; tests leave it out. */
    static var addressLookup: ((Double, Double) async -> String?)?
    static var clock: () -> Int64 = { nowMillis() }

    private static func k(_ name: String) -> String { vin.isEmpty ? name : "\(name)_\(vin)" }
    private static func changed() { onChange?() }

    static func start(_ store: KeyValueStore, _ currentVin: String?) {
        lock.lock()
        prefs = store
        vin = currentVin ?? ""
        sessions = loadSessions()
        active = loadActive().flatMap { activeToSession($0) }
        places = loadPlaces()
        lock.unlock()
        changed()
        fillAddresses()
    }

    /** Demo mode: use a separate, throw-away store filled with sample data. The real log is untouched. */
    static func enterDemo(_ demoStore: KeyValueStore, _ demoVin: String, _ list: [ChargeSession], _ cfg: PlacesConfig) {
        lock.lock()
        if realPrefs == nil {
            realPrefs = prefs
            realVin = vin
        }
        demoStore.removeAll()
        prefs = demoStore
        vin = demoVin
        active = nil
        lock.unlock()
        save(list)
        savePlaces(cfg)
    }

    static func exitDemo() {
        lock.lock()
        guard let p = realPrefs else { lock.unlock(); return }
        prefs = p
        vin = realVin
        realPrefs = nil
        sessions = loadSessions()
        active = loadActive().flatMap { activeToSession($0) }
        places = loadPlaces()
        lock.unlock()
        changed()
    }

    /** Show another car's log. */
    static func setVin(_ v: String) {
        lock.lock()
        vin = v
        sessions = loadSessions()
        active = loadActive().flatMap { activeToSession($0) }
        lock.unlock()
        changed()
    }

    private static func fillAddresses() {
        // Fill in any addresses that couldn't be looked up earlier (offline).
        for s in sessions.filter({ $0.address == nil && $0.lat != nil }).prefix(3) { lookupAddress(s.id) }
    }

    // MARK: Session detection

    static func observe(_ st: VehicleStatus) {
        lock.lock()
        defer { lock.unlock() }
        guard let p = prefs, let pct = st.batteryPct else { return }
        let t = st.energyAtMillis ?? st.updatedAtMillis ?? clock()
        var a = loadActive()

        // A session left open for over a day (phone off, car asleep) is closed first.
        if let open = a, t - open.optLong("lastT") > 24 * 3_600_000 {
            finish(open, nil, nil, nil)
            a = nil
        }

        if st.isCharging {
            var kw: Double? = nil
            if let r = ChargePower.reading(st), r.measured { kw = r.kw }
            let cur: JSONObject
            if let open = a {
                cur = open
                if cur.isNull("lat"), let lat = st.lat {
                    cur.put("lat", lat)
                    cur.put("lon", orNull(st.lon))
                }
                if let kw = kw, cur.isNull("peak") || kw > cur.optDouble("peak") { cur.put("peak", kw) }
                if st.chargingMode == "Quick" { cur.put("mode", "Quick") }
                cur.put("usable", ChargePower.usableKwh(st))   // a corrected battery size applies to a session already running
            } else {
                cur = JSONObject()
                cur.put("start", t)
                cur.put("startPct", pct)
                cur.put("startKwh", orNull(st.residualKwh))
                cur.put("lat", orNull(st.lat))
                cur.put("lon", orNull(st.lon))
                cur.put("mode", st.chargingMode.map { JSONValue.string($0) } ?? JSONValue.null)
                cur.put("peak", orNull(kw))
                cur.put("usable", ChargePower.usableKwh(st))
            }
            if t >= cur.optLong("lastT", 0) {
                cur.put("lastT", t)
                cur.put("lastPct", pct)
                cur.put("lastKwh", orNull(st.residualKwh))
            }
            p.set(k("active"), cur.toString())
            active = activeToSession(cur)
            changed()
        } else if let open = a {
            // Charging stopped. If this report shows a higher level (finished while we weren't looking), use it.
            let higher = pct > open.optDouble("lastPct") && t > open.optLong("lastT")
            finish(open, higher ? pct : nil, higher ? t : nil, higher ? st.residualKwh : nil)
        }
    }

    private static func orNull(_ d: Double?) -> JSONValue { d.map { JSONValue.double($0) } ?? JSONValue.null }

    private static func finish(_ a: JSONObject, _ endPct: Double?, _ endT: Int64?, _ endKwh: Double?) {
        guard let p = prefs else { return }
        p.set(k("active"), nil)
        active = nil
        defer { changed() }
        guard let s = activeToSession(a, endPct, endT, endKwh) else { return }
        if s.kwh < 0.3 || (s.end - s.start < 2 * 60_000 && s.endPct - s.startPct < 1) { return }
        save(Array(([s] + loadSessions()).prefix(500)))
        lookupAddress(s.id)
    }

    private static func activeToSession(_ a: JSONObject, _ endPct: Double? = nil, _ endT: Int64? = nil, _ endKwh: Double? = nil) -> ChargeSession? {
        guard a.has("start"), let startPct = a.num("startPct") else { return nil }
        let lastPct = endPct ?? a.optDouble("lastPct", startPct)
        let startKwh: Double? = a.isNull("startKwh") ? nil : a.optDouble("startKwh")
        let lastKwh: Double? = endKwh ?? (a.isNull("lastKwh") ? nil : a.optDouble("lastKwh"))
        let usable = a.optDouble("usable", 73.0)
        var measured = false
        var kwh = max((lastPct - startPct) / 100.0 * usable, 0.0)
        if let s = startKwh, let l = lastKwh, l > s {
            kwh = l - s
            measured = true
        }
        let lat: Double? = a.isNull("lat") ? nil : a.optDouble("lat")
        let lon: Double? = a.isNull("lon") ? nil : a.optDouble("lon")
        var near: String? = nil
        if let la = lat, let lo = lon { near = nearbyCharger(la, lo) }
        let start = a.optLong("start")
        return ChargeSession(
            id: start, start: start, end: endT ?? a.optLong("lastT", start),
            startPct: startPct, endPct: lastPct, kwh: kwh,
            peakKw: a.isNull("peak") ? nil : a.optDouble("peak"),
            mode: a.isNull("mode") ? nil : a.optString("mode"),
            lat: lat, lon: lon, address: nil, source: near, measured: measured)
    }

    // MARK: Places

    static func match(_ s: ChargeSession, _ cfg: PlacesConfig? = nil) -> PlaceMatch {
        let cfg = cfg ?? places
        if let lat = s.lat, let lon = s.lon {
            if let h = cfg.home, let hla = h.lat, let hlo = h.lon, distanceM(lat, lon, hla, hlo) <= 50 { return PlaceMatch(kind: "home", label: "Home", price: h.price) }
            for kn in cfg.known {
                if let kla = kn.lat, let klo = kn.lon, distanceM(lat, lon, kla, klo) <= 75 {
                    return PlaceMatch(kind: "known", label: kn.name.allSatisfy({ $0.isWhitespace }) ? "Known charger" : kn.name, price: kn.price)
                }
            }
        }
        let dc = s.mode == "Quick"
        let estimate = dc ? cfg.publicDcPrice : cfg.publicAcPrice
        let label = s.source ?? s.address ?? (s.lat == nil ? "Unknown place" : "Away")
        return PlaceMatch(kind: s.lat == nil ? "unknown" : "public", label: label, price: estimate > 0 ? estimate : nil)
    }

    static func savePlaces(_ cfg: PlacesConfig) {
        lock.lock()
        places = cfg
        let known = JSONArray()
        for kn in cfg.known { known.put(kn.toJson()) }
        let j = JSONObject()
        j.put("home", cfg.home.map { JSONValue.object($0.toJson()) } ?? JSONValue.null)
        j.put("known", known)
        j.put("publicAc", cfg.publicAcPrice)
        j.put("publicDc", cfg.publicDcPrice)
        j.put("currency", cfg.currency)
        prefs?.set("places", j.toString())
        lock.unlock()
        changed()
    }

    private static func loadPlaces() -> PlacesConfig {
        guard let text = prefs?.string("places"), let j = try? JSONObject(text) else { return PlacesConfig() }
        return PlacesConfig(
            home: j.optJSONObject("home").map { ChargePlace.fromJson($0) },
            known: (j.optJSONArray("known")?.objects() ?? []).map { ChargePlace.fromJson($0) },
            publicAcPrice: j.optDouble("publicAc", 0.0), publicDcPrice: j.optDouble("publicDc", 0.0),
            currency: j.optString("currency", "kr"))
    }

    // MARK: Storage

    private static func loadSessions() -> [ChargeSession] {
        guard let a = try? JSONArray(prefs?.string(k("sessions")) ?? "[]") else { return [] }
        return a.objects().compactMap { ChargeSession.fromJson($0) }
    }

    private static func loadActive() -> JSONObject? {
        guard let text = prefs?.string(k("active")) else { return nil }
        return try? JSONObject(text)
    }

    private static func save(_ list: [ChargeSession]) {
        lock.lock()
        sessions = list
        let a = JSONArray()
        for s in list { a.put(s.toJson()) }
        prefs?.set(k("sessions"), a.toString())
        lock.unlock()
        changed()
    }

    static func delete(_ id: Int64) { save(loadSessions().filter { $0.id != id }) }

    static func clearAll() {
        lock.lock()
        prefs?.set(k("sessions"), nil)
        prefs?.set(k("active"), nil)
        sessions = []
        active = nil
        lock.unlock()
        changed()
    }

    // MARK: Addresses

    private static func lookupAddress(_ id: Int64) {
        guard let lookup = addressLookup, let s = sessions.first(where: { $0.id == id }), let lat = s.lat, let lon = s.lon else { return }
        Task.detached {
            guard let addr = await lookup(lat, lon) else { return }
            save(loadSessions().map { one in
                var copy = one
                if copy.id == id { copy.address = addr }
                return copy
            })
        }
    }

    /** Name of a public charger within 120 m, from the chargers LionFlow already loaded for the map. */
    private static func nearbyCharger(_ lat: Double, _ lon: Double) -> String? {
        guard let text = savedChargers(), let j = try? JSONObject(text), let items = j.optJSONArray("items") else { return nil }
        var best: (Double, String)? = nil
        for c in items.objects() {
            let d = distanceM(lat, lon, c.optDouble("lat"), c.optDouble("lon"))
            if d <= 120 && (best == nil || d < best!.0) { best = (d, c.optString("name")) }
        }
        guard let name = best?.1, !name.allSatisfy({ $0.isWhitespace }) else { return nil }
        return name
    }

    static func distanceM(_ la1: Double, _ lo1: Double, _ la2: Double, _ lo2: Double) -> Double {
        let r = 6_371_000.0
        func rad(_ d: Double) -> Double { d * 0.017453292519943295 }   // Java's Math.toRadians
        let dLa = rad(la2 - la1)
        let dLo = rad(lo2 - lo1)
        let a = sin(dLa / 2)
        let b = sin(dLo / 2)
        let h = a * a + cos(rad(la1)) * cos(rad(la2)) * b * b
        return 2 * r * asin(sqrt(h))
    }
}
