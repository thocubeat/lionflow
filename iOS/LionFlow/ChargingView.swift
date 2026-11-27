import SwiftUI
import UserNotifications

// The Charging tab: how full the battery is, what the charger is doing, the car's own limit, and the charging log.

private func f1(_ v: Double) -> String { Fmt.fixed(v, v < 10 ? 1 : 0) }
private func kwhText(_ v: Double) -> String { Fmt.fixed(v, v < 100 ? 1 : 0) + " kWh" }
private func money(_ v: Double, _ currency: String) -> String { Fmt.fixed(v, v < 10 ? 1 : 0) + " " + currency }
private func durText(_ hours: Double) -> String {
    let m = JSONValue.javaInt(hours * 60)
    return m >= 60 ? "\(m / 60) h \(m % 60) min" : "\(m) min"
}
private func pct(_ v: Double) -> String { String(JSONValue.javaInt(v)) }
private func price(_ text: String) -> Double { JSONValue.javaParseDouble(text.replacingOccurrences(of: ",", with: ".")) ?? 0.0 }
private func onlyPrice(_ text: String) -> String { String(text.filter { $0.isNumber || $0 == "." || $0 == "," }.prefix(6)) }

struct ChargingView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm
    let wide: Bool

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let s = model.prefs
        ScreenColumn(wide: wide, refreshing: model.ui.refreshing, onRefresh: { model.refresh() }) {
            if st?.energyType == "Fuel" {
                ScreenHeader(kicker: ui.vehicle.map { model.vehicleKind($0) } ?? s.carName, title: "Charging")
                LfCard { T("Charging controls are unavailable. Fuel level and range are on the Home screen.").font(LfType.body).foregroundColor(c.text2) }
            } else {
                ScreenHeader(kicker: s.carName, title: "Charging")
                gauge(st, s)
                HStack(spacing: 10) {
                    LfIcon(LfIcons.bolt, c.accentInk, 20)
                    T(chargingLine(st)).font(.system(size: 14)).foregroundColor(c.okText).frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.okBg))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(c.okLine, lineWidth: 1))
                TimeToTarget()
                details(ui, st, s)
                if model.remoteReady { chargeButton(ui, st) }
                T("Charging power is worked out by LionFlow (the car only reports range per hour): measured from the energy added between two car reports, or estimated from the time left until then. Scheduled start time is set in the car or in the MyPeugeot app; “Stop charging” works by turning the scheduled start on (the car then waits for that time). “Turn off” beside it lets the car charge as soon as it is plugged in again.")
                    .font(LfType.tiny).foregroundColor(c.muted)
                if model.remoteReady { CarLimitCard() } else { RemoteSetupHint() }
                ChargeLogSection()
            }
        }
    }

    private func gauge(_ st: VehicleStatus?, _ s: AppSettings) -> some View {
        ZStack {
            Ring(fraction: (st?.batteryPct ?? 0) / 100, color: c.meter, size: 210)
            VStack(spacing: 0) {
                (Text(verbatim: wholeNumber(st?.batteryPct)).foregroundColor(c.text).font(.system(size: 54, weight: .semibold))
                    + Text(verbatim: " %").foregroundColor(c.muted).font(.system(size: 24, weight: .semibold)))
                Plain("≈ \(fmtDistance(st?.rangeKm, s))").font(.system(size: 14)).foregroundColor(c.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: 230)
        .accessibilityElement(children: .combine)
    }

    private func details(_ ui: UiState, _ st: VehicleStatus?, _ s: AppSettings) -> some View {
        let power = ChargePower.label(st) ?? (st?.isCharging == true ? "Calculating…" : "—")
        var rate = "—"
        if let r = st?.chargingRateKmh, r > 0 { rate = fmtDistance(r, s) + "/h" }
        let kind = st?.chargingMode == "Quick" ? "Fast (DC)" : (st?.chargingMode == "Slow" ? "AC" : "—")
        var start = "Not set"
        let sched = st.flatMap { ChargePower.scheduledStart($0) }
        if let t = sched { start = TimeFmt.hm(t.hour, t.minute) }
        var health = "—"
        if let h = st?.batteryHealthPct { health = pct(h) + " %" }
        // The car waits for the scheduled time; it is turned off here.
        let canTurnOff = sched != nil && model.remoteReady && ui.commandInProgress == nil
        return LfCard {
            DetailRow(label: "Charging power", value: power)
            LfDivider()
            DetailRow(label: "Range added per hour", value: rate, translate: false)
            LfDivider()
            DetailRow(label: "Type", value: kind)
            LfDivider()
            DetailRow(label: "Scheduled start", value: start, action: canTurnOff ? "Turn off" : nil) {
                confirm(st?.plugged == true ? "Turn off scheduled start? Charging starts now." : "Turn off scheduled start?") { model.clearScheduledStart() }
            }
            LfDivider()
            DetailRow(label: "Battery health", value: health, translate: false)
            EstimatedHealth(compact: false)
        }
    }

    private func chargeButton(_ ui: UiState, _ st: VehicleStatus?) -> some View {
        let charging = st?.isCharging == true
        let sending = ui.commandInProgress?.contains("harg") == true
        return PrimaryButton(sending ? "Sending…" : (charging ? "Stop charging" : "Charge now"), enabled: st?.plugged == true && ui.commandInProgress == nil) {
            confirm(charging ? "Stop charging" : "Start charging now") { model.charging(!charging) }
        }
    }
}

private struct DetailRow: View {
    @Environment(\.lf) private var c
    let label: String
    let value: String
    var translate = true
    var action: String? = nil
    var onAction: () -> Void = {}

    var body: some View {
        HStack(spacing: 0) {
            T(label).font(.system(size: 14)).foregroundColor(c.muted).frame(maxWidth: .infinity, alignment: .leading)
            (translate ? T(value) : Plain(value)).font(LfType.label).foregroundColor(c.text)
            if let action = action {
                T(action).font(LfType.label).foregroundColor(c.accentInk).padding(.vertical, 6).padding(.leading, 14).tap(onAction)
            }
        }
        .padding(.vertical, 2)
    }
}

/// Battery health estimated from the phone's own charging log, shown under the car's official figure. Only for
/// cars that report the energy in the battery and whose size when new is known (set in Settings or reported).
struct EstimatedHealth: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let compact: Bool

    var body: some View {
        if let st = model.ui.status, st.residualKwh != nil {
            let newKwh: Double? = model.prefs.batteryKwh > 0 ? model.prefs.batteryKwh : st.capacityKwh
            let est = ChargeSession.healthEstimate(ChargeLog.sessions, newKwh)
            let value = est.map { "≈ \(pct($0.0)) %" }
            if compact {
                if let value = value { T("Estimated: \(value)").font(LfType.tiny).foregroundColor(c.muted) }
            } else {
                LfDivider()
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        T("Estimated battery health based on charging sessions").font(.system(size: 14)).foregroundColor(c.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Plain(value ?? "—").font(LfType.label).foregroundColor(c.text)
                    }
                    T(est != nil ? "(not an official number) · from \(est!.1) sessions" : "(not an official number) · needs 3 charging sessions of 15 % or more")
                        .font(LfType.tiny).foregroundColor(c.muted)
                }
                .padding(.vertical, 2)
            }
        }
    }
}

/** Shown where remote buttons would be when remote control isn't set up (nothing if the car can't do it). */
struct RemoteSetupHint: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    var body: some View {
        if model.remotePossible {
            T("Remote control isn't set up — tap to enable").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                .padding(.vertical, 4)
                .tap { model.openRemoteSetup() }
            NeedsPackHint(electric: true)
        }
    }
}

/// When the battery reaches 80 %, with a reminder for it. Worked out from the car's time to 100 %: AC charging is
/// steady, so the time to 80 % is that time scaled by how much of the climb is left.
private struct TimeToTarget: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    private let target = 80

    private func remind(_ at: Int64, _ clock: String) {
        let center = UNUserNotificationCenter.current()
        let title = tr("LionFlow: car at \(target) %")
        let when = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: Date(timeIntervalSince1970: Double(at) / 1000))
        center.requestAuthorization(options: [.alert, .sound]) { ok, _ in
            guard ok else {
                Task { @MainActor in model.toastText("Allow notifications for LionFlow in the iPhone's Settings to get the reminder") }
                return
            }
            let content = UNMutableNotificationContent()
            content.title = title
            content.sound = .default
            let request = UNNotificationRequest(identifier: "charge-target", content: content, trigger: UNCalendarNotificationTrigger(dateMatching: when, repeats: false))
            center.add(request) { error in
                Task { @MainActor in model.toastText(error == nil ? "Reminder set for \(clock)" : "Couldn't set the reminder") }
            }
        }
    }

    var body: some View {
        let ui = model.ui
        if let st = ui.status, st.isCharging, let soc = st.batteryPct, let rem = st.remainingMinutes {
            if soc >= Double(target) {
                LfCard { T("Already past \(target) %").font(LfType.body).foregroundColor(c.text2) }
            } else {
                let minutes = Double(rem) * (Double(target) - soc) / max(100 - soc, 1.0)
                let now = nowMillis()
                let fresh = st.updatedAtMillis.flatMap { now - $0 < 30 * 60_000 ? $0 : nil }
                let at = max((fresh ?? now) + JSONValue.javaLong(minutes * 60_000), now + 60_000)
                let parts = Calendar.current.dateComponents([.hour, .minute], from: Date(timeIntervalSince1970: Double(at) / 1000))
                let clock = TimeFmt.hm(parts.hour ?? 0, parts.minute ?? 0)
                let left = Int((at - now) / 60_000)
                LfCard {
                    T("Reaches \(target) % at \(clock)").font(.system(size: 18, weight: .semibold)).foregroundColor(c.text)
                    T("In " + (left >= 60 ? "\(left / 60) h \(left % 60) min" : "\(left) min")).font(LfType.small).foregroundColor(c.muted)
                    SecondaryButton("Remind me at \(clock)") { remind(at, clock) }.padding(.top, 10)
                    T("Worked out from the car's time to 100 %. Stop charging yourself when the reminder comes.").font(LfType.tiny).foregroundColor(c.muted).padding(.top, 8)
                }
            }
        }
    }
}

/** The car's own limit: daily (the percentage is set in the car) or a full charge for a trip. */
private struct CarLimitCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @Environment(\.confirm) private var confirm

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let current = st?.chargeType == "Partial" ? "daily" : (st?.chargeType == "Full" ? "trip" : "")
        let about = st?.chargeType == "Partial" ? "The car stops at the daily limit set in the car's own settings."
            : (st?.chargeType == "Full" ? "The car charges to 100 %. Switch back to Daily limit after the trip." : "The car hasn't reported which limit is active.")
        LfCard {
            T("Charge limit").font(LfType.label).foregroundColor(c.text)
            T("Car's limit").font(LfType.small).foregroundColor(c.muted)
            Segmented(options: [("daily", "Daily limit"), ("trip", "Full (trip)")], selected: current, fill: true) { v in
                if v != current && ui.commandInProgress == nil && st != nil {
                    confirm(v == "daily" ? "Use the car's daily charge limit" : "Charge to 100 % for a trip") { model.setCarChargeLimit(v == "daily") }
                }
            }
            T(about + " The % for the daily limit can only be changed in the car.").font(LfType.tiny).foregroundColor(c.muted)
            LfDivider()
            T("LionFlow's own charge limit and charging times are only in the Android app: they need the phone to check on the car while the app is closed, and an iPhone doesn't let an app do that.")
                .font(LfType.tiny).foregroundColor(c.muted)
        }
    }
}

// MARK: Charging log

/** A charging session picked from the log (for its sheet). */
private struct PickedSession: Identifiable {
    let session: ChargeSession
    var id: Int64 { session.id }
}

/** Charging log, home/away totals and cost estimates, at the bottom of the Charging tab. */
private struct ChargeLogSection: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    @State private var showAll = false
    @State private var editPlaces = false
    @State private var detail: PickedSession?

    var body: some View {
        let sessions = ChargeLog.sessions
        let active = ChargeLog.active
        let cfg = ChargeLog.places
        SectionTitle("Charging log")
        totals(sessions, cfg)
        CostCard(sessions: sessions, cfg: cfg)
        if let a = active {
            LfCard {
                HStack(spacing: 8) {
                    LfIcon(LfIcons.bolt, c.accentInk, 18)
                    T("Charging now · since \(TimeFmt.format(a.start, "HH:mm"))").font(.system(size: 15, weight: .semibold)).foregroundColor(c.text)
                }
                T("\(pct(a.startPct)) % → \(pct(a.endPct)) % · \(kwhText(a.kwh)) so far · \(ChargeLog.match(a, cfg).label)").font(LfType.small).foregroundColor(c.text2)
            }
        }
        if sessions.isEmpty {
            if active == nil {
                T("No charging sessions yet. LionFlow logs a session each time it sees the car charging (it checks every ~10 minutes while charging).")
                    .font(LfType.small).foregroundColor(c.muted)
            }
        } else {
            LfCard {
                ForEach(Array((showAll ? sessions : Array(sessions.prefix(12))).enumerated()), id: \.element.id) { i, sess in
                    if i > 0 { LfDivider() }
                    SessionRow(sess: sess, cfg: cfg).tap { detail = PickedSession(session: sess) }
                }
                if sessions.count > 12 {
                    LfDivider()
                    T(showAll ? "Show fewer" : "Show all \(sessions.count)").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.vertical, 8)
                        .tap { showAll.toggle() }
                }
            }
        }
        SecondaryButton("Places & prices") { editPlaces = true }
            .sheet(isPresented: $editPlaces) {
                PlacesSheet { editPlaces = false }.environmentObject(model).environment(\.lf, c)
            }
        T("Built from the car's own reports, so start and end can be up to ~10 minutes off. Energy is what went into the battery; the meter at the charger reads a few percent more (charging losses).")
            .font(LfType.tiny).foregroundColor(c.muted)
            .sheet(item: $detail) { picked in
                SessionSheet(sess: picked.session) { detail = nil }.environmentObject(model).environment(\.lf, c)
            }
    }

    /** Totals for the last 30 days. */
    private func totals(_ sessions: [ChargeSession], _ cfg: PlacesConfig) -> some View {
        let since = nowMillis() - 30 * 86_400_000
        let recent = sessions.filter { $0.start >= since }
        let matches = recent.map { ($0, ChargeLog.match($0, cfg)) }
        let total = recent.reduce(0.0) { $0 + $1.kwh }
        let home = matches.filter { $0.1.kind == "home" }.reduce(0.0) { $0 + $1.0.kwh }
        let priced = matches.filter { $0.1.price != nil }
        let cost = priced.reduce(0.0) { $0 + $1.0.kwh * ($1.1.price ?? 0) }
        var line = "Add prices under Places & prices to see costs"
        if !priced.isEmpty {
            if priced.count < matches.count {
                line = "≈ \(money(cost, cfg.currency)) for \(priced.count) of \(matches.count) sessions (others have no price)"
            } else {
                line = "≈ \(money(cost, cfg.currency)) estimated cost" + (total > 0 ? " · \(money(cost / total, cfg.currency))/kWh average" : "")
            }
        }
        return LfCard {
            T("Last 30 days").font(LfType.small).foregroundColor(c.muted)
            HStack(alignment: .bottom) {
                Plain(kwhText(total)).font(.system(size: 28, weight: .semibold)).foregroundColor(c.text)
                Spacer()
                T("\(recent.count) sessions").font(LfType.small).foregroundColor(c.muted)
            }
            ProgressBar(fraction: total > 0 ? home / total : 0, color: c.meter)
            HStack {
                T("Home \(kwhText(home))").font(LfType.small).foregroundColor(c.text)
                Spacer()
                T("Away \(kwhText(total - home))").font(LfType.small).foregroundColor(c.text2)
            }
            LfDivider()
            T(line).font(LfType.small).foregroundColor(c.text2)
            if cfg.home?.lat == nil {
                T("Set your home under Places & prices to split home and away.").font(LfType.tiny).foregroundColor(c.muted)
            }
        }
    }
}

private struct SessionRow: View {
    @Environment(\.lf) private var c
    let sess: ChargeSession
    let cfg: PlacesConfig

    var body: some View {
        let m = ChargeLog.match(sess, cfg)
        var bits: [String] = []
        if let a = sess.avgKw { bits.append("avg \(f1(a)) kW") }
        if let p = sess.peakKw { bits.append("peak \(f1(p)) kW") }
        bits.append(sess.mode == "Quick" ? "DC" : "AC")
        if let p = m.price { bits.append(p == 0 ? "free" : "≈ \(money(sess.kwh * p, cfg.currency))") } else { bits.append("no price") }
        return VStack(alignment: .leading, spacing: 2) {
            HStack {
                Plain(TimeFmt.format(sess.start, "EEE d MMM, HH:mm")).font(.system(size: 15, weight: .semibold)).foregroundColor(c.text)
                Spacer()
                T(String(m.label.prefix(28))).font(.system(size: 13, weight: .semibold)).foregroundColor(m.kind == "home" ? c.accentInk : c.text2).lineLimit(1)
            }
            Plain("\(pct(sess.startPct)) % → \(pct(sess.endPct)) % · \(kwhText(sess.kwh)) · \(durText(sess.hours))").font(LfType.small).foregroundColor(c.text2)
            T(bits.joined(separator: " · ")).font(LfType.tiny).foregroundColor(c.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
    }
}

private struct StatTile: View {
    @Environment(\.lf) private var c
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            T(label).font(LfType.tiny).foregroundColor(c.muted)
            T(value).font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(c.line, lineWidth: 1))
    }
}

private struct SheetHeader: View {
    @Environment(\.lf) private var c
    let title: String
    let subtitle: String
    var icon: String? = nil
    var iconTint: Color? = nil
    var iconBg: Color? = nil
    var translateTitle = true
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            if let icon = icon {
                LfIcon(icon, iconTint ?? c.text2, 22).frame(width: 48, height: 48)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(iconBg ?? c.raised))
            }
            VStack(alignment: .leading, spacing: 0) {
                (translateTitle ? T(title) : Plain(title)).font(.system(size: 24, weight: .semibold)).foregroundColor(c.text).lineLimit(1)
                T(subtitle).font(LfType.small).foregroundColor(c.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: onClose) {
                LfIcon(LfIcons.close, c.text2, 18).frame(width: 36, height: 36).background(Circle().fill(c.raised)).contentShape(Circle())
            }
            .buttonStyle(LfPress())
            .accessibilityLabel(Text(verbatim: tr("Close")))
        }
    }
}

/** One charging session in full. */
private struct SessionSheet: View {
    @Environment(\.lf) private var c
    let sess: ChargeSession
    let onClose: () -> Void
    @State private var confirmDelete = false

    var body: some View {
        let cfg = ChargeLog.places
        let m = ChargeLog.match(sess, cfg)
        let home = m.kind == "home"
        let when = "\(TimeFmt.format(sess.start, "EEE d MMM yyyy")) · \(TimeFmt.format(sess.start, "HH:mm"))–\(TimeFmt.format(sess.end, "HH:mm"))"
        let cost = m.price.map { $0 == 0 ? "Free" : "≈ " + money(sess.kwh * $0, cfg.currency) } ?? "—"
        let whereTitle = m.kind == "home" ? "Home" : (m.kind == "known" ? "\(m.label) (your charger)" : (sess.source ?? "Public / other"))
        let whereText = sess.address ?? (sess.lat == nil ? "The car didn't report a position" : "Looking up address…")
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SheetHeader(title: m.label, subtitle: when, icon: home ? LfIcons.home : LfIcons.bolt, iconTint: home ? c.accentInk : c.blueInk,
                            iconBg: home ? c.okBg : c.raisedBlue, onClose: onClose)
                VStack(alignment: .leading, spacing: 12) {
                    HStack(alignment: .bottom) {
                        VStack(alignment: .leading, spacing: 0) {
                            T("Energy").font(LfType.tiny).foregroundColor(c.muted)
                            Plain(kwhText(sess.kwh)).font(.system(size: 34, weight: .semibold)).foregroundColor(c.text)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 0) {
                            T("Cost").font(LfType.tiny).foregroundColor(c.muted)
                            T(cost).font(.system(size: 26, weight: .semibold)).foregroundColor(c.accentInk)
                        }
                    }
                    // The battery from the level it started at to the level it reached.
                    GeometryReader { geo in
                        let a = CGFloat(min(max(sess.startPct / 100, 0), 1))
                        let b = CGFloat(min(max(sess.endPct / 100, 0), 1))
                        ZStack(alignment: .leading) {
                            Capsule().fill(c.bar)
                            Capsule().fill(c.meter).frame(width: geo.size.width * b)
                            Capsule().fill(c.line2).frame(width: geo.size.width * a)
                        }
                    }
                    .frame(height: 10)
                    HStack {
                        Plain("\(pct(sess.startPct)) %").font(LfType.small).foregroundColor(c.muted)
                        Spacer()
                        Plain("+\(pct(sess.endPct - sess.startPct)) %").font(.system(size: 13, weight: .semibold)).foregroundColor(c.text)
                        Spacer()
                        Plain("\(pct(sess.endPct)) %").font(LfType.small).foregroundColor(c.text)
                    }
                    if let p = m.price, p > 0 { Plain("\(JSONValue.javaDouble(p)) \(cfg.currency)/kWh").font(LfType.tiny).foregroundColor(c.muted) }
                }
                .padding(18)
                .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(c.card))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(c.line, lineWidth: 1))
                HStack(spacing: 10) {
                    StatTile(label: "Duration", value: durText(sess.hours))
                    StatTile(label: "Type", value: sess.mode == "Quick" ? "Fast (DC)" : "AC")
                }
                HStack(spacing: 10) {
                    StatTile(label: "Average", value: sess.avgKw.map { "\(f1($0)) kW" } ?? "—")
                    StatTile(label: "Peak", value: sess.peakKw.map { "\(f1($0)) kW" } ?? "—")
                }
                HStack(spacing: 12) {
                    LfIcon(LfIcons.pin, c.muted, 20)
                    VStack(alignment: .leading, spacing: 0) {
                        T(whereTitle).font(LfType.label).foregroundColor(c.text)
                        T(whereText).font(LfType.small).foregroundColor(c.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(16)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.card))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(c.line, lineWidth: 1))
                HStack {
                    T(confirmDelete ? "Tap again to delete" : "Delete session").font(LfType.label).foregroundColor(c.danger).padding(.vertical, 12).tap {
                        if confirmDelete {
                            ChargeLog.delete(sess.id)
                            onClose()
                        } else {
                            confirmDelete = true
                        }
                    }
                    Spacer()
                    PrimaryButton("Done", fill: false, action: onClose)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
    }
}

/** Registered charging costs per period (sessions with a known price). Weeks start on Monday. */
private struct CostCard: View {
    @Environment(\.lf) private var c
    let sessions: [ChargeSession]
    let cfg: PlacesConfig

    private struct Period: Identifiable {
        let label: String
        let from: Int64
        let to: Int64
        var id: String { label }
    }

    private func periods() -> [Period] {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone.current
        cal.firstWeekday = 2
        let now = Date()
        func ms(_ d: Date?) -> Int64 { Int64(((d ?? now).timeIntervalSince1970 * 1000).rounded()) }
        func start(_ unit: Calendar.Component, _ back: Int) -> Int64 {
            let begin = cal.dateInterval(of: unit, for: now)?.start ?? cal.startOfDay(for: now)
            return ms(cal.date(byAdding: unit, value: -back, to: begin))
        }
        let far = Int64.max
        var out = [
            Period(label: "This week", from: start(.weekOfYear, 0), to: far), Period(label: "Last week", from: start(.weekOfYear, 1), to: start(.weekOfYear, 0)),
            Period(label: "This month", from: start(.month, 0), to: far), Period(label: "Last month", from: start(.month, 1), to: start(.month, 0)),
            Period(label: "This year", from: start(.year, 0), to: far),
        ]
        let lastYear = start(.year, 1)
        let thisYear = start(.year, 0)
        if sessions.contains(where: { $0.start >= lastYear && $0.start < thisYear }) { out.append(Period(label: "Last year", from: lastYear, to: thisYear)) }
        return out
    }

    var body: some View {
        if !sessions.isEmpty {
            LfCard {
                T("Charging costs").font(LfType.label).foregroundColor(c.text)
                ForEach(Array(periods().enumerated()), id: \.element.id) { i, p in
                    if i > 0 { LfDivider() }
                    row(p)
                }
            }
        }
    }

    private func row(_ p: Period) -> some View {
        let inside = sessions.filter { $0.start >= p.from && $0.start < p.to }
        let energy = inside.reduce(0.0) { $0 + $1.kwh }
        let prices = inside.map { ($0, ChargeLog.match($0, cfg).price) }
        let cost = prices.reduce(0.0) { $0 + $1.0.kwh * ($1.1 ?? 0) }
        let unpriced = prices.filter { $0.1 == nil }.count
        return HStack {
            VStack(alignment: .leading, spacing: 0) {
                T(p.label).font(LfType.body).foregroundColor(c.text)
                T(kwhText(energy) + (unpriced > 0 ? " · \(unpriced) without price" : "")).font(LfType.tiny).foregroundColor(c.muted)
            }
            Spacer()
            Plain(inside.isEmpty ? "—" : money(cost, cfg.currency)).font(.system(size: 18, weight: .semibold)).foregroundColor(c.text)
        }
        .padding(.vertical, 6)
    }
}

// MARK: Places and prices

private struct PickedPlace: Identifiable {
    let place: ChargePlace
    var id: String { place.id }
}

private struct SheetLabel: View {
    @Environment(\.lf) private var c
    let text: String
    var body: some View {
        Text(verbatim: tr(text).uppercased(with: L10n.locale)).font(.system(size: 12, weight: .semibold)).tracking(1).foregroundColor(c.muted).padding(.top, 6)
    }
}

/** Home, up to five chargers of your own, and what other chargers cost: used for the home/away split and the costs. */
private struct PlacesSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let onClose: () -> Void
    @State private var editing: PickedPlace?
    @State private var ac = ""
    @State private var dc = ""
    @State private var cur = ""
    @State private var loaded = false

    private func save() {
        var cfg = ChargeLog.places
        cfg.publicAcPrice = price(ac)
        cfg.publicDcPrice = price(dc)
        cfg.currency = cur.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "kr" : cur
        ChargeLog.savePlaces(cfg)
        onClose()
    }

    var body: some View {
        let cfg = ChargeLog.places
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                SheetHeader(title: "Places & prices", subtitle: "Used to split home and away charging, and to estimate what it costs.", onClose: onClose)
                SheetLabel(text: "Your places")
                GroupCard {
                    PlaceRow(icon: LfIcons.home, tint: c.accentInk, bg: c.okBg, title: "Home", place: cfg.home, currency: cfg.currency)
                        .tap { editing = PickedPlace(place: cfg.home ?? ChargePlace(id: "home", name: "Home", address: "", lat: nil, lon: nil, price: 0.0)) }
                    ForEach(cfg.known, id: \.id) { k in
                        LfDivider()
                        PlaceRow(icon: LfIcons.pin, tint: c.blueInk, bg: c.raisedBlue, title: k.name, place: k, currency: cfg.currency, translateTitle: false)
                            .tap { editing = PickedPlace(place: k) }
                    }
                    if cfg.known.count < 5 {
                        LfDivider()
                        HStack(spacing: 14) {
                            LfIcon(LfIcons.plus, c.accentInk, 18).frame(width: 40, height: 40)
                                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(c.line2, lineWidth: 1.5))
                            VStack(alignment: .leading, spacing: 0) {
                                T("Add a charger").font(LfType.label).foregroundColor(c.accentInk)
                                T("Work, cabin, a friend's place · \(5 - cfg.known.count) left").font(LfType.tiny).foregroundColor(c.muted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 14)
                        .tap { editing = PickedPlace(place: ChargePlace(id: "k\(nowMillis())", name: "", address: "", lat: nil, lon: nil, price: 0.0)) }
                    }
                }
                SheetLabel(text: "Other chargers")
                T("An estimate for charging anywhere else. Leave empty if you don't know.").font(LfType.tiny).foregroundColor(c.muted)
                HStack(spacing: 10) {
                    PriceBox(label: "Normal (AC)", value: $ac, currency: cur)
                    PriceBox(label: "Fast (DC)", value: $dc, currency: cur)
                }
                SheetLabel(text: "Currency")
                let options = ChargeLog.currencies.contains(ChargeLog.places.currency) ? ChargeLog.currencies : ChargeLog.currencies + [ChargeLog.places.currency]
                ForEach(Array(stride(from: 0, to: options.count, by: 5)), id: \.self) { start in
                    let line = Array(options[start..<min(start + 5, options.count)])
                    HStack(spacing: 8) {
                        ForEach(line, id: \.self) { opt in
                            let on = cur == opt
                            Plain(opt).font(LfType.label).foregroundColor(on ? c.onAccent : c.text)
                                .frame(maxWidth: .infinity).frame(height: 44)
                                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(on ? c.accent : c.card))
                                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(on ? c.accent : c.line, lineWidth: 1))
                                .tap { cur = opt }
                        }
                        ForEach(0..<(5 - line.count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity).frame(height: 44) }
                    }
                }
                PrimaryButton("Save", action: save).padding(.top, 6)
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
        .onAppear {
            if loaded { return }
            loaded = true
            ac = cfg.publicAcPrice > 0 ? JSONValue.javaDouble(cfg.publicAcPrice) : ""
            dc = cfg.publicDcPrice > 0 ? JSONValue.javaDouble(cfg.publicDcPrice) : ""
            cur = cfg.currency
        }
        .sheet(item: $editing) { picked in
            PlaceEditor(place: picked.place) { editing = nil }.environmentObject(model).environment(\.lf, c)
        }
    }
}

private struct PlaceRow: View {
    @Environment(\.lf) private var c
    let icon: String
    let tint: Color
    let bg: Color
    let title: String
    let place: ChargePlace?
    let currency: String
    var translateTitle = true

    var body: some View {
        let set = place?.lat != nil
        let name = title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Charger" : title
        let address = place?.address ?? ""
        HStack(spacing: 14) {
            LfIcon(icon, tint, 20).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(bg))
            VStack(alignment: .leading, spacing: 0) {
                (translateTitle || name == "Charger" ? T(name) : Plain(name)).font(LfType.label).foregroundColor(c.text)
                if set {
                    (address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? T("Location set") : Plain(address)).font(LfType.tiny).foregroundColor(c.muted).lineLimit(1)
                } else {
                    T("Not set — tap to add").font(LfType.tiny).foregroundColor(c.accentInk).lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if set, let p = place {
                T(p.price == 0 ? "Free" : "\(JSONValue.javaDouble(p.price)) \(currency)/kWh").font(.system(size: 12, weight: .semibold)).foregroundColor(c.text)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(c.raised))
            }
            LfIcon(LfIcons.chevron, c.muted, 16)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }
}

private struct PriceBox: View {
    @Environment(\.lf) private var c
    let label: String
    @Binding var value: String
    let currency: String

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            T(label).font(LfType.tiny).foregroundColor(c.muted)
            HStack(alignment: .lastTextBaseline) {
                TextField("0.00", text: $value)
                    .keyboardType(.decimalPad)
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(c.text)
                    .accentColor(c.accentInk)
                    .onChange(of: value) { v in
                        let clean = onlyPrice(v)
                        if clean != v { value = clean }
                    }
                    .accessibilityLabel(Text(verbatim: tr(label)))
                Plain("\(currency)/kWh").font(LfType.tiny).foregroundColor(c.muted)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(c.line, lineWidth: 1))
    }
}

/** One place: its name, where it is (an address, or where the car stands now) and what a kWh costs there. */
private struct PlaceEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let place: ChargePlace
    let onClose: () -> Void
    @State private var name = ""
    @State private var address = ""
    @State private var cost = ""
    @State private var lat: Double?
    @State private var lon: Double?
    @State private var status: String?
    @State private var busy = false
    @State private var loaded = false
    /** The address the app itself filled in (from the saved place, or for the car's position). */
    @State private var filledIn: String?

    private var isHome: Bool { place.id == "home" }

    private func store(_ la: Double?, _ lo: Double?) {
        var cfg = ChargeLog.places
        var np = place
        let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        np.name = isHome ? "Home" : (typed.isEmpty ? "Charger" : typed)
        np.address = address.trimmingCharacters(in: .whitespacesAndNewlines)
        np.lat = la
        np.lon = lo
        np.price = price(cost)
        if isHome { cfg.home = np } else { cfg.known = Array((cfg.known.filter { $0.id != place.id } + [np]).prefix(5)) }
        ChargeLog.savePlaces(cfg)
        onClose()
    }

    private func save() {
        if let la = lat, let lo = lon { store(la, lo); return }
        if address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            status = "Enter an address or use the car's location"
            return
        }
        busy = true
        status = "Finding the address…"
        let wanted = address
        Task {
            let found = try? await Geocoder.find(wanted)
            busy = false
            if let f = found { store(f.0, f.1) } else { status = "Couldn't find that address. Try adding the town, or use the car's location." }
        }
    }

    private func useCar(_ carLat: Double, _ carLon: Double) {
        lat = carLat
        lon = carLon
        status = "Using the car's location"
        let lookup = model.services.address
        Task {
            if let a = await lookup(carLat, carLon) {
                filledIn = a
                address = a
                lat = carLat
                lon = carLon
            }
        }
    }

    var body: some View {
        let st = model.ui.status
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                (isHome ? T("Home") : (place.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? T("Add charger") : Plain(place.name)))
                    .font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
                if !isHome {
                    LfField(label: "Name (e.g. Work, Cabin)", text: $name)
                        .onChange(of: name) { v in if v.count > 30 { name = String(v.prefix(30)) } }
                }
                LfField(label: "Address", text: $address)
                    .onChange(of: address) { v in
                        if v.count > 120 { address = String(v.prefix(120)) }
                        // An address typed by hand has to be looked up again.
                        if v != filledIn {
                            lat = nil
                            lon = nil
                        }
                    }
                if let carLat = st?.lat, let carLon = st?.lon {
                    T("Use the car's current location").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                        .padding(.vertical, 4)
                        .tap { useCar(carLat, carLon) }
                }
                LfField(label: "Price per kWh (empty = free)", text: $cost, keyboard: .decimalPad)
                    .onChange(of: cost) { v in
                        let clean = onlyPrice(v)
                        if clean != v { cost = clean }
                    }
                T(isHome ? "Sessions within 50 m of this spot count as home." : "Sessions within 75 m of this spot use this charger's price.")
                    .font(LfType.tiny).foregroundColor(c.muted)
                if let status = status { T(status).font(LfType.tiny).foregroundColor(c.text2) }
                HStack {
                    if !isHome && !place.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                        T("Remove").font(LfType.label).foregroundColor(c.danger).padding(12).tap {
                            var cfg = ChargeLog.places
                            cfg.known = cfg.known.filter { $0.id != place.id }
                            ChargeLog.savePlaces(cfg)
                            onClose()
                        }
                    }
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onClose)
                    T(busy ? "Saving…" : "Save").font(LfType.label).foregroundColor(c.accentInk).padding(12).tap { if !busy { save() } }
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
        .onAppear {
            if loaded { return }
            loaded = true
            name = place.name
            filledIn = place.address
            address = place.address
            cost = place.price > 0 ? JSONValue.javaDouble(place.price) : ""
            lat = place.lat
            lon = place.lon
        }
    }
}
