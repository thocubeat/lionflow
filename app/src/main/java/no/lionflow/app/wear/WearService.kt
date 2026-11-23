package no.lionflow.app.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.CommandResult
import no.lionflow.app.api.Http
import no.lionflow.app.api.Tracker
import no.lionflow.app.api.objects
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.tr
import no.lionflow.app.ui.minutesAgo
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Answers LionFlow on the watch. The watch has no Peugeot sign-in of its own: it asks the phone,
 * and Wear OS only delivers these messages between apps with the same package and signing key.
 */
class WearService : WearableListenerService() {

    private val app get() = applicationContext as LionFlowApp

    override fun onMessageReceived(e: MessageEvent) {
        // Runs on a background thread; the watch waits for the answer.
        runBlocking {
            when (e.path) {
                "/lionflow/status" -> reply(e.sourceNodeId, "/lionflow/status/reply", status())
                "/lionflow/cmd" -> {
                    val cmd = String(e.data)
                    if (cmd == "nextcar") {
                        no.lionflow.app.model.Garage.next(app)?.let { no.lionflow.app.model.Garage.activate(app, it) }
                        reply(e.sourceNodeId, "/lionflow/status/reply", status())
                    } else command(e.sourceNodeId, cmd)
                }
            }
        }
    }

    private suspend fun reply(node: String, path: String, j: JSONObject) {
        try { Wearable.getMessageClient(this).sendMessage(node, path, j.toString().toByteArray()).await() } catch (_: Exception) { }
    }

    private suspend fun status(): JSONObject {
        val app = app
        if (!app.api.isSignedIn) return JSONObject().put("error", tr("Open LionFlow to sign in"))
        return try {
            val v = app.api.currentVehicle() ?: return JSONObject().put("error", tr("No car on this account"))
            val st = ChargePower.record(app.api.readStatus(v))
            no.lionflow.app.widget.WidgetData.save(applicationContext, st)
            val s = app.settings.value
            val (today, yesterday) = try { distances(v) } catch (_: Exception) { null to null }
            JSONObject()
                .put("name", s.carName)
                .put("cars", no.lionflow.app.model.Garage.entries(app).size)
                .put("battery", st.batteryPct?.let { "${it.toInt()} %" } ?: "—")
                .put("range", "≈ " + fmtDistance(st.rangeKm, s) + if (st.rangeEstimated) " " + tr("(est.)").trim() else "")
                .put("fuel", st.energyType == "Fuel")
                .put("locked", st.locked ?: JSONObject.NULL)
                .put("status", tr(when {
                    st.isCharging -> "Charging" + (ChargePower.label(st)?.let { " at $it" } ?: "")
                    st.preconditioningOn -> "Pre-heating is on"
                    st.plugged -> "Plugged in"
                    else -> ""
                }))
                .put("place", place(st))
                .put("today", today?.let { fmtDistance(it, s) } ?: "—")
                .put("yesterday", yesterday?.let { fmtDistance(it, s) } ?: "—")
                .put("updated", tr(minutesAgo(st.updatedAtMillis ?: System.currentTimeMillis(), System.currentTimeMillis())))
        } catch (ex: Exception) {
            JSONObject().put("error", tr(Http.describe(ex)))
        }
    }

    /** Where the car is: a saved place ("Parked at Home") or the street address. */
    private suspend fun place(st: VehicleStatus): String {
        val lat = st.lat ?: return ""
        val lon = st.lon ?: return ""
        if (st.moving) return tr("Driving")
        val cfg = ChargeLog.places.value
        cfg.home?.let { h -> if (h.lat != null && h.lon != null && ChargeLog.distanceM(lat, lon, h.lat, h.lon) <= 50) return tr("Home") }
        cfg.known.forEach { k -> if (k.lat != null && k.lon != null && ChargeLog.distanceM(lat, lon, k.lat, k.lon) <= 75) return k.name }
        return try { ChargeLog.reverseGeocode(lat, lon) } catch (_: Exception) { null } ?: String.format(Locale.US, "%.5f, %.5f", lat, lon)
    }

    /** Kilometres driven today and yesterday (local time). */
    private suspend fun distances(v: no.lionflow.app.api.Vehicle): Pair<Double, Double> {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val todayStart = cal.timeInMillis
        val yStart = todayStart - 86_400_000L
        val trips = app.api.allTrips(v, yStart).trips.map { Trip.parse(it) }
        val uniq = trips.distinctBy { it.id }
        val today = uniq.filter { (it.startMillis ?: 0L) >= todayStart }.sumOf { it.distanceKm }
        val yesterday = uniq.filter { (it.startMillis ?: 0L) in yStart until todayStart }.sumOf { it.distanceKm }
        return today to yesterday
    }

    private suspend fun command(node: String, cmd: String) {
        val app = app
        if (!app.api.isSignedIn || !app.remote.isSetUp) {
            reply(node, "/lionflow/cmd/reply", JSONObject().put("text", tr("Set up remote control in LionFlow on your phone first"))); return
        }
        val label = when (cmd) { "unlock" -> "Unlock"; "lock" -> "Lock"; "lights" -> "Flash lights"; "preheat" -> "Pre-heating"; else -> return }
        val t = Tracker(
            onProgress = { p -> runBlocking { reply(node, "/lionflow/cmd/reply", JSONObject().put("text", tr("$label — $p")).put("final", false)) } },
            onFinal = { },
        )
        val r = withTimeoutOrNull(90_000) {
            try {
                when (cmd) {
                    "unlock" -> app.remote.lock(false, t)
                    "lock" -> app.remote.lock(true, t)
                    "lights" -> app.remote.lights(t)
                    else -> {
                        val st = try { app.api.currentVehicle()?.let { app.api.readStatus(it) } } catch (_: Exception) { null }
                        app.remote.preconditioning(true, st?.programsJson() ?: JSONObject(), t)
                    }
                }
            } catch (ex: Exception) { CommandResult.Failed(Http.describe(ex)) }
        } ?: CommandResult.Failed("No answer from the car yet — it may be asleep or out of coverage")
        val text = when (r) {
            is CommandResult.Success -> "$label: done"
            is CommandResult.Delivered -> "$label: car received it"
            is CommandResult.Failed -> "$label failed — ${r.reason}"
        }
        app.remote.addLog("watch: $text")
        reply(node, "/lionflow/cmd/reply", JSONObject().put("text", tr(text)).put("final", true))
    }
}
