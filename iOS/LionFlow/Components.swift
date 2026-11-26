import SwiftUI

// The building blocks every screen is made of: the same cards, buttons and switches as in the Android app.

extension View {
    /** A soft, warm-tinted shadow under cards in light mode, for a little depth. Nothing in dark mode. */
    func softShadow(_ c: LfColors, _ radius: CGFloat = 10) -> some View {
        shadow(color: c.isDark ? .clear : Color(hex: 0xFF3B2A16).opacity(0.16), radius: radius * 0.8, x: 0, y: radius * 0.35)
    }

    /** Lets the app's own background show behind a text box with several lines. */
    @ViewBuilder func clearEditorBackground() -> some View {
        if #available(iOS 16.0, *) { self.scrollContentBackground(.hidden) } else { self }
    }

    /// One line that is set smaller (down to `smallest` of its size) when it is too long for its place. It keeps its
    /// full height: without that, a row of things side by side can squeeze it smaller than it has to be.
    func fitLine(_ smallest: CGFloat) -> some View {
        lineLimit(1).minimumScaleFactor(smallest).fixedSize(horizontal: false, vertical: true)
    }

    /** Makes anything a button without the system's own button look. */
    func tap(_ action: @escaping () -> Void) -> some View {
        Button(action: action) { self.contentShape(Rectangle()) }.buttonStyle(LfPress())
    }
}

/** A button that only dims a little while pressed. */
struct LfPress: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.6 : 1)
    }
}

struct LfIcon: View {
    let name: String
    let color: Color
    var size: CGFloat = 18
    init(_ name: String, _ color: Color, _ size: CGFloat = 18) {
        self.name = name
        self.color = color
        self.size = size
    }
    var body: some View {
        Image(systemName: name)
            .font(.system(size: size * 0.9, weight: .semibold))
            .foregroundColor(color)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

struct LfCard<Content: View>: View {
    @Environment(\.lf) private var c
    var radius: CGFloat = 24
    var padding: CGFloat = 18
    /** Fill the height it is given (for cards standing side by side). */
    var stretch = false
    var onTap: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        let card = VStack(alignment: .leading, spacing: 12) { content() }
            .padding(padding)
            .frame(maxWidth: .infinity, maxHeight: stretch ? .infinity : nil, alignment: .topLeading)
            .background(shape.fill(c.card))
            .overlay(shape.strokeBorder(c.line, lineWidth: 1))
            .softShadow(c)
        if let onTap = onTap {
            Button(action: onTap) { card.contentShape(shape) }.buttonStyle(LfPress())
        } else {
            card
        }
    }
}

struct Chip: View {
    @Environment(\.lf) private var c
    let text: String
    var icon: String? = nil
    var iconTint: Color? = nil
    var onTap: (() -> Void)? = nil

    var body: some View {
        let chip = HStack(spacing: 6) {
            if let icon = icon { LfIcon(icon, iconTint ?? c.chipText, 14) }
            T(text).font(LfType.small).foregroundColor(c.chipText).lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(Capsule().fill(c.card))
        .overlay(Capsule().strokeBorder(c.line, lineWidth: 1))
        if let onTap = onTap { chip.tap(onTap) } else { chip }
    }
}

struct PrimaryButton: View {
    @Environment(\.lf) private var c
    let text: String
    var icon: String? = nil
    var color: Color? = nil
    var enabled = true
    var fill = true
    let action: () -> Void

    init(_ text: String, icon: String? = nil, color: Color? = nil, enabled: Bool = true, fill: Bool = true, action: @escaping () -> Void) {
        self.text = text
        self.icon = icon
        self.color = color
        self.enabled = enabled
        self.fill = fill
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let icon = icon { LfIcon(icon, enabled ? c.onAccent : c.muted, 18) }
                T(text).font(.system(size: 16, weight: .semibold)).foregroundColor(enabled ? c.onAccent : c.muted)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, 22)
            .frame(maxWidth: fill ? .infinity : nil, minHeight: 52)
            .background(Capsule().fill(enabled ? (color ?? c.accent) : c.raised))
            .contentShape(Capsule())
        }
        .buttonStyle(LfPress())
        .disabled(!enabled)
    }
}

struct SecondaryButton: View {
    @Environment(\.lf) private var c
    let text: String
    var textColor: Color? = nil
    var fill = true
    let action: () -> Void

    init(_ text: String, textColor: Color? = nil, fill: Bool = true, action: @escaping () -> Void) {
        self.text = text
        self.textColor = textColor
        self.fill = fill
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            T(text).font(LfType.label).foregroundColor(textColor ?? c.text).multilineTextAlignment(.center)
                .padding(.horizontal, 20)
                .frame(maxWidth: fill ? .infinity : nil, minHeight: 50)
                .overlay(Capsule().strokeBorder(c.line2, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(LfPress())
    }
}

struct CircleIconButton: View {
    @Environment(\.lf) private var c
    let icon: String
    let description: String
    var size: CGFloat = 44
    var filled = false
    var tint: Color? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            LfIcon(icon, tint ?? (filled ? c.onAccent : c.text2), 18)
                .frame(width: size, height: size)
                .background(Circle().fill(filled ? c.accent : c.card))
                .overlay(Circle().strokeBorder(c.line, lineWidth: filled ? 0 : 1))
                .contentShape(Circle())
        }
        .buttonStyle(LfPress())
        .accessibilityLabel(Text(verbatim: tr(description)))
    }
}

/** The app's own switch: a lime pill when on. */
struct LfToggle: View {
    @Environment(\.lf) private var c
    let on: Bool
    let label: String
    let onChange: (Bool) -> Void

    var body: some View {
        Button(action: { onChange(!on) }) {
            Circle().fill(on ? c.onAccent : c.muted)
                .frame(width: 24, height: 24)
                .frame(width: 46, height: 24, alignment: on ? .trailing : .leading)
                .padding(3)
                .background(Capsule().fill(on ? c.accent : c.line2))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
                .animation(.easeOut(duration: 0.15), value: on)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: tr(label)))
        .accessibilityValue(Text(verbatim: tr(on ? "On" : "Off")))
        .accessibilityAddTraits(.isButton)
    }
}

struct ToggleRow: View {
    @Environment(\.lf) private var c
    let title: String
    var subtitle: String? = nil
    let on: Bool
    var showDivider = true
    let onChange: (Bool) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    T(title).font(LfType.label).foregroundColor(c.text)
                    if let s = subtitle { T(s).font(LfType.tiny).foregroundColor(c.muted) }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                LfToggle(on: on, label: title, onChange: onChange)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            if showDivider { LfDivider() }
        }
    }
}

/** A row of choices where one is picked. `options` are (id, label) pairs. */
struct Segmented: View {
    @Environment(\.lf) private var c
    let options: [(String, String)]
    let selected: String
    var fill = false
    let onSelect: (String) -> Void

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.0) { option in
                let active = option.0 == selected
                Button(action: { onSelect(option.0) }) {
                    // The words of a choice are never cut short: what stands beside the control gives way instead.
                    T(option.1).font(.system(size: 14, weight: .semibold)).foregroundColor(active ? c.text : c.muted).lineLimit(1)
                        .fixedSize(horizontal: !fill, vertical: true)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: fill ? .infinity : nil, minHeight: 38)
                        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(active ? c.card : Color.clear).softShadow(c, active ? 3 : 0))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(active ? [.isSelected] : [])
            }
        }
        .padding(3)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(c.raised))
    }
}

struct SectionTitle: View {
    @Environment(\.lf) private var c
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(verbatim: tr(text).uppercased(with: L10n.locale))
            .font(.system(size: 12, weight: .semibold)).tracking(1).foregroundColor(c.muted)
            .padding(.leading, 4)
            .padding(.top, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
    }
}

struct GroupCard<Content: View>: View {
    @Environment(\.lf) private var c
    @ViewBuilder var content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        VStack(alignment: .leading, spacing: 0) { content() }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(c.card))
            .clipShape(shape)
            .overlay(shape.strokeBorder(c.line, lineWidth: 1))
            .softShadow(c)
    }
}

struct ProgressBar: View {
    @Environment(\.lf) private var c
    let fraction: Double
    let color: Color
    var height: CGFloat = 10
    var marker: Double? = nil

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(c.track).frame(height: height)
                Capsule().fill(color).frame(width: geo.size.width * CGFloat(min(max(fraction, 0), 1)), height: height)
                if let m = marker {
                    Rectangle().fill(c.text).frame(width: 2, height: height + 8)
                        .offset(x: geo.size.width * CGFloat(min(max(m, 0), 0.99)))
                }
            }
            .frame(height: height + 8)
        }
        .frame(height: height + 8)
    }
}

struct Wordmark: View {
    @Environment(\.lf) private var c
    var size: CGFloat = 15
    var body: some View {
        (Text(verbatim: "Lion").foregroundColor(c.text) + Text(verbatim: "Flow").foregroundColor(c.accentInk))
            .font(.system(size: size, weight: .bold))
            .accessibilityLabel(Text(verbatim: "LionFlow"))
    }
}

struct ScreenHeader<Trailing: View>: View {
    @Environment(\.lf) private var c
    let kicker: String?
    let title: String
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 4) {
                if let k = kicker { T(k).font(.system(size: 14)).foregroundColor(c.muted) }
                T(title).font(LfType.title).foregroundColor(c.text).accessibilityAddTraits(.isHeader)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
    }
}

extension ScreenHeader where Trailing == EmptyView {
    init(kicker: String?, title: String) {
        self.init(kicker: kicker, title: title, trailing: { EmptyView() })
    }
}

/** The LionFlow lion, drawn from the same points as in the Android app. */
struct LionLogo: View {
    @Environment(\.lf) private var c
    let size: CGFloat
    var accent: Color? = nil
    var face: Color? = nil

    init(_ size: CGFloat, accent: Color? = nil, face: Color? = nil) {
        self.size = size
        self.accent = accent
        self.face = face
    }

    var body: some View {
        let a = accent ?? c.accentInk
        let f = face ?? c.bg
        ZStack {
            LionPart(part: .mane).fill(a)
            LionPart(part: .face).fill(f)
            LionPart(part: .features).fill(a)
            LionPart(part: .lines).stroke(a, style: StrokeStyle(lineWidth: 2.2 * size / 106, lineCap: .round))
        }
        .frame(width: size, height: size)
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: "LionFlow"))
    }
}

struct LionPart: Shape {
    enum Part { case mane, face, features, lines }
    let part: Part

    private static let mane: [(CGFloat, CGFloat)] = [
        (50.0, 8.9), (57.8, 18.6), (70.4, 10.5), (71.8, 25.2), (86.7, 23.3), (81.5, 37.1), (95.8, 41.8), (85.0, 52.0), (95.8, 62.2),
        (81.5, 66.9), (86.7, 80.7), (71.8, 78.8), (70.4, 93.5), (57.8, 85.4), (50.0, 98.1), (42.2, 85.4), (29.6, 93.5), (28.2, 78.8), (13.3, 80.7),
        (18.5, 66.9), (4.2, 62.2), (15.0, 52.0), (4.2, 41.8), (18.5, 37.1), (13.3, 23.3), (28.2, 25.2), (29.6, 10.5), (42.2, 18.6),
    ]
    private static let face: [(CGFloat, CGFloat)] = [(50, 22), (69, 31), (74, 54), (63, 75), (50, 82), (37, 75), (26, 54), (31, 31)]
    private static let features: [[(CGFloat, CGFloat)]] = [
        [(31, 43), (46, 49), (44, 52.5), (34, 50.5)],
        [(69, 43), (54, 49), (56, 52.5), (66, 50.5)],
        [(43, 61), (57, 61), (50, 68)],
        [(43.5, 75), (46.5, 74), (45.3, 79.5)],
        [(56.5, 75), (53.5, 74), (54.7, 79.5)],
    ]
    private static let lines: [((CGFloat, CGFloat), (CGFloat, CGFloat))] = [
        ((47, 53), (45, 61)), ((53, 53), (55, 61)), ((50, 68), (50, 72)), ((50, 72), (42, 75.5)), ((50, 72), (58, 75.5)),
    ]

    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 106
        func point(_ p: (CGFloat, CGFloat)) -> CGPoint { CGPoint(x: rect.minX + p.0 * s, y: rect.minY + p.1 * s) }
        var path = Path()
        func polygon(_ pts: [(CGFloat, CGFloat)]) {
            for (i, p) in pts.enumerated() {
                if i == 0 { path.move(to: point(p)) } else { path.addLine(to: point(p)) }
            }
            path.closeSubpath()
        }
        switch part {
        case .mane: polygon(LionPart.mane)
        case .face: polygon(LionPart.face)
        case .features: for f in LionPart.features { polygon(f) }
        case .lines:
            for l in LionPart.lines {
                path.move(to: point(l.0))
                path.addLine(to: point(l.1))
            }
        }
        return path
    }
}

struct Ring: View {
    @Environment(\.lf) private var c
    let fraction: Double
    let color: Color
    let size: CGFloat
    var stroke: CGFloat = 16

    var body: some View {
        ZStack {
            Circle().stroke(c.track, lineWidth: stroke)
            Circle().trim(from: 0, to: CGFloat(min(max(fraction, 0), 1)))
                .stroke(color, style: StrokeStyle(lineWidth: stroke, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .padding(stroke / 2)
        .frame(width: size, height: size)
    }
}

struct Pill: View {
    let text: String
    let bg: Color
    let fg: Color
    var body: some View {
        T(text).font(.system(size: 12, weight: .semibold)).foregroundColor(fg).lineLimit(1)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Capsule().fill(bg))
    }
}

struct LfDivider: View {
    @Environment(\.lf) private var c
    var body: some View { Rectangle().fill(c.divider).frame(height: 1).frame(maxWidth: .infinity) }
}

/** The small spinning circle shown while something is on its way. */
struct Busy: View {
    @Environment(\.lf) private var c
    var body: some View {
        ProgressView().progressViewStyle(CircularProgressViewStyle(tint: c.accentInk)).frame(width: 20, height: 20)
    }
}

/// A page that scrolls, and that can be pulled down to read the car again. Written by hand so it works the same on
/// every iPhone and iPad the app supports (the system's own pull-to-refresh came to scrolling pages with iOS 16).
///
/// How far the page has moved is asked of the scrolling page itself (`ScrollWatcher`). Where that page can't be found,
/// it is worked out from where SwiftUI has laid the content out instead. That second way used to be the usual one,
/// but it stopped saying anything while a page is scrolled: on iOS 26.5 the walk-through finds that it does not
/// work at all (`test11EachWayOfFollowingAPage` writes down what it finds, per system version).
struct LfScroll<Content: View>: View {
    /** The car is being read: the small circle at the top keeps spinning. */
    var refreshing = false
    var onRefresh: (() -> Void)? = nil
    /** Told when the page has been scrolled further up than `passAt` points, and when it is back again. */
    var passAt: CGFloat = 0
    var onPassed: ((Bool) -> Void)? = nil
    /** How far below the top of the page the circle belongs: the height of the clock where the page goes behind it. */
    var topInset: CGFloat = 0
    @ViewBuilder var content: () -> Content
    @State private var track = ScrollTrack()

    var body: some View {
        ScrollView(.vertical, showsIndicators: false) {
            content()
                .frame(maxWidth: .infinity)
                .background(GeometryReader { g in
                    Color.clear.preference(key: PullOffset.self, value: g.frame(in: .named("lf-scroll")).minY)
                })
                .background(
                    ScrollWatcher(onFound: { track.pageFound = true }, onMoved: { moved($0, byHand: $1, from: .page) })
                        .frame(width: 0, height: 0)
                        .accessibilityHidden(true),
                    alignment: .top)
        }
        .coordinateSpace(name: "lf-scroll")
        .onPreferenceChange(PullOffset.self) { y in moved(-y, byHand: true, from: .layout) }
        .overlay(
            Group { if onRefresh != nil { PullCircle(track: track, refreshing: refreshing, topInset: topInset) } },
            alignment: .top)
    }

    /// `scrolled` is above zero when the page has been pushed up, and below zero while it is pulled down. Only a pull
    /// made by hand counts as one: a page that is still settling into place when it opens is not being pulled.
    private func moved(_ scrolled: CGFloat, byHand: Bool, from source: ScrollTrack.Source) {
        if let only = ScrollTrack.only {
            if only != source { return }
        } else if source == .layout && track.pageFound {
            return
        }
        if let onPassed = onPassed {
            let now = scrolled > passAt
            if now != track.passed {
                track.passed = now
                DispatchQueue.main.async { onPassed(now) }
            }
        }
        guard let onRefresh = onRefresh else { return }
        let pull = byHand ? max(0, -scrolled) : 0
        if pull != track.pull { DispatchQueue.main.async { track.pull = pull } }
        if pull > ScrollTrack.pullToRefresh && track.armed {
            track.armed = false
            DispatchQueue.main.async { onRefresh() }
        } else if pull < 8 {
            track.armed = true
        }
    }
}

/** What a scrolling page remembers between two reports of how far it has moved. */
final class ScrollTrack: ObservableObject {
    enum Source: String { case page, layout }

    /** Pulled this far down, the car is read again. */
    static let pullToRefresh: CGFloat = 76
    /// For the walk-through that is run when the app is built: "-scroll-watch page" or "-scroll-watch layout" makes
    /// the app listen to that one way only, so each of them is known to work on its own.
    static let only: Source? = {
        let args = ProcessInfo.processInfo.arguments
        guard let i = args.firstIndex(of: "-scroll-watch"), i + 1 < args.count else { return nil }
        return Source(rawValue: args[i + 1])
    }()

    /** How far the page is pulled down right now. */
    @Published var pull: CGFloat = 0
    var armed = true
    var passed = false
    var pageFound = false
}

/// The small circle that comes down from the top while the page is pulled, as in the Android app: its ring closes as
/// the pull gets long enough, and it spins for as long as the car is being read.
private struct PullCircle: View {
    @ObservedObject var track: ScrollTrack
    let refreshing: Bool
    let topInset: CGFloat
    @Environment(\.lf) private var c

    var body: some View {
        let progress = Double(min(1, track.pull / ScrollTrack.pullToRefresh))
        ZStack {
            Circle().fill(c.isDark ? c.raised : c.card)
                .shadow(color: Color.black.opacity(c.isDark ? 0.5 : 0.18), radius: 5, x: 0, y: 2)
            if refreshing {
                Busy()
            } else {
                Circle().trim(from: 0, to: CGFloat(0.08 + 0.74 * progress))
                    .stroke(c.accentInk, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90 + 200 * progress))
                    .padding(11)
            }
        }
        .frame(width: 40, height: 40)
        .offset(y: refreshing ? 12 : min(track.pull, 120) * 0.6 - 42)
        // It comes out from under the top edge of the page (below the clock), so it is cut off there.
        .frame(maxWidth: .infinity)
        .frame(height: 120, alignment: .top)
        .clipped()
        .padding(.top, topInset)
        .opacity(refreshing || track.pull > 6 ? 1 : 0)
        .animation(.easeOut(duration: 0.2), value: refreshing)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Sits in a scrolling page and tells how far the page has been scrolled: above zero when it is pushed up, below
/// zero while it is pulled down. It listens to the page SwiftUI scrolls with, which says so on every system version.
private struct ScrollWatcher: UIViewRepresentable {
    let onFound: () -> Void
    /** How far, and whether a finger is moving the page (or it is on its way back after being let go). */
    let onMoved: (CGFloat, Bool) -> Void

    func makeUIView(context: Context) -> Probe {
        let probe = Probe()
        probe.isUserInteractionEnabled = false
        probe.isAccessibilityElement = false
        probe.onFound = onFound
        probe.onMoved = onMoved
        return probe
    }

    func updateUIView(_ probe: Probe, context: Context) {
        probe.onFound = onFound
        probe.onMoved = onMoved
    }

    final class Probe: UIView {
        var onFound: (() -> Void)?
        var onMoved: ((CGFloat, Bool) -> Void)?
        private weak var page: UIScrollView?
        private var watching: NSKeyValueObservation?

        override func didMoveToSuperview() {
            super.didMoveToSuperview()
            look()
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            look()
        }

        /// Finds the scrolling page this view sits in, and starts listening to it. Once found it is kept, also
        /// while this view is taken out for a moment (it sits at the top of the page, which can be scrolled away).
        private func look() {
            var above = superview
            while let view = above, !(view is UIScrollView) { above = view.superview }
            guard let found = above as? UIScrollView else { return }
            if found === page && watching != nil { return }
            page = found
            onFound?()
            watching = found.observe(\.contentOffset, options: [.new]) { [weak self] page, _ in
                self?.onMoved?(page.contentOffset.y + page.adjustedContentInset.top, page.isDragging || page.isDecelerating)
            }
        }
    }
}

private struct PullOffset: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

/** The frame every tab's content sits in: it scrolls and keeps to a readable width on big screens. */
struct ScreenColumn<Content: View>: View {
    var wide = false
    var maxWidth: CGFloat = 720
    var refreshing = false
    var onRefresh: (() -> Void)? = nil
    @ViewBuilder var content: () -> Content

    var body: some View {
        LfScroll(refreshing: refreshing, onRefresh: onRefresh) {
            VStack(alignment: .leading, spacing: 14) { content() }
                .padding(20)
                .frame(maxWidth: wide ? maxWidth : .infinity)
        }
        .overlay(StatusBarBackdrop(), alignment: .top)
    }
}

/** The page's own colour behind the clock at the top, so what is scrolled up doesn't show through it. */
struct StatusBarBackdrop: View {
    @Environment(\.lf) private var c

    var body: some View {
        c.bg.ignoresSafeArea(edges: .top).frame(height: 0)
    }
}

/** A text field in the app's own look: a label above the line being typed. */
struct LfField: View {
    @Environment(\.lf) private var c
    let label: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var secure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            T(label).font(LfType.tiny).foregroundColor(c.muted)
            Group {
                if secure {
                    SecureField("", text: $text)
                } else {
                    TextField("", text: $text)
                }
            }
            .keyboardType(keyboard)
            .disableAutocorrection(true)
            .autocapitalization(.none)
            .font(LfType.body)
            .foregroundColor(c.text)
            .accentColor(c.accentInk)
            .accessibilityLabel(Text(verbatim: tr(label)))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(c.line2, lineWidth: 1))
    }
}

/** Views laid out left to right that go on to a new line when the row is full (brand chips, tags). */
struct FlowRow<Item: Hashable, Content: View>: View {
    let items: [Item]
    var spacing: CGFloat = 8
    @ViewBuilder var content: (Item) -> Content
    @State private var height: CGFloat = 0

    var body: some View {
        GeometryReader { geo in layout(in: geo.size.width) }
            .frame(height: height)
    }

    private func layout(in width: CGFloat) -> some View {
        var x: CGFloat = 0
        var y: CGFloat = 0
        return ZStack(alignment: .topLeading) {
            ForEach(items, id: \.self) { item in
                content(item)
                    .alignmentGuide(.leading) { d in
                        if x + d.width > width && x > 0 {
                            x = 0
                            y += d.height + spacing
                        }
                        let at = x
                        x = item == items.last ? 0 : x + d.width + spacing
                        return -at
                    }
                    .alignmentGuide(.top) { _ in
                        let at = y
                        if item == items.last { y = 0 }
                        return -at
                    }
            }
        }
        .background(GeometryReader { g in
            Color.clear.preference(key: FlowHeight.self, value: g.size.height)
        })
        .onPreferenceChange(FlowHeight.self) { height = $0 }
    }
}

private struct FlowHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}
