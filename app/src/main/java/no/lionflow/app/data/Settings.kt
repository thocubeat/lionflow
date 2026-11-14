package no.lionflow.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val theme: String = "dark",          // dark | light | system
    val distanceUnit: String = "km",     // km | mi
    val tempUnit: String = "c",          // c | f
    val autoRefresh: Boolean = true,
    val bioUnlock: Boolean = true,
    val remoteConfirm: Boolean = true,
    val remoteBioOnly: Boolean = true,
    val rememberMe: Boolean = true,
    val country: String = "NO",
    val nobilKey: String = "",
    val notifyChargeDone: Boolean = true,
    val notifyChargeStopped: Boolean = true,
    val notifyPreheat: Boolean = true,
    val notifyUnlocked: Boolean = true,
    val notifyLowBattery: Boolean = true,
    val notifyService: Boolean = false,
    val carName: String = "My Peugeot",
    val wakeOnOpen: Boolean = true,
    val autoUpdate: Boolean = true,
    val chargerSource: String = "osm", // osm (free, no key) | nobil (needs key)
    val carActions: Boolean = true,     // remote actions from Android Auto
    val carVideo: Boolean = true,       // YouTube in Android Auto, only while parked
    val batteryKwh: Double = 0.0,       // usable battery size for kW maths; 0 = use what the car reports (else 73)
)

/** Plain (non-secret) preferences, exposed as a StateFlow so the UI updates instantly. */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("lf_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load() = AppSettings(
        theme = p.getString("theme", "dark") ?: "dark",
        distanceUnit = p.getString("dist", "km") ?: "km",
        tempUnit = p.getString("temp", "c") ?: "c",
        autoRefresh = p.getBoolean("auto", true),
        bioUnlock = p.getBoolean("bio", true),
        remoteConfirm = p.getBoolean("remoteConfirm", true),
        remoteBioOnly = p.getBoolean("remoteBio", true),
        rememberMe = p.getBoolean("remember", true),
        country = p.getString("country", "NO") ?: "NO",
        nobilKey = p.getString("nobil", "") ?: "",
        notifyChargeDone = p.getBoolean("nDone", true),
        notifyChargeStopped = p.getBoolean("nStop", true),
        notifyPreheat = p.getBoolean("nHeat", true),
        notifyUnlocked = p.getBoolean("nLock", true),
        notifyLowBattery = p.getBoolean("nLow", true),
        notifyService = p.getBoolean("nService", false),
        carName = p.getString("carName", "My Peugeot") ?: "My Peugeot",
        wakeOnOpen = p.getBoolean("wakeOnOpen", true),
        autoUpdate = p.getBoolean("autoUpdate", true),
        chargerSource = p.getString("chargerSource", "osm") ?: "osm",
        carActions = p.getBoolean("carActions", true),
        carVideo = p.getBoolean("carVideo", true),
        batteryKwh = p.getFloat("batteryKwh", 0f).toDouble(),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        p.edit()
            .putString("theme", s.theme)
            .putString("dist", s.distanceUnit)
            .putString("temp", s.tempUnit)
            .putBoolean("auto", s.autoRefresh)
            .putBoolean("bio", s.bioUnlock)
            .putBoolean("remoteConfirm", s.remoteConfirm)
            .putBoolean("remoteBio", s.remoteBioOnly)
            .putBoolean("remember", s.rememberMe)
            .putString("country", s.country)
            .putString("nobil", s.nobilKey)
            .putBoolean("nDone", s.notifyChargeDone)
            .putBoolean("nStop", s.notifyChargeStopped)
            .putBoolean("nHeat", s.notifyPreheat)
            .putBoolean("nLock", s.notifyUnlocked)
            .putBoolean("nLow", s.notifyLowBattery)
            .putBoolean("nService", s.notifyService)
            .putString("carName", s.carName)
            .putBoolean("wakeOnOpen", s.wakeOnOpen)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putString("chargerSource", s.chargerSource)
            .putBoolean("carActions", s.carActions)
            .putBoolean("carVideo", s.carVideo)
            .putFloat("batteryKwh", s.batteryKwh.toFloat())
            .apply()
        _state.value = s
    }

    // Small non-secret runtime values
    var lastRefresh: Long
        get() = p.getLong("lastRefresh", 0L)
        set(v) { p.edit().putLong("lastRefresh", v).apply() }

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0L)
        set(v) { p.edit().putLong("lastUpdateCheck", v).apply() }

    var photoPath: String?
        get() = p.getString("photo", null)
        set(v) { p.edit().putString("photo", v).apply() }

    /** Locally kept service history (the API only knows the next service). */
    var serviceHistoryJson: String
        get() = p.getString("serviceHistory", "[]") ?: "[]"
        set(v) { p.edit().putString("serviceHistory", v).apply() }

    /** Trips imported from a CSV file. */
    var importedTripsJson: String
        get() = p.getString("importedTrips", "[]") ?: "[]"
        set(v) { p.edit().putString("importedTrips", v).apply() }

    /** Last status snapshot used by the background notifier to detect changes. */
    /** Schedules just sent to the car, shown until Peugeot's status catches up ({"time":..,"programs":[..]}). */
    var pendingPrograms: String
        get() = p.getString("pendingPrograms", "") ?: ""
        set(v) { p.edit().putString("pendingPrograms", v).apply() }

    var notifySnapshot: String
        get() = p.getString("notifySnapshot", "{}") ?: "{}"
        set(v) { p.edit().putString("notifySnapshot", v).apply() }

    fun clearPersonal() {
        p.edit().remove("lastRefresh").remove("notifySnapshot").apply()
    }
}
