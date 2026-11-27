import Foundation
import Combine
import WidgetKit
import BackgroundTasks
import UIKit

/// Keeps the home-screen widget up to date: each time the car's numbers (or the settings that change how they read)
/// change, the app writes what the widget shows to the shared folder and asks the widget to draw again.
@MainActor
final class WidgetBridge {
    private let model: AppModel
    private var watching: AnyCancellable?
    private var last: WidgetSnapshot?

    init(_ model: AppModel) {
        self.model = model
        last = WidgetShelf.read()
        watching = model.$ui.combineLatest(model.$prefs, model.$parkedAt)
            .debounce(for: .milliseconds(600), scheduler: RunLoop.main)
            .sink { [weak self] _ in self?.update() }
    }

    func update() {
        let snap = Self.snapshot(model)
        if snap == last { return }
        last = snap
        if WidgetShelf.write(snap) { WidgetCenter.shared.reloadAllTimelines() }
    }

    static func snapshot(_ model: AppModel) -> WidgetSnapshot {
        let ui = model.ui
        let s = model.prefs
        var w = WidgetSnapshot()
        w.name = tr(s.carName)
        w.batteryLabel = tr("Battery")
        w.rangeLabel = tr("Est. range")
        w.carLabel = tr("Car")
        w.outsideLabel = tr("Outside")
        w.noData = tr("No data yet")
        for (key, english) in WidgetSnapshot.english {
            let n = WidgetSnapshot.sampleNumber
            w.ago[key] = tr(english.replacingOccurrences(of: "{n}", with: n)).replacingOccurrences(of: n, with: "{n}")
        }
        switch ui.phase {
        case .signedOut, .loading:
            w.note = tr("Open LionFlow to sign in")
            return w
        default: break
        }
        w.signedIn = true
        guard let st = ui.status else {
            w.note = tr("Tap ↻ to load your car")
            return w
        }
        w.hasData = true
        w.fuel = st.energyType == "Fuel"
        w.charging = st.isCharging
        w.plugged = st.plugged
        w.precond = st.preconditioningOn
        w.battery = st.batteryPct
        w.batteryLabel = tr(w.fuel ? "Fuel" : "Battery")
        if let b = st.batteryPct, b >= 0 { w.batteryText = String(Int(b)) }
        if let r = st.rangeKm, r >= 0 {
            let shown = s.distanceUnit == "mi" ? r * 0.621371 : r
            w.rangeText = "≈" + String(Int(shown.rounded()))
            w.rangeUnit = " " + tr(s.distanceUnit == "mi" ? "mi" : "km")
        }
        let kw = ChargePower.label(st).map { " " + $0 } ?? ""
        if st.isCharging, let left = ChargePower.timeLeft(st), left.0 > 0 {
            let to = left.1.map { " to \($0) %" } ?? ""
            w.chargeLine = tr("Charging\(kw) · \(left.0 / 60) h \(left.0 % 60) min left\(to)")
        } else if st.isCharging {
            w.chargeLine = tr("Charging\(kw)")
        } else {
            w.chargeLine = tr(st.plugged ? "Plugged in · not charging" : "Not charging")
        }
        // The car doesn't report the temperature inside, so "Car" shows whether it is heating.
        w.cabinText = tr(st.preconditioningOn ? "Pre-heating" : "Not heating")
        w.outsideText = st.outsideTempC.map { fmtTemp($0, s) } ?? "—"
        if let p = model.parkedAt {
            w.place = p == "@Home" ? tr("Home") : (p.hasPrefix("@") ? String(p.dropFirst()) : p)
        }
        w.carTime = st.updatedAtMillis ?? ui.lastRefresh
        w.fetched = ui.lastRefresh
        return w
    }
}

/// Asks iOS to start the app in the background now and then (iOS decides when, often every hour or so) to read the
/// car, so the widget doesn't only change when the app is opened.
enum BackgroundRefresh {
    /// From Info.plist: SideStore and AltStore give the app a bundle id of their own and change this name to match.
    static let id = (Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String])?.first
        ?? "no.lionflow.app.refresh"

    static func register(_ model: AppModel, _ widgets: WidgetBridge) {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: id, using: nil) { task in
            schedule()
            let run = BackgroundRun(task)
            // Time up: tell iOS, but don't break off a request: if it is renewing the sign-in, the new key must
            // still be saved (the old one stops working on the server at once).
            task.expirationHandler = { run.finish(false) }
            Task { @MainActor in
                await model.refreshInBackground()
                widgets.update()
                run.finish(true)
            }
        }
    }

    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: id)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 20 * 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}

/** Tells iOS once that the background work is over. */
private final class BackgroundRun: @unchecked Sendable {
    private let task: BGTask
    private let lock = NSLock()
    private var done = false

    init(_ task: BGTask) { self.task = task }

    func finish(_ success: Bool) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { task.setTaskCompleted(success: success) }
    }
}
