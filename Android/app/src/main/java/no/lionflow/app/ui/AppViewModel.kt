package no.lionflow.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import no.lionflow.app.api.toJson
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
    /** The carmaker has no live status for this car (common on older petrol/diesel cars); trips etc. still work. */
    val statusMissing: Boolean = false,
    val refreshing: Boolean = false,
    val reloadingVehicles: Boolean = false,
    val lastRefresh: Long = 0L,
    val now: Long = System.currentTimeMillis(),
    val weather: Weather? = null,
    val myLat: Double? = null,
    val myLon: Double? = null,
    val chargers: List<Charger> = emptyList(),
    val chargerError: String? = null,
    val trips: List<Trip> = emptyList(),
    val routes: List<no.lionflow.app.route.Route> = emptyList(),
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
    val updateDismissed: Boolean = false,
    val vehicles: List<no.lionflow.app.api.Vehicle> = emptyList(),
    /** Warnings the car itself reports (tyre pressure, a lamp …). */
    val alerts: List<no.lionflow.app.model.CarAlert> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val lf = app as LionFlowApp
    private val api = lf.api
    private val remote = lf.remote
    val settings = lf.settings

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    /** The EV trip planner on the Map tab. */
    val planner by lazy {
        no.lionflow.app.plan.Planner(viewModelScope, lf, { _ui.value.status }, { _ui.value.trips },
            { _ui.value.myLat?.let { la -> _ui.value.myLon?.let { lo -> la to lo } } })
    }
    private var ticker: Job? = null
    private val vehicleMetadataLock = Mutex()
    private val brandingAttempts = HashSet<Pair<String, String>>()

    val remoteReady: Boolean get() = remote.isSetUp || isDemo
    /** Demo mode: a sample car, no account. */
    val isDemo: Boolean get() = no.lionflow.app.demo.Demo.isOn(getApplication())
    /** Store screenshots: demo without the "Demo mode" banner. */
    var hideDemoBanner by androidx.compose.runtime.mutableStateOf(false)
    /** False when Peugeot has no remote-services link for this car — then don't offer setup at all. */
    val remotePossible: Boolean get() = api.customerId != null || remote.isSetUp || isDemo
    fun commandLog(): String = energyLog() + remote.log.joinToString("\n").ifEmpty { "No commands sent since the app was opened." }

    /**
     * Technical details for a bug report: app, phone, car model and the latest car data and errors. No position,
     * names, e-mail, VIN or account ids: those are left out or blanked.
     */
    fun techDetails(): String {
        val app = getApplication<Application>()
        val st = _ui.value.status
        val v = _ui.value.vehicle
        val s = settings.value
        val play = no.lionflow.app.plan.SavedRoutes.fromPlay(app)
        val sb = StringBuilder()
        sb.append("LionFlow ${no.lionflow.app.BuildConfig.VERSION_NAME} (${if (play) "Google Play" else "GitHub"})\n")
        sb.append("Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        sb.append("Language ${no.lionflow.app.ui.l10n.L10n.lang} · ${java.util.TimeZone.getDefault().id}\n")
        if (v != null) sb.append("Car: ${v.brand} · ${v.motorization} · model code ${v.vin.take(8)}\n")
        if (st != null) {
            sb.append("Energy ${st.energyType} · battery ${st.batteryPct?.toInt()} % · capacity ${st.capacityKwh} kWh · residual ${st.residualKwh}\n")
            sb.append("Charging ${st.chargingStatus} · ${st.chargeType} · plugged ${st.plugged} · preheat ${st.preconditioningOn} · ignition ${st.ignition}\n")
            sb.append("Car reported ${st.updatedAtMillis?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(it)) }}\n")
        }
        sb.append("Remote control set up: $remoteReady\n")
        sb.append("Charge limit ${if (s.chargeLimitOn) "${s.chargeLimitPct} %" else "off"} · smart schedule ${s.smartMode.ifEmpty { "off" }} · route tracking ${s.routeTracking}\n")
        sb.append("Trips: ${_ui.value.trips.size} shown, server none-found ${tripsNone}, logged from the car ${no.lionflow.app.model.CarTrips.list().size}\n\n")
        sb.append(commandLog())
        return scrub(sb.toString())
    }

    /** Blanks anything that could point to a person or place: coordinates, VINs, e-mails, ids and tokens. */
    private fun scrub(t: String): String = t
        .replace(Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+"), "[e-mail]")
        .replace(Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b"), "[VIN]")
        .replace(Regex("-?\\b\\d{1,3}\\.\\d{4,}\\b"), "[pos]")
        .replace(Regex("(?i)bearer\\s+\\S+"), "Bearer [token]")
        .replace(Regex("\\b[A-Za-z0-9_-]{24,}\\b"), "[id]")

    /** What the car reports about battery and fuel, word for word: for finding out why a level shows wrong. */
    private fun energyLog(): String {
        val raw = _ui.value.status?.raw ?: return ""
        val sb = StringBuilder("LionFlow ${no.lionflow.app.BuildConfig.VERSION_NAME} · car energy data\n")
        (raw.optJSONArray("energies") ?: raw.optJSONArray("energy"))?.objects()?.forEach { e ->
            val ch = e.optJSONObject("extension")?.optJSONObject("electric")?.optJSONObject("charging")
            val load = e.optJSONObject("extension")?.optJSONObject("electric")?.optJSONObject("battery")?.optJSONObject("load")
            sb.append("${e.optString("type")}: level=${e.opt("level")} autonomy=${e.opt("autonomy")} updated=${e.optString("updatedAt")}")
            if (ch != null) sb.append(" plugged=${ch.opt("plugged")} status=${ch.optString("status")}")
            if (load != null) sb.append(" capacity=${load.opt("capacity")} residual=${load.opt("residual")}")
            sb.append("\n")
        } ?: sb.append("no energies in the answer (it has: ${raw.keys().asSequence().toList().sorted().joinToString(", ")})\n")
        val hist = no.lionflow.app.model.ChargePower.history()
        if (hist.isNotEmpty()) { sb.append("\nlast car reports (newest first):\n"); hist.forEach { sb.append(it).append('\n') } }
        return sb.append("\n").toString()
    }

    /** Trip tags (kept on the phone). */
    val tags = no.lionflow.app.model.TripTags({ settings.tripTagsJson }, { settings.tripTagsJson = it })

    fun tagTrip(t: Trip, tagId: String?) {
        tags.set(t, tagId, tripEnds(t))
        if (tagId != null) autoTagNow()
    }

    fun editTag(id: String, name: String, color: Int, from: no.lionflow.app.model.TagPlace?, to: no.lionflow.app.model.TagPlace?) {
        tags.edit(id, name, color, from, to); autoTagNow()
    }

    fun setAutoTag(on: Boolean) {
        settings.update { it.copy(autoTagTrips = on) }
        if (on) autoTagNow() else tags.clearAuto()
    }

    fun setAutoTagRoute(on: Boolean) { settings.update { it.copy(autoTagRoute = on) }; autoTagNow() }

    /** Auto-tagging goes by place only when the user asked for it and routes are being recorded. */
    private fun autoTagNow() {
        val s = settings.value
        if (!s.autoTagTrips) return
        tags.autoTag(_ui.value.trips, if (s.autoTagRoute && s.routeTracking) ::tripEnds else null)
    }

    /** Where a trip started and ended, from the route recorded on the phone; null when it wasn't recorded. */
    private val endsCache = HashMap<String, no.lionflow.app.model.TripEnds?>()
    private fun tripEnds(t: Trip): no.lionflow.app.model.TripEnds? {
        val r = no.lionflow.app.route.Routes.forTrip(_ui.value.routes, t.startMillis, t.endMillis) ?: return null
        return endsCache.getOrPut(r.file.path) {
            val p = no.lionflow.app.route.Routes.points(r.file)
            if (p.size < 2) null else no.lionflow.app.model.TripEnds(p.first().first, p.first().second, p.last().first, p.last().second)
        }
    }

    suspend fun searchPlace(q: String): List<no.lionflow.app.plan.Place> =
        no.lionflow.app.plan.PlanApi.search(q.trim(), _ui.value.status?.lat, _ui.value.status?.lon, no.lionflow.app.ui.l10n.L10n.lang)

    /** Makes sure trips this far back are loaded (for statistics over a longer time). */
    fun ensureTrips(days: Int) { if (days > tripsDays && !_ui.value.tripsLoading) loadTrips(days) }
    private var tripsDays = 30
    val hasPin: Boolean get() = remote.hasPin

    init {
        _ui.update { it.copy(lastRefresh = settings.lastRefresh, photoPath = curPhoto(), serviceHistory = loadHistory()) }
        decideStartPhase()
        checkForUpdate(force = false)
        viewModelScope.launch { kotlinx.coroutines.yield(); checkWhatsNew() }
    }

    private fun decideStartPhase() {
        if (isDemo) { showDemo(); return }
        if (!api.isSignedIn) {
            _ui.update { it.copy(phase = Phase.SignedOut) }
            return
        }
        val v = api.cachedVehicle()
        _ui.update { it.copy(vehicle = v, vehicles = api.cachedVehicles()) }
        // The app is locked with the MyPeugeot security code (or fingerprint) once remote control is set up.
        _ui.update { it.copy(phase = if (remote.hasPin) Phase.Locked else Phase.Ready) }
        if (_ui.value.phase == Phase.Ready) onUnlocked()
    }

    /** A setting to scroll to and flash when the Settings tab opens (set by [openSetting]). */
    var focusSetting by androidx.compose.runtime.mutableStateOf<String?>(null)

    fun openSetting(key: String) { focusSetting = key; setTab(Tab.Settings) }

    fun setTab(t: Tab) { if (Caps.tab(t, _ui.value.status)) _ui.update { it.copy(tab = t) } }

    /** Switch to another car on the account. Each car keeps its own name, charging log and range learning. */
    fun selectVehicle(v: no.lionflow.app.api.Vehicle) {
        val old = _ui.value.vehicle
        if (old?.vin == v.vin) return
        old?.let { settings.setNameFor(it.vin, settings.value.carName) }
        api.selectedVin = v.vin
        settings.update { it.copy(carName = settings.nameFor(v.vin) ?: ("My " + no.lionflow.app.model.Brands.current.display)) }
        settings.pendingPrograms = ""
        no.lionflow.app.model.ChargeLog.setVin(v.vin)
        no.lionflow.app.model.ChargePower.setVin(v.vin)
        no.lionflow.app.widget.WidgetData.clear(getApplication())
        _ui.update { it.copy(vehicle = v, status = null, alerts = emptyList(), photoPath = settings.photoFor(v.vin)) }
        refresh()
    }

    // ---------------- several accounts / brands ----------------

    data class GarageAccount(val id: String, val brand: no.lionflow.app.model.Brand, val signedIn: Boolean, val cars: List<no.lionflow.app.api.Vehicle>, val selectedVin: String?, val active: Boolean)

    /** Every signed-in account with its cars, for the car list. */
    fun garage(): List<GarageAccount> {
        val active = settings.activeAccount
        val list = settings.accounts().map { a ->
            GarageAccount(a.id, no.lionflow.app.model.Brand.of(a.brand), api.isSignedInTo(a.id), if (a.id == active) _ui.value.vehicles else api.cachedVehiclesOf(a.id), api.selectedVinOf(a.id), a.id == active)
        }.filter { it.signedIn || it.active }
        // The same car can be on two accounts (shared with family) or listed twice: show each car once, from the open account first.
        val seen = HashSet<String>()
        return list.sortedByDescending { it.active }.map { acc -> acc.copy(cars = acc.cars.filter { seen.add(it.vin) }) }
            .filter { it.cars.isNotEmpty() || it.active }
    }

    /** Name of a car on any account. */
    fun garageName(acc: GarageAccount, v: no.lionflow.app.api.Vehicle): String =
        if (acc.active && v.vin == _ui.value.vehicle?.vin) settings.value.carName else settings.nameFor(v.vin) ?: (acc.brand.display + " …" + v.vin.takeLast(4))

    fun hasSeveralCars(): Boolean = garage().sumOf { it.cars.size } > 1

    /** Open the car [vin] on account [id] (switching account if needed). */
    fun switchTo(id: String, vin: String?) {
        if (isDemo) return
        if (id == settings.activeAccount && (vin == null || vin == _ui.value.vehicle?.vin)) return
        val app = getApplication<Application>() as no.lionflow.app.LionFlowApp
        val target = settings.accounts().firstOrNull { it.id == id } ?: return
        if (id == settings.activeAccount) { _ui.value.vehicles.firstOrNull { it.vin == vin }?.let { selectVehicle(it) }; return }
        ticker?.cancel()
        // An account that was started but never signed in is dropped when leaving it.
        if (!api.isSignedIn) settings.saveAccounts(settings.accounts().filter { it.id != settings.activeAccount })
        val entry = no.lionflow.app.model.Garage.allEntries(app).firstOrNull { it.accountId == id && (vin == null || it.vehicle.vin == vin) }
        if (entry != null) no.lionflow.app.model.Garage.activate(app, entry)
        else { api.switchProfile(id); settings.activeAccount = id; settings.update { it.copy(brand = target.brand, country = target.country) } }
        resyncFromStore()
    }

    /** Re-read the active car from storage (after the widget, watch or car screen switched it). */
    private fun resyncFromStore() {
        ticker?.cancel()
        _ui.value = UiState(photoPath = curPhoto(), serviceHistory = loadHistory())
        decideStartPhase()
    }

    /** Called when the app comes forward: another surface may have changed the car. */
    private fun syncIfSwitchedElsewhere() {
        if (isDemo || _ui.value.phase == Phase.SignedOut) return
        val v = api.cachedVehicle() ?: return
        if (v.vin != _ui.value.vehicle?.vin) resyncFromStore()
    }

    private fun curPhoto(): String? = api.cachedVehicle()?.vin?.let { settings.photoFor(it) }

    /** Start signing in to another account (maybe another brand). The current one stays signed in. */
    fun addAccount() {
        if (isDemo) return
        val list = settings.accounts()
        val id = "a" + (list.mapNotNull { it.id.removePrefix("a").toIntOrNull() }.maxOrNull() ?: 1).plus(1)
        _ui.value.vehicle?.let { settings.setNameFor(it.vin, settings.value.carName) }
        remote.disconnect(); ticker?.cancel()
        settings.saveAccounts(list.map { if (it.id == settings.activeAccount) it.copy(brand = settings.value.brand, country = settings.value.country) else it } + no.lionflow.app.data.SettingsStore.Account(id, settings.value.brand, settings.value.country))
        api.switchProfile(id)
        settings.activeAccount = id
        no.lionflow.app.widget.WidgetData.clear(getApplication<Application>())
        _ui.value = UiState(phase = Phase.SignedOut, photoPath = curPhoto(), serviceHistory = loadHistory())
    }

    /** True while adding another account: lets the sign-in screen offer "back to my cars". */
    fun canCancelSignIn(): Boolean = settings.accounts().any { it.id != settings.activeAccount && api.isSignedInTo(it.id) }

    fun cancelSignIn() {
        val other = settings.accounts().firstOrNull { it.id != settings.activeAccount && api.isSignedInTo(it.id) } ?: return
        switchTo(other.id, null)
    }

    fun vehicleName(v: no.lionflow.app.api.Vehicle): String =
        if (v.vin == _ui.value.vehicle?.vin) settings.value.carName else settings.nameFor(v.vin) ?: (no.lionflow.app.model.Brands.current.display + " …" + v.vin.takeLast(4))

    fun setMildHybridOverride(enabled: Boolean?) {
        val vehicle = _ui.value.vehicle ?: return
        if (settings.mildHybridOverride(vehicle.vin) == enabled) return
        val previouslyMildHybrid = api.isMildHybrid(vehicle)
        settings.setMildHybridOverride(vehicle.vin, enabled)
        updateMildHybridStatus(reloadTrips = previouslyMildHybrid != api.isMildHybrid(vehicle))
        if (enabled == null && vehicle.needsBranding && (api.profile to vehicle.vin) !in brandingAttempts) refresh()
    }

    private fun updateMildHybridStatus(reloadTrips: Boolean = true) {
        val current = _ui.value
        val vehicle = current.vehicle ?: return
        current.status?.let { previous ->
            val status = api.parseStatus(vehicle, previous.raw).copy(
                kmToService = previous.kmToService,
                daysToService = previous.daysToService,
            )
            _ui.update { it.copy(status = status, tab = if (Caps.tab(it.tab, status)) it.tab else Tab.Home) }
            no.lionflow.app.widget.WidgetData.save(getApplication(), status)
        }
        if (reloadTrips && current.trips.isNotEmpty() && !current.tripsLoading) loadTrips()
    }

    fun vehicleKind(v: Vehicle): String = v.kind(settings.mildHybridOverride(v.vin))

    /** Reload the car list (e.g. after adding a car in MyPeugeot). */
    fun reloadVehicles() = viewModelScope.launch {
        if (_ui.value.reloadingVehicles) return@launch
        _ui.update { it.copy(reloadingVehicles = true) }
        val profile = api.profile
        try {
            vehicleMetadataLock.withLock {
                if (profile != api.profile) return@withLock
                val all = try { api.vehicles(checkSelectedBranding = true) } catch (e: no.lionflow.app.api.ApiException) {
                    if (!e.noData) throw e
                    toast("${no.lionflow.app.model.Brands.current.display} says no car is available on this account for the country you chose")
                    return@withLock
                }
                if (profile != api.profile) return@withLock
                val vehicle = api.pick(all)
                vehicle?.let { brandingAttempts.add(profile to it.vin) }
                applyVehicleMetadata(all, vehicle)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (profile == api.profile) toast("Couldn't load your cars — ${no.lionflow.app.api.Http.describe(e)}")
        } finally {
            if (profile == api.profile) _ui.update { it.copy(reloadingVehicles = false) }
        }
    }

    private fun applyVehicleMetadata(all: List<Vehicle>, vehicle: Vehicle?) {
        val previous = _ui.value.vehicle
        _ui.update { it.copy(vehicles = all, vehicle = vehicle) }
        if (vehicle != null && previous?.vin == vehicle.vin && vehicleKind(vehicle) != vehicleKind(previous)) {
            updateMildHybridStatus()
        }
    }
    fun clearMessage() = _ui.update { it.copy(message = null) }
    private fun toast(m: String) = _ui.update { it.copy(message = m) }
    fun toastText(m: String) = toast(m)

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
                try { api.loadUserInfo() } catch (e: AuthExpiredException) { throw e } catch (_: Exception) {}
                // Some accounts answer "No vehicle found" (404 / 40400) when the car isn't available to third-party apps: not a failed sign-in.
                var none = false
                val all = try { api.vehicles() } catch (e: no.lionflow.app.api.ApiException) {
                    if (!e.noData) throw e
                    none = true; emptyList()
                }
                val v = api.pick(all)
                val cur = settings.activeAccount
                val accs = settings.accounts()
                settings.saveAccounts(if (accs.any { it.id == cur }) accs.map { if (it.id == cur) it.copy(brand = settings.value.brand, country = settings.value.country) else it }
                    else accs + no.lionflow.app.data.SettingsStore.Account(cur, settings.value.brand, settings.value.country))
                v?.let { no.lionflow.app.model.ChargeLog.setVin(it.vin); no.lionflow.app.model.ChargePower.setVin(it.vin) }
                _ui.update { it.copy(vehicle = v, vehicles = all, signingIn = false, phase = Phase.SetupRemote, photoPath = v?.vin?.let { vin -> settings.photoFor(vin) }) }
                if (v == null) toast(if (none) "Signed in, but ${no.lionflow.app.model.Brands.current.display} says no car is available on this account for the country you chose. If the car shows in ${no.lionflow.app.model.Brands.current.app}, check that the country matches the one in that app. Some newer cars can't be reached by other apps yet."
                    else "No car found on this account — add it in the MyPeugeot app first")
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
                if (api.customerId == null) {
                    try { api.loadUserInfo() } catch (_: Exception) {}
                    if (api.customerId == null) {
                        _ui.update { it.copy(busy = false, setupError = NO_REMOTE) }
                        return@launch
                    }
                }
                api.requestSmsCode()
                _ui.update { it.copy(busy = false, smsSent = true) }
            } catch (e: Exception) {
                val code = (e as? no.lionflow.app.api.ApiException)?.code
                _ui.update { it.copy(busy = false, setupError = if (code == 400 || code == 403) NO_REMOTE else "Couldn't send the SMS — ${no.lionflow.app.api.Http.describe(e)}") }
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

    // ---------------- demo ----------------

    /** Try the app without an account: a sample car with made-up data. */
    /**
     * [kind] is the sample car to show: "electric", "hybrid" (plug-in) or "fuel" (petrol or diesel). [brand] is the
     * brand id whose names the app uses while the demo runs (null keeps the one already picked).
     */
    fun startDemo(kind: String = "electric", brand: String? = null) {
        val app = getApplication<Application>()
        no.lionflow.app.demo.Demo.setOn(app, true)
        no.lionflow.app.demo.Demo.choose(app, kind)
        no.lionflow.app.demo.Demo.reset()
        settings.demoPrevName = settings.value.carName
        settings.update { it.copy(carName = "Demo car", brand = brand ?: it.brand) }
        showDemo()
    }

    private fun showDemo() {
        val d = no.lionflow.app.demo.Demo
        d.load(getApplication())
        no.lionflow.app.model.ChargeLog.enterDemo(getApplication(), d.VIN, d.sessions(), d.places())
        val now = System.currentTimeMillis()
        java.io.File(getApplication<Application>().cacheDir, "demo_photo.jpg").delete()
        val car = d.vehicle
        _ui.update { it.copy(phase = Phase.Ready, photoPath = null, vehicle = car, vehicles = listOf(car), status = d.status(), trips = d.trips(), lastRefresh = now, now = now) }
        startTicker()
        loadLocationAndWeather()
    }

    fun exitDemo() {
        val app = getApplication<Application>()
        no.lionflow.app.demo.Demo.setOn(app, false)
        no.lionflow.app.model.ChargeLog.exitDemo()
        settings.update { it.copy(carName = settings.demoPrevName.ifBlank { "My " + no.lionflow.app.model.Brands.current.display }) }
        ticker?.cancel()
        _ui.value = UiState(photoPath = curPhoto(), serviceHistory = loadHistory())
        decideStartPhase()
    }

    fun openRemoteSetup() = _ui.update { it.copy(phase = Phase.SetupRemote, smsSent = false, setupError = null) }

    fun signOut() {
        if (isDemo) { exitDemo(); return }
        remote.forget()
        api.signOut()
        settings.clearPersonal()
        no.lionflow.app.widget.WidgetData.clear(getApplication<Application>())
        ticker?.cancel()
        // Other accounts stay signed in: go to one of them, if there is one.
        val gone = settings.activeAccount
        val rest = settings.accounts().filter { it.id != gone }
        if (rest.isNotEmpty()) {
            settings.saveAccounts(rest)
            val next = rest.firstOrNull { api.isSignedInTo(it.id) } ?: rest.first()
            api.switchProfile(next.id); settings.activeAccount = next.id
            settings.update { it.copy(brand = next.brand, country = next.country) }
            _ui.value = UiState(photoPath = curPhoto(), serviceHistory = loadHistory())
            val v = api.cachedVehicle()
            if (v != null) { settings.update { it.copy(carName = settings.nameFor(v.vin) ?: ("My " + no.lionflow.app.model.Brand.of(next.brand).display)) }
                no.lionflow.app.model.ChargeLog.setVin(v.vin); no.lionflow.app.model.ChargePower.setVin(v.vin) }
            decideStartPhase()
            return
        }
        _ui.value = UiState(phase = Phase.SignedOut, photoPath = curPhoto(), serviceHistory = loadHistory())
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
        syncIfSwitchedElsewhere()
        checkForUpdate(force = false)
        if (_ui.value.needsInstallPermission && no.lionflow.app.update.Updater.canInstall(getApplication<Application>())) installUpdate()
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

    private var serverRetries = 0

    private suspend fun refreshVehicleBranding(vehicle: Vehicle, profile: String): Vehicle =
        vehicleMetadataLock.withLock {
            val selected = _ui.value.vehicle
            if (profile != api.profile || (selected != null && selected.vin != vehicle.vin)) return@withLock vehicle
            val current = selected ?: vehicle
            if (!current.needsBranding || settings.mildHybridOverride(current.vin) != null ||
                !brandingAttempts.add(profile to current.vin)) return@withLock current
            val all = try {
                api.vehicles(checkSelectedBranding = true)
            } catch (e: AuthExpiredException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@withLock current
            }
            val active = _ui.value.vehicle
            if (profile != api.profile || (active != null && active.vin != current.vin)) return@withLock current
            val updated = all.firstOrNull { it.vin == current.vin } ?: current
            applyVehicleMetadata(all, updated)
            updated
        }

    /**
     * The refresh button: reads the carmaker's server now, and (with remote control set up) asks the car itself for
     * a fresh report, then reads again when it has answered. This is the manual way to wake the car; "Wake car when
     * app opens" in Settings does the same by itself.
     */
    fun refreshNow() {
        refresh()
        if (isDemo || !remote.isSetUp || _ui.value.status?.moving == true) return
        viewModelScope.launch {
            remote.preWake(minIntervalMs = 60_000, force = true) { viewModelScope.launch { delay(4_000); refresh() } }
        }
    }

    private var alertsAt = 0L

    /** Warnings from the car: optional, so a failure here never disturbs the refresh. At most every 10 minutes. */
    private fun loadAlerts(v: Vehicle) {
        val now = System.currentTimeMillis()
        if (v.vin in alertsOff || (now - alertsAt < 10 * 60_000 && v.vin == alertsVin)) return
        alertsAt = now; alertsVin = v.vin
        val profile = api.profile
        viewModelScope.launch {
            try {
                val list = no.lionflow.app.model.CarAlert.parse(api.alerts(v))
                if (profile == api.profile && _ui.value.vehicle?.vin == v.vin) _ui.update { it.copy(alerts = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                remote.addLog("warnings: " + no.lionflow.app.api.Http.describe(e))
                // A car or account the server refuses this for isn't asked again until the app restarts.
                if (e is no.lionflow.app.api.ApiException && e.code in 400..499) alertsOff.add(v.vin)
            }
        }
    }
    private var alertsVin = ""
    private val alertsOff = HashSet<String>()

    fun refresh() {
        if (_ui.value.refreshing) return
        if (isDemo) {
            val now = System.currentTimeMillis()
            _ui.update { it.copy(status = no.lionflow.app.demo.Demo.status(), lastRefresh = now, now = now) }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            val profile = api.profile
            try {
                val cached = _ui.value.vehicle ?: api.currentVehicle() ?: throw IllegalStateException("No car on this account")
                val v = refreshVehicleBranding(cached, profile)
                if (profile != api.profile || _ui.value.vehicle?.let { it.vin != v.vin } == true) return@launch
                var missing = false
                val raw = try { api.status(v) } catch (e: no.lionflow.app.api.ApiException) {
                    if (!e.noData) throw e
                    missing = true
                    remote.addLog("status: the server has no live status for this car (${e.code})")
                    // No live status for this car: carry on with just what we know (trips, service) so the app is still usable.
                    api.blankStatus(v)
                }
                val maint = try { api.maintenance(v) } catch (e: no.lionflow.app.api.ApiException) { if (missing) null else throw e }
                // Reload cars can complete while the status request is in flight.
                val selected = _ui.value.vehicle ?: v
                if (profile != api.profile || selected.vin != v.vin) return@launch
                val st = withTripFuel(applyHolds(no.lionflow.app.model.ChargePower.record(api.parseStatus(selected, raw, maint))), _ui.value.trips)
                if (settings.value.smartMode.isNotEmpty() && !programsPending()) try { no.lionflow.app.work.SmartSchedule.syncFromCar(app(), st.programs) } catch (_: Exception) {}
                val now = System.currentTimeMillis()
                settings.lastRefresh = now
                serverRetries = 0
                no.lionflow.app.widget.WidgetData.save(getApplication<Application>(), st)
                _ui.update {
                    it.copy(vehicle = selected, status = st, statusMissing = missing, refreshing = false,
                        lastRefresh = now, now = now, tab = if (Caps.tab(it.tab, st)) it.tab else Tab.Home)
                }
                if (_ui.value.trips.isEmpty() && !_ui.value.tripsLoading) loadTrips()
                if (!missing) loadAlerts(selected)
                if (Caps.battery(st) && !st.preconditioningOn && no.lionflow.app.work.ChargeLimit.due(getApplication(), st)) viewModelScope.launch {
                    no.lionflow.app.work.ChargeLimit.check(getApplication(), st)?.let { toast(it); kotlinx.coroutines.delay(4000); refresh() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthExpiredException) {
                if (profile != api.profile) return@launch
                _ui.update { it.copy(refreshing = false) }
                toast("Your Peugeot session expired — please sign in again")
                signOut()
            } catch (e: Exception) {
                if (profile != api.profile) return@launch
                _ui.update { it.copy(refreshing = false) }
                val msg = no.lionflow.app.api.Http.describe(e)
                remote.addLog("refresh failed: $msg")
                val down = e is no.lionflow.app.api.ApiException && e.code in 502..504
                if (down) {
                    // The brand's servers are down or busy: say so once in plain words and try again by itself.
                    if (serverRetries == 0) toast(no.lionflow.app.ui.l10n.L10n.tr("Peugeot's servers are down or busy right now (error ${e.code}). Your car is fine. LionFlow will try again in a minute."))
                    if (serverRetries < 3) {
                        serverRetries++
                        viewModelScope.launch { kotlinx.coroutines.delay(60_000L * serverRetries); refresh() }
                    }
                } else toast("Couldn't update — $msg")
            } finally {
                if (_ui.value.refreshing && api.profile == profile) _ui.update { it.copy(refreshing = false) }
            }
        }
    }

    // ---------------- remote commands ----------------

    // What the user just asked for, kept until the car's status catches up (Peugeot's server lags behind).
    private var holdPrecond: Pair<Boolean, Long>? = null
    private var holdLock: no.lionflow.app.model.LockHold? = null

    private fun applyHolds(st: VehicleStatus): VehicleStatus {
        val now = System.currentTimeMillis()
        var out = st
        holdPrecond?.let { (want, until) ->
            if (now > until || st.preconditioningOn == want) holdPrecond = null
            else out = out.copy(preconditioningOn = want)
        }
        holdLock?.let { h ->
            if (h.holds(st, now)) out = out.copy(locked = h.want) else holdLock = null
        }
        return out
    }

    private fun refreshSoon() = viewModelScope.launch {
        for (d in listOf(3_000L, 30_000L, 90_000L)) { delay(d); refresh() }
    }

    fun runCommand(label: String, optimistic: (VehicleStatus) -> VehicleStatus = { it }, block: suspend (Tracker) -> CommandResult) {
        if (isDemo) {
            // Simulated: nothing is sent, the sample car just changes.
            if (_ui.value.commandInProgress != null) return
            viewModelScope.launch {
                _ui.update { it.copy(commandInProgress = label, commandProgress = "Sending…") }
                delay(1_200)
                no.lionflow.app.demo.Demo.apply(label)
                holdPrecond = null; holdLock = null
                _ui.update { s -> s.copy(commandInProgress = null, commandProgress = null, status = s.status?.let(optimistic)) }
                toast("$label — done (demo)")
            }
            return
        }
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
                        is CommandResult.Failed -> { holdPrecond = null; holdLock = null; remote.addLog("$label failed: ${r.reason}"); toast("$label didn't go through: ${r.reason}") }
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
                    refreshSoon()
                }
                is CommandResult.Failed -> {
                    holdPrecond = null; holdLock = null
                    _ui.update { it.copy(commandInProgress = null, commandProgress = null) }
                    remote.addLog("$label failed: ${r.reason}")
                    toast("$label: ${r.reason}")
                }
            }
        }
    }

    fun lockCar(lock: Boolean) = runCommand(if (lock) "Lock" else "Unlock", { holdLock = no.lionflow.app.model.LockHold(lock, System.currentTimeMillis()); it.copy(locked = lock) }) { p -> remote.lock(lock, p) }
    fun horn() = runCommand("Horn") { p -> remote.horn(p) }
    fun lights() = runCommand("Flash lights") { p -> remote.lights(p) }
    fun preconditioning(on: Boolean) {
        val st = _ui.value.status
        // Plug-in hybrids only pre-heat on the battery, and the car ignores the request below 50 % (as in the official app).
        if (on && !isDemo && st != null && st.energyType == "Hybrid" && (st.batteryPct ?: 100.0) < 50 && !st.plugged) {
            toast(no.lionflow.app.ui.l10n.L10n.tr("Plug-in hybrids can only pre-heat with at least 50 % battery. Charge the car first."))
            return
        }
        preconditioningSend(on)
    }

    private fun preconditioningSend(on: Boolean) = runCommand(if (on) "Pre-heating" else "Stop pre-heating", { holdPrecond = on to System.currentTimeMillis() + 300_000; it.copy(preconditioningOn = on) }) { p ->
        val st = _ui.value.status ?: return@runCommand CommandResult.Failed("No status yet")
        remote.preconditioning(on, st.programsJson(), p)
    }

    /** Saves pre-heating schedules to the car (its 4 program slots), keeping pre-heating on/off as it is. */
    fun savePrograms(programs: List<no.lionflow.app.model.Program>) = if (isDemo) {
        no.lionflow.app.demo.Demo.apply("", programs); runCommand("Saving schedule", { it.copy(programs = programs) }) { CommandResult.Success }
    } else runCommand("Saving schedule", { it.copy(programs = programs) }) { p ->
        val st = _ui.value.status ?: return@runCommand CommandResult.Failed("No status yet")
        val r = remote.preconditioning(st.preconditioningOn, VehicleStatus.programsJsonOf(programs), p)
        if (r !is CommandResult.Failed) no.lionflow.app.model.ChargePower.setPendingPrograms(programs)
        if (r !is CommandResult.Failed && settings.value.smartMode.isNotEmpty()) no.lionflow.app.work.SmartSchedule.setDesired(app(), programs)
        r
    }

    private fun app() = getApplication<Application>() as no.lionflow.app.LionFlowApp

    /** The schedules as the user set them. With Smart schedule on, the car may have some switched off. */
    fun userPrograms(st: VehicleStatus?): List<no.lionflow.app.model.Program> =
        (if (settings.value.smartMode.isNotEmpty()) no.lionflow.app.work.SmartSchedule.desired(app()) else null) ?: st?.programs.orEmpty()

    fun smartOffSlots(): Set<Int> = if (settings.value.smartMode.isEmpty() && !settings.value.smartPlugged) emptySet() else no.lionflow.app.work.SmartSchedule.switchedOff(app())
    fun smartWhy(slot: Int) = no.lionflow.app.work.SmartSchedule.why(app(), slot)

    /** Turn Smart schedule on/off or change its limit. */
    fun setSmart(mode: String, temp: Int) {
        val wasOn = settings.value.smartMode.isNotEmpty()
        if (mode.isNotEmpty() && !wasOn) no.lionflow.app.work.SmartSchedule.setDesired(app(), _ui.value.status?.programs.orEmpty())
        settings.update { it.copy(smartMode = mode, smartTemp = temp) }
        if (mode.isEmpty() && wasOn) {
            // Give the car the user's own schedules back.
            val want = no.lionflow.app.work.SmartSchedule.desired(app())
            val hadOff = no.lionflow.app.work.SmartSchedule.switchedOff(app()).isNotEmpty()
            no.lionflow.app.work.SmartSchedule.clear(app())
            if (want != null && hadOff) savePrograms(want)
        } else if (mode.isNotEmpty()) {
            no.lionflow.app.work.SmartSchedule.recheck(app())
        }
        no.lionflow.app.work.SmartSchedule.schedule(app())
    }

    /** Smart schedule: only pre-heat when the car is plugged in. */
    fun setSmartPlugged(on: Boolean) {
        settings.update { it.copy(smartPlugged = on) }
        no.lionflow.app.work.SmartSchedule.recheck(app())
        no.lionflow.app.work.SmartSchedule.schedule(app())
    }

    private val _parkedAt = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    /** "Home", a known charger's name, or a street address for where the car is parked. */
    val parkedAt: kotlinx.coroutines.flow.StateFlow<String?> = _parkedAt
    private var parkedKey = ""
    private val addressCache = HashMap<String, String>()

    fun updateParkedPlace(lat: Double?, lon: Double?) {
        if (lat == null || lon == null) { _parkedAt.value = null; return }
        val cfg = no.lionflow.app.model.ChargeLog.places.value
        cfg.home?.let { h -> if (h.lat != null && h.lon != null && no.lionflow.app.model.ChargeLog.distanceM(lat, lon, h.lat, h.lon) <= 50) { _parkedAt.value = "@Home"; return } }
        cfg.known.forEach { k -> if (k.lat != null && k.lon != null && no.lionflow.app.model.ChargeLog.distanceM(lat, lon, k.lat, k.lon) <= 75) { _parkedAt.value = "@" + k.name.ifBlank { "Charger" }; return } }
        val key = String.format(java.util.Locale.US, "%.4f,%.4f", lat, lon)   // ~10 m
        addressCache[key]?.let { _parkedAt.value = it; return }
        if (key == parkedKey) return
        parkedKey = key
        _parkedAt.value = null
        viewModelScope.launch {
            val a = try { no.lionflow.app.model.ChargeLog.reverseGeocode(lat, lon) } catch (_: Exception) { null }
            if (a != null) { addressCache[key] = a; _parkedAt.value = a } else parkedKey = ""
        }
    }

    /** True while schedules we sent haven't shown up in Peugeot's status yet. */
    fun programsPending(): Boolean = no.lionflow.app.model.ChargePower.pendingPrograms() != null

    /** The car's own limit: daily (stops at the limit set in the car) or trip (charge to 100 %). */
    fun setCarChargeLimit(daily: Boolean) {
        val status = _ui.value.status ?: return
        if (!Caps.battery(status)) return
        runCommand(if (daily) "Daily charge limit" else "Full charge for a trip",
            { it.copy(chargeType = if (daily) "Partial" else "Full") }) { p -> remote.chargeLimit(daily, p) }
    }

    fun charging(startNow: Boolean) {
        val status = _ui.value.status ?: return
        if (!Caps.battery(status)) return
        runCommand(if (startNow) "Charge now" else "Stop charging", { it.copy(chargingStatus = if (startNow) "InProgress" else "Stopped") }) { p ->
            val st = _ui.value.status
            // Charging past the car's limit: switch the car to 100 % first, or it stops again at its daily limit.
            val s = settings.value
            if (startNow && s.chargeLimitOn && s.chargeForce && st?.chargeType != "Full") {
                val r = remote.chargeLimit(false)
                remote.addLog("charge now: set the car to 100 % first → ${if (r is CommandResult.Failed) r.reason else "sent"}")
            }
            remote.charging(startNow, st?.nextDelayedHour ?: 1, st?.nextDelayedMinute ?: 0, p)
        }
    }

    /** Turns "only charge between" on or off, or changes its times, and tells the car when to start. */
    fun setChargeWindow(on: Boolean, from: Int, to: Int) {
        val status = _ui.value.status ?: return
        if (!Caps.battery(status)) return
        val was = settings.value
        settings.update { it.copy(chargeWindowOn = on, chargeWindowFrom = from, chargeWindowTo = to) }
        if (isDemo) return
        // Times changed while the window is off: nothing to tell the car (sending would set its scheduled start).
        if (!on && !was.chargeWindowOn) return
        viewModelScope.launch {
            val r = no.lionflow.app.work.ChargeWindow.applyToCar(getApplication())
            if (r is CommandResult.Failed) toast(no.lionflow.app.ui.l10n.L10n.tr("Couldn't send the charging times to the car") + ": " + r.reason)
        }
    }

    /**
     * Turns off the car's scheduled (delayed) start, so it charges as soon as it is plugged in. "Stop charging" (also
     * from the charge limit or charging times) works by setting that scheduled start, so it can stay on afterwards.
     */
    fun clearScheduledStart() = runCommand("Turn off scheduled start") { p ->
        val st = _ui.value.status
        remote.charging(true, st?.nextDelayedHour ?: 1, st?.nextDelayedMinute ?: 0, p)
    }

    fun wakeUp() = runCommand("Wake car") { p -> remote.wakeUp(p) }

    // ---------------- app updates ----------------

    /** Looks for a newer release each time the app opens (at most every 10 min); shows the update popup. */
    // A getter (not a lazy field): init runs checkForUpdate() before fields declared further down exist.
    val fromPlay: Boolean get() = no.lionflow.app.update.Updater.installedFromPlay(getApplication())

    /**
     * GitHub installs only, at most once a day: fetches a tiny empty file from the GitHub release. GitHub's own
     * download counter for that file is the "active devices" number in the README. Nothing is sent about you or the phone.
     */
    private fun countActiveDevice() {
        val day = (System.currentTimeMillis() / 86_400_000L).toString()
        if (settings.raw("lastPingDay") == day) return
        settings.setRaw("lastPingDay", day)
        viewModelScope.launch {
            try {
                no.lionflow.app.api.Http.text(okhttp3.Request.Builder()
                    .url("https://github.com/thocubeat/lionflow/releases/latest/download/ping.txt")
                    .header("User-Agent", no.lionflow.app.api.Http.USER_AGENT).get().build())
            } catch (_: Exception) { settings.setRaw("lastPingDay", "") }   // try again next time the app opens
        }
    }

    fun checkForUpdate(force: Boolean) {
        if (no.lionflow.app.BuildConfig.DEBUG) return // test/screenshot builds
        if (fromPlay) return // Google Play keeps this copy up to date
        countActiveDevice()
        if (!force && !settings.value.autoUpdate) return
        val now = System.currentTimeMillis()
        if (!force && now - settings.lastUpdateCheck < 10 * 60_000L) return
        settings.lastUpdateCheck = now
        viewModelScope.launch {
            try {
                val r = no.lionflow.app.update.Updater.latest()
                if (r != null && no.lionflow.app.update.Updater.isNewer(r)) {
                    val same = _ui.value.update?.tag == r.tag
                    _ui.update { it.copy(update = r, updateDismissed = if (same) it.updateDismissed && !force else false) }
                } else {
                    _ui.update { it.copy(update = null) }
                    if (force) toast("LionFlow is up to date (${no.lionflow.app.BuildConfig.VERSION_NAME})")
                }
            } catch (e: Exception) {
                if (force) toast("Couldn't check for updates — ${no.lionflow.app.api.Http.describe(e)}")
            }
        }
    }

    /**
     * Some petrol cars send no fuel level in their status, but their trips say how full the tank was at the end:
     * then the newest trip's number is shown (it is what the car reported when it was last switched off).
     */
    private fun withTripFuel(st: VehicleStatus, trips: List<no.lionflow.app.model.Trip>): VehicleStatus {
        if (st.energyType != "Fuel" || st.batteryPct != null) return st
        val t = trips.filter { it.fuelEndPct != null }.maxByOrNull { it.endMillis ?: it.startMillis ?: 0L } ?: return st
        return st.copy(batteryPct = t.fuelEndPct, fuelPct = t.fuelEndPct)
    }

    fun dismissUpdate() = _ui.update { it.copy(updateDismissed = true, needsInstallPermission = false, updateStatus = null) }

    fun installUpdate() {
        val r = _ui.value.update ?: return
        val ctx = getApplication<Application>()
        _ui.update { it.copy(updateDismissed = false) }
        if (!no.lionflow.app.update.Updater.canInstall(ctx)) {
            _ui.update { it.copy(needsInstallPermission = true, updateStatus = "One time: allow LionFlow to install updates, then come back") }
            return
        }
        if (_ui.value.updateStatus?.startsWith("Downloading") == true) return
        viewModelScope.launch {
            try {
                _ui.update { it.copy(needsInstallPermission = false, updateStatus = "Downloading ${r.name}…") }
                val f = no.lionflow.app.update.Updater.download(ctx, r) { p -> _ui.update { it.copy(updateStatus = "Downloading ${r.name}… $p %") } }
                if (!no.lionflow.app.update.Updater.sameSigner(ctx, f)) {
                    f.delete()
                    _ui.update { it.copy(updateStatus = "The download isn't signed with LionFlow's key — not installed") }
                    remote.addLog("update ${r.tag} rejected: signature mismatch")
                    return@launch
                }
                _ui.update { it.copy(updateStatus = "Installing ${r.name}…") }
                no.lionflow.app.update.Updater.result.value = null
                no.lionflow.app.update.Updater.install(ctx, f)
                // No answer from Android after a while, or it said no (seen on Xiaomi's HyperOS): use Android's own
                // installer screen instead, as when installing the file by hand.
                val said = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    no.lionflow.app.update.Updater.result.first { it != null }
                }
                if (said != "pending" && said != "done" && _ui.value.updateStatus?.startsWith("Installing") == true) {
                    remote.addLog("update ${r.tag}: ${said ?: "no answer after 30 s"} → Android's installer")
                    _ui.update { it.copy(updateStatus = "Opening Android's installer…") }
                    try { no.lionflow.app.update.installWithSystem(ctx, f) }
                    catch (e: Exception) { _ui.update { it.copy(updateStatus = "Update failed — ${no.lionflow.app.api.Http.describe(e)}") } }
                }
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

    private val chargerCache: File get() = File(getApplication<Application>().cacheDir, "chargers.json")

    /** Saved chargers for this area (within ~3 km), so the map fills instantly and survives busy servers. */
    private fun cachedChargers(src: String, lat: Double, lon: Double, maxAgeMs: Long): List<Charger>? = try {
        val j = JSONObject(chargerCache.readText())
        val near = Math.abs(j.getDouble("lat") - lat) < 0.03 && Math.abs(j.getDouble("lon") - lon) < 0.05
        if (j.optString("src") == src && near && System.currentTimeMillis() - j.getLong("time") < maxAgeMs)
            j.getJSONArray("items").objects().map { no.lionflow.app.api.chargerFromJson(it) } else null
    } catch (_: Exception) { null }

    private fun saveChargers(src: String, lat: Double, lon: Double, list: List<Charger>) {
        try {
            chargerCache.writeText(JSONObject().put("src", src).put("lat", lat).put("lon", lon).put("time", System.currentTimeMillis())
                .put("items", JSONArray(list.map { it.toJson() })).toString())
        } catch (_: Exception) { }
    }

    fun loadChargers(lat: Double? = null, lon: Double? = null) {
        val src = settings.value.chargerSource
        val key = no.lionflow.app.BuildConfig.NOBIL_KEY
        if (src == "nobil" && key.isEmpty()) {
            _ui.update { it.copy(chargerError = "NOBIL isn't available in this build — switch to OpenStreetMap") }
            return
        }
        val la = lat ?: _ui.value.myLat ?: _ui.value.status?.lat ?: return
        val lo = lon ?: _ui.value.myLon ?: _ui.value.status?.lon ?: return
        // Fresh enough (6 h) → no network at all.
        cachedChargers(src, la, lo, 6 * 3600_000L)?.let { list ->
            _ui.update { it.copy(chargers = list, chargerError = null) }
            return
        }
        // Show older saved chargers right away while fetching.
        cachedChargers(src, la, lo, 30L * 86_400_000L)?.let { list -> _ui.update { it.copy(chargers = list) } }
        viewModelScope.launch {
            try {
                val list = if (src == "nobil") NobilApi.near(key, la, lo) else no.lionflow.app.api.OsmChargers.near(la, lo)
                if (list.isNotEmpty()) saveChargers(src, la, lo, list)
                _ui.update { it.copy(chargers = list, chargerError = if (list.isEmpty()) "No chargers found nearby" else null) }
            } catch (e: Exception) {
                val haveOld = _ui.value.chargers.isNotEmpty()
                val short = when (e) {
                    is no.lionflow.app.api.OsmChargers.BusyException -> "Charger servers are busy"
                    is java.io.IOException -> "No connection"
                    else -> "Couldn't load chargers"
                }
                remote.addLog("chargers failed: ${no.lionflow.app.api.Http.describe(e)}")
                _ui.update { it.copy(chargerError = if (haveOld) "$short — showing saved chargers" else "$short — try again in a minute") }
            }
        }
    }

    // ---------------- trips ----------------

    fun reloadRoutes() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val r = no.lionflow.app.route.Routes.list(getApplication())
        _ui.update { it.copy(routes = r) }
    }

    fun deleteRoute(r: no.lionflow.app.route.Route) { no.lionflow.app.route.Routes.delete(r); reloadRoutes() }

    /** Releases for the changelog (newest first), loaded when needed. */
    var releases by androidx.compose.runtime.mutableStateOf<List<no.lionflow.app.update.Release>>(emptyList())
        private set
    /** Set after an update: the version that was last opened before it. The "What's new" popup shows what came since. */
    var whatsNewSince by androidx.compose.runtime.mutableStateOf<Int?>(null)
        private set

    fun loadReleases() {
        if (releases.isNotEmpty()) return
        viewModelScope.launch {
            releases = try { no.lionflow.app.update.Updater.releases() } catch (e: Exception) { emptyList() }
        }
    }

    /** On start: after an update, show what changed since the version last opened (not on a first install). */
    private fun checkWhatsNew() {
        val now = no.lionflow.app.BuildConfig.VERSION_CODE
        val last = settings.raw("lastSeenCode").toIntOrNull()
        settings.setRaw("lastSeenCode", now.toString())
        if (last != null && last < now) { whatsNewSince = last; loadReleases() }
    }

    fun dismissWhatsNew() { whatsNewSince = null }

    /** True when the brand's server said "no trips" on the first call: then the trips list explains it. */
    var tripsNone by androidx.compose.runtime.mutableStateOf(false)

    fun loadTrips(daysAsked: Int = 30) {
        val days = maxOf(daysAsked, tripsDays).coerceAtMost(1100)
        tripsDays = days
        reloadRoutes()
        if (isDemo) { _ui.update { it.copy(trips = no.lionflow.app.demo.Demo.trips(), tripsLoading = false) }; return }
        val v = _ui.value.vehicle ?: return
        viewModelScope.launch {
            _ui.update { it.copy(tripsLoading = true) }
            try {
                val res = api.allTrips(v, System.currentTimeMillis() - days * 86_400_000L)
                val vehicle = _ui.value.vehicle?.takeIf { it.vin == v.vin } ?: v
                val parsed = api.parseTrips(vehicle, res.trips)
                val battery = _ui.value.status?.takeIf { it.energyType != "Fuel" }?.let { no.lionflow.app.model.ChargePower.usableKwh(it) }
                val all = no.lionflow.app.model.TripEnergy.fill(parsed, battery, no.lionflow.app.model.CarTrips.list())
                val fromCar = parsed.count { (it.kwh ?: 0.0) > 0 }
                val worked = all.count { it.energyEstimated }
                if (parsed.isNotEmpty()) remote.addLog("trips: energy from the car $fromCar, worked out $worked, missing ${all.size - fromCar - worked}")
                tripsNone = res.noneFound
                if (tripsNone) remote.addLog("trips: the server says no trips were found")
                val newest = all.maxOfOrNull { it.startMillis ?: 0L } ?: 0L
                if (newest > 0) remote.addLog("trips: newest from Peugeot " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(newest)))
                remote.addLog("trips: ${all.size} from the last $days days")
                val imported = loadImportedTrips()
                val merged = withCarTrips((all + imported).distinctBy { it.id }).sortedByDescending { it.startMillis ?: 0L }
                _ui.update { it.copy(trips = merged, tripsLoading = false, status = it.status?.let { st -> withTripFuel(st, merged) }) }
                autoTagNow()
            } catch (e: Exception) {
                _ui.update { it.copy(tripsLoading = false, trips = withCarTrips(loadImportedTrips()).sortedByDescending { t -> t.startMillis ?: 0L }) }
                toast("Couldn't load trips — ${no.lionflow.app.api.Http.describe(e)}")
            }
        }
    }

    fun exportTripsCsv(): File {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "lionflow-trips.csv")
        val iso = SimpleDateFormat("yyyy-MM-dd HH:mm", no.lionflow.app.ui.l10n.L10n.locale)
        val sb = StringBuilder("id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag\n")
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
                tags.tagOf(t.id)?.name?.replace(Regex("[,\\r\\n\"]"), " ").orEmpty(),
            ).joinToString(",")).append('\n')
        }
        f.writeText(sb.toString())
        return f
    }

    /** Imports trips from a CSV or .myp file (LionFlow's own, or the official app's export). */
    fun importTripsCsv(text: String) {
        val rows = try { no.lionflow.app.model.TripImport.parse(text) } catch (e: Exception) { emptyList() }
        if (rows.isEmpty()) {
            toast(no.lionflow.app.ui.l10n.L10n.tr("Couldn't read any trips from that file. Send it to me on GitHub and I'll add support for it."))
            return
        }
        val arr = JSONArray(settings.importedTripsJson)
        val have = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }.toHashSet()
        var count = 0
        val tagged = HashMap<String, String>()
        for (r in rows) {
            val id = r.json.optString("id")
            if (id in have) continue
            have += id; arr.put(r.json); count++
            r.tag?.let { tagged[id] = it }
        }
        settings.importedTripsJson = arr.toString()
        if (tagged.isNotEmpty()) loadImportedTrips().forEach { t ->
            tagged[t.id]?.let { name -> tags.set(t, (tags.byName(name) ?: tags.create(name, tags.data.tags.size % no.lionflow.app.model.TripTags.COLORS)).id) }
        }
        toast("Imported $count trips")
        _ui.update { s -> s.copy(trips = (s.trips + loadImportedTrips()).distinctBy { it.id }.sortedByDescending { it.startMillis ?: 0L }) }
    }

    /** Adds the trips LionFlow logged from the car's own reports, where Peugeot's list or an import has none at that time. */
    private fun withCarTrips(list: List<Trip>): List<Trip> {
        val own = no.lionflow.app.model.CarTrips.list()
        if (own.isEmpty()) return list
        val slack = 20 * 60_000L
        val extra = own.filter { o ->
            val a = o.startMillis ?: return@filter false; val b = o.endMillis ?: a
            list.none { t -> val ta = t.startMillis ?: return@none false; val tb = t.endMillis ?: ta; ta <= b + slack && tb >= a - slack }
        }
        return list + extra
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
                // Demo mode gets its own throw-away photo; the real one is never touched or shown.
                val vin = _ui.value.vehicle?.vin
                val f = File(if (isDemo) ctx.cacheDir else ctx.filesDir, if (isDemo) "demo_photo.jpg" else "car_photo_${vin ?: "x"}.jpg")
                ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                if (!isDemo && vin != null) settings.setPhotoFor(vin, f.absolutePath)
                _ui.update { it.copy(photoPath = f.absolutePath + "?" + System.currentTimeMillis()) }
            } catch (e: Exception) {
                toast("Couldn't save the photo")
            }
        }
    }

    private companion object {
        const val NO_REMOTE = "Peugeot doesn't offer remote control for this car to LionFlow. Check that remote commands (like pre-heating) work in the MyPeugeot app and that remote services are activated there (they need a subscription from the car brand, see Settings). You can still skip and use LionFlow for status, charging and trips."
    }
}
