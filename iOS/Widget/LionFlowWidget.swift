import SwiftUI
import WidgetKit

/// The home-screen widget: battery, range, charging and the temperatures, as on the Android app's widget.
/// It shows what the app last read (the app writes it to a shared folder: see Core/WidgetSnapshot.swift); the
/// ↻ button opens the app, which reads the car.
@main
struct LionFlowWidgets: WidgetBundle {
    var body: some Widget { CarWidget() }
}

struct CarWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "LionFlowCar", provider: CarTimeline()) { entry in
            CarWidgetView(entry: entry)
        }
        .configurationDisplayName("LionFlow")
        .description("Battery, range and charging at a glance.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
        .noContentMargins()
    }
}

struct CarEntry: TimelineEntry {
    let date: Date
    let snap: WidgetSnapshot?
    /// The shared folder can't be reached (the sideloading tool left out the App Group).
    let noShelf: Bool
}

struct CarTimeline: TimelineProvider {
    func placeholder(in context: Context) -> CarEntry { CarEntry(date: Date(), snap: Self.sample, noShelf: false) }

    func getSnapshot(in context: Context, completion: @escaping (CarEntry) -> Void) {
        let snap = WidgetShelf.read()
        completion(CarEntry(date: Date(), snap: context.isPreview && snap?.hasData != true ? Self.sample : snap, noShelf: false))
    }

    /// One entry every 5 minutes for the next hour, so "Updated … ago" keeps counting; the app asks for a new
    /// timeline each time it has read the car.
    func getTimeline(in context: Context, completion: @escaping (Timeline<CarEntry>) -> Void) {
        let snap = WidgetShelf.read()
        let noShelf = WidgetShelf.folder == nil
        let now = Date()
        let entries = (0..<12).map { CarEntry(date: now.addingTimeInterval(Double($0) * 300), snap: snap, noShelf: noShelf) }
        completion(Timeline(entries: entries, policy: .atEnd))
    }

    static let sample: WidgetSnapshot = {
        var w = WidgetSnapshot()
        w.signedIn = true; w.hasData = true; w.name = "My Peugeot"
        w.battery = 76; w.batteryText = "76"; w.rangeText = "≈312"; w.rangeUnit = " km"
        w.plugged = true; w.charging = true; w.chargeLine = "Charging 7.2 kW · 1 h 20 min left"
        w.cabinText = "Not heating"; w.outsideText = "4°C"; w.place = "Home"
        let t = Int64(Date().timeIntervalSince1970 * 1000)
        w.carTime = t; w.fetched = t
        return w
    }()
}

struct CarWidgetView: View {
    let entry: CarEntry
    @Environment(\.widgetFamily) private var family

    private var snap: WidgetSnapshot { entry.snap ?? WidgetSnapshot() }
    private var nowMs: Int64 { Int64(entry.date.timeIntervalSince1970 * 1000) }

    var body: some View {
        content
            .widgetURL(URL(string: "lionflow://open"))
            .widgetBackground(WColor.bg)
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .systemSmall: small
        case .systemLarge: full(panel: true)
        default: full(panel: false)
        }
    }

    // MARK: Pieces

    private var subLine: String {
        if entry.noShelf { return "Open LionFlow once" }
        if entry.snap == nil { return "Open LionFlow to load your car" }
        let line = snap.updatedLine(now: nowMs, short: family != .systemLarge)
        return [snap.place, line].compactMap { $0 }.joined(separator: " · ")
    }

    private func header(lion: CGFloat, nameSize: CGFloat, button: Bool) -> some View {
        HStack(spacing: 0) {
            LionMark().frame(width: lion, height: lion)
            Rectangle().fill(WColor.divider).frame(width: 1, height: lion * 0.9).padding(.horizontal, lion * 0.3)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: snap.name).font(.system(size: nameSize, weight: .bold)).foregroundColor(WColor.text)
                    .lineLimit(1).minimumScaleFactor(0.7)
                HStack(spacing: 5) {
                    if snap.place != nil && entry.snap != nil {
                        Image(systemName: "mappin").font(.system(size: 11, weight: .bold)).foregroundColor(WColor.accent)
                    }
                    Text(verbatim: subLine).font(.system(size: 12)).foregroundColor(WColor.muted).lineLimit(1)
                }
            }
            Spacer(minLength: 6)
            if button {
                Link(destination: URL(string: "lionflow://refresh")!) {
                    Image(systemName: "arrow.triangle.2.circlepath").font(.system(size: 19, weight: .semibold)).foregroundColor(WColor.text)
                        .frame(width: 44, height: 44)
                        .background(RoundedRectangle(cornerRadius: 17, style: .continuous).fill(WColor.button))
                        .overlay(RoundedRectangle(cornerRadius: 17, style: .continuous).strokeBorder(Color(hex: 0x363B43), lineWidth: 1))
                }
            }
        }
    }

    private func big(_ value: String, _ small: String, size: CGFloat, ratio: CGFloat, color: Color) -> Text {
        Text(verbatim: value).font(.system(size: size, weight: .bold)).foregroundColor(color)
            + Text(verbatim: small).font(.system(size: size * ratio, weight: .bold)).foregroundColor(color)
    }

    private var batteryValue: (String, String) { snap.hasData && snap.batteryText != "—" ? (snap.batteryText, "%") : ("—", "") }
    private var rangeValue: (String, String) { snap.hasData && snap.rangeText != "—" ? (snap.rangeText, snap.rangeUnit) : ("—", "") }

    private func cardShape(_ r: CGFloat = 20) -> RoundedRectangle { RoundedRectangle(cornerRadius: r, style: .continuous) }

    private var batteryCard: some View {
        HStack(spacing: 10) {
            BatteryMark().frame(width: 22, height: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: snap.batteryLabel).font(.system(size: 13, weight: .medium)).foregroundColor(WColor.muted)
                big(batteryValue.0, batteryValue.1, size: 44, ratio: 0.5, color: WColor.accent)
                    .lineLimit(1).minimumScaleFactor(0.4)
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, 12).padding(.trailing, 8).padding(.vertical, 8)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(
            ZStack {
                cardShape().fill(WColor.card)
                cardShape().fill(RadialGradient(colors: [WColor.glow.opacity(0.5), WColor.glow.opacity(0.15), WColor.glow.opacity(0)],
                                                center: .bottomTrailing, startRadius: 0, endRadius: 130))
            }
        )
        .overlay(cardShape().strokeBorder(WColor.line, lineWidth: 1))
    }

    private var rangeCard: some View {
        HStack(alignment: .top, spacing: 8) {
            RangeMark().frame(width: 26, height: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: snap.rangeLabel).font(.system(size: 13, weight: .medium)).foregroundColor(WColor.muted)
                big(rangeValue.0, rangeValue.1, size: 34, ratio: 0.42, color: WColor.text)
                    .lineLimit(1).minimumScaleFactor(0.4)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10).padding(.top, 10).padding(.bottom, 18)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(
            ZStack(alignment: .bottom) {
                cardShape().fill(WColor.card)
                RangeWaves().frame(height: 30).clipShape(cardShape())
            }
            .clipShape(cardShape())
        )
        .overlay(cardShape().strokeBorder(WColor.line, lineWidth: 1))
    }

    private func bar(height: CGFloat) -> some View {
        GeometryReader { g in
            let pct = min(max((snap.hasData ? snap.battery : nil) ?? 0, 0), 100) / 100
            ZStack(alignment: .leading) {
                Capsule().fill(WColor.track)
                Capsule().strokeBorder(WColor.line, lineWidth: 1)
                if pct > 0 {
                    Capsule().fill(WColor.fill).frame(width: max(height - 4, (g.size.width - 4) * pct)).padding(2)
                }
            }
        }
        .frame(height: height)
    }

    private var chargeRow: some View {
        HStack(spacing: 12) {
            if snap.charging { BoltMark().frame(width: 22, height: 22) }
            else if !snap.fuel { PlugMark().frame(width: 22, height: 22) }
            if !snap.fuel || !snap.hasData {
                Text(verbatim: snap.hasData ? snap.chargeLine : snap.noData)
                    .font(.system(size: 15)).foregroundColor(snap.charging ? WColor.text : WColor.muted).lineLimit(1).minimumScaleFactor(0.8)
            }
            Spacer(minLength: 0)
        }
        .padding(.leading, 4)
    }

    private func tile<V: View>(glow: Bool, @ViewBuilder _ icon: () -> V) -> some View {
        icon()
            .frame(width: 40, height: 40)
            .background(
                ZStack {
                    RoundedRectangle(cornerRadius: 15, style: .continuous).fill(WColor.tile)
                    if glow {
                        RoundedRectangle(cornerRadius: 15, style: .continuous)
                            .fill(RadialGradient(colors: [WColor.glow.opacity(0.5), WColor.glow.opacity(0)], center: .bottomTrailing, startRadius: 0, endRadius: 34))
                    }
                }
            )
            .overlay(RoundedRectangle(cornerRadius: 15, style: .continuous).strokeBorder(Color(hex: 0x343941), lineWidth: 1))
    }

    private var panel: some View {
        HStack(spacing: 10) {
            tile(glow: false) { FanMark(on: snap.precond).frame(width: 22, height: 22) }
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: snap.carLabel).font(.system(size: 12)).foregroundColor(WColor.muted)
                Text(verbatim: snap.cabinText).font(.system(size: 15, weight: .bold)).foregroundColor(WColor.text).lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Rectangle().fill(WColor.divider).frame(width: 1, height: 34)
            tile(glow: true) { Image(systemName: "thermometer").font(.system(size: 19, weight: .medium)).foregroundColor(WColor.text) }
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: snap.outsideLabel).font(.system(size: 12)).foregroundColor(WColor.muted)
                Text(verbatim: snap.outsideText).font(.system(size: 15, weight: .bold)).foregroundColor(WColor.text).lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 10).padding(.vertical, 9)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(WColor.panel))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(WColor.line, lineWidth: 1))
    }

    // MARK: Sizes

    private func full(panel withPanel: Bool) -> some View {
        VStack(spacing: 0) {
            header(lion: withPanel ? 40 : 32, nameSize: withPanel ? 21 : 18, button: true)
            HStack(spacing: 10) { batteryCard; rangeCard }
                .frame(minHeight: 60).padding(.top, withPanel ? 14 : 10)
            bar(height: withPanel ? 16 : 12).padding(.top, withPanel ? 14 : 9)
            if withPanel {
                chargeRow.padding(.top, 12)
                panel.padding(.top, 12)
            } else {
                chargeRow.padding(.top, 8)
            }
        }
        .padding(.leading, 16).padding(.trailing, 14).padding(.vertical, withPanel ? 16 : 12)
    }

    private var small: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 8) {
                LionMark().frame(width: 24, height: 24)
                Text(verbatim: snap.name).font(.system(size: 14, weight: .bold)).foregroundColor(WColor.text).lineLimit(1).minimumScaleFactor(0.7)
            }
            Spacer(minLength: 4)
            Text(verbatim: snap.batteryLabel).font(.system(size: 12, weight: .medium)).foregroundColor(WColor.muted)
            big(batteryValue.0, batteryValue.1, size: 40, ratio: 0.5, color: WColor.accent).lineLimit(1).minimumScaleFactor(0.5)
            big(rangeValue.0, rangeValue.1, size: 18, ratio: 0.6, color: WColor.text).lineLimit(1).minimumScaleFactor(0.6)
            bar(height: 10).padding(.top, 8)
            HStack(spacing: 4) {
                if snap.charging { BoltMark().frame(width: 12, height: 12) }
                Text(verbatim: entry.snap == nil || entry.noShelf ? subLine : snap.updatedLine(now: nowMs, short: true))
                    .font(.system(size: 11)).foregroundColor(WColor.muted).lineLimit(1).minimumScaleFactor(0.8)
            }
            .padding(.top, 6)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

private extension View {
    /// iOS 17 draws the widget's background itself (and needs to be told what it is); before that it is just behind.
    @ViewBuilder func widgetBackground<S: ShapeStyle>(_ style: S) -> some View {
        if #available(iOSApplicationExtension 17.0, *) {
            containerBackground(for: .widget) { Rectangle().fill(style) }
        } else {
            background(Rectangle().fill(style))
        }
    }
}

private extension WidgetConfiguration {
    /// iOS 17 adds margins around a widget's content; this widget lays out its own.
    func noContentMargins() -> some WidgetConfiguration {
        if #available(iOSApplicationExtension 17.0, *) {
            return self.contentMarginsDisabled()
        } else {
            return self
        }
    }
}
