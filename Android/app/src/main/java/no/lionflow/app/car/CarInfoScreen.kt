package no.lionflow.app.car

import android.graphics.Canvas
import androidx.car.app.CarContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.InfoModel
import no.lionflow.app.car.ui.InfoPaint
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.Tone
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtSpeed
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.l10n.tr
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Everything the car reports about itself, as a grid of small cards: battery and energy, health, charging,
 * the 12 V battery, odometer and service, temperature, doors, ignition and speed, privacy mode.
 * (The car's connected services share these values; live engine data from the OBD port is not available to apps.)
 */
internal class CarInfoScreen(ctx: CarContext, ui: CarUi) : DrawnScreen(ctx, ui) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { combine(CarHub.status, CarHub.message, CarHub.loading) { _, _, _ -> Unit }.collect { redraw() } }
                launch { while (true) { CarHub.refresh(carContext, force = true); CarHub.askCar(carContext); delay(10_000L) } }
                launch { CarHub.live.collect { redraw() } }
            }
        }
    }

    override fun buttons() = listOf(Btn(R.drawable.ic_car_refresh) { CarHub.refresh(carContext, force = true) })

    override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) {
        val (status, tone) = carStatusLine()
        val st = CarHub.status.value
        InfoPaint.draw(c, k, InfoModel(tr("Car Info"), status, tone, st?.let { cells(it) } ?: emptyList(), tr("Getting the car's status…")), pressed, hits)
    }

    override fun tapped(key: String) {}

    private fun path(o: JSONObject?, vararg keys: String): Any? {
        var cur: Any? = o
        for (k in keys) cur = (cur as? JSONObject)?.opt(k) ?: return null
        return cur
    }
    private fun num(o: JSONObject?, vararg keys: String): Double? = (path(o, *keys) as? Number)?.toDouble()
    private fun str(o: JSONObject?, vararg keys: String): String? = (path(o, *keys) as? String)?.takeIf { it.isNotBlank() }
    private fun one(v: Double) = String.format(Locale.US, "%.1f", v)

    /** The cards, most useful first (the last ones are left out on a screen too small for them all). */
    private fun cells(st: VehicleStatus): List<InfoModel.Cell> {
        val s = CarHub.app(carContext).settings.value
        val raw = st.raw
        val out = ArrayList<InfoModel.Cell>()
        fun add(label: String, value: String?, tone: Int = Tone.TEXT, bar: Double? = null) { if (!value.isNullOrBlank()) out += InfoModel.Cell(tr(label), value, tone, bar) }
        fun level(pct: Double) = when { pct < 10 -> Tone.BAD; pct < 20 -> Tone.WARN; else -> Tone.GOOD }
        val fuelOnly = st.energyType == "Fuel"
        if (!fuelOnly) {
            val live = CarHub.live.value
            val pct = live.pct ?: st.batteryPct
            add("Battery", pct?.let { "${it.roundToInt()} %" }, pct?.let { level(it) } ?: Tone.TEXT, pct)
            add("Range", (live.rangeKm ?: st.rangeKm)?.let { fmtDistance(it, s) })
            add("Energy in the battery", if (st.residualKwh != null && st.capacityKwh != null) "${one(st.residualKwh)} / ${one(st.capacityKwh)} kWh" else st.capacityKwh?.let { "${one(it)} kWh" })
            add("Battery health", st.batteryHealthPct?.let { "${it.roundToInt()} %" }, Tone.TEXT, st.batteryHealthPct)
        }
        if (st.fuelPct != null) {
            add("Fuel", "${st.fuelPct.roundToInt()} %", level(st.fuelPct), st.fuelPct)
            add("Fuel range", st.fuelRangeKm?.let { fmtDistance(it, s) })
        }
        if (!fuelOnly) {
            add("Charging", tr(when { st.isCharging -> "Charging"; st.chargingStatus == "Finished" -> "Charging complete"; st.plugged -> "Plugged in"; else -> "Not plugged in" }),
                if (st.isCharging || st.plugged) Tone.GOOD else Tone.TEXT)
            if (st.isCharging) {
                add("Charge speed", st.chargingRateKmh?.let { fmtDistance(it, s) + "/h" })
                add("Full in", st.remainingMinutes?.let { carDuration(it.toDouble()) })
                add("Charging mode", st.chargingMode?.let { tr(it) })
            }
            if (st.nextDelayedHour != null) add("Scheduled charging", String.format(Locale.US, "%02d:%02d", st.nextDelayedHour, st.nextDelayedMinute ?: 0))
        }
        add("Odometer", st.odometerKm?.let { fmtDistance(it, s) })
        add("Next service", listOfNotNull(st.kmToService?.let { fmtDistance(it, s) }, st.daysToService?.let { tr("{0} days").replace("{0}", "${it.roundToInt()}") }).joinToString(" · ").ifEmpty { null })
        add("Doors", CarHub.doorsLine(st), if (st.openDoors.isNotEmpty() || st.locked == false) Tone.WARN else Tone.TEXT)
        add("Outside", st.outsideTempC?.let { fmtTemp(it, s) })
        add("Pre-heating", tr(if (st.preconditioningOn) "On" else "Off"), if (st.preconditioningOn) Tone.WARN else Tone.TEXT)
        // The 12 V battery, when the car shares it (as volts, or as a level).
        val v12 = num(raw, "battery", "voltage")
        if (v12 != null) { if (v12 > 30) add("12 V battery", "${v12.roundToInt()} %", Tone.TEXT, v12) else add("12 V battery", "${one(v12)} V") }
        val speed = num(raw, "kinetic", "speed")
        add("Speed", if (st.moving || (speed ?: 0.0) > 0.5) fmtSpeed(speed ?: 0.0, s) else tr("Parked"))
        add("Ignition", st.ignition?.let { tr(it) })
        add("Privacy mode", str(raw, "privacy", "state")?.let { tr(it) })
        return out
    }
}
