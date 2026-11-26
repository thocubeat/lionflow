import Foundation

/// Reads trips exported from other apps: LionFlow's own CSV, the official MyPeugeot / MyCitroën / MyDS / MyOpel
/// app's CSV export (columns named in the app's language, ";" or "," between them) and its full ".myp" export (JSON).
/// Returns LionFlow's stored form: one JSON object per trip (id, start, end, km, min, kwh, avg, speed) and the tag
/// name, if the file had one. The same rules as the Android app, so a file reads the same on both.
enum TripImport {
    struct Row {
        var json: JSONObject
        var tag: String?
    }

    /** The time zone that dates without one are read in: the phone's own, unless a test sets another. */
    static var timeZone = TimeZone.current

    static func parse(_ text: String) throws -> [Row] {
        // A byte-order mark and blank space at the start are not part of the file.
        let skip: Set<UInt32> = [0xFEFF, 0x20, 0x0A, 0x0D, 0x09]
        var kept = String.UnicodeScalarView()
        var started = false
        for u in text.unicodeScalars {
            if !started && skip.contains(u.value) { continue }
            started = true
            kept.append(u)
        }
        let t = String(kept)
        return (t.hasPrefix("[") || t.hasPrefix("{")) ? try parseMyp(t) : try parseCsv(t)
    }

    // MARK: .myp (JSON): [{ "vin": …, "trips": [ { "id", "startDateTime" (s), "endDateTime", "startMileage", "endMileage", … } ] }]

    private static func parseMyp(_ t: String) throws -> [Row] {
        let root: JSONArray
        if t.hasPrefix("[") { root = try JSONArray(t) } else { root = JSONArray(); root.put(try JSONObject(t)) }
        var out: [Row] = []
        for i in 0..<root.length() {
            guard let car = root.optJSONObject(i), let trips = car.optJSONArray("trips") else { continue }
            let vin = String(car.optString("vin").suffix(6))
            for k in 0..<trips.length() {
                guard let o = trips.optJSONObject(k) else { continue }
                guard let start = secOrMs(o, ["startDateTime", "startDate", "start"]) else { continue }
                var distance = number(o, "distance")
                if distance == nil {
                    let d = o.optDouble("endMileage", Double.nan) - o.optDouble("startMileage", Double.nan)
                    if !d.isNaN && d >= 0 { distance = d }
                }
                guard let km = distance else { continue }
                var end = secOrMs(o, ["endDateTime", "endDate", "end"])
                if end == nil, let dur = number(o, "duration") { end = start &+ JSONValue.javaLong(dur * 1000) }
                var kwh = 0.0
                for key in ["electricConsumption", "consumptionElectric", "energyConsumption"] {
                    if let v = number(o, key) { kwh = v; break }
                }
                let id = o.opt("id").map { $0.javaString } ?? String(start)
                out.append(Row(json: try store("myp-\(vin)-\(id)", start, end ?? start, km, kwh), tag: nil))
            }
        }
        return out
    }

    private static func number(_ o: JSONObject, _ key: String) -> Double? {
        let d = o.optDouble(key, Double.nan)
        return d.isNaN ? nil : d
    }

    /** A whole number written as text, the way Kotlin's `toLongOrNull` reads it. */
    private static func long(_ s: String) -> Int64? {
        var digits = Substring(s)
        var negative = false
        if let f = digits.first, f == "-" || f == "+" {
            negative = f == "-"
            digits = digits.dropFirst()
        }
        if digits.isEmpty || !digits.unicodeScalars.allSatisfy({ $0.value >= 0x30 && $0.value <= 0x39 }) { return nil }
        return Int64((negative ? "-" : "") + String(digits))
    }

    /** A time that may be in seconds or milliseconds, or written out as a date. */
    private static func secOrMs(_ o: JSONObject, _ keys: [String]) -> Int64? {
        for k in keys {
            guard o.has(k), let v = o.opt(k) else { continue }
            let n: Int64?
            switch v {
            case .int(let i): n = i
            case .double(let d): n = JSONValue.javaLong(d)
            default: n = long(v.javaString)
            }
            if let n = n, n > 0 { return n < 10_000_000_000 ? n * 1000 : n }
            if let d = date(v.javaString) { return d }
        }
        return nil
    }

    // MARK: CSV

    private static func lines(_ t: String) -> [String] {
        t.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
    }

    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: .whitespacesAndNewlines) }

    private static func parseCsv(_ t: String) throws -> [Row] {
        let rows = lines(t).filter { !trim($0).isEmpty }
        if rows.count < 2 { return [] }
        let head = rows[0]
        // The separator is the one the heading has most of (";" wins a tie, then a tab).
        var sep: Unicode.Scalar = ";"
        var most = -1
        for c in [";", "\t", ","] as [Unicode.Scalar] {
            let n = head.unicodeScalars.filter { $0 == c }.count
            if n > most { most = n; sep = c }
        }
        let cols = split(head, sep).map { norm($0) }
        // LionFlow's own export.
        if cols.first == "id" && cols.count > 1 && cols[1] == "start" {
            var out: [Row] = []
            for line in rows.dropFirst() { if let r = try own(split(line, sep)) { out.append(r) } }
            return out
        }

        func find(_ words: [String], not: [String] = []) -> Int {
            cols.firstIndex { c in words.contains { c.contains($0) } && !not.contains { c.contains($0) } } ?? -1
        }
        let places = ["km", "mileage", "kilom", "odom", "lieu", "adresse", "address", "posit", "place", "lat", "lon"]
        let iStart = find(["start", "debut", "départ", "depart", "beginn", "inicio", "partenza", "begin", "dato", "date"], not: places)
        let iEnd = find(["end", "fin", "arrivee", "arrivée", "ende", "llegada", "arrivo", "slutt"], not: places)
        let iKm = find(["distance", "km", "kilom", "afstand", "strecke", "lengde"])
        let iDur = find(["duree", "durée", "duration", "dauer", "duracion", "durata", "varighet", "tid", "time"])
        let iKwh = find(["kwh", "energ", "electri"], not: ["/100", "100 km", "100km", "moy", "avg", "average"])
        let iAvg = find(["/100", "100 km", "100km", "moyen", "average", "durchschn", "media", "snitt"])
        if iStart < 0 || iKm < 0 { return [] }
        var out: [Row] = []
        for line in rows.dropFirst() {
            let c = split(line, sep)
            func at(_ i: Int) -> String? { i >= 0 && i < c.count ? c[i] : nil }
            guard let start = at(iStart).flatMap({ date($0) }) else { continue }
            guard let km = num(at(iKm)) else { continue }
            var end = start
            if let e = at(iEnd).flatMap({ date($0) }) {
                end = e
            } else if let dur = at(iDur).flatMap({ minutes($0) }) {
                end = start &+ JSONValue.javaLong(dur * 60_000)
            }
            var kwh = 0.0
            if let e = num(at(iKwh)) { kwh = e } else if let avg = num(at(iAvg)) { kwh = avg * km / 100 }
            out.append(Row(json: try store("csv-\(start)-\(JSONValue.javaLong(km * 10))", start, end, km, kwh), tag: nil))
        }
        return out
    }

    private static func own(_ c: [String]) throws -> Row? {
        if c.count < 4 { return nil }
        func at(_ i: Int) -> String? { i < c.count ? c[i] : nil }
        func time(_ s: String?) -> Int64 { s.flatMap { stamp($0, "yyyy-MM-dd HH:mm", lenient: true)?.millis } ?? 0 }
        func value(_ i: Int) -> Double { at(i).flatMap { JSONValue.javaParseDouble($0) } ?? 0.0 }
        let j = JSONObject()
        j.put("id", c[0].isEmpty ? "imp-\(DispatchTime.now().uptimeNanoseconds)" : c[0])
        j.put("start", time(at(1)))
        j.put("end", time(at(2)))
        for (key, i) in [("km", 3), ("min", 4), ("kwh", 5), ("avg", 6), ("speed", 7)] {
            let v = value(i)
            if !v.isFinite { throw JSONError("Forbidden numeric value: \(v)") }
            j.put(key, v)
        }
        let tag = at(8).map { trim($0) }
        return Row(json: j, tag: (tag ?? "").isEmpty ? nil : tag)
    }

    private static func store(_ id: String, _ start: Int64, _ end: Int64, _ km: Double, _ kwh: Double) throws -> JSONObject {
        let min = max(Double(end &- start) / 60_000.0, 0.0)
        let avg = (km > 0 && kwh > 0) ? kwh / km * 100 : 0.0
        let speed = min > 0 ? km / (min / 60) : 0.0
        // Android's JSON refuses numbers that are not numbers; the whole file then counts as unreadable.
        for v in [km, min, kwh, avg, speed] where !v.isFinite { throw JSONError("Forbidden numeric value: \(v)") }
        let j = JSONObject()
        j.put("id", id)
        j.put("start", start)
        j.put("end", end)
        j.put("km", km)
        j.put("min", min)
        j.put("kwh", kwh)
        j.put("avg", avg)
        j.put("speed", speed)
        return j
    }

    /** Splits one line at the separator; a separator inside "quotes" stays, and the quotes go. */
    private static func split(_ line: String, _ sep: Unicode.Scalar) -> [String] {
        var out: [String] = []
        var cur = String.UnicodeScalarView()
        var quoted = false
        for ch in line.unicodeScalars {
            if ch == "\"" {
                quoted.toggle()
            } else if ch == sep && !quoted {
                out.append(trim(String(cur)))
                cur = String.UnicodeScalarView()
            } else {
                cur.append(ch)
            }
        }
        out.append(trim(String(cur)))
        return out
    }

    private static func norm(_ s: String) -> String {
        trim(s).trimmingCharacters(in: CharacterSet(charactersIn: "\"")).lowercased()
    }

    /** "12,5" / "12.5" / "12.5 km" → 12.5 */
    private static func num(_ s: String?) -> Double? {
        guard let s = s else { return nil }
        let kept = String(String.UnicodeScalarView(s.unicodeScalars.filter { ($0.value >= 0x30 && $0.value <= 0x39) || $0 == "," || $0 == "." || $0 == "-" }))
        let commas = kept.filter { $0 == "," }.count
        let t = (commas == 1 && !kept.contains(".")) ? kept.replacingOccurrences(of: ",", with: ".") : kept.replacingOccurrences(of: ",", with: "")
        return JSONValue.javaParseDouble(t)
    }

    private static let clock = try! NSRegularExpression(pattern: "^([0-9]+):([0-9]{1,2})(?::([0-9]{1,2}))?$")
    private static let hours = try! NSRegularExpression(pattern: "([0-9]+)[ \\t\\n\\u000B\\f\\r]*h[ \\t\\n\\u000B\\f\\r]*([0-9]+)?")
    private static let spaces = try! NSRegularExpression(pattern: "[ \\t\\n\\u000B\\f\\r]+")

    private static func groups(_ re: NSRegularExpression, _ t: String) -> [String]? {
        let ns = t as NSString
        guard let m = re.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)) else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0).location == NSNotFound ? "" : ns.substring(with: m.range(at: $0)) }
    }

    /** "1:05", "01:05:30", "65", "65 min", "1h05" → minutes */
    private static func minutes(_ s: String) -> Double? {
        let t = trim(s)
        if let g = groups(clock, t), let a = Double(g[1]), let b = Double(g[2]) {
            if !g[3].isEmpty, let c = Double(g[3]) { return a * 60 + b + c / 60 }
            return a * 60 + b
        }
        if let g = groups(hours, t), let a = Double(g[1]) { return a * 60 + (Double(g[2]) ?? 0.0) }
        return num(t)
    }

    private static let formats = [
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm",
        "dd.MM.yyyy HH:mm:ss", "dd.MM.yyyy HH:mm", "dd-MM-yyyy HH:mm:ss", "dd-MM-yyyy HH:mm", "MM/dd/yyyy HH:mm:ss", "MM/dd/yyyy HH:mm",
        "MM/dd/yyyy hh:mm a", "dd/MM/yyyy", "dd.MM.yyyy", "yyyy-MM-dd",
    ]

    /** A date and time as text, in any of the ways the export files write it, in milliseconds since 1970. */
    static func date(_ s: String) -> Int64? {
        var t = trim(s).trimmingCharacters(in: CharacterSet(charactersIn: "\"")).replacingOccurrences(of: " - ", with: " ")
        t = spaces.stringByReplacingMatches(in: t, range: NSRange(location: 0, length: (t as NSString).length), withTemplate: " ")
        if t.isEmpty { return nil }
        if let n = long(t) { return n < 10_000_000_000 ? n &* 1000 : n }
        if let iso = isoWithOffset(t) { return iso }
        let length = t.utf16.count
        for f in formats {
            // The whole text has to be the date; one stray character at the end is let through, as on Android.
            if let r = stamp(t, f, lenient: false), r.end >= length - 1 { return r.millis }
        }
        return nil
    }

    private static let iso = try! NSRegularExpression(
        pattern: "^([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]{0,9}))?)?([Zz]|[+-][0-9]{2}:[0-9]{2}(?::[0-9]{2})?)$")

    /** "2026-11-26T09:08:40+01:00": a date with its own time zone, the strict ISO way. */
    private static func isoWithOffset(_ t: String) -> Int64? {
        guard let g = groups(iso, t) else { return nil }
        guard let y = Int(g[1]), let mo = Int(g[2]), let d = Int(g[3]), let h = Int(g[4]), let mi = Int(g[5]) else { return nil }
        let sec = g[6].isEmpty ? 0 : (Int(g[6]) ?? 0)
        if mo < 1 || mo > 12 || d < 1 || d > daysIn(y, mo) || h > 23 || mi > 59 || sec > 59 { return nil }
        var offset = 0
        let zone = g[8]
        if zone != "Z" && zone != "z" {
            let parts = zone.dropFirst().components(separatedBy: ":").compactMap { Int($0) }
            if parts.count < 2 || parts[0] > 18 || parts[1] > 59 || (parts.count > 2 && parts[2] > 59) { return nil }
            offset = parts[0] * 3600 + parts[1] * 60 + (parts.count > 2 ? parts[2] : 0)
            if offset > 18 * 3600 { return nil }
            if zone.hasPrefix("-") { offset = -offset }
        }
        let days = daysFromCivil(y, mo, d)
        let seconds = days * 86_400 + Int64(h * 3600 + mi * 60 + sec) - Int64(offset)
        let fraction = (g[7] + "000").prefix(3)
        return seconds * 1000 + (Int64(fraction) ?? 0)
    }

    private static func isLeap(_ y: Int) -> Bool { (y % 4 == 0 && y % 100 != 0) || y % 400 == 0 }
    private static func daysIn(_ y: Int, _ m: Int) -> Int {
        [31, isLeap(y) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1]
    }
    /** Days from 1 January 1970 to a date. */
    private static func daysFromCivil(_ year: Int, _ m: Int, _ d: Int) -> Int64 {
        let y = Int64(m <= 2 ? year - 1 : year)
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let mp = Int64(m > 2 ? m - 3 : m + 9)
        let doy = (153 * mp + 2) / 5 + Int64(d) - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /// Reads `text` from its start by a pattern made of yyyy, MM, dd, HH, hh, mm, ss, a and fixed characters, the
    /// way Java's SimpleDateFormat does: blanks before a number are skipped, a number takes all the digits there
    /// are, and what comes after the pattern is left alone (`end` says how far it got). Not `lenient`: a date that
    /// doesn't exist (30 February, 25:00) is no date.
    private static func stamp(_ text: String, _ pattern: String, lenient: Bool) -> (millis: Int64, end: Int)? {
        let t = Array(text.utf16)
        let p = Array(pattern.utf16)
        var i = 0
        var year = 1970, month = 1, day = 1, hour = 0, minute = 0, second = 0
        var hour12: Int? = nil
        var pm = false
        var k = 0
        func isLetter(_ c: UInt16) -> Bool { (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) }
        while k < p.count {
            let c = p[k]
            if c == 0x27 {                                   // 'quoted text'
                k += 1
                while k < p.count && p[k] != 0x27 {
                    if i >= t.count || t[i] != p[k] { return nil }
                    i += 1
                    k += 1
                }
                k += 1
                continue
            }
            if !isLetter(c) {
                if i >= t.count || t[i] != c { return nil }
                i += 1
                k += 1
                continue
            }
            while k < p.count && p[k] == c { k += 1 }
            while i < t.count && (t[i] == 0x20 || t[i] == 0x09) { i += 1 }
            if i >= t.count { return nil }
            if c == 0x61 {                                   // a: AM or PM
                guard i + 1 < t.count, t[i + 1] | 0x20 == 0x6D else { return nil }
                let first = t[i] | 0x20
                if first == 0x70 { pm = true } else if first != 0x61 { return nil }
                i += 2
                continue
            }
            var value = 0
            var digits = 0
            while i < t.count && t[i] >= 0x30 && t[i] <= 0x39 && digits < 9 {
                value = value * 10 + Int(t[i] - 0x30)
                i += 1
                digits += 1
            }
            if digits == 0 || (i < t.count && t[i] >= 0x30 && t[i] <= 0x39) { return nil }
            switch c {
            case 0x79: year = value
            case 0x4D: month = value
            case 0x64: day = value
            case 0x48: hour = value
            case 0x68:
                if !lenient && (value < 1 || value > 12) { return nil }
                hour12 = value
            case 0x6D: minute = value
            case 0x73: second = value
            default: return nil
            }
        }
        if let h = hour12 { hour = h % 12 + (pm ? 12 : 0) }
        if lenient {
            // Java rolls what is out of range over into the next unit: month 13 is January, 25:00 is 01:00 the day after.
            let months = Int64(year) * 12 + Int64(month - 1)
            var seconds = Int64(hour) * 3600 + Int64(minute) * 60 + Int64(second)
            let days = daysFromCivil(Int(floorDiv(months, 12)), Int(floorMod(months, 12)) + 1, 1) + Int64(day - 1) + floorDiv(seconds, 86_400)
            seconds = floorMod(seconds, 86_400)
            (year, month, day) = civilFromDays(days)
            hour = Int(seconds / 3600)
            minute = Int(seconds % 3600 / 60)
            second = Int(seconds % 60)
        } else if year < 1 {
            return nil
        }
        guard let when = wallTime(year, month, day, hour, minute, second, strict: !lenient) else { return nil }
        return (when, i)
    }

    private static func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
        let q = a / b
        return (a % b != 0 && (a < 0) != (b < 0)) ? q - 1 : q
    }
    private static func floorMod(_ a: Int64, _ b: Int64) -> Int64 { a - floorDiv(a, b) * b }

    private static func civilFromDays(_ days: Int64) -> (Int, Int, Int) {
        let z = days + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
        return (Int(y), Int(m), Int(d))
    }

    /// The moment a clock time stands for in `timeZone`. `strict`: a time that doesn't exist (the hour skipped when
    /// summer time starts, or 30 February) is no time. A time that comes twice (the night summer time ends) is the
    /// second one, as in Java.
    private static func wallTime(_ y: Int, _ mo: Int, _ d: Int, _ h: Int, _ mi: Int, _ s: Int, strict: Bool) -> Int64? {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        var parts = DateComponents()
        parts.year = y
        parts.month = mo
        parts.day = d
        parts.hour = h
        parts.minute = mi
        parts.second = s
        guard var when = cal.date(from: parts) else { return nil }
        func same(_ date: Date) -> Bool {
            let b = cal.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
            return b.year == y && b.month == mo && b.day == d && b.hour == h && b.minute == mi && b.second == s
        }
        if strict && !same(when) { return nil }
        for shift in [3600.0, 1800.0, 7200.0] {
            let later = when.addingTimeInterval(shift)
            if same(later) {
                when = later
                break
            }
        }
        return Int64((when.timeIntervalSince1970 * 1000).rounded())
    }
}
