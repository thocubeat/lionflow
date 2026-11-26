import Foundation

/// Translations. The app is written in English; every text is looked up here right before it is shown.
/// Data values are never translated, so app logic can't break. Texts with values in them ("Stop at 80 %") are
/// matched against templates ("Stop at {0} %"), and the values are translated too when they are known words.
///
/// The texts are the same file the Android app uses (`l10n.tsv`), and the matching follows the same rules, so a
/// text reads the same on both.
enum L10n {
    /** Languages offered in Settings: code to native name. "system" follows the phone. */
    static let options: [(String, String)] = [
        ("system", "System default"),
        ("en", "English"),
        ("fr", "Français"),
        ("de", "Deutsch"),
        ("es", "Español"),
        ("nb", "Norsk (bokmål)"),
        ("nl", "Nederlands"),
        ("fi", "Suomi"),
    ]
    static let columns = ["fr", "de", "es", "nb", "nl", "fi"]

    private struct Template {
        let regex: NSRegularExpression
        let english: String
        let translations: [String]
        /** The digits of its placeholders, each once, in the order they first appear. */
        let placeholders: [String]
        /** Length of the fixed text (UTF-16 units, as on Android). */
        let fixed: Int
    }

    private static let lock = NSLock()
    private static var current = "en"
    private static var exact: [String: [String]] = [:]
    private static var templates: [Template] = []
    private static var cache: [String: String] = [:]
    private static let placeholder = try! NSRegularExpression(pattern: "\\{(\\d)\\}")

    /** The language in use right now (never "system"). */
    static var lang: String { lock.lock(); defer { lock.unlock() }; return current }

    static var locale: Locale {
        switch lang {
        case "nb": return Locale(identifier: "nb_NO")
        case "en": return Locale(identifier: "en")
        default: return Locale(identifier: lang)
        }
    }

    /** Loads the texts (tab-separated: English, then one column per language; the first line is a header). Later files win. */
    static func load(_ files: [String]) {
        var ex: [String: [String]] = [:]
        var tp: [Template] = []
        for text in files {
            for line in lines(text).dropFirst() {
                let cols = line.components(separatedBy: "\t").map { unesc($0) }
                if cols.count < 5 { continue }
                let en = cols[0]
                let tr = (0..<columns.count).map { $0 + 1 < cols.count ? cols[$0 + 1] : "" }
                ex[en] = tr
                // Fragments that are glued to other text: "Ready at " + time, base + " · auto when open".
                var t = en
                var trs = tr
                if !t.allSatisfy({ $0.isWhitespace }) {
                    if t.hasSuffix(" ") { t += "{9}"; trs = trs.map { $0 + "{9}" } }
                    else if t.hasPrefix(" ") { t = "{9}" + t; trs = trs.map { "{9}" + $0 } }
                }
                let marks = placeholderDigits(t)
                if marks.isEmpty { continue }
                guard let re = toRegex(t) else { continue }
                var seen: [String] = []
                for m in marks where !seen.contains(m) { seen.append(m) }
                tp.append(Template(regex: re, english: t, translations: trs, placeholders: seen, fixed: stripped(t).utf16.count))
            }
        }
        // Most specific (most literal text) first; equal ones keep the order of the file.
        let sorted = tp.enumerated().sorted { a, b in a.element.fixed != b.element.fixed ? a.element.fixed > b.element.fixed : a.offset < b.offset }.map { $0.element }
        lock.lock()
        exact = ex
        templates = sorted
        cache.removeAll()
        lock.unlock()
    }

    static var isLoaded: Bool { lock.lock(); defer { lock.unlock() }; return !exact.isEmpty }

    static func setPreference(_ preference: String) {
        let l = (preference == "system" || preference.trimmingCharacters(in: .whitespaces).isEmpty) ? systemLang() : preference
        lock.lock()
        cache.removeAll()
        current = (columns.contains(l) || l == "en") ? l : "en"
        lock.unlock()
    }

    private static func systemLang() -> String {
        let first = Locale.preferredLanguages.first ?? "en"
        let code = first.components(separatedBy: CharacterSet(charactersIn: "-_")).first?.lowercased() ?? "en"
        return (code == "no" || code == "nb" || code == "nn") ? "nb" : code
    }

    /** Lines of a text file, whichever line ending it uses; no empty line for a final line break. */
    private static func lines(_ text: String) -> [String] {
        var out = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n").components(separatedBy: "\n")
        if out.last == "" { out.removeLast() }
        return out
    }

    private static func unesc(_ s: String) -> String {
        if !s.contains("\\") { return s }
        var out = ""
        var it = s.makeIterator()
        while let c = it.next() {
            if c == "\\", let n = it.next() {
                switch n {
                case "n": out.append("\n")
                case "t": out.append("\t")
                case "\\": out.append("\\")
                default: out.append(n)
                }
            } else {
                out.append(c)
            }
        }
        return out
    }

    private static func placeholderDigits(_ t: String) -> [String] {
        let ns = t as NSString
        return placeholder.matches(in: t, range: NSRange(location: 0, length: ns.length)).map { ns.substring(with: $0.range(at: 1)) }
    }

    private static func stripped(_ t: String) -> String {
        placeholder.stringByReplacingMatches(in: t, range: NSRange(location: 0, length: (t as NSString).length), withTemplate: "")
    }

    private static func toRegex(_ t: String) -> NSRegularExpression? {
        let ns = t as NSString
        var pattern = "\\A"
        var last = 0
        for m in placeholder.matches(in: t, range: NSRange(location: 0, length: ns.length)) {
            pattern += NSRegularExpression.escapedPattern(for: ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            pattern += "(?<p" + ns.substring(with: m.range(at: 1)) + ">.+?)"
            last = m.range.location + m.range.length
        }
        pattern += NSRegularExpression.escapedPattern(for: ns.substring(from: last)) + "\\z"
        // A placeholder used twice gives the same group name twice: such a template is left out, as on Android.
        return try? NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators])
    }

    /** Translate a text that is about to be shown. */
    static func tr(_ s: String) -> String { Brands.apply(trRaw(s)) }

    private static func trRaw(_ s: String) -> String {
        lock.lock()
        let l = current
        if l == "en" || s.allSatisfy({ $0.isWhitespace }) { lock.unlock(); return s }
        if let hit = cache[s] { lock.unlock(); return hit }
        let ex = exact
        let tps = templates
        lock.unlock()
        guard let col = columns.firstIndex(of: l) else { return s }
        let out = translate(s, col, 0, ex, tps)
        lock.lock()
        if cache.count > 3000 { cache.removeAll() }
        cache[s] = out
        lock.unlock()
        return out
    }

    private static func isJavaLetter(_ u: Unicode.Scalar) -> Bool {
        if u.value > 0xFFFF { return false }
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    private static func translate(_ s: String, _ col: Int, _ depth: Int, _ ex: [String: [String]], _ tps: [Template]) -> String {
        if let t = ex[s]?[col], !t.isEmpty { return t }
        if depth > 3 { return s }
        // Several templates can fit ("Updated {0}" and "{0} min ago" both fit "Updated 3 min ago"):
        // pick the one with the most fixed text whose values translate cleanly.
        var best: String? = nil
        var bestScore = Int.min
        let ns = s as NSString
        let whole = NSRange(location: 0, length: ns.length)
        for tp in tps {
            guard let m = tp.regex.firstMatch(in: s, options: [], range: whole) else { continue }
            var out = tp.translations[col]
            if out.isEmpty { continue }
            var penalty = 0
            for n in tp.placeholders {
                let r = m.range(withName: "p" + n)
                if r.location == NSNotFound { continue }
                let v = ns.substring(with: r)
                let t = translate(v, col, depth + 1, ex, tps)
                let letters = v.unicodeScalars.filter { isJavaLetter($0) }.count
                penalty += letters + (t == v ? letters * 30 : 0)
                out = out.replacingOccurrences(of: "{" + n + "}", with: t)
            }
            let score = tp.fixed * 10 - penalty
            if score > bestScore { bestScore = score; best = out }
        }
        if let best = best { return best }
        // Lists joined with " · " or lines: translate each part.
        for sep in ["\n", " · ", " — "] where s.contains(sep) {
            let parts = s.components(separatedBy: sep)
            let t = parts.map { translate($0, col, depth + 1, ex, tps) }
            if t != parts { return t.joined(separator: sep) }
        }
        return s
    }
}

/** Short alias, as in the Android code. */
func tr(_ s: String) -> String { L10n.tr(s) }
