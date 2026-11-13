package no.lionflow.app.api

import okhttp3.HttpUrl.Companion.toHttpUrl
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
        val req = Request.Builder().url(url).header("User-Agent", Http.USER_AGENT).get().build()
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
