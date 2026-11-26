import SwiftUI

// Screens still being written: each one is replaced by its own file.

private struct ComingSoon: View {
    @Environment(\.lf) private var c
    let title: String
    var body: some View {
        ScreenColumn {
            ScreenHeader(kicker: nil, title: title)
            LfCard { T("This screen is on its way.").font(LfType.body).foregroundColor(c.text2) }
        }
    }
}

struct ChargingView: View { let wide: Bool; var body: some View { ComingSoon(title: "Charging") } }
struct ClimateView: View { let wide: Bool; var body: some View { ComingSoon(title: "Climate") } }
struct TripsView: View { let wide: Bool; var body: some View { ComingSoon(title: "Trips") } }
struct ServiceView: View { let wide: Bool; var body: some View { ComingSoon(title: "Service") } }
struct MapView: View { var body: some View { ComingSoon(title: "Map") } }
struct SettingsView: View { let wide: Bool; var body: some View { ComingSoon(title: "Settings") } }
