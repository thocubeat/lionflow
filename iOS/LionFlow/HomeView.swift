import SwiftUI
import UIKit

// The Home tab: the car's photo with its name and status on it, then the cards the user chose, in their order.

func chargingLine(_ st: VehicleStatus?) -> String {
    guard let st = st else { return "Waiting for data…" }
    let next = st.nextDelayedHour.map { TimeFmt.hm($0, st.nextDelayedMinute ?? 0) }
    if st.isCharging {
        let power = ChargePower.label(st).map { " at \($0)" } ?? ""
        return "Charging" + power + (ChargePower.leftText(st) ?? "")
    }
    if st.plugged, let next = next { return "Plugged in · charging from \(next)" }
    if st.plugged {
        switch st.chargingStatus {
        case "Finished": return "Plugged in · full"
        case "Stopped": return "Plugged in · paused"
        default: return "Plugged in · not charging"
        }
    }
    return "Not plugged in"
}

/** The next enabled pre-heating schedule, e.g. "Mon 07:30". */
func nextSchedule(_ st: VehicleStatus?) -> String? {
    guard let programs = st?.programs.filter({ $0.enabled && !$0.days.isEmpty }), !programs.isEmpty else { return nil }
    let days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    let now = Calendar.current.dateComponents([.weekday, .hour, .minute], from: Date())
    let today = ((now.weekday ?? 1) + 5) % 7 // Mon = 0
    let nowMin = (now.hour ?? 0) * 60 + (now.minute ?? 0)
    var best: (Int, String)? = nil
    for p in programs {
        for d in p.days {
            guard let di = days.firstIndex(of: d) else { continue }
            var offset = (di - today + 7) % 7 * 1440 + p.hour * 60 + p.minute - nowMin
            if offset < 0 { offset += 7 * 1440 }
            let label = ((offset < 1440 && di == today) ? "today" : d) + " " + TimeFmt.hm(p.hour, p.minute)
            if best == nil || offset < best!.0 { best = (offset, label) }
        }
    }
    return best?.1
}

/** The start of today and of the six days before it, oldest first. */
private func lastSevenDays() -> [Int64] {
    let today = Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000)
    return (0...6).reversed().map { today - Int64($0) * 86_400_000 }
}

private func consumption(_ km: Double, _ kwh: Double, _ s: AppSettings) -> String {
    s.distanceUnit == "mi" ? Fmt.fixed(km / kwh / 1.609344, 1) + " mi/kWh" : Fmt.fixed(kwh / km * 100, 1) + " kWh/100 km"
}

func fuelMoney(_ v: Double, _ currency: String) -> String { Fmt.fixed(v, v < 10 ? 1 : 0) + " " + currency }

/** "64", or a dash when the car hasn't said. */
func wholeNumber(_ v: Double?) -> String {
    guard let v = v else { return "—" }
    return String(JSONValue.javaInt(v))
}

private let onPhoto = Color(hex: 0xFFF4F5F7)
private let onPhotoMuted = Color(hex: 0xD9E6E8EC)

private extension View {
    func photoShadow(_ on: Bool = true) -> some View {
        shadow(color: on ? Color.black.opacity(0.7) : .clear, radius: 7, x: 0, y: 2)
    }
}

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let wide: Bool
    let expanded: Bool
    @State private var editing = false
    @State private var pastPicture = false

    /** Asks for the address of where the car stands whenever the car has moved or the saved places changed. */
    private var parkedKey: String {
        guard let st = model.ui.status else { return "" }
        return "\(st.lat ?? 0),\(st.lon ?? 0),\(st.moving),\(model.dataVersion)"
    }

    private func lookUpPlace() {
        if let st = model.ui.status, !st.moving { model.updateParkedPlace(st.lat, st.lon) }
    }

    var body: some View {
        GeometryReader { geo in
            // The width a card has for what is in it: the column, less the page's and the card's own margins.
            let column = wide ? (min(geo.size.width, 980) - 54) / 2 : geo.size.width - 40
            ZStack(alignment: .top) {
                LfScroll(refreshing: model.ui.refreshing, onRefresh: { model.refresh() }, passAt: 8, onPassed: { pastPicture = $0 },
                         topInset: c.isDark ? geo.safeAreaInsets.top : 0) {
                    VStack(spacing: 0) {
                        Hero(topInset: c.isDark ? geo.safeAreaInsets.top : 0)
                        if wide {
                            HStack(alignment: .top, spacing: 14) {
                                VStack(spacing: 14) { notes; cards(left: true) }.frame(maxWidth: .infinity)
                                VStack(spacing: 14) { cards(left: false); customize }.frame(maxWidth: .infinity)
                            }
                            .padding(.horizontal, 20)
                            .padding(.top, 12)
                            .padding(.bottom, 20)
                            .frame(maxWidth: 980)
                        } else {
                            VStack(spacing: 14) { notes; cards(left: nil); customize }
                                .padding(.horizontal, 20)
                                .padding(.top, 12)
                                .padding(.bottom, 20)
                        }
                    }
                    .ownHeight()
                    .environment(\.lfNarrowColumn, column - 44 < 285)
                }
                // In the dark look the top of the picture goes up behind the clock, as on Android. That only reads
                // well while the page stands still at the top: as soon as it is scrolled, the car's name and then
                // the cards would pass through the clock, so the page's own colour comes in behind it. In the light
                // look the clock is dark too, so there the picture starts below it and the colour is always there.
                .ignoresSafeArea(edges: c.isDark ? .top : [])
                StatusBarBackdrop()
                    .opacity(c.isDark && !pastPicture ? 0 : 1)
                    .animation(.easeOut(duration: 0.15), value: pastPicture)
                    .allowsHitTesting(false)
            }
        }
        .onAppear(perform: lookUpPlace)
        .onChange(of: parkedKey) { _ in lookUpPlace() }
        .sheet(isPresented: $editing) {
            HomeEditor(target: "phone") { editing = false }
                .environmentObject(model)
                .environment(\.lf, c)
        }
    }

    @ViewBuilder private var notes: some View {
        if model.isDemo && !model.hideDemoBanner { DemoBanner() }
        NoStatusCard()
        QuietCarCard()
        HomeBanners()
    }

    /// The cards the user chose, in their order. On an iPad they stand in two columns: the car's state on the
    /// left, the rest on the right.
    @ViewBuilder private func cards(left: Bool?) -> some View {
        let leftIds: Set<String> = ["battery", "lock", "actions", "parked", "charging"]
        ForEach(HomeLayout.phoneCards(model.prefs).filter { left == nil || leftIds.contains($0) == left! }, id: \.self) { id in
            switch id {
            case "warnings": WarningsCard()
            case "battery": BatteryCard()
            case "lock": LockLine()
            case "actions": QuickActions()
            case "parked": ParkedCard()
            case "tripsClimate": TripsClimateRow()
            case "service": ServiceCard()
            case "fuel": FuelMonthCard()
            case "charging": if Caps.battery(model.ui.status) { ChargingSummary() }
            case "monthCost": if Caps.battery(model.ui.status) { MonthCostCard() }
            case "cardata": CarDataCard()
            default: EmptyView()
            }
        }
    }

    /** The way in to choosing what Home shows. */
    private var customize: some View {
        T("Customize this screen").font(.system(size: 13, weight: .semibold)).foregroundColor(c.muted)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 10)
            .tap { editing = true }
    }
}

/** "Driving", "Parked at Home", "Parked at <charger or address>", or just "Parked" while the place is being looked up. */
@MainActor private func parkedLabel(_ model: AppModel, _ st: VehicleStatus) -> String {
    if st.moving { return "Driving" }
    guard let place = model.parkedAt else { return "Parked" }
    return "Parked at " + (place.hasPrefix("@") ? String(place.dropFirst()) : place)
}

/** Tap the car name to switch between cars (also across brands) when there is more than one. */
private struct CarSwitcher<Name: View>: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @ViewBuilder var name: () -> Name

    var body: some View {
        if model.hasSeveralCars() {
            Menu {
                ForEach(model.garage()) { acc in
                    ForEach(acc.cars, id: \.vin) { v in
                        Button(action: { model.switchTo(acc.id, v.vin) }) {
                            Text(verbatim: model.garageName(acc, v) + " · " + acc.brand.display + " · " + tr(model.vehicleKind(v)))
                        }
                    }
                }
            } label: {
                HStack(spacing: 4) {
                    name()
                    Plain("▾").foregroundColor(onPhotoMuted)
                }
            }
        } else {
            name()
        }
    }
}

private struct Hero: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let topInset: CGFloat
    @State private var spins = 0.0
    @State private var picking = false
    @State private var photo: UIImage?

    private func loadPhoto() {
        guard let stamped = model.ui.photoPath else { photo = nil; return }
        let path = stamped.components(separatedBy: "?")[0]
        photo = UIImage(contentsOfFile: path)
    }

    var body: some View {
        let ui = model.ui
        let st = ui.status
        ZStack {
            if let photo = photo {
                Color.clear.overlay(Image(uiImage: photo).resizable().scaledToFill()).clipped()
                    .accessibilityLabel(Text(verbatim: tr("Photo of your car")))
            } else {
                LinearGradient(colors: [Color(hex: 0xFF23272D), Color(hex: 0xFF15171A)], startPoint: .top, endPoint: .bottom)
            }
            // Shade at the top and bottom so the text stays readable on any photo, fading into the page.
            LinearGradient(stops: [
                .init(color: Color(hex: 0xD9000000), location: 0), .init(color: Color(hex: 0x99000000), location: 0.22),
                .init(color: Color(hex: 0x00000000), location: 0.45), .init(color: Color(hex: 0x00000000), location: 0.62),
                .init(color: Color(hex: 0x66000000), location: 0.8), .init(color: c.bg.opacity(0.9), location: 0.92), .init(color: c.bg, location: 1),
            ], startPoint: .top, endPoint: .bottom)

            if photo == nil {
                VStack(spacing: 0) {
                    LfIcon(LfIcons.camera, c.accent, 24).frame(width: 52, height: 52).background(Circle().fill(Color.white.opacity(0.2)))
                    T("Add a photo of your car").font(LfType.label).foregroundColor(onPhoto).padding(.top, 8)
                    T("Saved on this phone").font(LfType.tiny).foregroundColor(onPhotoMuted)
                }
                .padding(16)
                .tap { picking = true }
            }

            VStack(spacing: 0) {
                top(ui, st)
                Spacer(minLength: 0)
                bottom(ui, st)
            }
        }
        .frame(height: 400 + topInset)
        .frame(maxWidth: .infinity)
        .clipped()
        // In the dark look the picture begins at the very top of the screen. When the page is pulled down, what
        // comes into view above it is the same near-black the picture's shade begins with, not the page's colour:
        // otherwise a lighter band with a hard edge shows up there.
        .background(Group { if c.isDark { Color(hex: 0xFF050607).frame(height: 900).offset(y: -900) } }, alignment: .top)
        .onAppear(perform: loadPhoto)
        .onChange(of: model.ui.photoPath) { _ in loadPhoto() }
        .sheet(isPresented: $picking) { PhotoPicker { data in model.savePhoto(data) }.ignoresSafeArea() }
    }

    /** Brand, car name, last update and the refresh button. */
    private func top(_ ui: UiState, _ st: VehicleStatus?) -> some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    LionLogo(22)
                    (Text(verbatim: "Lion").foregroundColor(onPhoto) + Text(verbatim: "Flow").foregroundColor(c.accent))
                        .font(.system(size: 17, weight: .bold)).photoShadow()
                }
                CarSwitcher {
                    Plain(model.prefs.carName).font(.system(size: 40, weight: .heavy)).tracking(-1).foregroundColor(onPhoto)
                        .fitLine(0.6).photoShadow()
                }
                T(ui.refreshing ? "Waking car…" : "Updated \(minutesAgo(st?.updatedAtMillis ?? ui.lastRefresh, ui.now))")
                    .font(.system(size: 13, weight: .medium)).foregroundColor(onPhoto).photoShadow()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: {
                withAnimation(.easeInOut(duration: 0.9)) { spins += 1 }
                model.refreshNow()
            }) {
                LfIcon(LfIcons.refresh, onPhoto, 24)
                    .rotationEffect(.degrees(spins * 360))
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(Color(hex: 0x8C1A1C20)))
                    .overlay(Circle().strokeBorder(Color.white.opacity(0.15), lineWidth: 1))
                    .contentShape(Circle())
            }
            .buttonStyle(LfPress())
            .accessibilityLabel(Text(verbatim: tr("Refresh car data now")))
        }
        .padding(.horizontal, 20)
        .padding(.top, topInset + 12)
        .padding(.bottom, 12)
    }

    /** Change photo, the weather, and whether the car is parked or driving. */
    private func bottom(_ ui: UiState, _ st: VehicleStatus?) -> some View {
        VStack(alignment: .trailing, spacing: 14) {
            if photo != nil {
                HStack(spacing: 8) {
                    LfIcon(LfIcons.camera, onPhoto, 16)
                    T("Change photo").font(.system(size: 13, weight: .medium)).foregroundColor(onPhoto)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(Capsule().fill(Color(hex: 0xB30D0E10)))
                .tap { picking = true }
            }
            HStack(spacing: 12) {
                if let w = ui.weather {
                    HStack(spacing: 10) {
                        LfIcon(LfIcons.cloud, c.text, 22)
                        T("\(fmtTemp(w.tempC, model.prefs)) · \(WeatherApi.describe(w.symbol))").font(LfType.body).foregroundColor(c.text)
                            .lineLimit(1).photoShadow(c.isDark)
                    }
                    .tap { model.setTab(.climate) }
                }
                Spacer(minLength: 0)
                if let st = st {
                    HStack(spacing: 8) {
                        LfIcon(LfIcons.pin, c.text, 20)
                        T(parkedLabel(model, st)).font(LfType.body).foregroundColor(c.text).lineLimit(1).photoShadow(c.isDark)
                    }
                    .tap { model.setTab(.map) }
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 14)
    }
}

/** Shown on Home in demo mode. */
struct DemoBanner: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 0) {
                T("Demo mode").font(LfType.label).foregroundColor(c.blueInk)
                T("Sample car and made-up data. Nothing is sent to a real car.").font(LfType.tiny).foregroundColor(c.text2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            T("Sign in").font(LfType.label).foregroundColor(c.accentInk).padding(6).tap { model.exitDemo() }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.raisedBlue))
    }
}

/** Older petrol/diesel cars often have no live status from the carmaker; say so instead of failing. */
private struct NoStatusCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        if model.ui.statusMissing {
            LfCard(radius: 20, padding: 14) {
                T("No live status for this car").font(LfType.label).foregroundColor(c.text)
                T("The carmaker doesn't send live data (battery, doors, position) for this model. Trips, fuel economy and service info still work.")
                    .font(LfType.tiny).foregroundColor(c.muted)
            }
        }
    }
}

/// The car itself hasn't sent anything new for hours. Nothing an app can force: say so plainly, with what usually
/// helps, so it doesn't look like LionFlow is stuck.
private struct QuietCarCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @State private var settled = false
    @State private var hiddenFor: Int64 = 0

    var body: some View {
        let ui = model.ui
        Group {
            if let st = ui.status, let at = st.updatedAtMillis, !model.isDemo, !ui.statusMissing, !st.moving,
               // With remote control the app wakes the car on open, so 6 h means it didn't answer; without it, a parked car can be quiet overnight.
               ui.now - at >= Int64(model.remoteReady ? 6 : 24) * 3_600_000, settled, hiddenFor != at, !ui.refreshing {
                LfCard(radius: 20, padding: 14) {
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            T("The car hasn't sent new data").font(LfType.label).foregroundColor(c.text)
                            T("Last report \(minutesAgo(at, ui.now)). LionFlow shows what the car last sent to the carmaker's server. If the official app has no new data either, the car's connection is asleep or the carmaker's service has a problem, and no app can fix that from the phone.")
                                .font(LfType.tiny).foregroundColor(c.muted)
                            T("A short drive, or locking and unlocking with the key, usually wakes it up. If it lasts for days, ask your dealer to check the car's connected services.")
                                .font(LfType.tiny).foregroundColor(c.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        T("OK").font(LfType.label).foregroundColor(c.accentInk).padding(8).tap { hiddenFor = at }
                    }
                }
            }
        }
        // Give the wake-up on open a moment before saying anything.
        .task {
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            settled = true
        }
    }
}

/** Notes at the top of Home. Each one can be turned off in Settings. */
struct HomeBanners: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let s = model.prefs
        if let st = model.ui.status {
            if s.bannerDriving && st.moving {
                HomeBanner(icon: LfIcons.car, tint: c.blueInk, title: "The car is being driven", text: "The numbers are from its last report and can lag behind while it is on the road.")
            }
            if s.bannerPrivacy && st.privacyOn {
                HomeBanner(icon: LfIcons.shield, tint: c.orangeInk, title: "Privacy mode is on in the car", text: "It blocks remote commands and can hide where the car is. It is turned off on the car's own screen.")
            }
            if s.bannerFog && !st.fogLightsOn.isEmpty && !st.moving {
                HomeBanner(icon: LfIcons.bulb, tint: c.orangeInk, title: "Fog lights are on", text: "The car is parked with its fog lights on.")
            }
        }
    }
}

private struct HomeBanner: View {
    @Environment(\.lf) private var c
    let icon: String
    let tint: Color
    let title: String
    let text: String
    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        HStack(spacing: 12) {
            LfIcon(icon, tint, 20).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.raised))
            VStack(alignment: .leading, spacing: 2) {
                T(title).font(LfType.label).foregroundColor(c.text)
                T(text).font(LfType.tiny).foregroundColor(c.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(14)
        .background(shape.fill(c.card))
        .overlay(shape.strokeBorder(c.line2, lineWidth: 1))
    }
}

/** Warnings the car itself reports. Nothing is drawn when there are none. */
private struct WarningsCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let alerts = model.ui.alerts
        if !alerts.isEmpty {
            LfCard(radius: 24, padding: 18) {
                HStack(spacing: 12) {
                    LfIcon(LfIcons.shield, c.orangeInk, 26)
                    T(alerts.count == 1 ? "1 warning from the car" : "\(alerts.count) warnings from the car").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
                }
                ForEach(Array(alerts.prefix(8).enumerated()), id: \.offset) { _, a in
                    HStack(spacing: 10) {
                        // The car's own name for the warning, shown as it is.
                        Plain(a.label).font(LfType.body).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
                        if let at = a.atMillis { Plain(TimeFmt.format(at, "d MMM · HH:mm")).font(LfType.tiny).foregroundColor(c.muted) }
                    }
                }
                T("As the car reports them. The car's own screen and handbook say what to do.").font(LfType.tiny).foregroundColor(c.muted)
            }
        }
    }
}

/** True when the cards on Home sit in a narrow column: a small phone, or an iPad with two columns side by side. */
private struct NarrowColumnKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    var lfNarrowColumn: Bool {
        get { self[NarrowColumnKey.self] }
        set { self[NarrowColumnKey.self] = newValue }
    }
}

private struct BatteryCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.lfNarrowColumn) private var narrow

    var body: some View {
        let st = model.ui.status
        let battery = Caps.battery(st)
        let parts = fmtDistance(st?.rangeKm, model.prefs).components(separatedBy: " ")
        // The level and the range stand side by side and are never cut short or squeezed: on a narrow card, and for
        // a range with four figures, the letters are simply set smaller.
        let long = (parts.first ?? "").count >= 5
        let levelSize: CGFloat = narrow ? 52 : 64
        let rangeSize: CGFloat = (narrow ? 28 : 34) - (long ? 6 : 0)
        LfCard(radius: 28, padding: 22, onTap: battery ? { model.setTab(.charging) } : nil) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 0) {
                    T(st?.energyType == "Fuel" ? "Fuel" : "Battery").font(LfType.body).foregroundColor(c.muted)
                    (Text(verbatim: wholeNumber(st?.batteryPct)).font(.system(size: levelSize, weight: .bold))
                        + Text(verbatim: " %").font(.system(size: narrow ? 24 : 30, weight: .bold)))
                        .foregroundColor(c.text).lineLimit(1).fixedSize()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(spacing: 0) {
                    (Text(verbatim: "≈").font(.system(size: narrow ? 18 : 22, weight: .bold))
                        + Text(verbatim: parts.first ?? "—").font(.system(size: rangeSize, weight: .bold))
                        + Text(verbatim: parts.count > 1 ? " " + parts.dropFirst().joined(separator: " ") : "").font(.system(size: narrow ? 15 : 18, weight: .semibold)))
                        .foregroundColor(c.onAccent).lineLimit(1).fixedSize()
                    T(st?.rangeEstimated == true ? "Range (est.)" : "Range").font(.system(size: 13, weight: .medium)).foregroundColor(c.onAccent)
                        .lineLimit(1).fixedSize()
                }
                .padding(.horizontal, narrow ? 14 : 18)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(c.accent))
            }
            ProgressBar(fraction: (st?.batteryPct ?? 0) / 100, color: c.meter).padding(.top, 6)
            if battery {
                HStack(spacing: 10) {
                    LfIcon(st?.isCharging == true ? LfIcons.bolt : LfIcons.plug, st?.isCharging == true ? c.accentInk : c.text2, 20)
                    T(chargingLine(st)).font(LfType.body).foregroundColor(c.text2).frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.top, 4)
            }
        }
    }
}

private struct LockLine: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let st = model.ui.status
        HStack(spacing: 12) {
            switch st?.locked {
            case .some(true):
                LfIcon(LfIcons.lock, c.accentInk, 24)
                T(st?.openDoors.isEmpty == true ? "Locked · Doors closed" : "Locked").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
            case .some(false):
                LfIcon(LfIcons.unlock, c.orangeInk, 24)
                T("Unlocked").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
            case .none:
                LfIcon(LfIcons.lock, c.muted, 24)
                T("Doors —").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

private struct QuickAction: View {
    @Environment(\.lf) private var c
    let icon: String
    let label: String
    var highlighted = false
    var busy = false
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 22, style: .continuous)
        Button(action: action) {
            VStack(spacing: 8) {
                if busy { Busy().frame(height: 28) } else { LfIcon(icon, highlighted ? c.onAccent : c.text, 28) }
                T(label).font(.system(size: 15, weight: .semibold)).foregroundColor(highlighted ? c.onAccent : c.text).fitLine(0.7)
            }
            .padding(.vertical, 16)
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity, minHeight: 96)
            .background(shape.fill(highlighted ? c.accent : c.card))
            .overlay(shape.strokeBorder(highlighted ? c.accent : c.line, lineWidth: 1))
            .contentShape(shape)
        }
        .buttonStyle(LfPress())
    }
}

private struct QuickActions: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let locked = st?.locked ?? true
        HStack(spacing: 10) {
            if model.remoteReady {
                if Caps.lockState(st) {
                    QuickAction(icon: locked ? LfIcons.lock : LfIcons.unlock, label: locked ? "Unlock" : "Lock", highlighted: true,
                                busy: ui.commandInProgress == "Lock" || ui.commandInProgress == "Unlock") {
                        confirm(locked ? "Unlock your car" : "Lock your car") { model.lockCar(!locked) }
                    }
                }
                QuickAction(icon: LfIcons.bulb, label: "Flash", busy: ui.commandInProgress == "Flash lights") { confirm("Flash the lights") { model.lights() } }
                QuickAction(icon: LfIcons.horn, label: "Horn", busy: ui.commandInProgress == "Horn") { confirm("Sound the horn") { model.horn() } }
            }
            if Caps.position(st) { QuickAction(icon: LfIcons.pin, label: "Find car") { model.setTab(.map) } }
        }
        if !model.remoteReady && model.remotePossible {
            T("Remote control isn't set up — tap to enable").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
                .tap { model.openRemoteSetup() }
            NeedsPackHint(electric: false).frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

/** Where the car is parked, which way it points, and the walk to it in Google Maps. */
private struct ParkedCard: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c

    var body: some View {
        if let st = model.ui.status, let lat = st.lat, let lon = st.lon {
            let facing = st.moving ? nil : st.heading
            LfCard(radius: 24, padding: 18) {
                HStack(spacing: 12) {
                    LfIcon(LfIcons.pin, c.text, 26)
                    VStack(alignment: .leading, spacing: 0) {
                        T(parkedLabel(model, st)).font(.system(size: 20, weight: .semibold)).foregroundColor(c.text).lineLimit(2)
                        T(facing != nil ? "Facing \(CarData.compassWord(facing!))" : (st.moving ? "Where it last reported" : "Where the car last reported"))
                            .font(LfType.small).foregroundColor(c.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // A small compass: the arrow points the way the car's nose does, north is up.
                    if let heading = facing {
                        ZStack {
                            Plain("N").font(.system(size: 9, weight: .bold)).foregroundColor(c.muted).frame(maxHeight: .infinity, alignment: .top).padding(.top, 2)
                            LfIcon(LfIcons.chevron, c.accentInk, 22).rotationEffect(.degrees(heading - 90))
                        }
                        .frame(width: 46, height: 46)
                        .background(Circle().fill(c.raised))
                        .overlay(Circle().strokeBorder(c.line2, lineWidth: 1))
                        .accessibilityElement()
                        .accessibilityLabel(Text(verbatim: tr("Facing \(CarData.compassWord(heading))")))
                    }
                }
                HStack(spacing: 10) {
                    if !st.moving {
                        PrimaryButton("Walk to the car") {
                            platform.openUrl("https://www.google.com/maps/dir/?api=1&destination=\(Fmt.fixed(lat, 6)),\(Fmt.fixed(lon, 6))&travelmode=walking")
                        }
                    }
                    SecondaryButton("Show on map") { model.setTab(.map) }
                }
            }
        }
    }
}

/** Trips and Climate side by side, each half the width. Cars without climate get the full-width trips card. */
private struct TripsClimateRow: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        if Caps.climate(model.ui.status) {
            HStack(alignment: .top, spacing: 12) {
                TripsTile()
                ClimateTile()
            }
            .fixedSize(horizontal: false, vertical: true)
        } else {
            TripsCard()
        }
    }
}

/** The last seven days of driving, worked out once for the cards that show it. */
private struct Week {
    let days: [Int64]
    let trips: [Trip]
    let perDay: [Double]
    let km: Double
    let kwh: Double

    init(_ all: [Trip]) {
        let dayMs: Int64 = 86_400_000
        let days = lastSevenDays()
        let week = all.filter { ($0.startMillis ?? 0) >= days[0] }
        self.days = days
        trips = week
        perDay = days.map { d in week.filter { ($0.startMillis ?? 0) >= d && ($0.startMillis ?? 0) < d + dayMs }.reduce(0.0) { $0 + $1.distanceKm } }
        km = perDay.reduce(0.0, +)
        kwh = week.compactMap { $0.kwh }.reduce(0.0, +)
    }
}

private struct DayBars: View {
    @Environment(\.lf) private var c
    let perDay: [Double]
    let width: CGFloat
    let tall: CGFloat
    var body: some View {
        let top = max(perDay.max() ?? 0, 1)
        HStack(alignment: .bottom, spacing: 0) {
            ForEach(Array(perDay.enumerated()), id: \.offset) { i, v in
                RoundedRectangle(cornerRadius: width / 2, style: .continuous).fill(i == 6 ? c.accent : (v > 0 ? c.bar : c.track))
                    .frame(width: width, height: 4 + tall * CGFloat(v / top))
                if i < perDay.count - 1 { Spacer(minLength: 2) }
            }
        }
        .accessibilityHidden(true)
    }
}

private struct TripsTile: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let ui = model.ui
        let s = model.prefs
        let w = Week(ui.trips)
        LfCard(radius: 24, padding: 16, stretch: true, onTap: { model.setTab(.trips); model.loadTrips() }) {
            HStack(spacing: 8) {
                LfIcon(LfIcons.route, c.text, 22)
                T("Trips").font(.system(size: 18, weight: .semibold)).foregroundColor(c.text).fitLine(0.6).frame(maxWidth: .infinity, alignment: .leading)
                LfIcon(LfIcons.chevron, c.muted, 18)
            }
            VStack(alignment: .leading, spacing: 2) {
                Plain(ui.trips.isEmpty && ui.tripsLoading ? "…" : fmtDistance(w.km, s)).font(.system(size: 28, weight: .bold)).foregroundColor(c.text)
                    .fitLine(0.7)
                T("Last 7 days · \(w.trips.count) trips").font(LfType.small).foregroundColor(c.muted)
                if w.km > 1 && w.kwh > 0 { Plain(consumption(w.km, w.kwh, s)).font(LfType.tiny).foregroundColor(c.muted) }
            }
            Spacer(minLength: 0)
            DayBars(perDay: w.perDay, width: 9, tall: 26)
        }
    }
}

private struct ClimateTile: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm
    var body: some View {
        let ui = model.ui
        let st = ui.status
        let on = st?.preconditioningOn == true
        LfCard(radius: 24, padding: 16, stretch: true, onTap: { model.setTab(.climate) }) {
            HStack(spacing: 8) {
                LfIcon(LfIcons.thermo, on ? c.orangeInk : c.text, 22)
                T("Climate").font(.system(size: 18, weight: .semibold)).foregroundColor(c.text).fitLine(0.6).frame(maxWidth: .infinity, alignment: .leading)
                LfIcon(LfIcons.chevron, c.muted, 18)
            }
            VStack(alignment: .leading, spacing: 2) {
                T(on ? "Pre-heating" : "Off").font(.system(size: 28, weight: .bold)).foregroundColor(c.text).fitLine(0.6)
                T("Outside \(fmtTemp(st?.outsideTempC ?? ui.weather?.tempC, model.prefs))").font(LfType.small).foregroundColor(c.muted)
                if let next = nextSchedule(st) { T("Next: \(next)").font(LfType.tiny).foregroundColor(c.muted) }
            }
            Spacer(minLength: 0)
            if model.remoteReady {
                PrimaryButton(on ? "Stop" : "Pre-heat", color: c.orange) {
                    confirm(on ? "Stop pre-heating" : "Start pre-heating") { model.preconditioning(!on) }
                }
            }
        }
    }
}

/** Last 7 days: distance, trips and a small bar per day. */
private struct TripsCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c

    private func summary(_ w: Week, _ s: AppSettings) -> String {
        var parts = ["\(w.trips.count) trips"]
        if w.km > 1 && w.kwh > 0 { parts.append(consumption(w.km, w.kwh, s)) }
        let fuelled = w.trips.filter { $0.liters != nil }
        let liters = fuelled.reduce(0.0) { $0 + ($1.liters ?? 0) }
        let distance = fuelled.reduce(0.0) { $0 + $1.distanceKm }
        if distance > 1 && liters > 0 { parts.append(Fmt.fixed(liters / distance * 100, 1) + " l/100 km") }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        let ui = model.ui
        let s = model.prefs
        let w = Week(ui.trips)
        let top = max(w.perDay.max() ?? 0, 1)
        LfCard(radius: 24, padding: 18, onTap: { model.setTab(.trips); model.loadTrips() }) {
            HStack(spacing: 12) {
                LfIcon(LfIcons.route, c.text, 26)
                VStack(alignment: .leading, spacing: 0) {
                    T("Trips").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
                    T("Last 7 days").font(LfType.small).foregroundColor(c.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LfIcon(LfIcons.chevron, c.muted, 20)
            }
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 0) {
                    Plain(ui.trips.isEmpty && ui.tripsLoading ? "…" : fmtDistance(w.km, s)).font(.system(size: 32, weight: .bold)).foregroundColor(c.text)
                    T(summary(w, s)).font(LfType.small).foregroundColor(c.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                // One bar per day, today on the right.
                HStack(alignment: .bottom, spacing: 6) {
                    ForEach(Array(w.perDay.enumerated()), id: \.offset) { i, v in
                        VStack(spacing: 4) {
                            RoundedRectangle(cornerRadius: 5, style: .continuous).fill(i == 6 ? c.accent : (v > 0 ? c.bar : c.track))
                                .frame(width: 10, height: 4 + 40 * CGFloat(v / top))
                            Plain(TimeFmt.format(w.days[i], "EEEEE")).font(LfType.tiny).foregroundColor(c.muted)
                        }
                    }
                }
                .accessibilityHidden(true)
            }
        }
    }
}

/// Next service: what the carmaker reports (km and/or days left), plus when you'll reach the km at your current
/// pace (average of the last 30 days of trips). Whichever comes first.
private struct ServiceCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let ui = model.ui
        let st = ui.status
        let since = nowMillis() - 30 * 86_400_000
        let perDay = ui.trips.filter { ($0.startMillis ?? 0) >= since }.reduce(0.0) { $0 + $1.distanceKm } / 30.0
        let kmLeft = st?.kmToService
        let byKmDays: Double? = (kmLeft != nil && perDay > 1) ? kmLeft! / perDay : nil
        let byDate = st?.daysToService
        let soonest = [byKmDays, byDate].compactMap { $0 }.min()
        LfCard(radius: 24, padding: 18, onTap: { model.setTab(.service) }) {
            HStack(spacing: 12) {
                LfIcon(LfIcons.wrench, c.text, 26)
                VStack(alignment: .leading, spacing: 0) {
                    T("Next service").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
                    T(kmLeft == nil && byDate == nil ? "View service details"
                        : (kmLeft != nil ? "In \(fmtDistance(kmLeft, model.prefs))" : "In \(JSONValue.javaInt(byDate ?? 0)) days"))
                        .font(LfType.small).foregroundColor(c.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                if let soonest = soonest {
                    VStack(alignment: .trailing, spacing: 0) {
                        Plain("≈ " + TimeFmt.format(nowMillis() + JSONValue.javaLong(soonest * 86_400_000), "d MMM yyyy")).font(LfType.label).foregroundColor(c.text)
                        T(byKmDays != nil && (byDate == nil || byKmDays! <= byDate!) ? "at your current driving" : "by date").font(LfType.tiny).foregroundColor(c.muted)
                    }
                }
                LfIcon(LfIcons.chevron, c.muted, 20)
            }
        }
    }
}

/** Petrol, diesel and hybrid cars: distance, fuel and what it cost so far this month. */
private struct FuelMonthCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let ui = model.ui
        let s = model.prefs
        if Caps.fuel(ui.status) {
            let from = FuelStats.monthStart(nowMillis())
            let month = ui.trips.filter { ($0.startMillis ?? 0) >= from }
            let km = month.reduce(0.0) { $0 + $1.distanceKm }
            let liters = FuelStats.liters(month)
            let fuelKm = month.filter { $0.liters != nil }.reduce(0.0) { $0 + $1.distanceKm }
            let cost = FuelStats.cost(liters, s.fuelPrice)
            LfCard(onTap: { model.setTab(.trips); model.loadTrips() }) {
                HStack {
                    T("Fuel this month").font(LfType.label).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
                    T(month.count == 1 ? "1 trip" : "\(month.count) trips").font(LfType.tiny).foregroundColor(c.muted)
                }
                HStack(alignment: .bottom) {
                    Plain(liters > 0 ? Fmt.fixed(liters, 1) + " l" : "—").font(.system(size: 30, weight: .semibold)).foregroundColor(c.text)
                    Spacer()
                    if let cost = cost {
                        Plain("≈ " + fuelMoney(cost, ChargeLog.places.currency)).font(.system(size: 20, weight: .semibold)).foregroundColor(c.accentInk)
                    } else if liters > 0 {
                        T("Set the fuel price").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                    }
                }
                HStack {
                    Plain(fmtDistance(km, s)).font(LfType.tiny).foregroundColor(c.text2)
                    Spacer()
                    if fuelKm > 0 && liters > 0 { Plain(Fmt.fixed(liters / fuelKm * 100, 1) + " l/100 km").font(LfType.tiny).foregroundColor(c.muted) }
                }
            }
        }
    }
}

struct ChargingSummary: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm
    var body: some View {
        let st = model.ui.status
        let s = model.prefs
        let charging = st?.isCharging == true
        LfCard(onTap: { model.setTab(.charging) }) {
            HStack {
                T("Charging").font(LfType.label).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
                Pill(text: charging ? "Charging" : (st?.plugged == true ? "Plugged in" : "Unplugged"), bg: c.okBg, fg: c.okText)
            }
            HStack(alignment: .lastTextBaseline, spacing: 10) {
                Plain(wholeNumber(st?.batteryPct) + " %").font(.system(size: 34, weight: .semibold)).foregroundColor(c.text)
                Plain("≈ \(fmtDistance(st?.rangeKm, s))").font(LfType.small).foregroundColor(c.muted)
                Spacer()
            }
            ProgressBar(fraction: (st?.batteryPct ?? 0) / 100, color: c.meter)
            T(chargingLine(st)).font(LfType.small).foregroundColor(c.text2)
            if charging, let rem = st?.remainingMinutes {
                let done = TimeFmt.format(nowMillis() + Int64(rem) * 60_000, "HH:mm")
                T("Done at about \(done)").font(LfType.tiny).foregroundColor(c.muted)
            }
            if !charging, let last = ChargeLog.sessions.first {
                let day = TimeFmt.format(last.start, "EEE d MMM")
                let energy = Fmt.fixed(last.kwh, 1)
                let place = ChargeLog.match(last).label
                T("Last: \(day) · \(energy) kWh · \(place)").font(LfType.tiny).foregroundColor(c.muted)
            }
            if model.remoteReady {
                PrimaryButton(charging ? "Stop charging" : "Charge now", enabled: st?.plugged == true) {
                    confirm(charging ? "Stop charging" : "Start charging") { model.charging(!charging) }
                }
            }
        }
    }
}

/** Charging cost and energy this month, split home/away. */
private struct MonthCostCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        let sessions = ChargeLog.sessions
        let cfg = ChargeLog.places
        if !sessions.isEmpty {
            let from = FuelStats.monthStart(nowMillis())
            let month = sessions.filter { $0.start >= from }
            let total = month.reduce(0.0) { $0 + $1.kwh }
            let home = month.filter { ChargeLog.match($0, cfg).kind == "home" }.reduce(0.0) { $0 + $1.kwh }
            let cost = month.reduce(0.0) { $0 + $1.kwh * (ChargeLog.match($1, cfg).price ?? 0) }
            LfCard(onTap: { model.setTab(.charging) }) {
                HStack {
                    T("Charging this month").font(LfType.label).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
                    T("\(month.count) sessions").font(LfType.tiny).foregroundColor(c.muted)
                }
                HStack(alignment: .bottom) {
                    Plain(Fmt.fixed(total, 0) + " kWh").font(.system(size: 30, weight: .semibold)).foregroundColor(c.text)
                    Spacer()
                    Plain("≈ " + Fmt.fixed(cost, 0) + " " + cfg.currency).font(.system(size: 20, weight: .semibold)).foregroundColor(c.accentInk)
                }
                ProgressBar(fraction: total > 0 ? home / total : 0, color: c.meter)
                HStack {
                    T("Home \(Fmt.fixed(home, 0)) kWh").font(LfType.tiny).foregroundColor(c.text2)
                    Spacer()
                    T("Away \(Fmt.fixed(total - home, 0)) kWh").font(LfType.tiny).foregroundColor(c.muted)
                }
            }
        }
    }
}
