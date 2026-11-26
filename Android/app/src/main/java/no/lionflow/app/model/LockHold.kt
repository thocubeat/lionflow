package no.lionflow.app.model

/**
 * Lock or unlock that the user just sent, shown until the car's own report catches up (Peugeot's server lags behind,
 * so the first status after a command can still have the old state).
 *
 * It is let go as soon as the car has reported its locks after the command was done, also when the car says
 * something else: what the car reports then is the truth. It used to be kept for 5 minutes unless the car reported
 * exactly the state that was asked for, which showed "Unlocked" while the car was locked: a car unlocked from the
 * app locks itself again after about 30 seconds when no door is opened, and the key can lock it too.
 */
data class LockHold(val want: Boolean, val since: Long, val until: Long = since + 300_000) {
    /** True while the app should still show [want] instead of what the status says. */
    fun holds(st: VehicleStatus, now: Long): Boolean {
        if (now > until || st.locked == want) return false
        val reported = reportedAt(st) ?: return true
        return reported <= since + SETTLE_MS
    }

    companion object {
        /** A report this long after the command can't be one from before it, even with the clocks a little apart. */
        const val SETTLE_MS = 15_000L

        /** When the car reported its locks: the doors' own time, else the time of the whole report. */
        fun reportedAt(st: VehicleStatus): Long? =
            VehicleStatus.parseIso(st.raw.optJSONObject("doorsState")?.optString("updatedAt")) ?: st.updatedAtMillis
    }
}
