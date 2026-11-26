import XCTest
@testable import LionFlowCore

/// The one-time codes behind remote control. The Android app's own generator was played through a whole activation
/// and several codes against a stand-in for the carmaker's code server, with fixed "random" bytes, and everything it
/// sent and worked out was written down (Fixtures/otp_expected.json, by iOS/oracle/Oracle3.kt). Here the Swift
/// generator gets the same answers and the same "random" bytes, and has to send and work out exactly the same.
final class OtpTests: ParityTestCase {
    private var state: Int32 = 0

    /** The same plain number generator as the fixture maker's (nothing here is secret). */
    private func fixedBytes(_ count: Int) -> [UInt8] {
        var out: [UInt8] = []
        for _ in 0..<count {
            state = (state &* 1103515245 &+ 12345) & 0x7fffffff
            out.append(UInt8(truncatingIfNeeded: state >> 16))
        }
        return out
    }

    private var savedRandom: ((Int) -> [UInt8])?
    private var savedTransport: (([(String, String)]) async throws -> String)?

    override func setUp() {
        super.setUp()
        savedRandom = Otp.random
        savedTransport = Otp.transport
        Otp.random = { [unowned self] n in self.fixedBytes(n) }
    }

    override func tearDown() {
        if let r = savedRandom { Otp.random = r }
        if let t = savedTransport { Otp.transport = t }
        super.tearDown()
    }

    private func pairs(_ any: Any?) -> [(String, String)] {
        ((any as? [[String]]) ?? []).map { ($0[0], $0[1]) }
    }

    private func xml(_ root: String, _ answer: [(String, String)]) -> String {
        func escaped(_ s: String) -> String { s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;") }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<\(root)>\n" + answer.map { "  <\($0.0)>\(escaped($0.1))</\($0.0)>\n" }.joined() + "</\(root)>\n"
    }

    func testAWholeActivationAndCodesGoExactlyAsOnAndroid() async throws {
        let w = try XCTUnwrap(expected("otp_expected.json") as? [String: Any])
        let pin = try XCTUnwrap(w["pin"] as? String)
        let sms = try XCTUnwrap(w["sms"] as? String)
        let deviceId = try XCTUnwrap(w["deviceId"] as? String)
        let steps = try XCTUnwrap(w["steps"] as? [[String: Any]])
        XCTAssertEqual(steps.count, 6)

        // The stand-in server: checks what it is sent against what Android sent, and answers what Android was answered.
        var queue: [[String: Any]] = []
        var problems: [String] = []
        Otp.transport = { [unowned self] params in
            guard !queue.isEmpty else {
                problems.append("a request Android never sent: \(params.map { $0.0 + "=" + $0.1 }.joined(separator: "&"))")
                throw ApiException(0, "no more answers")
            }
            let call = queue.removeFirst()
            let want = self.pairs(call["params"])
            let setup = (call["setup"] as? Bool) ?? false
            if params.map({ $0.0 }) != want.map({ $0.0 }) {
                problems.append("names sent: iPhone \(params.map { $0.0 }) Android \(want.map { $0.0 })")
            } else {
                for (i, p) in params.enumerated() where p.1 != want[i].1 { problems.append("\(p.0): iPhone «\(p.1)» Android «\(want[i].1)»") }
            }
            let action = params.first { $0.0 == "action" }?.1 ?? ""
            if action != (setup ? "ActionSetup" : "ActionFinalize") { problems.append("wrong kind of request: \(action)") }
            return self.xml(setup ? "ActionSetup" : "ActionFinalize", self.pairs(call["answer"]))
        }
        func run(_ index: Int, _ name: String, _ block: () async throws -> String) async {
            let step = steps[index]
            same("step \(index) name", name, step["name"] as? String)
            queue = (step["calls"] as? [[String: Any]]) ?? []
            var result: String
            do {
                result = try await block()
            } catch {
                result = "error: " + ((error as? LocalizedError)?.errorDescription ?? "\(error)")
            }
            same("\(name): result", result, step["result"] as? String)
            if !queue.isEmpty { problems.append("\(name): \(queue.count) requests Android sent were not sent") }
            for p in problems.prefix(8) { miss(name, p, "the same as Android") }
            problems = []
        }

        state = Int32(try XCTUnwrap(w["seed"] as? Int))
        let wrong = Otp(Otp.MACID, deviceId)
        await run(0, "activate with a wrong SMS code") { try await wrong.activate("WRONG1", pin); return "kotlin.Unit" }
        let otp = Otp(Otp.MACID, deviceId)
        await run(1, "activate") { try await otp.activate(sms, pin); return "kotlin.Unit" }
        sameJSONValue("state after activation", otp.toJson(), w["afterActivate"])
        same("serial", otp.serial(), w["serial"] as? String)
        same("kma", otp.kma(pin), w["kma"] as? String)
        await run(2, "first code (the server asks twice)") { try await otp.nextCode() }
        sameJSONValue("state after the first code", otp.toJson(), w["afterFirstCode"])
        // Saved and read again in between, as the app does.
        let again = try Otp.fromJson(otp.toJson())
        await run(3, "second code") { try await again.nextCode() }
        sameJSONValue("state after the second code", again.toJson(), w["afterSecondCode"])
        await run(4, "third code") { try await again.nextCode() }
        sameJSONValue("state after the third code", again.toJson(), w["afterThirdCode"])
        await run(5, "fourth code is refused") { try await again.nextCode() }
    }

    func testCryptoPiecesMatchAndroid() throws {
        let w = try XCTUnwrap(expected("otp_expected.json") as? [String: Any])
        let keys = try XCTUnwrap(w["keys"] as? [String: String])
        let factory = try XCTUnwrap(keys["factoryN"])
        let device = try XCTUnwrap(keys["deviceN"])

        for row in try XCTUnwrap(w["sha256"] as? [[String]]) { same("sha256(\(row[0]))", Otp.sha256Hex(row[0]), row[1]) }
        for row in try XCTUnwrap(w["hex"] as? [[String]]) { same("hexToBytes(\(row[0]))", Otp.hexToBytes(row[0]).map { Otp.toHex($0) }, row[1]) }
        XCTAssertNil(Otp.hexToBytes("xyz1"))

        let sealed = try XCTUnwrap(w["decodeOaep"] as? [[String: String]])
        XCTAssertEqual(sealed.count, 8)
        for (i, c) in sealed.enumerated() {
            same("decodeOaep[\(i)]", try? Otp.decodeOaep(c["sealed"] ?? "", factory), c["opened"])
            XCTAssertEqual(c["opened"], c["data"])
        }
        XCTAssertEqual(w["decodeOaepWrongKey"] as? String, "error: Incorrect decryption")
        XCTAssertThrowsError(try Otp.decodeOaep(sealed[2]["sealed"] ?? "", device)) { XCTAssertEqual(($0 as? Otp.OtpError)?.message, "Incorrect decryption") }

        let enc = try XCTUnwrap(w["oaepEncrypt"] as? [String: Any])
        state = Int32(try XCTUnwrap(enc["seed"] as? Int))
        for (i, c) in (try XCTUnwrap(enc["cases"] as? [[String: String]])).enumerated() {
            let data = try XCTUnwrap(Otp.hexToBytes(c["data"] ?? "x"))
            same("oaepEncrypt[\(i)]", (try? Otp.oaepEncrypt(data, device)).map { Otp.toHex($0) }, c["encrypted"])
        }

        for row in try XCTUnwrap(w["computeOtp"] as? [[String]]) {
            let t = Otp(Otp.MACID, "dev")
            t.iwK1 = row[0]
            t.defi = row[1]
            t.iwsecval = row[2]
            same("computeOtp(\(row[0]), \(row[1]), \(row[2]))", t.computeOtp(), row[3])
        }

        let r = Otp(Otp.MACID, "dev-1")
        r.iwalea = "00aa"
        r.iwK0 = "k0"
        r.iwK1 = "k1"
        r.challenge = "ch"
        r.codepin = "9999"
        let wantR = try XCTUnwrap(w["getR"] as? [String: String])
        for (k, v) in r.getR() { same("getR.\(k)", v, wantR[k]) }
        r.action = "synchro"
        let wantSynchro = try XCTUnwrap(w["getRSynchro"] as? [String: String])
        for (k, v) in r.getR() { same("getR (synchro).\(k)", v, wantSynchro[k]) }
        XCTAssertEqual(r.getR().map { $0.0 }, ["R0", "R1", "R2"])

        sameJSONValue("state read from a nearly empty text", try Otp.fromJson("{\"deviceId\":\"d\",\"iwalea\":\"aa\"}").toJson(), w["fromSparseJson"])
        XCTAssertThrowsError(try Otp.fromJson("{}"))
    }

    func testBigNumbers() {
        let m = BigUInt(bytes: [0x01, 0x00, 0x00, 0x00, 0x01])          // 2^32 + 1
        XCTAssertEqual(m.bitLength, 33)
        XCTAssertEqual(BigUInt(bytes: []).bitLength, 0)
        XCTAssertEqual(BigUInt(bytes: [0, 0, 5]).bytes(2), [0, 5])
        XCTAssertEqual(BigUInt(bytes: [1, 2, 3, 4, 5, 6]).bytes(8), [0, 0, 1, 2, 3, 4, 5, 6])
        XCTAssertEqual(BigUInt(bytes: [1, 2, 3, 4, 5, 6]).bytes(3), [4, 5, 6])
        // 3^17 = 129140163 = 0x07B285C3, small enough to check by hand.
        XCTAssertEqual(BigUInt(bytes: [3]).power(17, mod: BigUInt(bytes: [0xFF, 0xFF, 0xFF, 0xFF, 0xFF])).bytes(5), [0, 0x07, 0xB2, 0x85, 0xC3])
        // 2^40 mod (2^32 + 1): 2^32 is -1 there, so 2^40 = -256 = 2^32 + 1 - 256.
        XCTAssertEqual(BigUInt(bytes: [1, 0, 0, 0, 0, 0]).mod(m).bytes(5), [0, 0xFF, 0xFF, 0xFF, 0x01])
        // (2^32)^2 = 1 modulo 2^32 + 1.
        let x = BigUInt(bytes: [1, 0, 0, 0, 0])
        XCTAssertEqual(x.multiplied(by: x, mod: m).bytes(1), [1])
        XCTAssertEqual(BigUInt(hex: "0100")?.bytes(2), [1, 0])
        XCTAssertNil(BigUInt(hex: "zz"))
    }

    func testTheServersAnswersAreRead() {
        let raw = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ActionSetup>
          <err>OK</err>
          <Kfact> ab12 </Kfact>
          <empty></empty>
          <closed/>
          <twice></twice>
          <twice>second</twice>
          <twice>third</twice>
          <text>a &amp; b &lt;c&gt;</text>
          <outer>kept<inner>dropped</inner></outer>
        </ActionSetup>
        """
        let x = Otp.parseXml(raw, "ActionSetup")
        XCTAssertEqual(x?["err"], "OK")
        XCTAssertEqual(x?["Kfact"], "ab12")
        XCTAssertEqual(x?["empty"], "")
        XCTAssertEqual(x?["closed"], "")
        XCTAssertEqual(x?["twice"], "second")
        XCTAssertEqual(x?["text"], "a & b <c>")
        XCTAssertEqual(x?["outer"], "kept")
        XCTAssertNil(x?["inner"])
        XCTAssertNil(Otp.parseXml(raw, "ActionFinalize"))
        XCTAssertNil(Otp.parseXml("not xml at all", "ActionSetup"))
        XCTAssertEqual(Otp.parseXml("<ActionFinalize><err>NOK:Bad</err></ActionFinalize>", "ActionFinalize")?["err"], "NOK:Bad")
    }
}
