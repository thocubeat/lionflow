import Foundation

/** The sign-in settings of one brand in one country (from stellantis_configs.json). */
struct BrandConfig: Equatable {
    var oauthUrl: String
    var realm: String
    var scheme: String
    var locale: String
    var clientId: String
    var clientSecret: String
    var culture: String

    var basicToken: String { Data("\(clientId):\(clientSecret)".utf8).base64EncodedString() }
    var redirectUri: String { "\(scheme)://oauth2redirect/\(culture)" }
}

enum StellantisConfig {
    private static let lock = NSLock()
    private static var root: JSONObject?

    /** The text of stellantis_configs.json. The app gives it once at start; tests give their own. */
    static func setText(_ text: String) throws {
        let j = try JSONObject(text)
        lock.lock()
        root = j
        lock.unlock()
    }

    private static func loaded() throws -> JSONObject {
        lock.lock()
        defer { lock.unlock() }
        guard let r = root else { throw JSONError("The sign-in settings are not loaded") }
        return r
    }

    static func countries(_ brand: Brand) -> [String] {
        guard let cfgs = try? loaded().getJSONObject(brand.jsonKey).getJSONObject("configs") else { return [] }
        return cfgs.keys().sorted()
    }

    static func load(_ brand: Brand, _ country: String) throws -> BrandConfig {
        let app = try loaded().getJSONObject(brand.jsonKey)
        let cfgs = try app.getJSONObject("configs")
        // Not every brand is available in every country: fall back to Norway, then to the first one listed.
        guard let cc = [country, "NO", "GB"].first(where: { cfgs.has($0) }) ?? cfgs.keys().first else { throw JSONError("No countries for \(brand.jsonKey)") }
        let c = try cfgs.getJSONObject(cc)
        return BrandConfig(
            oauthUrl: try app.getString("oauth_url"),
            realm: try app.getString("realm"),
            scheme: try app.getString("scheme"),
            locale: try c.getString("locale"),
            clientId: try c.getString("client_id"),
            clientSecret: try c.getString("client_secret"),
            culture: cc.lowercased())
    }
}

enum MildHybridDetection {
    case vehicleMetadata, modelName, otherPowertrain, insufficientMetadata
    var detected: Bool { self == .vehicleMetadata || self == .modelName }
}

/** One car on the account. */
struct Vehicle: Equatable {
    var id: String
    var vin: String
    var motorization: String
    var brand: String
    var pictureUrl: String?
    var maintenanceHref: String?
    var modelLabel: String? = nil
    var legacyLabel: String? = nil

    private static let mildHybridMotorizations: Set<String> = [
        "mhev", "mhev48v", "48vmhev", "mildhybrid", "mildhybridelectricvehicle", "48vmildhybrid", "mildhybrid48v",
        "48vhybrid", "hybrid48v",
    ]
    private static let plugInMotorizations: Set<String> = ["phev", "pluginhybrid", "pluginhybridelectricvehicle"]
    private static let otherMotorizations: Set<String> = [
        "bev", "electric", "batteryelectricvehicle",
        "thermic", "thermal", "fuel", "petrol", "gasoline", "diesel", "ice", "hydrogen",
    ]
    private static let genericHybridMotorizations: Set<String> = ["hybrid", "hev", "hybridelectricvehicle", "fullhybrid"]
    private static let ambiguousMotorizations: Set<String> = genericHybridMotorizations.union(["", "unknown"])
    private static let labelSeparator = try! NSRegularExpression(pattern: "[^\\p{L}\\p{N}]+")
    private static let otherModel = try! NSRegularExpression(pattern: "\\b(?:phev|bev|plug ?in|electric|e ?208)\\b")
    private static let plugInModel = try! NSRegularExpression(pattern: "\\b(?:phev|plug ?in hybrid|hybrid plug ?in)\\b")
    private static let hybrid208 = try! NSRegularExpression(pattern: "\\b208(?: (?![0-9]{3,4}\\b)[\\p{L}\\p{N}]+)*? hybrid 100 e ?dcs ?6\\b")

    private static func found(_ re: NSRegularExpression, in s: String) -> Bool {
        re.firstMatch(in: s, options: [], range: NSRange(location: 0, length: (s as NSString).length)) != nil
    }

    /** Java's `Character.isLetterOrDigit`. */
    private static func isLetterOrDigit(_ u: Unicode.Scalar) -> Bool {
        switch u.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber: return true
        default: return false
        }
    }

    private var powertrain: String {
        var out = String.UnicodeScalarView()
        for u in motorization.unicodeScalars where Vehicle.isLetterOrDigit(u) { out.append(u) }
        return String(out).lowercased()
    }

    private var labels: [String] {
        [modelLabel, legacyLabel].compactMap { $0 }.map { label -> String in
            let low = label.lowercased()
            let spaced = Vehicle.labelSeparator.stringByReplacingMatches(in: low, options: [], range: NSRange(location: 0, length: (low as NSString).length), withTemplate: " ")
            return spaced.trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private var reportsPlugInHybrid: Bool {
        let p = powertrain
        if Vehicle.plugInMotorizations.contains(p) { return true }
        return Vehicle.ambiguousMotorizations.contains(p) && labels.contains { Vehicle.found(Vehicle.plugInModel, in: $0) }
    }

    var needsBranding: Bool {
        (brand.allSatisfy({ $0.isWhitespace }) || brand.caseInsensitiveCompare("Peugeot") == .orderedSame) && mildHybridDetection == .insufficientMetadata
    }

    var reportsMildHybrid: Bool { mildHybridDetection.detected }

    func isMildHybrid(_ manualOverride: Bool?) -> Bool { manualOverride ?? reportsMildHybrid }

    /** What kind of car this is, in words. */
    func kind(_ manualOverride: Bool?) -> String {
        let p = powertrain
        if isMildHybrid(manualOverride) { return "48 V mild hybrid" }
        if reportsPlugInHybrid { return "Plug-in hybrid" }
        if Vehicle.genericHybridMotorizations.contains(p) || reportsMildHybrid { return "Hybrid (type unknown)" }
        if ["electric", "bev", "batteryelectricvehicle"].contains(p) { return "Electric" }
        if ["thermal", "thermic", "fuel", "petrol", "gasoline", "diesel", "ice"].contains(p) { return "Petrol / diesel" }
        return motorization.allSatisfy({ $0.isWhitespace }) ? "Car" : motorization
    }

    func parseStatus(_ status: JSONObject, _ maintenance: JSONObject?, _ manualOverride: Bool?) -> VehicleStatus {
        // Off opts out of mild-hybrid handling and restores the standard status parser.
        let p = powertrain
        let hasFuel = status.optJSONArray("energies")?.objects().contains { $0.optString("type") == "Fuel" } ?? false
        let needsEvidence = manualOverride == nil && !reportsPlugInHybrid &&
            (Vehicle.genericHybridMotorizations.contains(p) || (Vehicle.ambiguousMotorizations.contains(p) && hasFuel))
        return VehicleStatus.parse(status, maintenance, mildHybrid: isMildHybrid(manualOverride), requireChargingEvidence: needsEvidence)
    }

    var mildHybridDetection: MildHybridDetection {
        let p = powertrain
        if Vehicle.mildHybridMotorizations.contains(p) { return .vehicleMetadata }
        if Vehicle.otherMotorizations.contains(p) || Vehicle.plugInMotorizations.contains(p) { return .otherPowertrain }
        let l = labels
        if l.contains(where: { Vehicle.found(Vehicle.otherModel, in: $0) }) { return .otherPowertrain }
        // The complete Peugeot model and transmission distinguish this version from a generic Hybrid.
        if Vehicle.ambiguousMotorizations.contains(p) && brand.caseInsensitiveCompare("Peugeot") == .orderedSame &&
            l.contains(where: { Vehicle.found(Vehicle.hybrid208, in: $0) }) { return .modelName }
        return .insufficientMetadata
    }

    func toJson() -> JSONObject {
        let j = JSONObject()
        j.put("id", id).put("vin", vin).put("motorization", motorization).put("brand", brand)
        j.put("picture", pictureUrl ?? "").put("maintenance", maintenanceHref ?? "")
        if let m = modelLabel { j.put("modelLabel", m) }
        if let l = legacyLabel { j.put("legacyLabel", l) }
        return j
    }

    /** A text value that is really text in the server's answer, without the spaces around it; nil when empty. */
    private static func metadataString(_ j: JSONObject, _ key: String) -> String? {
        guard case .string(let s)? = j.opt(key) else { return nil }
        let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }

    func withBrandingDetail(_ j: JSONObject?) -> Vehicle {
        guard let j = j else { return self }
        if let other = Vehicle.metadataString(j, "id"), other != id { return self }
        if let other = Vehicle.metadataString(j, "vin"), other != vin { return self }
        return withBranding(j)
    }

    private func withBranding(_ j: JSONObject) -> Vehicle {
        let branding = j.obj("_embedded", "extension", "branding")
        let brandingLabel = branding.flatMap { Vehicle.metadataString($0, "label") }
        let label = Vehicle.metadataString(j, "label")
        var out = self
        out.motorization = Vehicle.metadataString(j, "motorization") ?? motorization
        out.brand = branding.flatMap { Vehicle.metadataString($0, "brand") } ?? Vehicle.metadataString(j, "brand") ?? brand
        out.modelLabel = brandingLabel ?? label ?? modelLabel
        if brandingLabel == nil && label == nil {
            out.legacyLabel = legacyLabel
        } else if let l = label, brandingLabel != nil, l != brandingLabel {
            out.legacyLabel = l
        } else {
            out.legacyLabel = nil
        }
        return out
    }

    /** A car as LionFlow saved it. */
    static func fromJson(_ j: JSONObject) throws -> Vehicle {
        let picture = j.optString("picture")
        let maintenance = j.optString("maintenance")
        return Vehicle(
            id: try j.getString("id"), vin: try j.getString("vin"),
            motorization: metadataString(j, "motorization") ?? "", brand: metadataString(j, "brand") ?? "",
            pictureUrl: picture.isEmpty ? nil : picture,
            maintenanceHref: maintenance.isEmpty ? nil : maintenance,
            modelLabel: metadataString(j, "modelLabel"),
            legacyLabel: metadataString(j, "legacyLabel"))
    }

    /** A car as the carmaker's server lists it. */
    static func fromApi(_ j: JSONObject, _ defaultBrand: String) throws -> Vehicle {
        let picture = j.optJSONArray("pictures")?.optString(0) ?? ""
        return Vehicle(
            id: try j.getString("id"),
            vin: try j.getString("vin"),
            motorization: "",
            brand: defaultBrand,
            pictureUrl: picture.isEmpty ? nil : picture,
            maintenanceHref: j.obj("_links", "maintenance")?.str("href")
        ).withBranding(j)
    }
}

/** One at a time, in the order they came (Kotlin's `Mutex`): keeps two sign-in renewals from running at once. */
final class AsyncLock: @unchecked Sendable {
    private let state = NSLock()
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    private func enter(_ c: CheckedContinuation<Void, Never>) {
        state.lock()
        if busy {
            waiters.append(c)
            state.unlock()
        } else {
            busy = true
            state.unlock()
            c.resume()
        }
    }

    private func leave() {
        state.lock()
        if waiters.isEmpty {
            busy = false
            state.unlock()
        } else {
            let next = waiters.removeFirst()
            state.unlock()
            next.resume()
        }
    }

    func withLock<T>(_ body: () async throws -> T) async throws -> T {
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in enter(c) }
        do {
            let r = try await body()
            leave()
            return r
        } catch {
            leave()
            throw error
        }
    }
}

/** "2026-11-26T09:08:40Z" for a time in milliseconds since 1970 (UTC), the form the carmaker's server takes. */
func isoUTC(_ millis: Int64) -> String {
    let secs = Int64((Double(millis) / 1000).rounded(.down))
    var days = secs / 86400
    var rem = secs % 86400
    if rem < 0 { rem += 86400; days -= 1 }
    // Civil date from days since 1970 (Gregorian).
    let z = days + 719468
    let era = (z >= 0 ? z : z - 146096) / 146097
    let doe = z - era * 146097
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    let mp = (5 * doy + 2) / 153
    let d = doy - (153 * mp + 2) / 5 + 1
    let m = mp < 10 ? mp + 3 : mp - 9
    let y = yoe + era * 400 + (m <= 2 ? 1 : 0)
    return String(format: "%04d-%02d-%02dT%02d:%02d:%02dZ", Int(y), Int(m), Int(d), Int(rem / 3600), Int(rem % 3600 / 60), Int(rem % 60))
}
