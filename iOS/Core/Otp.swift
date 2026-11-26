// SPDX-License-Identifier: GPL-3.0-or-later
import Foundation
import CryptoKit
import CommonCrypto
import Security
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A whole number of any size, with just what the one-time-code generator needs: turning bytes into a number and
/// back, and raising to a power modulo another number. Kept simple on purpose (bit by bit); the numbers are 1024
/// bits and the power is 17, so speed doesn't matter.
struct BigUInt: Equatable {
    /** 32 bits at a time, lowest first, without zeros at the top (zero is empty). */
    private(set) var limbs: [UInt32]

    init(bytes: [UInt8]) {
        var out: [UInt32] = []
        var i = bytes.count
        while i > 0 {
            var limb: UInt32 = 0
            for shift in 0..<4 where i - 1 - shift >= 0 { limb |= UInt32(bytes[i - 1 - shift]) << UInt32(8 * shift) }
            out.append(limb)
            i -= 4
        }
        while let last = out.last, last == 0 { out.removeLast() }
        limbs = out
    }

    init?(hex: String) {
        guard let b = Otp.hexToBytes(hex) else { return nil }
        self.init(bytes: b)
    }

    var isZero: Bool { limbs.isEmpty }

    var bitLength: Int {
        guard let top = limbs.last else { return 0 }
        return limbs.count * 32 - top.leadingZeroBitCount
    }

    private func bit(_ i: Int) -> Bool {
        let limb = i / 32
        return limb < limbs.count && (limbs[limb] >> UInt32(i % 32)) & 1 == 1
    }

    /** The number as bytes, highest first: exactly `count` of them (zeros in front, or the lowest ones when it is longer). */
    func bytes(_ count: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: count)
        for i in 0..<count {
            let limb = i / 4
            if limb < limbs.count { out[count - 1 - i] = UInt8((limbs[limb] >> UInt32(8 * (i % 4))) & 0xff) }
        }
        return out
    }

    // Arithmetic on plain arrays of the same length (lowest first).

    private static func atLeast(_ a: [UInt32], _ b: [UInt32]) -> Bool {
        var i = a.count - 1
        while i >= 0 {
            if a[i] != b[i] { return a[i] > b[i] }
            i -= 1
        }
        return true
    }

    private static func subtract(_ a: inout [UInt32], _ b: [UInt32]) {
        var borrow: UInt64 = 0
        for i in 0..<a.count {
            let x = UInt64(a[i])
            let y = UInt64(b[i]) + borrow
            if x >= y {
                a[i] = UInt32(x - y)
                borrow = 0
            } else {
                a[i] = UInt32(x + 0x1_0000_0000 - y)
                borrow = 1
            }
        }
    }

    private static func add(_ a: inout [UInt32], _ b: [UInt32]) {
        var carry: UInt64 = 0
        for i in 0..<a.count {
            let sum = UInt64(a[i]) + UInt64(b[i]) + carry
            a[i] = UInt32(sum & 0xffff_ffff)
            carry = sum >> 32
        }
    }

    private static func double(_ a: inout [UInt32]) {
        var carry: UInt32 = 0
        for i in 0..<a.count {
            let next = a[i] >> 31
            a[i] = (a[i] << 1) | carry
            carry = next
        }
    }

    private func padded(_ count: Int) -> [UInt32] { limbs + [UInt32](repeating: 0, count: max(0, count - limbs.count)) }

    private static func trimmed(_ a: [UInt32]) -> BigUInt {
        var out = a
        while let last = out.last, last == 0 { out.removeLast() }
        var r = BigUInt(bytes: [])
        r.limbs = out
        return r
    }

    /** The remainder when divided by `m`. */
    func mod(_ m: BigUInt) -> BigUInt {
        precondition(!m.isZero, "division by zero")
        let n = m.limbs.count + 1
        let mm = m.padded(n)
        var r = [UInt32](repeating: 0, count: n)
        var i = bitLength - 1
        while i >= 0 {
            BigUInt.double(&r)
            if bit(i) { r[0] |= 1 }
            if BigUInt.atLeast(r, mm) { BigUInt.subtract(&r, mm) }
            i -= 1
        }
        return BigUInt.trimmed(r)
    }

    /** (self × other) mod m, for self and other below m. */
    func multiplied(by other: BigUInt, mod m: BigUInt) -> BigUInt {
        let n = m.limbs.count + 1
        let mm = m.padded(n)
        let a = padded(n)
        var r = [UInt32](repeating: 0, count: n)
        var i = other.bitLength - 1
        while i >= 0 {
            BigUInt.double(&r)
            if BigUInt.atLeast(r, mm) { BigUInt.subtract(&r, mm) }
            if other.bit(i) {
                BigUInt.add(&r, a)
                if BigUInt.atLeast(r, mm) { BigUInt.subtract(&r, mm) }
            }
            i -= 1
        }
        return BigUInt.trimmed(r)
    }

    /** self to the power of `exponent`, modulo `m`. */
    func power(_ exponent: UInt, mod m: BigUInt) -> BigUInt {
        var result = BigUInt(bytes: [1]).mod(m)
        var base = mod(m)
        var e = exponent
        while e > 0 {
            if e & 1 == 1 { result = result.multiplied(by: base, mod: m) }
            e >>= 1
            if e > 0 { base = base.multiplied(by: base, mod: m) }
        }
        return result
    }
}

/// Swift port of the inWebo one-time-code generator the carmakers' apps use for remote commands. It follows the
/// Android app's Otp.kt line by line, which was ported from psa_car_controller (GPL-3.0, Florian Bezannier) via
/// homeassistant-stellantis-vehicles.
///
/// Activation (once): SMS code + the security code (PIN) of the carmaker's app. After that it produces one-time
/// passwords that are traded for a remote-control token. The whole state, including the PIN, is kept in the Keychain.
final class Otp {
    struct OtpError: Error, LocalizedError, Equatable {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }

    static let ok = 0
    static let otpTwice = 10
    static let activateMode = "activate"
    static let otpMode = "otp"
    static let msMode = "ms"
    static let MACID = "bb8e981582b0f31353108fb020bead1c"
    private static let host = "https://otp.mpsa.com"
    private static let base36 = Array("abcdefghijklmnopqrstuvwxyz0123456789")

    /** Where random bytes come from: the system's secure generator. Tests put fixed ones here. */
    static var random: (Int) -> [UInt8] = { count in
        var b = [UInt8](repeating: 0, count: count)
        if count > 0 { _ = SecRandomCopyBytes(kSecRandomDefault, count, &b) }
        return b
    }

    /** Sends one request to the code server and gives back its answer (XML). Tests put a stand-in server here. */
    static var transport: ([(String, String)]) async throws -> String = { params in
        // The same address the Android app builds: values percent-encoded, a space as %20.
        let query = params.map { urlEncode($0.0).replacingOccurrences(of: "+", with: "%20") + "=" + urlEncode($0.1).replacingOccurrences(of: "+", with: "%20") }.joined(separator: "&")
        let req = try Http.request("\(Otp.host)/iwws/MAC?\(query)", headers: [
            ("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 8.0.0; Android SDK built for x86_64 Build/OSR1.180418.004)"),
        ])
        return try await Http.text(req)
    }

    let macid: String
    var deviceId: String
    var iwalea: String
    var kiw: String? = nil
    var kfact: String? = nil
    var codepin = ""
    var smsCode = ""
    var challenge = ""
    var action = ""
    var mode = Otp.activateMode
    var defi = "0"

    // The fields of the generator's state that the protocol uses.
    var iwid = ""
    var iwTsync = "0"
    var iwK0 = ""
    var iwK1 = ""
    var iwsecid = ""
    var iwsecval = ""

    init(_ macid: String, _ deviceId: String) {
        self.macid = macid
        self.deviceId = deviceId
        self.iwalea = Otp.randomHex(16)
    }

    func serial() -> String { "\(deviceId)/_/\(iwalea)" }

    func kma(_ pin: String) -> String { String(Otp.sha256Hex("\(pin);\(serial())").prefix(32)) }

    func getR() -> [(String, String)] {
        let iw = iwK0
        let r2 = action == "synchro" ? "\(challenge);\(iw);\(codepin)" : "\(challenge);\(iw);"
        let r0 = "\(challenge);\(iw);\(serial())"
        let r1 = "\(challenge);\(iw);\(iwK1)"
        return [("R0", Otp.sha256Hex(r0)), ("R1", Otp.sha256Hex(r1)), ("R2", Otp.sha256Hex(r2))]
    }

    private func request(_ params: [(String, String)], setup: Bool) async throws -> [String: String] {
        let raw = try await Otp.transport(params)
        guard let out = Otp.parseXml(raw, setup ? "ActionSetup" : "ActionFinalize") else { throw OtpError("Bad response from OTP server") }
        return out
    }

    /** Step 1 of activation, or step 1 of producing a code. */
    @discardableResult
    func activationStart() async throws -> Bool {
        var p: [(String, String)] = [
            ("action", "ActionSetup"), ("mode", mode), ("id", iwid), ("lastsync", iwTsync),
            ("version", "Generator-1.0/0.2.11"), ("macid", macid),
        ]
        if mode == Otp.otpMode { p.append(("sid", iwsecid)) } else if mode == Otp.activateMode { p.append(("code", smsCode)) }
        let xml = try await request(p, setup: true)
        if xml["err"] == "OK" {
            if mode == Otp.activateMode {
                guard let f = xml["Kfact"] else { throw OtpError("Missing Kfact") }
                guard let wrapped = xml["Kiw"] else { throw OtpError("Missing Kiw") }
                kfact = f
                kiw = try Otp.decodeOaep(wrapped, f)
            } else if mode == Otp.otpMode {
                challenge = xml["challenge"] ?? ""
            }
            return true
        }
        throw OtpError("OTP setup refused: \(xml["err"] ?? "null")")
    }

    /** Returns `ok`, `otpTwice`, or throws. */
    func activationFinalize() async throws -> Int {
        let r = getR()
        var p: [(String, String)] = [
            ("action", "ActionFinalize"), ("mode", mode), ("id", iwid), ("lastsync", iwTsync),
            ("version", "Generator-1.0/0.2.11"), ("lang", "fr"), ("ack", ""), ("macid", macid),
        ]
        if mode == Otp.otpMode {
            p.append(("keytype", "0"))
            p.append(("sid", iwsecid))
        } else if mode == Otp.activateMode {
            guard let modulus = kiw else { throw OtpError("Not initialised") }
            guard let kmaBytes = Otp.hexToBytes(kma(codepin)) else { throw OtpError("Not initialised") }
            let kmaCrypt = Otp.toHex(try Otp.oaepEncrypt(kmaBytes, modulus))
            let pinCrypt = Otp.toHex(try Otp.oaepEncrypt(Array(codepin.utf8), modulus))
            p.append(("serial", serial()))
            p.append(("code", smsCode))
            p.append(("Kma", kmaCrypt))
            p.append(("pin", pinCrypt))
            p.append(("name", "Android SDK built for x86_64 / UNKNOWN"))
        }
        p.append(contentsOf: r)
        let xml = try await request(p, setup: false)
        if xml["err"] != "OK" { throw OtpError("OTP error: \(xml["err"] ?? "null")") }
        try synchro(xml, kma(codepin))

        if mode == Otp.otpMode {
            guard let d = xml["defi"] else { throw OtpError("Missing defi in OTP response") }
            defi = d
            return xml["J"] != nil ? Otp.otpTwice : Otp.ok
        }

        guard let msN = xml["ms_n"], let msKey = xml["ms_key"] else { return Otp.ok }
        if (Int(msN) ?? 0) > 1 { throw OtpError("Unsupported ms_n=\(msN)") }

        challenge = xml["challenge"] ?? ""
        action = "synchro"
        guard let f = kfact else { throw OtpError("Not initialised") }
        let tempModulus = try Otp.decodeOaep(msKey, f)
        let random = Otp.random(16)
        let kpubEncode = try Otp.oaepEncrypt(random, tempModulus)
        iwsecval = Otp.toHex(try Otp.aes(kma(codepin), random, encrypt: true))
        iwsecid = xml["s_id"] ?? ""

        var p2: [(String, String)] = [
            ("action", "ActionFinalize"), ("mode", Otp.msMode),
            ("ms_id0", xml["ms_id"] ?? ""), ("ms_val0", Otp.toHex(kpubEncode)), ("macid", macid),
            ("id", iwid), ("lastsync", iwTsync), ("ms_n", "1"),
        ]
        p2.append(contentsOf: getR())
        let xml2 = try await request(p2, setup: false)
        try synchro(xml2, kma(codepin))
        return Otp.ok
    }

    private func synchro(_ x: [String: String], _ key: String) throws {
        if let v = x["id"], !v.isEmpty { iwid = v }
        if let v = x["K0"], !v.isEmpty { iwK0 = Otp.toHex(try Otp.aes(key, try Otp.bytes(v), encrypt: false)) }
        if let v = x["K1"], !v.isEmpty { iwK1 = Otp.toHex(try Otp.aes(key, try Otp.bytes(v), encrypt: false)) }
        if let v = x["dK1"], !v.isEmpty { iwK1 = String(Otp.sha256Hex("\(iwK1);\(v)").prefix(32)) }
        if let n = x["s_n"], !n.isEmpty { iwTsync = x["Tsync"] ?? iwTsync }
    }

    func computeOtp() -> String {
        let res = Otp.sha256(Array("\(iwK1):\(defi):\(iwsecval)".utf8))
        func word(_ at: Int) -> UInt64 { (UInt64(res[at]) << 24) | (UInt64(res[at + 1]) << 16) | (UInt64(res[at + 2]) << 8) | UInt64(res[at + 3]) }
        let a = word(0) & 0xfffffff
        let b = word(4) & 1023
        var n = a * 1024 + b
        if n == 0 { return "a" }
        var out = ""
        while n > 0 {
            out.append(Otp.base36[Int(n % 36)])
            n /= 36
        }
        return out
    }

    /** Activate with the SMS code and the PIN. Throws on failure. */
    func activate(_ sms: String, _ pin: String) async throws {
        smsCode = sms
        codepin = pin
        mode = Otp.activateMode
        if try await activationStart() { _ = try await activationFinalize() }
    }

    /** Produce a one-time password. Limited by the carmaker to roughly 6 per day. */
    func nextCode() async throws -> String {
        mode = Otp.otpMode
        if !(try await activationStart()) { throw OtpError("Can't start OTP") }
        var res = try await activationFinalize()
        if res == Otp.otpTwice {
            mode = Otp.otpMode
            try await activationStart()
            res = try await activationFinalize()
            if res != Otp.ok { throw OtpError("Can't get OTP code") }
        }
        return computeOtp()
    }

    func toJson() -> String {
        let j = JSONObject()
        j.put("deviceId", deviceId)
        j.put("iwalea", iwalea)
        j.put("kiw", kiw ?? "")
        j.put("kfact", kfact ?? "")
        j.put("codepin", codepin)
        j.put("action", action)
        j.put("defi", defi)
        j.put("iwid", iwid)
        j.put("iwTsync", iwTsync)
        j.put("iwK0", iwK0)
        j.put("iwK1", iwK1)
        j.put("iwsecid", iwsecid)
        j.put("iwsecval", iwsecval)
        return j.toString()
    }

    static func fromJson(_ json: String) throws -> Otp {
        let j = try JSONObject(json)
        let o = Otp(MACID, try j.getString("deviceId"))
        o.iwalea = try j.getString("iwalea")
        let kiw = j.optString("kiw")
        let kfact = j.optString("kfact")
        o.kiw = kiw.isEmpty ? nil : kiw
        o.kfact = kfact.isEmpty ? nil : kfact
        o.codepin = j.optString("codepin")
        o.action = j.optString("action")
        o.defi = j.optString("defi", "0")
        o.iwid = j.optString("iwid")
        o.iwTsync = j.optString("iwTsync", "0")
        o.iwK0 = j.optString("iwK0")
        o.iwK1 = j.optString("iwK1")
        o.iwsecid = j.optString("iwsecid")
        o.iwsecval = j.optString("iwsecval")
        return o
    }

    // MARK: Crypto helpers

    static func sha256(_ bytes: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: Data(bytes))) }
    static func sha256Hex(_ s: String) -> String { toHex(sha256(Array(s.utf8))) }
    static func randomHex(_ bytes: Int) -> String { toHex(random(bytes)) }

    static func toHex(_ bytes: [UInt8]) -> String {
        let digits = Array("0123456789abcdef")
        var out = ""
        out.reserveCapacity(bytes.count * 2)
        for b in bytes {
            out.append(digits[Int(b >> 4)])
            out.append(digits[Int(b & 15)])
        }
        return out
    }

    /** Bytes from hex text (an odd number of digits gets a zero in front). Nil when it isn't hex. */
    static func hexToBytes(_ hex: String) -> [UInt8]? {
        let clean = Array((hex.utf8.count % 2 == 1 ? "0" + hex : hex).utf8)
        var out: [UInt8] = []
        out.reserveCapacity(clean.count / 2)
        func value(_ c: UInt8) -> UInt8? {
            switch c {
            case 48...57: return c - 48
            case 97...102: return c - 87
            case 65...70: return c - 55
            default: return nil
            }
        }
        var i = 0
        while i < clean.count {
            guard let hi = value(clean[i]), let lo = value(clean[i + 1]) else { return nil }
            out.append(hi << 4 | lo)
            i += 2
        }
        return out
    }

    private static func bytes(_ hex: String) throws -> [UInt8] {
        guard let b = hexToBytes(hex) else { throw OtpError("Bad response from OTP server") }
        return b
    }

    /** AES with a 128-bit key given as hex, one block after another, nothing added (Java's "AES/ECB/NoPadding"). */
    static func aes(_ keyHex: String, _ data: [UInt8], encrypt: Bool) throws -> [UInt8] {
        let key = try bytes(keyHex)
        guard [16, 24, 32].contains(key.count), data.count % 16 == 0 else { throw OtpError("Bad response from OTP server") }
        if data.isEmpty { return [] }
        let capacity = data.count + kCCBlockSizeAES128
        var out = [UInt8](repeating: 0, count: capacity)
        var moved = 0
        let status = CCCrypt(CCOperation(encrypt ? kCCEncrypt : kCCDecrypt), CCAlgorithm(kCCAlgorithmAES), CCOptions(kCCOptionECBMode),
                             key, key.count, nil, data, data.count, &out, capacity, &moved)
        guard status == CCCryptorStatus(kCCSuccess) else { throw OtpError("Bad response from OTP server") }
        return Array(out.prefix(moved))
    }

    private static func mgf1(_ seed: [UInt8], _ length: Int) -> [UInt8] {
        var out: [UInt8] = []
        var counter: UInt32 = 0
        while out.count < length {
            out += sha256(seed + [UInt8(counter >> 24), UInt8((counter >> 16) & 0xff), UInt8((counter >> 8) & 0xff), UInt8(counter & 0xff)])
            counter += 1
        }
        return Array(out.prefix(length))
    }

    private static func xor(_ a: [UInt8], _ b: [UInt8]) -> [UInt8] {
        var out = a
        for i in 0..<a.count { out[i] = a[i] ^ b[i] }
        return out
    }

    /** RSA-OAEP (SHA-256, MGF1-SHA-256, empty label) encryption with public exponent 0x11. */
    static func oaepEncrypt(_ message: [UInt8], _ modulusHex: String) throws -> [UInt8] {
        guard let n = BigUInt(hex: modulusHex), !n.isZero else { throw OtpError("Not initialised") }
        let k = (n.bitLength + 7) / 8
        let hLen = 32
        guard k - message.count - 2 * hLen - 2 >= 0 else { throw OtpError("Not initialised") }
        let lHash = sha256([])
        let ps = [UInt8](repeating: 0, count: k - message.count - 2 * hLen - 2)
        let db = lHash + ps + [1] + message
        let seed = random(hLen)
        let maskedDb = xor(db, mgf1(seed, k - hLen - 1))
        let maskedSeed = xor(seed, mgf1(maskedDb, hLen))
        let em = [0] + maskedSeed + maskedDb
        return BigUInt(bytes: em).power(0x11, mod: n).bytes(k)
    }

    /** The vendor's "decrypt with the public key" OAEP unwrap, block by block (128-byte blocks). Gives hex. */
    static func decodeOaep(_ encHex: String, _ modulusHex: String) throws -> String {
        guard let n = BigUInt(hex: modulusHex), !n.isZero else { throw OtpError("Incorrect decryption") }
        let k = (n.bitLength + 7) / 8
        let hLen = 32
        let lHash = sha256([])
        let enc = try bytes(encHex)
        var out = ""
        var off = 0
        while off < enc.count {
            let end = min(off + 128, enc.count)
            let em = BigUInt(bytes: Array(enc[off..<end])).power(0x11, mod: n).bytes(k)
            guard em.count > 2 * hLen + 1 else { throw OtpError("Incorrect decryption") }
            let maskedSeed = Array(em[1...hLen])
            let maskedDb = Array(em[(hLen + 1)...])
            let seed = xor(maskedSeed, mgf1(maskedDb, hLen))
            let db = xor(maskedDb, mgf1(seed, k - hLen - 1))
            if Array(db[0..<hLen]) != lHash { throw OtpError("Incorrect decryption") }
            var onePos = -1
            var i = hLen
            while i < db.count {
                if db[i] == 1 { onePos = i; break }
                if db[i] != 0 { break }
                i += 1
            }
            if onePos < 0 { throw OtpError("Incorrect decryption") }
            out += toHex(Array(db[(onePos + 1)...]))
            off = end
        }
        return out
    }

    // MARK: The server's answers

    /** The children of the given root element as tag → text (the first of each tag wins). Nil when there are none. */
    static func parseXml(_ raw: String, _ rootTag: String) -> [String: String]? {
        var text = raw
        if let r = raw.range(of: "?>") { text = String(raw[r.upperBound...]) }
        let reader = XmlChildren(rootTag)
        let parser = XMLParser(data: Data(text.utf8))
        parser.delegate = reader
        parser.parse()
        return reader.out.isEmpty ? nil : reader.out
    }
}

/** Reads the texts of the elements right under the root element, when the root has the wanted name. */
private final class XmlChildren: NSObject, XMLParserDelegate {
    private let rootTag: String
    private var depth = 0
    private var inRoot = false
    private var current: String?
    private var buffer = ""
    var out: [String: String] = [:]

    init(_ rootTag: String) { self.rootTag = rootTag }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?, qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        depth += 1
        if depth == 1 {
            inRoot = elementName == rootTag
        } else if depth == 2 && inRoot {
            current = elementName
            buffer = ""
            if out[elementName] == nil { out[elementName] = "" }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inRoot && depth == 2 && current != nil { buffer += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        if depth == 2, let tag = current {
            let t = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
            if !t.isEmpty && (out[tag] ?? "").isEmpty { out[tag] = t }
            current = nil
        }
        depth -= 1
    }
}
