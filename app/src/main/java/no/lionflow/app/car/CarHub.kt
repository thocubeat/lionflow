package no.lionflow.app.car

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.Charger
import no.lionflow.app.api.CommandResult
import no.lionflow.app.api.Http
import no.lionflow.app.api.NobilApi
import no.lionflow.app.api.OsmChargers
import no.lionflow.app.api.RemoteControl
import no.lionflow.app.api.Tracker
import no.lionflow.app.api.chargerFromJson
import no.lionflow.app.api.objects
import no.lionflow.app.api.toJson
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.minutesAgo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shared car state for the Android Auto screens, the driver display and the media source.
 * Each of those runs in its own session/service, so they all read from here.
 */
object CarHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow<VehicleStatus?>(null)
    val status: StateFlow<VehicleStatus?> = _status

    private val _chargers = MutableStateFlow<List<Charger>>(emptyList())
    val chargers: StateFlow<List<Charger>> = _chargers

    /** Short line about what's happening right now ("Pre-heating — car is waking up"). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** One-off results and notices, shown as a car toast. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Charger the driver picked in the list; the map flies there. */
    val focus = MutableStateFlow<Charger?>(null)

    /** True while LionFlow holds the driver display (navigation "started" without a route). */
    val driverDisplay = MutableStateFlow(false)

    private var lastRefresh = 0L
    private var refreshing = false
    private var clearJob: Job? = null

    fun app(ctx: Context) = ctx.applicationContext as LionFlowApp

    fun refresh(ctx: Context, force: Boolean = false) {
        val app = app(ctx)
        if (!app.api.isSignedIn || refreshing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 60_000 && _status.value != null) return
        refreshing = true
        scope.launch {
            try {
                val v = app.api.cachedVehicles().firstOrNull() ?: app.api.vehicles().firstOrNull()
                if (v != null) {
                    val st = VehicleStatus.parse(app.api.status(v), null)
                    _status.value = st
                    lastRefresh = System.currentTimeMillis()
                    no.lionflow.app.widget.WidgetData.save(ctx.applicationContext, st)
                    if (_chargers.value.isEmpty()) st.lat?.let { la -> st.lon?.let { lo -> loadChargers(ctx, la, lo) } }
                }
            } catch (e: Exception) {
                say("Couldn't update — ${Http.describe(e)}")
            } finally {
                refreshing = false
            }
        }
    }

    // ---------------- remote actions ----------------

    fun actionsAllowed(ctx: Context): String? {
        val app = app(ctx)
        return when {
            !app.api.isSignedIn -> "Sign in to LionFlow on your phone first"
            !app.settings.value.carActions -> "Remote actions in the car are off in LionFlow settings"
            !app.remote.isSetUp -> "Set up remote control in LionFlow on your phone first"
            else -> null
        }
    }

    fun command(ctx: Context, label: String, block: suspend (RemoteControl, VehicleStatus?, Tracker) -> CommandResult) {
        actionsAllowed(ctx)?.let { say(it); return }
        if (_busy.value) { say("Wait — still sending the last command"); return }
        val app = app(ctx)
        _busy.value = true
        say("$label…", sticky = true)
        scope.launch {
            val t = Tracker(
                onProgress = { p -> _message.value = "$label — $p" },
                onFinal = { r -> scope.launch { say(resultText(label, r)); delay(3000); refresh(ctx, force = true) } },
            )
            val r = try {
                block(app.remote, _status.value, t)
            } catch (e: Exception) {
                CommandResult.Failed(Http.describe(e))
            }
            _busy.value = false
            say(resultText(label, r))
            app.remote.addLog("car: $label → ${resultText(label, r)}")
            delay(3000)
            refresh(ctx, force = true)
        }
    }

    private fun resultText(label: String, r: CommandResult) = when (r) {
        is CommandResult.Success -> "$label: done"
        is CommandResult.Delivered -> "$label: car received it"
        is CommandResult.Failed -> "$label failed — ${r.reason}"
    }

    fun preheat(ctx: Context, on: Boolean) = command(ctx, if (on) "Pre-heating" else "Stop pre-heating") { rc, st, t ->
        rc.preconditioning(on, st?.programsJson() ?: JSONObject(), t)
    }

    fun charge(ctx: Context, start: Boolean) = command(ctx, if (start) "Charge now" else "Stop charging") { rc, st, t ->
        rc.charging(start, st?.nextDelayedHour ?: 1, st?.nextDelayedMinute ?: 0, t)
    }

    fun lock(ctx: Context, lock: Boolean) = command(ctx, if (lock) "Lock" else "Unlock") { rc, _, t -> rc.lock(lock, t) }
    fun lights(ctx: Context) = command(ctx, "Flash lights") { rc, _, t -> rc.lights(t) }
    fun horn(ctx: Context) = command(ctx, "Horn") { rc, _, t -> rc.horn(t) }

    fun say(text: String, sticky: Boolean = false) {
        _message.value = text
        if (!sticky) _notices.tryEmit(text)
        clearJob?.cancel()
        if (!sticky) clearJob = scope.launch { delay(12_000); if (_message.value == text) _message.value = null }
    }

    // ---------------- trips ----------------

    private val _trips = MutableStateFlow<List<Trip>?>(null)
    val trips: StateFlow<List<Trip>?> = _trips
    private var tripsLoadedAt = 0L

    /** Last 30 days of trips from Peugeot (newest first), at most every 5 minutes. */
    fun loadTrips(ctx: Context, force: Boolean = false) {
        val app = app(ctx)
        if (!app.api.isSignedIn) return
        if (!force && _trips.value != null && System.currentTimeMillis() - tripsLoadedAt < 5 * 60_000) return
        tripsLoadedAt = System.currentTimeMillis()
        scope.launch {
            try {
                val v = app.api.cachedVehicles().firstOrNull() ?: app.api.vehicles().firstOrNull() ?: return@launch
                val since = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .format(java.util.Date(System.currentTimeMillis() - 30 * 86_400_000L))
                val all = mutableListOf<Trip>()
                var page: String? = null
                var guard = 0
                do {
                    val j = app.api.trips(v, since, page)
                    j.optJSONObject("_embedded")?.optJSONArray("trips")?.objects()?.forEach { all += Trip.parse(it) }
                    page = j.optJSONObject("_links")?.optJSONObject("next")?.optString("href")
                        ?.substringAfter("pageToken=", "")?.substringBefore("&")?.ifEmpty { null }
                    guard++
                } while (page != null && guard < 10)
                _trips.value = all.distinctBy { it.id }.sortedByDescending { it.startMillis ?: 0L }
            } catch (e: Exception) {
                tripsLoadedAt = 0L
                if (_trips.value == null) _trips.value = emptyList()
                say("Couldn't load trips — ${Http.describe(e)}")
            }
        }
    }

    // ---------------- chargers ----------------

    private fun cacheFile(ctx: Context) = File(ctx.cacheDir, "chargers.json")

    fun loadChargers(ctx: Context, lat: Double, lon: Double) {
        val app = app(ctx)
        val src = app.settings.value.chargerSource
        val key = app.settings.value.nobilKey.trim()
        try {
            val j = JSONObject(cacheFile(ctx).readText())
            val near = Math.abs(j.getDouble("lat") - lat) < 0.03 && Math.abs(j.getDouble("lon") - lon) < 0.05
            if (j.optString("src") == src && near) {
                _chargers.value = j.getJSONArray("items").objects().map { chargerFromJson(it) }.withDistance(lat, lon)
                if (System.currentTimeMillis() - j.getLong("time") < 6 * 3600_000L) return
            }
        } catch (_: Exception) { }
        scope.launch {
            try {
                val list = if (src == "nobil" && key.isNotEmpty()) NobilApi.near(key, lat, lon) else OsmChargers.near(lat, lon)
                if (list.isNotEmpty()) {
                    _chargers.value = list.withDistance(lat, lon)
                    cacheFile(ctx).writeText(JSONObject().put("src", src).put("lat", lat).put("lon", lon).put("time", System.currentTimeMillis())
                        .put("items", JSONArray(list.map { it.toJson() })).toString())
                }
            } catch (_: Exception) {
                if (_chargers.value.isEmpty()) say("Charger servers are busy — try again in a minute")
            }
        }
    }

    private fun List<Charger>.withDistance(lat: Double, lon: Double) =
        map { it.copy(distanceM = distanceM(lat, lon, it.lat, it.lon).toInt()) }.sortedBy { it.distanceM }

    fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val a = Math.sin(dLa / 2).let { it * it } + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2)) * Math.sin(dLo / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }

    // ---------------- text for the car screens ----------------

    fun batteryLine(ctx: Context): String {
        val st = _status.value ?: return if (app(ctx).api.isSignedIn) "Getting car status…" else "Sign in on your phone"
        val s = app(ctx).settings.value
        return "${st.batteryPct?.toInt() ?: "—"} %  ·  ${fmtDistance(st.rangeKm, s)}"
    }

    fun chargingLine(st: VehicleStatus): String = when {
        st.isCharging -> "Charging" + (st.remainingMinutes?.let { m -> " · ${m / 60} h ${m % 60} min left" } ?: "")
        st.chargingStatus == "Finished" -> "Charging complete"
        st.plugged -> "Plugged in, not charging"
        else -> "Not plugged in"
    }

    fun detailLine(ctx: Context): String {
        val st = _status.value ?: return "LionFlow"
        val s = app(ctx).settings.value
        val parts = mutableListOf(chargingLine(st))
        st.outsideTempC?.let { parts += "${fmtTemp(it, s)} outside" }
        if (st.preconditioningOn) parts += "pre-heating"
        return parts.joinToString(" · ")
    }

    fun doorsLine(st: VehicleStatus) = when {
        st.openDoors.isNotEmpty() -> "Open: ${st.openDoors.joinToString(", ")}"
        st.locked == true -> "Locked"
        st.locked == false -> "Unlocked"
        else -> "Unknown"
    }

    fun updatedLine(): String {
        val t = _status.value?.updatedAtMillis ?: return "Not updated yet"
        return "Car reported ${minutesAgo(t, System.currentTimeMillis())}"
    }
}
