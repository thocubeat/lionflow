import SwiftUI
import WatchConnectivity

/// LionFlow on the Apple Watch: battery and range, where the car is, and lock, lights and pre-heating.
/// The watch has no sign-in of its own. It asks LionFlow on the iPhone, which answers and sends the commands.
@main
struct LionFlowWatchApp: App {
    @StateObject private var phone = PhoneLink()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            WatchScreen(model: phone.model)
                .onChange(of: scenePhase) { phase in
                    if phase == .active { phone.refreshWhenReady() }
                }
        }
    }
}

/** The connection to the iPhone. */
@MainActor
final class PhoneLink: NSObject, ObservableObject, WCSessionDelegate {
    let model: WatchModel
    private let preview: Bool

    override init() {
        model = WatchModel()
        preview = ProcessInfo.processInfo.arguments.contains("-preview")
        super.init()
        if preview {
            showSample()
            return
        }
        model.send = { [weak self] path, payload in self?.send(path, payload) ?? false }
        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }
    }

    /** Asks the phone for the car's status, once the connection is up. */
    func refreshWhenReady() {
        if preview || !WCSession.isSupported() { return }
        if WCSession.default.activationState == .activated && !model.busy { model.refresh() }
    }

    private func send(_ path: String, _ payload: String) -> Bool {
        let session = WCSession.default
        guard session.activationState == .activated, session.isReachable else { return false }
        session.sendMessage(["path": path, "data": payload], replyHandler: nil) { [weak self] error in
            NSLog("LionFlow watch: the question did not reach the phone (%@)", error.localizedDescription)
            Task { @MainActor in self?.model.sendFailed() }
        }
        return true
    }

    // What is written to the system's log here is only about the link to the phone, never about the car.
    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        NSLog("LionFlow watch: link to the phone is %@, phone within reach: %@%@", activationState == .activated ? "up" : "not up",
              session.isReachable ? "yes" : "no", error.map { " (" + $0.localizedDescription + ")" } ?? "")
        Task { @MainActor in self.model.refresh() }
    }

    /** The phone came within reach: ask again if the watch is still waiting for its first answer. */
    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        NSLog("LionFlow watch: phone within reach: %@", session.isReachable ? "yes" : "no")
        guard session.isReachable else { return }
        Task { @MainActor in
            if self.model.state == nil && !self.model.busy { self.model.refresh() }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        guard let path = message["path"] as? String, let data = message["data"] as? String else { return }
        NSLog("LionFlow watch: an answer from the phone (%@)", path)
        Task { @MainActor in self.model.receive(path, data) }
    }

    /// For pictures of the watch app without a phone (started with "-preview"): a sample car.
    private func showSample() {
        let fuel = ProcessInfo.processInfo.arguments.contains("-fuel")
        let sample: [String: Any] = [
            "name": fuel ? "My Opel" : "My Peugeot", "cars": 2, "battery": fuel ? "58 %" : "64 %", "range": fuel ? "≈ 520 km" : "≈ 312 km",
            "fuel": fuel, "locked": true, "status": fuel ? "" : "Charging at 7.4 kW", "place": "Home", "today": "42 km", "yesterday": "18 km",
            "updated": "2 min ago", "hide": [String](),
            "extras": [["label": "Doors", "value": "Locked"], ["label": "Odometer", "value": "13,412 km"]],
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: sample), let text = String(data: data, encoding: .utf8) else { return }
        model.receive(WatchWire.statusReply, text)
    }
}

private let lime = Color(red: 0xC6 / 255.0, green: 0xF3 / 255.0, blue: 0x6B / 255.0)
private let ink = Color(red: 0x10 / 255.0, green: 0x11 / 255.0, blue: 0x14 / 255.0)
private let soft = Color(red: 0xB8 / 255.0, green: 0xBC / 255.0, blue: 0xC4 / 255.0)
private let grey = Color(red: 0x9A / 255.0, green: 0x9E / 255.0, blue: 0xA6 / 255.0)

private func t(_ english: String) -> String { WatchText.t(english) }

/** One pending question: "Unlock the car?". */
private struct Ask: Identifiable {
    let cmd: String
    var id: String { cmd }
    var question: String {
        switch cmd {
        case "unlock": return "Unlock the car?"
        case "lock": return "Lock the car?"
        case "lights": return "Flash the lights?"
        default: return "Start pre-heating?"
        }
    }
}

struct WatchScreen: View {
    @ObservedObject var model: WatchModel
    @State private var ask: Ask? = nil

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                Text(model.state?.name ?? "LionFlow").font(.headline).fontWeight(.bold).foregroundColor(lime)
                    .multilineTextAlignment(.center)
                if (model.state?.cars ?? 1) > 1 {
                    Button("⇄ " + t("Switch car")) { model.nextCar() }.disabled(model.busy)
                }
                if let car = model.state {
                    CarRows(car: car, model: model, ask: $ask)
                } else {
                    Text(model.noPhone ? t("Open LionFlow on your phone. The watch gets its data from there.") : (model.message ?? t("Getting car status…")))
                        .multilineTextAlignment(.center).padding(.horizontal, 6).padding(.vertical, 8)
                    Button(t("Refresh")) { model.refresh() }
                }
                if model.busy {
                    Text(t("Sending to the car…")).font(.system(size: 12)).foregroundColor(grey)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 4)
        }
        .alert(item: $ask) { a in
            Alert(title: Text(t(a.question)),
                  primaryButton: .default(Text(t("Yes"))) { model.command(a.cmd) },
                  secondaryButton: .cancel(Text(t("No"))))
        }
    }
}

/** The rows for a car: level and range, distance, the actions, where it is, and what else was chosen on the phone. */
private struct CarRows: View {
    let car: WatchCar
    @ObservedObject var model: WatchModel
    @Binding var ask: Ask?

    private var locked: Bool { car.locked != false }

    var body: some View {
        VStack(spacing: 8) {
            VStack(spacing: 0) {
                Text(car.battery).font(.system(size: 40, weight: .bold))
                Text("\(t(car.fuel ? "Fuel" : "Battery")) · \(car.range)").font(.system(size: 14)).foregroundColor(soft)
                if !car.status.trimmingCharacters(in: .whitespaces).isEmpty {
                    Text(car.status).font(.system(size: 12)).foregroundColor(grey).multilineTextAlignment(.center)
                }
            }
            if !car.hide.contains("distance") {
                HStack {
                    Distance(value: car.today, label: t("Today"))
                    Distance(value: car.yesterday, label: t("Yesterday"))
                }
                .padding(.vertical, 4)
            }
            if let m = model.message {
                Text(m).font(.system(size: 12)).foregroundColor(lime).multilineTextAlignment(.center).padding(.horizontal, 6)
            }
            Actions(car: car, locked: locked, busy: model.busy, ask: $ask)
            if !car.hide.contains("place") {
                InfoRow(label: t("Car is at"), value: car.place.trimmingCharacters(in: .whitespaces).isEmpty ? "—" : car.place, lines: 3)
            }
            ForEach(Array(car.extras.enumerated()), id: \.offset) { row in
                InfoRow(label: row.element.label, value: row.element.value, lines: 2)
            }
            Text(WatchText.updated(car.updated)).font(.system(size: 11)).foregroundColor(grey)
            Button(t("Refresh")) { model.refresh() }.disabled(model.busy)
        }
    }
}

private struct Actions: View {
    let car: WatchCar
    let locked: Bool
    let busy: Bool
    @Binding var ask: Ask?

    var body: some View {
        VStack(spacing: 8) {
            if !car.hide.contains("lock") {
                Button { ask = Ask(cmd: locked ? "unlock" : "lock") } label: {
                    Text(t(locked ? "Unlock" : "Lock")).fontWeight(.semibold).foregroundColor(ink).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent).tint(lime).disabled(busy)
            }
            if !car.hide.contains("flash") {
                Button(t("Flash lights")) { ask = Ask(cmd: "lights") }.disabled(busy)
            }
            if !car.fuel && !car.hide.contains("preheat") {
                Button(t("Pre-heat")) { ask = Ask(cmd: "preheat") }.disabled(busy)
            }
        }
    }
}

private struct Distance: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 0) {
            Text(value).fontWeight(.semibold)
            Text(label).font(.system(size: 11)).foregroundColor(grey)
        }
        .frame(maxWidth: .infinity)
    }
}

/** A row that only shows something: a small label over its value. */
private struct InfoRow: View {
    let label: String
    let value: String
    let lines: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.system(size: 11)).foregroundColor(soft)
            Text(value).font(.system(size: 15)).lineLimit(lines).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Color.white.opacity(0.12)))
    }
}
