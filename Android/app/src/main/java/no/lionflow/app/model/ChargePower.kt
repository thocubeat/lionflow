package no.lionflow.app.model

import android.content.Context
import android.content.SharedPreferences
import no.lionflow.app.data.SettingsStore
import org.json.JSONArray
import java.util.Locale

/**
 * Charging power in kW. The car only reports "km of range per hour", so LionFlow works it out:
 *  - measured: energy in the battery between two car reports (4–40 min apart) ÷ time between them
 *  - estimated (until there are two reports): energy still needed ÷ time left reported by the car
 */
object ChargePower {
    /** Minutes left and the % they lead to: to LionFlow's limit when one is set and still ahead, otherwise the car's own time to full. */
    fun timeLeft(st: VehicleStatus): Pair<Int, Int?>? {
        val rem = st.remainingMinutes ?: return null
        val s = settings?.value
        val soc = st.batteryPct
        if (s != null && s.chargeLimitOn && soc != null && soc < s.chargeLimitPct && soc < 100)
            return Pair((rem * (s.chargeLimitPct - soc) / (100 - soc)).toInt().coerceAtLeast(1), s.chargeLimitPct)
        return Pair(rem, null)
    }
    fun leftText(st: VehicleStatus): String? = timeLeft(st)?.let { (m, pct) ->
        " · ${m / 60} h ${m % 60} min left" + (pct?.let { " to $it %" } ?: "")
    }

    data class Reading(val kw: Double, val measured: Boolean)

    private var prefs: SharedPreferences? = null
    private var settings: SettingsStore? = null

    private var vin = ""
    private fun k(name: String) = if (vin.isEmpty()) name else "${name}_$vin"

    fun init(ctx: Context, s: SettingsStore, currentVin: String?) {
        prefs = ctx.getSharedPreferences("charge_samples", Context.MODE_PRIVATE)
        settings = s
        vin = currentVin.orEmpty()
        val p = prefs!!
        if (vin.isNotEmpty() && !p.contains(k("kmPerPct")) && p.contains("kmPerPct")) p.edit().putFloat(k("kmPerPct"), p.getFloat(k("kmPerPct"), 0f)).remove("kmPerPct").apply()
    }

    fun setVin(v: String) { vin = v }
    internal fun store(): SharedPreferences? = prefs
    internal fun key(name: String) = k(name)

    /**
     * Usable battery size: the user's setting, else what the car reports (or works out from energy left and %),
     * else a default for the car type (plug-in hybrids ~11.5 kWh, electric E-5008 73 kWh).
     */
    fun usableKwh(st: VehicleStatus): Double =
        settings?.value?.batteryKwh?.takeIf { it > 0 }
            ?: st.capacityKwh?.takeIf { it > 1 }
            ?: st.residualKwh?.let { r -> st.batteryPct?.takeIf { it >= 10 }?.let { r / (it / 100.0) } }?.takeIf { it in 5.0..150.0 }
            ?: if (st.energyType == "Hybrid") 11.5 else 73.0

    private fun energyIn(st: VehicleStatus): Double? =
        st.residualKwh ?: st.batteryPct?.let { it / 100.0 * usableKwh(st) }

    /**
     * Call on every fresh car report. Fixes a missing/0 range and remembers charging samples.
     * Returns the status to use.
     */
    /** The last car reports, one line each, for the log (to see how the battery level moved). */
    /**
     * The car reports its scheduled start time even when it is set to charge right away (the API has no on/off for it),
     * so LionFlow remembers what it last sent. Null when there is no scheduled start, or LionFlow turned it off.
     */
    fun scheduledStart(st: VehicleStatus): Pair<Int, Int>? {
        val h = st.nextDelayedHour ?: return null
        if (prefs?.getString(k("startMode"), "") == "immediate") return null
        return h to (st.nextDelayedMinute ?: 0)
    }

    fun rememberStartMode(immediate: Boolean) {
        prefs?.edit()?.putString(k("startMode"), if (immediate) "immediate" else "delayed")?.apply()
    }

    fun history(): List<String> = prefs?.getString(k("hist"), "")?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    private fun remember(p: android.content.SharedPreferences, st: VehicleStatus, carLevel: Double? = st.batteryPct) {
        val t = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US).format(java.util.Date(st.updatedAtMillis ?: System.currentTimeMillis()))
        val car = if (carLevel != null && carLevel.toInt() != st.batteryPct?.toInt()) " (car says ${carLevel.toInt()})" else ""
        val line = "$t level=${st.batteryPct?.toInt()}$car residual=${st.residualKwh?.let { String.format(java.util.Locale.US, "%.1f", it) }} " +
            "status=${st.chargingStatus} type=${st.chargeType} plugged=${st.plugged} preheat=${st.preconditioningOn}"
        val old = history()
        if (old.firstOrNull()?.substringAfter(" ")?.substringAfter(" ") == line.substringAfter(" ").substringAfter(" ")) return
        p.edit().putString(k("hist"), (listOf(line) + old).take(30).joinToString("\n")).apply()
    }

    /**
     * An electric car can suddenly report a much lower level with the same energy in the battery: an E-5008 went from
     * 89 % to 79 % the moment its daily charge limit was switched back on (68.5 kWh both times), and then counted down
     * slowly from the old number. Only when exactly that is seen (level drops 5 points or more between two reports while
     * the energy stays put), the level from before the drop is kept and moved by the change in energy, until the car's
     * own number has caught up. The energy figure alone is never used: on other cars it doesn't match the level.
     */
    private fun fixLevel(st: VehicleStatus): VehicleStatus {
        val p = prefs ?: return st
        val level = st.batteryPct; val res = st.residualKwh; val cap = st.capacityKwh
        if (st.energyType != "Electric" || level == null || res == null || cap == null || cap < 30) return st
        val now = System.currentTimeMillis()
        val last = p.getString(k("lvl"), "")!!.split(",").mapNotNull { it.toDoubleOrNull() }
        var anchor = p.getString(k("lvlAnchor"), "")!!.split(",").mapNotNull { it.toDoubleOrNull() }
        if (last.size == 2 && last[0] - level >= 5 && kotlin.math.abs(last[1] - res) < 1.0) anchor = listOf(last[0], last[1], now.toDouble())
        var out = st
        if (anchor.size == 3) {
            val est = anchor[0] + (res - anchor[1]) / cap * 100
            // Never while plugged in: the car's own level decides where it stops charging, and showing more than the
            // car says made a car that stopped at its 80 % daily limit look like it went on to 89 %.
            if (st.plugged || now - anchor[2] > 86_400_000L || est - level <= 2 || est > 100.5) anchor = emptyList()
            else out = st.copy(batteryPct = Math.round(est).toDouble())
        }
        p.edit().putString(k("lvl"), "$level,$res").putString(k("lvlAnchor"), anchor.joinToString(",")).apply()
        return out
    }

    fun record(raw: VehicleStatus): VehicleStatus {
        val st = applyPendingPrograms(fixRange(fixLevel(raw)))
        try { ChargeLog.observe(st) } catch (_: Exception) { }
        try { CarTrips.observe(st) } catch (_: Exception) { }
        val p = prefs ?: return st
        remember(p, st, raw.batteryPct)
        if (!st.isCharging) { p.edit().remove(k("s")).apply(); return st }
        val t = st.energyAtMillis ?: st.updatedAtMillis ?: return st
        val e = energyIn(st) ?: return st
        val arr = load()
        if (arr.isNotEmpty() && arr.last().first == t) return st
        val keep = (arr + (t to e)).filter { t - it.first <= 45 * 60_000L }.takeLast(12)
        p.edit().putString(k("s"), JSONArray(keep.map { JSONArray().put(it.first).put(it.second) }).toString()).apply()
        return st
    }

    // ---------------- pre-heating schedules just sent ----------------

    private fun key(p: Program) = listOf(p.days.sorted().joinToString(","), p.hour, p.minute, p.enabled).joinToString("|")

    /** Remember schedules sent to the car; Peugeot's status can show the old ones for a while. */
    fun setPendingPrograms(list: List<Program>) {
        settings?.pendingPrograms = org.json.JSONObject().put("time", System.currentTimeMillis())
            .put("programs", JSONArray(list.map { p ->
                org.json.JSONObject().put("slot", p.slot).put("days", JSONArray(p.days)).put("hour", p.hour).put("minute", p.minute).put("on", p.enabled)
            })).toString()
    }

    fun pendingPrograms(): List<Program>? = try {
        val raw = settings?.pendingPrograms.orEmpty()
        if (raw.isBlank()) null else {
            val j = org.json.JSONObject(raw)
            if (System.currentTimeMillis() - j.getLong("time") > 2 * 3_600_000L) { settings?.pendingPrograms = ""; null }
            else {
                val a = j.getJSONArray("programs")
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i); val d = o.getJSONArray("days")
                    Program(o.getInt("slot"), (0 until d.length()).map { d.getString(it) }, o.getInt("hour"), o.getInt("minute"), o.getBoolean("on"))
                }
            }
        }
    } catch (_: Exception) { null }

    /** Until the car's status shows the new schedules, use the ones we sent (so they don't vanish or get overwritten). */
    private fun applyPendingPrograms(st: VehicleStatus): VehicleStatus {
        val pend = pendingPrograms() ?: return st
        val car = st.programs.map { key(it) }.toSet()
        val want = pend.map { key(it) }.toSet()
        if (car == want) { settings?.pendingPrograms = ""; return st }
        // The car's list is still the old one for a while. If it changes to something that is neither the old nor the new list,
        // someone else (another phone, MyPeugeot) edited the schedules: show the car's list instead of ours.
        val raw = org.json.JSONObject(settings?.pendingPrograms.orEmpty())
        val carKey = car.sorted().joinToString(";")
        val base = raw.optString("base", "")
        if (base.isEmpty()) { raw.put("base", carKey); settings?.pendingPrograms = raw.toString() }
        else if (base != carKey) { settings?.pendingPrograms = ""; return st }
        return st.copy(programs = pend)
    }

    /**
     * Peugeot's API sometimes reports 0 km range (often when plugged in or asleep) even with a full battery.
     * Then we use km-per-% learned from the last real reading, or a plain estimate (usable kWh at ~19 kWh/100 km).
     */
    private fun fixRange(st: VehicleStatus): VehicleStatus {
        if (st.energyType == "Fuel") return st
        val p = prefs
        val pct = st.batteryPct ?: return st
        val range = st.rangeKm
        if (range != null && range > 0 && pct >= 5) {
            val perPct = range / pct
            if (perPct in 1.0..10.0) p?.edit()?.putFloat(k("kmPerPct"), perPct.toFloat())?.apply()
            return st
        }
        if (range != null && range > 0) return st
        if (pct < 1) return st
        val learned = p?.getFloat(k("kmPerPct"), 0f)?.toDouble()?.takeIf { it > 0 }
        val perPct = learned ?: (usableKwh(st) / 19.0 * 100.0 / 100.0)
        return st.copy(rangeKm = Math.round(pct * perPct).toDouble(), rangeEstimated = true)
    }

    private fun load(): List<Pair<Long, Double>> = try {
        val a = JSONArray(prefs?.getString(k("s"), "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONArray(it).let { x -> x.getLong(0) to x.getDouble(1) } }
    } catch (_: Exception) { emptyList() }

    fun reading(st: VehicleStatus?): Reading? {
        if (st == null || !st.isCharging) return null
        val max = if (st.chargingMode == "Quick") 250.0 else 23.0
        val samples = load()
        val newest = samples.lastOrNull()
        if (newest != null) {
            val base = samples.lastOrNull { newest.first - it.first in 4 * 60_000L..40 * 60_000L }
            if (base != null) {
                val kw = (newest.second - base.second) / ((newest.first - base.first) / 3_600_000.0)
                if (kw in 0.5..max) return Reading(kw, true)
            }
        }
        val rem = st.remainingMinutes?.takeIf { it > 0 } ?: return null
        val need = if (st.residualKwh != null && st.capacityKwh != null) st.capacityKwh - st.residualKwh
        else st.batteryPct?.let { usableKwh(st) * (100 - it) / 100.0 } ?: return null
        val kw = need / (rem / 60.0)
        return if (kw in 0.5..max) Reading(kw, false) else null
    }

    fun format(r: Reading): String =
        String.format(Locale.US, if (r.kw < 10) "%.1f kW" else "%.0f kW", r.kw) + if (r.measured) "" else " (est.)"

    fun label(st: VehicleStatus?): String? = reading(st)?.let { format(it) }
}
