package no.lionflow.app.plan

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** An address or point the user can drive from or to. */
data class Place(val name: String, val detail: String, val lat: Double, val lon: Double, val country: String = "")

/** A car ferry on the route: the quay it leaves from and arrives at, the crossing time, and where along the route it starts. */
data class Ferry(val name: String, val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double, val minutes: Double, val idx: Int, val endIdx: Int = -1)

/** A driving route: every point with the distance and driving time up to it, and the speed on the stretch before it. */
class RouteData(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val km: DoubleArray,
    val min: DoubleArray,
    val speed: DoubleArray,
) {
    /** Car ferries on the way (their crossing time is already in [min]). */
    var ferries: List<Ferry> = emptyList()
    val size: Int get() = lat.size
    val totalKm: Double get() = if (size == 0) 0.0 else km[size - 1]
    val totalMin: Double get() = if (size == 0) 0.0 else min[size - 1]

    /** There and back on the same road: the route followed by itself in reverse (ferries included). */
    fun roundTrip(): RouteData {
        val n = size
        if (n < 2) return this
        val m = 2 * n - 1
        val la = DoubleArray(m); val lo = DoubleArray(m); val k = DoubleArray(m); val t = DoubleArray(m); val v = DoubleArray(m)
        for (i in 0 until n) { la[i] = lat[i]; lo[i] = lon[i]; k[i] = km[i]; t[i] = min[i]; v[i] = speed[i] }
        for (j in n until m) {
            val o = m - 1 - j
            la[j] = lat[o]; lo[j] = lon[o]
            k[j] = k[j - 1] + (km[o + 1] - km[o]); t[j] = t[j - 1] + (min[o + 1] - min[o]); v[j] = speed[o + 1]
        }
        val r = RouteData(la, lo, k, t, v)
        r.ferries = ferries + ferries.map { f ->
            val e = if (f.endIdx > f.idx) f.endIdx else f.idx + 1
            f.copy(aLat = f.bLat, aLon = f.bLon, bLat = f.aLat, bLon = f.aLon, idx = (m - 1 - e).coerceAtLeast(0), endIdx = m - 1 - f.idx)
        }
        return r
    }

    /** The part of the route from point [from] to point [to] (inclusive), as a route of its own. */
    fun slice(from: Int, to: Int) = RouteData(
        lat.copyOfRange(from, to + 1), lon.copyOfRange(from, to + 1), km.copyOfRange(from, to + 1),
        min.copyOfRange(from, to + 1), speed.copyOfRange(from, to + 1),
    )

    /** Energy used up to each point (kWh) for a car with this consumption factor. */
    fun energy(factor: Double): DoubleArray {
        val e = DoubleArray(size)
        val c = conditions
        // On a ferry the car is parked: no driving energy.
        val onBoat = BooleanArray(size)
        for (f in ferries) if (f.endIdx > f.idx) for (i in (f.idx + 1)..f.endIdx.coerceAtMost(size - 1)) onBoat[i] = true
        for (i in 1 until size) {
            if (onBoat[i]) { e[i] = e[i - 1]; continue }
            val base = (km[i] - km[i - 1]) * Consumption.shape(speed[i]) / 100.0 * factor
            // Cold (or heat) scales the use; hills and wind add or take off. A steep downhill can give energy back (regeneration).
            val seg = if (c == null) base else base * c.tempMul[i] + c.extraKwh[i]
            e[i] = e[i - 1] + seg
        }
        return e
    }

    /** What hills, wind and temperature do on this route (null until looked up, or when that failed). */
    var conditions: Conditions? = null
    /** The hour of departure [conditions] was looked up for. */
    var conditionsKey: Long = Long.MIN_VALUE
    /** Heights along the route (distance, metres); the same whatever the departure time. */
    var elevation: Pair<DoubleArray, DoubleArray>? = null

    companion object {
        /**
         * @param segM metres per stretch between two points (or null: measured from the coordinates)
         * @param segS seconds per stretch (or null: the total time spread evenly)
         * @param totalSec the route's driving time, which also counts turns and junctions
         */
        fun build(lat: DoubleArray, lon: DoubleArray, segM: DoubleArray?, segS: DoubleArray?, totalSec: Double): RouteData {
            val n = lat.size
            val km = DoubleArray(n); val mins = DoubleArray(n); val speed = DoubleArray(n)
            if (n < 2) return RouteData(lat, lon, km, mins, speed)
            val useSeg = segM != null && segS != null && segM.size == n - 1 && segS.size == n - 1
            val d = DoubleArray(n - 1) { i -> if (useSeg) segM!![i] / 1000.0 else Geo.km(lat[i], lon[i], lat[i + 1], lon[i + 1]) }
            val total = d.sum().coerceAtLeast(0.001)
            val rawSec = if (useSeg) segS!!.sum() else 0.0
            val avg = if (totalSec > 0) total / (totalSec / 3600.0) else 70.0
            // Stretch times leave out junctions and turns: stretch them to the route's own total.
            val scale = if (useSeg && rawSec > 0 && totalSec > 0) (totalSec / rawSec).coerceIn(1.0, 1.4) else 1.0
            for (i in 1 until n) {
                val dk = d[i - 1]
                val v = if (useSeg && segS!![i - 1] > 0.05) (dk / (segS[i - 1] / 3600.0)).coerceIn(10.0, 150.0) else avg.coerceIn(10.0, 150.0)
                km[i] = km[i - 1] + dk
                mins[i] = mins[i - 1] + (if (useSeg) segS!![i - 1] * scale else dk / avg * 3600.0) / 60.0
                speed[i] = v
            }
            speed[0] = speed[1]
            return RouteData(lat, lon, km, mins, speed)
        }
    }
}

object Geo {
    /** Distance in km; fine for the short distances it is used for. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 110.57
        val dx = (lon2 - lon1) * 111.32 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** The route point closest to a place: its index and how far off the route the place is (km). */
    fun project(r: RouteData, lat: Double, lon: Double): Pair<Int, Double> {
        val cosLat = cos(Math.toRadians(lat)) * 111.32
        var best = 0; var bestD = Double.MAX_VALUE
        for (i in 0 until r.size) {
            val dy = (r.lat[i] - lat) * 110.57
            if (abs(dy) > bestD) continue
            val dx = (r.lon[i] - lon) * cosLat
            val d = dx * dx + dy * dy
            if (d < bestD * bestD) { bestD = sqrt(d); best = i }
        }
        return best to bestD
    }

    /** Google/OSRM encoded polyline. Returns lat and lon arrays. */
    fun decodePolyline(s: String, precision: Int = 6): Pair<DoubleArray, DoubleArray> {
        val f = Math.pow(10.0, precision.toDouble())
        val lats = ArrayList<Double>(); val lons = ArrayList<Double>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            for (k in 0..1) {
                var shift = 0; var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (k == 0) lat += delta else lon += delta
            }
            lats.add(lat / f); lons.add(lon / f)
        }
        return lats.toDoubleArray() to lons.toDoubleArray()
    }
}

/** How much the car uses at different speeds, tuned with the car's own longest trips. */
object Consumption {
    data class TripSample(val km: Double, val kwh: Double, val speedKmh: Double)

    /** @param factor this car against the reference curve; @param trips how many of the car's trips it is built on (0 = none) */
    data class Model(val factor: Double, val trips: Int) {
        fun at(speedKmh: Double): Double = shape(speedKmh) * factor
    }

    /** kWh/100 km at a steady speed for a reference family EV: rolling and drivetrain, air drag (v²), and heating/electronics (a fixed load). */
    fun shape(speedKmh: Double): Double {
        val v = speedKmh.coerceIn(15.0, 150.0)
        return 9.0 + 0.00105 * v * v + 150.0 / v
    }

    fun fit(samples: List<TripSample>, batteryKwh: Double): Model {
        val base = if (batteryKwh in 1.0..60.0) 0.85 else 1.0
        val ok = samples.filter { it.km >= 8 && it.kwh > 0.3 && it.speedKmh in 15.0..150.0 && (it.kwh / it.km * 100) in 6.0..60.0 }
        if (ok.size < 3) return Model(base, 0)
        val longest = ok.sortedByDescending { it.km }
        // The longest trips say most about a road trip: everything over 40 km, or else the longest third.
        val long = longest.filter { it.km >= 40 }
        val use = if (long.size >= 5) long.take(25) else longest.take(max(5, ok.size / 3).coerceAtMost(25))
        val actual = use.sumOf { it.kwh }
        // A trip's average speed hides its fast stretches, where most energy goes: count it a little higher.
        val predicted = use.sumOf { it.km * shape(it.speedKmh * 1.1) / 100.0 }
        return Model((actual / predicted).coerceIn(0.6, 1.8), use.size)
    }
}

/** How fast the car takes power at a fast charger: full power when low, tapering off towards full. */
object ChargeCurve {
    fun power(soc: Double, chargerKw: Double, carMaxKw: Double): Double {
        val f = when {
            soc < 8 -> 0.9
            soc < 20 -> 1.0
            soc < 80 -> 1.0 - (soc - 20) / 60 * 0.65
            else -> 0.35 - (soc - 80) / 20 * 0.27
        }
        return min(chargerKw, carMaxKw * f).coerceAtLeast(3.0)
    }

    fun minutes(from: Double, to: Double, batteryKwh: Double, chargerKw: Double, carMaxKw: Double): Double {
        var m = 0.0; var s = from
        while (s < to - 1e-6) {
            val step = min(1.0, to - s)
            m += step / 100 * batteryKwh / power(s + step / 2, chargerKw, carMaxKw) * 60
            s += step
        }
        return m
    }

    /**
     * The car's top charging power that makes the model match the user's own fast charges: each sample is
     * (from %, to %, minutes it really took). Null when there are too few to say.
     */
    fun fitMaxKw(samples: List<Triple<Double, Double, Double>>, batteryKwh: Double): Double? {
        if (samples.size < 2) return null
        val actual = samples.sumOf { it.third }
        fun model(max: Double) = samples.sumOf { minutes(it.first, it.second, batteryKwh, 350.0, max) }
        var lo = 30.0; var hi = 250.0
        if (actual >= model(lo)) return lo
        if (actual <= model(hi)) return hi
        repeat(30) { val mid = (lo + hi) / 2; if (model(mid) > actual) lo = mid else hi = mid }
        return (lo + hi) / 2
    }

    fun pctAfter(from: Double, minutes: Double, batteryKwh: Double, chargerKw: Double, carMaxKw: Double): Double {
        var s = from; var left = minutes
        while (s < 100 && left > 0) {
            val step = min(1.0, 100 - s)
            val t = step / 100 * batteryKwh / power(s + step / 2, chargerKw, carMaxKw) * 60
            if (t > left) { s += step * left / t; break }
            left -= t; s += step
        }
        return min(s, 100.0)
    }
}

/** A fast charger near the route. */
data class PlanCharger(
    val id: String,
    val name: String,
    val operator: String,
    val lat: Double,
    val lon: Double,
    /** Highest power in kW; 0 = not known. */
    val kw: Double,
    val points: Int,
    /** The nearest route point, how far along the route that is, and how far off the route the charger is. */
    val idx: Int,
    val km: Double,
    val offKm: Double,
    val price: Double? = null,
    val currency: String = "",
    /** Charge points free right now, when known. */
    val free: Int? = null,
)

/** A stop the user chose, or changed: where, and optionally how full to charge or how long to stay. */
data class StopEdit(val chargerId: String, val targetPct: Int? = null, val minutes: Int? = null)

data class Stop(
    val charger: PlanCharger,
    val arrivePct: Double,
    val leavePct: Double,
    val minutes: Int,
    val kwh: Double,
    val cost: Double?,
    /** The user picked this charger (or changed its numbers). */
    val pinned: Boolean,
    /** Minutes after departure when the car gets here. */
    val atMin: Double,
    val alternatives: List<PlanCharger>,
)

data class Plan(
    val stops: List<Stop>,
    val arrivePct: Double,
    val driveMin: Double,
    val chargeMin: Int,
    val totalMin: Double,
    val totalKm: Double,
    /** False when the car can't get there: no charger within reach after [failKm]. */
    val ok: Boolean,
    val failKm: Double?,
    val cost: Double?,
    val currency: String,
    val unpriced: Int,
    /** Battery % at every point of the route (filled in by the Planner). */
    val socAt: DoubleArray = DoubleArray(0),
    /** Energy the driving takes (kWh), stops left out. */
    val usedKwh: Double = 0.0,
)

data class Options(
    val startPct: Double,
    /** Never plan to go below this. */
    val minPct: Int,
    /** Charge to this at a stop, unless less is enough. */
    val targetPct: Int,
    val batteryKwh: Double,
    val carMaxKw: Double,
)

object TripPlanner {
    /** Road distance to a charger against the straight line, and the minutes to park and plug in. */
    private const val DETOUR = 1.4
    private const val PLUG_MIN = 5.0
    /** Energy lost between the charger's meter and the battery. */
    private const val LOSS = 1.07

    fun plan(
        route: RouteData, energy: DoubleArray, chargers: List<PlanCharger>, o: Options,
        edits: List<StopEdit>, excluded: Set<String>, model: Consumption.Model, preferred: Set<String> = emptySet(),
    ): Plan {
        val n = route.size
        if (n < 2 || energy.size != n) return Plan(emptyList(), o.startPct, 0.0, 0, 0.0, 0.0, true, null, null, "", 0)
        val b = o.batteryKwh.coerceAtLeast(5.0)
        val totalKwh = energy[n - 1]
        fun detourKm(c: PlanCharger) = c.offKm * DETOUR * 2
        fun detourKwh(c: PlanCharger) = detourKm(c) * model.at(50.0) / 100.0
        fun detourMin(c: PlanCharger) = detourKm(c) / 40.0 * 60.0
        fun effKw(c: PlanCharger) = min(if (c.kw <= 0) 50.0 else c.kw, o.carMaxKw)
        // Far along the route is good; slow, far off the road or a single plug is not.
        // A charger right by a stop the user chose to pass through is worth a lot: it saves a separate stop.
        fun score(c: PlanCharger) = c.km - (1 - effKw(c) / o.carMaxKw) * 70 - c.offKm * 12 - (if (c.points == 1) 12 else 0) + (if (c.id in preferred) 80 else 0)

        val byId = chargers.associateBy { it.id }
        val pins = edits.mapNotNull { e -> byId[e.chargerId]?.let { it to e } }.sortedBy { it.first.km }
        val pinnedIds = pins.map { it.first.id }.toSet()
        val used = HashSet<String>()
        val stops = ArrayList<Stop>()

        var soc = o.startPct
        var pos = 0
        var clock = 0.0
        var drive = 0.0
        var pin = 0
        var ok = true
        var failKm: Double? = null
        var guard = 0

        while (true) {
            if (guard++ > 40) { ok = false; failKm = route.km[pos]; break }
            val next = pins.getOrNull(pin)
            val posKwh = energy[pos]
            val posKm = route.km[pos]
            // Already below the minimum (a low start): get to a charger on what is left, down to a small reserve.
            val floor = if (soc > o.minPct) o.minPct.toDouble() else min(5.0, soc / 2)
            val budget = posKwh + (soc - floor) / 100 * b
            val goalKwh = if (next != null) energy[next.first.idx] + detourKwh(next.first) else totalKwh
            val goalKm = next?.first?.km ?: route.totalKm
            // The last point the battery reaches: energy can go down again after a climb, so stop at the first point out of reach.
            val reach = run { var i = pos; while (i < n - 1 && energy[i + 1] <= budget + 1e-9) i++; i }
            val within = chargers.filter { it.km > posKm + 8 && it.idx <= reach && it.id !in excluded && it.id !in used && energy[it.idx] + detourKwh(it) <= budget + 1e-9 }

            val c: PlanCharger
            val edit: StopEdit?
            if (goalKwh <= budget + 1e-9 && (next?.first?.idx ?: (n - 1)) <= reach) {
                if (next == null) break            // the destination is within reach
                c = next.first; edit = next.second; pin++
            } else {
                val cands = within.filter { it.id !in pinnedIds && it.km < goalKm - 5 }
                if (cands.isEmpty()) {
                    ok = false
                    var i = pos
                    while (i < n - 1 && energy[i + 1] <= budget) i++
                    failKm = route.km[i]
                    break
                }
                val best = cands.maxByOrNull { score(it) }!!
                // A charger by a stop on the way is the natural place to charge: it saves a separate stop,
                // as long as it isn't far short of the best charger within reach.
                val pref = cands.filter { it.id in preferred }.maxByOrNull { score(it) }
                c = if (pref != null && pref.km >= best.km - 80) pref else best
                edit = null
            }

            val arr = (soc - (energy[c.idx] + detourKwh(c) - posKwh) / b * 100).coerceIn(0.0, 100.0)
            // What the next leg needs: to the next chosen stop, or to the destination, with the minimum left.
            val after = pins.getOrNull(pin)
            val nextKwh = if (after != null) energy[after.first.idx] + detourKwh(after.first) else totalKwh
            val needPct = o.minPct + (nextKwh - energy[c.idx]) / b * 100 + 2
            // "Charge to" is a ceiling: never fill above it (a stop the user set to a higher number may go there).
            // A longer break only keeps the car plugged in longer; it doesn't raise the charge past the ceiling.
            val cap = (edit?.targetPct ?: o.targetPct).toDouble()
            var leave = when {
                edit?.minutes != null -> min(ChargeCurve.pctAfter(arr, edit.minutes.toDouble(), b, effKw(c), o.carMaxKw), cap)
                edit?.targetPct != null -> edit.targetPct.toDouble()
                else -> min(needPct, cap)
            }
            leave = leave.coerceIn(arr, 100.0)
            val mins = if (edit?.minutes != null) edit.minutes.toDouble() else ChargeCurve.minutes(arr, leave, b, effKw(c), o.carMaxKw)
            val kwh = (leave - arr) / 100 * b
            val leg = route.min[c.idx] - route.min[pos] + detourMin(c)
            clock += leg; drive += leg
            stops += Stop(
                charger = c, arrivePct = arr, leavePct = leave, minutes = Math.round(mins).toInt(), kwh = kwh,
                cost = c.price?.let { it * kwh * LOSS }, pinned = edit != null, atMin = clock,
                alternatives = within.filter { it.id != c.id && it.id !in pinnedIds && it.km < goalKm + 0.001 }.sortedByDescending { score(it) }.take(6),
            )
            used += c.id
            if (kwh > 0.01) clock += mins + PLUG_MIN
            soc = leave
            pos = c.idx
        }

        val last = route.totalMin - route.min[pos]
        clock += last; drive += last
        val arrive = (soc - (totalKwh - energy[pos]) / b * 100).coerceAtMost(100.0)
        val charging = stops.filter { it.kwh > 0.01 }
        val cur = charging.firstOrNull { it.cost != null }?.charger?.currency.orEmpty()
        val priced = charging.filter { it.cost != null && it.charger.currency == cur }
        return Plan(
            stops = stops, arrivePct = arrive, driveMin = drive, chargeMin = stops.sumOf { it.minutes }, totalMin = clock,
            totalKm = route.totalKm + stops.sumOf { detourKm(it.charger) }, ok = ok, failKm = failKm,
            cost = if (priced.isEmpty()) null else priced.sumOf { it.cost!! }, currency = cur, unpriced = charging.size - priced.size,
        )
    }
}
