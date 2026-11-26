package no.lionflow.app.work

import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.CommandResult
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.VehicleStatus
import java.util.Calendar

/**
 * LionFlow's own charge limit: when the car is charging and has reached the chosen %, send "stop charging"
 * (Peugeot's "delayed" charge = wait for the scheduled start time). The car can ignore that, so we only
 * trust a later status: if it is still charging 5 minutes after a try, we try again (3 tries), then say so.
 */
object ChargeLimit {
    @Volatile private var sending = false
    private const val MAX_TRIES = 3
    private const val RETRY_MS = 5 * 60_000L

    private data class State(val session: Long, val tries: Int, val lastTry: Long, val gaveUp: Boolean)

    private fun session(): Long = ChargeLog.active.value?.start ?: 0L

    private fun state(app: LionFlowApp): State {
        val p = app.settings.limitState.split(",")
        val st = State(p.getOrNull(0)?.toLongOrNull() ?: -1, p.getOrNull(1)?.toIntOrNull() ?: 0,
            p.getOrNull(2)?.toLongOrNull() ?: 0, p.getOrNull(3) == "1")
        // A new charging session starts from scratch (session 0 = unknown: keep, but let retries expire after 12 h).
        val sess = session()
        return if (st.session != sess || (sess == 0L && System.currentTimeMillis() - st.lastTry > 12 * 3_600_000L)) State(sess, 0, 0, false) else st
    }

    private fun save(app: LionFlowApp, s: State) {
        app.settings.limitState = "${s.session},${s.tries},${s.lastTry},${if (s.gaveUp) 1 else 0}"
    }

    private fun overLimit(app: LionFlowApp, st: VehicleStatus): Boolean {
        val s = app.settings.value
        if (!s.chargeLimitOn || !st.isCharging || !app.remote.isSetUp) return false
        val pct = st.batteryPct ?: return false
        // The car reports every few minutes, so stop a little early when charging fast:
        // add what it will gain before the next check (at most 3 %).
        val kw = no.lionflow.app.model.ChargePower.reading(st)?.kw ?: 0.0
        val gain = (kw * (5.0 / 60.0) / no.lionflow.app.model.ChargePower.usableKwh(st) * 100).coerceIn(0.0, 3.0)
        return pct + gain >= s.chargeLimitPct
    }

    /** True if something should be done now (a try, or telling the user it didn't work). */
    fun due(app: LionFlowApp, st: VehicleStatus): Boolean {
        if (!overLimit(app, st)) return false
        val s = state(app)
        if (s.gaveUp) return false
        return s.tries >= MAX_TRIES || System.currentTimeMillis() - s.lastTry > RETRY_MS
    }

    /**
     * Start time for the "delayed" charge. Uses the car's own scheduled time, unless that is missing or
     * within the next hour (then charging would just start again) — then it picks a time
     * 12 hours from now.
     */
    private fun delayTime(st: VehicleStatus): Pair<Int, Int> {
        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val h = st.nextDelayedHour; val m = st.nextDelayedMinute
        if (h != null && m != null && h in 0..23) {
            val until = ((h * 60 + m) - nowMin + 1440) % 1440
            if (until >= 60) return h to m
        }
        // A time in the past (e.g. "a minute ago") makes the car start charging right away, so pick
        // one well ahead. If charging starts again then, the limit stops it again.
        val t = (nowMin + 12 * 60) % 1440
        return (t / 60) to (t % 60)
    }

    private const val K_FORCE = "chargeForceState"   // "<what>,<time of last try>,<tries>"
    private const val K_STOPPED = "chargeLimitStopped" // "<% when stopped>,<time>,<restarts>"
    private const val K_RESTORE = "chargeLimitRestore" // "1" or "1,<time of last try>,<tries>": put the car back on its daily limit once unplugged

    /** After LionFlow stopped a forced charge: once the car is unplugged, put it back on its daily limit. Returns true when it acted. */
    suspend fun restoreWhenUnplugged(app: LionFlowApp, st: VehicleStatus): Boolean {
        val p = app.settings.raw(K_RESTORE).split(",")
        if (p.firstOrNull() != "1" || st.plugged || !app.remote.isSetUp || sending) return false
        if (st.chargeType == "Partial") { app.settings.setRaw(K_RESTORE, ""); return false }
        val last = p.getOrNull(1)?.toLongOrNull() ?: 0L
        val tries = p.getOrNull(2)?.toIntOrNull() ?: 0
        if (tries >= MAX_TRIES || System.currentTimeMillis() - last < RETRY_MS) return false
        sending = true
        try {
            val r = app.remote.chargeLimit(true)
            app.settings.setRaw(K_RESTORE, if (r is CommandResult.Failed) "1,${System.currentTimeMillis()},${tries + 1}" else "")
            app.remote.addLog("charge limit: unplugged, back to the car's daily limit → ${if (r is CommandResult.Failed) r.reason else "sent"}")
            return true
        } finally { sending = false }
    }

    /** Checks the car again 15, 45, 80 and 115 minutes after LionFlow stopped a charge (the re-check lasts 2 hours). */
    private fun verifyLater(app: LionFlowApp) {
        val wm = androidx.work.WorkManager.getInstance(app)
        listOf("limit_verify_1" to 15L, "limit_verify_2" to 45L, "limit_verify_3" to 80L, "limit_verify_4" to 115L).forEach { (name, min) ->
            wm.enqueueUniqueWork(name, androidx.work.ExistingWorkPolicy.REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<StatusWorker>().setInitialDelay(min, java.util.concurrent.TimeUnit.MINUTES)
                    .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
        }
    }

    /**
     * After LionFlow stopped a charge at the limit, the car sometimes reports a much lower level later (seen: 89 % at the
     * stop, 80 % in the morning, in both LionFlow and MyPeugeot). Then the stop came too early, or the level the car
     * reported while charging was off. While still plugged in, charge on to the limit again (at most twice). Returns a text when it acted.
     */
    suspend fun recheck(app: LionFlowApp, st: VehicleStatus): String? {
        val s = app.settings.value
        val p = app.settings.raw(K_STOPPED).split(",")
        val stoppedPct = p.getOrNull(0)?.toDoubleOrNull() ?: return null
        val at = p.getOrNull(1)?.toLongOrNull() ?: return null
        val restarts = p.getOrNull(2)?.toIntOrNull() ?: 0
        if (System.currentTimeMillis() - at > 2 * 3_600_000L || !s.chargeLimitOn || !app.remote.isSetUp || sending) { app.settings.setRaw(K_STOPPED, ""); return null }
        val pct = st.batteryPct ?: return null
        if (!st.plugged) { app.settings.setRaw(K_STOPPED, ""); return null }
        if (st.isCharging || pct >= s.chargeLimitPct - 3 || pct > stoppedPct - 3 || restarts >= 2) return null
        if (s.chargeWindowOn && !ChargeWindow.inside(ChargeWindow.minutesNow(), s.chargeWindowFrom, s.chargeWindowTo)) return null
        sending = true
        try {
            app.settings.setRaw(K_STOPPED, "$stoppedPct,$at,${restarts + 1}")
            if (s.chargeForce) app.remote.chargeLimit(false)
            val r = app.remote.charging(true, st.nextDelayedHour ?: 1, st.nextDelayedMinute ?: 0)
            app.remote.addLog("charge limit: car now reports ${pct.toInt()} % (was ${stoppedPct.toInt()} % at the stop) → charge on: ${if (r is CommandResult.Failed) r.reason else "sent"}")
            return if (r is CommandResult.Failed) null
                else "The car now reports ${pct.toInt()} % (it said ${stoppedPct.toInt()} % when charging stopped). Charging on to your ${s.chargeLimitPct} %."
        } finally { sending = false }
    }

    /**
     * "Charge past the car's limit": after LionFlow stops a charge it puts the car back on its daily limit
     * (safer if the phone is offline next time). So each new charge has to be switched to 100 % again, or the
     * car stops at its daily limit (e.g. 80 %) without LionFlow's limit ever being reached. This does that, and
     * restarts a charge the car already ended at its daily limit. Returns a short text when it acted.
     */
    suspend fun force(app: LionFlowApp, st: VehicleStatus): String? {
        val s = app.settings.value
        if (!s.chargeLimitOn || !s.chargeForce || !app.remote.isSetUp || sending) return null
        val pct = st.batteryPct ?: return null
        if (!st.plugged || pct >= s.chargeLimitPct - 1) { app.settings.setRaw(K_FORCE, ""); return null }
        val windowOk = !s.chargeWindowOn || ChargeWindow.inside(ChargeWindow.minutesNow(), s.chargeWindowFrom, s.chargeWindowTo)
        val what = when {
            st.chargeType != "Full" && (st.isCharging || st.chargingStatus == "Finished") -> "full"
            st.chargeType == "Full" && st.chargingStatus == "Finished" && !st.isCharging && windowOk -> "start"
            else -> return null
        }
        val p = app.settings.raw(K_FORCE).split(",")
        val tries = if (p.getOrNull(0) == what) p.getOrNull(2)?.toIntOrNull() ?: 0 else 0
        val last = if (p.getOrNull(0) == what) p.getOrNull(1)?.toLongOrNull() ?: 0L else 0L
        if (tries >= MAX_TRIES || System.currentTimeMillis() - last < RETRY_MS) return null
        sending = true
        try {
            app.settings.setRaw(K_FORCE, "$what,${System.currentTimeMillis()},${tries + 1}")
            if (what == "full") {
                val r = app.remote.chargeLimit(false)
                app.remote.addLog("charge past the car's limit: set the car to 100 % → ${if (r is CommandResult.Failed) r.reason else "sent"}")
                if (r is CommandResult.Failed) return null
                // A charge the car already ended at its daily limit also needs a new start.
                if (!st.isCharging && windowOk) {
                    val (h, m) = (st.nextDelayedHour ?: 1) to (st.nextDelayedMinute ?: 0)
                    val r2 = app.remote.charging(true, h, m)
                    app.remote.addLog("charge past the car's limit: start charging again → ${if (r2 is CommandResult.Failed) r2.reason else "sent"}")
                }
                return if (tries == 0) "The car was on its daily limit at ${pct.toInt()} %. Set it to charge on to your ${s.chargeLimitPct} %." else ""
            } else {
                val r = app.remote.charging(true, st.nextDelayedHour ?: 1, st.nextDelayedMinute ?: 0)
                app.remote.addLog("charge past the car's limit: start charging again at ${pct.toInt()} % → ${if (r is CommandResult.Failed) r.reason else "sent"}")
                return ""   // acted, nothing to tell
            }
        } finally { sending = false }
    }

    /** Returns a short text if it acted, else null. */
    suspend fun check(app: LionFlowApp, st: VehicleStatus): String? {
        if (sending || !due(app, st)) return null
        sending = true
        try {
            val s = state(app)
            val pct = st.batteryPct?.toInt()
            val limit = app.settings.value.chargeLimitPct
            if (s.tries >= MAX_TRIES) {
                save(app, s.copy(gaveUp = true))
                app.remote.addLog("charge limit $limit %: car still charging after $MAX_TRIES tries")
                return "The car kept charging (now $pct %) — it ignored the stop. Unplug or stop it in the car."
            }
            val (h, m) = delayTime(st)
            val r = app.remote.charging(false, h, m)
            val ok = r !is CommandResult.Failed
            save(app, s.copy(tries = s.tries + 1, lastTry = System.currentTimeMillis()))
            app.remote.addLog("charge limit $limit % → try ${s.tries + 1}: ${if (ok) "sent (delay to %02d:%02d)".format(h, m) else (r as CommandResult.Failed).reason}")
            if (ok) {
                app.settings.setRaw(K_STOPPED, "${st.batteryPct ?: 0.0},${System.currentTimeMillis()},0")
                verifyLater(app)
            }
            // Forced mode: the car stays on "charge to 100 %" until it's unplugged. Switching it back to its daily limit
            // right away makes it show (and keep) the level from before the charge, e.g. 80 % with 90 % in the battery.
            if (ok && app.settings.value.chargeForce) app.settings.setRaw(K_RESTORE, "1")
            return if (s.tries == 0) "Asking the car to stop charging at $pct % (your limit is $limit %)" else null
        } finally {
            sending = false
        }
    }
}
