import Foundation

enum CommandResult: Equatable {
    case success
    /** The car has received the command; the final answer arrives later through `Tracker.onFinal`. */
    case delivered
    case failed(String)
}

/** Follows one command: progress lines while waiting, and the car's final answer if it comes after we stopped waiting. */
final class Tracker {
    let onProgress: (String) -> Void
    let onFinal: (CommandResult) -> Void
    init(onProgress: @escaping (String) -> Void = { _ in }, onFinal: @escaping (CommandResult) -> Void = { _ in }) {
        self.onProgress = onProgress
        self.onFinal = onFinal
    }
}

/** A value that arrives once, later (Kotlin's CompletableDeferred). */
final class Deferred<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var value: T?
    private var callbacks: [(T) -> Void] = []

    var isCompleted: Bool { lock.lock(); defer { lock.unlock() }; return value != nil }

    /** The first value wins; later ones are ignored. */
    func complete(_ v: T) {
        lock.lock()
        if value != nil {
            lock.unlock()
            return
        }
        value = v
        let waiting = callbacks
        callbacks = []
        lock.unlock()
        for c in waiting { c(v) }
    }

    private func whenDone(_ c: @escaping (T) -> Void) {
        lock.lock()
        if let v = value {
            lock.unlock()
            c(v)
            return
        }
        callbacks.append(c)
        lock.unlock()
    }

    /** Waits for the value, at most this long. Nil when it didn't come in time. */
    func wait(timeoutMs: Int) async -> T? {
        await withCheckedContinuation { (c: CheckedContinuation<T?, Never>) in
            let once = NSLock()
            var done = false
            func finish(_ v: T?) {
                once.lock()
                if done {
                    once.unlock()
                    return
                }
                done = true
                once.unlock()
                c.resume(returning: v)
            }
            whenDone { finish($0) }
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) { finish(nil) }
        }
    }
}

/// Remote commands (lock, climate, charging, horn, lights) go over MQTT, exactly like the carmakers' own apps.
/// The MQTT password is a short-lived token obtained with a one-time password from `Otp`; a refresh token
/// (valid about 3 days) avoids asking for new one-time passwords too often.
final class RemoteControl: @unchecked Sendable {
    static let kOtp = "otp_state"
    static let kPin = "security_code"
    static let kMqttAccess = "mqtt_access"
    static let kMqttExp = "mqtt_expires"
    static let kMqttRefresh = "mqtt_refresh"
    static let kMqttRefreshExp = "mqtt_refresh_expires"
    private static let inProgress: Set<String> = ["900", "901", "902"]

    /** The carmaker's message server. Tests put their own here. */
    static var broker: (host: String, port: UInt16, tls: Bool) = ("mwa.mpsa.com", 8885, true)

    private let api: StellantisApi
    private let secure: SecureStore
    private let gate = AsyncLock()
    private let state = NSLock()
    private var client: MqttClient?
    private var pending: [String: Deferred<String>] = [:]
    private var progress: [String: (String) -> Void] = [:]
    private var wakeWaiters: [String: () -> Void] = [:]
    private var finals: [String: (CommandResult) -> Void] = [:]
    private var services: [String: String] = [:]
    private var reasons: [String: String] = [:]
    private var peugeotReasons: [String: String] = [:]
    private var wakeIds: Set<String> = []
    private var wakeDone: Deferred<Bool>?
    private var logLines: [String] = []
    private var lastWake: Int64 = 0
    // When the car doesn't answer wake-ups (asleep connection, or "busy with another request"), stop
    // asking so often: piling up requests doesn't help and can keep the car's queue busy.
    private var wakePauseUntil: Int64 = 0
    private var wakePauseMs: Int64 = 0

    init(_ api: StellantisApi, _ secure: SecureStore) {
        self.api = api
        self.secure = secure
    }

    private func locked<T>(_ body: () -> T) -> T {
        state.lock()
        defer { state.unlock() }
        return body()
    }

    /** Last answers from the car, newest first: shown in Settings › Command log for troubleshooting. */
    var log: [String] { locked { logLines } }

    func addLog(_ line: String) {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "HH:mm:ss"
        let t = f.string(from: Date())
        locked {
            logLines.insert("\(t)  \(line)", at: 0)
            if logLines.count > 40 { logLines.removeLast(logLines.count - 40) }
        }
    }

    var isSetUp: Bool { secure.get(Self.kOtp) != nil }

    /** Activate remote control with the SMS code and the security code of the carmaker's app. */
    func activate(_ smsCode: String, _ pin: String) async throws {
        let deviceId = String((api.currentAccessToken() ?? Otp.randomHex(8)).prefix(16))
        let otp = Otp(Otp.MACID, deviceId)
        let code = pin.trimmingCharacters(in: .whitespacesAndNewlines)
        try await otp.activate(smsCode.trimmingCharacters(in: .whitespacesAndNewlines), code)
        secure.put(Self.kOtp, otp.toJson())
        secure.put(Self.kPin, code)
        // Trade a first one-time password for a token, so the setup is verified end to end.
        // The server sometimes rejects the very first code right after activation, so try a few fresh codes.
        var last: Error? = nil
        for _ in 0..<3 {
            do {
                try await passwordGrant(otp)
                return
            } catch let e as ApiException {
                last = e
                if e.code != 400 { break }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
        // Don't leave a half-finished setup behind: the user can start over with a new SMS.
        secure.put(Self.kOtp, nil)
        secure.put(Self.kPin, nil)
        throw last ?? ApiException(0, "Activation failed")
    }

    /** Local check of the security code before a remote action (Face ID or Touch ID can replace it). */
    func checkPin(_ pin: String) -> Bool { secure.get(Self.kPin).map { $0 == pin } ?? false }
    var hasPin: Bool { secure.get(Self.kPin) != nil }

    private func passwordGrant(_ otp: Otp) async throws {
        let code = try await otp.nextCode()
        secure.put(Self.kOtp, otp.toJson())
        let j = try await api.mqttToken(JSONObject().put("grant_type", "password").put("password", code))
        try saveToken(j, withRefresh: true)
    }

    private func saveToken(_ j: JSONObject, withRefresh: Bool) throws {
        guard let access = j.str("access_token") else { throw ApiException(0, "No remote-control token in response") }
        secure.put(Self.kMqttAccess, access)
        let exp = nowMillis() + j.optLong("expires_in", 3600) * 1000
        secure.put(Self.kMqttExp, String(exp))
        if let refresh = j.str("refresh_token") {
            secure.put(Self.kMqttRefresh, refresh)
            if withRefresh || secure.get(Self.kMqttRefreshExp) == nil {
                secure.put(Self.kMqttRefreshExp, String(nowMillis() + 3 * 24 * 3600 * 1000))
            }
        }
    }

    private func refreshGrant(_ refresh: String) async throws {
        try saveToken(try await api.mqttToken(JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh)), withRefresh: false)
    }

    private func ensureToken(force: Bool, marginMs: Int64 = 60_000) async throws {
        let exp = Int64(secure.get(Self.kMqttExp) ?? "") ?? 0
        if !force && secure.get(Self.kMqttAccess) != nil && nowMillis() < exp - marginMs { return }
        let refreshExp = Int64(secure.get(Self.kMqttRefreshExp) ?? "") ?? 0
        let refresh = secure.get(Self.kMqttRefresh)
        if let r = refresh, nowMillis() < refreshExp {
            do {
                try await refreshGrant(r)
                return
            } catch is ApiException {
                // fall through to a fresh one-time password
            }
        }
        guard let otpJson = secure.get(Self.kOtp) else { throw ApiException(0, "Remote control isn't set up yet") }
        let otp = try Otp.fromJson(otpJson)
        do {
            try await passwordGrant(otp)
        } catch {
            guard let r = refresh else { throw error }
            try await refreshGrant(r)
        }
    }

    private func connect() async throws -> MqttClient {
        if let c = locked({ client }), c.isConnected { return c }
        locked({ client })?.disconnect()
        let c = MqttClient()
        c.onMessage = { [weak self] _, payload in self?.messageArrived(String(decoding: payload, as: UTF8.self)) }
        let b = Self.broker
        try await c.connect(host: b.host, port: b.port, tls: b.tls, username: "IMA_OAUTH_ACCESS_TOKEN", password: secure.get(Self.kMqttAccess) ?? "",
                            keepAlive: 120, timeoutSeconds: 20)
        try await c.subscribe("psa/RemoteServices/to/cid/\(api.customerId ?? "null")/#")
        locked { client = c }
        return c
    }

    /** One message from the carmaker's server about a command. (Not private, so tests can feed it answers.) */
    func messageArrived(_ body: String) {
        guard let data = try? JSONObject(body) else { return }
        let id = data.optString("correlation_id")
        guard let code = data.str("return_code") ?? data.str("process_code") else { return }
        let isWake = locked { wakeIds.contains(id) }
        let svc = locked { services[id] } ?? (isWake ? "/VehCharge/state (wake)" : "?")
        addLog("\(svc) → \(code)" + (data.str("reason").map { " · \($0)" } ?? "") + (data.optJSONObject("resp_data").map { " · resp_data=\($0.toString())" } ?? ""))
        // A pre-wake: the car is reachable once it's forwarded (903) or finished.
        if isWake {
            if code == "903" || !Self.inProgress.contains(code) { locked { wakeDone }?.complete(true) }
            if !Self.inProgress.contains(code) {
                noteWakeAnswer(code)
                let cb: (() -> Void)? = locked {
                    wakeIds.remove(id)
                    return wakeWaiters.removeValue(forKey: id)
                }
                if code == "0" || code == "1" { cb?() }
            }
            return
        }
        // 900 = accepted, 901 = car asleep (being woken), 902 = checking state, 903 = delivered to the car.
        if code == "900" || code == "901" || code == "902" {
            let p = locked { progress[id] }
            p?(code == "900" ? "Sent to Peugeot…" : (code == "901" ? "Waking the car…" : "Checking the car…"))
            return
        }
        if code == "903" {
            let p = locked { progress[id] }
            let w = locked { pending[id] }
            p?("Car received it")
            w?.complete("903")
            return
        }
        let reason = data.optString("reason")
        let finalCode = (code == "400" && reason.contains("no.matching.service.key")) ? "not_compatible" : code
        if finalCode != "0" && finalCode != "1" {
            let service = locked { services[id] }
            if let d = Self.describeRespData(service, data.optJSONObject("resp_data")) { locked { reasons[id] = d } }
            let own = reason.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
            if !own.allSatisfy({ $0.isWhitespace }) { locked { peugeotReasons[id] = own } }
        }
        if let w = locked({ pending[id] }), !w.isCompleted {
            w.complete(finalCode)
        } else {
            let done: ((CommandResult) -> Void, String?, String?)? = locked {
                guard let f = finals.removeValue(forKey: id) else { return nil }
                return (f, reasons.removeValue(forKey: id), peugeotReasons.removeValue(forKey: id))
            }
            if let d = done { d.0(Self.mapCode(finalCode, d.1, d.2)) }
        }
    }

    static func mapCode(_ code: String, _ reason: String? = nil, _ peugeotReason: String? = nil) -> CommandResult {
        if code == "0" || code == "1" { return .success }
        let meaning: String
        switch code {
        case "400": meaning = "The car refused the command"
        case "not_compatible": meaning = "Your car or subscription doesn't support this command"
        case "502": meaning = "The car is still busy with another request — try again in a moment"
        case "300": meaning = "The car didn't answer in time — it may have poor coverage"
        case "500": meaning = "Peugeot's remote-control server reported an internal error"
        case "422": meaning = "Privacy mode is on in the car, so remote commands are blocked"
        case "506": meaning = "The car's mobile provider isn't supported for remote commands"
        case "507": meaning = "The car has no GPS coverage right now"
        case "113": meaning = "Peugeot couldn't match the car (VIN)"
        default: meaning = "Peugeot answered with an unknown result"
        }
        let codeLabel = code == "not_compatible" ? "400" : code
        var extra = ""
        if let p = peugeotReason, !p.allSatisfy({ $0.isWhitespace }) { extra = "Peugeot says: \(p)" }
        return .failed((reason ?? meaning) + " (code \(codeLabel)" + (extra.isEmpty ? "" : " · \(extra)") + ")")
    }

    /** The car's own reason for refusing, from resp_data (the same numbers the carmakers' apps use). */
    static func describeRespData(_ service: String?, _ rd: JSONObject?) -> String? {
        guard let service = service, let rd = rd else { return nil }
        func int(_ k: String) -> Int? { rd.has(k) ? rd.optInt(k, -1) : nil }
        if service == "/Doors" {
            if int("error") == 0 { return "The engine is running" }
            switch int("lock_resp_state") {
            case 2: return "A door is open"
            case 3: return "The car refused the request"
            case 5: return "The key is inside the car"
            case 4: return "The car refused without giving a reason — check that doors, boot, bonnet and windows are fully closed and the key isn't in or right next to the car"
            default: return nil
            }
        }
        if service == "/Horn" || service == "/Lights" {
            switch int("error") {
            case 0: return "The engine is running"
            case 1, 2: return "Just done — wait a moment before sending again"
            default: return nil
            }
        }
        if service == "/ThermalPrecond" {
            switch int("error") {
            case 0: return "The engine is running"
            case 1: return "Pre-heating is already running"
            case 2: return "Communication problem in the car"
            case 3: return "Can't stop while the engine is running"
            case 4: return "Pre-heating wasn't running"
            default: return nil
            }
        }
        if service.hasPrefix("/VehCharge") {
            switch int("error") {
            case 0: return "The charging system is in standby"
            case 1: return "It's already charging"
            case 2: return "Charging communication problem in the car"
            case 3: return "The engine is running"
            default: return nil
            }
        }
        return nil
    }

    private func describe(_ error: Error) -> String {
        guard let e = error as? MqttClient.MqttError else { return Http.describe(error) }
        switch e.reasonCode {
        case 128:
            // SUBACK 0x80: connected, but the carmaker won't let this account listen to the car.
            return "Peugeot refused remote control for this account (MQTT code 128). Usually remote services aren't active for this car: check in MyPeugeot that the remote service is activated and that pre-heating works there. This needs a subscription from the car brand: " + Brands.current.eRemotePack
        case 4, 5:
            return "Peugeot's remote-control server didn't accept the login (MQTT code \(e.reasonCode)). Set up remote control again in Settings."
        default:
            return "Couldn't connect to Peugeot's remote-control server: \(e.message) (MQTT code \(e.reasonCode))"
        }
    }

    /** One try at sending. Nil means: refused with "400" on the first go, try once more with a fresh token and connection. */
    private func sendOnce(_ service: String, _ params: JSONObject, _ tracker: Tracker, retried: Bool) async throws -> CommandResult? {
        if !(locked({ client })?.isConnected ?? false) || retried { tracker.onProgress("Connecting…") }
        try await ensureToken(force: retried)
        if retried { disconnectQuietly() }
        let c = try await connect()
        // If a wake-up is on its way, let it reach the car first: sending now would be refused as a duplicate (502).
        if let d = locked({ wakeDone }), !d.isCompleted {
            tracker.onProgress("Waking the car…")
            _ = await d.wait(timeoutMs: 45_000)
        }
        var attempt = 0
        while true {
            attempt += 1
            let waiter = Deferred<String>()
            let correlation = try await publish(c, service, params) { id in
                self.locked {
                    self.pending[id] = waiter
                    self.progress[id] = tracker.onProgress
                    self.finals[id] = tracker.onFinal
                    self.services[id] = service
                }
            }
            locked { lastWake = nowMillis() }     // any command wakes the car too
            tracker.onProgress("Sent — waiting for the car…")
            let code = await waiter.wait(timeoutMs: 90_000)
            locked {
                pending[correlation] = nil
                progress[correlation] = nil
            }
            if code == "903" {
                // Delivered: stop making the user wait. The final answer (if any) goes to tracker.onFinal.
                DispatchQueue.global().asyncAfter(deadline: .now() + .seconds(180)) { [weak self] in
                    self?.locked {
                        self?.finals[correlation] = nil
                        self?.services[correlation] = nil
                        self?.reasons[correlation] = nil
                    }
                }
                return .delivered
            }
            let (reason, pReason): (String?, String?) = locked {
                finals[correlation] = nil
                services[correlation] = nil
                return (reasons.removeValue(forKey: correlation), peugeotReasons.removeValue(forKey: correlation))
            }
            if code == "300" && attempt == 1 {
                // No answer in time: often the car was waking up or briefly out of coverage. That attempt woke it; try once more.
                tracker.onProgress("Car didn't answer — trying again…")
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                continue
            }
            if code == "502" && attempt == 1 {
                // The car was still busy with an earlier request: wait a little and send once more.
                tracker.onProgress("Car was busy — trying again…")
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                continue
            }
            guard let answered = code else { return .failed("No answer from the car yet — it may be asleep or out of coverage") }
            if answered == "400" && !retried { return nil }
            return Self.mapCode(answered, reason, pReason)
        }
    }

    private func send(_ service: String, _ params: JSONObject, _ tracker: Tracker, retried: Bool = false) async -> CommandResult {
        var outcome: CommandResult? = nil
        do {
            outcome = try await gate.withLock { () -> CommandResult? in
                do {
                    return try await self.sendOnce(service, params, tracker, retried: retried)
                } catch {
                    let msg = self.describe(error)
                    self.addLog("error: \(msg)")
                    return .failed(msg)
                }
            }
        } catch {
            outcome = .failed(describe(error))
        }
        if let r = outcome { return r }
        return await send(service, params, tracker, retried: true)
    }

    /** "20261126090840123": the time in UTC, down to the millisecond. */
    private static func stamp(_ millis: Int64) -> String {
        let iso = isoUTC(millis)      // 2026-11-26T09:08:40Z
        let digits = iso.filter { $0.isNumber }
        let ms = Int(((millis % 1000) + 1000) % 1000)
        return digits + String(format: "%03d", ms)
    }

    /** Builds and publishes one request; `beforeSend` registers listeners before the message goes out. */
    private func publish(_ c: MqttClient, _ service: String, _ params: JSONObject, _ beforeSend: (String) -> Void) async throws -> String {
        guard let customer = api.customerId else { throw ApiException(0, "Missing customer id") }
        guard let vin = api.cachedVehicle()?.vin else { throw ApiException(0, "No vehicle") }
        let now = nowMillis()
        let correlation = UUID().uuidString.lowercased().replacingOccurrences(of: "-", with: "") + Self.stamp(now)
        let payload = Self.payload(token: secure.get(Self.kMqttAccess), customer: customer, correlation: correlation, reqDate: isoUTC(now), vin: vin, params: params)
        beforeSend(correlation)
        addLog("\(service) ← sent \(params.toString())")
        try await c.publish("psa/RemoteServices/from/cid/\(customer)\(service)", Array(payload.toString().utf8))
        return correlation
    }

    /** The request as the carmaker's server expects it. */
    static func payload(token: String?, customer: String, correlation: String, reqDate: String, vin: String, params: JSONObject) -> JSONObject {
        let j = JSONObject()
        j.put("access_token", token)
        j.put("customer_id", customer)
        j.put("correlation_id", correlation)
        j.put("req_date", reqDate)
        j.put("vin", vin)
        j.put("req_parameters", params)
        return j
    }

    /// Sends a wake-up to the car without waiting for it, so it is already awake when a real command arrives.
    /// Skipped if a wake-up (or any command) went out within `minIntervalMs`. `onAwake` runs when the car confirms
    /// it is awake.
    @discardableResult
    func preWake(minIntervalMs: Int64, force: Bool = false, onAwake: @escaping () -> Void = {}) async -> Bool {
        if !isSetUp { return false }
        let now = nowMillis()
        let skip: Bool = locked {
            if now - lastWake < minIntervalMs { return true }
            // `force`: the user asked for it (the refresh button), so the pause after unanswered wake-ups doesn't apply.
            if !force && now < wakePauseUntil { return true }
            lastWake = now
            return false
        }
        if skip { return false }
        do {
            try await gate.withLock { () -> Void in
                try await self.ensureToken(force: false)
                let c = try await self.connect()
                let d = Deferred<Bool>()
                self.locked { self.wakeDone = d }
                _ = try await self.publish(c, "/VehCharge/state", JSONObject().put("action", "state")) { id in
                    self.locked {
                        self.wakeIds.insert(id)
                        self.wakeWaiters[id] = onAwake
                    }
                }
                // Never let a lost answer hold up later commands.
                DispatchQueue.global().asyncAfter(deadline: .now() + .seconds(45)) { d.complete(false) }
            }
            return true
        } catch {
            return false
        }
    }

    private func noteWakeAnswer(_ code: String) {
        if code == "0" || code == "1" || code == "903" {
            locked {
                wakePauseMs = 0
                wakePauseUntil = 0
            }
            return
        }
        if code == "300" || code == "502" || code == "500" {
            let pause: Int64 = locked {
                wakePauseMs = min(wakePauseMs == 0 ? 10 * 60_000 : wakePauseMs * 2, 2 * 60 * 60_000)
                wakePauseUntil = nowMillis() + wakePauseMs
                return wakePauseMs
            }
            addLog("Car didn't answer the wake-up (\(code)) · next try in \(pause / 60_000) min")
        }
    }

    /// Gets everything ready before the user presses a button: a fresh token (renewed if it expires within
    /// 10 minutes) and an open connection to the carmaker's server.
    func warmUp() async {
        if !isSetUp { return }
        _ = try? await gate.withLock { () -> Void in
            try await self.ensureToken(force: false, marginMs: 10 * 60_000)
            _ = try await self.connect()
        }
    }

    func disconnect() { disconnectQuietly() }

    private func disconnectQuietly() {
        let c: MqttClient? = locked {
            let old = client
            client = nil
            return old
        }
        c?.disconnect()
    }

    // MARK: Commands

    func lock(_ locking: Bool, _ t: Tracker = Tracker()) async -> CommandResult {
        await send("/Doors", JSONObject().put("action", locking ? "lock" : "unlock"), t)
    }
    func horn(_ t: Tracker = Tracker()) async -> CommandResult {
        await send("/Horn", JSONObject().put("nb_horn", "2").put("action", "activate"), t)
    }
    func lights(_ t: Tracker = Tracker()) async -> CommandResult {
        await send("/Lights", JSONObject().put("duration", "10").put("action", "activate"), t)
    }
    func wakeUp(_ t: Tracker = Tracker()) async -> CommandResult {
        await send("/VehCharge/state", JSONObject().put("action", "state"), t)
    }

    func preconditioning(_ on: Bool, _ programs: JSONObject, _ t: Tracker = Tracker()) async -> CommandResult {
        await send("/ThermalPrecond", JSONObject().put("asap", on ? "activate" : "deactivate").put("programs", programs), t)
    }

    /** The car's own charge limit: daily = stop at the limit set in the car, trip = charge to 100 %. */
    func chargeLimit(_ daily: Bool, _ t: Tracker = Tracker()) async -> CommandResult {
        await send("/VehCharge/limit", JSONObject().put("action", daily ? "daily" : "trip"), t)
    }

    /** immediate = start now; delayed = wait for the scheduled start time (that is: stop now). */
    func charging(_ startNow: Bool, _ hour: Int, _ minute: Int, _ t: Tracker = Tracker()) async -> CommandResult {
        let program = JSONObject().put("hour", hour).put("minute", minute)
        let r = await send("/VehCharge", JSONObject().put("program", program).put("type", startNow ? "immediate" : "delayed"), t)
        if case .failed = r {} else { ChargePower.rememberStartMode(immediate: startNow) }
        return r
    }

    func forget() {
        disconnectQuietly()
        for k in [Self.kOtp, Self.kPin, Self.kMqttAccess, Self.kMqttExp, Self.kMqttRefresh, Self.kMqttRefreshExp] { secure.put(k, nil) }
    }
}
