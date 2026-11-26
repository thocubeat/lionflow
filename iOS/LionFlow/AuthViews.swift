import SwiftUI

// Signing in, the sample car, setting up remote control, and the lock screen.

private struct AuthColumn<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 18) { content() }
                .padding(.horizontal, 24)
                .padding(.vertical, 32)
                .frame(maxWidth: 460)
                .frame(maxWidth: .infinity)
        }
        // On a small phone these screens are taller than the screen: what is scrolled up stays out of the clock.
        .overlay(StatusBarBackdrop(), alignment: .top)
    }
}

private struct SecurityNote: View {
    @Environment(\.lf) private var c
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            LfIcon(LfIcons.shield, c.accentInk, 18)
            T(text).font(LfType.tiny).lineSpacing(2).foregroundColor(c.text2).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(c.line, lineWidth: 1))
    }
}

struct SignInView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    // Step 1: pick the brand. Step 2: sign in. Step 3: set up the demo (from "Try without an account").
    @State private var step = 0

    var body: some View {
        let shown = step == 0 ? (model.ui.signingIn ? 2 : 1) : step
        AuthColumn {
            VStack(spacing: 12) {
                LionLogo(84)
                Wordmark(size: 34)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 24)
            .padding(.bottom, 4)
            if shown == 1 {
                T("Choose your car brand").font(LfType.title).foregroundColor(c.text).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                BrandGrid { b in
                    model.update { $0.brand = b.id }
                    step = 2
                }
                // Demo is only for people with no car yet; when adding another car there is a way back instead.
                if model.canCancelSignIn() {
                    SecondaryButton("Back to my cars") { model.cancelSignIn() }
                } else {
                    SecondaryButton("Try without an account") { step = 3 }
                }
                T("LionFlow is an independent, unofficial app. It is not affiliated with, endorsed or sponsored by Stellantis, Peugeot, Citroën, DS Automobiles, Opel or Vauxhall. The brand names belong to their owners and are only used to say which accounts and cars LionFlow works with.")
                    .font(LfType.tiny).foregroundColor(c.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
            } else if shown == 3 {
                DemoSetup { step = 1 }
            } else {
                Plain(Brand.of(model.prefs.brand).display).font(LfType.title).foregroundColor(c.text).frame(maxWidth: .infinity)
                CountryRow()
                Button(action: { model.update { $0.rememberMe.toggle() } }) {
                    HStack(spacing: 12) {
                        let on = model.prefs.rememberMe
                        RoundedRectangle(cornerRadius: 5, style: .continuous).fill(on ? c.meter : Color.clear)
                            .frame(width: 22, height: 22)
                            .overlay(RoundedRectangle(cornerRadius: 5, style: .continuous).strokeBorder(on ? c.meter : c.muted, lineWidth: 2))
                            .overlay(on ? LfIcon(LfIcons.check, .white, 13) : nil)
                        T("Remember me").font(LfType.body).foregroundColor(c.text)
                        Spacer()
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityValue(Text(verbatim: tr(model.prefs.rememberMe ? "On" : "Off")))
                if model.ui.signingIn {
                    HStack(spacing: 10) {
                        Busy()
                        T("Signing in…").font(LfType.body).foregroundColor(c.text)
                    }
                    .frame(maxWidth: .infinity)
                } else {
                    PrimaryButton("Sign in", icon: LfIcons.lock) {
                        platform.openSignIn(model.authorizeUrl(), scheme: model.redirectScheme) { answer in
                            if let answer = answer { model.handleRedirect(answer) }
                        }
                    }
                    SecondaryButton("Change brand") { step = 1 }
                }
                SubscriptionNotice()
            }
        }
    }
}

/** Step 1 of signing in: one large square button per brand. */
struct BrandGrid: View {
    @Environment(\.lf) private var c
    let onPick: (Brand) -> Void

    var body: some View {
        let brands = Brand.allCases
        let rows = stride(from: 0, to: brands.count, by: 2).map { Array(brands[$0..<min($0 + 2, brands.count)]) }
        VStack(spacing: 12) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 12) {
                    ForEach(row, id: \.self) { b in
                        Button(action: { onPick(b) }) {
                            // A square tile, as wide as half the row, with the name in the middle. Brand names are
                            // shown as they are, in every language.
                            RoundedRectangle(cornerRadius: 24, style: .continuous).fill(c.card)
                                .aspectRatio(1, contentMode: .fit)
                                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(c.line, lineWidth: 1))
                                .overlay(
                                    Plain(b.display).font(.system(size: 22, weight: .semibold)).foregroundColor(c.text)
                                        .fitLine(0.6).padding(.horizontal, 10)
                                )
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(LfPress())
                        .accessibilityLabel(Text(verbatim: b.display))
                    }
                    if row.count == 1 { Color.clear.frame(maxWidth: .infinity).frame(height: 1) }
                }
            }
        }
    }
}

func countryName(_ code: String) -> String {
    let name = Locale.current.localizedString(forRegionCode: code) ?? ""
    return name.isEmpty ? code : name
}

/** Which country the carmaker account is from. */
struct CountryRow: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @State private var picking = false

    var body: some View {
        let s = model.prefs
        let countries = StellantisConfig.countries(Brand.of(s.brand))
        let effective = [s.country, "NO", "GB"].first { countries.contains($0) } ?? countries.first ?? s.country
        Button(action: { picking = true }) {
            HStack {
                T("Country of your account").font(LfType.small).foregroundColor(c.muted)
                Spacer()
                Plain(countryName(effective)).font(LfType.label).foregroundColor(c.text)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.card))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(c.line, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(LfPress())
        .sheet(isPresented: $picking) {
            PickerSheet(title: "Country of your account", options: countries.sorted { countryName($0) < countryName($1) }.map { ($0, countryName($0)) },
                        selected: effective, translate: false) { cc in
                model.update { $0.country = cc }
                picking = false
            }
            .environment(\.lf, c)
        }
    }
}

/** A list to pick one thing from, as a sheet. `options` are (id, label) pairs. */
struct PickerSheet: View {
    @Environment(\.lf) private var c
    let title: String
    let options: [(String, String)]
    let selected: String
    var translate = true
    let onPick: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            T(title).font(LfType.stat).foregroundColor(c.text).padding(.horizontal, 24).padding(.top, 24).padding(.bottom, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(options, id: \.0) { option in
                        let on = option.0 == selected
                        Button(action: { onPick(option.0) }) {
                            HStack {
                                (translate ? T(option.1) : Plain(option.1))
                                    .font(.system(size: 15, weight: on ? .semibold : .regular)).foregroundColor(on ? c.accentInk : c.text)
                                Spacer()
                                if on { LfIcon(LfIcons.check, c.accentInk, 16) }
                            }
                            .padding(.vertical, 12)
                            .padding(.horizontal, 24)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(LfPress())
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(c.card.ignoresSafeArea())
    }
}

/** Plain-words list of what needs a subscription for the chosen brand. */
struct SubscriptionNotice: View {
    @Environment(\.lf) private var c
    var body: some View {
        let b = Brands.current
        HStack(alignment: .top, spacing: 10) {
            LfIcon(LfIcons.shield, c.orangeInk, 18)
            VStack(alignment: .leading, spacing: 6) {
                T("What needs a subscription").font(LfType.label).foregroundColor(c.text)
                T("This is decided by \(b.display), not by LionFlow.").font(LfType.tiny).foregroundColor(c.muted)
                T("Status, battery, range, trips and service: your \(b.app) account is enough.").font(LfType.tiny).lineSpacing(2).foregroundColor(c.text2)
                T("Charging control and pre-heating (electric and plug-in hybrid cars): \(b.eRemotePack).").font(LfType.tiny).lineSpacing(2).foregroundColor(c.text2)
                T("Lock, unlock, lights and horn: \(b.doorsPack).").font(LfType.tiny).lineSpacing(2).foregroundColor(c.text2)
                T("Which pack is included, and for how long, depends on the model, country and order date. Check and activate it in \(b.app). LionFlow can't activate it for you.")
                    .font(LfType.tiny).lineSpacing(2).foregroundColor(c.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(c.line, lineWidth: 1))
    }
}

/** One line shown next to a remote function when remote control isn't working yet. */
struct NeedsPackHint: View {
    @Environment(\.lf) private var c
    let electric: Bool
    var body: some View {
        let b = Brands.current
        T("Needs " + (electric ? b.eRemotePack : b.doorsPack) + " from \(b.display). Activate it in \(b.app), then set up remote control in Settings.")
            .font(LfType.tiny).foregroundColor(c.muted)
    }
}

/// The step after "Try without an account": which kind of sample car to look at (electric, plug-in hybrid, or
/// petrol or diesel) and of which brand. The brand decides the names the app uses while the demo runs.
private struct DemoSetup: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let onBack: () -> Void
    @State private var kind = "electric"
    @State private var brand = ""

    private let kinds = [
        ("electric", "Electric", "Battery, charging and pre-heating"),
        ("hybrid", "Plug-in hybrid", "Battery for short trips, fuel for long ones"),
        ("fuel", "Petrol / diesel", "Fuel level, fuel cost and statistics"),
    ]

    var body: some View {
        let chosen = brand.isEmpty ? Brand.of(model.prefs.brand).id : brand
        T("Try a sample car").font(LfType.title).foregroundColor(c.text).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        T("Sample car and made-up data. Nothing is sent to a real car.").font(LfType.small).foregroundColor(c.muted)
            .multilineTextAlignment(.center).frame(maxWidth: .infinity)

        T("Kind of car").font(LfType.label).foregroundColor(c.text)
        VStack(spacing: 10) {
            ForEach(kinds, id: \.0) { item in
                let on = item.0 == kind
                let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
                Button(action: { kind = item.0 }) {
                    HStack(spacing: 12) {
                        VStack(alignment: .leading, spacing: 2) {
                            T(item.1).font(LfType.label).foregroundColor(c.text)
                            T(item.2).font(LfType.tiny).foregroundColor(c.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Circle().fill(on ? c.accent : c.raised).frame(width: 24, height: 24)
                            .overlay(on ? LfIcon(LfIcons.check, c.onAccent, 14) : nil)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .frame(minHeight: 60)
                    .background(shape.fill(c.card))
                    .overlay(shape.strokeBorder(on ? c.accentInk : c.line, lineWidth: on ? 2 : 1))
                    .contentShape(Rectangle())
                }
                .buttonStyle(LfPress())
                .accessibilityAddTraits(on ? [.isSelected] : [])
            }
        }

        T("Brand").font(LfType.label).foregroundColor(c.text)
        FlowRow(items: Brand.allCases) { b in
            let on = b.id == chosen
            Button(action: { brand = b.id }) {
                Plain(b.display).font(LfType.label).foregroundColor(on ? c.onAccent : c.text)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(Capsule().fill(on ? c.accent : c.card))
                    .overlay(Capsule().strokeBorder(on ? c.accent : c.line, lineWidth: 1))
            }
            .buttonStyle(LfPress())
            .accessibilityAddTraits(on ? [.isSelected] : [])
        }

        PrimaryButton("Start the demo") { model.startDemo(kind, chosen) }
        SecondaryButton("Back", action: onBack)
    }
}

struct RemoteSetupView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @State private var sms = ""
    @State private var pin = ""

    var body: some View {
        let ui = model.ui
        AuthColumn {
            LionLogo(56).padding(.top, 8)
            T("Enable remote control").font(LfType.title).foregroundColor(c.text)
            T("To lock, unlock, pre-heat and control charging from LionFlow, Peugeot needs to confirm this phone once: we send you an SMS code, and you enter it together with your 4-digit MyPeugeot security code.")
                .font(LfType.body).lineSpacing(3).foregroundColor(c.text2)
            SubscriptionNotice()
            if !ui.smsSent {
                PrimaryButton(ui.busy ? "Sending…" : "Send SMS code", enabled: !ui.busy) { model.requestSms() }
            } else {
                LfField(label: "SMS code", text: $sms, keyboard: .numberPad)
                    .onChange(of: sms) { v in
                        let clean = String(v.filter { $0.isLetter || $0.isNumber }.prefix(12))
                        if clean != v { sms = clean }
                    }
                LfField(label: "MyPeugeot security code (4 digits)", text: $pin, keyboard: .numberPad, secure: true)
                    .onChange(of: pin) { v in
                        let clean = String(v.filter { $0.isNumber }.prefix(4))
                        if clean != v { pin = clean }
                    }
                PrimaryButton(ui.busy ? "Activating…" : "Activate", enabled: !ui.busy && sms.count >= 4 && pin.count == 4) {
                    model.activateRemote(sms, pin)
                }
                T("Didn't get it? Send again").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                    .padding(.vertical, 8)
                    .tap { model.requestSms() }
            }
            if let error = ui.setupError { T(error).font(LfType.small).foregroundColor(c.danger) }
            SecurityNote(text: "Your security code is stored encrypted on this phone only and is used to sign remote commands, exactly like the MyPeugeot app. Too many wrong codes can make Peugeot block remote control for a while.")
            SecondaryButton("Skip for now — status only") { model.skipRemoteSetup() }
        }
    }
}

struct LockView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    @State private var code = ""
    @State private var error = false

    private var bio: Bool { model.prefs.bioUnlock && platform.biometricAvailable }

    private func tryBio() {
        platform.authenticate("Unlock LionFlow", onSuccess: { model.unlock() }, onFail: { _ in })
    }

    private func entered(_ digit: String) {
        guard code.count < 4, !error else { return }
        code += digit
        guard code.count == 4 else { return }
        if model.checkPin(code) {
            model.unlock()
        } else {
            error = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                code = ""
                error = false
            }
        }
    }

    var body: some View {
        AuthColumn {
            VStack(spacing: 10) {
                LionLogo(52)
                T(error ? "Wrong code" : "Enter security code").font(.system(size: 26, weight: .semibold)).foregroundColor(error ? c.danger : c.text)
                T(bio ? "Your 4-digit MyPeugeot code, or use \(platform.biometricName)" : "Your 4-digit MyPeugeot code")
                    .font(LfType.small).foregroundColor(c.muted).multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 24)
            PinDots(filled: code.count, error: error)
            PinPad(bioIcon: bio ? platform.biometricIcon : nil, onDigit: entered, onDelete: { code = String(code.dropLast()) }, onBio: tryBio)
                .padding(.top, 16)
            T("Forgot your code? Sign out and sign in again").font(LfType.small).foregroundColor(c.muted).multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .tap { model.signOut() }
        }
        .onAppear { if bio { tryBio() } }
    }
}

struct PinDots: View {
    @Environment(\.lf) private var c
    let filled: Int
    let error: Bool

    var body: some View {
        HStack(spacing: 18) {
            ForEach(0..<4, id: \.self) { i in
                let on = i < filled
                let col = error ? c.danger : c.text
                Circle().fill(on ? col : Color.clear).frame(width: 16, height: 16)
                    .overlay(Circle().strokeBorder(on ? col : c.bar, lineWidth: 2))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: tr("\(filled) of 4 digits entered")))
    }
}

struct PinPad: View {
    @Environment(\.lf) private var c
    /** The Face ID / Touch ID symbol, or nil when the key isn't shown. */
    let bioIcon: String?
    let onDigit: (String) -> Void
    let onDelete: () -> Void
    let onBio: () -> Void

    private let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["bio", "0", "del"]]

    var body: some View {
        VStack(spacing: 14) {
            ForEach(rows, id: \.self) { row in
                HStack(spacing: 22) {
                    ForEach(row, id: \.self) { key in
                        switch key {
                        case "bio":
                            Button(action: onBio) {
                                Group { if let icon = bioIcon { LfIcon(icon, c.accentInk, 30) } else { Color.clear } }
                                    .frame(maxWidth: .infinity).frame(height: 64).contentShape(Rectangle())
                            }
                            .buttonStyle(LfPress())
                            .disabled(bioIcon == nil)
                            .accessibilityLabel(Text(verbatim: tr("Use Face ID or Touch ID")))
                            .accessibilityHidden(bioIcon == nil)
                        case "del":
                            Button(action: onDelete) {
                                LfIcon(LfIcons.backspace, c.text, 26).frame(maxWidth: .infinity).frame(height: 64).contentShape(Rectangle())
                            }
                            .buttonStyle(LfPress())
                            .accessibilityLabel(Text(verbatim: tr("Delete")))
                        default:
                            Button(action: { onDigit(key) }) {
                                Plain(key).font(.system(size: 26, weight: .semibold)).foregroundColor(c.text)
                                    .frame(maxWidth: .infinity).frame(height: 64)
                                    .background(Capsule().fill(c.card))
                                    .overlay(Capsule().strokeBorder(c.line, lineWidth: 1))
                                    .contentShape(Capsule())
                            }
                            .buttonStyle(LfPress())
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 12)
    }
}

/** Asks for the 4-digit security code before a remote action. */
struct PinDialog: View {
    @Environment(\.lf) private var c
    let title: String
    let check: (String) -> Bool
    let onDismiss: () -> Void
    let onSuccess: () -> Void
    @State private var code = ""
    @State private var error = false

    private func entered(_ digit: String) {
        guard code.count < 4, !error else { return }
        code += digit
        guard code.count == 4 else { return }
        if check(code) {
            onSuccess()
        } else {
            error = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                code = ""
                error = false
            }
        }
    }

    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea().onTapGesture(perform: onDismiss)
            VStack(spacing: 12) {
                T("\(title)?").font(LfType.stat).foregroundColor(c.text).multilineTextAlignment(.center)
                T(error ? "Wrong code — try again" : "Enter your 4-digit security code").font(LfType.small).foregroundColor(error ? c.danger : c.muted)
                PinDots(filled: code.count, error: error)
                PinPad(bioIcon: nil, onDigit: entered, onDelete: { code = String(code.dropLast()) }, onBio: {})
                T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onDismiss)
            }
            .padding(24)
            .frame(maxWidth: 380)
            .background(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(c.card))
            .padding(.horizontal, 24)
        }
        .accessibilityAddTraits(.isModal)
    }
}
