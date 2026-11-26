import Foundation

/// New builds of the iPhone app, and the signature on the copy that is installed.
///
/// An iPhone app can't renew its own signature or install a new version of itself: Apple leaves that to SideStore,
/// AltStore, Sideloadly or Xcode. So every build of main publishes a "source" next to the app file (the file those
/// tools read, in AltStore's format: see .github/scripts/ios-source.py). SideStore and AltStore then find new builds
/// by themselves, and SideStore renews the signature by itself. The app only looks: is there a newer build, and when
/// does the signature run out. Then it hands over to the tool, with a link that tells it what to do.
enum IOSRelease {
    /** The source every build of main publishes. */
    static let sourceURL = "https://github.com/thocubeat/lionflow/releases/download/ios-latest/source.json"
    /** The download page, for when no tool on the phone answers. */
    static let pageURL = "https://github.com/thocubeat/lionflow/releases/tag/ios-latest"
    /** The part of the README on keeping the app signed and up to date without doing anything. */
    static let howToURL = "https://github.com/thocubeat/lionflow#keep-it-working-by-itself"
    /// LionFlow in the source. (Signed with a free Apple ID, the installed copy has the team's id added to this, so
    /// the app is looked up by this name and not by its own.)
    static let bundleId = "no.lionflow.app"

    struct Build: Equatable {
        let version: String
        let build: Int
        let downloadURL: String
        let notes: String
    }

    /** The newest LionFlow build in a source file. */
    static func latest(inSource text: String) -> Build? {
        guard let data = text.data(using: .utf8),
              let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let apps = root["apps"] as? [[String: Any]] else { return nil }
        let app = apps.first(where: { ($0["bundleIdentifier"] as? String) == bundleId }) ?? apps.first
        // The first version listed is the newest (that is how SideStore and AltStore read it too).
        guard let newest = (app?["versions"] as? [[String: Any]])?.first,
              let version = newest["version"] as? String, !version.isEmpty,
              let url = newest["downloadURL"] as? String, !url.isEmpty else { return nil }
        let build = (newest["buildVersion"] as? String).flatMap { Int($0) } ?? (newest["buildVersion"] as? Int) ?? 0
        return Build(version: version, build: build, downloadURL: url, notes: (newest["localizedDescription"] as? String) ?? "")
    }

    /// Is `build` newer than the app that is running ("0.1.57", build "57")? The versions are compared number by
    /// number; the build numbers only when the versions are the same.
    static func isNewer(_ b: Build, thanVersion version: String, build: String) -> Bool {
        let a = numbers(b.version), c = numbers(version)
        for i in 0..<max(a.count, c.count) {
            let x = i < a.count ? a[i] : 0
            let y = i < c.count ? c[i] : 0
            if x != y { return x > y }
        }
        return b.build > (Int(build) ?? 0)
    }

    private static func numbers(_ v: String) -> [Int] {
        v.split(separator: ".").map { Int($0.prefix(while: { $0.isNumber })) ?? 0 }
    }

    /// Links that hand a new build to the tool on the phone: SideStore first, then AltStore. Both answer to
    /// "<name>://install?url=…" (it is in their own code), download the file and install it over the copy there is,
    /// so the sign-in and everything else in the app stay.
    static func installLinks(_ ipaURL: String) -> [URL] {
        ["sidestore", "altstore"].compactMap { link($0, "install", ipaURL) }
    }

    /** Links that open the tool that renews the signature, SideStore first. */
    static let toolLinks: [URL] = ["sidestore://", "altstore://"].compactMap { URL(string: $0) }

    private static func link(_ scheme: String, _ action: String, _ url: String) -> URL? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        guard let value = url.addingPercentEncoding(withAllowedCharacters: allowed) else { return nil }
        return URL(string: "\(scheme)://\(action)?url=\(value)")
    }
}

/** The signature Apple puts on a copy of the app that someone signed themselves (SideStore, AltStore, Sideloadly, Xcode). */
enum AppSignature {
    /** Home says so this long before the signature runs out. */
    static let warnBefore: TimeInterval = 48 * 3600

    /// When the signature runs out, from the app's provisioning profile ("embedded.mobileprovision": a signed file
    /// with a property list inside). Nil when there is none, as in the App Store, TestFlight or the simulator.
    static func expiry(profile data: Data) -> Date? {
        guard let start = data.range(of: Data("<?xml".utf8)),
              let end = data.range(of: Data("</plist>".utf8), in: start.lowerBound..<data.endIndex) else { return nil }
        let plist = data.subdata(in: start.lowerBound..<end.upperBound)
        guard let info = (try? PropertyListSerialization.propertyList(from: plist, format: nil)) as? [String: Any] else { return nil }
        return info["ExpirationDate"] as? Date
    }
}
