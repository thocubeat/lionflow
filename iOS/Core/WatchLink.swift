import Foundation

/// Every car the user has registered, across accounts and brands (the Android app's `Garage`). The watch's
/// "Switch car" goes through them in this order.
enum Garage {
    struct Entry: Equatable {
        let accountId: String
        let brand: Brand
        let vehicle: Vehicle
    }

    /** Each car once, even when it is on two accounts (shared with family): the open account's entry wins. */
    static func entries(_ s: AppServices) -> [Entry] {
        let active = s.settings.activeAccount
        let signedIn = s.settings.accounts().filter { s.api.isSignedInTo($0.id) }
        var seen = Set<String>()
        var out: [Entry] = []
        for a in stableSorted(signedIn, by: { $0.id == active && $1.id != active }) {
            for v in s.api.cachedVehiclesOf(a.id) where seen.insert(v.vin).inserted {
                out.append(Entry(accountId: a.id, brand: Brand.of(a.brand), vehicle: v))
            }
        }
        return out
    }

    static func current(_ s: AppServices) -> Entry? {
        guard let vin = s.api.cachedVehicle()?.vin else { return nil }
        let active = s.settings.activeAccount
        return entries(s).first { $0.accountId == active && $0.vehicle.vin == vin }
    }

    /** The car after the one in use. Nil when there is only one car. */
    static func next(_ s: AppServices) -> Entry? {
        let all = entries(s)
        if all.count < 2 { return nil }
        let cur = current(s)
        let i = all.firstIndex { $0 == cur } ?? -1
        return all[(i + 1) % all.count]
    }
}

/// Answers LionFlow on the Apple Watch: a port of the Android app's `WearService`. The watch has no sign-in of its
/// own. It asks the phone, and the phone answers with texts that are already in the app's language and units.
final class WatchLink: @unchecked Sendable {
    static let statusPath = "/lionflow/status"
    static let statusReply = "/lionflow/status/reply"
    static let cmdPath = "/lionflow/cmd"
    static let cmdReply = "/lionflow/cmd/reply"

    private let services: AppServices
    /** "Switch car" on the watch. Done by the app, so its own screens follow. */
    var switchToNextCar: @Sendable () async -> Void = {}
    /** How long the phone waits for the car before it gives up (tests shorten it). */
    var commandTimeoutMs = 90_000

    init(_ services: AppServices) { self.services = services }

    /// One message from the watch. `reply` gets a path and an answer: once for a status, and several times for a
    /// command (progress lines, then the result).
    func handle(_ path: String, _ payload: String, reply: @escaping @Sendable (String, JSONObject) -> Void) async {
        switch path {
        case Self.statusPath:
            reply(Self.statusReply, await status())
        case Self.cmdPath:
            if payload == "nextcar" {
                await switchToNextCar()
                reply(Self.statusReply, await status())
            } else {
                await command(payload, reply)
            }
        default:
            break
        }
    }

    func status() async -> JSONObject {
        let api = services.api
        if !api.isSignedIn { return JSONObject().put("error", tr("Open LionFlow to sign in")) }
        do {
            guard let v = try await api.currentVehicle() else { return JSONObject().put("error", tr("No car on this account")) }
            let st = ChargePower.record(try await api.readStatus(v))
            services.statusChanged(st)
            let s = services.settings.value
            // What the watch shows is chosen on the phone (Settings → Home screens → Watch).
            let hidden = HomeLayout.hidden(s.watchHidden)
            var today: Double? = nil
            var yesterday: Double? = nil
            if !hidden.contains("distance"), let d = try? await distances(v) {
                today = d.0
                yesterday = d.1
            }
            let extras = JSONArray()
            func extra(_ id: String, _ label: String, _ value: String?) {
                guard !hidden.contains(id), let value = value, !value.allSatisfy({ $0.isWhitespace }) else { return }
                extras.put(JSONObject().put("label", tr(label)).put("value", value))
            }
            extra("doors", "Doors", doorsLine(st))
            extra("odometer", "Odometer", st.odometerKm.map { fmtDistance($0, s) })
            extra("outside", "Outside", st.outsideTempC.map { fmtTemp($0, s) })
            var service: [String] = []
            if let km = st.kmToService { service.append(fmtDistance(km, s)) }
            if let days = st.daysToService { service.append(tr("{0} days").replacingOccurrences(of: "{0}", with: "\(days.roundToLong)")) }
            extra("service", "Next service", service.isEmpty ? nil : service.joined(separator: " · "))

            var state = ""
            if st.isCharging { state = "Charging" + (ChargePower.label(st).map { " at \($0)" } ?? "") }
            else if st.preconditioningOn { state = "Pre-heating is on" }
            else if st.plugged { state = "Plugged in" }
            let now = nowMillis()
            let estimated = st.rangeEstimated ? " " + tr("(est.)").trimmingCharacters(in: .whitespaces) : ""
            let whereItIs = await place(st)
            var hide: [String] = []
            for id in HomeLayout.ids(s.watchHidden) where !hide.contains(id) { hide.append(id) }
            return JSONObject()
                .put("hide", JSONArray(hide))
                .put("extras", extras)
                .put("name", s.carName)
                .put("cars", Garage.entries(services).count)
                .put("battery", st.batteryPct.map { "\(JSONValue.javaInt($0)) %" } ?? "—")
                .put("range", "≈ " + fmtDistance(st.rangeKm, s) + estimated)
                .put("fuel", st.energyType == "Fuel")
                .put("locked", st.locked.map { JSONValue.bool($0) } ?? JSONValue.null)
                .put("status", tr(state))
                .put("place", whereItIs)
                .put("today", today.map { fmtDistance($0, s) } ?? "—")
                .put("yesterday", yesterday.map { fmtDistance($0, s) } ?? "—")
                .put("updated", tr(minutesAgo(st.updatedAtMillis ?? now, now)))
        } catch {
            return JSONObject().put("error", tr(Http.describe(error)))
        }
    }

    /** Where the car is: a saved place ("Home", a charger's name) or the street address. */
    private func place(_ st: VehicleStatus) async -> String {
        guard let lat = st.lat, let lon = st.lon else { return "" }
        if st.moving { return tr("Driving") }
        let cfg = ChargeLog.places
        if let h = cfg.home, let hLat = h.lat, let hLon = h.lon, ChargeLog.distanceM(lat, lon, hLat, hLon) <= 50 { return tr("Home") }
        for k in cfg.known {
            if let kLat = k.lat, let kLon = k.lon, ChargeLog.distanceM(lat, lon, kLat, kLon) <= 75 { return k.name }
        }
        if let address = await services.address(lat, lon) { return address }
        return Fmt.fixed(lat, 5) + ", " + Fmt.fixed(lon, 5)
    }

    /** Kilometres driven today and yesterday (local time). */
    private func distances(_ v: Vehicle) async throws -> (Double, Double) {
        let todayStart = Int64((Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000).rounded())
        let yStart = todayStart - 86_400_000
        let trips = try await services.api.allTrips(v, yStart).trips.map { Trip.parse($0) }
        var seen = Set<String>()
        let uniq = trips.filter { seen.insert($0.id).inserted }
        let today = uniq.filter { ($0.startMillis ?? 0) >= todayStart }.reduce(0.0) { $0 + $1.distanceKm }
        let yesterday = uniq.filter { let t = $0.startMillis ?? 0; return t >= yStart && t < todayStart }.reduce(0.0) { $0 + $1.distanceKm }
        return (today, yesterday)
    }

    private func command(_ cmd: String, _ reply: @escaping @Sendable (String, JSONObject) -> Void) async {
        let api = services.api
        let remote = services.remote
        if !api.isSignedIn || !remote.isSetUp {
            reply(Self.cmdReply, JSONObject().put("text", tr("Set up remote control in LionFlow on your phone first")))
            return
        }
        let label: String
        switch cmd {
        case "unlock": label = "Unlock"
        case "lock": label = "Lock"
        case "lights": label = "Flash lights"
        case "preheat": label = "Pre-heating"
        default: return
        }
        let tracker = Tracker(onProgress: { p in
            reply(Self.cmdReply, JSONObject().put("text", tr("\(label) — \(p)")).put("final", false))
        })
        let done = Deferred<CommandResult>()
        let work = Task {
            let r: CommandResult
            switch cmd {
            case "unlock": r = await remote.lock(false, tracker)
            case "lock": r = await remote.lock(true, tracker)
            case "lights": r = await remote.lights(tracker)
            default:
                var programs = JSONObject()
                if let v = try? await api.currentVehicle(), let st = try? await api.readStatus(v) { programs = st.programsJson() }
                r = await remote.preconditioning(true, programs, tracker)
            }
            done.complete(r)
        }
        let answer = await done.wait(timeoutMs: commandTimeoutMs)
        if answer == nil { work.cancel() }
        let r = answer ?? .failed("No answer from the car yet — it may be asleep or out of coverage")
        let text: String
        switch r {
        case .success: text = "\(label): done"
        case .delivered: text = "\(label): car received it"
        case .failed(let reason): text = "\(label) failed — \(reason)"
        }
        remote.addLog("watch: \(text)")
        reply(Self.cmdReply, JSONObject().put("text", tr(text)).put("final", true))
    }
}
