package no.lionflow.app.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.AuthExpiredException
import no.lionflow.app.api.Charger
import no.lionflow.app.api.CommandResult
import no.lionflow.app.api.Tracker
import no.lionflow.app.api.NobilApi
import no.lionflow.app.api.Vehicle
import no.lionflow.app.api.Weather
import no.lionflow.app.api.WeatherApi
import no.lionflow.app.api.objects
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class Phase { Loading, SignedOut, SetupRemote, Locked, Ready }
enum class Tab { Home, Charging, Map, Climate, Service, Trips, Settings }

data class ServiceEntry(val title: String, val note: String, val dateMillis: Long)

data class UiState(
    val phase: Phase = Phase.Loading,
    val tab: Tab = Tab.Home,
    val busy: Boolean = false,
    val signingIn: Boolean = false,
    val vehicle: Vehicle? = null,
    val status: VehicleStatus? = null,
    val refreshing: Boolean = false,
    val lastRefresh: Long = 0L,
    val now: Long = System.currentTimeMillis(),
    val weather: Weather? = null,
    val myLat: Double? = null,
    val myLon: Double? = null,
    val chargers: List<Charger> = emptyList(),
    val chargerError: String? = null,
    val trips: List<Trip> = emptyList(),
    val tripsLoading: Boolean = false,
    val serviceHistory: List<ServiceEntry> = emptyList(),
    val commandInProgress: String? = null,
    val commandProgress: String? = null,
    val message: String? = null,
    val smsSent: Boolean = false,
    val setupError: String? = null,
    val photoPath: String? = null,
    val update: no.lionflow.app.update.Release? = null,
    val updateStatus: String? = null,
    val needsInstallPermission: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val lf = app as LionFlowApp
    private val api = lf.api
    private val remote = lf.remote
    val settings = lf.settings

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var ticker: Job? = null

    val remoteReady: Boolean get() = remote.isSetUp
    fun commandLog(): String = remote.log.joinToString("\n").ifEmpty { "No commands sent since the app was opened." }
    val hasPin: Boolean get() = remote.hasPin

    init {
        _ui.update { it.copy(lastRefresh = settings.lastRefresh, photoPath = settings.photoPath, serviceHistory = loadHistory()) }
        decideStartPhase()
    }

    private fun decideStartPhase() {
        if (!api.isSignedIn) {
            _ui.update { it.copy(phase = Phase.SignedOut) }
            return
        }
        val v = api.cachedVehicles().firstOrNull()
        _ui.update { it.copy(vehicle = v) }
        // The app is locked with the MyPeugeot security code (or fingerprint) once remote control is set up.
        _ui.update { it.copy(phase = if (remote.hasPin) Phase.Locked else Phase.Ready) }
        if (_ui.value.phase == Phase.Ready) onUnlocked()
    }

    fun setTab(t: Tab) = _ui.update { it.copy(tab = t) }
    fun clearMessage() = _ui.update { it.copy(message = null) }
    private fun toast(m: String) = _ui.update { it.copy(message = m) }

    // ---------------- sign in ----------------

    fun authorizeUrl(): String = api.authorizeUrl()

    fun handleRedirect(uri: Uri) {
        val code = uri.getQueryParameter("code") ?: run {
            toast(uri.getQueryParameter("error_description") ?: "Sign-in was cancelled")
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(signingIn = true) }
            try {
                api.exchangeCode(code)
                api.loadUserInfo()
                val v = api.vehicles().firstOrNull()
                _ui.update { it.copy(vehicle = v, signingIn = false, phase = Phase.SetupRemote) }
                if (v == null) toast("No car found on this account — add it in the MyPeugeot app first")
            } catch (e: Exception) {
                _ui.update { it.copy(signingIn = false) }
                toast("Sign-in failed — ${no.lionflow.app.api.Http.describe(e)}")
            }
        }
    }

    fun requestSms() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, setupError = null) }
            try {
                api.requestSmsCode()
                _ui.update { it.copy(busy = false, smsSent = true) }
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, setupError = "Couldn't send the SMS — ${no.lionflow.app.api.Http.describe(e)}") }
            }
        }
    }

    fun activateRemote(sms: String, pin: String) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, setupError = null) }
            try {
                remote.activate(sms, pin)
                _ui.update { it.copy(busy = false, phase = Phase.Ready) }
                toast("Remote control is ready")
                onUnlocked()
            } catch (e: Exception) {
                _ui.update { it.copy(busy = false, setupError = "Activation failed — ${no.lionflow.app.api.Http.describe(e)}") }
            }
        }
    }

    fun skipRemoteSetup() {
        _ui.update { it.copy(phase = Phase.Ready) }
        onUnlocked()
    }

    fun openRemoteSetup() = _ui.update { it.copy(phase = Phase.SetupRemote, smsSent = false, setupError = null) }

    fun signOut() {
        remote.forget()
        api.signOut()
        settings.clearPersonal()
        no.lionflow.app.widget.WidgetData.clear(getApplication<Application>())
        ticker?.cancel()
        _ui.value = UiState(phase = Phase.SignedOut, photoPath = settings.photoPath, serviceHistory = loadHistory())
    }

    // ---------------- lock ----------------

    fun checkPin(pin: String): Boolean = remote.checkPin(pin)

    fun unlock() {
        _ui.update { it.copy(phase = Phase.Ready) }
        onUnlocked()
    }

    private fun onUnlocked() {
        checkForUpdate(force = false)
        maybeAutoRefresh(force = _ui.value.status == null)
        warmUpRemote()
        startTicker()
        loadLocationAndWeather()
    }

    // ---------------- refresh ----------------

    /** Called when the app comes to the foreground. */
    fun onForeground() {
        if (_ui.value.phase == Phase.Ready) {
            maybeAutoRefresh(force = false)
            startTicker()
            warmUpRemote()
            checkForUpdate(force = false)
            // Back from "Install unknown apps": continue the update automatically.
            if (_ui.value.needsInstallPermission && no.lionflow.app.update.Updater.canInstall(getApplication<Application>())) installUpdate()
        }
    }

    private fun warmUpRemote() {
        if (!remote.isSetUp) return
        viewModelScope.launch {
            remote.warmUp()
            // If the car looks asleep, start waking it now so the first command is quick.
            val st = _ui.value.status
            val stale = st?.updatedAtMillis?.let { System.currentTimeMillis() - it > 5 * 60_000 } ?: true
            if (settings.value.wakeOnOpen && stale && st?.moving != true) {
                remote.preWake(minIntervalMs = 10 * 60_000) {
                    viewModelScope.launch { delay(4_000); refresh() }
                }
            }
        }
    }

    /** Called the moment a remote-action button is tapped, before fingerprint/code confirmation. */
    fun prewakeForAction() {
        if (remote.isSetUp) viewModelScope.launch { remote.preWake(minIntervalMs = 2 * 60_000) }
    }

    fun onBackground() {
        ticker?.cancel()
        ticker = null
        if (_ui.value.commandInProgress == null) viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { remote.disconnect() }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                _ui.update { it.copy(now = System.currentTimeMillis()) }
                maybeAutoRefresh(force = false)
            }
        }
    }

    /** At most once per 5 minutes, and only while the app is open. */
    private fun maybeAutoRefresh(force: Boolean) {
        val since = System.currentTimeMillis() - settings.lastRefresh
        if (force || (settings.value.autoRefresh && since >= 5 * 60_000)) refresh()
    }

    fun refresh() {
        if (_ui.value.refreshing) return
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            try {
                val v = _ui.value.vehicle ?: api.vehicles().firstOrNull() ?: throw IllegalStateException("No car on this account")
                val raw = api.status(v)
                val maint = api.maintenance(v)
                val st = VehicleStatus.parse(raw, maint)
                val now = System.currentTimeMillis()
                settings.lastRefresh = now
                no.lionflow.app.widget.WidgetData.save(getApplication<Application>(), st)
                _ui.update { it.copy(vehicle = v, status = st, refreshing = false, lastRefresh = now, now = now) }
            } catch (e: AuthExpiredException) {
                _ui.update { it.copy(refreshing = false) }
                toast("Your Peugeot session expired — please sign in again")
                signOut()
            } catch (e: Exception) {
                _ui.update { it.copy(refreshing = false) }
                val msg = no.lionflow.app.api.Http.describe(e)
                remote.addLog("refresh failed: $msg")
                toast("Couldn't update — $msg")
            }
        }
    }

    // ---------------- remote commands ----------------

    fun runCommand(label: String, optimistic: (VehicleStatus) -> VehicleStatus = { it }, block: suspend (Tracker) -> CommandResult) {
        if (!remote.isSetUp) {
            toast("Set up remote control first (Settings › Security)")
            return
        }
        if (_ui.value.commandInProgress != null) return
        viewModelScope.launch {
            _ui.update { it.copy(commandInProgress = label, commandProgress = "Sending…") }
            val tracker = Tracker(
                onProgress = { p -> _ui.update { it.copy(commandProgress = p) } },
                // Arrives later only when we stopped waiting at "delivered".
                onFinal = { r ->
                    when (r) {
                        is CommandResult.Failed -> { remote.addLog("$label failed: ${r.reason}"); toast("$label didn't go through: ${r.reason}") }
                        else -> {}
                    }
                    viewModelScope.launch { delay(2_000); refresh() }
                },
            )
            val r = block(tracker)
            when (r) {
                is CommandResult.Success, is CommandResult.Delivered -> {
                    // Show the result straight away; the real status follows.
                    _ui.update { s -> s.copy(commandInProgress = null, commandProgress = null, status = s.status?.let(optimistic)) }
                    toast(if (r is CommandResult.Delivered) "$label — the car got it" else "$label — done")
                    delay(3_000)
                    refresh()
                }
                is CommandResult.Failed -> {
                    _ui.update { it.copy(commandInProgress = null, commandProgress = null) }
                    remote.addLog("$label failed: ${r.reason}")
                    toast("$label: ${r.reason}")
                }
            }
        }
    }

    fun lockCar(lock: Boolean) = runCommand(if (lock) "Lock" else "Unlock", { it.copy(locked = lock) }) { p -> remote.lock(lock, p) }
    fun horn() = runCommand("Horn") { p -> remote.horn(p) }
    fun lights() = runCommand("Flash lights") { p -> remote.lights(p) }
    fun preconditioning(on: Boolean) = runCommand(if (on) "Pre-heating" else "Stop pre-heating", { it.copy(preconditioningOn = on) }) { p ->
        val st = _ui.value.status ?: return@runCommand CommandResult.Failed("No status yet")
        remote.preconditioning(on, st.programsJson(), p)
    }

    fun charging(startNow: Boolean) = runCommand(if (startNow) "Charge now" else "Stop charging", { it.copy(chargingStatus = if (startNow) "InProgress" else "Stopped") }) { p ->
        val st = _ui.value.status
        remote.charging(startNow, st?.nextDelayedHour ?: 1, st?.nextDelayedMinute ?: 0, p)
    }

    fun wakeUp() = runCommand("Wake car") { p -> remote.wakeUp(p) }

    // ---------------- app updates ----------------

    /** Looks for a newer release at most every 6 hours (or now when [force]); installs it if auto-update is on. */
    fun checkForUpdate(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - settings.lastUpdateCheck < 6 * 3600_000L) return
        settings.lastUpdateCheck = now
        viewModelScope.launch {
            try {
                val r = no.lionflow.app.update.Updater.latest()
                if (r != null && no.lionflow.app.update.Updater.isNewer(r)) {
                    _ui.update { it.copy(update = r, updateStatus = null) }
                    if (settings.value.autoUpdate) installUpdate()
                } else {
                    _ui.update { it.copy(update = null) }
                    if (force) toast("LionFlow is up to date (${no.lionflow.app.BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                if (force) toast("Couldn't check for updates — ${no.lionflow.app.api.Http.describe(e)}")
            }
        }
    }

    fun installUpdate() {
        val r = _ui.value.update ?: return
        val ctx = getApplication<Application>()
        if (!no.lionflow.app.update.Updater.canInstall(ctx)) {
            _ui.update { it.copy(needsInstallPermission = true, updateStatus = "Allow LionFlow to install updates (one time)") }
            return
        }
        if (_ui.value.updateStatus?.startsWith("Downloading") == true) return
        viewModelScope.launch {
            try {
                _ui.update { it.copy(needsInstallPermission = false, updateStatus = "Downloading ${r.tag}…") }
                val f = no.lionflow.app.update.Updater.download(ctx, r) { p -> _ui.update { it.copy(updateStatus = "Downloading ${r.tag}… $p %") } }
                if (!no.lionflow.app.update.Updater.sameSigner(ctx, f)) {
                    f.delete()
                    _ui.update { it.copy(updateStatus = "The download isn't signed with LionFlow's key — not installed") }
                    remote.addLog("update ${r.tag} rejected: signature mismatch")
                    return@launch
                }
                _ui.update { it.copy(updateStatus = "Installing ${r.tag}…") }
                no.lionflow.app.update.Updater.install(ctx, f)
            } catch (e: Exception) {
                _ui.update { it.copy(updateStatus = "Update failed — ${no.lionflow.app.api.Http.describe(e)}") }
            }
        }
    }

    // ---------------- location, weather, chargers ----------------

    @SuppressLint("MissingPermission")
    fun loadLocationAndWeather() {
        val ctx = getApplication<Application>()
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var loc: Location? = null
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (loc == null || l.time > loc.time) loc = l
            }
        } catch (_: SecurityException) { }
        val st = _ui.value.status
        val lat = loc?.latitude ?: st?.lat
        val lon = loc?.longitude ?: st?.lon
        if (lat == null || lon == null) return
        _ui.update { it.copy(myLat = loc?.latitude, myLon = loc?.longitude) }
        viewModelScope.launch {
            try {
                val w = WeatherApi.forecast(lat, lon)
                _ui.update { it.copy(weather = w) }
            } catch (_: Exception) { }
        }
        loadChargers(lat, lon)
    }

    fun loadChargers(lat: Double? = null, lon: Double? = null) {
        val src = settings.value.chargerSource
        val key = settings.value.nobilKey.trim()
        if (src == "nobil" && key.isEmpty()) {
            _ui.update { it.copy(chargerError = "Add your NOBIL key in Settings, or switch to OpenStreetMap (free)") }
            return
        }
        val la = lat ?: _ui.value.myLat ?: _ui.value.status?.lat ?: return
        val lo = lon ?: _ui.value.myLon ?: _ui.value.status?.lon ?: return
        viewModelScope.launch {
            try {
                val list = if (src == "nobil") NobilApi.near(key, la, lo) else no.lionflow.app.api.OsmChargers.near(la, lo)
                _ui.update { it.copy(chargers = list, chargerError = if (list.isEmpty()) "No chargers found nearby" else null) }
            } catch (e: Exception) {
                _ui.update { it.copy(chargerError = "Chargers: ${no.lionflow.app.api.Http.describe(e)}") }
            }
        }
    }

    // ---------------- trips ----------------

    fun loadTrips(days: Int = 30) {
        val v = _ui.value.vehicle ?: return
        viewModelScope.launch {
            _ui.update { it.copy(tripsLoading = true) }
            try {
                val since = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date(System.currentTimeMillis() - days * 86_400_000L))
                val all = mutableListOf<Trip>()
                var page: String? = null
                var guard = 0
                do {
                    val j = api.trips(v, since, page)
                    j.optJSONObject("_embedded")?.optJSONArray("trips")?.objects()?.forEach { all += Trip.parse(it) }
                    val next = j.optJSONObject("_links")?.optJSONObject("next")?.optString("href")
                    page = next?.substringAfter("pageToken=", "")?.substringBefore("&")?.ifEmpty { null }
                    guard++
                } while (page != null && guard < 10)
                val imported = loadImportedTrips()
                val merged = (all + imported).distinctBy { it.id }.sortedByDescending { it.startMillis ?: 0L }
                _ui.update { it.copy(trips = merged, tripsLoading = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(tripsLoading = false, trips = loadImportedTrips()) }
                toast("Couldn't load trips — ${no.lionflow.app.api.Http.describe(e)}")
            }
        }
    }

    fun exportTripsCsv(): File {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "lionflow-trips.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val sb = StringBuilder("id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh\n")
        for (t in _ui.value.trips) {
            sb.append(listOf(
                t.id,
                t.startMillis?.let { iso.format(Date(it)) } ?: "",
                t.endMillis?.let { iso.format(Date(it)) } ?: "",
                String.format(Locale.US, "%.1f", t.distanceKm),
                t.durationSec?.let { String.format(Locale.US, "%.0f", it / 60) } ?: "",
                t.kwh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                t.avgKwhPer100?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                t.avgSpeedKmh?.let { String.format(Locale.US, "%.0f", it) } ?: "",
            ).joinToString(",")).append('\n')
        }
        f.writeText(sb.toString())
        return f
    }

    fun importTripsCsv(text: String) {
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val rows = text.lines().drop(1).filter { it.isNotBlank() }
        val arr = JSONArray(settings.importedTripsJson)
        var count = 0
        for (r in rows) {
            val c = r.split(",")
            if (c.size < 4) continue
            arr.put(JSONObject()
                .put("id", c[0].ifEmpty { "imp-" + System.nanoTime() })
                .put("start", c.getOrNull(1)?.let { s -> try { iso.parse(s)?.time } catch (_: Exception) { null } } ?: 0L)
                .put("end", c.getOrNull(2)?.let { s -> try { iso.parse(s)?.time } catch (_: Exception) { null } } ?: 0L)
                .put("km", c.getOrNull(3)?.toDoubleOrNull() ?: 0.0)
                .put("min", c.getOrNull(4)?.toDoubleOrNull() ?: 0.0)
                .put("kwh", c.getOrNull(5)?.toDoubleOrNull() ?: 0.0)
                .put("avg", c.getOrNull(6)?.toDoubleOrNull() ?: 0.0)
                .put("speed", c.getOrNull(7)?.toDoubleOrNull() ?: 0.0))
            count++
        }
        settings.importedTripsJson = arr.toString()
        toast("Imported $count trips")
        _ui.update { s -> s.copy(trips = (s.trips + loadImportedTrips()).distinctBy { it.id }.sortedByDescending { it.startMillis ?: 0L }) }
    }

    private fun loadImportedTrips(): List<Trip> = try {
        JSONArray(settings.importedTripsJson).objects().map { o ->
            Trip(
                id = o.optString("id"),
                startMillis = o.optLong("start").takeIf { it > 0 },
                endMillis = o.optLong("end").takeIf { it > 0 },
                distanceKm = o.optDouble("km", 0.0),
                durationSec = o.optDouble("min", 0.0) * 60,
                kwh = o.optDouble("kwh", 0.0).takeIf { it > 0 },
                avgKwhPer100 = o.optDouble("avg", 0.0).takeIf { it > 0 },
                avgSpeedKmh = o.optDouble("speed", 0.0).takeIf { it > 0 },
            )
        }
    } catch (_: Exception) { emptyList() }

    // ---------------- service history & photo ----------------

    private fun loadHistory(): List<ServiceEntry> = try {
        JSONArray(settings.serviceHistoryJson).objects().map { ServiceEntry(it.optString("title"), it.optString("note"), it.optLong("date")) }
            .sortedByDescending { it.dateMillis }
    } catch (_: Exception) { emptyList() }

    fun addServiceEntry(title: String, note: String) {
        val list = loadHistory() + ServiceEntry(title, note, System.currentTimeMillis())
        settings.serviceHistoryJson = JSONArray(list.map { JSONObject().put("title", it.title).put("note", it.note).put("date", it.dateMillis) }).toString()
        _ui.update { it.copy(serviceHistory = loadHistory()) }
    }

    fun deleteServiceEntry(e: ServiceEntry) {
        val list = loadHistory().filterNot { it == e }
        settings.serviceHistoryJson = JSONArray(list.map { JSONObject().put("title", it.title).put("note", it.note).put("date", it.dateMillis) }).toString()
        _ui.update { it.copy(serviceHistory = loadHistory()) }
    }

    fun savePhoto(uri: Uri) {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val f = File(ctx.filesDir, "car_photo.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                settings.photoPath = f.absolutePath
                _ui.update { it.copy(photoPath = f.absolutePath + "?" + System.currentTimeMillis()) }
            } catch (e: Exception) {
                toast("Couldn't save the photo")
            }
        }
    }
}
