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
 * Shared car state for the Android Auto screens, and the driver display.
 * Each of those runs in its own session/service, so they all read from here.
 */
object CarHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow<VehicleStatus?>(null)
    val status: StateFlow<VehicleStatus?> = _status

    private val _chargers = MutableStateFlow<List<Charger>>(emptyList())
    val chargers: StateFlow<List<Charger>> = _chargers

    private val _fuel = MutableStateFlow<List<Charger>>(emptyList())
    /** Petrol stations near the car (OpenStreetMap), for petrol cars and plug-in hybrids. */
    val fuel: StateFlow<List<Charger>> = _fuel
    private var fuelAt = 0L
    private var fuelLat = 0.0
    private var fuelLon = 0.0

    fun loadFuel(ctx: Context, lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (_fuel.value.isNotEmpty() && now - fuelAt < 6 * 3600_000L && distanceM(lat, lon, fuelLat, fuelLon) < 3000) return
        scope.launch {
            try {
                val list = OsmChargers.near(lat, lon, 15000, 60, "fuel")
                if (list.isNotEmpty()) {
                    _fuel.value = list.withDistance(lat, lon)
                    fuelAt = System.currentTimeMillis(); fuelLat = lat; fuelLon = lon
                }
            } catch (_: Exception) {
                if (_fuel.value.isEmpty()) say("Fuel station servers are busy — try again in a minute")
            }
        }
    }

    /** Short line about what's happening right now ("Pre-heating — car is waking up"). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** One-off results and notices, shown as a car toast. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** True while the car's status is being fetched. */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** Charger the driver picked in the list; the map flies there. */


    /** True while LionFlow holds the driver display (navigation "started" without a route). */


    /** What the last refresh did, shown at the bottom of the car overview while we track down car problems. */
    @Volatile var diag: String = "no refresh yet"
    private var lastRefresh = 0L
    private var refreshing = false
    private var clearJob: Job? = null

    fun app(ctx: Context) = ctx.applicationContext as LionFlowApp

    /** For the tests that draw the car screens: a status and trips put in place without asking the car. */
    @androidx.annotation.VisibleForTesting
    internal fun show(st: VehicleStatus?, trips: List<Trip>?) { _status.value = st; _trips.value = trips }

    /** Another car was chosen: forget everything about the old one and load the new one. */
    fun switchCar(ctx: Context, e: no.lionflow.app.model.Garage.Entry) {
        no.lionflow.app.model.Garage.activate(app(ctx), e)
        _status.value = null; _chargers.value = emptyList(); _fuel.value = emptyList(); _trips.value = null
        fuelAt = 0L; lastRefresh = 0L; tripsLoadedAt = 0L
        refresh(ctx, force = true); loadTrips(ctx, force = true)
    }

    fun refresh(ctx: Context, force: Boolean = false) {
        val app = app(ctx)
        if (!app.api.isSignedIn || refreshing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 60_000 && _status.value != null) return
        refreshing = true
        _loading.value = true
        diag = "refreshing…"
        scope.launch {
            try {
                val v = app.api.currentVehicle()
                if (v == null) diag = "no vehicle found on the account"
                if (v != null) {
                    diag = "vehicle ok, getting status…"
                    // A car the server has no live status for still gets a (nearly empty) status, so the screens work.
                    val raw = try { app.api.status(v) } catch (e: no.lionflow.app.api.ApiException) { if (e.noData) app.api.blankStatus(v) else throw e }
                    val st = no.lionflow.app.model.ChargePower.record(app.api.parseStatus(v, raw))
                    _status.value = st
                    diag = "status ok"
                    lastRefresh = System.currentTimeMillis()
                    no.lionflow.app.widget.WidgetData.save(ctx.applicationContext, st)
                    if (_chargers.value.isEmpty()) st.lat?.let { la -> st.lon?.let { lo -> loadChargers(ctx, la, lo) } }
                }
            } catch (e: Exception) {
                diag = "error: ${e.javaClass.simpleName} ${Http.describe(e)}".take(120)
                say("Couldn't update — ${Http.describe(e)}")
            } finally {
                refreshing = false
                _loading.value = false
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
                val v = app.api.currentVehicle() ?: return@launch
                val all = app.api.parseTrips(v, app.api.allTrips(v, System.currentTimeMillis() - 30 * 86_400_000L).trips)
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

    private var chargersBusy = false

    /** Chargers near a position: from the list kept on the phone when it is fresh and for the same place, else fetched. */
    fun loadChargers(ctx: Context, lat: Double, lon: Double) {
        if (chargersBusy) return
        val app = app(ctx)
        val src = app.settings.value.chargerSource
        val key = no.lionflow.app.BuildConfig.NOBIL_KEY
        val file = cacheFile(ctx)
        chargersBusy = true
        scope.launch {
            try {
                // The kept list is read off the main thread: it can be a big file.
                val kept = kotlinx.coroutines.withContext(Dispatchers.IO) {
                    try {
                        val j = JSONObject(file.readText())
                        val near = Math.abs(j.getDouble("lat") - lat) < 0.03 && Math.abs(j.getDouble("lon") - lon) < 0.05
                        if (j.optString("src") == src && near) j.getJSONArray("items").objects().map { chargerFromJson(it) }.withDistance(lat, lon) to j.getLong("time") else null
                    } catch (_: Exception) { null }
                }
                if (kept != null) {
                    _chargers.value = kept.first
                    if (System.currentTimeMillis() - kept.second < 6 * 3600_000L) return@launch
                }
                val list = if (src == "nobil" && key.isNotEmpty()) NobilApi.near(key, lat, lon) else OsmChargers.near(lat, lon)
                if (list.isNotEmpty()) {
                    _chargers.value = list.withDistance(lat, lon)
                    val text = JSONObject().put("src", src).put("lat", lat).put("lon", lon).put("time", System.currentTimeMillis())
                        .put("items", JSONArray(list.map { it.toJson() })).toString()
                    kotlinx.coroutines.withContext(Dispatchers.IO) { try { file.writeText(text) } catch (_: Exception) { } }
                }
            } catch (_: Exception) {
                if (_chargers.value.isEmpty()) say("Charger servers are busy — try again in a minute")
            } finally { chargersBusy = false }
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
        st.isCharging -> "Charging" + (no.lionflow.app.model.ChargePower.label(st)?.let { " at $it" } ?: "") + (no.lionflow.app.model.ChargePower.leftText(st) ?: "")
        st.chargingStatus == "Finished" -> "Charging complete"
        st.plugged -> "Plugged in, not charging"
        else -> "Not plugged in"
    }

    fun detailLine(ctx: Context): String {
        val st = _status.value ?: return "LionFlow"
        val s = app(ctx).settings.value
        val parts = mutableListOf<String>()
        if (st.energyType != "Fuel") parts += chargingLine(st)
        st.outsideTempC?.let { parts += "${fmtTemp(it, s)} outside" }
        if (st.preconditioningOn) parts += "pre-heating"
        return parts.joinToString(" · ").ifBlank { "LionFlow" }
    }

    /** The car's names for doors and hatches, as people say them. */
    private fun doorName(id: String): String = when (id.lowercase()) {
        "driver" -> "Driver's door"
        "passenger" -> "Passenger door"
        "rearleft" -> "Rear left door"
        "rearright" -> "Rear right door"
        "trunk", "boot" -> "Boot"
        "rearwindow" -> "Rear window"
        "roofwindow" -> "Sunroof"
        "hood", "bonnet" -> "Bonnet"
        else -> id
    }

    /** Already translated. */
    fun doorsLine(st: VehicleStatus) = when {
        st.openDoors.isNotEmpty() -> no.lionflow.app.ui.l10n.tr("Open: {0}").replace("{0}", st.openDoors.joinToString(", ") { no.lionflow.app.ui.l10n.tr(doorName(it)) })
        st.locked == true -> no.lionflow.app.ui.l10n.tr("Locked")
        st.locked == false -> no.lionflow.app.ui.l10n.tr("Unlocked")
        else -> no.lionflow.app.ui.l10n.tr("Unknown")
    }

    /** Battery and range as the car itself reports them through Android Auto (null when it doesn't). */
    class Live(val pct: Double?, val rangeKm: Double?)
    val live = MutableStateFlow(Live(null, null))

    private var askedAt = 0L

    /**
     * The brand's server only has what the car last sent, which can be many minutes old (a door shown open that was
     * closed long ago). While Android Auto runs the car is on anyway, so it is asked to send its state now and then,
     * when what we have is more than 2 minutes old; the status is fetched again once it has answered.
     */
    fun askCar(ctx: Context) {
        val app = app(ctx)
        if (!app.remote.isSetUp) return
        val now = System.currentTimeMillis()
        val at = _status.value?.updatedAtMillis ?: 0L
        if (now - at < 60_000L || now - askedAt < 90_000L) return
        askedAt = now
        scope.launch {
            val sent = app.remote.preWake(minIntervalMs = 90_000L) {
                scope.launch { delay(6_000L); refresh(ctx, force = true); delay(15_000L); refresh(ctx, force = true) }
            }
            if (sent) app.remote.addLog("Android Auto: asked the car for a fresh status")
        }
    }

    fun updatedLine(): String {
        val t = _status.value?.updatedAtMillis ?: return "Not updated yet"
        return "Car reported ${minutesAgo(t, System.currentTimeMillis())}"
    }
}
