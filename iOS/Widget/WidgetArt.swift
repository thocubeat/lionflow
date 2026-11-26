import SwiftUI

/// Colours and drawings of the home-screen widget: the same as the Android app's widget.
enum WColor {
    static let text = Color(hex: 0xF2F3F5)
    static let muted = Color(hex: 0x9A9EA6)
    static let accent = Color(hex: 0xC6F36B)
    static let orange = Color(hex: 0xFFB547)
    static let divider = Color(hex: 0x2E333A)
    static let line = Color(hex: 0x2C3138)
    static let glow = Color(hex: 0xB6EE3C)
    static let bg = LinearGradient(colors: [Color(hex: 0x15181C), Color(hex: 0x0B0D10)], startPoint: .top, endPoint: .bottom)
    static let card = LinearGradient(colors: [Color(hex: 0x191C21), Color(hex: 0x111317)], startPoint: .top, endPoint: .bottom)
    static let panel = LinearGradient(colors: [Color(hex: 0x181B20), Color(hex: 0x101215)], startPoint: .top, endPoint: .bottom)
    static let tile = LinearGradient(colors: [Color(hex: 0x252930), Color(hex: 0x16191D)], startPoint: .top, endPoint: .bottom)
    static let button = LinearGradient(colors: [Color(hex: 0x262A31), Color(hex: 0x15181C)], startPoint: .top, endPoint: .bottom)
    static let track = LinearGradient(colors: [Color(hex: 0x121417), Color(hex: 0x1B1E23)], startPoint: .top, endPoint: .bottom)
    static let fill = LinearGradient(colors: [Color(hex: 0xA8E23C), Color(hex: 0xD8FF72)], startPoint: .leading, endPoint: .trailing)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: alpha)
    }
}

/// A shape from SVG path data (M L H V C S Q Z, absolute and relative), scaled from its view box to the frame.
struct VectorShape: Shape {
    let d: String
    let box: CGSize

    func path(in rect: CGRect) -> Path {
        let p = VectorShape.parse(d)
        let sx = rect.width / box.width, sy = rect.height / box.height
        return p.applying(CGAffineTransform(a: sx, b: 0, c: 0, d: sy, tx: rect.minX, ty: rect.minY))
    }

    static func parse(_ d: String) -> Path {
        var tokens: [String] = []
        var num = ""
        func flush() { if !num.isEmpty { tokens.append(num); num = "" } }
        for ch in d {
            if ch.isLetter && ch != "e" { flush(); tokens.append(String(ch)) }
            else if ch == "-" { if num.last == "e" { num.append(ch) } else { flush(); num = "-" } }
            else if ch == "," || ch == " " || ch == "\n" { flush() }
            else if ch == "." && num.contains(".") { flush(); num = "." }
            else { num.append(ch) }
        }
        flush()
        var path = Path()
        var i = 0
        var cmd = "M"
        var cur = CGPoint.zero, start = CGPoint.zero, lastCtrl: CGPoint? = nil
        func n() -> CGFloat { defer { i += 1 }; return i < tokens.count ? CGFloat(Double(tokens[i]) ?? 0) : 0 }
        func pt(_ rel: Bool) -> CGPoint { let x = n(), y = n(); return rel ? CGPoint(x: cur.x + x, y: cur.y + y) : CGPoint(x: x, y: y) }
        while i < tokens.count {
            if let c = tokens[i].first, c.isLetter { cmd = tokens[i]; i += 1 }
            let rel = cmd == cmd.lowercased()
            switch cmd.uppercased() {
            case "M":
                cur = pt(rel); start = cur; path.move(to: cur)
                cmd = rel ? "l" : "L"; lastCtrl = nil
            case "L": cur = pt(rel); path.addLine(to: cur); lastCtrl = nil
            case "H": let x = n(); cur = CGPoint(x: rel ? cur.x + x : x, y: cur.y); path.addLine(to: cur); lastCtrl = nil
            case "V": let y = n(); cur = CGPoint(x: cur.x, y: rel ? cur.y + y : y); path.addLine(to: cur); lastCtrl = nil
            case "C":
                let c1 = pt(rel), c2 = pt(rel), e = pt(rel)
                path.addCurve(to: e, control1: c1, control2: c2); cur = e; lastCtrl = c2
            case "S":
                let c1 = lastCtrl.map { CGPoint(x: 2 * cur.x - $0.x, y: 2 * cur.y - $0.y) } ?? cur
                let c2 = pt(rel), e = pt(rel)
                path.addCurve(to: e, control1: c1, control2: c2); cur = e; lastCtrl = c2
            case "Q":
                let c = pt(rel), e = pt(rel)
                path.addQuadCurve(to: e, control: c); cur = e; lastCtrl = nil
            case "Z":
                path.closeSubpath(); cur = start; lastCtrl = nil
                if i < tokens.count, let c = tokens[i].first, !c.isLetter { cmd = "L" }
            default: i += 1
            }
        }
        return path
    }
}

/// The lion head of the widget's corner.
struct LionMark: View {
    var body: some View {
        ZStack {
            VectorShape(d: "M50.0 8.9 L57.8 18.6 L70.4 10.5 L71.8 25.2 L86.7 23.3 L81.5 37.1 L95.8 41.8 L85.0 52.0 L95.8 62.2 L81.5 66.9 L86.7 80.7 L71.8 78.8 L70.4 93.5 L57.8 85.4 L50.0 98.1 L42.2 85.4 L29.6 93.5 L28.2 78.8 L13.3 80.7 L18.5 66.9 L4.2 62.2 L15.0 52.0 L4.2 41.8 L18.5 37.1 L13.3 23.3 L28.2 25.2 L29.6 10.5 L42.2 18.6 Z", box: CGSize(width: 100, height: 106))
                .fill(WColor.accent)
            VectorShape(d: "M50 22 L69 31 L74 54 L63 75 L50 82 L37 75 L26 54 L31 31 Z", box: CGSize(width: 100, height: 106))
                .fill(Color(hex: 0x0F1012))
            VectorShape(d: "M31 43 L46 49 L44 52.5 L34 50.5 Z M69 43 L54 49 L56 52.5 L66 50.5 Z M43 61 L57 61 L50 68 Z", box: CGSize(width: 100, height: 106))
                .fill(WColor.accent)
        }
        .aspectRatio(100.0 / 106.0, contentMode: .fit)
    }
}

/// The battery with a lightning bolt.
struct BatteryMark: View {
    var body: some View {
        GeometryReader { g in
            let s = g.size.width / 30
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 3 * s).stroke(WColor.accent, lineWidth: 2.4 * s)
                    .frame(width: 24 * s, height: 36 * s).offset(x: 3 * s, y: 6 * s)
                RoundedRectangle(cornerRadius: 1.5 * s).fill(WColor.accent)
                    .frame(width: 11 * s, height: 4.5 * s).offset(x: 9.5 * s, y: 1.5 * s)
                VectorShape(d: "M16.8,13 L10.5,25 L15,25 L13.2,34 L19.5,22 L15,22 Z", box: CGSize(width: 30, height: 44))
                    .fill(WColor.accent)
            }
        }
        .aspectRatio(30.0 / 44.0, contentMode: .fit)
    }
}

/// The map pin over a road, beside the range.
struct RangeMark: View {
    var body: some View {
        GeometryReader { g in
            let s = g.size.width / 36
            ZStack(alignment: .topLeading) {
                Ellipse().stroke(Color(hex: 0x8E939B), lineWidth: 1.6 * s).frame(width: 28 * s, height: 9 * s).offset(x: 4 * s, y: 24.5 * s)
                VectorShape(d: "M18,4c-5,0 -9,4 -9,9c0,6.5 9,16 9,16s9,-9.5 9,-16c0,-5 -4,-9 -9,-9z", box: CGSize(width: 36, height: 36))
                    .stroke(WColor.text, style: StrokeStyle(lineWidth: 2.2 * s, lineJoin: .round))
                Circle().stroke(WColor.text, lineWidth: 2.2 * s).frame(width: 6 * s, height: 6 * s).offset(x: 15 * s, y: 10 * s)
            }
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

struct BoltMark: View {
    var body: some View {
        VectorShape(d: "M13 2L4 14h7l-1 8 9-12h-7z", box: CGSize(width: 24, height: 24)).fill(WColor.accent)
            .aspectRatio(1, contentMode: .fit)
    }
}

/// A plug (two pins, the body and the cable).
struct PlugMark: View {
    var body: some View {
        GeometryReader { g in
            let s = g.size.width / 24
            Path { p in
                p.move(to: CGPoint(x: 9 * s, y: 2 * s)); p.addLine(to: CGPoint(x: 9 * s, y: 8 * s))
                p.move(to: CGPoint(x: 15 * s, y: 2 * s)); p.addLine(to: CGPoint(x: 15 * s, y: 8 * s))
                p.move(to: CGPoint(x: 6 * s, y: 8 * s)); p.addLine(to: CGPoint(x: 18 * s, y: 8 * s)); p.addLine(to: CGPoint(x: 18 * s, y: 11 * s))
                p.addArc(center: CGPoint(x: 12 * s, y: 11 * s), radius: 6 * s, startAngle: .degrees(0), endAngle: .degrees(180), clockwise: false)
                p.closeSubpath()
                p.move(to: CGPoint(x: 12 * s, y: 17 * s)); p.addLine(to: CGPoint(x: 12 * s, y: 22 * s))
            }
            .stroke(WColor.muted, style: StrokeStyle(lineWidth: 2 * s, lineCap: .round, lineJoin: .round))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

struct FanMark: View {
    let on: Bool
    var body: some View {
        GeometryReader { g in
            VectorShape(d: "M12 12c-2-3-1-7 2-8 1 3-1 5-2 8zM12 12c3-2 7-1 8 2-3 1-5-1-8-2zM12 12c2 3 1 7-2 8-1-3 1-5 2-8zM12 12c-3 2-7 1-8-2 3-1 5 1 8 2z", box: CGSize(width: 24, height: 24))
                .stroke(on ? WColor.orange : WColor.text, style: StrokeStyle(lineWidth: 1.8 * g.size.width / 24, lineCap: .round, lineJoin: .round))
        }
        .aspectRatio(1, contentMode: .fit)
    }
}

/// Two soft green waves along the bottom of the range card.
struct RangeWaves: View {
    var body: some View {
        let box = CGSize(width: 300, height: 80)
        ZStack {
            VectorShape(d: "M0,30 C55,6 115,10 165,34 C215,58 262,52 300,26 L300,80 L0,80 Z", box: box)
                .fill(LinearGradient(colors: [WColor.glow.opacity(0.24), WColor.glow.opacity(0.04)], startPoint: .top, endPoint: .bottom))
            VectorShape(d: "M0,56 C70,28 140,34 190,50 C235,64 268,58 300,40 L300,80 L0,80 Z", box: box)
                .fill(LinearGradient(colors: [WColor.accent.opacity(0.32), WColor.accent.opacity(0.08)], startPoint: .top, endPoint: .bottom))
            VectorShape(d: "M0,30 C55,6 115,10 165,34 C215,58 262,52 300,26", box: box)
                .stroke(Color(hex: 0xD4FF6E, alpha: 0.5), lineWidth: 1.4)
        }
    }
}
