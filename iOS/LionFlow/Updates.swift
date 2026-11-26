import SwiftUI
import UIKit

/// Whether a newer build of the app is out, and when the signature on this copy runs out (see IOSRelease). It
/// doesn't install or renew anything itself: an iPhone app can't. It hands over to SideStore or AltStore.
@MainActor
final class AppUpdates: ObservableObject {
    /** The newest build in LionFlow's source, once it has been read. */
    @Published private(set) var latest: IOSRelease.Build?
    @Published private(set) var checking = false
    @Published private(set) var lastCheckFailed = false
    /** The build whose note on Home was put away with "Later". */
    @Published private var dismissed: Int

    let version: String
    let build: String
    /** When the signature runs out. Nil for a copy without one (App Store, TestFlight, the simulator). */
    let signedUntil: Date?
    /** For the walk-through: a newer build and a signature that runs out soon, without asking anyone. */
    private let sample: Bool

    private let defaults = UserDefaults.standard
    private static let checkedKey = "iosUpdateChecked"
    private static let dismissedKey = "iosUpdateDismissed"
    /** The source is read at most this often by itself (and whenever asked in Settings). */
    private static let every: TimeInterval = 6 * 3600

    init(bundle: Bundle = .main) {
        version = (bundle.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0"
        build = (bundle.infoDictionary?["CFBundleVersion"] as? String) ?? ""
        dismissed = UserDefaults.standard.integer(forKey: Self.dismissedKey)
        // "-sample-updates" for the pictures and the walk-through: a build 0.1.9999 and 20 hours of signature left.
        sample = ProcessInfo.processInfo.arguments.contains("-sample-updates")
        if sample {
            signedUntil = Date().addingTimeInterval(20 * 3600)
            latest = IOSRelease.Build(version: "0.1.9999", build: 9999, downloadURL: IOSRelease.pageURL,
                                      notes: "A sample of what a new build says about itself.")
            dismissed = 0
        } else {
            signedUntil = bundle.url(forResource: "embedded", withExtension: "mobileprovision")
                .flatMap { try? Data(contentsOf: $0) }
                .flatMap { AppSignature.expiry(profile: $0) }
        }
    }

    /// A copy built by hand in Xcode has build number 1: it is not compared with the published builds (it may well
    /// be newer than all of them).
    var builtByHand: Bool { !sample && (Int(build) ?? 1) <= 1 }

    /** The newer build there is, if any. */
    var available: IOSRelease.Build? {
        guard let l = latest, !builtByHand, IOSRelease.isNewer(l, thanVersion: version, build: build) else { return nil }
        return l
    }

    var showUpdateNote: Bool { available.map { $0.build != dismissed } ?? false }

    var signatureRunsOutSoon: Bool { signedUntil.map { $0.timeIntervalSinceNow < AppSignature.warnBefore } ?? false }

    /** "Tue 3 Dec 14:20", in the app's language and clock. */
    var signedUntilText: String? {
        signedUntil.map { TimeFmt.format(Int64($0.timeIntervalSince1970 * 1000), "EEE d MMM HH:mm") }
    }

    /** Reads the source when the app opens or comes back, at most every few hours. */
    func checkIfDue() {
        let last = defaults.double(forKey: Self.checkedKey)
        if Date().timeIntervalSince1970 - last >= Self.every || latest == nil && last == 0 {
            Task { await check() }
        }
    }

    func check() async {
        if checking || sample || builtByHand { return }
        checking = true
        defer { checking = false }
        do {
            let req = try Http.request(IOSRelease.sourceURL, headers: [("User-Agent", Http.contactAgent)])
            let found = IOSRelease.latest(inSource: try await Http.text(req))
            if let found = found { latest = found }
            lastCheckFailed = found == nil
            defaults.set(Date().timeIntervalSince1970, forKey: Self.checkedKey)
        } catch {
            lastCheckFailed = true
        }
    }

    /** "Later": the note goes away until there is a build newer than this one. */
    func later() {
        guard let a = available else { return }
        dismissed = a.build
        if !sample { defaults.set(a.build, forKey: Self.dismissedKey) }
    }

    /** Hands the new build to SideStore or AltStore; the download page when neither is on the phone. */
    func install() {
        guard let a = available else { return }
        open(IOSRelease.installLinks(a.downloadURL) + [URL(string: IOSRelease.pageURL)].compactMap { $0 })
    }

    /** Opens SideStore or AltStore to renew the signature; how-to page when neither is on the phone (Sideloadly, Xcode). */
    func openRefreshTool() {
        open(IOSRelease.toolLinks + [URL(string: IOSRelease.howToURL)].compactMap { $0 })
    }

    /** Tries the links in turn, until one opens. */
    private func open(_ links: [URL]) {
        guard let first = links.first else { return }
        let rest = Array(links.dropFirst())
        UIApplication.shared.open(first, options: [:]) { opened in
            if !opened { Task { @MainActor in self.open(rest) } }
        }
    }
}

/// The notes about the app itself at the top of Home: a newer build is out, or the signature runs out within two
/// days (with SideStore refreshing it every day, that one never shows).
struct AppUpdateNotes: View {
    @EnvironmentObject private var updates: AppUpdates
    @Environment(\.lf) private var c

    var body: some View {
        if updates.showUpdateNote, let a = updates.available {
            LfCard(radius: 20, padding: 14) {
                HStack(spacing: 10) {
                    LfIcon(LfIcons.download, c.accentInk, 20)
                    T("Update available").font(LfType.label).foregroundColor(c.text)
                }
                T("LionFlow \(a.version) is ready. You have \(updates.version).").font(LfType.small).foregroundColor(c.text2)
                if !a.notes.isEmpty {
                    Plain(a.notes).font(LfType.tiny).foregroundColor(c.muted).lineLimit(3)
                }
                HStack(spacing: 10) {
                    PrimaryButton("Install now") { updates.install() }
                    SecondaryButton("Later") { updates.later() }
                }
                T("Opens SideStore or AltStore, which installs it over this copy. Your sign-in and data stay.")
                    .font(LfType.tiny).foregroundColor(c.muted)
            }
        }
        if updates.signatureRunsOutSoon, let until = updates.signedUntilText {
            LfCard(radius: 20, padding: 14) {
                HStack(spacing: 10) {
                    LfIcon("checkmark.seal.fill", c.orangeInk, 20)
                    T("Refresh LionFlow soon").font(LfType.label).foregroundColor(c.text)
                }
                T("Apple's signature on this copy of LionFlow is valid until \(until). After that it won't open until SideStore, AltStore or Sideloadly has refreshed it. Your sign-in and data stay.")
                    .font(LfType.small).foregroundColor(c.text2)
                SecondaryButton("Refresh it now") { updates.openRefreshTool() }
                T("Opens SideStore or AltStore. With Sideloadly, the computer does it when the phone is on the same Wi-Fi.")
                    .font(LfType.tiny).foregroundColor(c.muted)
            }
        }
    }
}
