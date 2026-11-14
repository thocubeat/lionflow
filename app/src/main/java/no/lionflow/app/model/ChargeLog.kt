package no.lionflow.app.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.lionflow.app.api.Http
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A place where you charge: Home or one of up to 5 known chargers. Price per kWh (0 = free). */
data class ChargePlace(
    val id: String,          // "home" or "k1".."k5"
    val name: String,
    val address: String,
    val lat: Double?,
    val lon: Double?,
    val price: Double,       // per kWh, 0 = free
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("address", address)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL).put("price", price)

    companion object {
        fun fromJson(j: JSONObject) = ChargePlace(
            j.optString("id"), j.optString("name"), j.optString("address"),
            if (j.isNull("lat")) null else j.optDouble("lat"), if (j.isNull("lon")) null else j.optDouble("lon"),
            j.optDouble("price", 0.0),
        )
    }
}

data class PlacesConfig(
    val home: ChargePlace? = null,
    val known: List<ChargePlace> = emptyList(),   // up to 5
    val publicAcPrice: Double = 0.0,              // estimate for unknown AC chargers, 0 = don't estimate
    val publicDcPrice: Double = 0.0,              // estimate for unknown fast chargers
    val currency: String = "kr",
)

/** One charging session, built from the car's own status reports. */
data class ChargeSession(
    val id: Long,
    val start: Long,
    val end: Long,
    val startPct: Double,
    val endPct: Double,
    val kwh: Double,
    val peakKw: Double?,
    val mode: String?,        // Slow (AC) | Quick (DC)
    val lat: Double?,
    val lon: Double?,
    val address: String?,     // looked up from the car's position
    val source: String?,      // charger name/operator if a known public charger is right there
) {
    val hours: Double get() = (end - start) / 3_600_000.0
    val avgKw: Double? get() = if (hours > 0.05) kwh / hours else null

    fun toJson(): JSONObject = JSONObject().put("id", id).put("start", start).put("end", end)
        .put("startPct", startPct).put("endPct", endPct).put("kwh", kwh)
        .put("peakKw", peakKw ?: JSONObject.NULL).put("mode", mode ?: JSONObject.NULL)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("address", address ?: JSONObject.NULL).put("source", source ?: JSONObject.NULL)

    companion object {
        fun fromJson(j: JSONObject) = ChargeSession(
            j.getLong("id"), j.getLong("start"), j.getLong("end"), j.getDouble("startPct"), j.getDouble("endPct"),
            j.getDouble("kwh"), if (j.isNull("peakKw")) null else j.optDouble("peakKw"),
            if (j.isNull("mode")) null else j.optString("mode"),
            if (j.isNull("lat")) null else j.optDouble("lat"), if (j.isNull("lon")) null else j.optDouble("lon"),
            if (j.isNull("address")) null else j.optString("address"), if (j.isNull("source")) null else j.optString("source"),
        )
    }
}

/** Where a session happened and what it cost. */
data class PlaceMatch(val kind: String, val label: String, val price: Double?)   // kind: home | known | public | unknown

/**
 * Charging log. The car has no charging history API, so LionFlow watches its status reports
 * (app, widget, Android Auto and a background check every ~10 min while charging) and turns
 * "charging started → stopped" into sessions. Everything stays on the phone.
 */
object ChargeLog {
    private var prefs: SharedPreferences? = null
    private var cacheDir: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
    val sessions: StateFlow<List<ChargeSession>> = _sessions

    private val _active = MutableStateFlow<ChargeSession?>(null)
    /** The session in progress (end = last report so far). */
    val active: StateFlow<ChargeSession?> = _active

    private val _places = MutableStateFlow(PlacesConfig())
    val places: StateFlow<PlacesConfig> = _places

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("charge_log", Context.MODE_PRIVATE)
        cacheDir = ctx.cacheDir
        _sessions.value = loadSessions()
        _active.value = loadActive()?.let { activeToSession(it) }
        _places.value = loadPlaces()
        // Fill in any addresses that couldn't be looked up earlier (offline).
        _sessions.value.filter { it.address == null && it.lat != null }.take(3).forEach { lookupAddress(it.id) }
    }

    // ---------------- session detection ----------------

    @Synchronized
    fun observe(st: VehicleStatus) {
        val p = prefs ?: return
        val pct = st.batteryPct ?: return
        val t = st.energyAtMillis ?: st.updatedAtMillis ?: System.currentTimeMillis()
        var a = loadActive()

        // A session left open for over a day (phone off, car asleep) is closed first.
        if (a != null && t - a.getLong("lastT") > 24 * 3_600_000L) { finish(a, null, null); a = null }

        if (st.isCharging) {
            val kw = ChargePower.reading(st)?.takeIf { it.measured }?.kw
            if (a == null) {
                a = JSONObject().put("start", t).put("startPct", pct).put("startKwh", st.residualKwh ?: JSONObject.NULL)
                    .put("lat", st.lat ?: JSONObject.NULL).put("lon", st.lon ?: JSONObject.NULL)
                    .put("mode", st.chargingMode ?: JSONObject.NULL).put("peak", kw ?: JSONObject.NULL)
                    .put("usable", ChargePower.usableKwh(st))
            } else {
                if (a.isNull("lat") && st.lat != null) a.put("lat", st.lat).put("lon", st.lon)
                if (kw != null && (a.isNull("peak") || kw > a.optDouble("peak"))) a.put("peak", kw)
                if (st.chargingMode == "Quick") a.put("mode", "Quick")
            }
            if (t >= a.optLong("lastT", 0L)) a.put("lastT", t).put("lastPct", pct).put("lastKwh", st.residualKwh ?: JSONObject.NULL)
            p.edit().putString("active", a.toString()).apply()
            _active.value = activeToSession(a)
        } else if (a != null) {
            // Charging stopped. If this report shows a higher level (finished while we weren't looking), use it.
            val higher = pct > a.optDouble("lastPct") && t > a.getLong("lastT")
            finish(a, if (higher) pct else null, if (higher) t else null, if (higher) st.residualKwh else null)
        }
    }

    private fun finish(a: JSONObject, endPct: Double?, endT: Long?, endKwh: Double? = null) {
        val p = prefs ?: return
        p.edit().remove("active").apply()
        _active.value = null
        val s = activeToSession(a, endPct, endT, endKwh)
        if (s.kwh < 0.3 || s.end - s.start < 2 * 60_000L && s.endPct - s.startPct < 1) return
        val list = (listOf(s) + loadSessions()).take(500)
        save(list)
        lookupAddress(s.id)
    }

    private fun activeToSession(a: JSONObject, endPct: Double? = null, endT: Long? = null, endKwh: Double? = null): ChargeSession {
        val startPct = a.getDouble("startPct")
        val lastPct = endPct ?: a.optDouble("lastPct", startPct)
        val startKwh = if (a.isNull("startKwh")) null else a.optDouble("startKwh")
        val lastKwh = endKwh ?: if (a.isNull("lastKwh")) null else a.optDouble("lastKwh")
        val usable = a.optDouble("usable", 73.0)
        val kwh = if (startKwh != null && lastKwh != null && lastKwh > startKwh) lastKwh - startKwh
        else ((lastPct - startPct) / 100.0 * usable).coerceAtLeast(0.0)
        val lat = if (a.isNull("lat")) null else a.optDouble("lat")
        val lon = if (a.isNull("lon")) null else a.optDouble("lon")
        val near = if (lat != null && lon != null) nearbyCharger(lat, lon) else null
        return ChargeSession(
            id = a.getLong("start"), start = a.getLong("start"), end = endT ?: a.optLong("lastT", a.getLong("start")),
            startPct = startPct, endPct = lastPct, kwh = kwh,
            peakKw = if (a.isNull("peak")) null else a.optDouble("peak"),
            mode = if (a.isNull("mode")) null else a.optString("mode"),
            lat = lat, lon = lon, address = null, source = near,
        )
    }

    // ---------------- places ----------------

    fun match(s: ChargeSession, cfg: PlacesConfig = _places.value): PlaceMatch {
        val lat = s.lat; val lon = s.lon
        if (lat != null && lon != null) {
            cfg.home?.let { h -> if (h.lat != null && h.lon != null && distanceM(lat, lon, h.lat, h.lon) <= 50) return PlaceMatch("home", "Home", h.price) }
            cfg.known.forEach { k -> if (k.lat != null && k.lon != null && distanceM(lat, lon, k.lat, k.lon) <= 75) return PlaceMatch("known", k.name.ifBlank { "Known charger" }, k.price) }
        }
        val dc = s.mode == "Quick"
        val price = (if (dc) cfg.publicDcPrice else cfg.publicAcPrice).takeIf { it > 0 }
        val label = s.source ?: s.address ?: if (lat == null) "Unknown place" else "Away"
        return PlaceMatch(if (lat == null) "unknown" else "public", label, price)
    }

    fun savePlaces(cfg: PlacesConfig) {
        _places.value = cfg
        prefs?.edit()?.putString("places", JSONObject()
            .put("home", cfg.home?.toJson() ?: JSONObject.NULL)
            .put("known", JSONArray(cfg.known.map { it.toJson() }))
            .put("publicAc", cfg.publicAcPrice).put("publicDc", cfg.publicDcPrice).put("currency", cfg.currency)
            .toString())?.apply()
    }

    private fun loadPlaces(): PlacesConfig = try {
        val j = JSONObject(prefs?.getString("places", null) ?: return PlacesConfig())
        val ka = j.optJSONArray("known") ?: JSONArray()
        PlacesConfig(
            home = j.optJSONObject("home")?.let { ChargePlace.fromJson(it) },
            known = (0 until ka.length()).map { ChargePlace.fromJson(ka.getJSONObject(it)) },
            publicAcPrice = j.optDouble("publicAc", 0.0), publicDcPrice = j.optDouble("publicDc", 0.0),
            currency = j.optString("currency", "kr"),
        )
    } catch (_: Exception) { PlacesConfig() }

    // ---------------- storage ----------------

    private fun loadSessions(): List<ChargeSession> = try {
        val a = JSONArray(prefs?.getString("sessions", "[]") ?: "[]")
        (0 until a.length()).map { ChargeSession.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    private fun loadActive(): JSONObject? = try { prefs?.getString("active", null)?.let { JSONObject(it) } } catch (_: Exception) { null }

    @Synchronized
    private fun save(list: List<ChargeSession>) {
        _sessions.value = list
        prefs?.edit()?.putString("sessions", JSONArray(list.map { it.toJson() }).toString())?.apply()
    }

    fun delete(id: Long) = save(loadSessions().filter { it.id != id })

    fun clearAll() {
        prefs?.edit()?.remove("sessions")?.remove("active")?.apply()
        _sessions.value = emptyList(); _active.value = null
    }

    // ---------------- addresses ----------------

    private fun lookupAddress(id: Long) {
        scope.launch {
            val s = _sessions.value.firstOrNull { it.id == id } ?: return@launch
            val lat = s.lat ?: return@launch; val lon = s.lon ?: return@launch
            val addr = try { reverseGeocode(lat, lon) } catch (_: Exception) { null } ?: return@launch
            save(loadSessions().map { if (it.id == id) it.copy(address = addr) else it })
        }
    }

    /** OpenStreetMap Nominatim (free). Returns "Street 12, City". */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=$lat&lon=$lon"
        val j = JSONObject(Http.text(Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()))
        val a = j.optJSONObject("address") ?: return@withContext j.optString("display_name").takeIf { it.isNotBlank() }
        val street = listOf(a.optString("road"), a.optString("house_number")).filter { it.isNotBlank() }.joinToString(" ")
        val city = listOf("city", "town", "village", "municipality", "suburb").map { a.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
        listOf(street, city).filter { it.isNotBlank() }.joinToString(", ").ifBlank { j.optString("display_name") }
    }

    /** Address → coordinates (Nominatim). */
    suspend fun geocode(address: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + URLEncoder.encode(address, "UTF-8")
        val a = JSONArray(Http.text(Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).build()))
        if (a.length() == 0) null else a.getJSONObject(0).let { it.getString("lat").toDouble() to it.getString("lon").toDouble() }
    }

    /** Name of a public charger within 120 m, from the chargers LionFlow already loaded for the map. */
    private fun nearbyCharger(lat: Double, lon: Double): String? = try {
        val j = JSONObject(File(cacheDir, "chargers.json").readText())
        val items = j.getJSONArray("items")
        var best: Pair<Double, String>? = null
        for (i in 0 until items.length()) {
            val c = items.getJSONObject(i)
            val d = distanceM(lat, lon, c.optDouble("lat"), c.optDouble("lon"))
            if (d <= 120 && (best == null || d < best.first)) best = d to c.optString("name")
        }
        best?.second?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    fun distanceM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val r = 6_371_000.0
        val dLa = Math.toRadians(la2 - la1); val dLo = Math.toRadians(lo2 - lo1)
        val h = sin(dLa / 2).let { it * it } + cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }
}
