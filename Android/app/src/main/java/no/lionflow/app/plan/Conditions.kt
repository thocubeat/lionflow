package no.lionflow.app.plan

import no.lionflow.app.api.Http
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlinx.coroutines.async
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The weather at one place along the route, at the time the car gets there. */
data class WeatherSample(val km: Double, val tempC: Double, val windMs: Double, val windFromDeg: Double)

/**
 * What the road and the weather do to the battery, for every stretch of a route:
 * a factor for the cold (or heat), and extra kWh for hills and wind.
 */
class Conditions(
    /** For the stretch before each route point: factor on the normal use. */
    val tempMul: DoubleArray,
    /** For the stretch before each route point: extra kWh (hills, wind); can be negative downhill or with a tailwind. */
    val extraKwh: DoubleArray,
    val climbM: Double,
    val descentM: Double,
    val tMin: Double?,
    val tMax: Double?,
    val windMax: Double?,
) {
    val hasHills: Boolean get() = climbM > 0 || descentM > 0
    val hasWeather: Boolean get() = tMin != null
}

object ConditionsModel {
    /** kWh per metre climbed (2.2 t car, drivetrain losses) and recovered per metre descended (regeneration). */
    const val UP = 0.0067
    const val DOWN = 0.0044
    /**
     * The car's own trips already contain an average amount of hills. Take that average off per km, so only
     * what is steeper or flatter than usual, and the net climb or descent, is added.
     */
    const val USUAL_PER_KM = 0.0136
    private const val CD_A = 0.7          // drag area of a large family SUV, m²
    private const val DRIVE_EFF = 0.9

    /** Cold costs range (heating, battery chemistry) and so does heat (air conditioning). */
    fun tempMul(t: Double): Double = when {
        t < 15.0 -> 1.0 + min(0.013 * (15.0 - t), 0.45)
        t > 24.0 -> 1.0 + min(0.008 * (t - 24.0), 0.15)
        else -> 1.0
    }

    /** Direction of travel in degrees (0 = north) between two points. */
    fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val dy = la2 - la1
        val dx = (lo2 - lo1) * cos(Math.toRadians((la1 + la2) / 2))
        return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
    }

    /**
     * kWh per km extra for driving at [speedKmh] into a head wind of [headMs] (negative = tail wind).
     * Wind near the road is weaker than the 10 m forecast: counted at 60 %.
     */
    fun windKwhPerKm(speedKmh: Double, headMs: Double): Double {
        val v = speedKmh / 3.6
        val w = headMs * 0.6
        val rel = v + w
        val force = 0.5 * 1.25 * CD_A * (rel * abs(rel) - v * v)      // newtons
        return force * 1000.0 / 3.6e6 / DRIVE_EFF
    }

    /**
     * @param elevKm distance along the route of each height sample, @param elev the heights (m); either may be null
     */
    fun build(r: RouteData, elevKm: DoubleArray?, elev: DoubleArray?, weather: List<WeatherSample>): Conditions? {
        val n = r.size
        if (n < 2) return null
        val hills = elevKm != null && elev != null && elev.size >= 2 && elevKm.size == elev.size
        if (!hills && weather.isEmpty()) return null
        val tm = DoubleArray(n) { 1.0 }
        val extra = DoubleArray(n)
        var climb = 0.0; var desc = 0.0
        var k = 0
        val ws = weather.sortedBy { it.km }
        for (i in 1 until n) {
            val dk = r.km[i] - r.km[i - 1]
            if (dk <= 0) continue
            val mid = (r.km[i] + r.km[i - 1]) / 2
            if (hills) {
                val ek = elevKm!!; val ev = elev!!
                while (k < ek.size - 2 && ek[k + 1] <= mid) k++
                val len = (ek[k + 1] - ek[k]).coerceAtLeast(0.1)
                val dh = ev[k + 1] - ev[k]
                val frac = (dk / len).coerceAtMost(1.0)
                extra[i] += (if (dh > 0) dh * UP else dh * DOWN) * frac - USUAL_PER_KM * dk
                if (dh > 0) climb += dh * frac else desc -= dh * frac
            }
            if (ws.isNotEmpty()) {
                val w = ws.minByOrNull { abs(it.km - mid) }!!
                tm[i] = tempMul(w.tempC)
                val heading = bearing(r.lat[i - 1], r.lon[i - 1], r.lat[i], r.lon[i])
                val head = w.windMs * cos(Math.toRadians(w.windFromDeg - heading))
                extra[i] += windKwhPerKm(r.speed[i], head) * dk
            }
        }
        return Conditions(
            tm, extra, climb, desc,
            ws.minOfOrNull { it.tempC }, ws.maxOfOrNull { it.tempC }, ws.maxOfOrNull { it.windMs },
        )
    }
}

/** Gets heights and the forecast along a route from Open-Meteo (free, no key). */
object ConditionsApi {
    private fun num(v: Double) = String.format(Locale.US, "%.4f", v)

    /** Heights every few km along the route: distance along the route and metres above sea level. */
    suspend fun elevation(r: RouteData): Pair<DoubleArray, DoubleArray>? {
        val spacing = max(3.0, r.totalKm / 300.0)
        val idx = ArrayList<Int>()
        var last = -1e9
        for (i in 0 until r.size) if (r.km[i] - last >= spacing) { idx.add(i); last = r.km[i] }
        if (idx.last() != r.size - 1) idx.add(r.size - 1)
        if (idx.size < 2) return null
        val out = ArrayList<Double>()
        for (chunk in idx.chunked(100)) {
            val url = "https://api.open-meteo.com/v1/elevation?latitude=" + chunk.joinToString(",") { num(r.lat[it]) } +
                "&longitude=" + chunk.joinToString(",") { num(r.lon[it]) }
            val j = Http.json(Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT).get().build()) as? JSONObject ?: return null
            out.addAll(parseElevation(j) ?: return null)
        }
        if (out.size != idx.size) return null
        return DoubleArray(idx.size) { r.km[idx[it]] } to out.toDoubleArray()
    }

    fun parseElevation(j: JSONObject): List<Double>? {
        val a = j.optJSONArray("elevation") ?: return null
        return (0 until a.length()).map { a.optDouble(it, Double.NaN) }.takeIf { l -> l.none { it.isNaN() } }
    }

    /** The forecast at about every 50 km, for the hour the car gets there. Empty when the trip is too far ahead. */
    suspend fun weather(r: RouteData, departMs: Long): List<WeatherSample> {
        if (!forecastPossible(departMs)) return emptyList()
        val step = max(50.0, r.totalKm / 12.0)
        val idx = ArrayList<Int>()
        var last = -1e9
        for (i in 0 until r.size) if (r.km[i] - last >= step) { idx.add(i); last = r.km[i] }
        if (idx.isEmpty()) return emptyList()
        val url = "https://api.open-meteo.com/v1/forecast?latitude=" + idx.joinToString(",") { num(r.lat[it]) } +
            "&longitude=" + idx.joinToString(",") { num(r.lon[it]) } +
            "&hourly=temperature_2m,wind_speed_10m,wind_direction_10m&wind_speed_unit=ms&timeformat=unixtime&forecast_days=7"
        val body = Http.json(Request.Builder().url(url).header("User-Agent", Http.CONTACT_AGENT).get().build())
        val times = idx.map { departMs + (r.min[it] * 60_000.0).toLong() }
        return parseWeather(body, idx.map { r.km[it] }, times)
    }

    /** Open-Meteo answers with one object for one place and a list of them for several. */
    fun parseWeather(body: Any?, kms: List<Double>, timesMs: List<Long>): List<WeatherSample> {
        val items: List<JSONObject> = when (body) {
            is JSONArray -> (0 until body.length()).mapNotNull { body.optJSONObject(it) }
            is JSONObject -> listOf(body)
            else -> emptyList()
        }
        val out = ArrayList<WeatherSample>()
        for ((n, item) in items.withIndex()) {
            val h = item.optJSONObject("hourly") ?: continue
            val t = h.optJSONArray("time") ?: continue
            val temp = h.optJSONArray("temperature_2m") ?: continue
            val ws = h.optJSONArray("wind_speed_10m") ?: continue
            val wd = h.optJSONArray("wind_direction_10m") ?: continue
            if (n >= kms.size || t.length() == 0) continue
            val want = timesMs[n] / 1000L
            var best = 0; var bestD = Long.MAX_VALUE
            for (k in 0 until t.length()) { val d = abs(t.optLong(k) - want); if (d < bestD) { bestD = d; best = k } }
            val tc = temp.optDouble(best, Double.NaN); val w = ws.optDouble(best, Double.NaN); val d = wd.optDouble(best, Double.NaN)
            if (tc.isNaN() || w.isNaN() || d.isNaN()) continue
            out.add(WeatherSample(kms[n], tc, w, d))
        }
        return out
    }

    /**
     * Heights and forecast for a route, fetched side by side; null when neither could be had.
     * The forecast is asked twice if the first try fails (Open-Meteo sometimes drops the first call).
     */
    suspend fun load(r: RouteData, departMs: Long): Conditions? = kotlinx.coroutines.coroutineScope {
        val elJob = async {
            r.elevation ?: try { kotlinx.coroutines.withTimeoutOrNull(14_000L) { elevation(r) } }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        }
        val wJob = async {
            var w: List<WeatherSample>? = null
            for (attempt in 0..1) {
                w = try { kotlinx.coroutines.withTimeoutOrNull(7_000L) { weather(r, departMs) } }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                if (!w.isNullOrEmpty() || !forecastPossible(departMs)) break
                kotlinx.coroutines.delay(1_200L)
            }
            w.orEmpty()
        }
        val el = elJob.await()
        if (el != null) r.elevation = el
        ConditionsModel.build(r, el?.first, el?.second, wJob.await())
    }

    fun forecastPossible(departMs: Long): Boolean = departMs <= System.currentTimeMillis() + 6L * 86_400_000L
}
