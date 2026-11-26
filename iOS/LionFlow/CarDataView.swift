import SwiftUI

// The "Car data" card on Home, the sheet with everything the car reports, and the editor for what Home shows.

/** The 12 V battery as the car shares it: volts on most cars, a level on some. */
func battery12(_ st: VehicleStatus) -> String? {
    guard let b = st.raw.optJSONObject("battery"), b.has("voltage"), !b.isNull("voltage") else { return nil }
    let v = b.optDouble("voltage")
    if v.isNaN { return nil }
    return v > 30 ? "\(v.roundToInt) %" : Fmt.fixed(v, 1) + " V"
}

/** A few of the car's numbers, and the way in to all of them. */
struct CarDataCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @State private var open = false

    private func cells(_ st: VehicleStatus) -> [(String, String)] {
        var out: [(String, String)] = []
        if let odo = st.odometerKm { out.append(("Odometer", fmtDistance(odo, model.prefs))) }
        if let t = st.outsideTempC { out.append(("Outside", fmtTemp(t, model.prefs))) }
        if let b = battery12(st) { out.append(("12 V battery", b)) }
        return out
    }

    var body: some View {
        if let st = model.ui.status, !model.ui.statusMissing {
            let list = cells(st)
            LfCard(radius: 24, padding: 18, onTap: { open = true }) {
                HStack(spacing: 12) {
                    LfIcon(LfIcons.car, c.text, 26)
                    VStack(alignment: .leading, spacing: 0) {
                        T("Car data").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
                        T("Every value the car reports").font(LfType.small).foregroundColor(c.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    LfIcon(LfIcons.chevron, c.muted, 16)
                }
                if !list.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(list, id: \.0) { cell in
                            VStack(alignment: .leading, spacing: 0) {
                                Plain(cell.1).font(LfType.label).foregroundColor(c.text).lineLimit(1)
                                T(cell.0).font(LfType.tiny).foregroundColor(c.muted).lineLimit(1)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            .sheet(isPresented: $open) {
                CarDataSheet { open = false }
                    .environmentObject(model)
                    .environment(\.lf, c)
            }
        }
    }
}

private struct RowSection: Identifiable {
    let title: String
    let rows: [(String, String)]
    var id: String { title }
}

/** The car's status in plain words: section title to its rows (label, value). Values are already in the app's language. */
private func plainRows(_ st: VehicleStatus, _ s: AppSettings) -> [RowSection] {
    func onOff(_ b: Bool) -> String { tr(b ? "On" : "Off") }
    func one(_ v: Double) -> String { Fmt.fixed(v, 1) }
    func whole(_ v: Double) -> String { String(v.roundToInt) }
    var out: [RowSection] = []
    func section(_ title: String, _ rows: [(String, String?)]) {
        var kept: [(String, String)] = []
        for (k, v) in rows {
            if let v = v, !v.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { kept.append((k, v)) }
        }
        if !kept.isEmpty { out.append(RowSection(title: title, rows: kept)) }
    }

    section("Report", [
        ("Last report", st.updatedAtMillis.map { TimeFmt.format($0, "d MMM yyyy · HH:mm") }),
        ("Odometer", st.odometerKm.map { fmtDistance($0, s) }),
    ])

    let fuelOnly = st.energyType == "Fuel"
    var energy: String? = nil
    if let r = st.residualKwh, let cap = st.capacityKwh { energy = "\(one(r)) / \(one(cap)) kWh" } else if let cap = st.capacityKwh { energy = "\(one(cap)) kWh" }
    var charging: String? = nil
    if !fuelOnly {
        charging = tr(st.isCharging ? "Charging" : (st.chargingStatus == "Finished" ? "Charging complete" : (st.plugged ? "Plugged in" : "Not plugged in")))
    }
    let fuelLevel: Double? = st.fuelPct ?? (fuelOnly ? st.batteryPct : nil)
    let fuelRange: Double? = st.fuelRangeKm ?? (fuelOnly ? st.rangeKm : nil)
    section("Energy", [
        ("Battery", fuelOnly ? nil : st.batteryPct.map { "\(whole($0)) %" }),
        ("Range", fuelOnly ? nil : st.rangeKm.map { fmtDistance($0, s) }),
        ("Energy in the battery", energy),
        ("Battery health", fuelOnly ? nil : st.batteryHealthPct.map { "\(whole($0)) %" }),
        ("Charging", charging),
        ("Fuel", fuelLevel.map { "\(whole($0)) %" }),
        ("Fuel range", fuelRange.map { fmtDistance($0, s) }),
        ("Average since new", FuelStats.lifetimeLPer100(st).map { "≈ \(one($0)) l/100 km" }),
        ("Fuel since new", FuelStats.lifetimeLiters(st).map { "≈ \(whole($0)) l" }),
        ("12 V battery", battery12(st)),
    ])

    var speed: Double? = nil
    if let k = st.raw.optJSONObject("kinetic"), k.has("speed"), !k.isNull("speed") {
        let v = k.optDouble("speed")
        if !v.isNaN { speed = v }
    }
    var fog: String? = nil
    if st.raw.optJSONObject("lightingSystem")?.has("fog") == true { fog = onOff(!st.fogLightsOn.isEmpty) }
    section("Car", [
        ("Doors", doorsLine(st)),
        ("Alarm", st.alarmOn.map { onOff($0) }),
        ("Ignition", st.ignition.map { tr($0) }),
        ("Speed", (st.moving || (speed ?? 0) > 0.5) ? fmtSpeed(speed ?? 0, s) : tr("Parked")),
        ("Outside", st.outsideTempC.map { fmtTemp($0, s) }),
        ("Pre-heating", Caps.climate(st) ? onOff(st.preconditioningOn) : nil),
        ("Fog lights", fog),
        ("Privacy mode", st.privacyState.map { tr($0) }),
    ])

    var position: String? = nil
    if let lat = st.lat, let lon = st.lon { position = Fmt.fixed(lat, 5) + ", " + Fmt.fixed(lon, 5) }
    section("Position", [
        ("Latitude, longitude", position),
        ("Facing", st.heading.map { "\(CarData.compass($0)) · \(whole($0))°" }),
    ])

    var service: [String] = []
    if let km = st.kmToService { service.append(fmtDistance(km, s)) }
    if let days = st.daysToService { service.append(tr("{0} days").replacingOccurrences(of: "{0}", with: whole(days))) }
    section("Service", [("Next service", service.isEmpty ? nil : service.joined(separator: " · "))])
    return out
}

private struct ValueRows: View {
    @Environment(\.lf) private var c
    let rows: [(String, String)]
    let translateLabel: Bool

    var body: some View {
        GroupCard {
            ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                if i > 0 { LfDivider() }
                HStack(alignment: .center, spacing: 12) {
                    (translateLabel ? T(row.0).font(LfType.small) : Plain(row.0).font(LfType.tiny)).foregroundColor(c.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Plain(row.1).font(translateLabel ? LfType.label : LfType.small).foregroundColor(c.text).multilineTextAlignment(.trailing)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
    }
}

/** Everything the car reported: first in plain words, then every single value, then the raw answer. */
struct CarDataSheet: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    let onDismiss: () -> Void
    @State private var showRaw = false

    var body: some View {
        let st = model.ui.status
        let all = st.map { CarData.flatten($0.raw) } ?? []
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) {
                    LfIcon(LfIcons.car, c.text2, 22).frame(width: 48, height: 48).background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.raised))
                    VStack(alignment: .leading, spacing: 0) {
                        T("All car data").font(.system(size: 24, weight: .semibold)).foregroundColor(c.text).lineLimit(1)
                        T("What the car last sent to the carmaker's server").font(LfType.small).foregroundColor(c.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Button(action: onDismiss) {
                        LfIcon(LfIcons.close, c.text2, 18).frame(width: 36, height: 36).background(Circle().fill(c.raised)).contentShape(Circle())
                    }
                    .buttonStyle(LfPress())
                    .accessibilityLabel(Text(verbatim: tr("Close")))
                }
                if let st = st, !model.ui.statusMissing, !all.isEmpty {
                    ForEach(plainRows(st, model.prefs)) { section in
                        T(section.title).font(LfType.label).foregroundColor(c.text).padding(.top, 4)
                        ValueRows(rows: section.rows, translateLabel: true)
                    }
                    T("Every value").font(LfType.label).foregroundColor(c.text).padding(.top, 4)
                    T("Each value the car sent, under the carmaker's own names.").font(LfType.tiny).foregroundColor(c.muted)
                    ValueRows(rows: all, translateLabel: false)
                    HStack(spacing: 10) {
                        SecondaryButton(showRaw ? "Hide raw data" : "Show raw data") { showRaw.toggle() }
                        SecondaryButton("Copy raw data") {
                            platform.copy(CarData.pretty(st.raw))
                            model.toastText("Copied")
                        }
                    }
                    T("The raw data holds the car's position. Mind who you share it with.").font(LfType.tiny).foregroundColor(c.muted)
                    if showRaw {
                        Plain(CarData.pretty(st.raw)).font(.system(size: 11, design: .monospaced)).foregroundColor(c.text2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.card))
                            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(c.line, lineWidth: 1))
                    }
                } else {
                    T("The carmaker's server has no data from this car yet.").font(LfType.small).foregroundColor(c.muted)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
    }
}

/** Choose what a home screen shows: "phone" (also the order) or "watch". */
struct HomeEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let target: String
    let onClose: () -> Void

    private var all: [HomeLayout.Item] { target == "watch" ? HomeLayout.watch : HomeLayout.phone }

    private func applies(_ i: HomeLayout.Item) -> Bool {
        switch i.needs {
        case "battery": return Caps.battery(model.ui.status)
        case "fuel": return Caps.fuel(model.ui.status)
        default: return true
        }
    }

    private func setHidden(_ csv: String) {
        model.update { if target == "watch" { $0.watchHidden = csv } else { $0.homeHidden = csv } }
    }

    private func arrow(_ up: Bool, _ enabled: Bool, _ item: HomeLayout.Item) -> some View {
        Button(action: {
            let list = all
            model.update { $0.homeOrder = HomeLayout.move(list, $0.homeOrder, item.id, up: up, applies: { applies($0) }) }
        }) {
            LfIcon(LfIcons.chevron, enabled ? c.text2 : c.line2, 16).rotationEffect(.degrees(up ? -90 : 90))
                .frame(width: 34, height: 34).contentShape(Circle())
        }
        .buttonStyle(LfPress())
        .disabled(!enabled)
        .accessibilityLabel(Text(verbatim: tr(up ? "Move up" : "Move down") + ": " + tr(item.title)))
    }

    var body: some View {
        let s = model.prefs
        let hiddenCsv = target == "watch" ? s.watchHidden : s.homeHidden
        let hidden = HomeLayout.hidden(hiddenCsv)
        let items = (target == "phone" ? HomeLayout.ordered(all, s.homeOrder) : all).filter { applies($0) }
        VStack(alignment: .leading, spacing: 0) {
            T(target == "watch" ? "On the watch" : "Home screen").font(LfType.stat).foregroundColor(c.text).padding(.bottom, 8)
            T(target == "watch" ? "Choose what the watch shows. It changes the next time the watch updates." : "Choose the cards on Home, and move them with the arrows.")
                .font(LfType.small).foregroundColor(c.muted).padding(.bottom, 8)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { i, item in
                        if i > 0 { LfDivider() }
                        HStack(spacing: 0) {
                            if target == "phone" {
                                arrow(true, i > 0, item)
                                arrow(false, i < items.count - 1, item)
                                Spacer().frame(width: 6)
                            }
                            VStack(alignment: .leading, spacing: 0) {
                                T(item.title).font(.system(size: 14, weight: .semibold)).foregroundColor(hidden.contains(item.id) ? c.muted : c.text)
                                if !item.note.isEmpty { T(item.note).font(LfType.tiny).foregroundColor(c.muted) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            LfToggle(on: !hidden.contains(item.id), label: item.title) { show in
                                setHidden(HomeLayout.toggle(hiddenCsv, item.id, show: show))
                            }
                            .padding(.leading, 8)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            HStack {
                T("Reset").font(LfType.label).foregroundColor(c.muted).padding(12).tap {
                    model.update {
                        if target == "watch" {
                            $0.watchHidden = HomeLayout.watchHidden
                        } else {
                            $0.homeHidden = HomeLayout.phoneHidden
                            $0.homeOrder = ""
                        }
                    }
                }
                Spacer()
                T("Done").font(LfType.label).foregroundColor(c.accentInk).padding(12).tap(onClose)
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(c.card.ignoresSafeArea())
    }
}
