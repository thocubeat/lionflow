package no.lionflow.app.api

import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ---------------- Weather: MET Norway Locationforecast (free, no key) ----------------

data class WeatherHour(val timeMillis: Long, val tempC: Double, val symbol: String)
data class Weather(
    val tempC: Double,
    val windMs: Double,
    val symbol: String,
    val hours: List<WeatherHour>,
    val tomorrowMorningC: Double?,
) {
    val feelsLikeC: Double
        get() {
            // Wind chill (valid for t <= 10 °C and wind > 1.3 m/s), otherwise the air temperature.
            val v = windMs * 3.6
            return if (tempC <= 10 && v > 4.8) {
                13.12 + 0.6215 * tempC - 11.37 * Math.pow(v, 0.16) + 0.3965 * tempC * Math.pow(v, 0.16)
            } else tempC
        }
}

object WeatherApi {
    suspend fun forecast(lat: Double, lon: Double): Weather {
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact".toHttpUrl().newBuilder()
            .addQueryParameter("lat", String.format(Locale.US, "%.4f", lat))
            .addQueryParameter("lon", String.format(Locale.US, "%.4f", lon))
            .build()
        val req = Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT).get().build()
        val j = Http.json(req) as JSONObject
        val series = j.obj("properties")?.optJSONArray("timeseries") ?: JSONArray()
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val hours = series.objects().mapNotNull { t ->
            val time = try { iso.parse(t.getString("time"))?.time } catch (_: Exception) { null } ?: return@mapNotNull null
            val details = t.obj("data", "instant", "details") ?: return@mapNotNull null
            val sym = t.obj("data", "next_1_hours", "summary")?.str("symbol_code")
                ?: t.obj("data", "next_6_hours", "summary")?.str("symbol_code") ?: "cloudy"
            WeatherHour(time, details.optDouble("air_temperature"), sym) to details.optDouble("wind_speed", 0.0)
        }
        if (hours.isEmpty()) throw ApiException(0, "No forecast data")
        val now = System.currentTimeMillis()
        val upcoming = hours.filter { it.first.timeMillis >= now - 3600_000 }
        val first = upcoming.firstOrNull() ?: hours.first()
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 7); set(java.util.Calendar.MINUTE, 0)
        }
        val tomorrow = hours.minByOrNull { kotlin.math.abs(it.first.timeMillis - cal.timeInMillis) }
            ?.takeIf { kotlin.math.abs(it.first.timeMillis - cal.timeInMillis) < 3 * 3600_000 }
        return Weather(
            tempC = first.first.tempC,
            windMs = first.second,
            symbol = first.first.symbol,
            hours = upcoming.map { it.first }.take(12),
            tomorrowMorningC = tomorrow?.first?.tempC,
        )
    }

    fun describe(symbol: String): String = when {
        symbol.startsWith("clearsky") -> "Clear"
        symbol.startsWith("fair") -> "Fair"
        symbol.startsWith("partlycloudy") -> "Partly cloudy"
        symbol.startsWith("cloudy") -> "Cloudy"
        symbol.startsWith("fog") -> "Fog"
        symbol.contains("thunder") -> "Thunder"
        symbol.contains("sleet") -> "Sleet"
        symbol.contains("snow") -> "Snow"
        symbol.contains("rain") -> "Rain"
        else -> "Cloudy"
    }

    fun kind(symbol: String): String = when {
        symbol.contains("snow") || symbol.contains("sleet") -> "snow"
        symbol.contains("rain") || symbol.contains("thunder") -> "rain"
        symbol.startsWith("clearsky") || symbol.startsWith("fair") -> "sun"
        else -> "cloud"
    }
}

// ---------------- Chargers: NOBIL (free API key from info.nobil.no/api) ----------------

data class Charger(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val maxKw: Double,
    val points: Int,
    val address: String,
    val distanceM: Int?,
)

object NobilApi {
    /** Fast chargers (50 kW and up) inside a box, with how many charge points are free right now when NOBIL knows. */
    suspend fun box(apiKey: String, south: Double, west: Double, north: Double, east: Double): List<no.lionflow.app.plan.RawCharger> {
        val url = "https://nobil.no/api/server/search.php".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", apiKey)
            .addQueryParameter("apiversion", "3")
            .addQueryParameter("action", "search")
            .addQueryParameter("type", "rectangle")
            .addQueryParameter("northeast", "($north,$east)")
            .addQueryParameter("southwest", "($south,$west)")
            .addQueryParameter("limit", "500")
            .addQueryParameter("format", "json")
            .build()
        val j = Http.json(Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).get().build()) as? JSONObject ?: return emptyList()
        val stations = j.optJSONArray("chargerstations") ?: return emptyList()
        return stations.objects().mapNotNull { s ->
            val c = s.optJSONObject("csmd") ?: return@mapNotNull null
            val pos = c.optString("Position").trim('(', ')').split(",")
            val la = pos.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val lo = pos.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            var maxKw = 0.0
            s.optJSONObject("attr")?.optJSONObject("conn")?.let { conn ->
                for (key in conn.keys()) {
                    val cap = conn.optJSONObject(key)?.optJSONObject("5")?.optString("trans").orEmpty()
                    val kw = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*kW").find(cap)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                    if (kw != null && kw > maxKw) maxKw = kw
                }
            }
            if (maxKw < 50.0) return@mapNotNull null
            // Only stations open to the public (NOBIL "Availability": 1 = public); bus depots and private ones are left out.
            val avail = s.optJSONObject("attr")?.optJSONObject("st")?.optJSONObject("2")?.optString("attrvalid").orEmpty()
            if (avail.isNotEmpty() && avail != "1") return@mapNotNull null
            if (no.lionflow.app.plan.PlanParse.notForCars(c.optString("name") + " " + c.optString("Owned_by"))) return@mapNotNull null
            // Real-time: only trust a count when NOBIL says this station reports live status.
            var free: Int? = null
            val live = c.optString("Real_time_information").trim().lowercase()
            if (live !in setOf("2", "no", "false", "0")) {
                for (k in c.keys()) if (k.contains("avail", ignoreCase = true)) c.optString(k).trim().toIntOrNull()?.let { free = it }
            }
            val owner = c.optString("Owned_by").trim()
            no.lionflow.app.plan.RawCharger(
                "nobil-${c.optString("id")}", c.optString("name").ifBlank { owner }.ifBlank { "Charging station" }, owner,
                la, lo, maxKw, c.optInt("Number_charging_points", 0), "", free,
            )
        }
    }

    suspend fun near(apiKey: String, lat: Double, lon: Double, limit: Int = 40, distanceM: Int = 15000): List<Charger> {
        val url = "https://nobil.no/api/server/search.php".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", apiKey)
            .addQueryParameter("apiversion", "3")
            .addQueryParameter("action", "search")
            .addQueryParameter("type", "near")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("long", lon.toString())
            .addQueryParameter("distance", distanceM.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("format", "json")
            .build()
        val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).get().build()
        val j = Http.json(req) as? JSONObject ?: return emptyList()
        val stations = j.optJSONArray("chargerstations") ?: return emptyList()
        return stations.objects().mapNotNull { s ->
            val c = s.optJSONObject("csmd") ?: return@mapNotNull null
            val pos = c.optString("Position").trim('(', ')').split(",")
            val la = pos.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            val lo = pos.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
            var maxKw = 0.0
            // attr.conn holds the connectors; attribute "5" is the charging capacity, e.g. "50 kW - 400VDC max 125A"
            s.optJSONObject("attr")?.optJSONObject("conn")?.let { conn ->
                for (key in conn.keys()) {
                    val connector = conn.optJSONObject(key) ?: continue
                    val cap = connector.optJSONObject("5")?.optString("trans").orEmpty()
                    val kw = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*kW").find(cap)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
                    if (kw != null && kw > maxKw) maxKw = kw
                }
            }
            Charger(
                id = c.optString("id"),
                name = c.optString("name"),
                lat = la, lon = lo,
                maxKw = maxKw,
                points = c.optInt("Number_charging_points", 0),
                address = listOf(c.optString("Street"), c.optString("House_number"), c.optString("City")).filter { it.isNotBlank() }.joinToString(" "),
                distanceM = null,
            )
        }
    }
}


// ---------------- Chargers: OpenStreetMap via Overpass (free, no key) ----------------

object OsmChargers {
    /** Free public Overpass servers, tried in order — the main one is often busy (HTTP 429). */
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    class BusyException : Exception("The free charger servers are busy right now")

    suspend fun near(lat: Double, lon: Double, radiusM: Int = 12000, limit: Int = 80, amenity: String = "charging_station"): List<Charger> {
        var last: Exception? = null
        for (url in ENDPOINTS) {
            try {
                return query(url, lat, lon, radiusM, limit, amenity)
            } catch (e: ApiException) {
                last = e
                if (e.code != 429 && e.code < 500) throw e
            } catch (e: java.io.IOException) {
                last = e
            }
        }
        throw if (last is ApiException || last == null) BusyException() else last
    }

    /**
     * Asks a server whether it has room right now: seconds to wait (0 = free), or null when it doesn't answer at all.
     * Takes at most 3 s, so a dead or busy server is known before any real search is started.
     */
    private suspend fun probe(url: String): Int? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val c = Http.client.newBuilder().callTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
            val req = Request.Builder().url(url.replace("/interpreter", "/status")).header("User-Agent", Http.CONTACT_AGENT).get().build()
            c.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use 5
                val t = resp.body?.string().orEmpty()
                when {
                    t.contains("available now") -> 0
                    else -> Regex("in (\\d+) seconds").find(t)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 20) ?: 5
                }
            }
        } catch (e: Exception) { null }
    }

    /** Runs an Overpass query on the first free server that answers. */
    suspend fun raw(q: String): JSONObject {
        // 1. Check the servers first (3 s): which are up, and which have room right now.
        val waits = kotlinx.coroutines.coroutineScope { ENDPOINTS.map { u -> async { u to probe(u) } }.awaitAll() }
        val usable = waits.filter { it.second != null }.sortedBy { it.second }
        if (usable.isEmpty()) throw java.io.IOException("No internet connection, or all the free charger servers are down")
        // 2. Ask the free ones at once and the others a little later; the first good answer wins.
        val result = kotlinx.coroutines.CompletableDeferred<JSONObject>()
        val failed = java.util.concurrent.atomic.AtomicInteger(0)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        usable.forEachIndexed { i, (url, wait) ->
            scope.launch {
                kotlinx.coroutines.delay((wait ?: 0) * 1000L + i * 1500L)
                if (result.isCompleted) return@launch
                try {
                    val req = Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT)
                        .post(okhttp3.FormBody.Builder().add("data", q).build()).build()
                    val j = Http.json(req) as? JSONObject ?: JSONObject()
                    // A server that ran out of time answers "OK" with a remark and no data.
                    if (j.optString("remark").isNotBlank() && (j.optJSONArray("elements")?.length() ?: 0) == 0) throw java.io.IOException("server gave up")
                    result.complete(j)
                } catch (e: Exception) {
                    if (failed.incrementAndGet() == usable.size) result.completeExceptionally(BusyException())
                }
            }
        }
        return try {
            kotlinx.coroutines.withTimeout(40_000L) { result.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw BusyException()
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private suspend fun query(endpoint: String, lat: Double, lon: Double, radiusM: Int, limit: Int, amenity: String): List<Charger> {
        val q = "[out:json][timeout:25];(" +
            "node[\"amenity\"=\"$amenity\"](around:$radiusM,$lat,$lon);" +
            "way[\"amenity\"=\"$amenity\"](around:$radiusM,$lat,$lon);" +
            ");out center $limit;"
        val req = Request.Builder().url(endpoint)
            .header("User-Agent", Http.USER_AGENT)
            .post(okhttp3.FormBody.Builder().add("data", q).build())
            .build()
        val j = Http.json(req) as? JSONObject ?: return emptyList()
        val els = j.optJSONArray("elements") ?: return emptyList()
        return els.objects().mapNotNull { e ->
            val la = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
            val lo = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
            val t = e.optJSONObject("tags") ?: JSONObject()
            var maxKw = 0.0
            for (k in t.keys()) {
                if (k.endsWith(":output") || k == "maxpower" || k == "charging_station:output") {
                    val v = t.optString(k).lowercase()
                    val num = Regex("([0-9]+(?:[.,][0-9]+)?)").find(v)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull() ?: continue
                    val kw = when {
                        v.contains("mw") -> num * 1000
                        v.contains("kw") -> num
                        num > 1000 -> num / 1000 // plain watts
                        else -> num
                    }
                    if (kw > maxKw) maxKw = kw
                }
            }
            val name = listOf("name", "operator", "brand", "network").map { t.optString(it) }.firstOrNull { it.isNotBlank() } ?: (if (amenity == "fuel") "Fuel station" else "Charging station")
            Charger(
                id = "osm-${e.optString("type")}-${e.optLong("id")}",
                name = name,
                lat = la, lon = lo,
                maxKw = maxKw,
                points = t.optString("capacity").toIntOrNull() ?: 0,
                address = listOf(t.optString("addr:street"), t.optString("addr:housenumber"), t.optString("addr:city")).filter { it.isNotBlank() }.joinToString(" "),
                distanceM = null,
            )
        }
    }
}


fun Charger.toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("lat", lat).put("lon", lon)
    .put("kw", maxKw).put("points", points).put("address", address)

fun chargerFromJson(j: JSONObject) = Charger(
    id = j.optString("id"), name = j.optString("name"), lat = j.optDouble("lat"), lon = j.optDouble("lon"),
    maxKw = j.optDouble("kw", 0.0), points = j.optInt("points"), address = j.optString("address"), distanceM = null,
)
