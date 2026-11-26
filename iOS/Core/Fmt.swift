import Foundation

// Numbers and times written the way the Android app writes them, so a value reads the same on both.

/** Milliseconds since 1970, as `System.currentTimeMillis()`. */
func nowMillis() -> Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }

extension Double {
    /** Kotlin's `roundToInt()` / Java's `Math.round`: a half rounds up (2.5 is 3, -2.5 is -2). */
    var roundToInt: Int { Int(Int32(truncatingIfNeeded: max(Int64(Int32.min), min(Int64(Int32.max), roundToLong)))) }
    var roundToLong: Int64 {
        if isNaN { return 0 }
        let floor = rounded(.down)
        return JSONValue.javaLong(self - floor >= 0.5 ? floor + 1 : floor)
    }
}

enum Fmt {
    /// Java's `String.format(Locale.US, "%.<decimals>f", v)`: the number as it is normally written (its shortest
    /// text), rounded half up at the last place. C's printf rounds the exact binary value instead, so 0.25 with one
    /// decimal is "0.3" here and on Android, where printf would say "0.2".
    static func fixed(_ v: Double, _ decimals: Int, grouping: Bool = false) -> String {
        if v.isNaN { return "NaN" }
        if v.isInfinite { return v < 0 ? "-Infinity" : "Infinity" }
        guard var dec = Decimal(string: "\(abs(v))", locale: Locale(identifier: "en_US_POSIX")) else { return String(format: "%.\(decimals)f", v) }
        var rounded = Decimal()
        NSDecimalRound(&rounded, &dec, decimals, .plain)
        var text = NSDecimalString(&rounded, Locale(identifier: "en_US_POSIX"))
        if text.contains("e") || text.contains("E") { return String(format: "%.\(decimals)f", v) }
        var whole = text
        var frac = ""
        if let dot = text.firstIndex(of: ".") {
            whole = String(text[text.startIndex..<dot])
            frac = String(text[text.index(after: dot)...])
        }
        while frac.count < decimals { frac += "0" }
        if grouping { whole = group(whole) }
        text = decimals > 0 ? whole + "." + frac : whole
        let negative = v < 0 || (v == 0 && v.sign == .minus)
        return negative ? "-" + text : text
    }

    /** Java's "%,d": 13412 as "13,412". */
    static func grouped(_ n: Int64) -> String { (n < 0 ? "-" : "") + group(String(n.magnitude)) }

    private static func group(_ digits: String) -> String {
        var out = ""
        for (i, ch) in digits.enumerated() {
            if i > 0 && (digits.count - i) % 3 == 0 { out.append(",") }
            out.append(ch)
        }
        return out
    }

    /** Hour and minute as "07:05" (Java's "%02d:%02d"). */
    static func hhmm(_ hour: Int, _ minute: Int) -> String { String(format: "%02d:%02d", hour, minute) }
}

func fmtDistance(_ km: Double?, _ s: AppSettings) -> String {
    guard let km = km else { return "—" }
    return s.distanceUnit == "mi" ? Fmt.grouped((km * 0.621371).roundToLong) + " mi" : Fmt.grouped(km.roundToLong) + " km"
}

/** A speed in the unit chosen for distances: "80 km/h" or "50 mph". */
func fmtSpeed(_ kmh: Double, _ s: AppSettings) -> String {
    s.distanceUnit == "mi" ? "\((kmh * 0.621371).roundToLong) mph" : "\(kmh.roundToLong) km/h"
}

func fmtTemp(_ c: Double?, _ s: AppSettings, unit: Bool = true) -> String {
    guard let c = c, !c.isNaN else { return "—" }
    return s.tempUnit == "f" ? "\((c * 9 / 5 + 32).roundToLong)°\(unit ? "F" : "")" : "\(c.roundToLong)°\(unit ? "C" : "")"
}

func minutesAgo(_ then: Int64, _ now: Int64) -> String {
    if then <= 0 { return "not updated yet" }
    let m = (now - then) / 60_000
    if m < 1 { return "just now" }
    if m < 60 { return "\(m) min ago" }
    if m < 60 * 24 { return "\(m / 60) h ago" }
    return "\(m / 1440) d ago"
}
