import Foundation

// JSON as the Android app sees it.
//
// LionFlow's logic was written against Android's `org.json`, whose `opt…` calls never fail and coerce in their own
// way: the text "12" reads as a number, a JSON null reads as the text "null", a missing number is NaN. These types
// behave the same, so code ported from the Android app means the same thing here. Keys keep the order they came in.

enum JSONValue: Equatable {
    case string(String)
    case int(Int64)
    case double(Double)
    case bool(Bool)
    case object(JSONObject)
    case array(JSONArray)
    case null

    /** What Java's `String.valueOf` gives for the value: how Android turns any JSON value into text. */
    var javaString: String {
        switch self {
        case .string(let s): return s
        case .int(let i): return String(i)
        case .double(let d): return JSONValue.javaDouble(d)
        case .bool(let b): return b ? "true" : "false"
        case .object(let o): return o.toString()
        case .array(let a): return a.toString()
        case .null: return "null"
        }
    }

    var isNull: Bool { if case .null = self { return true }; return false }

    var asDouble: Double? {
        switch self {
        case .double(let d): return d
        case .int(let i): return Double(i)
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces))
        default: return nil
        }
    }

    var asLong: Int64? {
        switch self {
        case .int(let i): return i
        case .double(let d): return JSONValue.javaLong(d)
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces)).map { JSONValue.javaLong($0) }
        default: return nil
        }
    }

    /** A Java `int`: 32 bits, so a larger number wraps the way it does on Android. */
    var asInt: Int? {
        switch self {
        case .int(let i): return Int(Int32(truncatingIfNeeded: i))
        case .double(let d): return JSONValue.javaInt(d)
        case .string(let s): return Double(s.trimmingCharacters(in: .whitespaces)).map { JSONValue.javaInt($0) }
        default: return nil
        }
    }

    var asBool: Bool? {
        switch self {
        case .bool(let b): return b
        case .string(let s):
            let l = s.lowercased()
            return l == "true" ? true : (l == "false" ? false : nil)
        default: return nil
        }
    }

    /** Java's `(long) d`: NaN is 0, and values outside the range stop at the ends. */
    static func javaLong(_ d: Double) -> Int64 {
        if d.isNaN { return 0 }
        if d >= 9.223372036854775807e18 { return Int64.max }
        if d <= -9.223372036854775808e18 { return Int64.min }
        return Int64(d)
    }

    static func javaInt(_ d: Double) -> Int {
        if d.isNaN { return 0 }
        if d >= 2147483647 { return Int(Int32.max) }
        if d <= -2147483648 { return Int(Int32.min) }
        return Int(d)
    }

    /** Java's `Double.toString`: "12.0", "0.5", and "1.0E7" style from ten million up or below a thousandth. */
    static func javaDouble(_ d: Double) -> String {
        if d.isNaN { return "NaN" }
        if d.isInfinite { return d < 0 ? "-Infinity" : "Infinity" }
        if d == 0 { return d.sign == .minus ? "-0.0" : "0.0" }
        let a = abs(d)
        if a >= 1e-3 && a < 1e7 {
            let s = "\(d)"
            // Swift already writes the shortest text that reads back as the same number, as Java does.
            return s.contains("e") ? plainDecimal(d) : s
        }
        // Scientific: digits from the shortest text, written as d.dddE±x.
        var digits = ""
        var exp = 0
        let s = "\(a)".lowercased()
        if let e = s.firstIndex(of: "e") {
            digits = String(s[s.startIndex..<e]).replacingOccurrences(of: ".", with: "")
            exp = Int(s[s.index(after: e)...].replacingOccurrences(of: "+", with: "")) ?? 0
        } else {
            let parts = s.split(separator: ".", omittingEmptySubsequences: false)
            let whole = String(parts[0])
            let frac = parts.count > 1 ? String(parts[1]) : ""
            if whole != "0" {
                digits = whole + frac
                exp = whole.count - 1
            } else {
                let stripped = frac.drop(while: { $0 == "0" })
                digits = String(stripped)
                exp = -(frac.count - stripped.count + 1)
            }
        }
        while digits.count > 1 && digits.hasSuffix("0") { digits.removeLast() }
        let head = String(digits.prefix(1))
        let tail = String(digits.dropFirst())
        return (d < 0 ? "-" : "") + head + "." + (tail.isEmpty ? "0" : tail) + "E" + String(exp)
    }

    private static func plainDecimal(_ d: Double) -> String {
        let f = NumberFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.numberStyle = .decimal
        f.usesGroupingSeparator = false
        f.minimumFractionDigits = 1
        f.maximumFractionDigits = 17
        return f.string(from: NSNumber(value: d)) ?? "\(d)"
    }

    /** How Android writes a number into JSON text: 12.0 becomes 12. */
    static func jsonNumber(_ d: Double) -> String {
        if d == 0 && d.sign == .minus { return "-0" }
        if d.isFinite && d == Double(javaLong(d)) { return String(javaLong(d)) }
        return javaDouble(d)
    }
}

struct JSONError: Error, CustomStringConvertible {
    let message: String
    init(_ message: String) { self.message = message }
    var description: String { message }
}

/** Anything that can be put into a JSON object or array. */
protocol JSONConvertible { var jsonValue: JSONValue { get } }
extension String: JSONConvertible { var jsonValue: JSONValue { .string(self) } }
extension Int: JSONConvertible { var jsonValue: JSONValue { .int(Int64(self)) } }
extension Int64: JSONConvertible { var jsonValue: JSONValue { .int(self) } }
extension Double: JSONConvertible { var jsonValue: JSONValue { .double(self) } }
extension Bool: JSONConvertible { var jsonValue: JSONValue { .bool(self) } }
extension JSONValue: JSONConvertible { var jsonValue: JSONValue { self } }
extension JSONObject: JSONConvertible { var jsonValue: JSONValue { .object(self) } }
extension JSONArray: JSONConvertible { var jsonValue: JSONValue { .array(self) } }

final class JSONObject: Equatable, CustomStringConvertible {
    private var order: [String] = []
    private var map: [String: JSONValue] = [:]

    init() {}

    /** Reads JSON text that holds an object. */
    convenience init(_ text: String) throws {
        self.init()
        var parser = JSONParser(text)
        guard case .object(let o) = try parser.document() else { throw JSONError("JSON text is not an object") }
        order = o.order
        map = o.map
    }

    static func == (a: JSONObject, b: JSONObject) -> Bool { a === b || a.map == b.map }

    /** Puts a value, or takes the key away when the value is nil (as Android does). */
    @discardableResult
    func put<T: JSONConvertible>(_ key: String, _ value: T?) -> JSONObject {
        guard let value = value else { remove(key); return self }
        if map[key] == nil { order.append(key) }
        map[key] = value.jsonValue
        return self
    }

    func remove(_ key: String) {
        if map.removeValue(forKey: key) != nil { order.removeAll { $0 == key } }
    }

    func has(_ key: String) -> Bool { map[key] != nil }
    func isNull(_ key: String) -> Bool { map[key]?.isNull ?? true }
    func opt(_ key: String) -> JSONValue? { map[key] }
    func keys() -> [String] { order }
    func length() -> Int { order.count }

    func optString(_ key: String, _ fallback: String = "") -> String { map[key]?.javaString ?? fallback }
    func optDouble(_ key: String, _ fallback: Double = .nan) -> Double { map[key]?.asDouble ?? fallback }
    func optInt(_ key: String, _ fallback: Int = 0) -> Int { map[key]?.asInt ?? fallback }
    func optLong(_ key: String, _ fallback: Int64 = 0) -> Int64 { map[key]?.asLong ?? fallback }
    func optBoolean(_ key: String, _ fallback: Bool = false) -> Bool { map[key]?.asBool ?? fallback }
    func optJSONObject(_ key: String) -> JSONObject? { if case .object(let o)? = map[key] { return o }; return nil }
    func optJSONArray(_ key: String) -> JSONArray? { if case .array(let a)? = map[key] { return a }; return nil }

    func getString(_ key: String) throws -> String {
        guard let v = map[key], !v.isNull else { throw JSONError("No value for \(key)") }
        return v.javaString
    }
    func getJSONObject(_ key: String) throws -> JSONObject {
        guard let o = optJSONObject(key) else { throw JSONError("No object for \(key)") }
        return o
    }
    func getJSONArray(_ key: String) throws -> JSONArray {
        guard let a = optJSONArray(key) else { throw JSONError("No array for \(key)") }
        return a
    }

    // The app's own short helpers (the same ones the Android code has).

    /** The object at the end of a path of keys, or nil when any step is missing. */
    func obj(_ path: String...) -> JSONObject? {
        var cur: JSONObject? = self
        for p in path { cur = cur?.optJSONObject(p) }
        return cur
    }

    /** Text for a key that is there and not null. */
    func str(_ key: String) -> String? { has(key) && !isNull(key) ? optString(key) : nil }

    /** A number for a key that is there, not null, and reads as a number. */
    func num(_ key: String) -> Double? {
        guard has(key), !isNull(key) else { return nil }
        let d = optDouble(key)
        return d.isNaN ? nil : d
    }

    func toString() -> String { var out = ""; JSONWriter.write(.object(self), into: &out, indent: 0, level: 0); return out }
    func toString(_ indent: Int) -> String { var out = ""; JSONWriter.write(.object(self), into: &out, indent: indent, level: 0); return out }
    var description: String { toString() }
}

final class JSONArray: Equatable, CustomStringConvertible {
    private(set) var items: [JSONValue] = []

    init() {}

    convenience init(_ text: String) throws {
        self.init()
        var parser = JSONParser(text)
        guard case .array(let a) = try parser.document() else { throw JSONError("JSON text is not an array") }
        items = a.items
    }

    convenience init<T: JSONConvertible>(_ values: [T]) {
        self.init()
        items = values.map { $0.jsonValue }
    }

    static func == (a: JSONArray, b: JSONArray) -> Bool { a === b || a.items == b.items }

    @discardableResult
    func put<T: JSONConvertible>(_ value: T) -> JSONArray { items.append(value.jsonValue); return self }

    func length() -> Int { items.count }
    func opt(_ i: Int) -> JSONValue? { i >= 0 && i < items.count ? items[i] : nil }
    func isNull(_ i: Int) -> Bool { opt(i)?.isNull ?? true }
    func optString(_ i: Int, _ fallback: String = "") -> String { opt(i)?.javaString ?? fallback }
    func optDouble(_ i: Int, _ fallback: Double = .nan) -> Double { opt(i)?.asDouble ?? fallback }
    func optInt(_ i: Int, _ fallback: Int = 0) -> Int { opt(i)?.asInt ?? fallback }
    func optLong(_ i: Int, _ fallback: Int64 = 0) -> Int64 { opt(i)?.asLong ?? fallback }
    func optBoolean(_ i: Int, _ fallback: Bool = false) -> Bool { opt(i)?.asBool ?? fallback }
    func optJSONObject(_ i: Int) -> JSONObject? { if case .object(let o)? = opt(i) { return o }; return nil }
    func optJSONArray(_ i: Int) -> JSONArray? { if case .array(let a)? = opt(i) { return a }; return nil }

    /** The objects in the array, skipping anything else. */
    func objects() -> [JSONObject] { (0..<items.count).compactMap { optJSONObject($0) } }

    func toString() -> String { var out = ""; JSONWriter.write(.array(self), into: &out, indent: 0, level: 0); return out }
    func toString(_ indent: Int) -> String { var out = ""; JSONWriter.write(.array(self), into: &out, indent: indent, level: 0); return out }
    var description: String { toString() }
}

enum JSON {
    /** A server answer as JSON: an object, an array, the plain text when it is neither, or nil when empty. */
    static func parse(_ s: String) -> JSONValue? {
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return nil }
        if t.hasPrefix("[") {
            if let a = try? JSONArray(t) { return JSONValue.array(a) }
            return JSONValue.string(t)
        }
        if t.hasPrefix("{") {
            if let o = try? JSONObject(t) { return JSONValue.object(o) }
            return JSONValue.string(t)
        }
        return JSONValue.string(t)
    }
}

// MARK: - Reading

/** A strict JSON reader that types numbers as Android does: whole numbers stay whole, anything with a point or exponent is a double. */
struct JSONParser {
    private let bytes: [UInt8]
    private var pos = 0

    init(_ text: String) { bytes = Array(text.utf8) }

    mutating func document() throws -> JSONValue {
        let v = try value()
        skipSpace()
        if pos != bytes.count { throw JSONError("Unexpected text after JSON at \(pos)") }
        return v
    }

    private mutating func skipSpace() {
        while pos < bytes.count {
            let c = bytes[pos]
            if c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 { pos += 1 } else { break }
        }
    }

    private mutating func value() throws -> JSONValue {
        skipSpace()
        guard pos < bytes.count else { throw JSONError("Unexpected end of JSON") }
        switch bytes[pos] {
        case UInt8(ascii: "{"): return .object(try object())
        case UInt8(ascii: "["): return .array(try array())
        case UInt8(ascii: "\""): return .string(try string())
        default: return try literal()
        }
    }

    private mutating func object() throws -> JSONObject {
        let o = JSONObject()
        pos += 1
        skipSpace()
        if pos < bytes.count && bytes[pos] == UInt8(ascii: "}") { pos += 1; return o }
        while true {
            skipSpace()
            guard pos < bytes.count, bytes[pos] == UInt8(ascii: "\"") else { throw JSONError("Expected a key at \(pos)") }
            let key = try string()
            skipSpace()
            guard pos < bytes.count, bytes[pos] == UInt8(ascii: ":") else { throw JSONError("Expected ':' at \(pos)") }
            pos += 1
            o.put(key, try value())
            skipSpace()
            guard pos < bytes.count else { throw JSONError("Unterminated object") }
            if bytes[pos] == UInt8(ascii: ",") { pos += 1; continue }
            if bytes[pos] == UInt8(ascii: "}") { pos += 1; return o }
            throw JSONError("Expected ',' or '}' at \(pos)")
        }
    }

    private mutating func array() throws -> JSONArray {
        let a = JSONArray()
        pos += 1
        skipSpace()
        if pos < bytes.count && bytes[pos] == UInt8(ascii: "]") { pos += 1; return a }
        while true {
            a.put(try value())
            skipSpace()
            guard pos < bytes.count else { throw JSONError("Unterminated array") }
            if bytes[pos] == UInt8(ascii: ",") { pos += 1; continue }
            if bytes[pos] == UInt8(ascii: "]") { pos += 1; return a }
            throw JSONError("Expected ',' or ']' at \(pos)")
        }
    }

    private mutating func string() throws -> String {
        pos += 1
        var out = [UInt8]()
        while pos < bytes.count {
            let c = bytes[pos]
            if c == UInt8(ascii: "\"") {
                pos += 1
                return String(decoding: out, as: UTF8.self)
            }
            if c != UInt8(ascii: "\\") { out.append(c); pos += 1; continue }
            pos += 1
            guard pos < bytes.count else { break }
            let e = bytes[pos]
            pos += 1
            switch e {
            case UInt8(ascii: "n"): out.append(0x0A)
            case UInt8(ascii: "t"): out.append(0x09)
            case UInt8(ascii: "r"): out.append(0x0D)
            case UInt8(ascii: "b"): out.append(0x08)
            case UInt8(ascii: "f"): out.append(0x0C)
            case UInt8(ascii: "u"):
                var unit = try hex4()
                // Two \u escapes can make up one character (a surrogate pair).
                if unit >= 0xD800 && unit <= 0xDBFF && pos + 1 < bytes.count && bytes[pos] == UInt8(ascii: "\\") && bytes[pos + 1] == UInt8(ascii: "u") {
                    let save = pos
                    pos += 2
                    let low = try hex4()
                    if low >= 0xDC00 && low <= 0xDFFF { unit = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00) } else { pos = save }
                }
                let scalar = Unicode.Scalar(unit) ?? Unicode.Scalar(0xFFFD)!
                out.append(contentsOf: Array(String(Character(scalar)).utf8))
            default: out.append(e)   // \" \\ \/ and anything else stand for themselves
            }
        }
        throw JSONError("Unterminated string")
    }

    private mutating func hex4() throws -> UInt32 {
        guard pos + 4 <= bytes.count, let v = UInt32(String(decoding: bytes[pos..<pos + 4], as: UTF8.self), radix: 16) else { throw JSONError("Bad \\u escape at \(pos)") }
        pos += 4
        return v
    }

    private mutating func literal() throws -> JSONValue {
        let start = pos
        while pos < bytes.count {
            let c = bytes[pos]
            if c == UInt8(ascii: ",") || c == UInt8(ascii: "}") || c == UInt8(ascii: "]") || c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 { break }
            pos += 1
        }
        let text = String(decoding: bytes[start..<pos], as: UTF8.self)
        if text.isEmpty { throw JSONError("Expected a value at \(start)") }
        switch text.lowercased() {
        case "null": return .null
        case "true": return .bool(true)
        case "false": return .bool(false)
        default: break
        }
        if !text.contains("."), let i = Int64(text) { return .int(i) }
        if let d = Double(text) { return .double(d) }
        throw JSONError("Unknown value '\(text)' at \(start)")
    }
}

// MARK: - Writing

enum JSONWriter {
    static func write(_ v: JSONValue, into out: inout String, indent: Int, level: Int) {
        switch v {
        case .string(let s): quote(s, into: &out)
        case .int(let i): out += String(i)
        case .double(let d): out += JSONValue.jsonNumber(d)
        case .bool(let b): out += b ? "true" : "false"
        case .null: out += "null"
        case .object(let o):
            let keys = o.keys()
            if keys.isEmpty { out += "{}"; return }
            out += "{"
            for (n, k) in keys.enumerated() {
                if n > 0 { out += "," }
                newline(&out, indent, level + 1)
                quote(k, into: &out)
                out += indent > 0 ? ": " : ":"
                write(o.opt(k) ?? .null, into: &out, indent: indent, level: level + 1)
            }
            newline(&out, indent, level)
            out += "}"
        case .array(let a):
            if a.length() == 0 { out += "[]"; return }
            out += "["
            for (n, item) in a.items.enumerated() {
                if n > 0 { out += "," }
                newline(&out, indent, level + 1)
                write(item, into: &out, indent: indent, level: level + 1)
            }
            newline(&out, indent, level)
            out += "]"
        }
    }

    private static func newline(_ out: inout String, _ indent: Int, _ level: Int) {
        if indent > 0 { out += "\n" + String(repeating: " ", count: indent * level) }
    }

    /** Quotes text the way Android does, which also escapes "/". */
    static func quote(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "/": out += "\\/"
            case "\t": out += "\\t"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        out += "\""
    }
}
