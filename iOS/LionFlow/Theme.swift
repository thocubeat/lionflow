import SwiftUI

/// LionFlow's own colours, the same as in the Android app: a dark theme with a lime accent, and a warm light one.
struct LfColors {
    let isDark: Bool
    let bg: Color
    let text: Color
    let text2: Color
    let chipText: Color
    let muted: Color
    let card: Color
    let line: Color
    let line2: Color
    let track: Color
    let nav: Color
    let navLine: Color
    let raised: Color
    let raisedBlue: Color
    let divider: Color
    let bar: Color
    let icon: Color
    let slot: Color
    let accent: Color
    let accentInk: Color
    let meter: Color
    let orange: Color
    let orangeInk: Color
    let blueInk: Color
    let okBg: Color
    let okLine: Color
    let okText: Color
    let danger: Color
    let onAccent: Color

    static let dark = LfColors(
        isDark: true,
        bg: Color(hex: 0xFF0D0E10), text: Color(hex: 0xFFF2F3F5), text2: Color(hex: 0xFFC9CCD1), chipText: Color(hex: 0xFFD5D8DC),
        muted: Color(hex: 0xFF9A9EA6), card: Color(hex: 0xFF16181B), line: Color(hex: 0xFF23262A), line2: Color(hex: 0xFF2A2D32),
        track: Color(hex: 0xFF26292E), nav: Color(hex: 0xFF121316), navLine: Color(hex: 0xFF1F2125), raised: Color(hex: 0xFF1E2024),
        raisedBlue: Color(hex: 0xFF1E2228), divider: Color(hex: 0xFF202327), bar: Color(hex: 0xFF3A3E45), icon: Color(hex: 0xFF6B7078),
        slot: Color(hex: 0xFF121417), accent: Color(hex: 0xFFC6F36B), accentInk: Color(hex: 0xFFC6F36B), meter: Color(hex: 0xFFC6F36B),
        orange: Color(hex: 0xFFFFB547), orangeInk: Color(hex: 0xFFFFB547), blueInk: Color(hex: 0xFF8AB4FF),
        okBg: Color(hex: 0xFF1A1E12), okLine: Color(hex: 0xFF2F3A1A), okText: Color(hex: 0xFFE4F7C2), danger: Color(hex: 0xFFE5484D),
        onAccent: Color(hex: 0xFF0D0E10))

    // A warm off-white page under near-white cards (with soft shadows), and dark warm-grey text for easy reading.
    static let light = LfColors(
        isDark: false,
        bg: Color(hex: 0xFFEFEBE4), text: Color(hex: 0xFF14120F), text2: Color(hex: 0xFF322E29), chipText: Color(hex: 0xFF262320),
        muted: Color(hex: 0xFF58534B), card: Color(hex: 0xFFFFFDFA), line: Color(hex: 0xFFE6E0D7), line2: Color(hex: 0xFFD3CCC1),
        track: Color(hex: 0xFFE5DFD5), nav: Color(hex: 0xFFFFFDFA), navLine: Color(hex: 0xFFE2DBD1), raised: Color(hex: 0xFFF5F1EB),
        raisedBlue: Color(hex: 0xFFE9EEF8), divider: Color(hex: 0xFFEFEAE3), bar: Color(hex: 0xFFC6BEB2), icon: Color(hex: 0xFF746E65),
        slot: Color(hex: 0xFFF2EEE8), accent: Color(hex: 0xFFC6F36B), accentInk: Color(hex: 0xFF3F6600), meter: Color(hex: 0xFF558F00),
        orange: Color(hex: 0xFFFFB547), orangeInk: Color(hex: 0xFFA35F00), blueInk: Color(hex: 0xFF2560C8),
        okBg: Color(hex: 0xFFEEF5D8), okLine: Color(hex: 0xFFD0E39C), okText: Color(hex: 0xFF2A4300), danger: Color(hex: 0xFFC62B30),
        onAccent: Color(hex: 0xFF0D0E10))
}

extension Color {
    /** A colour written as 0xAARRGGBB, as in the Android code. */
    init(hex: UInt32) {
        let a = Double((hex >> 24) & 0xff) / 255
        let r = Double((hex >> 16) & 0xff) / 255
        let g = Double((hex >> 8) & 0xff) / 255
        let b = Double(hex & 0xff) / 255
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

private struct LfColorsKey: EnvironmentKey {
    static let defaultValue = LfColors.dark
}

extension EnvironmentValues {
    /** The colours of the theme in use. */
    var lf: LfColors {
        get { self[LfColorsKey.self] }
        set { self[LfColorsKey.self] = newValue }
    }
}

/** Text sizes, the same as in the Android app. */
enum LfType {
    static func display(_ size: CGFloat, _ weight: Font.Weight = .semibold) -> Font { .system(size: size, weight: weight) }
    static let title = display(30)
    static let big = display(52)
    static let stat = display(22)
    static let body = Font.system(size: 15)
    static let small = Font.system(size: 13)
    static let tiny = Font.system(size: 12)
    static let label = Font.system(size: 15, weight: .semibold)
}

/// A text in the app's language. Every text the app shows goes through here (or through `tr`), as on Android.
func T(_ english: String) -> Text { Text(verbatim: tr(english)) }

/** A text shown as it is: numbers, names, the car's own words. */
func Plain(_ text: String) -> Text { Text(verbatim: text) }

/** Names of the symbols used for icons (Apple's SF Symbols, all there since iOS 15). */
enum LfIcons {
    static let home = "house.fill"
    static let bolt = "bolt.fill"
    static let thermo = "thermometer"
    static let route = "point.topleft.down.curvedto.point.bottomright.up"
    static let map = "map.fill"
    static let wrench = "wrench.fill"
    static let gear = "gearshape.fill"
    static let menu = "ellipsis"
    static let lock = "lock.fill"
    static let unlock = "lock.open.fill"
    static let bulb = "lightbulb.fill"
    static let horn = "speaker.wave.2.fill"
    static let pin = "mappin.and.ellipse"
    static let refresh = "arrow.clockwise"
    static let camera = "camera.fill"
    static let cloud = "cloud.fill"
    static let plug = "powerplug.fill"
    static let chevron = "chevron.right"
    static let car = "car.fill"
    static let shield = "exclamationmark.shield.fill"
    static let download = "arrow.down.circle.fill"
    static let close = "xmark"
    static let check = "checkmark"
    static let backspace = "delete.left"
    static let sun = "sun.max.fill"
    static let snow = "snowflake"
    static let rain = "cloud.rain.fill"
    static let plus = "plus"
    static let trash = "trash"
    static let tag = "tag.fill"
    static let edit = "pencil"
    static let fuel = "fuelpump.fill"
    static let clock = "clock"
    static let info = "info.circle"
    static let copy = "doc.on.doc"
    static let share = "square.and.arrow.up"
    static let person = "person.crop.circle"
    static let faceId = "faceid"
    static let touchId = "touchid"
}
