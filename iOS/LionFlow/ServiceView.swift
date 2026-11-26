import SwiftUI

// The Service tab: the odometer, the service plan as a timeline, and the user's own service history.

private struct PickedEntry: Identifiable {
    let entry: ServiceEntry
    var id: String { "\(entry.dateMillis)|\(entry.title)" }
}

struct ServiceView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    let wide: Bool
    @State private var adding = false
    @State private var toDelete: PickedEntry?

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let s = model.prefs
        ScreenColumn(wide: wide, onRefresh: { model.refresh() }) {
            ScreenHeader(kicker: nil, title: "Service")
            LfCard {
                HStack(spacing: 12) {
                    LfIcon(LfIcons.car, c.text2, 20).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.raised))
                    VStack(alignment: .leading, spacing: 0) {
                        T("Odometer").font(LfType.small).foregroundColor(c.muted)
                        Plain(fmtDistance(st?.odometerKm, s)).font(.system(size: 28, weight: .semibold)).foregroundColor(c.text)
                    }
                    Spacer(minLength: 0)
                }
            }
            ServiceTimeline()
            SecondaryButton("Find a workshop") {
                platform.openUrl("https://www.google.com/maps/search/" + Brands.current.workshopQuery)
            }
            HStack(alignment: .top, spacing: 12) {
                LfCard(radius: 20, padding: 14, stretch: true) {
                    HStack(spacing: 6) {
                        LfIcon(LfIcons.check, c.accentInk, 14)
                        T("Doors").font(LfType.small).foregroundColor(c.muted)
                    }
                    T((st?.openDoors ?? []).isEmpty ? "All closed" : "\(st?.openDoors.count ?? 0) open").font(LfType.label).foregroundColor(c.text)
                }
                LfCard(radius: 20, padding: 14, stretch: true) {
                    HStack(spacing: 6) {
                        LfIcon(LfIcons.check, c.accentInk, 14)
                        T("Battery health").font(LfType.small).foregroundColor(c.muted)
                    }
                    Plain(st?.batteryHealthPct == nil ? "—" : wholeNumber(st?.batteryHealthPct) + " %").font(LfType.label).foregroundColor(c.text)
                    EstimatedHealth(compact: true)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            history(ui)
        }
        .onAppear { if ui.trips.isEmpty && !ui.tripsLoading { model.loadTrips() } }
        .sheet(isPresented: $adding) {
            HistoryEditor { adding = false }.environmentObject(model).environment(\.lf, c)
        }
        .alert(item: $toDelete) { picked in
            Alert(title: Text(verbatim: tr("Delete “\(picked.entry.title)”?")),
                  primaryButton: .destructive(Text(verbatim: tr("Delete"))) { model.deleteServiceEntry(picked.entry) },
                  secondaryButton: .cancel(Text(verbatim: tr("Keep"))))
        }
    }

    private func history(_ ui: UiState) -> some View {
        LfCard(padding: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    T("History").font(LfType.label).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
                    T("Add").font(.system(size: 15, weight: .semibold)).foregroundColor(c.accentInk).padding(12).tap { adding = true }
                }
                .padding(.leading, 18)
                .padding(.trailing, 6)
                .padding(.top, 4)
                if ui.serviceHistory.isEmpty {
                    T("Keep your own log of services, tyre changes and repairs here.").font(LfType.small).foregroundColor(c.muted)
                        .padding(.horizontal, 18)
                        .padding(.bottom, 16)
                }
                ForEach(Array(ui.serviceHistory.enumerated()), id: \.offset) { i, e in
                    LfDivider()
                    HStack(alignment: .top, spacing: 14) {
                        Circle().fill(i < 2 ? c.blueInk : c.bar).frame(width: 10, height: 10).padding(.top, 5)
                        VStack(alignment: .leading, spacing: 0) {
                            Plain(e.title).font(LfType.label).foregroundColor(c.text)
                            if !e.note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { Plain(e.note).font(LfType.small).foregroundColor(c.muted) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        Plain(TimeFmt.format(e.dateMillis, "MMM yyyy")).font(LfType.small).foregroundColor(c.muted)
                    }
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .tap { toDelete = PickedEntry(entry: e) }
                }
            }
        }
    }
}

private struct HistoryEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let onClose: () -> Void
    @State private var title = ""
    @State private var note = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                T("Add to history").font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
                LfField(label: "What was done", text: $title)
                LfField(label: "Workshop, km, notes", text: $note)
                HStack {
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onClose)
                    T("Save").font(LfType.label).foregroundColor(c.accentInk).padding(12).tap {
                        let what = title.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !what.isEmpty { model.addServiceEntry(what, note.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        onClose()
                    }
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
    }
}

/** One step on the timeline: a dot (with a symbol when big), the line to the next step, and what the step says. */
private struct TimelineRow<Content: View>: View {
    @Environment(\.lf) private var c
    var icon: String? = nil
    let dot: Color
    let big: Bool
    let lineBelow: Bool
    @ViewBuilder var content: () -> Content

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(spacing: 0) {
                Rectangle().fill(c.bar).frame(width: 3, height: 10)
                Circle().fill(dot).frame(width: big ? 44 : 18, height: big ? 44 : 18)
                    .overlay(icon.map { LfIcon($0, c.card, 22) })
                if lineBelow { Rectangle().fill(c.bar).frame(width: 3).frame(maxHeight: .infinity) }
            }
            .frame(width: 48)
            VStack(alignment: .leading, spacing: 2) { content() }
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .fixedSize(horizontal: false, vertical: true)
    }
}

/** Next service, today and the last logged service as a timeline (like the carmaker's own app). */
private struct ServiceTimeline: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @State private var editInterval = false

    private func inText(_ days: Double) -> String {
        let m = JSONValue.javaInt(days / 30.4)
        if days < 1 { return "today" }
        return m >= 1 ? "in about \(m) months" : "in \(JSONValue.javaInt(days)) days"
    }

    private func date(_ now: Int64, _ days: Double) -> String { TimeFmt.format(now + JSONValue.javaLong(days * 86_400_000), "d MMM yyyy") }

    var body: some View {
        let ui = model.ui
        let s = model.prefs
        // The carmaker doesn't send the interval; default 12 500 km / 1 year.
        let intKm = s.serviceKm > 0 ? s.serviceKm : 12_500
        let intMonths = s.serviceMonths > 0 ? s.serviceMonths : 12
        let st = ui.status
        let now = nowMillis()
        let odo = st?.odometerKm
        // If the carmaker doesn't send service data, work it out from the interval: the next multiple of it on the odometer.
        let fromInterval = st?.kmToService == nil && st?.daysToService == nil && odo != nil
        let kmLeft: Double? = st?.kmToService ?? (fromInterval ? ((odo! / Double(intKm)).rounded(.down) + 1) * Double(intKm) - odo! : nil)
        let daysLeft = st?.daysToService
        // Your driving: km per day over the last 30 days of trips.
        let since = now - 30 * 86_400_000
        let perDay = ui.trips.filter { ($0.startMillis ?? 0) >= since }.reduce(0.0) { $0 + $1.distanceKm } / 30.0
        let byKmDays: Double? = (kmLeft != nil && perDay > 0.5) ? kmLeft! / perDay : nil
        let soonest = [byKmDays, daysLeft].compactMap { $0 }.min()
        let last = ui.serviceHistory.max { $0.dateMillis < $1.dateMillis }
        let urgent = (kmLeft ?? 1e9) < 1000 || (daysLeft ?? 1e9) < 30
        LfCard(padding: 16) {
            T("Service plan").font(LfType.label).foregroundColor(c.text)
            VStack(spacing: 0) {
                if kmLeft != nil || daysLeft != nil {
                    following(kmLeft, daysLeft, perDay, odo, now, intKm, intMonths, s)
                }
                TimelineRow(icon: LfIcons.wrench, dot: urgent ? c.orangeInk : c.text, big: true, lineBelow: true) {
                    next(st, kmLeft, daysLeft, byKmDays, soonest, perDay, odo, now, fromInterval, s)
                }
                TimelineRow(dot: c.accent, big: false, lineBelow: last != nil) {
                    HStack(spacing: 0) {
                        T("Today").font(LfType.small).foregroundColor(c.onAccent)
                        Plain("  " + fmtDistance(odo, s)).font(LfType.label).foregroundColor(c.onAccent)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(c.accent))
                }
                if let last = last {
                    TimelineRow(icon: LfIcons.check, dot: c.okText, big: true, lineBelow: false) {
                        T("Done " + TimeFmt.format(last.dateMillis, "d MMM yyyy")).font(LfType.small).foregroundColor(c.okText)
                        Plain(last.title).font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
                        if !last.note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { Plain(last.note).font(LfType.small).foregroundColor(c.muted) }
                    }
                }
            }
        }
        .sheet(isPresented: $editInterval) {
            IntervalEditor(km: intKm, months: intMonths, onDismiss: { editInterval = false }) { km, months in
                model.update {
                    $0.serviceKm = km
                    $0.serviceMonths = months
                }
                editInterval = false
            }
            .environment(\.lf, c)
        }
    }

    /** The one after next: the next service plus one interval. */
    private func following(_ kmLeft: Double?, _ daysLeft: Double?, _ perDay: Double, _ odo: Double?, _ now: Int64, _ intKm: Int, _ intMonths: Int, _ s: AppSettings) -> some View {
        let km2 = (kmLeft ?? 0) + Double(intKm)
        let days2 = (daysLeft ?? 0) + Double(intMonths) * 30.44
        let byKm2: Double? = perDay > 0.5 ? km2 / perDay : nil
        let soon2 = [byKm2, days2].compactMap { $0 }.min()
        var lines: [String] = []
        if let o = odo { lines.append("At ≈ \(fmtDistance(o + km2, s))") }
        lines.append("By date: " + date(now, days2))
        if let d = byKm2 { lines.append("At your driving: " + date(now, d)) }
        return TimelineRow(icon: LfIcons.wrench, dot: c.muted, big: true, lineBelow: true) {
            T(soon2.map { "Estimated " + inText($0) } ?? "Later").font(LfType.small).foregroundColor(c.muted)
            T("Following service").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
            ForEach(lines, id: \.self) { T($0).font(LfType.small).foregroundColor(c.muted) }
            T("Every \(fmtDistance(Double(intKm), s)) or \(intMonths) months — tap to change").font(.system(size: 12, weight: .semibold)).foregroundColor(c.accentInk)
                .padding(.vertical, 4)
                .tap { editInterval = true }
        }
    }

    @ViewBuilder
    private func next(_ st: VehicleStatus?, _ kmLeft: Double?, _ daysLeft: Double?, _ byKmDays: Double?, _ soonest: Double?, _ perDay: Double,
                      _ odo: Double?, _ now: Int64, _ fromInterval: Bool, _ s: AppSettings) -> some View {
        if kmLeft == nil && daysLeft == nil {
            T(st == nil ? "Waiting for data…" : "Peugeot hasn't sent service data yet").font(LfType.small).foregroundColor(c.muted)
            T("Next periodic service").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
        } else {
            T(soonest.map { "Estimated " + inText($0) } ?? "Upcoming").font(LfType.small).foregroundColor(c.blueInk)
            T("Next periodic service").font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
            if fromInterval {
                T("Worked out from the service interval — Peugeot doesn't send service data for this car").font(LfType.tiny).foregroundColor(c.muted)
            }
            if let k = kmLeft {
                T("In \(fmtDistance(k, s))" + (odo.map { " (at ≈ \(fmtDistance($0 + k, s)))" } ?? "")).font(LfType.small).foregroundColor(c.muted)
            }
            if let d = daysLeft { T("By date: " + date(now, d)).font(LfType.small).foregroundColor(c.muted) }
            if let d = byKmDays {
                T("At your driving (≈ \(fmtDistance(perDay * 30, s))/month): " + date(now, d)).font(LfType.small).foregroundColor(c.muted)
            }
            if kmLeft != nil && daysLeft != nil { T("Whichever comes first").font(LfType.tiny).foregroundColor(c.muted) }
        }
    }
}

private struct IntervalEditor: View {
    @Environment(\.lf) private var c
    let km: Int
    let months: Int
    let onDismiss: () -> Void
    let onSave: (Int, Int) -> Void
    @State private var kmText = ""
    @State private var monthsText = ""
    @State private var loaded = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                T("Service interval").font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
                T("See your service booklet. Used to estimate the service after next.").font(LfType.small).foregroundColor(c.muted)
                LfField(label: "Kilometres", text: $kmText, keyboard: .numberPad)
                    .onChange(of: kmText) { v in
                        let clean = String(v.filter { $0.isNumber }.prefix(6))
                        if clean != v { kmText = clean }
                    }
                LfField(label: "Months", text: $monthsText, keyboard: .numberPad)
                    .onChange(of: monthsText) { v in
                        let clean = String(v.filter { $0.isNumber }.prefix(3))
                        if clean != v { monthsText = clean }
                    }
                HStack {
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onDismiss)
                    T("Save").font(LfType.label).foregroundColor(c.accentInk).padding(12).tap {
                        let newKm = Int(kmText).map { min(max($0, 1000), 100_000) } ?? km
                        let newMonths = Int(monthsText).map { min(max($0, 1), 60) } ?? months
                        onSave(newKm, newMonths)
                    }
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
        .onAppear {
            if loaded { return }
            loaded = true
            kmText = String(km)
            monthsText = String(months)
        }
    }
}
