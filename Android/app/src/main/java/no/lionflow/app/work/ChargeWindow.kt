package no.lionflow.app.work

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.CommandResult
import no.lionflow.app.model.VehicleStatus
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * "Only charge between" two times. The car's own delayed start is set to the start of the window, so it begins
 * by itself. LionFlow stops charging when the window ends, and starts it if the car is plugged in during the
 * window but waiting (it would otherwise wait for the next day's start time).
 */
object ChargeWindow {
    private const val K_STATE = "chargeWindowState"   // "<what>,<time of last try>,<tries>"
    private const val RETRY_MS = 5 * 60_000L
    private const val MAX_TRIES = 3
    @Volatile private var sending = false

    fun minutesNow(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** True when [min] (minutes after midnight) is inside the window; a window over midnight is fine. */
    fun inside(min: Int, from: Int, to: Int): Boolean = when {
        from == to -> true
        from < to -> min in from until to
        else -> min >= from || min < to
    }

    /** Sets the car's delayed charge to start at the window's start (or back to normal when turned off). */
    suspend fun applyToCar(app: LionFlowApp): CommandResult? {
        if (!app.remote.isSetUp) return null
        val s = app.settings.value
        app.settings.setRaw(K_STATE, "")
        val r = if (s.chargeWindowOn) app.remote.charging(false, s.chargeWindowFrom / 60, s.chargeWindowFrom % 60)
            else app.remote.charging(true, s.chargeWindowFrom / 60, s.chargeWindowFrom % 60)
        app.remote.addLog("charging window ${if (s.chargeWindowOn) "on %02d:%02d–%02d:%02d".format(s.chargeWindowFrom / 60, s.chargeWindowFrom % 60, s.chargeWindowTo / 60, s.chargeWindowTo % 60) else "off"} → ${if (r is CommandResult.Failed) r.reason else "sent"}")
        schedule(app)
        return r
    }

    /** Wakes LionFlow just after the window opens and just after it closes, to check on the car. */
    fun schedule(app: LionFlowApp) {
        val wm = WorkManager.getInstance(app)
        val s = app.settings.value
        if (!s.chargeWindowOn) { wm.cancelUniqueWork("window_start"); wm.cancelUniqueWork("window_end"); return }
        val now = minutesNow()
        fun inMin(target: Int): Long = (((target - now) + 1440) % 1440).let { if (it == 0) 1440 else it }.toLong()
        listOf("window_start" to inMin(s.chargeWindowFrom) + 3, "window_end" to inMin(s.chargeWindowTo) + 1).forEach { (name, delay) ->
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<StatusWorker>().setInitialDelay(delay, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
    }

    /** Called on every background status check. Returns a short message when it acted, else null. */
    suspend fun check(app: LionFlowApp, st: VehicleStatus): String? {
        val s = app.settings.value
        if (!s.chargeWindowOn || !app.remote.isSetUp || sending) return null
        val now = minutesNow()
        val inWindow = inside(now, s.chargeWindowFrom, s.chargeWindowTo)
        val overLimit = s.chargeLimitOn && (st.batteryPct ?: 0.0) >= s.chargeLimitPct - 1
        val what = when {
            st.isCharging && !inWindow -> "stop"
            inWindow && st.plugged && !st.isCharging && st.chargingStatus != "Finished" && !overLimit && (st.batteryPct ?: 0.0) < 99 -> "start"
            else -> { app.settings.setRaw(K_STATE, ""); return null }
        }
        val p = app.settings.raw(K_STATE).split(",")
        val tries = if (p.getOrNull(0) == what) p.getOrNull(2)?.toIntOrNull() ?: 0 else 0
        val last = if (p.getOrNull(0) == what) p.getOrNull(1)?.toLongOrNull() ?: 0L else 0L
        if (tries >= MAX_TRIES || System.currentTimeMillis() - last < RETRY_MS) return null
        sending = true
        try {
            val h = s.chargeWindowFrom / 60; val m = s.chargeWindowFrom % 60
            val r = app.remote.charging(what == "start", h, m)
            app.settings.setRaw(K_STATE, "$what,${System.currentTimeMillis()},${tries + 1}")
            app.remote.addLog("charging window: $what (try ${tries + 1}) → ${if (r is CommandResult.Failed) r.reason else "sent"}")
            if (r is CommandResult.Failed) return if (tries + 1 >= MAX_TRIES) "Couldn't ${if (what == "stop") "stop" else "start"} charging: ${r.reason}" else null
            return if (tries > 0) null else if (what == "stop") "Charging stopped: outside your charging times. It starts again at ${no.lionflow.app.ui.l10n.TimeFmt.hm(h, m)}."
                else "Charging started: you plugged in during your charging times."
        } finally { sending = false }
    }
}
