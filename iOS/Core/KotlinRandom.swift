import Foundation

/// Kotlin's `Random(seed)` (the "xorwow" generator of its standard library), number for number, so the sample car
/// in demo mode has the very same trips and charging sessions as in the Android app.
struct KotlinRandom {
    private var x: Int32
    private var y: Int32
    private var z: Int32
    private var w: Int32
    private var v: Int32
    private var addend: Int32

    init(_ seed: Int) {
        let s1 = Int32(truncatingIfNeeded: seed)
        let s2 = s1 >> 31
        x = s1
        y = s2
        z = 0
        w = 0
        v = ~s1
        addend = (s1 << 10) ^ Int32(bitPattern: UInt32(bitPattern: s2) >> 4)
        // Kotlin throws away the first 64 numbers.
        for _ in 0..<64 { _ = nextInt() }
    }

    mutating func nextInt() -> Int32 {
        var t = x
        t = t ^ Int32(bitPattern: UInt32(bitPattern: t) >> 2)
        x = y
        y = z
        z = w
        let v0 = v
        w = v0
        t = (t ^ (t << 1)) ^ v0 ^ (v0 << 4)
        v = t
        addend = addend &+ 362437
        return t &+ addend
    }

    private mutating func nextBits(_ bitCount: Int32) -> Int32 {
        if bitCount <= 0 { _ = nextInt(); return 0 }
        return Int32(bitPattern: UInt32(bitPattern: nextInt()) >> UInt32(32 - bitCount))
    }

    /** A whole number from `from` up to, but not including, `until`. */
    mutating func nextInt(_ from: Int, _ until: Int) -> Int {
        let n = Int32(truncatingIfNeeded: until - from)
        precondition(n > 0, "empty range")
        if n & (0 &- n) == n {
            // A power of two: its top bits.
            let bits = Int32(31 - n.leadingZeroBitCount)
            return from + Int(nextBits(bits))
        }
        var value: Int32
        while true {
            let bits = Int32(bitPattern: UInt32(bitPattern: nextInt()) >> 1)
            value = bits % n
            if bits &- value &+ (n &- 1) >= 0 { break }
        }
        return from + Int(value)
    }

    mutating func nextInt(_ until: Int) -> Int { nextInt(0, until) }

    /** A number from 0 up to, but not including, 1. */
    mutating func nextDouble() -> Double {
        let hi = Int64(nextBits(26))
        let low = Int64(nextBits(27))
        return Double((hi << 27) + low) / 9007199254740992.0
    }

    mutating func nextDouble(_ from: Double, _ until: Double) -> Double {
        let r = from + nextDouble() * (until - from)
        return r >= until ? until.nextDown : r
    }
}

/** A small set of named texts kept between launches (Android's SharedPreferences). Tests use the one in memory. */
protocol KeyValueStore: AnyObject {
    func string(_ key: String) -> String?
    func set(_ key: String, _ value: String?)
    func removeAll()
}

final class MemoryStore: KeyValueStore {
    private var map: [String: String] = [:]
    private let lock = NSLock()
    init() {}
    func string(_ key: String) -> String? { lock.lock(); defer { lock.unlock() }; return map[key] }
    func set(_ key: String, _ value: String?) { lock.lock(); map[key] = value; lock.unlock() }
    func removeAll() { lock.lock(); map.removeAll(); lock.unlock() }
}

/** Texts kept in the app's own preferences, under a prefix so each store has its own names. */
final class DefaultsStore: KeyValueStore {
    private let defaults: UserDefaults
    private let prefix: String

    init(_ name: String, _ defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.prefix = name + "."
    }

    func string(_ key: String) -> String? { defaults.string(forKey: prefix + key) }
    func set(_ key: String, _ value: String?) {
        if let v = value { defaults.set(v, forKey: prefix + key) } else { defaults.removeObject(forKey: prefix + key) }
    }
    func removeAll() {
        for k in defaults.dictionaryRepresentation().keys where k.hasPrefix(prefix) { defaults.removeObject(forKey: k) }
    }
}
