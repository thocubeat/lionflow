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
    data class Reading(val kw: Double, val measured: Boolean)

    private var prefs: SharedPreferences? = null
    private var settings: SettingsStore? = null

    fun init(ctx: Context, s: SettingsStore) {
        prefs = ctx.getSharedPreferences("charge_samples", Context.MODE_PRIVATE)
        settings = s
    }

    /** Usable battery size: what the car reports, else the user's setting, else E-5008's 73 kWh. */
    fun usableKwh(st: VehicleStatus): Double =
        st.capacityKwh ?: settings?.value?.batteryKwh?.takeIf { it > 0 } ?: 73.0

    private fun energyIn(st: VehicleStatus): Double? =
        st.residualKwh ?: st.batteryPct?.let { it / 100.0 * usableKwh(st) }

    /** Remember this report while charging; forget the session when charging stops. */
    fun record(st: VehicleStatus) {
        val p = prefs ?: return
        if (!st.isCharging) { p.edit().remove("s").apply(); return }
        val t = st.energyAtMillis ?: st.updatedAtMillis ?: return
        val e = energyIn(st) ?: return
        val arr = load()
        if (arr.isNotEmpty() && arr.last().first == t) return
        val keep = (arr + (t to e)).filter { t - it.first <= 45 * 60_000L }.takeLast(12)
        p.edit().putString("s", JSONArray(keep.map { JSONArray().put(it.first).put(it.second) }).toString()).apply()
    }

    private fun load(): List<Pair<Long, Double>> = try {
        val a = JSONArray(prefs?.getString("s", "[]") ?: "[]")
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
