import XCTest
@testable import LionFlowCore

/// Remote control: the MQTT packets as bytes, what the car's answers mean, and (when a message server runs on this
/// machine, as it does in the build on GitHub) whole commands sent through a real server to a stand-in for the car.
final class RemoteTests: XCTestCase {

    // MARK: Packets

    func testPacketsAreTheRightBytes() throws {
        XCTAssertEqual(MqttCodec.length(0), [0x00])
        XCTAssertEqual(MqttCodec.length(127), [0x7F])
        XCTAssertEqual(MqttCodec.length(128), [0x80, 0x01])
        XCTAssertEqual(MqttCodec.length(16_383), [0xFF, 0x7F])
        XCTAssertEqual(MqttCodec.length(16_384), [0x80, 0x80, 0x01])
        XCTAssertEqual(MqttCodec.connect(clientId: "", username: "u", password: "pw", keepAlive: 120),
                       [0x10, 0x13, 0x00, 0x04, 0x4D, 0x51, 0x54, 0x54, 0x04, 0xC2, 0x00, 0x78, 0x00, 0x00, 0x00, 0x01, 0x75, 0x00, 0x02, 0x70, 0x77])
        XCTAssertEqual(MqttCodec.subscribe(packetId: 1, topic: "a/#"), [0x82, 0x08, 0x00, 0x01, 0x00, 0x03, 0x61, 0x2F, 0x23, 0x00])
        XCTAssertEqual(MqttCodec.publish(topic: "t", payload: [1, 2]), [0x30, 0x05, 0x00, 0x01, 0x74, 0x01, 0x02])
    }

    func testArrivingBytesAreCutIntoPackets() throws {
        let big = [UInt8](repeating: 7, count: 200)
        var buffer = MqttCodec.publish(topic: "psa/x", payload: big) + [0x20, 0x02, 0x00, 0x00] + [0x90, 0x03, 0x00, 0x01]
        let first = try XCTUnwrap(MqttCodec.take(&buffer))
        XCTAssertEqual(first.type, 3)
        let m = try XCTUnwrap(MqttCodec.message(first))
        XCTAssertEqual(m.0, "psa/x")
        XCTAssertEqual(m.1, big)
        let second = try MqttCodec.take(&buffer)
        XCTAssertEqual(second, MqttCodec.Packet(type: 2, flags: 0, body: [0, 0]))
        let notYet = try MqttCodec.take(&buffer)          // the last packet isn't whole yet
        XCTAssertNil(notYet)
        buffer.append(0x80)
        let third = try MqttCodec.take(&buffer)
        XCTAssertEqual(third, MqttCodec.Packet(type: 9, flags: 0, body: [0, 1, 0x80]))
        XCTAssertTrue(buffer.isEmpty)
        // A message sent with a packet number (QoS 1): the number is not part of the message.
        let withId = MqttCodec.Packet(type: 3, flags: 0x02, body: [0x00, 0x01, 0x74, 0x00, 0x09, 0x41, 0x42])
        XCTAssertEqual(MqttCodec.message(withId)?.1, [0x41, 0x42])
        var junk: [UInt8] = [0x30, 0xFF, 0xFF, 0xFF, 0xFF, 0x01]
        var refused = false
        do { _ = try MqttCodec.take(&junk) } catch { refused = true }
        XCTAssertTrue(refused)
    }

    // MARK: What the car's answers mean

    func testAnswersArePutIntoWords() throws {
        XCTAssertEqual(RemoteControl.mapCode("0"), .success)
        XCTAssertEqual(RemoteControl.mapCode("1"), .success)
        XCTAssertEqual(RemoteControl.mapCode("422"), .failed("Privacy mode is on in the car, so remote commands are blocked (code 422)"))
        XCTAssertEqual(RemoteControl.mapCode("not_compatible"), .failed("Your car or subscription doesn't support this command (code 400)"))
        XCTAssertEqual(RemoteControl.mapCode("400", "A door is open", "doors.open"), .failed("A door is open (code 400 · Peugeot says: doors.open)"))
        XCTAssertEqual(RemoteControl.mapCode("777", nil, " "), .failed("Peugeot answered with an unknown result (code 777)"))

        func rd(_ text: String) throws -> JSONObject { try JSONObject(text) }
        XCTAssertEqual(RemoteControl.describeRespData("/Doors", try rd("{\"lock_resp_state\":2}")), "A door is open")
        XCTAssertEqual(RemoteControl.describeRespData("/Doors", try rd("{\"error\":0,\"lock_resp_state\":2}")), "The engine is running")
        XCTAssertEqual(RemoteControl.describeRespData("/Doors", try rd("{\"lock_resp_state\":5}")), "The key is inside the car")
        XCTAssertNil(RemoteControl.describeRespData("/Doors", try rd("{\"lock_resp_state\":1}")))
        XCTAssertEqual(RemoteControl.describeRespData("/Horn", try rd("{\"error\":2}")), "Just done — wait a moment before sending again")
        XCTAssertEqual(RemoteControl.describeRespData("/ThermalPrecond", try rd("{\"error\":1}")), "Pre-heating is already running")
        XCTAssertEqual(RemoteControl.describeRespData("/VehCharge/limit", try rd("{\"error\":1}")), "It's already charging")
        XCTAssertNil(RemoteControl.describeRespData("/Other", try rd("{\"error\":1}")))
        XCTAssertNil(RemoteControl.describeRespData(nil, try rd("{\"error\":1}")))

        let p = RemoteControl.payload(token: "tok/en", customer: "c1", correlation: "abc", reqDate: "2026-11-26T09:08:40Z", vin: "VIN", params: JSONObject().put("action", "lock"))
        XCTAssertEqual(p.toString(), "{\"access_token\":\"tok\\/en\",\"customer_id\":\"c1\",\"correlation_id\":\"abc\",\"req_date\":\"2026-11-26T09:08:40Z\",\"vin\":\"VIN\",\"req_parameters\":{\"action\":\"lock\"}}")
    }

    // MARK: Through a real message server

    private static let port: UInt16 = 18830
    /** Set in the build on GitHub, where a message server is started first: there the tests below must not be skipped. */
    private static let mustRun = ProcessInfo.processInfo.environment["LIONFLOW_MQTT_TEST"] == "1"

    private func connected() async throws -> MqttClient {
        let c = MqttClient()
        do {
            try await c.connect(host: "127.0.0.1", port: Self.port, tls: false, username: "IMA_OAUTH_ACCESS_TOKEN", password: "tok", timeoutSeconds: 5)
        } catch {
            if Self.mustRun { throw error }
            throw XCTSkip("No MQTT server on this machine (port \(Self.port)): \(error.localizedDescription)")
        }
        return c
    }

    func testMessagesGoThroughARealServer() async throws {
        let a = try await connected()
        let b = try await connected()
        XCTAssertTrue(a.isConnected)
        let got = Deferred<String>()
        a.onMessage = { topic, payload in got.complete(topic + " " + String(decoding: payload, as: UTF8.self)) }
        try await a.subscribe("lionflow/test/\(ProcessInfo.processInfo.processIdentifier)/#")
        let text = String(repeating: "æøå-", count: 100)       // longer than 127 bytes: the length takes two bytes
        try await b.publish("lionflow/test/\(ProcessInfo.processInfo.processIdentifier)/x", Array(text.utf8))
        let arrived = await got.wait(timeoutMs: 5000)
        XCTAssertEqual(arrived, "lionflow/test/\(ProcessInfo.processInfo.processIdentifier)/x " + text)
        a.disconnect()
        b.disconnect()
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertFalse(a.isConnected)
    }

    func testCommandsReachTheCarAndItsAnswersComeBack() async throws {
        // The stand-in for the carmaker's server and the car: listens to what the app sends, and answers.
        let car = try await connected()
        let cid = "cid\(ProcessInfo.processInfo.processIdentifier)"
        let requests = NSLock()
        var seen: [(String, JSONObject)] = []
        var script: [String: [String]] = [:]       // service → the answers to give, in order
        car.onMessage = { topic, payload in
            guard let j = try? JSONObject(String(decoding: payload, as: UTF8.self)) else { return }
            let service = String(topic.dropFirst("psa/RemoteServices/from/cid/\(cid)".count))
            requests.lock()
            seen.append((service, j))
            let answers = script[service] ?? []
            requests.unlock()
            Task {
                for a in answers {
                    guard let answer = try? JSONObject(a) else { continue }
                    answer.put("correlation_id", j.optString("correlation_id"))
                    // A moment first, as a real car takes: the app has then said "Sent — waiting for the car…".
                    try? await Task.sleep(nanoseconds: 120_000_000)
                    try? await car.publish("psa/RemoteServices/to/cid/\(cid)\(service)", Array(answer.toString().utf8))
                }
            }
        }
        try await car.subscribe("psa/RemoteServices/from/cid/\(cid)/#")

        // The app's side, signed in and with a remote-control token that is still good.
        let secure = SecureStore(MemorySecrets())
        let name = "lionflow-remote-" + UUID().uuidString
        let defaults = UserDefaults(suiteName: name)!
        defaults.removePersistentDomain(forName: name)
        let api = StellantisApi(secure, SettingsStore(defaults))
        secure.put(StellantisApi.kCustomer, cid)
        secure.put(StellantisApi.kVehicles, JSONArray([Vehicle(id: "v1", vin: "VR3TESTVIN0000001", motorization: "Electric", brand: "Peugeot", pictureUrl: nil, maintenanceHref: nil).toJson()]).toString())
        secure.put(RemoteControl.kMqttAccess, "token-1")
        secure.put(RemoteControl.kMqttExp, String(nowMillis() + 3_600_000))
        secure.put(RemoteControl.kOtp, "{}")
        let saved = RemoteControl.broker
        RemoteControl.broker = ("127.0.0.1", Self.port, false)
        defer { RemoteControl.broker = saved }
        let remote = RemoteControl(api, secure)
        XCTAssertTrue(remote.isSetUp)

        // Lock: accepted, the car is woken, the car has it. The app stops waiting there; the final answer comes after.
        requests.lock()
        script["/Doors"] = ["{\"return_code\":\"900\"}", "{\"process_code\":\"901\"}", "{\"return_code\":\"903\"}", "{\"return_code\":\"0\"}"]
        script["/Horn"] = ["{\"return_code\":\"0\"}"]
        script["/Lights"] = ["{\"return_code\":\"422\",\"reason\":\"[privacy.on]\"}"]
        script["/VehCharge/state"] = ["{\"return_code\":\"901\"}", "{\"return_code\":\"0\"}"]
        script["/ThermalPrecond"] = ["{\"return_code\":\"1\"}"]
        requests.unlock()
        let lines = NSLock()
        var progress: [String] = []
        let final = Deferred<CommandResult>()
        let tracker = Tracker(onProgress: { p in lines.lock(); progress.append(p); lines.unlock() }, onFinal: { final.complete($0) })
        let locked = await remote.lock(true, tracker)
        XCTAssertEqual(locked, .delivered)
        let later = await final.wait(timeoutMs: 5000)
        XCTAssertEqual(later, .success)
        lines.lock()
        XCTAssertEqual(progress, ["Connecting…", "Sent — waiting for the car…", "Sent to Peugeot…", "Waking the car…", "Car received it"])
        lines.unlock()

        let horn = await remote.horn()
        XCTAssertEqual(horn, .success)
        let lights = await remote.lights()
        XCTAssertEqual(lights, .failed("Privacy mode is on in the car, so remote commands are blocked (code 422 · Peugeot says: privacy.on)"))
        let heat = await remote.preconditioning(true, VehicleStatus.programsJsonOf([]))
        XCTAssertEqual(heat, .success)

        // A wake-up that isn't waited for: the app is told when the car has answered.
        let awake = Deferred<Bool>()
        let sent = await remote.preWake(minIntervalMs: 0, force: true) { awake.complete(true) }
        XCTAssertTrue(sent)
        let woke = await awake.wait(timeoutMs: 5000)
        XCTAssertEqual(woke, true)
        let again = await remote.preWake(minIntervalMs: 60_000)
        XCTAssertFalse(again)                       // one just went out

        requests.lock()
        let all = seen
        requests.unlock()
        XCTAssertEqual(all.map { $0.0 }, ["/Doors", "/Horn", "/Lights", "/ThermalPrecond", "/VehCharge/state"])
        let lock = all[0].1
        XCTAssertEqual(lock.optString("access_token"), "token-1")
        XCTAssertEqual(lock.optString("customer_id"), cid)
        XCTAssertEqual(lock.optString("vin"), "VR3TESTVIN0000001")
        XCTAssertEqual(lock.optJSONObject("req_parameters")?.toString(), "{\"action\":\"lock\"}")
        XCTAssertEqual(lock.optString("correlation_id").count, 32 + 17)
        XCTAssertNotNil(VehicleStatus.parseIso(lock.optString("req_date")))
        XCTAssertEqual(all[1].1.optJSONObject("req_parameters")?.toString(), "{\"nb_horn\":\"2\",\"action\":\"activate\"}")
        XCTAssertEqual(all[3].1.optJSONObject("req_parameters")?.optString("asap"), "activate")
        XCTAssertEqual(all[3].1.optJSONObject("req_parameters")?.optJSONObject("programs")?.optJSONObject("program1")?.optInt("hour"), 34)
        XCTAssertFalse(remote.log.isEmpty)

        remote.disconnect()
        car.disconnect()
    }
}
