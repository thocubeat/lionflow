import Foundation

/// Trips worked out from the car's own reports (odometer, position, battery), for cars whose trips the carmaker's
/// server doesn't share with other apps. Each time the odometer has moved between two reports, that drive is logged.
/// When the car reports only after a drive, the start time is estimated from the distance.
enum CarTrips {
    private struct Snap {
        var t: Int64
        var odo: Double
        var lat: Double?
        var lon: Double?
        var pct: Double?
        var res: Double?
        var cap: Double?

        func json() -> JSONObject {
            func orNull(_ d: Double?) -> JSONValue { d.map { JSONValue.double($0) } ?? JSONValue.null }
            let j = JSONObject()
            j.put("t", t)
            j.put("odo", odo)
            j.put("lat", orNull(lat))
            j.put("lon", orNull(lon))
            j.put("pct", orNull(pct))
            j.put("res", orNull(res))
            j.put("cap", orNull(cap))
            return j
        }

        static func of(_ o: JSONObject?) -> Snap? {
            guard let o = o, o.has("t"), let odo = o.num("odo") else { return nil }
            func d(_ k: String) -> Double? { o.num(k) }
            return Snap(t: o.optLong("t"), odo: odo, lat: d("lat"), lon: d("lon"), pct: d("pct"), res: d("res"), cap: d("cap"))
        }
    }

    private static let maxTrips = 400
    private static let lock = NSLock()
    static var clock: () -> Int64 = { nowMillis() }

    private static func object(_ p: KeyValueStore, _ name: String) -> JSONObject? {
        guard let text = p.string(ChargePower.key(name)) else { return nil }
        return try? JSONObject(text)
    }

    static func observe(_ st: VehicleStatus) {
        lock.lock()
        defer { lock.unlock() }
        guard let p = ChargePower.store(), let odo = st.odometerKm, odo > 0 else { return }
        let now = Snap(t: st.updatedAtMillis ?? clock(), odo: odo, lat: st.lat, lon: st.lon, pct: st.energyType == "Fuel" ? nil : st.batteryPct, res: st.residualKwh, cap: st.capacityKwh)
        let lastKey = ChargePower.key("ctLast")
        let openKey = ChargePower.key("ctOpen")
        guard let last = Snap.of(object(p, "ctLast")) else {
            p.set(lastKey, now.json().toString())
            return
        }
        var open = object(p, "ctOpen")
        if now.t <= last.t && now.odo <= last.odo { return }   // nothing new from the car
        let moved = now.odo - last.odo
        if moved < -1 {
            // another car or a reset
            p.set(lastKey, now.json().toString())
            p.set(openKey, nil)
            return
        }
        // A drive that was still going at the last report but has since been idle for long: close it first.
        if let o = open, moved > 0.3, now.t - (Snap.of(o.optJSONObject("end"))?.t ?? 0) > 45 * 60_000 {
            save(p, o)
            open = nil
        }
        let stopped = !st.moving && st.ignition != "Drive" && st.ignition != "StartUp"
        if moved > 0.3 {
            let o: JSONObject
            if let existing = open {
                o = existing
                o.put("end", now.json())
                o.put("live", o.optBoolean("live") || st.moving)
            } else {
                o = JSONObject()
                o.put("start", last.json())
                o.put("end", now.json())
                o.put("live", st.moving)
            }
            open = o
            if stopped {
                save(p, o)
                open = nil
            }
        } else if let o = open, stopped || now.t - (Snap.of(o.optJSONObject("end"))?.t ?? now.t) > 15 * 60_000 {
            save(p, o)
            open = nil
        }
        p.set(lastKey, now.json().toString())
        p.set(openKey, open?.toString())
    }

    private static func save(_ p: KeyValueStore, _ open: JSONObject) {
        guard let a = Snap.of(open.optJSONObject("start")), let b = Snap.of(open.optJSONObject("end")) else { return }
        let km = b.odo - a.odo
        if km < 0.3 { return }
        // The car may only have reported long before and right after the drive: estimate the start from about 45 km/h.
        let est = max(JSONValue.javaLong(km / 45.0 * 3_600_000), 3 * 60_000)
        let start = open.optBoolean("live") ? a.t : max(a.t, b.t - est)
        var kwh: Double? = nil
        if let ar = a.res, let br = b.res, br < ar {
            kwh = ar - br
        } else if let ap = a.pct, let bp = b.pct, bp < ap, let cap = b.cap ?? a.cap {
            kwh = (ap - bp) / 100.0 * cap
        }
        if let k = kwh, !(k / km * 100 >= 5.0 && k / km * 100 <= 60.0) { kwh = nil }
        func orNull(_ d: Double?) -> JSONValue { d.map { JSONValue.double($0) } ?? JSONValue.null }
        let o = JSONObject()
        o.put("id", "car-\(b.t)")
        o.put("start", start)
        o.put("end", b.t)
        o.put("km", km)
        o.put("min", Double(b.t - start) / 60_000.0)
        o.put("kwh", kwh ?? 0.0)
        o.put("avg", kwh.map { $0 / km * 100 } ?? 0.0)
        o.put("speed", b.t > start ? km / (Double(b.t - start) / 3_600_000.0) : 0.0)
        o.put("aLat", orNull(a.lat))
        o.put("aLon", orNull(a.lon))
        o.put("bLat", orNull(b.lat))
        o.put("bLon", orNull(b.lon))
        o.put("estimated", !open.optBoolean("live"))
        let key = ChargePower.key("ctTrips")
        let arr = (try? JSONArray(p.string(key) ?? "[]")) ?? JSONArray()
        arr.put(o)
        let keep = JSONArray()
        for item in arr.items.suffix(maxTrips) { keep.put(item) }
        p.set(key, keep.toString())
    }

    /** Trips logged from the car's reports, newest first. */
    static func list() -> [Trip] {
        guard let p = ChargePower.store(), let arr = try? JSONArray(p.string(ChargePower.key("ctTrips")) ?? "[]") else { return [] }
        let trips = arr.objects().map { importedTrip($0, estimatedSpeedHidden: true) }
        return stableSorted(trips) { ($0.startMillis ?? 0) > ($1.startMillis ?? 0) }
    }

    /// A trip saved by LionFlow itself (logged from the car, or imported from a file): id, start, end, km, min, kwh,
    /// avg, speed. With `estimatedSpeedHidden`, a speed that was only estimated is left out.
    static func importedTrip(_ o: JSONObject, estimatedSpeedHidden: Bool = false) -> Trip {
        func positive(_ v: Double) -> Double? { v > 0 ? v : nil }
        let start = o.optLong("start")
        let end = o.optLong("end")
        var speed = positive(o.optDouble("speed", 0.0))
        if estimatedSpeedHidden && o.optBoolean("estimated") { speed = nil }
        return Trip(id: o.optString("id"), startMillis: start > 0 ? start : nil, endMillis: end > 0 ? end : nil,
                    distanceKm: o.optDouble("km", 0.0), durationSec: o.optDouble("min", 0.0) * 60,
                    kwh: positive(o.optDouble("kwh", 0.0)), avgKwhPer100: positive(o.optDouble("avg", 0.0)), avgSpeedKmh: speed)
    }
}
