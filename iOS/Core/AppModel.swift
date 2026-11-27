import Foundation
import Combine

enum Phase: Equatable { case loading, signedOut, setupRemote, locked, ready }

struct ServiceEntry: Equatable {
    var title: String
    var note: String
    var dateMillis: Int64
}

/** Everything the screens show. One value: a screen never sees half of a change. */
struct UiState: Equatable {
    var phase = Phase.loading
    var tab = AppTab.home
    var busy = false
    var signingIn = false
    var vehicle: Vehicle? = nil
    var status: VehicleStatus? = nil
    /** The carmaker has no live status for this car (common on older petrol/diesel cars); trips etc. still work. */
    var statusMissing = false
    var refreshing = false
    var reloadingVehicles = false
    var lastRefresh: Int64 = 0
    var now: Int64 = nowMillis()
    var weather: Weather? = nil
    var myLat: Double? = nil
    var myLon: Double? = nil
    var chargers: [Charger] = []
    var chargerError: String? = nil
    var trips: [Trip] = []
    var tripsLoading = false
    var serviceHistory: [ServiceEntry] = []
    var commandInProgress: String? = nil
    var commandProgress: String? = nil
    var message: String? = nil
    var smsSent = false
    var setupError: String? = nil
    var photoPath: String? = nil
    var vehicles: [Vehicle] = []
    /** Warnings the car itself reports (tyre pressure, a lamp …). */
    var alerts: [CarAlert] = []
}

struct AppError: Error, LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// The parts the app is built from: settings, the Keychain, the carmaker's API and remote control. Made once when
/// the app starts. The things only a phone can do (where am I, the widget) are handed in as closures, so the same
/// code runs in the tests without a phone.
final class AppServices {
    let settings: SettingsStore
    let secure: SecureStore
    let api: StellantisApi
    let remote: RemoteControl
    /** Demo mode's own small stores: which sample car, and its throw-away charging log. */
    let demo: KeyValueStore
    let demoLog: KeyValueStore
    let cacheDir: URL
    let filesDir: URL

    var version = "0.1.0"
    /** "iOS 17.2 · iPhone15,2": for the technical details of a bug report. */
    var system = ""
    var lastKnownLocation: () -> (lat: Double, lon: Double)? = { nil }
    /** Told when there is a fresh status (or none any more): the widget and the watch show it. */
    var statusChanged: (VehicleStatus?) -> Void = { _ in }
    var weather: (Double, Double) async throws -> Weather = { lat, lon in try await WeatherApi.forecast(lat, lon) }
    var chargersNear: (Double, Double) async throws -> [Charger] = { lat, lon in try await OsmChargers.near(lat, lon) }
    var address: (Double, Double) async -> String? = { lat, lon in try? await Geocoder.reverse(lat, lon) }

    /// `knownInstall`: the app's settings file was already there when it started. Then this is never treated as a
    /// new install, even if the settings can't be read (iOS can start the app in the background before that works),
    /// so the sign-in is never wiped by mistake.
    init(defaults: UserDefaults, secrets: SecretBackend, log: KeyValueStore, demo: KeyValueStore, demoLog: KeyValueStore, cacheDir: URL, filesDir: URL,
         knownInstall: Bool = false) {
        settings = SettingsStore(defaults)
        // What is in the Keychain outlives the app being deleted: a fresh install starts clean.
        if !knownInstall && settings.firstRun() { SecureStore(secrets).wipeAll() }
        L10n.setPreference(settings.value.language)
        TimeFmt.setPreference(settings.value.timeFormat)
        secure = SecureStore(secrets)
        secure.profile = settings.activeAccount
        api = StellantisApi(secure, settings)
        remote = RemoteControl(api, secure)
        self.demo = demo
        self.demoLog = demoLog
        self.cacheDir = cacheDir
        self.filesDir = filesDir
        let vin = api.cachedVehicle()?.vin
        ChargePower.start(log, settings, vin)
        ChargeLog.savedChargers = { try? String(contentsOf: cacheDir.appendingPathComponent("chargers.json"), encoding: .utf8) }
        ChargeLog.start(log, vin)
        // "Remember me" off: start every cold launch signed out.
        if !settings.value.rememberMe && api.isSignedIn {
            remote.forget()
            api.signOut()
        }
    }
}

/// The app's state and everything the screens can ask for: a port of the Android app's view model, function by
/// function, so both apps do the same thing in the same order.
@MainActor
final class AppModel: ObservableObject {
    let services: AppServices
    private var api: StellantisApi { services.api }
    private var remote: RemoteControl { services.remote }
    var settings: SettingsStore { services.settings }

    @Published private(set) var ui = UiState()
    /** The settings as they are now. Screens read this, and change them with `update`. */
    @Published private(set) var prefs: AppSettings
    /** "@Home", "@" and a known charger's name, or a street address for where the car is parked. */
    @Published private(set) var parkedAt: String? = nil
    /** True when the brand's server said "no trips" on the first call: then the trips list explains it. */
    @Published var tripsNone = false
    /** A setting to scroll to and flash when the Settings tab opens (set by `openSetting`). */
    @Published var focusSetting: String? = nil
    /** Screenshots: demo without the "Demo mode" banner. */
    @Published var hideDemoBanner = false
    /** Counts up when the trip tags or the charging log change, so the screens that show them draw again. */
    @Published private(set) var dataVersion = 0

    /** Trip tags (kept on the phone). */
    let tags: TripTags

    /** How long a simulated command takes in demo mode (tests set 0). */
    var demoCommandNanos: UInt64 = 1_200_000_000
    /** The pauses before reading the car again after a command, in milliseconds. */
    var refreshSoonMillis: [UInt64] = [3_000, 30_000, 90_000]

    private var ticker: Task<Void, Never>?
    private let vehicleMetadataLock = AsyncLock()
    private var brandingAttempts = Set<String>()
    private var tripsDays = 30
    private var serverRetries = 0
    private var alertsAt: Int64 = 0
    private var alertsVin = ""
    private var alertsOff = Set<String>()
    // What the user just asked for, kept until the car's status catches up (the carmaker's server lags behind).
    private var holdPrecond: (want: Bool, until: Int64)?
    private var holdLock: LockHold?
    private var parkedKey = ""
    private var addressCache: [String: String] = [:]

    static let noRemote = "Peugeot doesn't offer remote control for this car to LionFlow. Check that remote commands (like pre-heating) work in the MyPeugeot app and that remote services are activated there (they need a subscription from the car brand, see Settings). You can still skip and use LionFlow for status, charging and trips."

    init(_ services: AppServices) {
        self.services = services
        let store = services.settings
        prefs = store.value
        tags = TripTags(load: { store.tripTagsJson }, save: { store.tripTagsJson = $0 })
        ui.lastRefresh = store.lastRefresh
        ui.photoPath = curPhoto()
        ui.serviceHistory = loadHistory()
        // These are called from whatever thread the change happens on: each one comes back to the main thread.
        store.onChange = { @Sendable [weak self] _ in Task { @MainActor in self?.syncPrefs() } }
        tags.onChange = { @Sendable [weak self] in Task { @MainActor in self?.dataVersion += 1 } }
        ChargeLog.onChange = { @Sendable [weak self] in Task { @MainActor in self?.dataVersion += 1 } }
        let address = services.address
        ChargeLog.addressLookup = { @Sendable lat, lon in await address(lat, lon) }
        decideStartPhase()
    }

    // MARK: Settings

    /** Changes settings; the screens follow at once. */
    func update(_ change: (inout AppSettings) -> Void) {
        let before = settings.value
        settings.update(change)
        let after = settings.value
        if after.language != before.language { L10n.setPreference(after.language) }
        if after.timeFormat != before.timeFormat { TimeFmt.setPreference(after.timeFormat) }
        prefs = after
    }

    private func syncPrefs() {
        let now = settings.value
        if now != prefs { prefs = now }
    }

    var remoteReady: Bool { remote.isSetUp || isDemo }
    /** Demo mode: a sample car, no account. */
    var isDemo: Bool { Demo.isOn(services.demo) }
    /** False when the carmaker has no remote-services link for this car: then setup isn't offered at all. */
    var remotePossible: Bool { api.customerId != nil || remote.isSetUp || isDemo }
    var hasPin: Bool { remote.hasPin }

    func commandLog() -> String {
        let lines = remote.log.joined(separator: "\n")
        return energyLog() + (lines.isEmpty ? "No commands sent since the app was opened." : lines)
    }

    private static func stamp(_ millis: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    /// Technical details for a bug report: app, phone, car model and the latest car data and errors. No position,
    /// names, e-mail, VIN or account ids: those are left out or blanked.
    func techDetails() -> String {
        let st = ui.status
        let s = settings.value
        var sb = "LionFlow \(services.version) (iOS)\n"
        sb += "\(services.system)\n"
        sb += "Language \(L10n.lang) · \(TimeZone.current.identifier)\n"
        if let v = ui.vehicle { sb += "Car: \(v.brand) · \(v.motorization) · model code \(v.vin.prefix(8))\n" }
        if let st = st {
            func num(_ d: Double?) -> String { d.map { JSONValue.javaDouble($0) } ?? "null" }
            let pct = st.batteryPct.map { String(JSONValue.javaInt($0)) } ?? "null"
            sb += "Energy \(st.energyType) · battery \(pct) % · capacity \(num(st.capacityKwh)) kWh · residual \(num(st.residualKwh))\n"
            sb += "Charging \(st.chargingStatus ?? "null") · \(st.chargeType ?? "null") · plugged \(st.plugged) · preheat \(st.preconditioningOn) · ignition \(st.ignition ?? "null")\n"
            sb += "Car reported \(st.updatedAtMillis.map { AppModel.stamp($0) } ?? "null")\n"
        }
        sb += "Remote control set up: \(remoteReady)\n"
        sb += "Trips: \(ui.trips.count) shown, server none-found \(tripsNone), logged from the car \(CarTrips.list().count)\n\n"
        sb += commandLog()
        return AppModel.scrub(sb)
    }

    /** Blanks anything that could point to a person or place: coordinates, VINs, e-mails, ids and tokens. */
    static func scrub(_ text: String) -> String {
        let rules: [(String, String)] = [
            ("[A-Za-z0-9_.+-]+@[A-Za-z0-9_-]+\\.[A-Za-z0-9_.]+", "[e-mail]"),
            ("\\b[A-HJ-NPR-Z0-9]{17}\\b", "[VIN]"),
            ("-?\\b[0-9]{1,3}\\.[0-9]{4,}\\b", "[pos]"),
            ("(?i)bearer\\s+\\S+", "Bearer [token]"),
            ("\\b[A-Za-z0-9_-]{24,}\\b", "[id]"),
        ]
        var out = text
        for (pattern, with) in rules {
            guard let re = try? NSRegularExpression(pattern: pattern) else { continue }
            out = re.stringByReplacingMatches(in: out, range: NSRange(location: 0, length: (out as NSString).length), withTemplate: NSRegularExpression.escapedTemplate(for: with))
        }
        return out
    }

    /** What the car reports about battery and fuel, word for word: for finding out why a level shows wrong. */
    private func energyLog() -> String {
        guard let raw = ui.status?.raw else { return "" }
        var sb = "LionFlow \(services.version) · car energy data\n"
        if let energies = raw.optJSONArray("energies") {
            for e in energies.objects() {
                let electric = e.optJSONObject("extension")?.optJSONObject("electric")
                let ch = electric?.optJSONObject("charging")
                let load = electric?.optJSONObject("battery")?.optJSONObject("load")
                func any(_ o: JSONObject, _ k: String) -> String { o.opt(k)?.javaString ?? "null" }
                sb += "\(e.optString("type")): level=\(any(e, "level")) autonomy=\(any(e, "autonomy")) updated=\(e.optString("updatedAt"))"
                if let ch = ch { sb += " plugged=\(any(ch, "plugged")) status=\(ch.optString("status"))" }
                if let load = load { sb += " capacity=\(any(load, "capacity")) residual=\(any(load, "residual"))" }
                sb += "\n"
            }
        } else {
            sb += "no energies in the answer\n"
        }
        let hist = ChargePower.history()
        if !hist.isEmpty {
            sb += "\nlast car reports (newest first):\n"
            for h in hist { sb += h + "\n" }
        }
        return sb + "\n"
    }

    // MARK: Trip tags

    func tagTrip(_ t: Trip, _ tagId: String?) {
        tags.set(t, tagId, nil)
        if tagId != nil { autoTagNow() }
    }

    func editTag(_ id: String, _ name: String, _ color: Int, _ from: TagPlace?, _ to: TagPlace?) {
        tags.edit(id, name, color, from, to)
        autoTagNow()
    }

    func setAutoTag(_ on: Bool) {
        update { $0.autoTagTrips = on }
        if on { autoTagNow() } else { tags.clearAuto() }
    }

    /** Trips are matched by distance and speed here: the iPhone app doesn't record routes. */
    private func autoTagNow() {
        if !settings.value.autoTagTrips { return }
        tags.autoTag(ui.trips, nil)
    }

    /** Makes sure trips this far back are loaded (for statistics over a longer time). */
    func ensureTrips(_ days: Int) { if days > tripsDays && !ui.tripsLoading { loadTrips(days) } }

    // MARK: Start

    private func decideStartPhase() {
        if isDemo { showDemo(); return }
        if !api.isSignedIn {
            ui.phase = .signedOut
            return
        }
        ui.vehicle = api.cachedVehicle()
        ui.vehicles = api.cachedVehicles()
        // The app is locked with the security code (or Face ID) once remote control is set up.
        ui.phase = remote.hasPin ? .locked : .ready
        if ui.phase == .ready { onUnlocked() }
    }

    func openSetting(_ key: String) { focusSetting = key; setTab(.settings) }

    func setTab(_ t: AppTab) { if Caps.tab(t, ui.status) { ui.tab = t } }

    /** Switch to another car on the account. Each car keeps its own name, charging log and range learning. */
    func selectVehicle(_ v: Vehicle) {
        let old = ui.vehicle
        if old?.vin == v.vin { return }
        if let o = old { settings.setNameFor(o.vin, settings.value.carName) }
        api.selectedVin = v.vin
        update { $0.carName = self.settings.nameFor(v.vin) ?? ("My " + Brands.current.display) }
        settings.pendingPrograms = ""
        ChargeLog.setVin(v.vin)
        ChargePower.setVin(v.vin)
        services.statusChanged(nil)
        ui.vehicle = v
        ui.status = nil
        ui.alerts = []
        ui.photoPath = photo(v.vin)
        refresh()
    }

    // MARK: Several accounts / brands

    struct GarageAccount: Equatable, Identifiable {
        var id: String
        var brand: Brand
        var signedIn: Bool
        var cars: [Vehicle]
        var selectedVin: String?
        var active: Bool
    }

    /** Every signed-in account with its cars, for the car list. */
    func garage() -> [GarageAccount] {
        let active = settings.activeAccount
        let list = settings.accounts().map { a in
            GarageAccount(id: a.id, brand: Brand.of(a.brand), signedIn: api.isSignedInTo(a.id),
                          cars: a.id == active ? ui.vehicles : api.cachedVehiclesOf(a.id), selectedVin: api.selectedVinOf(a.id), active: a.id == active)
        }.filter { $0.signedIn || $0.active }
        // The same car can be on two accounts (shared with family) or listed twice: show each car once, from the open account first.
        var seen = Set<String>()
        var out: [GarageAccount] = []
        for var acc in stableSorted(list, by: { $0.active && !$1.active }) {
            acc.cars = acc.cars.filter { seen.insert($0.vin).inserted }
            if !acc.cars.isEmpty || acc.active { out.append(acc) }
        }
        return out
    }

    /** Name of a car on any account. */
    func garageName(_ acc: GarageAccount, _ v: Vehicle) -> String {
        if acc.active && v.vin == ui.vehicle?.vin { return settings.value.carName }
        return settings.nameFor(v.vin) ?? (acc.brand.display + " …" + String(v.vin.suffix(4)))
    }

    func hasSeveralCars() -> Bool { garage().reduce(0) { $0 + $1.cars.count } > 1 }

    /** Open the car `vin` on account `id` (switching account if needed). */
    func switchTo(_ id: String, _ vin: String?) {
        if isDemo { return }
        if id == settings.activeAccount && (vin == nil || vin == ui.vehicle?.vin) { return }
        guard let target = settings.accounts().first(where: { $0.id == id }) else { return }
        if id == settings.activeAccount {
            if let v = ui.vehicles.first(where: { $0.vin == vin }) { selectVehicle(v) }
            return
        }
        stopTicker()
        // An account that was started but never signed in is dropped when leaving it.
        if !api.isSignedIn { settings.saveAccounts(settings.accounts().filter { $0.id != self.settings.activeAccount }) }
        let car = api.isSignedInTo(id) ? api.cachedVehiclesOf(id).first(where: { vin == nil || $0.vin == vin }) : nil
        if let car = car {
            activate(id, car)
        } else {
            api.switchProfile(id)
            settings.activeAccount = id
            update { $0.brand = target.brand; $0.country = target.country }
        }
        resyncFromStore()
    }

    /// The watch's "Switch car": the next car becomes the one in use, across accounts, in the order of the car list.
    /// Only what is stored changes, and the screens read it again (as when the Android watch switches car).
    func switchToNextCar() {
        if isDemo { return }
        guard let next = Garage.next(services) else { return }
        stopTicker()
        activate(next.accountId, next.vehicle)
        resyncFromStore()
    }

    /** Make a car on another account the one in use. */
    private func activate(_ accountId: String, _ car: Vehicle) {
        if let cur = api.cachedVehicle() { settings.setNameFor(cur.vin, settings.value.carName) }
        var brand = Brands.current
        if accountId != settings.activeAccount {
            remote.disconnect()
            api.switchProfile(accountId)
            settings.activeAccount = accountId
            if let acc = settings.accounts().first(where: { $0.id == accountId }) {
                brand = Brand.of(acc.brand)
                update { $0.brand = acc.brand; $0.country = acc.country }
            }
        }
        api.selectedVin = car.vin
        settings.pendingPrograms = ""
        update { $0.carName = self.settings.nameFor(car.vin) ?? ("My " + brand.display) }
        ChargeLog.setVin(car.vin)
        ChargePower.setVin(car.vin)
        services.statusChanged(nil)
    }

    /** Re-read the active car from storage (after the widget or watch switched it). */
    private func resyncFromStore() {
        stopTicker()
        var fresh = UiState()
        fresh.photoPath = curPhoto()
        fresh.serviceHistory = loadHistory()
        ui = fresh
        decideStartPhase()
    }

    /** Called when the app comes forward: another surface may have changed the car. */
    private func syncIfSwitchedElsewhere() {
        if isDemo || ui.phase == .signedOut { return }
        guard let v = api.cachedVehicle() else { return }
        if v.vin != ui.vehicle?.vin { resyncFromStore() }
    }

    /// The photo of a car: its file name is kept in the settings, and the file in the app's own folder (the
    /// folder's full path changes when the app is updated, so only the name is saved).
    private func photo(_ vin: String) -> String? {
        guard let name = settings.photoFor(vin), !name.isEmpty else { return nil }
        let file = services.filesDir.appendingPathComponent((name as NSString).lastPathComponent)
        return FileManager.default.fileExists(atPath: file.path) ? file.path : nil
    }

    private func curPhoto() -> String? { api.cachedVehicle().flatMap { photo($0.vin) } }

    /** Start signing in to another account (maybe another brand). The current one stays signed in. */
    func addAccount() {
        if isDemo { return }
        let list = settings.accounts()
        let highest = list.compactMap { $0.id.hasPrefix("a") ? Int($0.id.dropFirst()) : Int($0.id) }.max() ?? 1
        let id = "a\(highest + 1)"
        if let v = ui.vehicle { settings.setNameFor(v.vin, settings.value.carName) }
        remote.disconnect()
        stopTicker()
        let s = settings.value
        let active = settings.activeAccount
        var saved = list.map { $0.id == active ? SettingsStore.Account(id: $0.id, brand: s.brand, country: s.country) : $0 }
        saved.append(SettingsStore.Account(id: id, brand: s.brand, country: s.country))
        settings.saveAccounts(saved)
        api.switchProfile(id)
        settings.activeAccount = id
        services.statusChanged(nil)
        var fresh = UiState()
        fresh.phase = .signedOut
        fresh.photoPath = curPhoto()
        fresh.serviceHistory = loadHistory()
        ui = fresh
    }

    /** True while adding another account: lets the sign-in screen offer "back to my cars". */
    func canCancelSignIn() -> Bool {
        let active = settings.activeAccount
        return settings.accounts().contains { $0.id != active && api.isSignedInTo($0.id) }
    }

    func cancelSignIn() {
        let active = settings.activeAccount
        guard let other = settings.accounts().first(where: { $0.id != active && api.isSignedInTo($0.id) }) else { return }
        switchTo(other.id, nil)
    }

    func vehicleName(_ v: Vehicle) -> String {
        if v.vin == ui.vehicle?.vin { return settings.value.carName }
        return settings.nameFor(v.vin) ?? (Brands.current.display + " …" + String(v.vin.suffix(4)))
    }

    func mildHybridOverride(_ v: Vehicle) -> Bool? { settings.mildHybridOverride(v.vin) }

    func setMildHybridOverride(_ enabled: Bool?) {
        guard let vehicle = ui.vehicle else { return }
        if settings.mildHybridOverride(vehicle.vin) == enabled { return }
        let before = api.isMildHybrid(vehicle)
        settings.setMildHybridOverride(vehicle.vin, enabled)
        updateMildHybridStatus(reloadTrips: before != api.isMildHybrid(vehicle))
        if enabled == nil && vehicle.needsBranding && !brandingAttempts.contains(api.profile + "|" + vehicle.vin) { refresh() }
        objectWillChange.send()
    }

    private func updateMildHybridStatus(reloadTrips: Bool = true) {
        let current = ui
        guard let vehicle = current.vehicle else { return }
        if let previous = current.status {
            var status = api.parseStatus(vehicle, previous.raw)
            status.kmToService = previous.kmToService
            status.daysToService = previous.daysToService
            ui.status = status
            if !Caps.tab(ui.tab, status) { ui.tab = .home }
            services.statusChanged(status)
        }
        if reloadTrips && !current.trips.isEmpty && !current.tripsLoading { loadTrips() }
    }

    func vehicleKind(_ v: Vehicle) -> String { v.kind(settings.mildHybridOverride(v.vin)) }

    /** Reload the car list (e.g. after adding a car in the carmaker's app). */
    func reloadVehicles() {
        if ui.reloadingVehicles { return }
        ui.reloadingVehicles = true
        let profile = api.profile
        Task {
            do {
                try await self.vehicleMetadataLock.withLock {
                    if profile != self.api.profile { return }
                    let all: [Vehicle]
                    do {
                        all = try await self.api.vehicles(checkSelectedBranding: true)
                    } catch let e as ApiException {
                        if !e.noData { throw e }
                        self.toast("\(Brands.current.display) says no car is available on this account for the country you chose")
                        return
                    }
                    if profile != self.api.profile { return }
                    let vehicle = self.api.pick(all)
                    if let v = vehicle { self.brandingAttempts.insert(profile + "|" + v.vin) }
                    self.applyVehicleMetadata(all, vehicle)
                }
            } catch is CancellationError {
            } catch {
                if profile == self.api.profile { self.toast("Couldn't load your cars — \(Http.describe(error))") }
            }
            if profile == self.api.profile { self.ui.reloadingVehicles = false }
        }
    }

    private func applyVehicleMetadata(_ all: [Vehicle], _ vehicle: Vehicle?) {
        let previous = ui.vehicle
        ui.vehicles = all
        ui.vehicle = vehicle
        if let v = vehicle, let p = previous, p.vin == v.vin, vehicleKind(v) != vehicleKind(p) { updateMildHybridStatus() }
    }

    func clearMessage() { ui.message = nil }
    private func toast(_ m: String) { ui.message = m }
    func toastText(_ m: String) { toast(m) }

    // MARK: Sign in

    func authorizeUrl() -> String { api.authorizeUrl() }
    /** The address scheme the carmaker's sign-in page sends its answer to (mymap, mymacsdk …). */
    var redirectScheme: String { api.cfg.scheme }

    func handleRedirect(_ url: URL) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func param(_ name: String) -> String? { items.first { $0.name == name }?.value }
        guard let code = param("code") else {
            toast(param("error_description") ?? "Sign-in was cancelled")
            return
        }
        ui.signingIn = true
        Task {
            do {
                try await self.api.exchangeCode(code)
                do { _ = try await self.api.loadUserInfo() } catch let e as AuthExpiredException { throw e } catch {}
                // Some accounts answer "No vehicle found" (404 / 40400) when the car isn't available to third-party apps: not a failed sign-in.
                var none = false
                var all: [Vehicle] = []
                do {
                    all = try await self.api.vehicles()
                } catch let e as ApiException {
                    if !e.noData { throw e }
                    none = true
                }
                let v = self.api.pick(all)
                let cur = self.settings.activeAccount
                let accs = self.settings.accounts()
                let s = self.settings.value
                let mine = SettingsStore.Account(id: cur, brand: s.brand, country: s.country)
                self.settings.saveAccounts(accs.contains { $0.id == cur } ? accs.map { $0.id == cur ? mine : $0 } : accs + [mine])
                if let v = v {
                    ChargeLog.setVin(v.vin)
                    ChargePower.setVin(v.vin)
                }
                var next = self.ui
                next.vehicle = v
                next.vehicles = all
                next.signingIn = false
                next.phase = .setupRemote
                next.photoPath = v.flatMap { self.photo($0.vin) }
                self.ui = next
                if v == nil {
                    self.toast(none
                        ? "Signed in, but \(Brands.current.display) says no car is available on this account for the country you chose. If the car shows in \(Brands.current.app), check that the country matches the one in that app. Some newer cars can't be reached by other apps yet."
                        : "No car found on this account — add it in the MyPeugeot app first")
                }
            } catch {
                self.ui.signingIn = false
                self.toast("Sign-in failed — \(Http.describe(error))")
            }
        }
    }

    func requestSms() {
        ui.busy = true
        ui.setupError = nil
        Task {
            do {
                if self.api.customerId == nil {
                    _ = try? await self.api.loadUserInfo()
                    if self.api.customerId == nil {
                        self.ui.busy = false
                        self.ui.setupError = AppModel.noRemote
                        return
                    }
                }
                try await self.api.requestSmsCode()
                self.ui.busy = false
                self.ui.smsSent = true
            } catch {
                let code = (error as? ApiException)?.code
                self.ui.busy = false
                self.ui.setupError = (code == 400 || code == 403) ? AppModel.noRemote : "Couldn't send the SMS — \(Http.describe(error))"
            }
        }
    }

    func activateRemote(_ sms: String, _ pin: String) {
        ui.busy = true
        ui.setupError = nil
        Task {
            do {
                try await self.remote.activate(sms, pin)
                self.ui.busy = false
                self.ui.phase = .ready
                self.toast("Remote control is ready")
                self.onUnlocked()
            } catch {
                self.ui.busy = false
                self.ui.setupError = "Activation failed — \(Http.describe(error))"
            }
        }
    }

    func skipRemoteSetup() {
        ui.phase = .ready
        onUnlocked()
    }

    // MARK: Demo

    /// Try the app without an account: a sample car with made-up data. `kind` is the sample car to show:
    /// "electric", "hybrid" (plug-in) or "fuel" (petrol or diesel). `brand` is the brand id whose names the app
    /// uses while the demo runs (nil keeps the one already picked).
    func startDemo(_ kind: String = "electric", _ brand: String? = nil) {
        Demo.setOn(services.demo, true)
        Demo.choose(services.demo, kind)
        Demo.reset()
        settings.demoPrevName = settings.value.carName
        update {
            $0.carName = "Demo car"
            if let b = brand { $0.brand = b }
        }
        showDemo()
    }

    private func showDemo() {
        Demo.load(services.demo)
        ChargeLog.enterDemo(services.demoLog, Demo.VIN, Demo.sessions(), Demo.places())
        let now = nowMillis()
        try? FileManager.default.removeItem(at: services.cacheDir.appendingPathComponent("demo_photo.jpg"))
        let car = Demo.vehicle
        var next = ui
        next.phase = .ready
        next.photoPath = nil
        next.vehicle = car
        next.vehicles = [car]
        next.status = Demo.status()
        next.trips = Demo.trips()
        next.lastRefresh = now
        next.now = now
        ui = next
        startTicker()
        loadLocationAndWeather()
    }

    func exitDemo() {
        Demo.setOn(services.demo, false)
        ChargeLog.exitDemo()
        let prev = settings.demoPrevName
        update { $0.carName = prev.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "My " + Brands.current.display : prev }
        resyncFromStore()
    }

    func openRemoteSetup() {
        ui.phase = .setupRemote
        ui.smsSent = false
        ui.setupError = nil
    }

    func signOut() {
        if isDemo { exitDemo(); return }
        remote.forget()
        api.signOut()
        settings.clearPersonal()
        services.statusChanged(nil)
        stopTicker()
        // Other accounts stay signed in: go to one of them, if there is one.
        let gone = settings.activeAccount
        let rest = settings.accounts().filter { $0.id != gone }
        if !rest.isEmpty {
            settings.saveAccounts(rest)
            let next = rest.first { self.api.isSignedInTo($0.id) } ?? rest[0]
            api.switchProfile(next.id)
            settings.activeAccount = next.id
            update { $0.brand = next.brand; $0.country = next.country }
            var fresh = UiState()
            fresh.photoPath = curPhoto()
            fresh.serviceHistory = loadHistory()
            ui = fresh
            if let v = api.cachedVehicle() {
                update { $0.carName = self.settings.nameFor(v.vin) ?? ("My " + Brand.of(next.brand).display) }
                ChargeLog.setVin(v.vin)
                ChargePower.setVin(v.vin)
            }
            decideStartPhase()
            return
        }
        var fresh = UiState()
        fresh.phase = .signedOut
        fresh.photoPath = curPhoto()
        fresh.serviceHistory = loadHistory()
        ui = fresh
    }

    // MARK: Lock

    func checkPin(_ pin: String) -> Bool { remote.checkPin(pin) }

    func unlock() {
        ui.phase = .ready
        onUnlocked()
    }

    private func onUnlocked() {
        maybeAutoRefresh(force: ui.status == nil)
        warmUpRemote()
        startTicker()
        loadLocationAndWeather()
    }

    // MARK: Refresh

    /** Called when the app comes to the foreground. */
    func onForeground() {
        syncIfSwitchedElsewhere()
        if ui.phase == .ready {
            maybeAutoRefresh(force: false)
            startTicker()
            warmUpRemote()
        }
    }

    private func warmUpRemote() {
        if !remote.isSetUp { return }
        Task {
            await self.remote.warmUp()
            // If the car looks asleep, start waking it now so the first command is quick.
            let st = self.ui.status
            var stale = true
            if let at = st?.updatedAtMillis { stale = nowMillis() - at > 5 * 60_000 }
            if self.settings.value.wakeOnOpen && stale && st?.moving != true {
                _ = await self.remote.preWake(minIntervalMs: 10 * 60_000, onAwake: { @Sendable in self.refreshIn(4_000) })
            }
        }
    }

    /** Reads the car again after a pause. Safe to call from any thread. */
    nonisolated private func refreshIn(_ millis: UInt64) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: millis * 1_000_000)
            self.refresh()
        }
    }

    /** Called the moment a remote-action button is tapped, before Face ID or the code confirms it. */
    func prewakeForAction() {
        if remote.isSetUp { Task { _ = await self.remote.preWake(minIntervalMs: 2 * 60_000) } }
    }

    func onBackground() {
        stopTicker()
        if ui.commandInProgress == nil {
            let remote = self.remote
            Task.detached { remote.disconnect() }
        }
    }

    private func stopTicker() {
        ticker?.cancel()
        ticker = nil
    }

    private func startTicker() {
        if ticker != nil { return }
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                guard let self = self, !Task.isCancelled else { return }
                self.ui.now = nowMillis()
                self.maybeAutoRefresh(force: false)
            }
        }
    }

    /** At most once per 5 minutes, and only while the app is open. */
    private func maybeAutoRefresh(force: Bool) {
        let since = nowMillis() - settings.lastRefresh
        if force || (settings.value.autoRefresh && since >= 5 * 60_000) { refresh() }
    }

    private func refreshVehicleBranding(_ vehicle: Vehicle, _ profile: String) async throws -> Vehicle {
        try await vehicleMetadataLock.withLock {
            let selected = self.ui.vehicle
            if profile != self.api.profile || (selected != nil && selected!.vin != vehicle.vin) { return vehicle }
            let current = selected ?? vehicle
            if !current.needsBranding || self.settings.mildHybridOverride(current.vin) != nil
                || !self.brandingAttempts.insert(profile + "|" + current.vin).inserted { return current }
            let all: [Vehicle]
            do {
                all = try await self.api.vehicles(checkSelectedBranding: true)
            } catch let e as AuthExpiredException {
                throw e
            } catch let e as CancellationError {
                throw e
            } catch {
                return current
            }
            let active = self.ui.vehicle
            if profile != self.api.profile || (active != nil && active!.vin != current.vin) { return current }
            let updated = all.first { $0.vin == current.vin } ?? current
            self.applyVehicleMetadata(all, updated)
            return updated
        }
    }

    /// The refresh button: reads the carmaker's server now, and (with remote control set up) asks the car itself
    /// for a fresh report, then reads again when it has answered. This is the manual way to wake the car; "Wake
    /// car when app opens" in Settings does the same by itself.
    func refreshNow() {
        refresh()
        if isDemo || !remote.isSetUp || ui.status?.moving == true { return }
        Task { _ = await self.remote.preWake(minIntervalMs: 60_000, force: true, onAwake: { @Sendable in self.refreshIn(4_000) }) }
    }

    /** Warnings from the car: optional, so a failure here never disturbs the refresh. At most every 10 minutes. */
    private func loadAlerts(_ v: Vehicle) {
        let now = nowMillis()
        if alertsOff.contains(v.vin) || (now - alertsAt < 10 * 60_000 && v.vin == alertsVin) { return }
        alertsAt = now
        alertsVin = v.vin
        let profile = api.profile
        Task {
            do {
                let list = CarAlert.parse(try await self.api.alerts(v))
                if profile == self.api.profile && self.ui.vehicle?.vin == v.vin { self.ui.alerts = list }
            } catch is CancellationError {
            } catch {
                self.remote.addLog("warnings: " + Http.describe(error))
                // A car or account the server refuses this for isn't asked again until the app restarts.
                if let e = error as? ApiException, (400...499).contains(e.code) { self.alertsOff.insert(v.vin) }
            }
        }
    }

    /** For the background refresh (the home-screen widget): reads the car once and waits until that is done. */
    func refreshInBackground() async {
        if isDemo || ui.phase != .ready || ui.refreshing { return }
        ui.refreshing = true
        inBackground = true
        await readCar()
        inBackground = false
    }

    /// While reading the car in the background a failed sign-in check never signs out: the app tries again when it
    /// is opened (signing out throws the saved sign-in away for good).
    private var inBackground = false

    func refresh() {
        if ui.refreshing { return }
        if isDemo {
            let now = nowMillis()
            var next = ui
            next.status = Demo.status()
            next.lastRefresh = now
            next.now = now
            ui = next
            return
        }
        ui.refreshing = true
        Task { await self.readCar() }
    }

    private func readCar() async {
        let profile = api.profile
        defer { if ui.refreshing && api.profile == profile { ui.refreshing = false } }
        do {
            var cached = ui.vehicle
            if cached == nil { cached = try await api.currentVehicle() }
            guard let first = cached else { throw AppError("No car on this account") }
            let v = try await refreshVehicleBranding(first, profile)
            if profile != api.profile || (ui.vehicle != nil && ui.vehicle!.vin != v.vin) { return }
            var missing = false
            var raw: JSONObject
            do {
                raw = try await api.status(v)
            } catch let e as ApiException {
                if !e.noData { throw e }
                missing = true
                remote.addLog("status: the server has no live status for this car (\(e.code))")
                // No live status for this car: carry on with just what we know (trips, service) so the app is still usable.
                raw = api.blankStatus(v)
            }
            var maint: JSONObject? = nil
            do {
                maint = try await api.maintenance(v)
            } catch let e as ApiException {
                if !missing { throw e }
            }
            // Reloading the cars can finish while the status request is on its way.
            let selected = ui.vehicle ?? v
            if profile != api.profile || selected.vin != v.vin { return }
            let st = applyHolds(ChargePower.record(api.parseStatus(selected, raw, maint)))
            let now = nowMillis()
            settings.lastRefresh = now
            serverRetries = 0
            services.statusChanged(st)
            var next = ui
            next.vehicle = selected
            next.status = st
            next.statusMissing = missing
            next.refreshing = false
            next.lastRefresh = now
            next.now = now
            if !Caps.tab(next.tab, st) { next.tab = .home }
            ui = next
            if ui.trips.isEmpty && !ui.tripsLoading { loadTrips() }
            if !missing { loadAlerts(selected) }
        } catch is CancellationError {
        } catch is AuthExpiredException {
            if profile != api.profile { return }
            ui.refreshing = false
            if inBackground { return }
            toast("Your Peugeot session expired — please sign in again")
            signOut()
        } catch {
            if profile != api.profile { return }
            ui.refreshing = false
            let msg = Http.describe(error)
            remote.addLog("refresh failed: \(msg)")
            if let e = error as? ApiException, (502...504).contains(e.code) {
                // The brand's servers are down or busy: say so once in plain words and try again by itself.
                if serverRetries == 0 { toast(tr("Peugeot's servers are down or busy right now (error \(e.code)). Your car is fine. LionFlow will try again in a minute.")) }
                if serverRetries < 3 {
                    serverRetries += 1
                    refreshIn(60_000 * UInt64(serverRetries))
                }
            } else {
                toast("Couldn't update — \(msg)")
            }
        }
    }

    // MARK: Remote commands

    private func applyHolds(_ st: VehicleStatus) -> VehicleStatus {
        let now = nowMillis()
        var out = st
        if let h = holdPrecond {
            if now > h.until || st.preconditioningOn == h.want { holdPrecond = nil } else { out.preconditioningOn = h.want }
        }
        if let h = holdLock {
            if h.holds(st, now: now) { out.locked = h.want } else { holdLock = nil }
        }
        return out
    }

    private func refreshSoon() {
        let pauses = refreshSoonMillis
        Task {
            for d in pauses {
                try? await Task.sleep(nanoseconds: d * 1_000_000)
                self.refresh()
            }
        }
    }

    func runCommand(_ label: String, optimistic: @escaping @MainActor (VehicleStatus) -> VehicleStatus = { $0 },
                    _ block: @escaping @MainActor (Tracker) async -> CommandResult) {
        if isDemo {
            // Simulated: nothing is sent, the sample car just changes.
            if ui.commandInProgress != nil { return }
            ui.commandInProgress = label
            ui.commandProgress = "Sending…"
            Task {
                try? await Task.sleep(nanoseconds: self.demoCommandNanos)
                Demo.apply(label)
                self.holdPrecond = nil
                self.holdLock = nil
                var next = self.ui
                next.commandInProgress = nil
                next.commandProgress = nil
                next.status = next.status.map { optimistic($0) }
                self.ui = next
                self.toast("\(label) — done (demo)")
            }
            return
        }
        if !remote.isSetUp {
            toast("Set up remote control first (Settings › Security)")
            return
        }
        if ui.commandInProgress != nil { return }
        ui.commandInProgress = label
        ui.commandProgress = "Sending…"
        Task {
            let tracker = Tracker(
                onProgress: { @Sendable p in Task { @MainActor in if self.ui.commandInProgress == label { self.ui.commandProgress = p } } },
                // Arrives later only when we stopped waiting at "delivered".
                onFinal: { @Sendable r in
                    Task { @MainActor in
                        if case .failed(let reason) = r {
                            self.holdPrecond = nil
                            self.holdLock = nil
                            self.remote.addLog("\(label) failed: \(reason)")
                            self.toast("\(label) didn't go through: \(reason)")
                        }
                        self.refreshIn(2_000)
                    }
                })
            let r = await block(tracker)
            switch r {
            case .success, .delivered:
                // Show the result straight away; the real status follows.
                var next = self.ui
                next.commandInProgress = nil
                next.commandProgress = nil
                next.status = next.status.map { optimistic($0) }
                self.ui = next
                self.toast(r == .delivered ? "\(label) — the car got it" : "\(label) — done")
                self.refreshSoon()
            case .failed(let reason):
                self.holdPrecond = nil
                self.holdLock = nil
                self.ui.commandInProgress = nil
                self.ui.commandProgress = nil
                self.remote.addLog("\(label) failed: \(reason)")
                self.toast("\(label): \(reason)")
            }
        }
    }

    func lockCar(_ lock: Bool) {
        runCommand(lock ? "Lock" : "Unlock", optimistic: { st in
            self.holdLock = LockHold(want: lock, since: nowMillis())
            var s = st
            s.locked = lock
            return s
        }) { p in await self.remote.lock(lock, p) }
    }

    func horn() { runCommand("Horn") { p in await self.remote.horn(p) } }
    func lights() { runCommand("Flash lights") { p in await self.remote.lights(p) } }

    func preconditioning(_ on: Bool) {
        // Plug-in hybrids only pre-heat on the battery, and the car ignores the request below 50 % (as in the official app).
        if on && !isDemo, let st = ui.status, st.energyType == "Hybrid", (st.batteryPct ?? 100.0) < 50, !st.plugged {
            toast(tr("Plug-in hybrids can only pre-heat with at least 50 % battery. Charge the car first."))
            return
        }
        runCommand(on ? "Pre-heating" : "Stop pre-heating", optimistic: { st in
            self.holdPrecond = (on, nowMillis() + 300_000)
            var s = st
            s.preconditioningOn = on
            return s
        }) { p in
            guard let st = self.ui.status else { return .failed("No status yet") }
            return await self.remote.preconditioning(on, st.programsJson(), p)
        }
    }

    /** Saves pre-heating schedules to the car (its 4 program slots), keeping pre-heating on/off as it is. */
    func savePrograms(_ programs: [Program]) {
        let shown: @MainActor (VehicleStatus) -> VehicleStatus = { st in
            var s = st
            s.programs = programs
            return s
        }
        if isDemo {
            Demo.apply("", programs)
            runCommand("Saving schedule", optimistic: shown) { _ in .success }
            return
        }
        runCommand("Saving schedule", optimistic: shown) { p in
            guard let st = self.ui.status else { return .failed("No status yet") }
            let r = await self.remote.preconditioning(st.preconditioningOn, VehicleStatus.programsJsonOf(programs), p)
            if case .failed = r {} else { ChargePower.setPendingPrograms(programs) }
            return r
        }
    }

    /** The schedules as the user set them. */
    func userPrograms(_ st: VehicleStatus?) -> [Program] { st?.programs ?? [] }

    func updateParkedPlace(_ lat: Double?, _ lon: Double?) {
        guard let lat = lat, let lon = lon else {
            if parkedAt != nil { parkedAt = nil }
            return
        }
        func show(_ text: String?) { if parkedAt != text { parkedAt = text } }
        let cfg = ChargeLog.places
        if let h = cfg.home, let hLat = h.lat, let hLon = h.lon, ChargeLog.distanceM(lat, lon, hLat, hLon) <= 50 {
            show("@Home")
            return
        }
        for k in cfg.known {
            if let kLat = k.lat, let kLon = k.lon, ChargeLog.distanceM(lat, lon, kLat, kLon) <= 75 {
                show("@" + (k.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Charger" : k.name))
                return
            }
        }
        let key = Fmt.fixed(lat, 4) + "," + Fmt.fixed(lon, 4)   // about 10 m
        if let known = addressCache[key] {
            show(known)
            return
        }
        if key == parkedKey { return }
        parkedKey = key
        show(nil)
        let lookup = services.address
        Task {
            if let a = await lookup(lat, lon) {
                self.addressCache[key] = a
                if self.parkedKey == key { self.parkedAt = a }
            } else if self.parkedKey == key {
                self.parkedKey = ""
            }
        }
    }

    /** True while schedules we sent haven't shown up in the carmaker's status yet. */
    func programsPending() -> Bool { ChargePower.pendingPrograms() != nil }

    /** The car's own limit: daily (stops at the limit set in the car) or trip (charge to 100 %). */
    func setCarChargeLimit(_ daily: Bool) {
        guard let status = ui.status, Caps.battery(status) else { return }
        runCommand(daily ? "Daily charge limit" : "Full charge for a trip", optimistic: { st in
            var s = st
            s.chargeType = daily ? "Partial" : "Full"
            return s
        }) { p in await self.remote.chargeLimit(daily, p) }
    }

    func charging(_ startNow: Bool) {
        guard let status = ui.status, Caps.battery(status) else { return }
        runCommand(startNow ? "Charge now" : "Stop charging", optimistic: { st in
            var s = st
            s.chargingStatus = startNow ? "InProgress" : "Stopped"
            return s
        }) { p in
            let st = self.ui.status
            return await self.remote.charging(startNow, st?.nextDelayedHour ?? 1, st?.nextDelayedMinute ?? 0, p)
        }
    }

    /// Turns off the car's scheduled (delayed) start, so it charges as soon as it is plugged in. "Stop charging"
    /// works by setting that scheduled start, so it can stay on afterwards.
    func clearScheduledStart() {
        runCommand("Turn off scheduled start") { p in
            let st = self.ui.status
            return await self.remote.charging(true, st?.nextDelayedHour ?? 1, st?.nextDelayedMinute ?? 0, p)
        }
    }

    func wakeUp() { runCommand("Wake car") { p in await self.remote.wakeUp(p) } }

    // MARK: Location, weather, chargers

    func loadLocationAndWeather() {
        let loc = services.lastKnownLocation()
        let st = ui.status
        guard let lat = loc?.lat ?? st?.lat, let lon = loc?.lon ?? st?.lon else { return }
        ui.myLat = loc?.lat
        ui.myLon = loc?.lon
        let forecast = services.weather
        Task {
            if let w = try? await forecast(lat, lon) { self.ui.weather = w }
        }
        loadChargers(lat, lon)
    }

    private var chargerCache: URL { services.cacheDir.appendingPathComponent("chargers.json") }

    /** Saved chargers for this area (within about 3 km), so the map fills at once and survives busy servers. */
    private func cachedChargers(_ src: String, _ lat: Double, _ lon: Double, _ maxAgeMs: Int64) -> [Charger]? {
        guard let text = try? String(contentsOf: chargerCache, encoding: .utf8), let j = try? JSONObject(text) else { return nil }
        guard let jLat = j.num("lat"), let jLon = j.num("lon"), j.has("time"), let items = j.optJSONArray("items") else { return nil }
        let near = abs(jLat - lat) < 0.03 && abs(jLon - lon) < 0.05
        if j.optString("src") == src && near && nowMillis() - j.optLong("time") < maxAgeMs {
            return items.objects().map { Charger.fromJson($0) }
        }
        return nil
    }

    private func saveChargers(_ src: String, _ lat: Double, _ lon: Double, _ list: [Charger]) {
        let items = JSONArray()
        for c in list { items.put(c.toJson()) }
        let j = JSONObject()
        j.put("src", src)
        j.put("lat", lat)
        j.put("lon", lon)
        j.put("time", nowMillis())
        j.put("items", items)
        try? j.toString().write(to: chargerCache, atomically: true, encoding: .utf8)
    }

    func loadChargers(_ lat: Double? = nil, _ lon: Double? = nil) {
        let src = settings.value.chargerSource
        if src == "nobil" {
            ui.chargerError = "NOBIL isn't available in this build — switch to OpenStreetMap"
            return
        }
        guard let la = lat ?? ui.myLat ?? ui.status?.lat, let lo = lon ?? ui.myLon ?? ui.status?.lon else { return }
        // Fresh enough (6 h): no network at all.
        if let list = cachedChargers(src, la, lo, 6 * 3_600_000) {
            ui.chargers = list
            ui.chargerError = nil
            return
        }
        // Show older saved chargers right away while fetching.
        if let list = cachedChargers(src, la, lo, 30 * 86_400_000) { ui.chargers = list }
        let near = services.chargersNear
        Task {
            do {
                let list = try await near(la, lo)
                if !list.isEmpty { self.saveChargers(src, la, lo, list) }
                self.ui.chargers = list
                self.ui.chargerError = list.isEmpty ? "No chargers found nearby" : nil
            } catch {
                let haveOld = !self.ui.chargers.isEmpty
                let short: String
                if error is BusyException { short = "Charger servers are busy" } else if error is URLError { short = "No connection" } else { short = "Couldn't load chargers" }
                self.remote.addLog("chargers failed: \(Http.describe(error))")
                self.ui.chargerError = haveOld ? "\(short) — showing saved chargers" : "\(short) — try again in a minute"
            }
        }
    }

    // MARK: Trips

    func loadTrips(_ daysAsked: Int = 30) {
        let days = min(max(daysAsked, tripsDays), 1100)
        tripsDays = days
        if isDemo {
            ui.trips = Demo.trips()
            ui.tripsLoading = false
            return
        }
        guard let v = ui.vehicle else { return }
        ui.tripsLoading = true
        Task {
            do {
                let res = try await self.api.allTrips(v, nowMillis() - Int64(days) * 86_400_000)
                let vehicle = self.ui.vehicle.flatMap { $0.vin == v.vin ? $0 : nil } ?? v
                let all = self.api.parseTrips(vehicle, res.trips)
                self.tripsNone = res.noneFound
                if res.noneFound { self.remote.addLog("trips: the server says no trips were found") }
                let newest = all.map { $0.startMillis ?? 0 }.max() ?? 0
                if newest > 0 { self.remote.addLog("trips: newest from Peugeot " + AppModel.stamp(newest)) }
                self.remote.addLog("trips: \(all.count) from the last \(days) days")
                let merged = AppModel.newestFirst(self.withCarTrips(AppModel.distinct(all + self.loadImportedTrips())))
                self.ui.trips = merged
                self.ui.tripsLoading = false
                self.autoTagNow()
            } catch {
                self.ui.tripsLoading = false
                self.ui.trips = AppModel.newestFirst(self.withCarTrips(self.loadImportedTrips()))
                self.toast("Couldn't load trips — \(Http.describe(error))")
            }
        }
    }

    private static func distinct(_ list: [Trip]) -> [Trip] {
        var seen = Set<String>()
        return list.filter { seen.insert($0.id).inserted }
    }

    private static func newestFirst(_ list: [Trip]) -> [Trip] {
        stableSorted(list, by: { ($0.startMillis ?? 0) > ($1.startMillis ?? 0) })
    }

    /** The trips as a file anyone can open in a spreadsheet. */
    func exportTripsCsv() throws -> URL {
        let dir = services.cacheDir.appendingPathComponent("exports", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("lionflow-trips.csv")
        var sb = "id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag\n"
        for t in ui.trips {
            let name = tags.tagOf(t.id)?.name ?? ""
            let cells: [String] = [
                t.id,
                t.startMillis.map { AppModel.stamp($0) } ?? "",
                t.endMillis.map { AppModel.stamp($0) } ?? "",
                Fmt.fixed(t.distanceKm, 1),
                t.durationSec.map { Fmt.fixed($0 / 60, 0) } ?? "",
                t.kwh.map { Fmt.fixed($0, 2) } ?? "",
                t.avgKwhPer100.map { Fmt.fixed($0, 1) } ?? "",
                t.avgSpeedKmh.map { Fmt.fixed($0, 0) } ?? "",
                AppModel.oneCell(name),
            ]
            sb += cells.joined(separator: ",") + "\n"
        }
        try sb.write(to: file, atomically: true, encoding: .utf8)
        return file
    }

    /** A name as one cell of a CSV line: commas, quotes and line breaks become spaces. */
    static func oneCell(_ text: String) -> String {
        var out = String.UnicodeScalarView()
        for u in text.unicodeScalars { out.append((u == "," || u == "\r" || u == "\n" || u == "\"") ? " " : u) }
        return String(out)
    }

    /** Imports trips from a CSV or .myp file (LionFlow's own, or the official app's export). */
    func importTripsCsv(_ text: String) {
        let rows = (try? TripImport.parse(text)) ?? []
        if rows.isEmpty {
            toast(tr("Couldn't read any trips from that file. Send it to me on GitHub and I'll add support for it."))
            return
        }
        let arr = (try? JSONArray(settings.importedTripsJson)) ?? JSONArray()
        var have = Set(arr.objects().map { $0.optString("id") })
        var count = 0
        var tagged: [String: String] = [:]
        for r in rows {
            let id = r.json.optString("id")
            if have.contains(id) { continue }
            have.insert(id)
            arr.put(r.json)
            count += 1
            if let tag = r.tag { tagged[id] = tag }
        }
        settings.importedTripsJson = arr.toString()
        if !tagged.isEmpty {
            for t in loadImportedTrips() {
                guard let name = tagged[t.id] else { continue }
                let tag = tags.byName(name) ?? tags.create(name, tags.data.tags.count % TripTags.colors)
                tags.set(t, tag.id)
            }
        }
        toast("Imported \(count) trips")
        ui.trips = AppModel.newestFirst(AppModel.distinct(ui.trips + loadImportedTrips()))
    }

    /** Adds the trips LionFlow logged from the car's own reports, where the carmaker's list or an import has none at that time. */
    private func withCarTrips(_ list: [Trip]) -> [Trip] {
        let own = CarTrips.list()
        if own.isEmpty { return list }
        let slack: Int64 = 20 * 60_000
        let extra = own.filter { o in
            guard let a = o.startMillis else { return false }
            let b = o.endMillis ?? a
            return !list.contains { t in
                guard let ta = t.startMillis else { return false }
                let tb = t.endMillis ?? ta
                return ta <= b + slack && tb >= a - slack
            }
        }
        return list + extra
    }

    private func loadImportedTrips() -> [Trip] {
        guard let a = try? JSONArray(settings.importedTripsJson) else { return [] }
        return a.objects().map { CarTrips.importedTrip($0) }
    }

    // MARK: Service history and photo

    private func loadHistory() -> [ServiceEntry] {
        guard let a = try? JSONArray(services.settings.serviceHistoryJson) else { return [] }
        let list = a.objects().map { ServiceEntry(title: $0.optString("title"), note: $0.optString("note"), dateMillis: $0.optLong("date")) }
        return stableSorted(list, by: { $0.dateMillis > $1.dateMillis })
    }

    private func saveHistory(_ list: [ServiceEntry]) {
        let a = JSONArray()
        for e in list {
            let j = JSONObject()
            j.put("title", e.title)
            j.put("note", e.note)
            j.put("date", e.dateMillis)
            a.put(j)
        }
        settings.serviceHistoryJson = a.toString()
        ui.serviceHistory = loadHistory()
    }

    func addServiceEntry(_ title: String, _ note: String) {
        saveHistory(loadHistory() + [ServiceEntry(title: title, note: note, dateMillis: nowMillis())])
    }

    func deleteServiceEntry(_ e: ServiceEntry) {
        saveHistory(loadHistory().filter { $0 != e })
    }

    /** Keeps a photo of the car (JPEG data) on this phone. */
    func savePhoto(_ jpeg: Data) {
        // Demo mode gets its own throw-away photo; the real one is never touched or shown.
        let vin = ui.vehicle?.vin
        let name = isDemo ? "demo_photo.jpg" : "car_photo_\(vin ?? "x").jpg"
        let file = (isDemo ? services.cacheDir : services.filesDir).appendingPathComponent(name)
        do {
            try jpeg.write(to: file, options: .atomic)
            if !isDemo, let vin = vin { settings.setPhotoFor(vin, name) }
            ui.photoPath = file.path + "?" + String(nowMillis())
        } catch {
            toast("Couldn't save the photo")
        }
    }
}
