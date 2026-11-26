import Foundation

/// The Stellantis (ex-PSA) connected-car API, used the same way the brands' own apps use it.
/// Endpoints and flow follow the MIT-licensed homeassistant-stellantis-vehicles project, as in the Android app.
final class StellantisApi: @unchecked Sendable {
    static let API = "https://api.groupe-psa.com"
    static let kAccess = "oauth_access"
    static let kRefresh = "oauth_refresh"
    static let kExpires = "oauth_expires"
    static let kCustomer = "customer_id"
    static let kVehicles = "vehicles"
    static let kSelected = "selected_vin"
    /** What the Android app's HTTP library calls itself where the app doesn't say anything else. */
    private static let okhttp = "okhttp/4.12.0"

    private let secure: SecureStore
    private let settings: SettingsStore
    private let tokenLock = AsyncLock()

    init(_ secure: SecureStore, _ settings: SettingsStore) {
        self.secure = secure
        self.settings = settings
    }

    var cfg: BrandConfig {
        let s = settings.value
        return (try? StellantisConfig.load(Brand.of(s.brand), s.country))
            ?? BrandConfig(oauthUrl: "", realm: "", scheme: "", locale: "", clientId: "", clientSecret: "", culture: "")
    }

    var isSignedIn: Bool { secure.get(Self.kRefresh) != nil }
    var customerId: String? { secure.get(Self.kCustomer) }

    private func clientQuery() -> String { "client_id=\(cfg.clientId)&locale=\(locale())" }

    // Some countries' listed locale is refused by the server ("Invalid parameter: locale", e.g. en-IS for Iceland).
    // Then the next one is tried and remembered: the country's own language, then British English.
    private func localeKey() -> String { "locale_\(settings.value.brand)_\(settings.value.country)" }
    private func locale() -> String { secure.get(localeKey()) ?? cfg.locale }
    private func nextLocale() -> Bool {
        let cc = settings.value.country.uppercased()
        let own = ["IS": "is", "NO": "nb", "DK": "da", "SE": "sv", "FI": "fi", "EE": "et", "LV": "lv", "LT": "lt"][cc]
        var tries: [String] = []
        let candidates: [String?] = [cfg.locale, own.map { "\($0)-\(cc)" }, "en-GB", "fr-FR"]
        for t in candidates {
            if let t = t, !tries.contains(t) { tries.append(t) }
        }
        let i = (tries.firstIndex(of: locale()) ?? -1) + 1
        if i >= tries.count { return false }
        secure.put(localeKey(), tries[i])
        return true
    }
    private func isLocaleError(_ e: ApiException) -> Bool { e.code == 400 && e.message.lowercased().contains("locale") }

    func authorizeUrl() -> String {
        let c = cfg
        return "\(c.oauthUrl)/am/oauth2/authorize?client_id=\(c.clientId)&response_type=code" +
            "&redirect_uri=\(c.redirectUri)&scope=openid%20profile%20email&locale=\(locale())"
    }

    // MARK: OAuth

    func exchangeCode(_ code: String) async throws {
        let c = cfg
        let url = "\(c.oauthUrl)/am/oauth2/access_token?redirect_uri=\(c.redirectUri)&grant_type=authorization_code&code=\(urlEncode(code))"
        try await tokenRequest(url, secure.profile)
    }

    /// Staying signed in. The carmaker's server hands out a NEW refresh token at every renewal and the old one stops
    /// working at once, so an app that loses the new one is signed out (a common complaint about the official apps).
    /// Three rules keep that from happening here: only one renewal runs at a time (`tokenLock`), the new refresh
    /// token is saved in the Keychain before anything else is done with the answer, and it goes to the account the
    /// renewal was started for, even if another car or account was picked while the request was out.
    private func refreshOauth(_ prof: String) async throws {
        guard let refresh = secure.get(Self.kRefresh, prof) else { throw AuthExpiredException() }
        let url = "\(cfg.oauthUrl)/am/oauth2/access_token?grant_type=refresh_token&refresh_token=\(urlEncode(refresh))"
        do {
            try await tokenRequest(url, prof)
        } catch let e as ApiException {
            if e.code == 401 || (e.code == 400 && tokenIsDead(e)) { throw AuthExpiredException() }
            throw e
        }
    }

    /** A 400 that is about something else than the token (a bad request, the wrong client) is not a reason to sign out. */
    private func tokenIsDead(_ e: ApiException) -> Bool {
        let m = e.message.lowercased()
        if m.contains("invalid_grant") { return true }
        return !["invalid_request", "invalid_client", "unauthorized_client", "unsupported_grant_type", "invalid_scope"].contains { m.contains($0) }
    }

    private func tokenRequest(_ url: String, _ prof: String) async throws {
        let req = try Http.request(url, method: "POST", headers: [
            ("Content-Type", "application/x-www-form-urlencoded"),
            ("Authorization", "Basic \(cfg.basicToken)"),
            ("User-Agent", Self.okhttp),
        ], body: Data())
        guard case .object(let j)? = try await Http.json(req) else { throw ApiException(0, "Empty token response") }
        guard let access = j.str("access_token") else { throw ApiException(0, "No access token in response") }
        let expiresIn = j.optLong("expires_in", 3600)
        // The new refresh token first, and saved before anything else: the old one is already dead on the server.
        if let r = j.str("refresh_token") { secure.put(Self.kRefresh, r, prof, now: true) }
        secure.put(Self.kAccess, access, prof)
        secure.put(Self.kExpires, String(nowMillis() + expiresIn * 1000), prof)
    }

    private func accessToken() async throws -> String {
        try await tokenLock.withLock { () -> String in
            let prof = self.secure.profile
            let exp = Int64(self.secure.get(Self.kExpires, prof) ?? "") ?? 0
            if self.secure.get(Self.kAccess, prof) == nil || nowMillis() > exp - 5 * 60_000 { try await self.refreshOauth(prof) }
            guard let t = self.secure.get(Self.kAccess, prof) else { throw AuthExpiredException() }
            return t
        }
    }

    private func authed<T>(_ block: (String) async throws -> T) async throws -> T {
        var n = 0
        while true {
            do {
                return try await authedOnce(block)
            } catch let e as ApiException {
                if isLocaleError(e) && n < 3 && nextLocale() {
                    n += 1
                    continue
                }
                throw e
            }
        }
    }

    private func authedOnce<T>(_ block: (String) async throws -> T) async throws -> T {
        let token = try await accessToken()
        do {
            return try await block(token)
        } catch let e as ApiException {
            if e.code != 401 { throw e }
            // Renew, unless another request already did while this one was out (never two renewals for one token).
            try await tokenLock.withLock { () -> Void in
                if self.secure.get(Self.kAccess) == token { try await self.refreshOauth(self.secure.profile) }
            }
            guard let renewed = secure.get(Self.kAccess) else { throw AuthExpiredException() }
            return try await block(renewed)
        }
    }

    private func otpHeaders(_ token: String) -> [(String, String)] {
        [("Authorization", "Bearer \(token)"), ("User-Agent", "okhttp/4.8.0"), ("Accept", "application/hal+json"), ("x-introspect-realm", cfg.realm)]
    }

    private func carHeaders(_ token: String) -> [(String, String)] {
        [("Authorization", "Bearer \(token)"), ("x-introspect-realm", cfg.realm), ("User-Agent", Self.okhttp)]
    }

    private func object(_ url: String, _ headers: [(String, String)], method: String = "GET", body: Data? = nil) async throws -> JSONObject? {
        if case .object(let j)? = try await Http.json(try Http.request(url, method: method, headers: headers, body: body)) { return j }
        return nil
    }

    // MARK: Account and cars

    /** Returns the customer id and stores it. */
    @discardableResult
    func loadUserInfo() async throws -> String? {
        try await authed { (token: String) -> String? in
            let url = "\(Self.API)/applications/cvs/v4/mauv/car-associations?\(self.clientQuery())"
            let req = try Http.request(url, headers: self.otpHeaders(token) + [("x-transaction-id", "1234")])
            var list: [JSONObject] = []
            if case .array(let a)? = try await Http.json(req) { list = a.objects() }
            // Some cars (e.g. newer models) have no remote-services link here; status still works without it.
            let customer = list.map { $0.optString("customer") }.first { !$0.isEmpty }
            if let c = customer { self.secure.put(Self.kCustomer, c) }
            return customer
        }
    }

    func vehicles(checkSelectedBranding: Bool = false) async throws -> [Vehicle] {
        let requestProfile = profile
        return try await authed { (token: String) -> [Vehicle] in
            if self.profile != requestProfile { return self.cachedVehiclesOf(requestProfile) }
            let defaultBrand = Brands.current.display
            let before = self.cachedVehiclesOf(requestProfile)
            let base = "\(Self.API)/connectedcar/v4/user/vehicles"
            let query = "\(self.clientQuery())&extension=branding"
            guard let j = try await self.object("\(base)?\(query)", self.carHeaders(token)) else { throw ApiException(0, "Empty vehicle list") }
            var order: [String] = []
            var groups: [String: [Vehicle]] = [:]
            for item in j.obj("_embedded")?.optJSONArray("vehicles")?.objects() ?? [] {
                let v = try Vehicle.fromApi(item, defaultBrand)
                if groups[v.vin] == nil { order.append(v.vin) }
                groups[v.vin, default: []].append(v)
            }
            // The same car can come twice (for example when it is shared with another account): keep the one in use.
            var list: [Vehicle] = []
            for vin in order {
                let same = groups[vin] ?? []
                if let known = same.first(where: { v in before.contains(where: { $0.id == v.id }) }) { list.append(known) }
                else if let first = same.first { list.append(first) }
            }
            if self.profile != requestProfile { return list }
            var updated = list
            if checkSelectedBranding, let selected = self.pick(list), selected.mildHybridDetection == .insufficientMetadata {
                let detail = await self.optionalBrandingDetail("\(base)/\(selected.id)?\(query)", token)
                let detailed = selected.withBrandingDetail(detail)
                updated = list.map { $0.id == selected.id && $0.vin == selected.vin ? detailed : $0 }
            }
            if self.profile == requestProfile {
                self.secure.put(Self.kVehicles, JSONArray(updated.map { $0.toJson() }).toString())
            }
            return updated
        }
    }

    private func optionalBrandingDetail(_ url: String, _ token: String) async -> JSONObject? {
        do {
            return try await object(url, carHeaders(token))
        } catch {
            return nil
        }
    }

    /** Cars cached for another account (used by the car list in Settings). */
    func cachedVehiclesOf(_ profile: String) -> [Vehicle] {
        guard let s = secure.get(Self.kVehicles, profile), let a = try? JSONArray(s) else { return [] }
        return a.objects().compactMap { try? Vehicle.fromJson($0) }
    }
    func selectedVinOf(_ profile: String) -> String? {
        let v = secure.get(Self.kSelected, profile) ?? ""
        return v.isEmpty ? nil : v
    }
    func isSignedInTo(_ profile: String) -> Bool { secure.get(Self.kRefresh, profile) != nil }
    func switchProfile(_ profile: String) { secure.profile = profile }
    var profile: String { secure.profile }

    func cachedVehicles() -> [Vehicle] { cachedVehiclesOf(profile) }

    /** The car chosen in the app (accounts can have several). */
    var selectedVin: String? {
        get { selectedVinOf(profile) }
        set { secure.put(Self.kSelected, newValue ?? "") }
    }

    func pick(_ list: [Vehicle]) -> Vehicle? {
        let chosen = selectedVin
        return list.first { $0.vin == chosen } ?? list.first
    }
    func cachedVehicle() -> Vehicle? { pick(cachedVehicles()) }
    func currentVehicle() async throws -> Vehicle? {
        if let v = cachedVehicle() { return v }
        return pick(try await vehicles())
    }

    func isMildHybrid(_ vehicle: Vehicle) -> Bool { vehicle.isMildHybrid(settings.mildHybridOverride(vehicle.vin)) }

    func parseStatus(_ vehicle: Vehicle, _ status: JSONObject, _ maintenance: JSONObject? = nil) -> VehicleStatus {
        vehicle.parseStatus(status, maintenance, settings.mildHybridOverride(vehicle.vin))
    }

    func readStatus(_ vehicle: Vehicle) async throws -> VehicleStatus { parseStatus(vehicle, try await status(vehicle)) }

    /// A status with only the kind of energy filled in, for a car the server has no live status for: the app still
    /// opens and shows trips and service info.
    func blankStatus(_ v: Vehicle) -> JSONObject {
        let energies = JSONArray()
        let m = v.motorization.lowercased()
        if m != "thermal" && m != "" { energies.put(JSONObject().put("type", "Electric")) }
        if m == "thermal" || m == "hybrid" { energies.put(JSONObject().put("type", "Fuel")) }
        return JSONObject().put("energies", energies)
    }

    func parseTrips(_ vehicle: Vehicle, _ trips: [JSONObject]) -> [Trip] {
        let mild = isMildHybrid(vehicle)
        return trips.map { Trip.parse($0, mildHybrid: mild) }
    }

    func status(_ v: Vehicle) async throws -> JSONObject {
        try await authed { (token: String) -> JSONObject in
            let url = "\(Self.API)/connectedcar/v4/user/vehicles/\(v.id)/status?\(self.clientQuery())"
            guard let j = try await self.object(url, self.carHeaders(token)) else { throw ApiException(0, "Empty status response") }
            return j
        }
    }

    /** Warnings the car itself has reported. Empty when there are none, or when the car doesn't share them. */
    func alerts(_ v: Vehicle) async throws -> [JSONObject] {
        do {
            return try await authed { (token: String) -> [JSONObject] in
                let url = "\(Self.API)/connectedcar/v4/user/vehicles/\(v.id)/alerts?\(self.clientQuery())"
                let j = try await self.object(url, self.carHeaders(token))
                return j?.obj("_embedded")?.optJSONArray("alerts")?.objects() ?? []
            }
        } catch let e as ApiException {
            if e.noData { return [] }
            throw e
        }
    }

    func maintenance(_ v: Vehicle) async throws -> JSONObject? {
        var urls: [String] = []
        for u in [v.maintenanceHref, "\(Self.API)/connectedcar/v4/user/vehicles/\(v.id)/maintenance"] {
            if let u = u, !urls.contains(u) { urls.append(u) }
        }
        for href in urls {
            do {
                let j: JSONObject? = try await authed { (token: String) -> JSONObject? in
                    let sep = href.contains("?") ? "&" : "?"
                    return try await self.object("\(href)\(sep)\(self.clientQuery())", self.carHeaders(token))
                }
                if let j = j, j.has("mileageBeforeMaintenance") || j.has("daysBeforeMaintenance") { return j }
            } catch let e as AuthExpiredException {
                throw e
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                // try the next address; service data is optional
            }
        }
        return nil
    }

    /// One page of trips since the given time (and until `untilIso`), or the page at `nextHref` (the server's own
    /// "next" link).
    func trips(_ v: Vehicle, _ sinceIso: String?, _ untilIso: String? = nil, _ nextHref: String? = nil) async throws -> JSONObject {
        try await authed { (token: String) -> JSONObject in
            var url: String
            if let next = nextHref, next.hasPrefix(Self.API) {
                url = next.contains("client_id=") ? next : next + (next.contains("?") ? "&" : "?") + self.clientQuery()
            } else {
                url = "\(Self.API)/connectedcar/v4/user/vehicles/\(v.id)/trips?\(self.clientQuery())&distance=0.1-&pageSize=60"
                if let since = sinceIso { url += "&timestamps=\(since)/\(untilIso ?? "")" }
            }
            return try await self.object(url, self.carHeaders(token)) ?? JSONObject()
        }
    }

    /** The server's answer: the trips (raw), and whether it said "no trips found" for the whole period. */
    struct TripsResult {
        var trips: [JSONObject]
        var noneFound: Bool
    }

    /** One week of trips, following the server's own "next" links. The flag is true when the server said "no trips". */
    private func tripsWindow(_ v: Vehicle, _ from: Int64, _ to: Int64?) async throws -> ([JSONObject], Bool) {
        var order: [String] = []
        var got: [String: JSONObject] = [:]
        var next: String? = nil
        var n = 0
        var none = false
        while true {
            let j: JSONObject
            do {
                j = try await trips(v, isoUTC(from), to.map { isoUTC($0) }, next)
            } catch let e as ApiException {
                // "404 / 40400 No trip was found" just means no trips in this period (some cars say so on every call).
                if e.noData {
                    if n == 0 { none = true }
                    break
                }
                throw e
            }
            var fresh = 0
            for t in j.optJSONObject("_embedded")?.optJSONArray("trips")?.objects() ?? [] {
                let id = t.optString("id")
                if got[id] == nil {
                    order.append(id)
                    fresh += 1
                }
                got[id] = t
            }
            let href = j.optJSONObject("_links")?.optJSONObject("next")?.optString("href") ?? ""
            next = href.allSatisfy({ $0.isWhitespace }) ? nil : href
            n += 1
            if next == nil || fresh == 0 || n >= 20 { break }
        }
        return (order.compactMap { got[$0] }, none)
    }

    /// All trips from `fromMillis` until now. Asked for week by week, each week following the server's own "next"
    /// links: asking for the whole period at once could stop after the first page and leave out the newest trips.
    func allTrips(_ v: Vehicle, _ fromMillis: Int64) async throws -> TripsResult {
        let now = nowMillis()
        let week: Int64 = 7 * 86_400_000
        var windows: [(Int64, Int64?)] = []
        var end: Int64? = nil
        var start = now - week
        while true {
            windows.append((max(start, fromMillis), end))
            if start <= fromMillis { break }
            end = start
            start -= week
        }
        let all = windows
        let result: ([[JSONObject]], Int) = try await withThrowingTaskGroup(of: (Int, [JSONObject], Bool).self) { group in
            var lists = [[JSONObject]](repeating: [], count: all.count)
            var none = 0
            var index = 0
            var running = 0
            // Four weeks at a time, as on Android.
            while index < all.count || running > 0 {
                while running < 4 && index < all.count {
                    let i = index
                    let w = all[i]
                    group.addTask {
                        let r = try await self.tripsWindow(v, w.0, w.1)
                        return (i, r.0, r.1)
                    }
                    index += 1
                    running += 1
                }
                if let r = try await group.next() {
                    lists[r.0] = r.1
                    if r.2 { none += 1 }
                }
                running -= 1
            }
            return (lists, none)
        }
        var seen = Set<String>()
        var out: [JSONObject] = []
        for list in result.0 {
            for t in list where seen.insert(t.optString("id")).inserted { out.append(t) }
        }
        return TripsResult(trips: out, noneFound: result.1 == windows.count)
    }

    // MARK: Remote control setup

    func requestSmsCode() async throws {
        try await authed { (token: String) -> Void in
            let url = "\(Self.API)/applications/cvs/v4/mobile/smsCode?\(self.clientQuery())"
            _ = try await Http.text(try Http.request(url, method: "POST", headers: self.otpHeaders(token), body: Data()))
        }
    }

    /** POSTs to the virtual-key token endpoint (password grant with an OTP, or refresh grant). */
    func mqttToken(_ body: JSONObject) async throws -> JSONObject {
        try await authed { (token: String) -> JSONObject in
            let url = "\(Self.API)/connectedcar/v4/virtualkey/remoteaccess/token?\(self.clientQuery())"
            let headers = self.otpHeaders(token) + [("Content-Type", "application/json; charset=utf-8")]
            return try await self.object(url, headers, method: "POST", body: Data(body.toString().utf8)) ?? JSONObject()
        }
    }

    /** First access token of the session — used as OTP device id like the reference implementation. */
    func currentAccessToken() -> String? { secure.get(Self.kAccess) }

    func signOut() { secure.clear() }
}
