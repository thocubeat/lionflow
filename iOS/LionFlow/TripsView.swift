import SwiftUI

// The Trips tab: distance and consumption for the week or month, electric/engine split for hybrids, fuel cost,
// statistics per tag, and the trips themselves.

private func one(_ v: Double) -> String { Fmt.fixed(v, 1) }
private func whole(_ v: Double) -> String { String(JSONValue.javaInt(v)) }
private func startOfToday() -> Int64 { Int64(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000) }
private func numberPart(_ distance: String) -> String { distance.components(separatedBy: " ")[0] }
private func hoursText(_ sec: Double) -> String {
    let m = JSONValue.javaInt(sec / 60)
    return m >= 60 ? tr("\(m / 60) h \(m % 60) min") : "\(m) min"
}

struct BackLink: View {
    @Environment(\.lf) private var c
    let label: String
    let action: () -> Void
    var body: some View {
        HStack(spacing: 2) {
            LfIcon("chevron.left", c.muted, 16)
            T(label).font(.system(size: 14)).foregroundColor(c.muted)
        }
        .padding(.vertical, 6)
        .tap(action)
    }
}

private struct Stat: View {
    @Environment(\.lf) private var c
    let value: String
    let label: String
    var size: CGFloat = 24
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Plain(value).font(.system(size: size, weight: .semibold)).foregroundColor(c.text).fitLine(0.6)
            // As many lines as the label needs: never cut short to match a shorter neighbour.
            T(label).font(LfType.tiny).foregroundColor(c.muted).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/** A trip picked from the list (for its sheet). */
private struct PickedTrip: Identifiable {
    let trip: Trip
    var id: String { trip.id }
}

/** The figures at the top of the Trips tab, worked out once per draw. */
private struct TripsSummary {
    let list: [Trip]
    let km: Double
    let avg: Double?
    let avgL: Double?
    let showFuel: Bool
    let mildHybrid: Bool

    init(_ ui: UiState, _ period: String) {
        let days: Int64 = period == "week" ? 7 : 30
        let since = nowMillis() - days * 86_400_000
        let list = ui.trips.filter { ($0.startMillis ?? 0) >= since }
        let km = list.reduce(0.0) { $0 + $1.distanceKm }
        let mild = ui.status?.isMildHybrid == true
        let kwh = mild ? 0.0 : list.compactMap { $0.kwh }.reduce(0.0, +)
        let liters = list.compactMap { $0.liters }.reduce(0.0, +)
        let fuelKm = list.filter { $0.liters != nil }.reduce(0.0) { $0 + $1.distanceKm }
        let avg: Double? = (km > 0 && kwh > 0) ? kwh / km * 100 : nil
        let avgL: Double? = (fuelKm > 0 && liters > 0) ? liters / fuelKm * 100 : nil
        self.list = list
        self.km = km
        self.avg = avg
        self.avgL = avgL
        mildHybrid = mild
        // Show l/100 km for petrol cars, or when only fuel was used (hybrids show both if both exist).
        showFuel = mild || (avgL != nil && (avg == nil || Caps.fuel(ui.status)))
    }
}

struct TripsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let wide: Bool
    @State private var period = "week"
    @State private var open: PickedTrip?

    var body: some View {
        let ui = model.ui
        let s = model.prefs
        let sum = TripsSummary(ui, period)
        ScreenColumn(wide: wide, refreshing: model.ui.refreshing, onRefresh: { model.refresh() }) {
            if !wide { BackLink(label: "Home") { model.setTab(.home) } }
            ScreenHeader(kicker: period == "week" ? "Last 7 days" : "Last 30 days", title: "Trips") {
                Segmented(options: [("week", "Week"), ("month", "Month")], selected: period) { period = $0 }
            }
            overview(sum, ui, s)
            if ui.status?.energyType == "Hybrid" && !sum.list.isEmpty { HybridStats(trips: sum.list) }
            // Petrol, diesel and hybrid cars: what the fuel cost, and the way in to months, fill-ups and records.
            if Caps.fuel(ui.status) { FuelCard(trips: sum.list) }
            TagStats(trips: sum.list)
            if ui.tripsLoading {
                HStack(spacing: 8) {
                    Busy()
                    T("Loading trips…").font(LfType.small).foregroundColor(c.muted)
                }
            }
            if sum.list.isEmpty && !ui.tripsLoading { empty(ui) }
            if !sum.list.isEmpty { tripList(sum, ui, s) }
        }
        .onAppear { if ui.trips.isEmpty && !ui.tripsLoading { model.loadTrips() } }
        .sheet(item: $open) { picked in
            TripDetail(trip: picked.trip, mildHybrid: sum.mildHybrid) { open = nil }.environmentObject(model).environment(\.lf, c)
        }
    }

    @ViewBuilder private func empty(_ ui: UiState) -> some View {
        T("No trips in this period.").font(LfType.small).foregroundColor(c.muted)
        // The carmaker's server answered "no trips found" (some cars, e.g. newer E-5008, don't share trips with other apps).
        if model.tripsNone && ui.trips.isEmpty {
            T("Peugeot doesn't give trips for this car to other apps, even when the official app shows them. LionFlow now logs the trips itself from the car's reports, whoever drives, so new trips show up here. Older trips can be imported: in the official app export your trips as CSV (or the full .myp file), then use Settings → Import driving data here.")
                .font(LfType.tiny).foregroundColor(c.muted)
        }
    }

    /** The totals, and a bar per day (week) or per week (month). */
    private func overview(_ sum: TripsSummary, _ ui: UiState, _ s: AppSettings) -> some View {
        let buckets = period == "week" ? 7 : 5
        let bucketMs: Int64 = period == "week" ? 86_400_000 : 7 * 86_400_000
        let start = startOfToday() - Int64(buckets - 1) * bucketMs
        var sums = [Double](repeating: 0, count: buckets)
        for t in sum.list {
            let i = Int(((t.startMillis ?? 0) - start) / bucketMs)
            if i >= 0 && i < buckets { sums[i] += t.distanceKm }
        }
        let values = sums
        let top = max(values.max() ?? 0, 1)
        let labels: [String] = (0..<buckets).map { i in
            period == "week" ? String(TimeFmt.format(start + Int64(i) * bucketMs, "EEE").prefix(2)) : "W\(i + 1)"
        }
        let showElectric = !sum.showFuel || (sum.avg != nil && Caps.battery(ui.status))
        return LfCard {
            HStack(alignment: .top, spacing: 4) {
                Stat(value: numberPart(fmtDistance(sum.km, s)), label: s.distanceUnit == "mi" ? "mi driven" : "km driven")
                if sum.showFuel { Stat(value: sum.avgL.map { one($0) } ?? "—", label: "l/100 km") }
                if showElectric { Stat(value: sum.avg.map { one($0) } ?? "—", label: "kWh/100 km") }
                Stat(value: "\(sum.list.count)", label: "trips")
            }
            HStack(alignment: .bottom, spacing: 8) {
                ForEach(0..<buckets, id: \.self) { i in
                    VStack(spacing: 6) {
                        RoundedRectangle(cornerRadius: 8, style: .continuous).fill(i == buckets - 1 ? c.meter : c.bar)
                            .frame(height: 4 + 68 * CGFloat(values[i] / top))
                        Plain(labels[i]).font(.system(size: 11)).foregroundColor(c.muted).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 96, alignment: .bottom)
            .accessibilityHidden(true)
        }
    }

    private func tripList(_ sum: TripsSummary, _ ui: UiState, _ s: AppSettings) -> some View {
        let battery: Double? = (sum.mildHybrid ? nil : ui.status).map { ChargePower.usableKwh($0) }
        let currency = ChargeLog.places.currency
        return LfCard(padding: 0) {
            VStack(spacing: 0) {
                ForEach(Array(sum.list.prefix(50).enumerated()), id: \.element.id) { i, t in
                    if i > 0 { LfDivider() }
                    TripRow(trip: t, mildHybrid: sum.mildHybrid, battery: battery, currency: currency) { open = PickedTrip(trip: t) }
                }
            }
        }
    }
}

private struct TripRow: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trip: Trip
    let mildHybrid: Bool
    let battery: Double?
    let currency: String
    let onOpen: () -> Void

    private var approx: String { trip.energyEstimated ? "≈ " : "" }

    private var lines: [String] {
        let s = model.prefs
        var out: [String] = []
        if !mildHybrid, let a = trip.avgKwhPer100 { out.append(approx + one(a) + " kWh/100") }
        // Battery used on the trip, in % and kWh.
        var used: Double? = nil
        if let k = trip.kwh, k > 0 { used = k } else if let a = trip.avgKwhPer100 { used = a * trip.distanceKm / 100 }
        if let used = used, let battery = battery, battery > 0 { out.append("−" + Fmt.fixed(used / battery * 100, 0) + " % · " + approx + one(used) + " kWh") }
        if let l = trip.avgLPer100 { out.append(one(l) + " l/100") }
        if s.fuelPrice > 0, let l = trip.liters { out.append("≈ " + fuelMoney(l * s.fuelPrice, currency)) }
        return out
    }

    var body: some View {
        var bits: [String] = []
        if let d = trip.durationSec { bits.append("\(whole(d / 60)) min") }
        if let v = trip.avgSpeedKmh { bits.append("\(fmtSpeed(v, model.prefs)) avg") }
        return HStack(spacing: 10) {
            HStack(spacing: 10) {
                LfIcon(LfIcons.route, c.blueInk, 20).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.raisedBlue))
                VStack(alignment: .leading, spacing: 0) {
                    if let start = trip.startMillis {
                        Plain(TimeFmt.format(start, "EEE d MMM · HH:mm")).font(LfType.label).foregroundColor(c.text)
                    } else {
                        T("Trip").font(LfType.label).foregroundColor(c.text)
                    }
                    T(bits.joined(separator: " · ")).font(LfType.small).foregroundColor(c.muted).fitLine(0.75)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .tap(onOpen)
            TripTagSlot(trip: trip)
            VStack(alignment: .trailing, spacing: 0) {
                Plain(fmtDistance(trip.distanceKm, model.prefs)).font(.system(size: 17, weight: .semibold)).foregroundColor(c.text)
                ForEach(lines, id: \.self) { line in Plain(line).font(LfType.tiny).foregroundColor(c.muted) }
            }
            .tap(onOpen)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

private struct InfoTile: View {
    @Environment(\.lf) private var c
    let label: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            T(label).font(LfType.tiny).foregroundColor(c.muted)
            Plain(value).font(.system(size: 20, weight: .semibold)).foregroundColor(c.text)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(c.line, lineWidth: 1))
    }
}

/** One trip in full. */
private struct TripDetail: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trip: Trip
    let mildHybrid: Bool
    let onDismiss: () -> Void

    private func closeButton() -> some View {
        Button(action: onDismiss) {
            LfIcon(LfIcons.close, c.text2, 18).frame(width: 36, height: 36).background(Circle().fill(c.raised)).contentShape(Circle())
        }
        .buttonStyle(LfPress())
        .accessibilityLabel(Text(verbatim: tr("Close")))
    }

    var body: some View {
        let s = model.prefs
        let start = trip.startMillis
        var end = trip.endMillis
        if end == nil, let st = start, let d = trip.durationSec { end = st + JSONValue.javaLong(d * 1000) }
        let km = trip.distanceKm
        let minutes = trip.durationSec.map { $0 / 60 }
        // Average speed from distance and time (the car's own figure isn't reliable on every model).
        var speed = trip.avgSpeedKmh
        if let m = minutes, m >= 1, km > 0 { speed = km / (m / 60) }
        var time = "—"
        if let m = minutes { time = m >= 60 ? "\(whole(m / 60)) h \(whole(m.truncatingRemainder(dividingBy: 60))) min" : "\(whole(m)) min" }
        var speedText = "—"
        if let v = speed { speedText = s.distanceUnit == "mi" ? "\((v * 0.621371).roundToLong) mph" : "\(v.roundToLong) km/h" }
        var use = ("Consumption", "—")
        if !mildHybrid, let a = trip.avgKwhPer100 { use = ("Consumption", (trip.energyEstimated ? "≈ " : "") + one(a) + " kWh/100") } else if let l = trip.avgLPer100 { use = ("Fuel", one(l) + " l/100") }
        var energy: String? = nil
        if !mildHybrid, let k = trip.kwh, k > 0 { energy = (trip.energyEstimated ? "≈ " : "") + one(k) + " kWh" }
        let fuel = trip.liters.map { one($0) + " l" }
        var fuelCost: String? = nil
        if s.fuelPrice > 0, let l = trip.liters { fuelCost = "≈ " + fuelMoney(l * s.fuelPrice, ChargeLog.places.currency) }
        var clock = ""
        if let st = start { clock = TimeFmt.format(st, "HH:mm") + (end.map { "–" + TimeFmt.format($0, "HH:mm") } ?? "") }
        return ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 14) {
                    LfIcon(LfIcons.route, c.blueInk, 22).frame(width: 48, height: 48).background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.raisedBlue))
                    VStack(alignment: .leading, spacing: 0) {
                        if let st = start {
                            Plain(TimeFmt.format(st, "EEE d MMM yyyy")).font(.system(size: 24, weight: .semibold)).foregroundColor(c.text).lineLimit(1)
                            Plain(clock).font(LfType.small).foregroundColor(c.muted)
                        } else {
                            T("Trip").font(.system(size: 24, weight: .semibold)).foregroundColor(c.text)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    closeButton()
                }
                HStack(alignment: .bottom) {
                    VStack(alignment: .leading, spacing: 0) {
                        T("Distance").font(LfType.tiny).foregroundColor(c.muted)
                        Plain(fmtDistance(km, s)).font(.system(size: 34, weight: .semibold)).foregroundColor(c.text)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 0) {
                        T("Duration").font(LfType.tiny).foregroundColor(c.muted)
                        T(time).font(.system(size: 26, weight: .semibold)).foregroundColor(c.accentInk)
                    }
                }
                .padding(18)
                .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(c.card))
                .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(c.line, lineWidth: 1))
                HStack(spacing: 10) {
                    InfoTile(label: "Average speed", value: speedText)
                    InfoTile(label: use.0, value: use.1)
                }
                if energy != nil || fuel != nil {
                    HStack(spacing: 10) {
                        if let e = energy { InfoTile(label: "Energy used", value: e) }
                        if let f = fuel { InfoTile(label: "Fuel used", value: f) }
                        if energy == nil, let cost = fuelCost { InfoTile(label: "Fuel cost", value: cost) } else if energy == nil || fuel == nil { Color.clear.frame(maxWidth: .infinity).frame(height: 1) }
                    }
                }
                if energy != nil, let cost = fuelCost {
                    HStack(spacing: 10) {
                        InfoTile(label: "Fuel cost", value: cost)
                        Color.clear.frame(maxWidth: .infinity).frame(height: 1)
                    }
                }
                HStack {
                    Spacer()
                    PrimaryButton("Done", fill: false, action: onDismiss)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
    }
}

// MARK: Hybrids

/** Plug-in hybrids: electric against engine driving for the period, and engine km since the last oil change. */
private struct HybridStats: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trips: [Trip]

    private func loadSinceOilChange() {
        let since = model.prefs.oilChangeMillis
        if since > 0 {
            let days = Int((nowMillis() - since) / 86_400_000) + 1
            if days > 30 { model.loadTrips(days) }
        }
    }

    var body: some View {
        let s = model.prefs
        let r = HybridSplit.of(trips)
        if r.totalKm > 0 {
            LfCard {
                T("Electric and engine driving").font(LfType.label).foregroundColor(c.text).padding(.bottom, 2)
                HStack(alignment: .top) {
                    Stat(value: "\((r.evShare * 100).roundToLong) %", label: "electric", size: 22)
                    Stat(value: numberPart(fmtDistance(r.evKm, s)), label: s.distanceUnit == "mi" ? "mi electric" : "km electric", size: 22)
                    Stat(value: numberPart(fmtDistance(r.engineKm, s)), label: s.distanceUnit == "mi" ? "mi engine" : "km engine", size: 22)
                }
                HStack(alignment: .top) {
                    Stat(value: one(r.kwh), label: "kWh from battery", size: 22)
                    Stat(value: r.evKwhPer100.map { one($0) } ?? "—", label: "kWh/100 km on electric trips", size: 22)
                }
                T("Electric km is estimated: trips without fuel are fully electric, and mixed trips are split using your own electric consumption.")
                    .font(LfType.tiny).foregroundColor(c.muted).padding(.top, 4)
            }
            oilCard(s)
                .onAppear(perform: loadSinceOilChange)
                .onChange(of: s.oilChangeMillis) { _ in loadSinceOilChange() }
        }
    }

    /** Engine km since the last oil change: not an official service counter, just a useful estimate. */
    private func oilCard(_ s: AppSettings) -> some View {
        let since = s.oilChangeMillis
        let all = model.ui.trips
        let res = HybridSplit.of(all.filter { ($0.startMillis ?? 0) >= since })
        let oldest = all.compactMap { $0.startMillis }.min()
        var sinceText = "Since " + TimeFmt.format(since, "d MMM yyyy")
        if let o = oldest, o > since + 86_400_000 {
            sinceText += ". Peugeot's trip history only goes back to " + TimeFmt.format(o, "d MMM yyyy") + ", so this may be too low."
        } else {
            sinceText += "."
        }
        return LfCard {
            T("Engine km since oil change").font(LfType.label).foregroundColor(c.text)
            if since <= 0 {
                T("Tell LionFlow when the oil was last changed to count the engine km driven since. This is an estimate, not Peugeot's service counter.")
                    .font(LfType.small).foregroundColor(c.muted)
                SecondaryButton("Oil was changed today") { model.update { $0.oilChangeMillis = nowMillis() } }
            } else {
                HStack(alignment: .top) {
                    Stat(value: fmtDistance(res.engineKm, s), label: "on the engine", size: 22)
                    Stat(value: fmtDistance(res.totalKm, s), label: "driven in total", size: 22)
                }
                T(sinceText).font(LfType.tiny).foregroundColor(c.muted)
                SecondaryButton("Oil changed again today") { model.update { $0.oilChangeMillis = nowMillis() } }
            }
        }
    }
}

// MARK: Fuel

/** On the Trips tab: fuel used and what it cost in the period, the price per litre, and the way in to the statistics. */
private struct FuelCard: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trips: [Trip]
    @State private var editPrice = false
    @State private var showStats = false

    var body: some View {
        let s = model.prefs
        let currency = ChargeLog.places.currency
        let liters = FuelStats.liters(trips)
        let cost = FuelStats.cost(liters, s.fuelPrice)
        LfCard {
            HStack {
                T("Fuel cost").font(LfType.label).foregroundColor(c.text).frame(maxWidth: .infinity, alignment: .leading)
                Plain(s.fuelPrice > 0 ? "\(Fmt.fixed(s.fuelPrice, 2)) \(currency)/l" : tr("Set the fuel price")).font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 4)
                    .tap { editPrice = true }
            }
            HStack(alignment: .top) {
                Stat(value: liters > 0 ? "\(one(liters)) l" : "—", label: "fuel used")
                Stat(value: cost.map { "≈ " + fuelMoney($0, currency) } ?? "—", label: "fuel cost")
            }
            if liters <= 0 && !trips.isEmpty { T("The car hasn't sent fuel figures for these trips.").font(LfType.tiny).foregroundColor(c.muted) }
            SecondaryButton("Months, fill-ups and records") { showStats = true }
        }
        .sheet(isPresented: $editPrice) {
            FuelPriceEditor(currency: currency) { editPrice = false }.environmentObject(model).environment(\.lf, c)
        }
        .background(EmptyView().sheet(isPresented: $showStats) {
            FuelStatsSheet { showStats = false }.environmentObject(model).environment(\.lf, c)
        })
    }
}

/** Price per litre, and the tank size (left empty, it is worked out from the fuel gauge). */
struct FuelPriceEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let currency: String
    let onClose: () -> Void
    @State private var price = ""
    @State private var tank = ""
    @State private var loaded = false

    private func save() {
        var p = 0.0
        if let v = JSONValue.javaParseDouble(price), v > 0, v < 100_000 { p = v }
        var t = 0.0
        if let v = JSONValue.javaParseDouble(tank), v >= 10, v <= 200 { t = v }
        model.update {
            $0.fuelPrice = p
            $0.tankLiters = t
        }
        onClose()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                T("Fuel price").font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
                T("What a litre costs you. It is used for the fuel cost of trips, months and fill-ups. The currency is chosen in Settings, under Units.")
                    .font(LfType.small).foregroundColor(c.muted)
                LfField(label: "\(currency)/l", text: $price, keyboard: .decimalPad)
                    .onChange(of: price) { v in
                        let clean = String(v.replacingOccurrences(of: ",", with: ".").filter { $0.isNumber || $0 == "." }.prefix(7))
                        if clean != v { price = clean }
                    }
                T("Tank size in litres. Leave it empty and LionFlow works it out from the fuel gauge.").font(LfType.small).foregroundColor(c.muted)
                LfField(label: "l", text: $tank, keyboard: .numberPad)
                    .onChange(of: tank) { v in
                        let clean = String(v.filter { $0.isNumber }.prefix(3))
                        if clean != v { tank = clean }
                    }
                HStack {
                    Spacer()
                    T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onClose)
                    T("Save").font(LfType.label).foregroundColor(c.accentInk).padding(12).tap(save)
                }
            }
            .padding(24)
        }
        .background(c.card.ignoresSafeArea())
        .onAppear {
            if loaded { return }
            loaded = true
            let s = model.prefs
            price = s.fuelPrice > 0 ? Fmt.fixed(s.fuelPrice, 2) : ""
            tank = s.tankLiters > 0 ? whole(s.tankLiters) : ""
        }
    }
}

private struct StatLine: Identifiable {
    let id: Int
    let label: String
    let value: String
    var note: String? = nil
    var translate = true
}

private struct StatLines: View {
    @Environment(\.lf) private var c
    let lines: [StatLine]
    var body: some View {
        GroupCard {
            ForEach(lines) { line in
                if line.id != lines.first?.id { LfDivider() }
                HStack(spacing: 12) {
                    VStack(alignment: .leading, spacing: 0) {
                        (line.translate ? T(line.label).font(LfType.small) : Plain(line.label).font(.system(size: 13, weight: .semibold))).foregroundColor(c.text)
                        if let note = line.note { Plain(note).font(LfType.tiny).foregroundColor(c.muted) }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Plain(line.value).font(LfType.label).foregroundColor(c.text)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
    }
}

/** Months, fill-ups, records and the figures since new. Loads a year of trips the first time it opens. */
private struct FuelStatsSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let onDismiss: () -> Void

    private func money(_ liters: Double?) -> String? {
        FuelStats.cost(liters, model.prefs.fuelPrice).map { "≈ " + fuelMoney($0, ChargeLog.places.currency) }
    }

    private func sinceNew(_ st: VehicleStatus?) -> [StatLine] {
        var out = [StatLine(id: 0, label: "Odometer", value: fmtDistance(st?.odometerKm, model.prefs))]
        if let avg = FuelStats.lifetimeLPer100(st) { out.append(StatLine(id: 1, label: "Average since new", value: "≈ \(one(avg)) l/100 km")) }
        if let l = FuelStats.lifetimeLiters(st) { out.append(StatLine(id: 2, label: "Fuel since new", value: "≈ \(l.roundToLong) l", note: money(l))) }
        return out
    }

    private func monthLines(_ trips: [Trip]) -> [StatLine] {
        let s = model.prefs
        return FuelStats.monthly(trips).prefix(13).enumerated().map { i, m in
            var bits = [fmtDistance(m.km, s), tr(m.trips == 1 ? "1 trip" : "\(m.trips) trips")]
            if m.seconds > 0 { bits.append(hoursText(m.seconds)) }
            if let l = m.lPer100 { bits.append("\(one(l)) l/100 km") }
            var value: [String] = []
            if m.liters > 0 {
                value.append("\(one(m.liters)) l")
                if let cost = money(m.liters) { value.append(cost) }
            }
            let name = TimeFmt.format(m.startMillis, "LLLL yyyy")
            return StatLine(id: i, label: name.prefix(1).uppercased(with: L10n.locale) + name.dropFirst(), value: value.isEmpty ? "—" : value.joined(separator: "  "),
                            note: bits.joined(separator: " · "), translate: false)
        }
    }

    private func fillLines(_ trips: [Trip], _ tank: Double?) -> [StatLine] {
        let s = model.prefs
        return FuelStats.fillUps(trips, tank).prefix(30).enumerated().map { i, f in
            var note = ["\(f.fromPct.roundToLong) % → \(f.toPct.roundToLong) %"]
            if let km = f.kmSincePrevious { note.append(fmtDistance(km, s)) }
            var value: [String] = []
            if let l = f.liters { value.append("≈ \(l.roundToLong) l") }
            if let cost = money(f.liters) { value.append(cost) }
            return StatLine(id: i, label: TimeFmt.format(f.atMillis, "d MMM · HH:mm"), value: value.isEmpty ? "+\((f.toPct - f.fromPct).roundToLong) %" : value.joined(separator: "  "),
                            note: note.joined(separator: " · "), translate: false)
        }
    }

    private func recordLines(_ rec: FuelStats.Records) -> [StatLine] {
        let s = model.prefs
        func day(_ t: Trip?) -> String? { t?.startMillis.map { TimeFmt.format($0, "EEE d MMM yyyy") } }
        func dayAndKm(_ t: Trip) -> String { [day(t), fmtDistance(t.distanceKm, s)].compactMap { $0 }.joined(separator: " · ") }
        var out: [StatLine] = []
        if let t = rec.longest { out.append(StatLine(id: 0, label: "Longest trip", value: fmtDistance(t.distanceKm, s), note: day(t))) }
        if let t = rec.longestTime { out.append(StatLine(id: 1, label: "Longest time at the wheel", value: hoursText(t.durationSec ?? 0), note: day(t))) }
        if let d = rec.bestDayMillis { out.append(StatLine(id: 2, label: "Most in one day", value: fmtDistance(rec.bestDayKm, s), note: TimeFmt.format(d, "EEE d MMM yyyy"))) }
        if let t = rec.mostEconomical { out.append(StatLine(id: 3, label: "Most economical trip", value: "\(one(t.avgLPer100 ?? 0)) l/100 km", note: dayAndKm(t))) }
        if let t = rec.thirstiest { out.append(StatLine(id: 4, label: "Thirstiest trip", value: "\(one(t.avgLPer100 ?? 0)) l/100 km", note: dayAndKm(t))) }
        return out
    }

    private func tankNote(_ guess: Double?) -> String {
        let s = model.prefs
        if s.tankLiters > 0 { return "Litres are worked out from your tank size (\(whole(s.tankLiters)) l) and the fuel gauge, so they are estimates." }
        if let g = guess { return "Litres are estimates: the tank looks like about \(g.roundToLong) l, worked out from the fuel gauge. You can set the tank size under the fuel price." }
        return "Set the tank size under the fuel price to see litres and cost for each fill-up."
    }

    private func title(_ text: String) -> some View {
        T(text).font(LfType.label).foregroundColor(c.text).padding(.top, 4)
    }

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let trips = ui.trips
        let guess = FuelStats.tankEstimate(trips)
        let tank: Double? = model.prefs.tankLiters > 0 ? model.prefs.tankLiters : guess
        let months = monthLines(trips)
        let fills = fillLines(trips, tank)
        let records = recordLines(FuelStats.records(trips))
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                SheetTop(icon: LfIcons.route, title: "Fuel statistics", subtitle: "From your trips and the car's own counters", onClose: onDismiss)
                if ui.tripsLoading {
                    HStack(spacing: 8) {
                        Busy()
                        T("Loading trips…").font(LfType.small).foregroundColor(c.muted)
                    }
                }
                if st?.odometerKm != nil || FuelStats.lifetimeLiters(st) != nil {
                    title("Since new")
                    StatLines(lines: sinceNew(st))
                    if FuelStats.lifetimeLiters(st) == nil {
                        T("The car doesn't share its fuel counter, so there is no average since new.").font(LfType.tiny).foregroundColor(c.muted)
                    }
                }
                title("Months")
                if months.isEmpty { T("No trips yet.").font(LfType.small).foregroundColor(c.muted) } else { StatLines(lines: months) }
                title("Fill-ups")
                if fills.isEmpty {
                    T("No fill-ups found yet. They are read from the fuel gauge before and after your trips, when the car reports it.").font(LfType.small).foregroundColor(c.muted)
                } else {
                    StatLines(lines: fills)
                    T(tankNote(guess)).font(LfType.tiny).foregroundColor(c.muted)
                }
                title("Records")
                if records.isEmpty { T("No trips yet.").font(LfType.small).foregroundColor(c.muted) } else { StatLines(lines: records) }
                T("Trips of 10 km or more count for the most economical and the thirstiest. The carmaker keeps about a year of trips.").font(LfType.tiny).foregroundColor(c.muted)
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 24)
        }
        .background(c.bg.ignoresSafeArea())
        .onAppear { model.loadTrips(365) }
    }
}

/** The top of a sheet: a symbol, a title with a line under it, and the button that closes it. */
struct SheetTop: View {
    @Environment(\.lf) private var c
    let icon: String
    let title: String
    let subtitle: String
    let onClose: () -> Void

    var body: some View {
        HStack(spacing: 14) {
            LfIcon(icon, c.text2, 22).frame(width: 48, height: 48).background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.raised))
            VStack(alignment: .leading, spacing: 0) {
                T(title).font(.system(size: 24, weight: .semibold)).foregroundColor(c.text).fitLine(0.7)
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

// MARK: Tags

/** The seven tag colours: red, orange, yellow, green, teal, blue, purple. */
private let tagHex: [UInt32] = [0xFFE5484D, 0xFFF08C3A, 0xFFF2C94C, 0xFF57B36A, 0xFF2BB3B0, 0xFF4C8DF6, 0xFF9B6BE8]

func tagColor(_ i: Int) -> Color { Color(hex: tagHex[min(max(i, 0), tagHex.count - 1)]) }

private func luminance(_ hex: UInt32) -> Double {
    func linear(_ v: UInt32) -> Double {
        let x = Double(v) / 255
        return x <= 0.04045 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * linear((hex >> 16) & 0xff) + 0.7152 * linear((hex >> 8) & 0xff) + 0.0722 * linear(hex & 0xff)
}

/** Black or white, whichever is easier to read on the tag's colour. */
func onTag(_ i: Int) -> Color {
    let l = luminance(tagHex[min(max(i, 0), tagHex.count - 1)])
    let white = 1.05 / (l + 0.05)
    let dark = (l + 0.05) / (luminance(0xFF0D0E10) + 0.05)
    return dark >= white ? Color(hex: 0xFF0D0E10) : .white
}

struct TagChip: View {
    let tag: TripTag
    var auto = false
    var maxWidth: CGFloat = 120
    var body: some View {
        // A star marks a tag LionFlow put on by itself.
        Plain(auto ? tag.name + " ✦" : tag.name).font(.system(size: 12, weight: .semibold)).foregroundColor(onTag(tag.color)).lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Capsule().fill(tagColor(tag.color)))
            .frame(maxWidth: maxWidth)
            .fixedSize(horizontal: true, vertical: false)
    }
}

/** In a trip row: the trip's tag, or a small tag button when it has none. Opens the tag picker. */
private struct TripTagSlot: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trip: Trip
    @State private var open = false

    var body: some View {
        let tag = model.tags.tagOf(trip.id)
        Button(action: { open = true }) {
            if let tag = tag {
                TagChip(tag: tag, auto: model.tags.isAuto(trip.id), maxWidth: 96)
            } else {
                LfIcon(LfIcons.tag, c.icon, 18).frame(width: 34, height: 34).contentShape(Circle())
            }
        }
        .buttonStyle(LfPress())
        .accessibilityLabel(Text(verbatim: tag?.name ?? tr("Tag this trip")))
        .sheet(isPresented: $open) {
            TagPicker(trip: trip) { open = false }.environmentObject(model).environment(\.lf, c)
        }
    }
}

private struct ColorDots: View {
    @Environment(\.lf) private var c
    let selected: Int
    let onPick: (Int) -> Void
    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<tagHex.count, id: \.self) { i in
                Button(action: { onPick(i) }) {
                    Circle().fill(tagColor(i)).frame(width: 26, height: 26)
                        .overlay(i == selected ? LfIcon(LfIcons.check, onTag(i), 14) : nil)
                        .padding(4)
                        .overlay(Circle().strokeBorder(i == selected ? c.text : Color.clear, lineWidth: 2))
                        .contentShape(Circle())
                }
                .buttonStyle(LfPress())
                .accessibilityLabel(Text(verbatim: tr("Colour") + " \(i + 1)"))
                .accessibilityAddTraits(i == selected ? [.isSelected] : [])
                if i < tagHex.count - 1 { Spacer(minLength: 0) }
            }
        }
    }
}

/** Pick, make, change or delete tags for one trip. */
private struct TagPicker: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trip: Trip
    let onDismiss: () -> Void
    @State private var editing: TripTag?
    @State private var adding = false
    @State private var name = ""
    @State private var color = 0
    @State private var confirmDelete = false
    @State private var loaded = false

    private func limit(_ v: String) { if v.count > TripTags.maxName { name = String(v.prefix(TripTags.maxName)) } }

    var body: some View {
        ScrollView {
            if let ed = editing { edit(ed) } else { pick() }
        }
        .background(c.card.ignoresSafeArea())
        .onAppear {
            if loaded { return }
            loaded = true
            adding = model.tags.data.tags.isEmpty
            color = model.tags.data.tags.count % tagHex.count
        }
    }

    private func edit(_ ed: TripTag) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            T("Change tag").font(LfType.stat).foregroundColor(c.text)
            LfField(label: "Name", text: $name).onChange(of: name, perform: limit)
            ColorDots(selected: color) { color = $0 }
            if confirmDelete { T("Delete this tag from all trips?").font(LfType.small).foregroundColor(c.danger) }
            T(confirmDelete ? "Yes, delete" : "Delete tag").font(.system(size: 13, weight: .semibold)).foregroundColor(c.danger).padding(.vertical, 6).tap {
                if confirmDelete {
                    model.tags.delete(ed.id)
                    editing = nil
                    confirmDelete = false
                } else {
                    confirmDelete = true
                }
            }
            HStack {
                Spacer()
                T("Cancel").font(LfType.label).foregroundColor(c.muted).padding(12).tap {
                    editing = nil
                    confirmDelete = false
                }
                T("Save").font(LfType.label).foregroundColor(name.trimmingCharacters(in: .whitespaces).isEmpty ? c.muted : c.accentInk).padding(12).tap {
                    if name.trimmingCharacters(in: .whitespaces).isEmpty { return }
                    model.editTag(ed.id, name, color, ed.from, ed.to)
                    editing = nil
                }
            }
        }
        .padding(24)
    }

    private func pick() -> some View {
        let data = model.tags.data
        let current = model.tags.tagOf(trip.id)?.id
        let typed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return VStack(alignment: .leading, spacing: 10) {
            T("Tag this trip").font(LfType.stat).foregroundColor(c.text).padding(.bottom, 4)
            ForEach(data.tags) { t in
                HStack(spacing: 8) {
                    HStack(spacing: 8) {
                        TagChip(tag: t, maxWidth: 170)
                        if t.id == current { LfIcon(LfIcons.check, c.accentInk, 18) }
                        Spacer(minLength: 0)
                    }
                    .tap {
                        model.tagTrip(trip, t.id)
                        onDismiss()
                    }
                    T("Edit").font(LfType.small).foregroundColor(c.muted).padding(8).tap {
                        name = t.name
                        color = t.color
                        editing = t
                    }
                }
                .padding(.vertical, 6)
            }
            if current != nil {
                T("Remove the tag from this trip").font(.system(size: 13, weight: .semibold)).foregroundColor(c.danger).padding(.vertical, 6).tap {
                    model.tagTrip(trip, nil)
                    onDismiss()
                }
            }
            if !data.tags.isEmpty { LfDivider() }
            if !adding {
                HStack(spacing: 8) {
                    LfIcon(LfIcons.plus, c.accentInk, 16)
                    T("New tag").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk)
                }
                .padding(.vertical, 8)
                .tap {
                    adding = true
                    name = ""
                    color = data.tags.count % tagHex.count
                }
            } else {
                LfField(label: "New tag", text: $name).onChange(of: name, perform: limit)
                T("Work, holiday, a client…").font(LfType.tiny).foregroundColor(c.muted)
                ColorDots(selected: color) { color = $0 }
                if !typed.isEmpty {
                    HStack(spacing: 10) {
                        TagChip(tag: TripTag(id: "", name: typed, color: color), maxWidth: 150)
                        T("Add to this trip").font(.system(size: 13, weight: .semibold)).foregroundColor(c.accentInk).padding(8).tap {
                            model.tagTrip(trip, model.tags.create(name, color).id)
                            onDismiss()
                        }
                    }
                }
            }
            LfDivider()
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    T("Tag similar trips automatically").font(.system(size: 13, weight: .semibold)).foregroundColor(c.text)
                    T("A trip with about the same distance and average speed as one you tagged gets the same tag, marked ✦. You can always change it.")
                        .font(LfType.tiny).foregroundColor(c.muted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LfToggle(on: model.prefs.autoTagTrips, label: "Tag similar trips automatically") { model.setAutoTag($0) }
            }
            HStack {
                Spacer()
                T("Close").font(LfType.label).foregroundColor(c.muted).padding(12).tap(onDismiss)
            }
        }
        .padding(24)
    }
}

/** One line of the statistics per tag. */
private struct TagRow: Identifiable {
    let tag: TripTag?
    let value: Double
    let count: Int
    var id: String { tag?.id ?? "" }
}

/** Driving split by tag, for the trips of the period shown on the Trips tab. */
private struct TagStats: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.lf) private var c
    let trips: [Trip]
    @State private var metric = "km"

    private func value(_ t: Trip) -> Double {
        switch metric {
        case "trips": return 1.0
        case "time": return (t.durationSec ?? 0) / 3600.0
        default: return t.distanceKm
        }
    }

    private func show(_ v: Double) -> String {
        switch metric {
        case "trips": return whole(v)
        case "time":
            let h = JSONValue.javaInt(v)
            return v >= 1 ? "\(h) h \(JSONValue.javaInt((v - Double(h)) * 60)) min" : "\(JSONValue.javaInt(v * 60)) min"
        default: return fmtDistance(v, model.prefs)
        }
    }

    private func rows(_ data: TagData) -> [TagRow] {
        var byTag: [String: [Trip]] = [:]
        for t in trips { byTag[model.tags.tagOf(t.id)?.id ?? "", default: []].append(t) }
        var out: [TagRow] = data.tags.map { tag in
            let list = byTag[tag.id] ?? []
            return TagRow(tag: tag, value: list.reduce(0.0) { $0 + value($1) }, count: list.count)
        }
        let rest = byTag[""] ?? []
        if !rest.isEmpty { out.append(TagRow(tag: nil, value: rest.reduce(0.0) { $0 + value($1) }, count: rest.count)) }
        return stableSorted(out, by: { $0.value > $1.value })
    }

    var body: some View {
        let data = model.tags.data
        if data.tags.isEmpty {
            if !model.ui.trips.isEmpty {
                HStack(spacing: 8) {
                    LfIcon(LfIcons.tag, c.muted, 16)
                    T("Tip: tag your trips (work, holiday, a client…) to see statistics per tag.").font(LfType.tiny).foregroundColor(c.muted)
                }
            }
        } else {
            let list = rows(data)
            let total = list.reduce(0.0) { $0 + $1.value }
            let top = max(list.map { $0.value }.max() ?? 0, 1e-9)
            LfCard {
                T("Trip statistics").font(LfType.label).foregroundColor(c.text)
                Segmented(options: [("km", "Distance"), ("trips", "Trips"), ("time", "Time")], selected: metric, fill: true) { metric = $0 }
                if total <= 0 {
                    T("No trips in this period.").font(LfType.small).foregroundColor(c.muted)
                } else {
                    // One bar split by tag, then a line per tag.
                    GeometryReader { geo in
                        HStack(spacing: 0) {
                            ForEach(list.filter { $0.value > 0 }) { row in
                                Rectangle().fill(row.tag.map { tagColor($0.color) } ?? c.bar).frame(width: geo.size.width * CGFloat(row.value / total))
                            }
                        }
                    }
                    .frame(height: 18)
                    .background(c.track)
                    .clipShape(Capsule())
                    .accessibilityHidden(true)
                    ForEach(list) { row in line(row, total, top) }
                }
            }
        }
    }

    private func line(_ row: TagRow, _ total: Double, _ top: Double) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(spacing: 8) {
                if let tag = row.tag { TagChip(tag: tag, maxWidth: 150) } else { T("No tag").font(LfType.small).foregroundColor(c.muted) }
                Spacer(minLength: 0)
                if metric != "trips" { T(row.count == 1 ? "1 trip" : "\(row.count) trips").font(LfType.tiny).foregroundColor(c.muted) }
                Plain(show(row.value)).font(.system(size: 14, weight: .semibold)).foregroundColor(c.text).lineLimit(1)
                Plain(Fmt.fixed(row.value / total * 100, 0) + " %").font(LfType.tiny).foregroundColor(c.muted).frame(width: 38, alignment: .leading)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(c.track)
                    Capsule().fill(row.tag.map { tagColor($0.color) } ?? c.bar).frame(width: geo.size.width * CGFloat(min(max(row.value / top, 0), 1)))
                }
            }
            .frame(height: 6)
        }
    }
}
