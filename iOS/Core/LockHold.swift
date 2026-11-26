import Foundation

/// Lock or unlock that the user just sent, shown until the car's own report catches up (Peugeot's server lags behind,
/// so the first status after a command can still have the old state). The same as the Android app's LockHold.
///
/// It is let go as soon as the car has reported its locks after the command was done, also when the car says
/// something else: what the car reports then is the truth. It used to be kept for 5 minutes unless the car reported
/// exactly the state that was asked for, which showed "Unlocked" while the car was locked: a car unlocked from the
/// app locks itself again after about 30 seconds when no door is opened, and the key can lock it too.
struct LockHold: Equatable {
    let want: Bool
    let since: Int64
    var until: Int64

    init(want: Bool, since: Int64, until: Int64? = nil) {
        self.want = want
        self.since = since
        self.until = until ?? since + 300_000
    }

    /** A report this long after the command can't be one from before it, even with the clocks a little apart. */
    static let settleMs: Int64 = 15_000

    /** True while the app should still show `want` instead of what the status says. */
    func holds(_ st: VehicleStatus, now: Int64) -> Bool {
        if now > until || st.locked == want { return false }
        guard let reported = Self.reportedAt(st) else { return true }
        return reported <= since + Self.settleMs
    }

    /** When the car reported its locks: the doors' own time, else the time of the whole report. */
    static func reportedAt(_ st: VehicleStatus) -> Int64? {
        VehicleStatus.parseIso(st.raw.optJSONObject("doorsState")?.optString("updatedAt")) ?? st.updatedAtMillis
    }
}
