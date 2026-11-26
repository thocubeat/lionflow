import SwiftUI

@main
struct LionFlowApp: App {
    @StateObject private var holder = AppHolder()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(holder.model)
                .environmentObject(holder.platform)
        }
    }
}
