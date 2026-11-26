package no.lionflow.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val theme: String = "dark",          // dark | light | system
    val distanceUnit: String = "km",     // km | mi
    val tempUnit: String = "c",          // c | f
    val autoRefresh: Boolean = true,
    /** Tag trips like ones the user tagged before (same distance and speed). */
    val autoTagTrips: Boolean = false,
    /** Auto-tagging goes by where a trip started and ended (needs route tracking). */
    val autoTagRoute: Boolean = false,
    val bioUnlock: Boolean = true,
    val remoteConfirm: Boolean = true,
    val remoteBioOnly: Boolean = true,
    val rememberMe: Boolean = true,
    val country: String = "NO",
    val brand: String = "peugeot",       // peugeot | citroen | ds | opel | vauxhall
    val nobilKey: String = "",
    val notifyChargeDone: Boolean = true,
    val notifyChargeStopped: Boolean = true,
    val notifyPreheat: Boolean = true,
    val notifyUnlocked: Boolean = true,
    val notifyLowBattery: Boolean = true,
    val notifyService: Boolean = false,
    val carName: String = "My Peugeot",
    val wakeOnOpen: Boolean = true,
    /** Smart pre-heat schedule: "" = off, "below" = only when colder than smartTemp, "above" = only when warmer. */
    val smartMode: String = "",
    val smartTemp: Int = 0,
    /** Smart schedule: only pre-heat when the car is plugged in. */
    val smartPlugged: Boolean = false,
    /** Wake the car from the background every N hours. 0 = never (default). */
    val bgWakeHours: Int = 0,
    val autoUpdate: Boolean = true,
    val chargerSource: String = "osm", // osm (free, no key) | nobil (needs key)
    val language: String = "system",
    val timeFormat: String = "system",   // system | 24 | 12  // system | en | fr | de | es | nb
    val carActions: Boolean = true,     // remote actions from Android Auto
    val carVideo: Boolean = true,       // YouTube in Android Auto, only while parked
    val carVideoSync: Boolean = true,   // hold the picture back ~0.4 s so it lines up with the car's delayed audio
    val carPlain: Boolean = false,      // Android Auto's own tiles and lists instead of the drawn screens (cars without a touch screen)
    val chargeLimitOn: Boolean = false, // LionFlow stops charging at chargeLimitPct
    val chargeLimitPct: Int = 80,
    /** Car is set to charge to 100 % while LionFlow stops it at chargeLimitPct (lets it go past the car's own daily limit). */
    val chargeForce: Boolean = false,
    /** Only charge between two times (minutes after midnight). */
    val chargeWindowOn: Boolean = false,
    val chargeWindowFrom: Int = 22 * 60,
    val chargeWindowTo: Int = 6 * 60,
    val oilChangeMillis: Long = 0L,     // when the engine oil was last changed (plug-in hybrids): counts engine km since
    val serviceKm: Int = 0,             // service interval in km; 0 = default for the car type
    val serviceMonths: Int = 0,         // service interval in months; 0 = default
    val routeTracking: Boolean = false, // record routes with the phone's GPS while connected to the car over Bluetooth
    val routeBtAddr: String = "",       // the car's Bluetooth address
    val routeBtName: String = "",
    val batteryKwh: Double = 0.0,       // usable battery size for kW maths; 0 = use what the car reports (else 73)
    /** Home screens (see HomeLayout): the phone's cards in the user's order, and what is hidden on each screen. */
    val homeOrder: String = "",
    val homeHidden: String = HomeLayout.PHONE_HIDDEN,
    val watchHidden: String = HomeLayout.WATCH_HIDDEN,
    val carHidden: String = HomeLayout.CAR_HIDDEN,
    /** Notes at the top of Home: the car is being driven, privacy mode is on, fog lights left on. */
    val bannerDriving: Boolean = true,
    val bannerPrivacy: Boolean = true,
    val bannerFog: Boolean = true,
    val fuelPrice: Double = 0.0,        // price per litre, in the currency used for charging costs; 0 = not set
    val tankLiters: Double = 0.0,       // tank size for fill-up litres; 0 = worked out from the fuel gauge
)

/** Plain (non-secret) preferences, exposed as a StateFlow so the UI updates instantly. */
class SettingsStore internal constructor(private val p: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("lf_settings", Context.MODE_PRIVATE))
    private val _state = MutableStateFlow(withBrandName(load()).also { no.lionflow.app.model.Brands.current = no.lionflow.app.model.Brand.of(it.brand) })
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load() = AppSettings(
        theme = p.getString("theme", "dark") ?: "dark",
        distanceUnit = p.getString("dist", "km") ?: "km",
        tempUnit = p.getString("temp", "c") ?: "c",
        autoRefresh = p.getBoolean("auto", true),
        autoTagTrips = p.getBoolean("autoTagTrips", false),
        autoTagRoute = p.getBoolean("autoTagRoute", false),
        bioUnlock = p.getBoolean("bio", true),
        remoteConfirm = p.getBoolean("remoteConfirm", true),
        remoteBioOnly = p.getBoolean("remoteBio", true),
        rememberMe = p.getBoolean("remember", true),
        country = p.getString("country", "NO") ?: "NO",
        brand = p.getString("brand", "peugeot") ?: "peugeot",
        nobilKey = p.getString("nobil", "") ?: "",
        notifyChargeDone = p.getBoolean("nDone", true),
        notifyChargeStopped = p.getBoolean("nStop", true),
        notifyPreheat = p.getBoolean("nHeat", true),
        notifyUnlocked = p.getBoolean("nLock", true),
        notifyLowBattery = p.getBoolean("nLow", true),
        notifyService = p.getBoolean("nService", false),
        carName = p.getString("carName", "My Peugeot") ?: "My Peugeot",
        wakeOnOpen = p.getBoolean("wakeOnOpen", true),
        smartMode = p.getString("smartMode", "") ?: "",
        smartTemp = p.getInt("smartTemp", 0),
        smartPlugged = p.getBoolean("smartPlugged", false),
        bgWakeHours = p.getInt("bgWakeHours", 0),
        autoUpdate = p.getBoolean("autoUpdate", true),
        chargerSource = p.getString("chargerSource", "osm") ?: "osm",
        carActions = p.getBoolean("carActions", true),
        language = p.getString("language", "system") ?: "system",
        timeFormat = p.getString("timeFormat", "system") ?: "system",
        carVideo = p.getBoolean("carVideo", true),
        carVideoSync = p.getBoolean("carVideoSync", true),
        carPlain = p.getBoolean("carPlain", false),
        batteryKwh = p.getFloat("batteryKwh", 0f).toDouble(),
        chargeLimitOn = p.getBoolean("chargeLimitOn", false),
        chargeLimitPct = p.getInt("chargeLimitPct", 80),
        chargeForce = p.getBoolean("chargeForce", false),
        chargeWindowOn = p.getBoolean("chargeWindowOn", false),
        chargeWindowFrom = p.getInt("chargeWindowFrom", 22 * 60),
        chargeWindowTo = p.getInt("chargeWindowTo", 6 * 60),
        routeTracking = p.getBoolean("routeTracking", false),
        oilChangeMillis = p.getLong("oilChangeMillis", 0L),
        serviceKm = p.getInt("serviceKm", 0),
        serviceMonths = p.getInt("serviceMonths", 0),
        routeBtAddr = p.getString("routeBtAddr", "") ?: "",
        routeBtName = p.getString("routeBtName", "") ?: "",
        homeOrder = p.getString("homeOrder", "") ?: "",
        homeHidden = p.getString("homeHidden", HomeLayout.PHONE_HIDDEN) ?: HomeLayout.PHONE_HIDDEN,
        watchHidden = p.getString("watchHidden", HomeLayout.WATCH_HIDDEN) ?: HomeLayout.WATCH_HIDDEN,
        carHidden = p.getString("carHidden", HomeLayout.CAR_HIDDEN) ?: HomeLayout.CAR_HIDDEN,
        bannerDriving = p.getBoolean("bannerDriving", true),
        bannerPrivacy = p.getBoolean("bannerPrivacy", true),
        bannerFog = p.getBoolean("bannerFog", true),
        fuelPrice = p.getFloat("fuelPrice", 0f).toDouble(),
        tankLiters = p.getFloat("tankLiters", 0f).toDouble(),
    )

    fun mildHybridOverride(vin: String): Boolean? =
        p.getString("mildHybridOverride_$vin", null)?.toBooleanStrictOrNull()

    fun setMildHybridOverride(vin: String, enabled: Boolean?) {
        p.edit { putString("mildHybridOverride_$vin", enabled?.toString()) }
    }

    fun update(block: (AppSettings) -> AppSettings) {
        val s = withBrandName(block(_state.value))
        p.edit()
            .putString("theme", s.theme)
            .putString("dist", s.distanceUnit)
            .putString("temp", s.tempUnit)
            .putBoolean("auto", s.autoRefresh)
            .putBoolean("autoTagTrips", s.autoTagTrips)
            .putBoolean("autoTagRoute", s.autoTagRoute)
            .putBoolean("bio", s.bioUnlock)
            .putBoolean("remoteConfirm", s.remoteConfirm)
            .putBoolean("remoteBio", s.remoteBioOnly)
            .putBoolean("remember", s.rememberMe)
            .putString("country", s.country)
            .putString("brand", s.brand)
            .putString("nobil", s.nobilKey)
            .putBoolean("nDone", s.notifyChargeDone)
            .putBoolean("nStop", s.notifyChargeStopped)
            .putBoolean("nHeat", s.notifyPreheat)
            .putBoolean("nLock", s.notifyUnlocked)
            .putBoolean("nLow", s.notifyLowBattery)
            .putBoolean("nService", s.notifyService)
            .putString("carName", s.carName)
            .putBoolean("wakeOnOpen", s.wakeOnOpen)
            .putString("smartMode", s.smartMode)
            .putInt("smartTemp", s.smartTemp)
            .putBoolean("smartPlugged", s.smartPlugged)
            .putInt("bgWakeHours", s.bgWakeHours)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putString("chargerSource", s.chargerSource)
            .putBoolean("carActions", s.carActions)
            .putString("language", s.language)
            .putString("timeFormat", s.timeFormat)
            .putBoolean("carVideo", s.carVideo)
            .putBoolean("carVideoSync", s.carVideoSync)
            .putBoolean("carPlain", s.carPlain)
            .putFloat("batteryKwh", s.batteryKwh.toFloat())
            .putBoolean("chargeLimitOn", s.chargeLimitOn)
            .putInt("chargeLimitPct", s.chargeLimitPct)
            .putBoolean("chargeForce", s.chargeForce)
            .putBoolean("chargeWindowOn", s.chargeWindowOn)
            .putInt("chargeWindowFrom", s.chargeWindowFrom)
            .putInt("chargeWindowTo", s.chargeWindowTo)
            .putBoolean("routeTracking", s.routeTracking)
            .putLong("oilChangeMillis", s.oilChangeMillis)
            .putInt("serviceKm", s.serviceKm)
            .putInt("serviceMonths", s.serviceMonths)
            .putString("routeBtAddr", s.routeBtAddr)
            .putString("routeBtName", s.routeBtName)
            .putString("homeOrder", s.homeOrder)
            .putString("homeHidden", s.homeHidden)
            .putString("watchHidden", s.watchHidden)
            .putString("carHidden", s.carHidden)
            .putBoolean("bannerDriving", s.bannerDriving)
            .putBoolean("bannerPrivacy", s.bannerPrivacy)
            .putBoolean("bannerFog", s.bannerFog)
            .putFloat("fuelPrice", s.fuelPrice.toFloat())
            .putFloat("tankLiters", s.tankLiters.toFloat())
            .apply()
        no.lionflow.app.model.Brands.current = no.lionflow.app.model.Brand.of(s.brand)
        _state.value = s
    }

    // Small non-secret runtime values
    /** The user's own car name, kept while demo mode shows the sample car. */
    var demoPrevName: String
        get() = p.getString("demoPrevName", "") ?: ""
        set(v) { p.edit().putString("demoPrevName", v).apply() }

    var lastRefresh: Long
        get() = p.getLong("lastRefresh", 0L)
        set(v) { p.edit().putLong("lastRefresh", v).apply() }

    var lastUpdateCheck: Long
        get() = p.getLong("lastUpdateCheck", 0L)
        set(v) { p.edit().putLong("lastUpdateCheck", v).apply() }

    /** One photo per car. The photo from before this existed goes to the first car that asks for it. */
    fun photoFor(vin: String): String? {
        p.getString("photo_$vin", null)?.let { return it }
        val legacy = p.getString("photo", null) ?: return null
        val owner = p.getString("photoOwner", null)
        if (owner == null) { p.edit().putString("photoOwner", vin).putString("photo_$vin", legacy).apply(); return legacy }
        return if (owner == vin) legacy else null
    }
    fun setPhotoFor(vin: String, path: String) = p.edit().putString("photo_$vin", path).apply()

    /** Locally kept service history (the API only knows the next service). */
    var serviceHistoryJson: String
        get() = p.getString("serviceHistory", "[]") ?: "[]"
        set(v) { p.edit().putString("serviceHistory", v).apply() }

    /** Trip tags and which trip has which (see TripTags). */
    var tripTagsJson: String
        get() = p.getString("tripTags", "") ?: ""
        set(v) { p.edit().putString("tripTags", v).apply() }

    /** Trips imported from a CSV file. */
    var importedTripsJson: String
        get() = p.getString("importedTrips", "[]") ?: "[]"
        set(v) { p.edit().putString("importedTrips", v).apply() }

    /** Last status snapshot used by the background notifier to detect changes. */
    /** Schedules just sent to the car, shown until Peugeot's status catches up ({"time":..,"programs":[..]}). */
    fun raw(key: String): String = p.getString(key, "") ?: ""
    fun setRaw(key: String, v: String) { p.edit().putString(key, v).apply() }

    var pendingPrograms: String
        get() = p.getString("pendingPrograms", "") ?: ""
        set(v) { p.edit().putString("pendingPrograms", v).apply() }

    /** Start time of the charging session LionFlow already stopped at the limit (so it only stops once). */
    var limitStoppedFor: Long
        get() = p.getLong("limitStoppedFor", 0L)
        set(v) { p.edit().putLong("limitStoppedFor", v).apply() }

    /** Charge-limit attempts this session: "session,attempts,lastTryMillis,gaveUp". */
    var limitState: String
        get() = p.getString("limitState", "") ?: ""
        set(v) { p.edit().putString("limitState", v).apply() }

    fun nameFor(vin: String): String? = p.getString("carName_$vin", null)?.let { brandName(it, no.lionflow.app.model.Brands.current) }

    /**
     * The name a car gets before anyone names it is "My Peugeot" (the app started as a Peugeot app). On a Citroën,
     * DS, Opel or Vauxhall account that untouched name becomes "My Vauxhall" and so on; a name typed by the user stays.
     */
    private fun withBrandName(s: AppSettings): AppSettings {
        val name = brandName(s.carName, no.lionflow.app.model.Brand.of(s.brand))
        return if (name == s.carName) s else s.copy(carName = name)
    }

    private fun brandName(name: String, brand: no.lionflow.app.model.Brand): String =
        if (name == "My Peugeot" && brand != no.lionflow.app.model.Brand.PEUGEOT) "My " + brand.display else name
    fun setNameFor(vin: String, name: String) = p.edit().putString("carName_$vin", name).apply()

    var notifySnapshot: String
        get() = p.getString("notifySnapshot", "{}") ?: "{}"
        set(v) { p.edit().putString("notifySnapshot", v).apply() }

    /** Signed-in accounts (one per carmaker login). The first one has id "" so its stored keys keep working. */
    data class Account(val id: String, val brand: String, val country: String)

    fun accounts(): List<Account> {
        val raw = p.getString("accounts", null)
        if (raw == null) return listOf(Account("", _state.value.brand, _state.value.country))
        val a = org.json.JSONArray(raw)
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Account(o.getString("id"), o.getString("brand"), o.getString("country")) } }
    }
    fun saveAccounts(list: List<Account>) {
        val a = org.json.JSONArray(); list.forEach { a.put(org.json.JSONObject().put("id", it.id).put("brand", it.brand).put("country", it.country)) }
        p.edit().putString("accounts", a.toString()).apply()
    }
    var activeAccount: String
        get() = p.getString("activeAccount", "") ?: ""
        set(v) { p.edit().putString("activeAccount", v).apply() }

    fun clearPersonal() {
        p.edit().remove("lastRefresh").remove("notifySnapshot").apply()
    }
}
