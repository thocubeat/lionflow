import SwiftUI
import WatchConnectivity
import UIKit
import LocalAuthentication
import AuthenticationServices
import CoreLocation
import PhotosUI

/// Things only the phone can do, handed to the screens: Face ID, the sign-in page, where the phone is, sharing a
/// file, opening a link, picking a photo.
@MainActor
final class Platform: NSObject, ObservableObject {
    private let location = CLLocationManager()
    private var signIn: ASWebAuthenticationSession?
    /** Called when the phone's position is known (or allowed) for the first time. */
    var onLocation: (() -> Void)?

    override init() {
        super.init()
        location.delegate = self
        location.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    // MARK: Face ID / Touch ID

    /** An Apple Watch can be paired with this device (an iPhone, not an iPad). */
    var watchPossible: Bool { WCSession.isSupported() }

    var biometricAvailable: Bool {
        LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
    }

    /** "Face ID" or "Touch ID", whichever this phone has. */
    var biometricName: String {
        let ctx = LAContext()
        _ = ctx.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: nil)
        return ctx.biometryType == .touchID ? "Touch ID" : "Face ID"
    }

    var biometricIcon: String { biometricName == "Touch ID" ? LfIcons.touchId : LfIcons.faceId }

    func authenticate(_ title: String, onSuccess: @escaping () -> Void, onFail: @escaping (String) -> Void) {
        let ctx = LAContext()
        ctx.localizedFallbackTitle = tr("Use security code")
        ctx.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: tr(title)) { ok, error in
            DispatchQueue.main.async {
                if ok { onSuccess() } else { onFail(error?.localizedDescription ?? "") }
            }
        }
    }

    // MARK: Sign in

    /// Opens the carmaker's own sign-in page in the system's sign-in sheet. The page ends by sending the app an
    /// address that starts with `scheme`; that address (or nil when the sheet was closed) goes to `done`.
    func openSignIn(_ url: String, scheme: String, done: @escaping (URL?) -> Void) {
        guard let page = URL(string: url) else { done(nil); return }
        let session = ASWebAuthenticationSession(url: page, callbackURLScheme: scheme) { [weak self] answer, _ in
            DispatchQueue.main.async {
                self?.signIn = nil
                done(answer)
            }
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        signIn = session
        if !session.start() { signIn = nil; done(nil) }
    }

    // MARK: Position

    func requestLocation() {
        switch location.authorizationStatus {
        case .notDetermined: location.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse: location.requestLocation()
        default: break
        }
    }

    var locationAllowed: Bool {
        let s = location.authorizationStatus
        return s == .authorizedAlways || s == .authorizedWhenInUse
    }

    var locationDenied: Bool {
        let s = location.authorizationStatus
        return s == .denied || s == .restricted
    }

    /** Where the phone last was, if it may be known. */
    func lastKnownLocation() -> (lat: Double, lon: Double)? {
        guard locationAllowed, let c = location.location?.coordinate else { return nil }
        return (c.latitude, c.longitude)
    }

    // MARK: Out of the app

    func openUrl(_ url: String) {
        guard let u = URL(string: url) else { return }
        UIApplication.shared.open(u)
    }

    func openAppSettings() { openUrl(UIApplication.openSettingsURLString) }

    private func topController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap { $0.windows }.first { $0.isKeyWindow } ?? scenes.first?.windows.first
        var top = window?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }

    /** The system's share sheet, for a file or a text. */
    func share(_ items: [Any]) {
        guard let top = topController() else { return }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        // On an iPad the sheet is a bubble and needs something to point at.
        sheet.popoverPresentationController?.sourceView = top.view
        sheet.popoverPresentationController?.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
        sheet.popoverPresentationController?.permittedArrowDirections = []
        top.present(sheet, animated: true)
    }

    func copy(_ text: String) { UIPasteboard.general.string = text }

    static var systemName: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw -> String in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion) · \(machine)"
    }
}

extension Platform: ASWebAuthenticationPresentationContextProviding {
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        // Asked for on the main thread by the system.
        MainActor.assumeIsolated {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            return scenes.flatMap { $0.windows }.first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }
}

extension Platform: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            if self.locationAllowed { self.location.requestLocation() }
            self.onLocation?()
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in self.onLocation?() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

/** The photo library's picker, for one picture. Gives back JPEG data, made smaller so it loads fast. */
struct PhotoPicker: UIViewControllerRepresentable {
    let onPick: (Data) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPick) }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPick: (Data) -> Void
        init(_ onPick: @escaping (Data) -> Void) { self.onPick = onPick }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else { return }
            let done = onPick
            provider.loadObject(ofClass: UIImage.self) { object, _ in
                guard let image = object as? UIImage else { return }
                // At most 1600 points on the long side: plenty for the top of the home screen.
                let longest = max(image.size.width, image.size.height)
                let scale = min(1, 1600 / max(longest, 1))
                let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
                guard let data = small.jpegData(compressionQuality: 0.85) else { return }
                DispatchQueue.main.async { done(data) }
            }
        }
    }
}

/// Everything the app is built from, made once when it starts: the car logic's services, the app's state and the
/// phone's own helpers.
@MainActor
final class AppHolder: ObservableObject {
    /** One for the whole app: made when the app starts, also when the watch starts it in the background. */
    static let shared = AppHolder()

    let model: AppModel
    let platform: Platform
    private var watch: WatchBridge?

    private init() {
        // Text boxes with several lines draw their own background unless told not to (before iOS 16).
        UITextView.appearance().backgroundColor = .clear
        let bundle = Bundle.main
        func text(_ name: String, _ ext: String) -> String? {
            bundle.url(forResource: name, withExtension: ext).flatMap { try? String(contentsOf: $0, encoding: .utf8) }
        }
        // The texts in every language (the Android app's own file, then the few only this app has), and the
        // carmakers' sign-in settings.
        L10n.load([text("l10n", "tsv"), text("l10n-ios", "tsv")].compactMap { $0 })
        if let configs = text("stellantis_configs", "json") { try? StellantisConfig.setText(configs) }

        let files = FileManager.default
        let cache = files.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let support = files.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? files.createDirectory(at: cache, withIntermediateDirectories: true)
        try? files.createDirectory(at: support, withIntermediateDirectories: true)

        let services = AppServices(
            defaults: .standard, secrets: KeychainSecrets(),
            log: DefaultsStore("log"), demo: DefaultsStore("demo"), demoLog: DefaultsStore("demolog"),
            cacheDir: cache, filesDir: support)
        services.version = (bundle.infoDictionary?["CFBundleShortVersionString"] as? String) ?? "0"
        services.system = Platform.systemName
        let platform = Platform()
        services.lastKnownLocation = { platform.lastKnownLocation() }
        self.platform = platform
        model = AppModel(services)
        let model = self.model
        platform.onLocation = { model.loadLocationAndWeather() }
        applyLaunchArguments()
        let link = WatchLink(services)
        link.switchToNextCar = { await MainActor.run { model.switchToNextCar() } }
        watch = WatchBridge(link)
    }

    /// For the pictures taken when the app is built: "-demo electric|hybrid|fuel" opens the sample car, "-brand opel"
    /// picks its brand, "-tab Charging" a tab, "-theme light" the light colours, "-lang nb" a language, "-clean"
    /// hides the demo banner, "-signedout" shows the first screen. Only ever starts or leaves demo mode: a
    /// signed-in account is never touched.
    private func applyLaunchArguments() {
        let args = ProcessInfo.processInfo.arguments
        func value(_ name: String) -> String? {
            guard let i = args.firstIndex(of: name), i + 1 < args.count else { return nil }
            return args[i + 1]
        }
        if args.contains("-signedout") {
            if model.isDemo { model.exitDemo() }
            return
        }
        guard let kind = value("-demo") else { return }
        if let theme = value("-theme") { model.update { $0.theme = theme } }
        if let lang = value("-lang") { model.update { $0.language = lang } }
        if model.isDemo { model.exitDemo() }
        if model.ui.phase == .signedOut { model.startDemo(kind, value("-brand")) }
        guard model.isDemo else { return }
        model.hideDemoBanner = args.contains("-clean")
        if let tab = value("-tab").flatMap({ AppTab(rawValue: $0) }) { model.setTab(tab) }
    }
}
