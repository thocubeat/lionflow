import SwiftUI
import UniformTypeIdentifiers

// The Settings tab.

private struct LinkRow: View {
    @Environment(\.lf) private var c
    let title: String
    let subtitle: String
    let icon: String
    var last = false
    var translateSubtitle = true
    let action: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                LfIcon(icon, c.text2, 18).frame(width: 36, height: 36).background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(c.raised))
                VStack(alignment: .leading, spacing: 0) {
                    T(title).font(LfType.label).foregroundColor(c.text)
                    (translateSubtitle ? T(subtitle) : Plain(subtitle)).font(LfType.tiny).foregroundColor(c.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LfIcon(LfIcons.chevron, c.muted, 16)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(minHeight: 56)
            .tap(action)
            if !last { LfDivider() }
        }
    }
}

private struct SegRow: View {
    @Environment(\.lf) private var c
    let title: String
    let options: [(String, String)]
    let selected: String
    let onSelect: (String) -> Void

    var body: some View {
        HStack {
            T(title).font(LfType.label).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
            Segmented(options: options, selected: selected, onSelect: onSelect).layoutPriority(1)
        }
        .padding(.leading, 16)
        .padding(.trailing, 12)
        .padding(.vertical, 10)
    }
}

/** A row that opens a short list to choose from. `options` are (value, label) pairs. */
private struct DropdownRow: View {
    @Environment(\.lf) private var c
    let title: String
    let subtitle: String
    let options: [(Int, String)]
    let selected: Int
    let onSelect: (Int) -> Void

    var body: some View {
        Menu {
            ForEach(options, id: \.0) { option in
                Button(action: { onSelect(option.0) }) { Text(verbatim: tr(option.1)) }
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 0) {
                    T(title).font(LfType.label).foregroundColor(c.text)
                    T(subtitle).font(LfType.tiny).foregroundColor(c.muted).multilineTextAlignment(.leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                T((options.first { $0.0 == selected }?.1 ?? "Never") + " ▾").font(LfType.label).foregroundColor(c.accentInk)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
    }
}

/** A small sheet with a title, some text fields or other content, and Cancel / Save. */
private struct EditSheet<Content: View>: View {
    @Environment(\.lf) private var c
    let title: String
    var saveLabel = "Save"
    let onCancel: () -> Void
    let onSave: () -> Void
    @ViewBuilder var content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                T(title).font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
                content()
                HStack {
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onCancel)
                    T(saveLabel).font(LfType.label).foregroundColor(c.accentInk).padding(12).tap(onSave)
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
    }
}

/** Which of the Settings tab's sheets is open. */
private enum SettingsSheet: String, Identifiable {
    case name, battery, language, log, feedback, carData, homePhone, homeWatch
    var id: String { rawValue }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @EnvironmentObject private var updates: AppUpdates
    @Environment(\.lf) private var c
    let wide: Bool
    @State private var sheet: SettingsSheet?
    @State private var confirmSignOut = false
    @State private var importing = false

    var body: some View {
        ScreenColumn(wide: wide, refreshing: model.ui.refreshing, onRefresh: { model.refresh() }) {
            if !wide { BackLink(label: "Home") { model.setTab(.home) } }
            T("Settings").font(LfType.title).foregroundColor(c.text)
            look
            profile
            homeScreens
            units
            security
            data
            about
            ending
        }
        .sheet(item: $sheet) { which in
            sheetContent(which).environmentObject(model).environmentObject(platform).environment(\.lf, c)
        }
        .alert(isPresented: $confirmSignOut) {
            Alert(title: Text(verbatim: tr("Sign out?")),
                  message: Text(verbatim: tr("This removes your Peugeot session and remote-control setup from this phone.")),
                  primaryButton: .destructive(Text(verbatim: tr("Sign out"))) { model.signOut() },
                  secondaryButton: .cancel(Text(verbatim: tr("Cancel"))))
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText, .json, .data]) { result in
            guard case .success(let url) = result else { return }
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            if let data = try? Data(contentsOf: url) {
                model.importTripsCsv(String(decoding: data, as: UTF8.self))
            } else {
                model.toastText("Couldn't read that file")
            }
        }
        .onAppear { updates.checkIfDue(soon: true) }
    }

    @ViewBuilder private func sheetContent(_ which: SettingsSheet) -> some View {
        switch which {
        case .name: NameEditor { sheet = nil }
        case .battery: BatteryEditor { sheet = nil }
        case .language:
            PickerSheet(title: "Language", options: L10n.options.map { ($0.0, $0.0 == "system" ? tr("System default") : $0.1) },
                        selected: model.prefs.language, translate: false) { code in
                model.update { $0.language = code }
                sheet = nil
            }
        case .log: LogSheet { sheet = nil }
        case .feedback: FeedbackSheet { sheet = nil }
        case .carData: CarDataSheet { sheet = nil }
        case .homePhone: HomeEditor(target: "phone") { sheet = nil }
        case .homeWatch: HomeEditor(target: "watch") { sheet = nil }
        }
    }

    // MARK: Sections

    @ViewBuilder private var look: some View {
        let system = model.prefs.theme == "system"
        GroupCard {
            ToggleRow(title: "Dark mode", subtitle: system ? "Follows the phone right now" : nil, on: c.isDark) { v in model.update { $0.theme = v ? "dark" : "light" } }
            ToggleRow(title: "Follow the phone's setting", subtitle: "Switches between light and dark with the phone", on: system, showDivider: false) { v in
                let dark = c.isDark
                model.update { $0.theme = v ? "system" : (dark ? "dark" : "light") }
            }
        }
    }

    @ViewBuilder private var profile: some View {
        let ui = model.ui
        let s = model.prefs
        let garage = model.garage()
        SectionTitle("Profile")
        GroupCard {
            HStack(spacing: 14) {
                Plain(s.carName.first.map { String($0).uppercased() } ?? "L").font(.system(size: 24, weight: .bold)).foregroundColor(c.onAccent)
                    .frame(width: 56, height: 56).background(Circle().fill(c.accent))
                VStack(alignment: .leading, spacing: 0) {
                    Plain(s.carName).font(.system(size: 17, weight: .semibold)).foregroundColor(c.text)
                    T("MyPeugeot account" + (ui.vehicle.map { " · VIN …\($0.vin.suffix(6))" } ?? "")).font(LfType.small).foregroundColor(c.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                T("Edit").font(.system(size: 14, weight: .semibold)).foregroundColor(c.accentInk).padding(8).tap { sheet = .name }
            }
            .padding(16)
            if garage.reduce(0, { $0 + $1.cars.count }) > 1 || garage.count > 1 { cars(garage, ui) }
            mildHybrid(ui)
            LfDivider()
            T("Add another car or account").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .tap { model.addAccount() }
            T("Another brand (for example Citroën next to Peugeot) needs its own sign-in. Your other accounts stay signed in.").font(LfType.tiny).foregroundColor(c.muted)
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
            LfDivider()
            T(ui.reloadingVehicles ? "Reloading cars…" : "Reload cars from MyPeugeot").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .tap { if !ui.reloadingVehicles { model.reloadVehicles() } }
        }
    }

    @ViewBuilder private func cars(_ garage: [AppModel.GarageAccount], _ ui: UiState) -> some View {
        LfDivider()
        T("My cars").font(LfType.small).foregroundColor(c.muted).padding(.leading, 16).padding(.top, 10)
        ForEach(garage) { acc in
            ForEach(acc.cars, id: \.vin) { v in
                let sel = acc.active && v.vin == ui.vehicle?.vin
                HStack {
                    VStack(alignment: .leading, spacing: 0) {
                        Plain(model.garageName(acc, v)).font(.system(size: 15, weight: sel ? .semibold : .regular)).foregroundColor(c.text)
                        Plain(acc.brand.display + " · " + tr(model.vehicleKind(v)) + " · VIN …" + String(v.vin.suffix(6))).font(LfType.tiny).foregroundColor(c.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if sel { T("Selected").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk) }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .tap { model.switchTo(acc.id, v.vin) }
            }
        }
    }

    /** "Is this a 48 V mild hybrid?": not for a car that is plainly electric (e-5008, ë-C3 …), unless it was set by hand. */
    @ViewBuilder private func mildHybrid(_ ui: UiState) -> some View {
        if let vehicle = ui.vehicle, !model.isDemo {
            let chosen = model.mildHybridOverride(vehicle)
            let plainElectric = model.vehicleKind(vehicle) == "Electric" || (ui.status?.energyType == "Electric" && ui.status?.fuelPct == nil)
            if !(plainElectric && chosen == nil) {
                LfDivider()
                DropdownRow(title: "48 V mild hybrid", subtitle: detectionText(vehicle.mildHybridDetection),
                            options: [(0, "Automatic"), (1, "On"), (2, "Off")], selected: chosen == nil ? 0 : (chosen == true ? 1 : 2)) { mode in
                    model.setMildHybridOverride(mode == 0 ? nil : mode == 1)
                }
            }
        }
    }

    private func detectionText(_ d: MildHybridDetection) -> String {
        switch d {
        case .vehicleMetadata: return "Automatic · Recognized from vehicle metadata"
        case .modelName: return "Automatic · Recognized from model name"
        case .otherPowertrain: return "Automatic · Different powertrain"
        case .insufficientMetadata: return "Automatic · Insufficient vehicle information"
        }
    }

    @ViewBuilder private var homeScreens: some View {
        let s = model.prefs
        SectionTitle("Home screens")
        GroupCard {
            LinkRow(title: "Phone", subtitle: "Choose the cards on Home and their order", icon: LfIcons.home) { sheet = .homePhone }
            if platform.watchPossible {
                LinkRow(title: "Watch", subtitle: "Choose what the watch shows", icon: LfIcons.gear) { sheet = .homeWatch }
            }
            ToggleRow(title: "Note when the car is being driven", subtitle: "At the top of Home", on: s.bannerDriving) { v in model.update { $0.bannerDriving = v } }
            ToggleRow(title: "Note when privacy mode is on", subtitle: "It blocks remote commands and can hide where the car is", on: s.bannerPrivacy) { v in model.update { $0.bannerPrivacy = v } }
            ToggleRow(title: "Note when fog lights are left on", subtitle: "While the car is parked", on: s.bannerFog, showDivider: false) { v in model.update { $0.bannerFog = v } }
        }
    }

    @ViewBuilder private var units: some View {
        let s = model.prefs
        SectionTitle("Units")
        GroupCard {
            SegRow(title: "Distance", options: [("km", "km"), ("mi", "miles")], selected: s.distanceUnit) { v in model.update { $0.distanceUnit = v } }
            LfDivider()
            SegRow(title: "Temperature", options: [("c", "°C"), ("f", "°F")], selected: s.tempUnit) { v in model.update { $0.tempUnit = v } }
            LfDivider()
            SegRow(title: "Clock", options: [("24", "24 h"), ("12", "12 h"), ("system", "System")], selected: s.timeFormat) { v in model.update { $0.timeFormat = v } }
            if Caps.battery(model.ui.status) {
                LfDivider()
                LinkRow(title: "Battery size", subtitle: s.batteryKwh > 0 ? "\(JSONValue.javaInt(s.batteryKwh)) kWh usable · used to work out charging kW"
                    : "Automatic (from the car) · used to work out charging kW and energy", icon: LfIcons.bolt, last: true) { sheet = .battery }
            }
        }
    }

    @ViewBuilder private var security: some View {
        let s = model.prefs
        let bio = platform.biometricName
        SectionTitle("Security")
        GroupCard {
            ToggleRow(title: "Unlock with \(bio)", subtitle: "Instead of typing your security code", on: s.bioUnlock) { v in model.update { $0.bioUnlock = v } }
            ToggleRow(title: "Confirm remote actions", subtitle: "Lock, unlock, climate and charging need confirmation", on: s.remoteConfirm) { v in model.update { $0.remoteConfirm = v } }
            ToggleRow(title: "\(bio) only", subtitle: "Skip the code — \(bio) confirms remote actions", on: s.remoteBioOnly) { v in model.update { $0.remoteBioOnly = v } }
            LinkRow(title: model.remoteReady ? "Remote control is set up" : "Set up remote control",
                    subtitle: model.remoteReady ? "Set up again with a new SMS code" : "SMS code + MyPeugeot security code", icon: LfIcons.shield) { model.openRemoteSetup() }
            LinkRow(title: "Command & error log", subtitle: "Exact answers and errors from Peugeot", icon: LfIcons.route, last: true) { sheet = .log }
        }
        SubscriptionNotice()
    }

    @ViewBuilder private var data: some View {
        let s = model.prefs
        SectionTitle("Driving data")
        GroupCard {
            LinkRow(title: "All car data", subtitle: "Every value the car reports, and the raw data", icon: LfIcons.car) { sheet = .carData }
            LinkRow(title: "Export driving data", subtitle: "Trips as a CSV file", icon: LfIcons.share) {
                if model.ui.trips.isEmpty { model.loadTrips() }
                if let file = try? model.exportTripsCsv() { platform.share([file]) } else { model.toastText("Couldn't make the file") }
            }
            LinkRow(title: "Import driving data", subtitle: "From LionFlow, or the official app's CSV or .myp export", icon: LfIcons.download) { importing = true }
            ToggleRow(title: "Tag similar trips automatically", subtitle: "A trip with about the same distance and average speed as one you tagged gets the same tag", on: s.autoTagTrips) { v in model.setAutoTag(v) }
            ToggleRow(title: "Auto-refresh when open", subtitle: "When the app opens or is in use — at most every 5 min", on: s.autoRefresh) { v in model.update { $0.autoRefresh = v } }
            ToggleRow(title: "Wake car when app opens", subtitle: "Commands go through faster. Uses a little of the 12 V battery; at most every 10 min. When this is off, the car is only woken when you tap refresh",
                      on: s.wakeOnOpen, showDivider: false) { v in model.update { $0.wakeOnOpen = v } }
        }
        SectionTitle("Chargers on the map")
        GroupCard {
            T("Free and worldwide, no key needed. Data from OpenStreetMap volunteers — usually complete, power is sometimes missing.").font(LfType.tiny).foregroundColor(c.muted).padding(16)
        }
    }

    /// "New versions": which build this is, and whether a newer one is out. Tapping installs the newer one, or reads
    /// the list again; a copy built in Xcode, or one that couldn't check, gets the download page.
    @ViewBuilder private var newVersions: some View {
        let mine = model.services.version
        if updates.builtByHand {
            LinkRow(title: "New versions", subtitle: "You have \(mine). Newer ones are on LionFlow's page on GitHub", icon: LfIcons.download) {
                platform.openUrl(IOSRelease.pageURL)
            }
        } else if let a = updates.available {
            LinkRow(title: "New versions", subtitle: "You have \(mine). \(a.version) is ready: tap to install", icon: LfIcons.download) {
                updates.install()
            }
        } else if updates.latest != nil && !updates.lastCheckFailed {
            LinkRow(title: "New versions", subtitle: "You have \(mine), the newest", icon: LfIcons.download) {
                Task { await updates.check() }
            }
        } else {
            LinkRow(title: "New versions", subtitle: "You have \(mine). Newer ones are on LionFlow's page on GitHub", icon: LfIcons.download) {
                platform.openUrl(IOSRelease.pageURL)
                Task { await updates.check() }
            }
        }
    }

    @ViewBuilder private var about: some View {
        let s = model.prefs
        SectionTitle("App")
        GroupCard {
            newVersions
            if let until = updates.signedUntilText {
                LinkRow(title: "App signature", subtitle: "Valid until \(until). SideStore, AltStore or Sideloadly renews it: tap to see how that can happen by itself",
                        icon: "checkmark.seal.fill") {
                    platform.openUrl(IOSRelease.howToURL)
                }
            }
            LinkRow(title: "Send feedback", subtitle: "Ideas, problems or bugs, straight to the developer", icon: "envelope.fill", last: true) { sheet = .feedback }
        }
        SectionTitle("Appearance")
        GroupCard {
            LinkRow(title: "Language", subtitle: s.language == "system" ? tr("System default") : (L10n.options.first { $0.0 == s.language }?.1 ?? tr("System default")),
                    icon: "globe", last: true, translateSubtitle: false) { sheet = .language }
        }
        SectionTitle("Support LionFlow")
        GroupCard {
            VStack(alignment: .leading, spacing: 10) {
                T("LionFlow is free, has no ads and never will. It's made in my spare time. If it makes your Peugeot a bit nicer to live with, a small donation helps keep it going. Thank you! ☕")
                    .font(LfType.small).foregroundColor(c.text2)
                PrimaryButton("Buy me a coffee on Ko-fi") { platform.openUrl("https://ko-fi.com/thocubeat") }
            }
            .padding(16)
        }
    }

    @ViewBuilder private var ending: some View {
        if model.isDemo {
            SecondaryButton("Exit demo", textColor: c.danger) { model.exitDemo() }.padding(.top, 14)
        } else {
            SecondaryButton("Sign out", textColor: c.danger) { confirmSignOut = true }.padding(.top, 14)
        }
        T("LionFlow \(model.services.version) · Unofficial: not affiliated with Stellantis, Peugeot, Citroën, DS Automobiles, Opel or Vauxhall.\nMap: Apple Maps · Chargers © OpenStreetMap contributors · Weather MET Norway")
            .font(LfType.tiny).foregroundColor(c.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
    }
}

private struct NameEditor: View {
    @EnvironmentObject private var model: AppModel
    let onClose: () -> Void
    @State private var name = ""
    @State private var loaded = false

    var body: some View {
        EditSheet(title: "Car name", onCancel: onClose, onSave: {
            let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
            model.update { $0.carName = typed.isEmpty ? "My " + Brands.current.display : typed }
            onClose()
        }) {
            LfField(label: "Car name", text: $name)
                .onChange(of: name) { v in if v.count > 24 { name = String(v.prefix(24)) } }
        }
        .onAppear {
            if loaded { return }
            loaded = true
            name = model.prefs.carName
        }
    }
}

private struct BatteryEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let onClose: () -> Void
    @State private var text = ""
    @State private var loaded = false

    var body: some View {
        EditSheet(title: "Battery size", onCancel: onClose, onSave: {
            var kwh = 0.0
            if let v = JSONValue.javaParseDouble(text), v >= 10, v <= 200 { kwh = v }
            model.update { $0.batteryKwh = kwh }
            onClose()
        }) {
            T("Usable capacity in kWh. Leave empty to use what the car reports. Examples: E-5008 73 kWh, e-208/e-2008 46–51 kWh, plug-in hybrids about 10–12 kWh.")
                .font(LfType.small).foregroundColor(c.muted)
            LfField(label: "kWh", text: $text, keyboard: .decimalPad)
                .onChange(of: text) { v in
                    let clean = String(v.replacingOccurrences(of: ",", with: ".").filter { $0.isNumber || $0 == "." }.prefix(5))
                    if clean != v { text = clean }
                }
        }
        .onAppear {
            if loaded { return }
            loaded = true
            let kwh = model.prefs.batteryKwh
            text = kwh > 0 ? String(JSONValue.javaInt(kwh)) : ""
        }
    }
}

/** What was sent to the car and what came back, word for word: for working out why something failed. */
private struct LogSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    let onClose: () -> Void

    var body: some View {
        let text = model.commandLog()
        VStack(alignment: .leading, spacing: 10) {
            T("Command & error log").font(LfType.stat).foregroundColor(c.text)
            ScrollView {
                Plain(text).font(.system(size: 11, design: .monospaced)).lineSpacing(3).foregroundColor(c.text2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack {
                Spacer()
                T("Close").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onClose)
                T("Copy").font(LfType.label).foregroundColor(c.accentInk).padding(12).tap {
                    platform.copy(text)
                    model.toastText("Copied")
                }
            }
        }
        .padding(24)
        .background(c.card.ignoresSafeArea())
    }
}

/** Feedback to the developer by e-mail, optionally with technical details (no position, names or VIN). */
private struct FeedbackSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    let onClose: () -> Void
    @State private var message = ""
    @State private var details = true
    @State private var showDetails = false
    @State private var tech = ""

    private func send() {
        let text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return }
        let body = text + (details ? "\n\n---- Technical details ----\n\(tech)" : "")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        let subject = "LionFlow feedback (\(model.services.version), iOS)".addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        let encoded = String(body.prefix(6000)).addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        guard let url = URL(string: "mailto:antpqth@gmail.com?subject=\(subject)&body=\(encoded)"), UIApplication.shared.canOpenURL(url) else {
            // No e-mail app set up: the text goes to the share sheet instead.
            platform.share([body])
            return
        }
        UIApplication.shared.open(url)
        onClose()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                T("Send feedback").font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
                T("Write anything: an idea, something that doesn't work, or what happened just before a problem.").font(LfType.small).foregroundColor(c.muted)
                TextEditor(text: $message)
                    .clearEditorBackground()
                    .font(LfType.body)
                    .foregroundColor(c.text)
                    .frame(minHeight: 130)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(c.raised))
                    .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(c.line2, lineWidth: 1))
                    .accessibilityLabel(Text(verbatim: tr("Your message")))
                    .onChange(of: message) { v in if v.count > 5000 { message = String(v.prefix(5000)) } }
                HStack(alignment: .top, spacing: 12) {
                    LfToggle(on: details, label: "Include technical details") { details = $0 }
                    VStack(alignment: .leading, spacing: 0) {
                        T("Include technical details").font(LfType.label).foregroundColor(c.text)
                        T("App and iOS version, phone and car model, the car's latest battery data and the command & error log. No position, names, e-mail or VIN. The more I get, the easier it is to help.")
                            .font(LfType.tiny).foregroundColor(c.muted)
                    }
                }
                if details {
                    T(showDetails ? "Hide what is sent" : "Show what is sent").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                        .padding(.vertical, 4)
                        .tap { showDetails.toggle() }
                }
                if details && showDetails { Plain(tech).font(.system(size: 10, design: .monospaced)).lineSpacing(2).foregroundColor(c.text2) }
                T("Opens your e-mail app with everything filled in. Tap send there.").font(LfType.tiny).foregroundColor(c.muted)
                HStack {
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onClose)
                    T("Send").font(LfType.label).foregroundColor(message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? c.muted : c.accentInk).padding(12).tap(send)
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
        .onAppear { if tech.isEmpty { tech = model.techDetails() } }
    }
}
