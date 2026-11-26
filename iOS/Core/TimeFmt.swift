import Foundation

/** 12- or 24-hour clock, from Settings (or the phone's own setting). */
enum TimeFmt {
    private(set) static var use24 = true

    static func setPreference(_ pref: String) {
        switch pref {
        case "12": use24 = false
        case "24": use24 = true
        default:
            // "j" is the hour as this phone shows it: a pattern with "a" in it means a 12-hour clock.
            let p = DateFormatter.dateFormat(fromTemplate: "j", options: 0, locale: Locale.current) ?? ""
            use24 = !p.contains("a")
        }
    }

    /** Turns a date pattern written with HH:mm into the chosen clock. */
    static func pattern(_ p: String) -> String {
        use24 ? p : p.replacingOccurrences(of: "HH:mm:ss", with: "h:mm:ss a").replacingOccurrences(of: "HH:mm", with: "h:mm a")
    }

    static func hm(_ h: Int, _ m: Int) -> String {
        if use24 { return String(format: "%02d:%02d", h, m) }
        return String(format: "%d:%02d %@", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM")
    }

    /** A time in milliseconds since 1970, written with a pattern like "EEE d MMM HH:mm" in the app's language. */
    static func format(_ millis: Int64, _ pattern: String, timeZone: TimeZone = .current) -> String {
        let f = DateFormatter()
        f.locale = L10n.lang == "en" ? Locale(identifier: "en_US_POSIX") : L10n.locale
        f.timeZone = timeZone
        f.dateFormat = TimeFmt.pattern(pattern)
        return f.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }
}
