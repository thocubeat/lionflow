import SwiftUI

/// Asks for confirmation (Face ID or the security code) before a remote action, as chosen in Settings.
struct ConfirmAction {
    let run: (String, @escaping () -> Void) -> Void
    func callAsFunction(_ label: String, _ action: @escaping () -> Void) { run(label, action) }
}

private struct ConfirmKey: EnvironmentKey {
    static let defaultValue = ConfirmAction { _, action in action() }
}

extension EnvironmentValues {
    var confirm: ConfirmAction {
        get { self[ConfirmKey.self] }
        set { self[ConfirmKey.self] = newValue }
    }
}

/** An action waiting for the security code. */
private struct PendingPin: Identifiable {
    let id = UUID()
    let label: String
    let action: () -> Void
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @EnvironmentObject private var updates: AppUpdates
    @Environment(\.colorScheme) private var system
    @Environment(\.scenePhase) private var scenePhase
    @State private var pinFor: PendingPin?

    private var colors: LfColors {
        switch model.prefs.theme {
        case "light": return .light
        case "dark": return .dark
        default: return system == .dark ? .dark : .light
        }
    }

    private func confirm(_ label: String, _ action: @escaping () -> Void) {
        // Nothing is sent to a real car in demo mode, and the sample car has no security code to ask for.
        if model.isDemo { action(); return }
        model.prewakeForAction() // wake the car while the user confirms
        let s = model.prefs
        if !s.remoteConfirm {
            action()
        } else if s.remoteBioOnly && platform.biometricAvailable {
            platform.authenticate(label, onSuccess: action, onFail: { _ in pinFor = PendingPin(label: label, action: action) })
        } else {
            pinFor = PendingPin(label: label, action: action)
        }
    }

    var body: some View {
        let c = colors
        ZStack {
            c.bg.ignoresSafeArea()
            switch model.ui.phase {
            case .loading: LionLogo(96)
            case .signedOut: SignInView()
            case .setupRemote: RemoteSetupView()
            case .locked: LockView()
            case .ready: MainShell()
            }
            if let label = model.ui.commandInProgress { CommandBanner(label: label, progress: model.ui.commandProgress) }
            if let msg = model.ui.message { Toast(message: msg) }
            if let pending = pinFor {
                PinDialog(title: pending.label, check: { model.checkPin($0) }, onDismiss: { pinFor = nil }, onSuccess: {
                    pinFor = nil
                    pending.action()
                })
            }
        }
        .environment(\.lf, c)
        .environment(\.confirm, ConfirmAction(run: confirm))
        .preferredColorScheme(model.prefs.theme == "system" ? nil : (c.isDark ? .dark : .light))
        .onAppear { updates.checkIfDue() }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                model.onForeground()
                updates.checkIfDue()
            } else if phase == .background {
                model.onBackground()
            }
        }
    }
}

/** "Unlock · Sending…" at the top while a command is on its way to the car. */
private struct CommandBanner: View {
    @Environment(\.lf) private var c
    let label: String
    let progress: String?

    var body: some View {
        VStack {
            HStack(spacing: 10) {
                Busy()
                T("\(label) · \(progress ?? "Sending…")").font(LfType.small).foregroundColor(c.text)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: 520)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.card))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(c.line, lineWidth: 1))
            .softShadow(c)
            .padding(.top, 12)
            .padding(.horizontal, 16)
            Spacer()
        }
        .accessibilityElement(children: .combine)
    }
}

/** A short message at the bottom that goes away by itself (after 4 seconds, 9 for a long one) or when tapped. */
private struct Toast: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let message: String

    var body: some View {
        VStack {
            Spacer()
            T(message).font(LfType.small)
                .foregroundColor(c.isDark ? Color(hex: 0xFF111214) : Color(hex: 0xFFF2F3F5))
                .multilineTextAlignment(.leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .frame(maxWidth: 520, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.isDark ? Color(hex: 0xFFF2F3F5) : Color(hex: 0xFF16181B)))
                .softShadow(c)
                .onTapGesture { model.clearMessage() }
                .padding(.horizontal, 16)
                .padding(.bottom, 100)
        }
        .task(id: message) {
            try? await Task.sleep(nanoseconds: message.count > 60 ? 9_000_000_000 : 4_000_000_000)
            if !Task.isCancelled && model.ui.message == message { model.clearMessage() }
        }
        .accessibilityAddTraits(.isStaticText)
    }
}

private struct NavItem: Identifiable {
    let tab: AppTab
    let label: String
    let icon: String
    var id: String { tab.rawValue }
}

/** All tabs in menu order. The phone menu shows as many as fit; the rest go under "More". */
private let allTabs = [
    NavItem(tab: .home, label: "Home", icon: LfIcons.home),
    NavItem(tab: .charging, label: "Charging", icon: LfIcons.bolt),
    NavItem(tab: .climate, label: "Climate", icon: LfIcons.thermo),
    NavItem(tab: .trips, label: "Trips", icon: LfIcons.route),
    NavItem(tab: .map, label: "Map", icon: LfIcons.map),
    NavItem(tab: .service, label: "Service", icon: LfIcons.wrench),
    NavItem(tab: .settings, label: "Settings", icon: LfIcons.gear),
]
private let railTabs = [
    NavItem(tab: .home, label: "Home", icon: LfIcons.home),
    NavItem(tab: .charging, label: "Charging", icon: LfIcons.bolt),
    NavItem(tab: .map, label: "Map", icon: LfIcons.map),
    NavItem(tab: .climate, label: "Climate", icon: LfIcons.thermo),
    NavItem(tab: .trips, label: "Trips", icon: LfIcons.route),
    NavItem(tab: .service, label: "Service", icon: LfIcons.wrench),
    NavItem(tab: .settings, label: "Settings", icon: LfIcons.gear),
]

private struct MainShell: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c

    private func open(_ tab: AppTab) {
        model.setTab(tab)
        if tab == .trips { model.loadTrips() }
    }

    var body: some View {
        GeometryReader { geo in
            let wide = geo.size.width >= 600
            if wide {
                // iPad: the navigation sits on the right, within thumb reach, with Home at the bottom.
                HStack(spacing: 0) {
                    TabContent(wide: true, expanded: geo.size.width >= 900).frame(maxWidth: .infinity, maxHeight: .infinity)
                    Rectangle().fill(c.navLine).frame(width: 1).ignoresSafeArea()
                    VStack(spacing: 14) {
                        LionLogo(32)
                        Spacer()
                        ForEach(railTabs.filter { Caps.tab($0.tab, model.ui.status) }.reversed()) { item in
                            RailItem(item: item, active: model.ui.tab == item.tab) { open(item.tab) }
                        }
                    }
                    .padding(.vertical, 24)
                    .frame(width: 88)
                    .frame(maxHeight: .infinity)
                    .background(c.nav.ignoresSafeArea())
                }
            } else {
                VStack(spacing: 0) {
                    TabContent(wide: false, expanded: false).frame(maxWidth: .infinity, maxHeight: .infinity)
                    PhoneBar(width: geo.size.width, open: open)
                }
            }
        }
    }
}

private struct PhoneBar: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let width: CGFloat
    let open: (AppTab) -> Void

    var body: some View {
        // About 72 points per button: show all if they fit, otherwise keep room for "More".
        let fit = Int((width - 40) / 72)
        let tabs = allTabs.filter { Caps.tab($0.tab, model.ui.status) }
        let shown = fit >= tabs.count ? tabs : Array(tabs.prefix(max(fit - 1, 3)))
        let more = tabs.filter { t in !shown.contains { $0.tab == t.tab } }
        let shape = RoundedRectangle(cornerRadius: 30, style: .continuous)
        HStack(spacing: 0) {
            ForEach(shown) { item in
                Button(action: { open(item.tab) }) {
                    PhoneNavLabel(icon: item.icon, label: item.label, active: model.ui.tab == item.tab)
                }
                .buttonStyle(LfPress())
                .accessibilityAddTraits(model.ui.tab == item.tab ? [.isSelected] : [])
            }
            if !more.isEmpty {
                Menu {
                    ForEach(more) { item in
                        Button(action: { open(item.tab) }) {
                            Label(tr(item.label), systemImage: item.icon)
                        }
                    }
                } label: {
                    PhoneNavLabel(icon: LfIcons.menu, label: "More", active: more.contains { $0.tab == model.ui.tab })
                }
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
        .background(shape.fill(c.card))
        .overlay(shape.strokeBorder(c.line, lineWidth: 1))
        .softShadow(c, 12)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(c.bg.ignoresSafeArea(edges: .bottom))
    }
}

private struct PhoneNavLabel: View {
    @Environment(\.lf) private var c
    let icon: String
    let label: String
    let active: Bool

    var body: some View {
        VStack(spacing: 4) {
            LfIcon(icon, active ? c.onAccent : c.text2, 22)
                .frame(width: 52, height: 32)
                .background(Capsule().fill(active ? c.accent : Color.clear))
            T(label).font(.system(size: 12, weight: active ? .semibold : .medium)).foregroundColor(active ? c.accentInk : c.text2)
                .fitLine(0.8)
        }
        .padding(.vertical, 2)
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

private struct RailItem: View {
    @Environment(\.lf) private var c
    let item: NavItem
    let active: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                LfIcon(item.icon, active ? c.onAccent : c.muted, 22)
                    .frame(width: 56, height: 32)
                    .background(Capsule().fill(active ? c.accent : Color.clear))
                T(item.label).font(.system(size: 12, weight: active ? .semibold : .medium)).foregroundColor(active ? c.text : c.muted)
                    .fitLine(0.8)
            }
            .padding(.vertical, 4)
            .frame(width: 72)
            .contentShape(Rectangle())
        }
        .buttonStyle(LfPress())
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }
}

private struct TabContent: View {
    @EnvironmentObject private var model: AppModel
    let wide: Bool
    let expanded: Bool

    var body: some View {
        switch model.ui.tab {
        case .home: HomeView(wide: wide, expanded: expanded)
        case .charging: ChargingView(wide: wide)
        case .map: MapView()
        case .climate: ClimateView(wide: wide)
        case .trips: TripsView(wide: wide)
        case .service: ServiceView(wide: wide)
        case .settings: SettingsView(wide: wide)
        }
    }
}
