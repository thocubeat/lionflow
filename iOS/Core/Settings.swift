import Foundation

struct AppSettings: Equatable {
    var theme = "dark"                // dark | light | system
    var distanceUnit = "km"           // km | mi
    var tempUnit = "c"                // c | f
    var autoRefresh = true
    /** Tag trips like ones the user tagged before (same distance and speed). */
    var autoTagTrips = false
    var autoTagRoute = false
    var bioUnlock = true
    var remoteConfirm = true
    var remoteBioOnly = true
    var rememberMe = true
    var country = "NO"
    var brand = "peugeot"             // peugeot | citroen | ds | opel | vauxhall
    var nobilKey = ""
    var notifyChargeDone = true
    var notifyChargeStopped = true
    var notifyPreheat = true
    var notifyUnlocked = true
    var notifyLowBattery = true
    var notifyService = false
    var carName = "My Peugeot"
    var wakeOnOpen = true
    /** Smart pre-heat schedule: "" = off, "below" = only when colder than smartTemp, "above" = only when warmer. */
    var smartMode = ""
    var smartTemp = 0
    var smartPlugged = false
    var bgWakeHours = 0
    var autoUpdate = true
    var chargerSource = "osm"         // osm (free, no key) | nobil (needs key)
    var language = "system"
    var timeFormat = "system"         // system | 24 | 12
    var carActions = true
    var carVideo = true
    var carVideoSync = true
    var carPlain = false
    var chargeLimitOn = false         // LionFlow stops charging at chargeLimitPct
    var chargeLimitPct = 80
    /** Car is set to charge to 100 % while LionFlow stops it at chargeLimitPct. */
    var chargeForce = false
    /** Only charge between two times (minutes after midnight). */
    var chargeWindowOn = false
    var chargeWindowFrom = 22 * 60
    var chargeWindowTo = 6 * 60
    var oilChangeMillis: Int64 = 0    // when the engine oil was last changed (plug-in hybrids): counts engine km since
    var serviceKm = 0                 // service interval in km; 0 = default for the car type
    var serviceMonths = 0             // service interval in months; 0 = default
    var routeTracking = false
    var routeBtAddr = ""
    var routeBtName = ""
    var batteryKwh = 0.0              // usable battery size for kW maths; 0 = use what the car reports (else 73)
    /** Home screens (see HomeLayout): the phone's cards in the user's order, and what is hidden on each screen. */
    var homeOrder = ""
    var homeHidden = HomeLayout.phoneHidden
    var watchHidden = HomeLayout.watchHidden
    var carHidden = HomeLayout.carHidden
    /** Notes at the top of Home: the car is being driven, privacy mode is on, fog lights left on. */
    var bannerDriving = true
    var bannerPrivacy = true
    var bannerFog = true
    var fuelPrice = 0.0               // price per litre, in the currency used for charging costs; 0 = not set
    var tankLiters = 0.0              // tank size for fill-up litres; 0 = worked out from the fuel gauge

    // Each setting once, by the name it is saved under.
    private static let strings: [(String, WritableKeyPath<AppSettings, String>)] = [
        ("theme", \.theme), ("dist", \.distanceUnit), ("temp", \.tempUnit), ("country", \.country), ("brand", \.brand), ("nobil", \.nobilKey),
        ("carName", \.carName), ("smartMode", \.smartMode), ("chargerSource", \.chargerSource), ("language", \.language), ("timeFormat", \.timeFormat),
        ("routeBtAddr", \.routeBtAddr), ("routeBtName", \.routeBtName), ("homeOrder", \.homeOrder), ("homeHidden", \.homeHidden),
        ("watchHidden", \.watchHidden), ("carHidden", \.carHidden),
    ]
    private static let bools: [(String, WritableKeyPath<AppSettings, Bool>)] = [
        ("auto", \.autoRefresh), ("autoTagTrips", \.autoTagTrips), ("autoTagRoute", \.autoTagRoute), ("bio", \.bioUnlock), ("remoteConfirm", \.remoteConfirm),
        ("remoteBio", \.remoteBioOnly), ("remember", \.rememberMe), ("nDone", \.notifyChargeDone), ("nStop", \.notifyChargeStopped), ("nHeat", \.notifyPreheat),
        ("nLock", \.notifyUnlocked), ("nLow", \.notifyLowBattery), ("nService", \.notifyService), ("wakeOnOpen", \.wakeOnOpen), ("smartPlugged", \.smartPlugged),
        ("autoUpdate", \.autoUpdate), ("carActions", \.carActions), ("carVideo", \.carVideo), ("carVideoSync", \.carVideoSync), ("carPlain", \.carPlain),
        ("chargeLimitOn", \.chargeLimitOn), ("chargeForce", \.chargeForce), ("chargeWindowOn", \.chargeWindowOn), ("routeTracking", \.routeTracking),
        ("bannerDriving", \.bannerDriving), ("bannerPrivacy", \.bannerPrivacy), ("bannerFog", \.bannerFog),
    ]
    private static let ints: [(String, WritableKeyPath<AppSettings, Int>)] = [
        ("smartTemp", \.smartTemp), ("bgWakeHours", \.bgWakeHours), ("chargeLimitPct", \.chargeLimitPct), ("chargeWindowFrom", \.chargeWindowFrom),
        ("chargeWindowTo", \.chargeWindowTo), ("serviceKm", \.serviceKm), ("serviceMonths", \.serviceMonths),
    ]
    private static let doubles: [(String, WritableKeyPath<AppSettings, Double>)] = [
        ("batteryKwh", \.batteryKwh), ("fuelPrice", \.fuelPrice), ("tankLiters", \.tankLiters),
    ]

    init() {}

    /** Reads saved settings; anything missing keeps its default, so a setting added later needs no migration. */
    init(_ j: JSONObject) {
        for (k, kp) in AppSettings.strings where j.has(k) && !j.isNull(k) { self[keyPath: kp] = j.optString(k) }
        for (k, kp) in AppSettings.bools where j.has(k) { self[keyPath: kp] = j.optBoolean(k, self[keyPath: kp]) }
        for (k, kp) in AppSettings.ints where j.has(k) { self[keyPath: kp] = j.optInt(k, self[keyPath: kp]) }
        for (k, kp) in AppSettings.doubles where j.has(k) { self[keyPath: kp] = j.optDouble(k, self[keyPath: kp]) }
        if j.has("oilChangeMillis") { oilChangeMillis = j.optLong("oilChangeMillis", 0) }
    }

    func toJSON() -> JSONObject {
        let j = JSONObject()
        for (k, kp) in AppSettings.strings { j.put(k, self[keyPath: kp]) }
        for (k, kp) in AppSettings.bools { j.put(k, self[keyPath: kp]) }
        for (k, kp) in AppSettings.ints { j.put(k, self[keyPath: kp]) }
        for (k, kp) in AppSettings.doubles { j.put(k, self[keyPath: kp]) }
        j.put("oilChangeMillis", oilChangeMillis)
        return j
    }
}

/** Plain (non-secret) preferences. `onChange` tells the app when a setting changed, so the screens follow at once. */
final class SettingsStore {
    private let p: UserDefaults
    private let lock = NSLock()
    private var state: AppSettings
    var onChange: ((AppSettings) -> Void)?

    init(_ defaults: UserDefaults = .standard) {
        p = defaults
        let saved = defaults.string(forKey: "settings").flatMap { try? JSONObject($0) }
        state = saved.map { AppSettings($0) } ?? AppSettings()
        Brands.current = Brand.of(state.brand)
    }

    var value: AppSettings { lock.lock(); defer { lock.unlock() }; return state }

    func update(_ change: (inout AppSettings) -> Void) {
        lock.lock()
        var s = state
        change(&s)
        state = s
        lock.unlock()
        p.set(s.toJSON().toString(), forKey: "settings")
        Brands.current = Brand.of(s.brand)
        onChange?(s)
    }

    private func string(_ key: String, _ fallback: String = "") -> String { p.string(forKey: key) ?? fallback }
    private func long(_ key: String) -> Int64 { (p.object(forKey: key) as? NSNumber)?.int64Value ?? 0 }
    private func setLong(_ key: String, _ v: Int64) { p.set(NSNumber(value: v), forKey: key) }

    // Small non-secret runtime values

    /** The user's own car name, kept while demo mode shows the sample car. */
    var demoPrevName: String { get { string("demoPrevName") } set { p.set(newValue, forKey: "demoPrevName") } }
    var lastRefresh: Int64 { get { long("lastRefresh") } set { setLong("lastRefresh", newValue) } }

    /** One photo per car. */
    func photoFor(_ vin: String) -> String? { p.string(forKey: "photo_" + vin) }
    func setPhotoFor(_ vin: String, _ path: String?) { p.set(path, forKey: "photo_" + vin) }

    /** Locally kept service history (the API only knows the next service). */
    var serviceHistoryJson: String { get { string("serviceHistory", "[]") } set { p.set(newValue, forKey: "serviceHistory") } }
    /** Trip tags and which trip has which (see TripTags). */
    var tripTagsJson: String { get { string("tripTags") } set { p.set(newValue, forKey: "tripTags") } }
    /** Trips imported from a CSV file. */
    var importedTripsJson: String { get { string("importedTrips", "[]") } set { p.set(newValue, forKey: "importedTrips") } }

    func raw(_ key: String) -> String { string(key) }
    func setRaw(_ key: String, _ v: String) { p.set(v, forKey: key) }

    /** Schedules just sent to the car, shown until the server's status catches up ({"time":..,"programs":[..]}). */
    var pendingPrograms: String { get { string("pendingPrograms") } set { p.set(newValue, forKey: "pendingPrograms") } }
    /** Start time of the charging session LionFlow already stopped at the limit (so it only stops once). */
    var limitStoppedFor: Int64 { get { long("limitStoppedFor") } set { setLong("limitStoppedFor", newValue) } }
    /** Charge-limit attempts this session: "session,attempts,lastTryMillis,gaveUp". */
    var limitState: String { get { string("limitState") } set { p.set(newValue, forKey: "limitState") } }

    /** The user's own answer to "is this a mild hybrid?", when the car's data gets it wrong. Nil = go by the data. */
    func mildHybridOverride(_ vin: String) -> Bool? {
        switch p.string(forKey: "mildHybridOverride_" + vin) {
        case "true": return true
        case "false": return false
        default: return nil
        }
    }
    func setMildHybridOverride(_ vin: String, _ enabled: Bool?) {
        p.set(enabled.map { $0 ? "true" : "false" }, forKey: "mildHybridOverride_" + vin)
    }

    func nameFor(_ vin: String) -> String? { p.string(forKey: "carName_" + vin) }
    func setNameFor(_ vin: String, _ name: String) { p.set(name, forKey: "carName_" + vin) }

    /** Last status snapshot used by the background notifier to detect changes. */
    var notifySnapshot: String { get { string("notifySnapshot", "{}") } set { p.set(newValue, forKey: "notifySnapshot") } }

    /** Signed-in accounts (one per carmaker login). The first one has id "" so its stored keys keep working. */
    struct Account: Equatable {
        var id: String
        var brand: String
        var country: String
    }

    func accounts() -> [Account] {
        guard let raw = p.string(forKey: "accounts"), let a = try? JSONArray(raw) else {
            let s = value
            return [Account(id: "", brand: s.brand, country: s.country)]
        }
        return a.objects().map { Account(id: $0.optString("id"), brand: $0.optString("brand"), country: $0.optString("country")) }
    }

    func saveAccounts(_ list: [Account]) {
        let a = JSONArray()
        for it in list { a.put(JSONObject().put("id", it.id).put("brand", it.brand).put("country", it.country)) }
        p.set(a.toString(), forKey: "accounts")
    }

    var activeAccount: String { get { string("activeAccount") } set { p.set(newValue, forKey: "activeAccount") } }

    func clearPersonal() {
        p.removeObject(forKey: "lastRefresh")
        p.removeObject(forKey: "notifySnapshot")
    }

    /** True once per install: the first time the app runs. */
    func firstRun() -> Bool {
        if p.bool(forKey: "installed") { return false }
        p.set(true, forKey: "installed")
        return true
    }
}
