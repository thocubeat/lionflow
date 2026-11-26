import Foundation

/** A label the user puts on trips ("Work", "Client A", "Holiday"). `color` is an index into the 7 tag colours. */
struct TripTag: Equatable, Identifiable {
    var id: String
    var name: String
    var color: Int
    var from: TagPlace? = nil
    var to: TagPlace? = nil
}

/** An address the user chose as one end of a tag's route. */
struct TagPlace: Equatable {
    var name: String
    var lat: Double
    var lon: Double
}

/** Where a trip started and ended (from a recorded route). */
struct TripEnds: Equatable {
    var aLat: Double
    var aLon: Double
    var bLat: Double
    var bLon: Double
}

/// What a trip is tagged with. `tag` is empty when the user removed the tag on purpose (then it is never
/// tagged automatically again). `km` and `speed` are kept so later trips on the same route can be recognised.
struct TagMark: Equatable {
    var tag: String
    var auto: Bool
    var km: Double
    var speed: Double?
    var at: Int64
    var ends: TripEnds? = nil
}

struct TagData: Equatable {
    var tags: [TripTag] = []
    var marks: [String: TagMark] = [:]
}

/** The user's trip tags, kept on the phone only. */
final class TripTags {
    static let colors = 7
    static let maxName = 24
    private static let maxMarks = 3000
    /** How far apart two drives of the same route can be. */
    static let speedTol = 8.0
    /** How close (km) a trip's start and end must be to a tagged trip's, and to a tag's addresses. */
    static let placeKm = 0.3
    static let ruleKm = 0.5

    private let save: (String) -> Void
    private(set) var data: TagData
    /** Told when the tags changed, so the screens can follow. */
    var onChange: (() -> Void)?
    var clock: () -> Int64 = { nowMillis() }

    init(load: () -> String, save: @escaping (String) -> Void) {
        self.save = save
        self.data = TripTags.parse(load())
    }

    func tagOf(_ tripId: String) -> TripTag? {
        guard let id = data.marks[tripId]?.tag, !id.isEmpty else { return nil }
        return data.tags.first { $0.id == id }
    }
    func isAuto(_ tripId: String) -> Bool { data.marks[tripId]?.auto == true }
    func byName(_ name: String) -> TripTag? {
        let wanted = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return data.tags.first { $0.name.caseInsensitiveCompare(wanted) == .orderedSame }
    }

    private static func clean(_ name: String) -> String { String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxName)) }
    private static func color(_ c: Int) -> Int { min(max(c, 0), colors - 1) }

    @discardableResult
    func create(_ name: String, _ color: Int) -> TripTag {
        if let existing = byName(name) { return existing }
        let t = TripTag(id: "t" + String(clock(), radix: 36) + String(data.tags.count), name: TripTags.clean(name), color: TripTags.color(color))
        var d = data
        d.tags.append(t)
        put(d)
        return t
    }

    func edit(_ id: String, _ name: String, _ color: Int, _ from: TagPlace?, _ to: TagPlace?) {
        var d = data
        d.tags = d.tags.map { $0.id == id ? TripTag(id: $0.id, name: TripTags.clean(name), color: TripTags.color(color), from: from, to: to) : $0 }
        put(d)
    }

    /** Removes the tag everywhere. */
    func delete(_ id: String) {
        put(TagData(tags: data.tags.filter { $0.id != id }, marks: data.marks.filter { $0.value.tag != id }))
    }

    /** The user's own choice for a trip; nil takes the tag off. */
    func set(_ trip: Trip, _ tagId: String?, _ ends: TripEnds? = nil) {
        let mark = TagMark(tag: tagId ?? "", auto: false, km: trip.distanceKm, speed: TripTags.speedOf(trip), at: trip.startMillis ?? clock(), ends: ends)
        var d = data
        // A trip that never had a tag just goes back to having none.
        if tagId == nil && data.marks[trip.id]?.auto != true { d.marks[trip.id] = nil } else { d.marks[trip.id] = mark }
        put(d)
    }

    /// Tags trips that look like a trip the user tagged before: about the same distance and average speed.
    /// Trips the user has already decided on are left alone. Returns how many were tagged.
    ///
    /// With `ends` (the "same route" option), a trip whose recorded start and end are known is matched by
    /// place instead: first against the addresses set on a tag, then against tagged trips that were recorded.
    /// Both directions count, so the drive home gets the same tag. Trips without a recording, and tagged
    /// trips without one, still go by distance and speed.
    @discardableResult
    func autoTag(_ trips: [Trip], _ ends: ((Trip) -> TripEnds?)? = nil) -> Int {
        // In a fixed order, so equally good matches always give the same answer.
        let known = data.marks.sorted { $0.key < $1.key }.map { $0.value }
            .filter { m in !m.auto && !m.tag.isEmpty && data.tags.contains { $0.id == m.tag } }
        let ruled = data.tags.filter { $0.from != nil && $0.to != nil }
        if known.isEmpty && (ends == nil || ruled.isEmpty) { return 0 }
        var marks = data.marks
        var n = 0
        for t in trips {
            if marks[t.id] != nil { continue }
            let speed = TripTags.speedOf(t)
            let e = ends?(t)
            var tag: String? = nil
            if let e = e {
                var best: (Double, String)? = nil
                for r in ruled {
                    guard let f = r.from, let to = r.to, let d = TripTags.apart(e, TripEnds(aLat: f.lat, aLon: f.lon, bLat: to.lat, bLon: to.lon), TripTags.ruleKm) else { continue }
                    if best == nil || d < best!.0 { best = (d, r.id) }
                }
                if best == nil {
                    for k in known {
                        guard let ke = k.ends, let d = TripTags.apart(e, ke, TripTags.placeKm) else { continue }
                        if best == nil || d < best!.0 { best = (d, k.tag) }
                    }
                }
                tag = best?.1
            }
            if tag == nil, let speed = speed {
                // By distance and speed. A recorded trip is never matched this way against another recorded trip: their places differ.
                var best: (Double, String)? = nil
                for k in known where e == nil || k.ends == nil {
                    guard let d = TripTags.likeness(t.distanceKm, speed, k) else { continue }
                    if best == nil || d < best!.0 { best = (d, k.tag) }
                }
                tag = best?.1
            }
            guard let found = tag else { continue }
            marks[t.id] = TagMark(tag: found, auto: true, km: t.distanceKm, speed: speed, at: t.startMillis ?? clock(), ends: e)
            n += 1
        }
        if n > 0 {
            var d = data
            d.marks = marks
            put(d)
        }
        return n
    }

    /** Takes off every tag that was put on automatically (used when the feature is turned off). */
    func clearAuto() {
        var d = data
        d.marks = d.marks.filter { !$0.value.auto }
        put(d)
    }

    private func put(_ d: TagData) {
        var out = d
        // Keep the newest marks only, so this never grows without end.
        if d.marks.count > TripTags.maxMarks {
            let newest = d.marks.sorted { $0.value.at > $1.value.at }.prefix(TripTags.maxMarks)
            out.marks = Dictionary(uniqueKeysWithValues: newest.map { ($0.key, $0.value) })
        }
        data = out
        save(TripTags.toJson(out))
        onChange?()
    }

    static func km(_ aLat: Double, _ aLon: Double, _ bLat: Double, _ bLon: Double) -> Double {
        func rad(_ d: Double) -> Double { d * 0.017453292519943295 }
        let dLat = rad(bLat - aLat)
        let dLon = rad(bLon - aLon)
        let a = sin(dLat / 2)
        let b = sin(dLon / 2)
        let h = a * a + cos(rad(aLat)) * cos(rad(bLat)) * b * b
        return 6371.0 * 2 * asin(sqrt(h))
    }

    /** How far apart (km, both ends added) two trips are, driven either way; nil when not the same two places. */
    static func apart(_ x: TripEnds, _ y: TripEnds, _ tol: Double) -> Double? {
        let same = (km(x.aLat, x.aLon, y.aLat, y.aLon), km(x.bLat, x.bLon, y.bLat, y.bLon))
        let back = (km(x.aLat, x.aLon, y.bLat, y.bLon), km(x.bLat, x.bLon, y.aLat, y.aLon))
        return [same, back].filter { $0.0 <= tol && $0.1 <= tol }.map { $0.0 + $0.1 }.min()
    }

    static func kmTol(_ km: Double) -> Double { min(max(km * 0.1, 0.5), 2.0) }

    /** Average speed from distance and time (the car's own figure isn't reliable on every model). */
    static func speedOf(_ t: Trip) -> Double? {
        if let d = t.durationSec, d >= 60, t.distanceKm > 0 { return t.distanceKm / (d / 3600.0) }
        if let s = t.avgSpeedKmh, s > 0 { return s }
        return nil
    }

    /** Lower is more alike; nil when the trip is not the same route. */
    static func likeness(_ km: Double, _ speed: Double, _ k: TagMark) -> Double? {
        guard let ks = k.speed else { return nil }
        let dk = abs(km - k.km) / kmTol(k.km)
        let ds = abs(speed - ks) / speedTol
        return dk <= 1.0 && ds <= 1.0 ? dk + ds : nil
    }

    static func parse(_ text: String) -> TagData {
        guard let j = try? JSONObject(text.allSatisfy({ $0.isWhitespace }) ? "{}" : text) else { return TagData() }
        func place(_ o: JSONObject?) -> TagPlace? {
            guard let o = o, o.has("lat"), o.has("lon") else { return nil }
            return TagPlace(name: o.optString("n"), lat: o.optDouble("lat"), lon: o.optDouble("lon"))
        }
        let tags = (j.optJSONArray("tags")?.objects() ?? [])
            .map { TripTag(id: $0.optString("id"), name: $0.optString("name"), color: $0.optInt("color"), from: place($0.optJSONObject("from")), to: place($0.optJSONObject("to"))) }
            .filter { !$0.id.isEmpty && !$0.name.isEmpty }
        var marks: [String: TagMark] = [:]
        if let m = j.optJSONObject("marks") {
            for id in m.keys() {
                guard let o = m.optJSONObject(id) else { continue }
                var e: TripEnds? = nil
                if let a = o.optJSONArray("ends"), a.length() == 4 { e = TripEnds(aLat: a.optDouble(0), aLon: a.optDouble(1), bLat: a.optDouble(2), bLon: a.optDouble(3)) }
                marks[id] = TagMark(tag: o.optString("tag"), auto: o.optBoolean("auto"), km: o.optDouble("km", 0.0), speed: o.has("speed") ? o.optDouble("speed") : nil, at: o.optLong("at"), ends: e)
            }
        }
        return TagData(tags: tags, marks: marks)
    }

    static func toJson(_ d: TagData) -> String {
        func placeJson(_ p: TagPlace) -> JSONObject {
            let o = JSONObject()
            o.put("n", p.name)
            o.put("lat", p.lat)
            o.put("lon", p.lon)
            return o
        }
        let tags = JSONArray()
        for t in d.tags {
            let o = JSONObject()
            o.put("id", t.id)
            o.put("name", t.name)
            o.put("color", t.color)
            if let p = t.from { o.put("from", placeJson(p)) }
            if let p = t.to { o.put("to", placeJson(p)) }
            tags.put(o)
        }
        let marks = JSONObject()
        for (id, k) in d.marks.sorted(by: { $0.key < $1.key }) {
            let o = JSONObject()
            o.put("tag", k.tag)
            o.put("auto", k.auto)
            o.put("km", k.km)
            o.put("at", k.at)
            if let s = k.speed { o.put("speed", s) }
            if let e = k.ends { o.put("ends", JSONArray().put(e.aLat).put(e.aLon).put(e.bLat).put(e.bLon)) }
            marks.put(id, o)
        }
        let out = JSONObject()
        out.put("tags", tags)
        out.put("marks", marks)
        return out.toString()
    }
}
