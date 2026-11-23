package no.lionflow.app.work

import android.content.Context
import androidx.work.*
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.WeatherApi
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Program
import no.lionflow.app.model.VehicleStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Smart schedule: about an hour before a pre-heat is due, look at the forecast and switch that schedule
 * on or off in the car depending on the temperature limit. The car itself keeps running the schedule.
 */
object SmartSchedule {
    private const val K_DESIRED = "smartDesired"   // what the user wants (all schedules as they set them)
    private const val K_OFF = "smartOff"           // slots we have switched off because of the temperature
    private const val K_EVAL = "smartEval"         // slot -> occurrence already checked
    private const val K_WHY = "smartWhy"           // slot -> why it was switched off: {at, plug, temp}

    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    fun desired(app: LionFlowApp): List<Program>? = try {
        val raw = app.settings.raw(K_DESIRED)
        if (raw.isBlank()) null else JSONArray(raw).let { a ->
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> val d = o.getJSONArray("days")
                Program(o.getInt("slot"), (0 until d.length()).map { d.getString(it) }, o.getInt("hour"), o.getInt("minute"), o.getBoolean("on")) } }
        }
    } catch (_: Exception) { null }

    /** Remember what the user wants (called whenever they save schedules). Also forgets what we had switched off. */
    fun setDesired(app: LionFlowApp, list: List<Program>) {
        app.settings.setRaw(K_DESIRED, JSONArray(list.map { p -> JSONObject().put("slot", p.slot).put("days", JSONArray(p.days)).put("hour", p.hour).put("minute", p.minute).put("on", p.enabled) }).toString())
        app.settings.setRaw(K_OFF, ""); app.settings.setRaw(K_EVAL, ""); app.settings.setRaw(K_WHY, "")
    }

    /** Why a schedule was switched off: the time it was due, whether because the car wasn't plugged in, and the forecast. */
    data class Why(val at: Long, val notPlugged: Boolean, val temp: Int)

    fun why(app: LionFlowApp, slot: Int): Why? = try {
        JSONObject(app.settings.raw(K_WHY).ifBlank { "{}" }).optJSONObject(slot.toString())?.let { Why(it.getLong("at"), it.getBoolean("plug"), it.getInt("temp")) }
    } catch (_: Exception) { null }

    /** Pick up schedules that were added, changed or removed outside LionFlow (MyPeugeot, another phone) so they aren't hidden. */
    fun syncFromCar(app: LionFlowApp, car: List<Program>) {
        val want = desired(app) ?: return
        val off = switchedOff(app).toMutableSet()
        val byDesired = want.associateBy { it.slot }
        var changed = false
        val out = ArrayList<Program>()
        for (c in car) {
            val d = byDesired[c.slot]
            when {
                d == null -> { out.add(c); changed = true }
                d.days.sorted() != c.days.sorted() || d.hour != c.hour || d.minute != c.minute || (c.slot !in off && d.enabled != c.enabled) -> { out.add(c); off.remove(c.slot); changed = true }
                else -> out.add(d)
            }
        }
        if (want.any { w -> car.none { it.slot == w.slot } }) changed = true
        if (!changed) return
        setDesired(app, out)
        app.settings.setRaw(K_OFF, off.filter { s -> out.any { it.slot == s } }.joinToString(","))
    }

    /** Check again (for example after the limit changed). */
    fun recheck(app: LionFlowApp) { app.settings.setRaw(K_EVAL, "") }

    fun clear(app: LionFlowApp) { listOf(K_DESIRED, K_OFF, K_EVAL, K_WHY).forEach { app.settings.setRaw(it, "") } }

    fun switchedOff(app: LionFlowApp): Set<Int> = app.settings.raw(K_OFF).split(",").mapNotNull { it.toIntOrNull() }.toSet()

    /** What the car should actually be given: the user's schedules minus the ones switched off by the temperature. */
    fun effective(app: LionFlowApp, desired: List<Program>): List<Program> {
        val off = switchedOff(app)
        return desired.map { if (it.slot in off) it.copy(enabled = false) else it }
    }

    /** Next time this schedule fires, in millis, or null. */
    fun nextOccurrence(p: Program, now: Long): Long? {
        if (!p.enabled || p.days.isEmpty()) return null
        var best: Long? = null
        for (d in p.days) {
            val di = DAYS.indexOf(d); if (di < 0) continue
            val cal = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, p.hour); set(Calendar.MINUTE, p.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val today = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
            cal.add(Calendar.DAY_OF_YEAR, (di - today + 7) % 7)
            if (cal.timeInMillis < now) cal.add(Calendar.DAY_OF_YEAR, 7)
            if (best == null || cal.timeInMillis < best) best = cal.timeInMillis
        }
        return best
    }

    fun schedule(app: LionFlowApp) {
        val wm = WorkManager.getInstance(app)
        if (app.settings.value.smartMode.isEmpty() && !app.settings.value.smartPlugged) wm.cancelUniqueWork("smart-schedule")
        else wm.enqueueUniquePeriodicWork("smart-schedule", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SmartWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    /** One check. Returns a short message if something was changed in the car. */
    suspend fun run(app: LionFlowApp): String? {
        val s = app.settings.value
        if ((s.smartMode.isEmpty() && !s.smartPlugged) || !app.remote.isSetUp) return null
        val desired = desired(app) ?: return null
        val now = System.currentTimeMillis()
        val evaluated = try { JSONObject(app.settings.raw(K_EVAL).ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        // Schedules that are due within the next hour and haven't been checked for that occurrence yet.
        val due = desired.mapNotNull { p -> nextOccurrence(p, now)?.takeIf { it - now <= 65 * 60_000L && (s.smartPlugged || evaluated.optLong(p.slot.toString()) != it) }?.let { p to it } }
        if (due.isEmpty()) return null
        val vehicle = app.api.cachedVehicle() ?: return null
        val st = VehicleStatus.parse(app.api.status(vehicle), null)
        val lat = st.lat ?: return null; val lon = st.lon ?: return null
        val weather = WeatherApi.forecast(lat, lon)
        val off = switchedOff(app).toMutableSet()
        val why = try { JSONObject(app.settings.raw(K_WHY).ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
        var text: String? = null
        for ((p, at) in due) {
            val temp = weather.hours.minByOrNull { kotlin.math.abs(it.timeMillis - at) }?.tempC ?: weather.tempC
            val tempOk = when (s.smartMode) { "below" -> temp < s.smartTemp; "above" -> temp > s.smartTemp; else -> true }
            val ok = tempOk && (!s.smartPlugged || st.plugged)
            if (ok) { off.remove(p.slot); why.remove(p.slot.toString()) }
            else { off.add(p.slot); why.put(p.slot.toString(), JSONObject().put("at", at).put("plug", tempOk).put("temp", Math.round(temp).toInt())) }
            evaluated.put(p.slot.toString(), at)
            text = (if (ok) "Pre-heat stays on at " else if (tempOk) "Pre-heat skipped (not plugged in) at " else "Pre-heat skipped at ") + no.lionflow.app.ui.l10n.TimeFmt.hm(p.hour, p.minute) + " (" + Math.round(temp) + "°C)"
        }
        app.settings.setRaw(K_EVAL, evaluated.toString())
        app.settings.setRaw(K_WHY, why.toString())
        if (off == switchedOff(app)) return null
        app.settings.setRaw(K_OFF, off.joinToString(","))
        val send = effective(app, desired)
        app.remote.warmUp()
        val r = app.remote.preconditioning(st.preconditioningOn, VehicleStatus.programsJsonOf(send))
        if (r is no.lionflow.app.api.CommandResult.Failed) { app.settings.setRaw(K_EVAL, ""); return null }
        ChargePower.setPendingPrograms(send)
        return text
    }
}

class SmartWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LionFlowApp
        try { SmartSchedule.run(app)?.let { Waker.headsUp(app, "Smart schedule: $it") } } catch (_: Exception) { }
        return Result.success()
    }
}
