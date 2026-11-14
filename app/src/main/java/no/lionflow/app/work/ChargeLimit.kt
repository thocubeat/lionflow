package no.lionflow.app.work

import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.CommandResult
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.VehicleStatus

/**
 * LionFlow's own charge limit: when the car is charging and has reached the chosen %, send "stop charging"
 * (Peugeot's "delayed" charge = wait for the scheduled time). Runs from the app and the background check.
 */
object ChargeLimit {
    @Volatile private var sending = false

    /** True if the battery is at/over the limit while charging and we haven't stopped this session yet. */
    fun due(app: LionFlowApp, st: VehicleStatus): Boolean {
        val s = app.settings.value
        if (!s.chargeLimitOn || !st.isCharging || !app.remote.isSetUp) return false
        val pct = st.batteryPct ?: return false
        if (pct < s.chargeLimitPct) return false
        val session = ChargeLog.active.value?.start ?: 0L
        return session == 0L || app.settings.limitStoppedFor != session
    }

    /** Returns a short result text if it acted, else null. */
    suspend fun check(app: LionFlowApp, st: VehicleStatus): String? {
        if (sending || !due(app, st)) return null
        sending = true
        try {
            val r = app.remote.charging(false, st.nextDelayedHour ?: 1, st.nextDelayedMinute ?: 0)
            val ok = r !is CommandResult.Failed
            app.remote.addLog("charge limit ${app.settings.value.chargeLimitPct} % → ${if (ok) "stopped" else (r as CommandResult.Failed).reason}")
            if (ok) app.settings.limitStoppedFor = ChargeLog.active.value?.start ?: System.currentTimeMillis()
            return if (ok) "Stopped charging at ${st.batteryPct?.toInt()} % (your limit is ${app.settings.value.chargeLimitPct} %)" else null
        } finally {
            sending = false
        }
    }
}
