import SwiftUI

/** The watch can start the app in the background to ask about the car: everything is made ready at launch. */
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        _ = AppHolder.shared
        return true
    }
}

@main
struct LionFlowApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var holder = AppHolder.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(holder.model)
                .environmentObject(holder.platform)
                .environmentObject(holder.updates)
        }
    }
}
