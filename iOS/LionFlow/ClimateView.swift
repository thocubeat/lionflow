import SwiftUI

// The Climate tab: pre-heating now, the car's own pre-heating schedules, and the weather where you are.

private let weekDays = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

private func daysText(_ days: [String]) -> String {
    let set = Set(days)
    if days.count == 7 { return "Every day" }
    if set == Set(weekDays.prefix(5)) { return "Monday to Friday" }
    if set == ["Sat", "Sun"] { return "Weekends" }
    if days.isEmpty { return "No days" }
    return weekDays.filter { set.contains($0) }.joined(separator: ", ")
}

struct WeatherIcon: View {
    @Environment(\.lf) private var c
    let kind: String
    let size: CGFloat
    var body: some View {
        switch kind {
        case "sun": LfIcon(LfIcons.sun, c.orangeInk, size)
        case "snow": LfIcon(LfIcons.snow, c.blueInk, size)
        case "rain": LfIcon(LfIcons.rain, c.blueInk, size)
        default: LfIcon(LfIcons.cloud, c.muted, size)
        }
    }
}

struct ClimateView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm
    let wide: Bool

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let s = model.prefs
        let on = st?.preconditioningOn == true
        ScreenColumn(wide: wide, onRefresh: { model.refresh() }) {
            ScreenHeader(kicker: "Outside \(fmtTemp(st?.outsideTempC ?? ui.weather?.tempC, s))", title: "Climate")
            LfCard(padding: 20) {
                VStack(spacing: 12) {
                    LfIcon("wind", on ? c.orangeInk : c.muted, 40)
                    T(on ? "Pre-heating is on" : "Pre-heating is off").font(LfType.stat).foregroundColor(c.text)
                    if model.remoteReady {
                        let sending = ui.commandInProgress?.contains("heating") == true
                        PrimaryButton(sending ? "Sending…" : (on ? "Stop pre-heating" : "Start pre-heating now"), color: c.orange,
                                      enabled: st != nil && ui.commandInProgress == nil) {
                            confirm(on ? "Stop pre-heating" : "Start pre-heating") { model.preconditioning(!on) }
                        }
                    }
                    T("Heats or cools the cabin for a limited time (about 10–30 minutes, depending on the car). Uses grid power when plugged in.")
                        .font(LfType.tiny).foregroundColor(c.muted).multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
            }
            if model.remoteReady { PreheatSchedules() } else { RemoteSetupHint() }
            weather(ui.weather, s)
        }
    }

    /** The weather where the phone is (or where the car is, when the phone's position isn't shared). */
    private func weather(_ w: Weather?, _ s: AppSettings) -> some View {
        LfCard {
            if let w = w {
                HStack(spacing: 10) {
                    WeatherIcon(kind: WeatherApi.kind(w.symbol), size: 30)
                    VStack(alignment: .leading, spacing: 0) {
                        T("\(fmtTemp(w.tempC, s)) · \(WeatherApi.describe(w.symbol))").font(LfType.stat).foregroundColor(c.text)
                        T("Your location · feels like \(fmtTemp(w.feelsLikeC, s)) · wind \(w.windMs.roundToLong) m/s").font(LfType.tiny).foregroundColor(c.muted)
                    }
                }
                HStack(spacing: 0) {
                    ForEach(Array(w.hours.prefix(6).enumerated()), id: \.offset) { _, h in
                        VStack(spacing: 4) {
                            Plain(TimeFmt.format(h.timeMillis, "HH:mm")).font(.system(size: 11)).foregroundColor(c.muted).lineLimit(1).minimumScaleFactor(0.7)
                            WeatherIcon(kind: WeatherApi.kind(h.symbol), size: 18)
                            Plain(fmtTemp(h.tempC, s, unit: false)).font(.system(size: 13, weight: .semibold)).foregroundColor(c.text)
                        }
                        .frame(maxWidth: .infinity)
                    }
                }
                if let t = w.tomorrowMorningC {
                    T("Tomorrow 07:00: \(fmtTemp(t, s))" + (t < 0 ? " — pre-heating recommended" : "")).font(LfType.tiny).foregroundColor(c.muted)
                }
                T("Weather data: MET Norway").font(.system(size: 11)).foregroundColor(c.muted)
                    .tap { platform.openUrl("https://www.met.no/en/free-meteorological-data") }
            } else {
                T("Weather here").font(LfType.label).foregroundColor(c.text)
                T("Allow location to see the weather and forecast where you are.").font(LfType.small).foregroundColor(c.muted)
                SecondaryButton("Allow location") {
                    if platform.locationDenied { platform.openAppSettings() } else { platform.requestLocation() }
                }
            }
        }
    }
}

/** A schedule being made or changed (for its sheet). */
private struct EditedProgram: Identifiable {
    let program: Program
    let isNew: Bool
    var id: Int { program.slot }
}

/** Pre-heating schedules, stored in the car's 4 program slots (they run even if the phone is off). */
private struct PreheatSchedules: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm
    @State private var editing: EditedProgram?

    private func save(_ list: [Program], _ label: String) {
        confirm(label) { model.savePrograms(list) }
    }

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let programs = stableSorted(model.userPrograms(st), by: { $0.slot < $1.slot })
        let busy = ui.commandInProgress != nil
        LfCard(padding: 0) {
            T("Pre-heating schedules").font(LfType.label).foregroundColor(c.text).padding(.horizontal, 18).padding(.top, 16)
            T(model.programsPending() && ui.commandInProgress == nil ? "Sent to the car. Peugeot's status can take a while to show the change."
                : "The car heats or cools the cabin so it is ready at the time you set.")
                .font(LfType.tiny).foregroundColor(c.muted).padding(.horizontal, 18).padding(.bottom, 6)
            if programs.isEmpty {
                T("No schedules yet.").font(LfType.small).foregroundColor(c.muted).padding(.horizontal, 18).padding(.vertical, 8)
            }
            ForEach(programs, id: \.slot) { p in
                VStack(spacing: 0) {
                    LfDivider()
                    HStack {
                        VStack(alignment: .leading, spacing: 0) {
                            T("Ready at " + TimeFmt.hm(p.hour, p.minute)).font(LfType.stat).foregroundColor(p.enabled ? c.text : c.muted)
                            T(daysText(p.days)).font(LfType.small).foregroundColor(c.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .tap { if !busy { editing = EditedProgram(program: p, isNew: false) } }
                        LfToggle(on: p.enabled, label: "Schedule on") { on in
                            if busy { return }
                            let changed = programs.map { item -> Program in
                                var copy = item
                                if copy.slot == p.slot { copy.enabled = on }
                                return copy
                            }
                            save(changed, on ? "Turn schedule on" : "Turn schedule off")
                        }
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                }
            }
            LfDivider()
            if programs.count < 4 {
                let ready = st != nil && !busy
                T("+ Add schedule").font(.system(size: 13, weight: .semibold)).foregroundColor(ready ? c.accentInk : c.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 14)
                    .tap {
                        guard ready, let slot = (1...4).first(where: { n in !programs.contains { $0.slot == n } }) else { return }
                        editing = EditedProgram(program: Program(slot: slot, days: Array(weekDays.prefix(5)), hour: 7, minute: 0, enabled: true), isNew: true)
                    }
            } else {
                T("The car has room for 4 schedules.").font(LfType.tiny).foregroundColor(c.muted).padding(.horizontal, 18).padding(.vertical, 12)
            }
        }
        .sheet(item: $editing) { e in
            ScheduleEditor(program: e.program, isNew: e.isNew,
                onSave: { np in
                    editing = nil
                    save(programs.filter { $0.slot != np.slot } + [np], e.isNew ? "Add schedule" : "Save schedule")
                },
                onDelete: {
                    editing = nil
                    save(programs.filter { $0.slot != e.program.slot }, "Delete schedule")
                },
                onClose: { editing = nil })
                .environment(\.lf, c)
        }
    }
}

private struct ScheduleEditor: View {
    @Environment(\.lf) private var c
    let program: Program
    let isNew: Bool
    let onSave: (Program) -> Void
    let onDelete: () -> Void
    let onClose: () -> Void
    @State private var time = Date()
    @State private var days: Set<String> = []
    @State private var loaded = false

    private func saved() -> Program {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: time)
        var p = program
        p.hour = parts.hour ?? 0
        p.minute = parts.minute ?? 0
        p.days = weekDays.filter { days.contains($0) }
        p.enabled = true
        return p
    }

    var body: some View {
        let presets: [(String, [String])] = [("Weekdays", Array(weekDays.prefix(5))), ("Weekends", ["Sat", "Sun"]), ("Every day", weekDays)]
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                T(isNew ? "New schedule" : "Edit schedule").font(LfType.stat).foregroundColor(c.text)
                T("Ready at").font(LfType.small).foregroundColor(c.muted)
                DatePicker("", selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(WheelDatePickerStyle())
                    .labelsHidden()
                    .environment(\.locale, Locale(identifier: TimeFmt.use24 ? "en_GB" : "en_US"))
                    .frame(maxWidth: .infinity)
                    .accessibilityLabel(Text(verbatim: tr("Ready at")))
                T("Days").font(LfType.small).foregroundColor(c.muted)
                HStack(spacing: 4) {
                    ForEach(weekDays, id: \.self) { d in
                        let on = days.contains(d)
                        Button(action: { if on { days.remove(d) } else { days.insert(d) } }) {
                            T(String(d.prefix(2))).font(.system(size: 12, weight: .semibold)).foregroundColor(on ? c.onAccent : c.text)
                                .frame(width: 38, height: 38)
                                .background(Circle().fill(on ? c.accent : c.raised))
                                .overlay(Circle().strokeBorder(on ? c.accent : c.line, lineWidth: 1))
                                .contentShape(Circle())
                        }
                        .buttonStyle(LfPress())
                        .accessibilityLabel(Text(verbatim: tr(d)))
                        .accessibilityAddTraits(on ? [.isSelected] : [])
                    }
                }
                HStack(spacing: 14) {
                    ForEach(presets, id: \.0) { preset in
                        T(preset.0).font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk).padding(.vertical, 6).tap { days = Set(preset.1) }
                    }
                }
                T("The car heats or cools the cabin so it is ready at this time. It starts about 45 minutes before when plugged in (about 20 when not) and keeps going about 10 minutes after. The car decides this, apps can't change it.")
                    .font(LfType.tiny).foregroundColor(c.muted)
                HStack {
                    if !isNew { T("Delete").font(LfType.label).foregroundColor(c.danger).padding(12).tap(onDelete) }
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onClose)
                    T("Save").font(LfType.label).foregroundColor(days.isEmpty ? c.muted : c.accentInk).padding(12).tap { if !days.isEmpty { onSave(saved()) } }
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
        .onAppear {
            if loaded { return }
            loaded = true
            days = Set(program.days)
            var parts = DateComponents()
            parts.hour = min(max(program.hour, 0), 23)
            parts.minute = min(max(program.minute, 0), 59)
            time = Calendar.current.date(from: parts) ?? Date()
        }
    }
}
