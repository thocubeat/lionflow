import Foundation

/// What the home-screen widget shows, written by the app and read by the widget (no secrets, no position).
/// The texts are already in the user's language: the widget has no translations of its own. Only "how long ago"
/// is put together in the widget, from the `ago` patterns, so it stays right between two app runs.
/// This file is part of both the app and the widget.
struct WidgetSnapshot: Codable, Equatable {
    var signedIn = false
    var hasData = false
    var name = "LionFlow"
    var battery: Double? = nil
    var batteryText = "—"
    var rangeText = "—"
    var rangeUnit = ""
    var batteryLabel = "Battery"
    var rangeLabel = "Est. range"
    var carLabel = "Car"
    var outsideLabel = "Outside"
    var fuel = false
    var charging = false
    var plugged = false
    var precond = false
    var chargeLine = ""
    var cabinText = "—"
    var outsideText = "—"
    var place: String? = nil
    /// When the car last reported, and when the app last asked (milliseconds).
    var carTime: Int64 = 0
    var fetched: Int64 = 0
    /// Shown instead of "Updated …": "Open LionFlow to sign in", "No data yet" and the like.
    var note: String? = nil
    var noData = "No data yet"
    /// "updated.min" → "Updated {n} min ago" and so on (kinds: updated, asleep, asleepShort; units: now, min, h, d).
    var ago: [String: String] = [:]

    /// "Updated 5 min ago", or "Car asleep · Last report 3 h ago" when the car itself has been quiet for a while.
    func updatedLine(now: Int64, short: Bool) -> String {
        if let note = note { return note }
        let asleep = now - carTime > 30 * 60_000 && fetched - carTime > 30 * 60_000
        let kind = asleep ? (short ? "asleepShort" : "asleep") : "updated"
        let m = max(0, (now - carTime) / 60_000)
        let (unit, n): (String, Int64) = m < 1 ? ("now", 0) : m < 60 ? ("min", m) : m < 1440 ? ("h", m / 60) : ("d", m / 1440)
        let pattern = ago[kind + "." + unit] ?? Self.english[kind + "." + unit] ?? ""
        return pattern.replacingOccurrences(of: "{n}", with: String(n))
    }

    /// The English lines the patterns are made from (the app translates each with the number 4321 in it).
    static let english: [String: String] = {
        var out: [String: String] = [:]
        let agoText = ["now": "just now", "min": "{n} min ago", "h": "{n} h ago", "d": "{n} d ago"]
        for (unit, text) in agoText {
            out["updated." + unit] = "Updated " + text
            out["asleep." + unit] = "Car asleep · Last report " + text
            out["asleepShort." + unit] = "Asleep · " + text
        }
        return out
    }()

    static let sampleNumber = "4321"
}

/// The shared folder of the app and its widget (an App Group). Sideloading tools give the group a name of their
/// own (SideStore and AltStore add the team id), so the name is read from this copy's provisioning profile.
enum WidgetShelf {
    static let fileName = "widget.json"

    static let folder: URL? = {
        let files = FileManager.default
        for id in groupNames() {
            if let url = files.containerURL(forSecurityApplicationGroupIdentifier: id) { return url }
        }
        return nil
    }()

    static func groupNames() -> [String] {
        var names: [String] = []
        if let url = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
           let data = try? Data(contentsOf: url), let groups = profileGroups(data) {
            names += groups.filter { $0.hasPrefix("group.") && !$0.contains("*") }
        }
        // Built with Xcode, or the profile can't be read: the name the project gives it.
        var id = Bundle.main.bundleIdentifier ?? "no.lionflow.app"
        if Bundle.main.bundlePath.hasSuffix(".appex") { id = id.components(separatedBy: ".").dropLast().joined(separator: ".") }
        names.append("group." + id)
        return names
    }

    /// The App Groups listed in a provisioning profile (a property list inside a signed envelope).
    static func profileGroups(_ data: Data) -> [String]? {
        guard let start = data.range(of: Data("<?xml".utf8)), let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex) else { return nil }
        let plist = data.subdata(in: start.lowerBound..<end.upperBound)
        guard let dict = (try? PropertyListSerialization.propertyList(from: plist, options: [], format: nil)) as? [String: Any],
              let ents = dict["Entitlements"] as? [String: Any] else { return nil }
        return ents["com.apple.security.application-groups"] as? [String]
    }

    static func read() -> WidgetSnapshot? {
        guard let url = folder?.appendingPathComponent(fileName), let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    @discardableResult
    static func write(_ snap: WidgetSnapshot) -> Bool {
        guard let url = folder?.appendingPathComponent(fileName), let data = try? JSONEncoder().encode(snap) else { return false }
        return (try? data.write(to: url, options: .atomic)) != nil
    }
}
