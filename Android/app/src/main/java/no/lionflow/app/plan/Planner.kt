package no.lionflow.app.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.Http
import no.lionflow.app.model.ChargePower
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** The trip planner's state and actions. Lives in the view model, so a plan survives switching tabs. */
class Planner(
    private val scope: CoroutineScope,
    private val app: LionFlowApp,
    private val status: () -> VehicleStatus?,
    private val trips: () -> List<Trip>,
    private val myPos: () -> Pair<Double, Double>?,
) {
    var open by mutableStateOf(false)
    /** The address form is showing (phones: full screen). */
    var editing by mutableStateOf(true)
    /** null = where the car is. */
    var from by mutableStateOf<Place?>(null)
    var to by mutableStateOf<Place?>(null)
    /** Up to 3 places the trip passes through, in order; null = a slot the user is still filling in. */
    var vias by mutableStateOf<List<Place?>>(emptyList())
    /** Minutes to stay at each via stop (same order as [vias]). */
    var stays by mutableStateOf<List<Int>>(emptyList())
    /** null = the car's current charge. */
    var startPct by mutableStateOf<Int?>(null)
    var minPct by mutableStateOf(app.settings.raw("planMin").toIntOrNull()?.coerceIn(5, 50) ?: 20)
    var targetPct by mutableStateOf(app.settings.raw("planTarget").toIntOrNull()?.coerceIn(50, 100) ?: 80)

    var route by mutableStateOf<RouteData?>(null)
        private set
    var chargers by mutableStateOf<List<PlanCharger>>(emptyList())
        private set
    var plan by mutableStateOf<Plan?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var model by mutableStateOf(Consumption.Model(1.0, 0))
        private set
    var edits by mutableStateOf<List<StopEdit>>(emptyList())
        private set
    var excluded by mutableStateOf<Set<String>>(emptySet())
        private set
    /** The start and end the current plan was made for. */
    var planFrom by mutableStateOf<Place?>(null)
        private set
    var planTo by mutableStateOf<Place?>(null)
    /** Round trip: drive to the destination, stay [destStay] minutes, then back to the start. */
    var roundTrip by mutableStateOf(false)
        private set
    var destStay by mutableStateOf(30)
        private set
    /** True when the plan showing is a round trip (the destination is then the last of [planVias]). */
    var planRound by mutableStateOf(false)
        private set
    var planVias by mutableStateOf<List<Place>>(emptyList())
        private set
    var planStays by mutableStateOf<List<Int>>(emptyList())
        private set

    /** When the trip starts; null = now. A time that has passed counts as now. */
    var departAt by mutableStateOf<Long?>(null)
        private set
    /** The departure time of a saved trip that is being shown as it was planned. */
    private var savedDepart: Long? = null
    fun departMillis(): Long = savedDepart ?: departAt?.takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis()

    /** A car ferry on the route with the wait for it: when it leaves (if the timetable is known) and minutes to wait. */
    class FerryInfo(val ferry: Ferry, val waitMin: Int, val departMs: Long?)
    var ferryInfo by mutableStateOf<List<FerryInfo>>(emptyList())
        private set

    /** One of the routes found: the road, and its plan once that is worked out (null until then). */
    class AltRoute(val route: RouteData, val plan: Plan?, val raw: List<RawCharger>?, val full: Boolean = true)
    var alts by mutableStateOf<List<AltRoute>>(emptyList())
        private set
    var altIndex by mutableStateOf(0)
    /** True once the user has chosen a route themselves: then the planner no longer switches on its own. */
    private var userPicked = false
    /** Set when the planner switched to the route with the fewest charging stops. */
    var autoPicked by mutableStateOf(false)
        private set
    private var altJob: Job? = null

    var saved by mutableStateOf(loadPlaces("planSaved"))
        private set
    var recent by mutableStateOf(loadPlaces("planRecent"))
        private set
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var corridorFor: RouteData? = null
    private var corridorRaw: List<RawCharger> = emptyList()
    private var viaRawKey = ""
    private var viaRaw: List<RawCharger> = emptyList()
    /** The car's top charging power used for the plan, and how many of its own fast charges that was fitted to (0 = a typical figure). */
    var carKw by mutableStateOf(0.0)
        private set
    var fitCount by mutableStateOf(0)
        private set
    /** The charger lookup, started as soon as a long route is known so it runs while the first plan is worked out. */
    private var prefetch: kotlinx.coroutines.Deferred<Result<List<RawCharger>>>? = null
    private var prefetchFor: RouteData? = null
    private var prefetchFull = true
    /** True when the chargers loaded for this route cover all of it, false when only the stretches where a stop could be needed. */
    private var corridorFull = true
    private var condJob: kotlinx.coroutines.Deferred<Unit>? = null
    private var condFor: RouteData? = null
    private var priceCountry = ""
    private val prices by lazy {
        Prices(try { app.assets.open("charge_prices.json").bufferedReader().use { it.readText() } } catch (_: Exception) { "{}" })
    }
    val pricesAsOf: String get() = prices.asOf

    fun carPct(): Int = status()?.batteryPct?.roundToInt()?.coerceIn(1, 100) ?: 80
    fun startNow(): Int = startPct ?: carPct()
    fun batteryKwh(): Double = status()?.let { ChargePower.usableKwh(it) } ?: 73.0

    // ---------------- addresses ----------------

    /** Suggestions while typing; waits for a short pause first. */
    fun search(q: String) {
        searchJob?.cancel()
        if (q.trim().length < 2) { results = emptyList(); searching = false; return }
        searchJob = scope.launch {
            delay(350)
            searching = true
            try {
                val near = myPos() ?: status()?.let { st -> st.lat?.let { la -> st.lon?.let { lo -> la to lo } } }
                results = PlanApi.search(q.trim(), near?.first, near?.second, no.lionflow.app.ui.l10n.L10n.lang)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { results = emptyList() }
            searching = false
        }
    }

    fun clearSearch() { searchJob?.cancel(); results = emptyList(); searching = false }

    private fun same(a: Place, b: Place) = a.name == b.name && Geo.km(a.lat, a.lon, b.lat, b.lon) < 0.05
    fun isSaved(p: Place) = saved.any { same(it, p) }
    fun toggleSaved(p: Place) {
        saved = if (isSaved(p)) saved.filterNot { same(it, p) } else (listOf(p) + saved).take(20)
        storePlaces("planSaved", saved)
    }

    /** Takes a place off the list of recent destinations. */
    fun removeRecent(p: Place) {
        recent = recent.filterNot { same(it, p) }
        storePlaces("planRecent", recent)
    }

    private fun remember(p: Place) {
        recent = (listOf(p) + recent.filterNot { same(it, p) }).take(6)
        storePlaces("planRecent", recent)
    }

    private fun loadPlaces(key: String): List<Place> = try {
        val a = JSONArray(app.settings.raw(key).ifBlank { "[]" })
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Place(o.optString("n"), o.optString("d"), o.getDouble("la"), o.getDouble("lo"), o.optString("c")) } }
    } catch (_: Exception) { emptyList() }

    private fun storePlaces(key: String, list: List<Place>) =
        app.settings.setRaw(key, JSONArray(list.map { JSONObject().put("n", it.name).put("d", it.detail).put("la", it.lat).put("lo", it.lon).put("c", it.country) }).toString())

    // ---------------- planning ----------------

    /** NOBIL key: the one built into the app, else the one typed in Settings. */
    private fun nobilKey(): String = no.lionflow.app.BuildConfig.NOBIL_KEY.ifBlank { app.settings.value.nobilKey }

    /** Where the trip starts: the chosen address, else the car, else the phone. */
    private fun origin(): Place? {
        from?.let { return it }
        val st = status()
        val country = app.settings.value.country
        if (st?.lat != null && st.lon != null) return Place("Car's position", "", st.lat, st.lon, country)
        return myPos()?.let { Place("My position", "", it.first, it.second, country) }
    }

    /** Finds the route and plans the stops. */
    fun go() {
        val b = to ?: return
        planJob?.cancel()
        savedDepart = null
        planJob = scope.launch {
            error = null
            val a = origin()
            if (a == null) { error = "Choose where the trip starts"; return@launch }
            try {
                busy = "Finding the route…"
                val via = vias.filterNotNull().take(3)
                val stay = vias.indices.filter { vias[it] != null }.take(3).map { stays.getOrElse(it) { 0 } }
                val round = roundTrip && Geo.km(a.lat, a.lon, b.lat, b.lon) > 0.3
                // A round trip with no stops on the way is the one-way route there and the same road back: half the work.
                val rs = if (round && via.isEmpty()) PlanApi.routes(a, b, emptyList()).map { it.roundTrip() }
                    else if (round) PlanApi.routes(a, a, via + b) else PlanApi.routes(a, b, via)
                val r = rs[0]
                altJob?.cancel(); alts = rs.map { AltRoute(it, null, null) }; altIndex = 0; ferryInfo = emptyList(); userPicked = false; autoPicked = false
                edits = emptyList(); excluded = emptySet(); chargers = emptyList(); corridorFor = null; corridorRaw = emptyList(); viaRawKey = ""; viaRaw = emptyList()
                route = r; planFrom = a; planRound = round
                if (round) { planTo = a; planVias = via + b; planStays = stay + destStay } else { planTo = b; planVias = via; planStays = stay }
                prefetch?.cancel(); prefetch = null; prefetchFor = null; condJob?.cancel()
                if (r.totalKm > 120) {
                    val (o0, m0) = context()
                    val zones = needZones(r, o0, m0)
                    prefetchFor = r; prefetchFull = zones == null
                    prefetch = scope.async(Dispatchers.IO) { runCatching { PlanApi.chargers(r, nobilKey(), zones) } }
                }
                // Hills and weather are fetched at the same time as the chargers, not one after the other.
                condFor = r; condJob = scope.async { ensureConditions(r) }
                priceCountry = if (a.country.isNotBlank() && a.country.equals(b.country, ignoreCase = true)) a.country else ""
                remember(b); from?.let { remember(it) }; via.forEach { remember(it) }
                compute()
                editing = false
                // The forecast sometimes fails on the first try: ask once more in the background and redo the plan.
                if (missingWeather(r)) { kotlinx.coroutines.delay(1_500L); compute() }
                startAlts()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't plan the trip — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    /** Plans again after a change to the numbers or the stops (no new route). */
    fun recompute() {
        if (route == null) return
        savedDepart = null
        planJob?.cancel()
        planJob = scope.launch {
            error = null
            try { compute(); if (alts.any { it.plan == null }) startAlts() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't find chargers — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    /** Battery use and charging speed for this car (matched to its own trips and fast charges when there are enough). */
    private fun context(): Pair<Options, Consumption.Model> {
        val kwh = batteryKwh()
        val m = Consumption.fit(trips().mapNotNull { t ->
            val used = t.kwh ?: t.avgKwhPer100?.let { it * t.distanceKm / 100 }
            val v = t.avgSpeedKmh
            if (used != null && v != null) Consumption.TripSample(t.distanceKm, used, v) else null
        }, kwh)
        model = m
        val fitSamples = no.lionflow.app.model.ChargeLog.sessions.value
            .filter { (it.mode?.contains("Quick", true) == true || (it.avgKw ?: 0.0) >= 40.0) && it.endPct - it.startPct >= 8 && it.hours * 60 >= 8 && it.kwh > 0 }
            .sortedByDescending { it.start }.take(8).map { Triple(it.startPct, it.endPct, it.hours * 60) }
        val fitted = ChargeCurve.fitMaxKw(fitSamples, kwh)
        val maxKw = fitted ?: if (kwh >= 68) 110.0 else 80.0
        carKw = maxKw; fitCount = if (fitted != null) fitSamples.size else 0
        return Options(startNow().toDouble(), minPct, targetPct, kwh, maxKw) to m
    }

    /**
     * Works out the stops for [r], then adds what the plan itself doesn't know about: stays at the places the user
     * passes through, and the wait for car ferries (from the timetable where there is one, else a typical wait).
     */
    private suspend fun solve(
        r: RouteData, o: Options, m: Consumption.Model, ch: List<PlanCharger>, ed: List<StopEdit>, ex: Set<String>,
        prefer: Set<String>, vias: List<Place>, stays: List<Int>,
    ): Pair<Plan, List<FerryInfo>> {
        ensureConditions(r)
        val energy = withContext(Dispatchers.Default) { r.energy(m.factor) }
        val p = withContext(Dispatchers.Default) { TripPlanner.plan(r, energy, ch, o, ed, ex, m, prefer) }
        // A charging stop right at a via stop is the same wait: count the overlap once.
        val overlap = vias.indices.sumOf { i ->
            val v = vias[i]; val st = stays.getOrElse(i) { 0 }
            if (st <= 0) 0 else p.stops.filter { Geo.km(v.lat, v.lon, it.charger.lat, it.charger.lon) < 1.0 }.maxOfOrNull { minOf(it.minutes, st) } ?: 0
        }
        val start = departMillis()
        var extra = 0.0
        val infos = ArrayList<FerryInfo>()
        for (f in r.ferries.sortedBy { it.idx }) {
            val km = r.km[f.idx]
            val charging = p.stops.filter { it.charger.km <= km && it.kwh > 0.01 }.sumOf { it.minutes + 5 }
            val stayed = vias.indices.sumOf { i -> if (r.km[Geo.project(r, vias[i].lat, vias[i].lon).first] <= km) stays.getOrElse(i) { 0 } else 0 }
            val eta = start + ((r.min[f.idx] + charging + stayed + extra) * 60_000.0).toLong()
            val dep = try { kotlinx.coroutines.withTimeoutOrNull(13_000L) { PlanApi.ferryDeparture(f, eta) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            val wait = if (dep != null) ((dep - eta) / 60_000.0).coerceIn(0.0, 240.0) else 20.0
            infos += FerryInfo(f, wait.roundToInt(), dep)
            extra += wait
        }
        // Battery % along the way: what driving takes, plus what each charging stop puts back.
        val soc = DoubleArray(r.size)
        val byIdx = p.stops.filter { it.kwh > 0.01 }.sortedBy { it.charger.idx }
        var added = 0.0; var si = 0
        for (i in 0 until r.size) {
            while (si < byIdx.size && byIdx[si].charger.idx <= i) { added += byIdx[si].leavePct - byIdx[si].arrivePct; si++ }
            soc[i] = (o.startPct - (energy[i] - energy[0]) / o.batteryKwh * 100 + added).coerceIn(0.0, 100.0)
        }
        // A round trip's total is the driving, charging and ferries: the stay at the far end is left out.
        val farStay = if (planRound) stays.lastOrNull() ?: 0 else 0
        return p.copy(totalMin = p.totalMin + stays.sum() - farStay - overlap + extra, socAt = soc, usedKwh = energy.last() - energy[0]) to infos
    }

    /** The stops the via places call for: a charger within 1 km of a via stop with a stay is used for that stay, and any charger that close is preferred. */
    private fun viaPlan(): Pair<List<StopEdit>, Set<String>> {
        if (planVias.isEmpty()) return emptyList<StopEdit>() to emptySet()
        val prefer = HashSet<String>(); val implicit = ArrayList<StopEdit>()
        planVias.forEachIndexed { i, v ->
            val near = chargers.map { it to Geo.km(v.lat, v.lon, it.lat, it.lon) }.filter { it.second < 1.0 }
            near.forEach { prefer += it.first.id }
            // Only preferred when a stop is needed anyway: a stay never forces charging (the tip under the stop offers it).
        }
        return implicit to prefer
    }

    /**
     * The stretches of [r] (point ranges) where a charging stop could be needed. Where the battery is surely above
     * about half, no charger is looked up. Null = look at the whole route.
     */
    private fun needZones(r: RouteData, o: Options, m: Consumption.Model): List<IntRange>? {
        val last = r.size - 1
        if (last < 3 || o.targetPct <= 55) return null
        val perPct = o.batteryKwh / 100.0
        val hi = r.energy(m.factor * 1.3)   // uses more: the battery is down to half sooner
        val lo = r.energy(m.factor * 0.8)   // uses less: the car gets furthest before it must stop
        fun reach(e: DoubleArray, from: Int, soc: Double, limit: Double): Int {
            var i = from
            while (i < last && soc - (e[i] - e[from]) / perPct >= limit) i++
            return i
        }
        val raw = ArrayList<IntRange>()
        var a = 0; var b = 0; var sa = o.startPct; var sb = o.startPct
        var n = 0
        while (n++ < 12) {
            val na = if (sa < 50.0) a else reach(hi, a, sa, 50.0)
            if (na >= last) break
            val nb = reach(lo, maxOf(b, na), sb, o.minPct + 2.0)
            raw += na..nb
            if (nb >= last) break
            a = na; b = nb
            sa = minOf(o.targetPct.toDouble(), 60.0); sb = o.targetPct.toDouble()
        }
        if (raw.isEmpty()) return emptyList()   // the battery stays high all the way
        // Some room on each side, then join stretches that touch.
        val padKm = 15.0
        val padded = raw.map { z ->
            var f = z.first; while (f > 0 && r.km[z.first] - r.km[f] < padKm) f--
            var t = z.last; while (t < last && r.km[t] - r.km[z.last] < padKm) t++
            f..t
        }.sortedBy { it.first }
        val out = ArrayList<IntRange>()
        for (z in padded) {
            if (out.isNotEmpty() && z.first <= out.last().last) out[out.size - 1] = out.last().first..maxOf(out.last().last, z.last) else out += z
        }
        return if (out.size == 1 && out[0].first <= 0 && out[0].last >= last) null else out
    }

    /** Hills, wind and temperature along the route, for the hour of departure (looked up once per route and hour; a failed lookup is left out). */
    private suspend fun ensureConditions(r: RouteData) {
        val key = departMillis() / 3_600_000L
        if (r.conditionsKey == key) return
        val c = try { kotlinx.coroutines.withTimeoutOrNull(20_000L) { ConditionsApi.load(r, departMillis()) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        // Keep what we got (heights are kept on the route), but ask again next time while the forecast is missing.
        if (c != null || r.conditions == null) r.conditions = c
        if (missingWeather(r)) r.conditionsKey = Long.MIN_VALUE else r.conditionsKey = key
    }

    private fun missingWeather(r: RouteData): Boolean =
        r.conditions?.hasWeather != true && ConditionsApi.forecastPossible(departMillis())

    private suspend fun solveHere(r: RouteData, o: Options, m: Consumption.Model): Pair<Plan, List<FerryInfo>> {
        val (implicit, prefer) = viaPlan()
        return solve(r, o, m, chargers, edits + implicit, excluded, prefer, planVias, planStays)
    }

    private suspend fun compute() {
        val r = route ?: return
        val (o, m) = context()
        if (condFor === r) condJob?.let { runCatching { it.await() } }
        var res = solveHere(r, o, m)
        var changed = false
        if (!res.first.ok && corridorFor !== r) {
            // The car can't make it in one go: look up the fast chargers along the way (once per route).
            busy = "Finding chargers along the route…"
            if (prefetchFor === r && prefetch != null) { corridorRaw = prefetch!!.await().getOrThrow(); corridorFull = prefetchFull }
            else { val z = needZones(r, o, m); corridorRaw = PlanApi.chargers(r, nobilKey(), z); corridorFull = z == null }
            corridorFor = r; changed = true
        }
        // Chargers right by every stop on the way (best effort): fewer separate stops, and a place to charge during a stay.
        val key = planVias.joinToString("|") { "${it.lat},${it.lon}" }
        if (key != viaRawKey) {
            viaRaw = if (planVias.isEmpty()) emptyList() else kotlinx.coroutines.coroutineScope {
                planVias.map { v -> async { try { PlanApi.chargersNear(v.lat, v.lon, nobilKey()) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() } } }.awaitAll().flatten()
            }
            viaRawKey = key; changed = true
        }
        if (changed) {
            chargers = withContext(Dispatchers.Default) { place(corridorRaw + viaRaw, r) }
            res = solveHere(r, o, m)
        }
        // Not enough with the chargers on the likely stretches: look at the whole route.
        if (!res.first.ok && corridorFor === r && !corridorFull) {
            busy = "Finding more chargers…"
            corridorRaw = PlanApi.chargers(r, nobilKey()); corridorFull = true
            chargers = withContext(Dispatchers.Default) { place(corridorRaw + viaRaw, r) }
            res = solveHere(r, o, m)
        }
        plan = res.first; ferryInfo = res.second
        alts = alts.mapIndexed { i, a -> if (i == altIndex) AltRoute(a.route, res.first, if (corridorFor === r) corridorRaw else null, corridorFull) else a }
    }

    /** Works out the other routes in the background, so they can be compared with the chosen one. */
    private fun startAlts() {
        altJob?.cancel()
        if (alts.size < 2) return
        altJob = scope.launch {
            // The chargers for every route are asked for at once; the plans are worked out one by one.
            val (o0, m0) = context()
            val fetches = alts.mapIndexed { i, a ->
                if (i == altIndex || a.plan != null || a.raw != null) null
                else { val z = needZones(a.route, o0, m0); z to scope.async(Dispatchers.IO) { kotlinx.coroutines.withTimeoutOrNull(25_000L) { PlanApi.chargers(a.route, nobilKey(), z) } } }
            }
            for (i in alts.indices) {
                val a = alts[i]
                if (i == altIndex || a.plan != null) continue
                try {
                    val (o, m) = context()
                    var full = a.raw == null || a.full
                    var raw = a.raw ?: fetches[i]?.let { (z, d) -> full = z == null; d.await() }
                    var ch = withContext(Dispatchers.Default) { place(raw.orEmpty(), a.route) }
                    var res = solve(a.route, o, m, ch, emptyList(), emptySet(), emptySet(), planVias, planStays)
                    if (!res.first.ok && !full) {
                        raw = kotlinx.coroutines.withTimeoutOrNull(25_000L) { PlanApi.chargers(a.route, nobilKey()) }; full = true
                        ch = withContext(Dispatchers.Default) { place(raw.orEmpty(), a.route) }
                        res = solve(a.route, o, m, ch, emptyList(), emptySet(), emptySet(), planVias, planStays)
                    }
                    val fRaw = raw; val fFull = full
                    alts = alts.mapIndexed { k, x -> if (k == i) AltRoute(a.route, res.first, fRaw, fFull) else x }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { }
            }
            pickFewestStops()
        }
    }

    /** Trips saved from the planner (also shown in Android Auto). */
    var savedTrips by mutableStateOf(SavedRoutes.all(app))
        private set
    init { scope.launch { SavedRoutes.changes.collect { savedTrips = SavedRoutes.all(app) } } }

    private fun pj(p: Place) = JSONObject().put("n", p.name).put("d", p.detail).put("la", p.lat).put("lo", p.lon).put("c", p.country)
    private fun pf(o: JSONObject) = Place(o.optString("n"), o.optString("d"), o.getDouble("la"), o.getDouble("lo"), o.optString("c"))

    /**
     * Saves the plan that is showing. With [asPlanned] the whole plan is kept and opens exactly as it is now;
     * otherwise only the places, and it is planned again from the car's position and charge when opened.
     */
    /** The trip built by the last saveTrip(keep = false): shown without being saved. */
    var lastBuilt: SavedRoutes.Saved? = null
        private set

    /** "Home → Oslo", or "Oslo ⇄" for a round trip. */
    private fun tripName(): String {
        val dest = if (planRound) planVias.lastOrNull() else planTo
        fun nm(pl: Place?) = pl?.let { if (it.detail.isEmpty() && (it.name == "Car's position" || it.name == "My position")) no.lionflow.app.ui.l10n.L10n.tr(it.name) else it.name } ?: ""
        return if (planRound) nm(dest) + " ⇄" else nm(planFrom) + " → " + nm(dest)
    }

    /**
     * The plan that is showing, in the form the car screen draws, without saving it. Also when the trip can't be
     * made: the car screen then shows how far it gets.
     */
    fun preview(): SavedRoutes.Saved? {
        val r = route ?: return null; val p = plan ?: return null
        return SavedRoutes.build(tripName(), r, r.energy(model.factor), p, batteryKwh(), null)
    }

    fun saveTrip(asPlanned: Boolean = true, keep: Boolean = true): Boolean {
        val r = route ?: return false; val p = plan ?: return false
        if (!p.ok) return false
        val dest = if (planRound) planVias.lastOrNull() else planTo
        val name = tripName()
        val setup = JSONObject().put("round", planRound).put("destStay", destStay)
            .put("vias", JSONArray().apply { (if (planRound) planVias.dropLast(1) else planVias).forEach { put(pj(it)) } })
            .put("stays", JSONArray().apply { (if (planRound) planStays.dropLast(1) else planStays).forEach { put(it) } })
        from?.let { setup.put("from", pj(it)) }
        dest?.let { setup.put("to", pj(it)) }
        // The plan in full, so it opens exactly as it is now, without asking any server.
        if (asPlanned) setup.put("full", JSONObject()
            .put("route", PlanStore.route(r)).put("plan", PlanStore.plan(p))
            .put("planFrom", planFrom?.let { pj(it) }).put("planTo", planTo?.let { pj(it) })
            .put("planVias", JSONArray().apply { planVias.forEach { put(pj(it)) } })
            .put("planStays", JSONArray().apply { planStays.forEach { put(it) } })
            .put("depart", departMillis()).put("startPct", startPct ?: -1).put("minPct", minPct).put("targetPct", targetPct)
            .put("ferries", JSONArray().apply { ferryInfo.forEach { put(JSONObject().put("i", it.ferry.idx).put("w", it.waitMin).put("d", it.departMs ?: -1L)) } }))
        if (!keep) { lastBuilt = SavedRoutes.build(name, r, r.energy(model.factor), p, batteryKwh(), setup); return true }
        SavedRoutes.add(app, SavedRoutes.build(name, r, r.energy(model.factor), p, batteryKwh(), setup))
        savedTrips = SavedRoutes.all(app)
        return true
    }

    /** Start from where the car is now (or this phone, without the car's position). Plans again when a plan is showing. */
    fun startAtCar() { from = null; if (route != null) go() }

    fun deleteTrip(id: Long) { SavedRoutes.delete(app, id); savedTrips = SavedRoutes.all(app) }

    /** Shows a saved trip exactly as it was planned: same route, stops, start and times. Nothing is looked up again. */
    fun openTrip(t: SavedRoutes.Saved) {
        val su = t.setup ?: return
        val full = su.optJSONObject("full")
        val dest = su.optJSONObject("to") ?: return
        if (full == null) {   // saved to be planned again: from where the car is now (unless another start was chosen), with its charge now
            from = su.optJSONObject("from")?.let { pf(it) }; to = pf(dest)
            startPct = null; departAt = null; savedDepart = null
            val vs = su.optJSONArray("vias"); val ss = su.optJSONArray("stays")
            vias = if (vs == null) emptyList() else (0 until vs.length()).map { pf(vs.getJSONObject(it)) }
            stays = vias.indices.map { ss?.optInt(it) ?: 0 }
            roundTrip = su.optBoolean("round"); destStay = su.optInt("destStay", 30)
            go(); return
        }
        try {
            planJob?.cancel(); altJob?.cancel(); prefetch?.cancel(); prefetch = null; prefetchFor = null; condJob?.cancel()
            val r = PlanStore.route(full.getJSONObject("route"))
            val pl = PlanStore.plan(full.getJSONObject("plan"))
            val pVias = full.getJSONArray("planVias").let { a -> (0 until a.length()).map { pf(a.getJSONObject(it)) } }
            val pStays = full.getJSONArray("planStays").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val round = su.optBoolean("round")
            val start = full.optJSONObject("planFrom")?.let { pf(it) }
            // Where the trip started, as a fixed place (the car's position then, if it started there).
            from = start?.let { if (it.detail.isEmpty() && (it.name == "Car's position" || it.name == "My position")) it.copy(name = "Where the car was") else it }
            to = pf(dest)
            vias = if (round) pVias.dropLast(1) else pVias
            stays = if (round) pStays.dropLast(1) else pStays
            roundTrip = round; destStay = su.optInt("destStay", 30)
            startPct = full.optInt("startPct", -1).takeIf { it >= 0 }
            minPct = full.optInt("minPct", minPct); targetPct = full.optInt("targetPct", targetPct)
            savedDepart = full.optLong("depart").takeIf { it > 0 }
            val fi = full.optJSONArray("ferries")
            ferryInfo = if (fi == null) emptyList() else (0 until fi.length()).mapNotNull { i -> fi.getJSONObject(i).let { o ->
                r.ferries.firstOrNull { it.idx == o.getInt("i") }?.let { f -> FerryInfo(f, o.getInt("w"), o.getLong("d").takeIf { it > 0 }) } } }
            edits = emptyList(); excluded = emptySet()
            route = r; plan = pl
            planFrom = start ?: from; planTo = full.optJSONObject("planTo")?.let { pf(it) }; planRound = round; planVias = pVias; planStays = pStays
            chargers = pl.stops.flatMap { listOf(it.charger) + it.alternatives }.distinctBy { it.id }
            corridorFor = r; corridorRaw = emptyList(); corridorFull = false
            viaRawKey = planVias.joinToString("|") { "${it.lat},${it.lon}" }; viaRaw = emptyList()
            alts = listOf(AltRoute(r, pl, null)); altIndex = 0; userPicked = true; autoPicked = true
            error = null; busy = null; editing = false
        } catch (e: Exception) {
            error = "Couldn't open the saved trip"
        }
    }

    /** Minutes spent at the far end of a round trip (not part of the trip's total time). */
    val farStay: Int get() = if (planRound) planStays.lastOrNull() ?: 0 else 0

    /** The other routes' plans are out of date after a change to the numbers. */
    private fun staleAlts() { alts = alts.mapIndexed { i, a -> if (i == altIndex) a else AltRoute(a.route, null, a.raw, a.full) } }

    /** Right after planning, moves to the route that needs the fewest charging stops (then the quickest). */
    private fun pickFewestStops() {
        if (userPicked || autoPicked) return
        val cur = plan ?: return
        fun stops(p: Plan) = p.stops.count { it.kwh > 0.01 }
        // Fewest charging stops first. Then the one that uses clearly less energy (less driving, less charging),
        // and only when that is about even, the quicker one.
        fun beats(a: Plan, b: Plan): Boolean = when {
            !b.ok -> a.ok
            !a.ok -> false
            stops(a) != stops(b) -> stops(a) < stops(b)
            a.usedKwh < b.usedKwh * 0.92 -> true
            b.usedKwh < a.usedKwh * 0.92 -> false
            else -> a.totalMin < b.totalMin - 1
        }
        var best: Int? = null; var bestPlan = cur
        for (i in alts.indices) {
            if (i == altIndex) continue
            val pl = alts[i].plan ?: continue
            if (beats(pl, bestPlan)) { best = i; bestPlan = pl }
        }
        autoPicked = true
        best?.let { switchRoute(it) }
    }

    /** Switches to another of the routes found (the user's choice). */
    fun selectRoute(i: Int) { userPicked = true; switchRoute(i) }

    private fun switchRoute(i: Int) {
        if (i == altIndex || i !in alts.indices) return
        val a = alts[i]
        planJob?.cancel(); altJob?.cancel()
        altIndex = i; route = a.route; edits = emptyList(); excluded = emptySet()
        corridorRaw = a.raw ?: emptyList(); corridorFor = if (a.raw != null) a.route else null; corridorFull = a.full
        viaRawKey = ""; viaRaw = emptyList(); ferryInfo = emptyList()
        prefetch?.cancel(); prefetch = null; prefetchFor = null
        chargers = place(corridorRaw, a.route)
        planJob = scope.launch {
            error = null
            try { compute(); startAlts() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                error = "Couldn't find chargers — ${Http.describe(e)}"
            } finally { busy = null }
        }
    }

    /** Moves the start time to the next or the previous quarter of an hour (back to "now" at the earliest). */
    /** Leave at a set time (null = now). A time in the past means now. */
    fun setDepart(ms: Long?) {
        savedDepart = null
        departAt = ms?.takeIf { it > System.currentTimeMillis() + 60_000L }
        staleAlts(); recompute()
    }

    fun shiftDepart(dir: Int) {
        val now = System.currentTimeMillis()
        val base = departAt?.takeIf { it > now } ?: now
        val q = 15 * 60_000L
        val t = if (dir > 0) (base / q + 1) * q else ((base - 1) / q) * q
        savedDepart = null
        departAt = if (t <= now + 60_000L) null else t
        staleAlts(); recompute()
    }

    private fun cellKey(cy: Long, cx: Long) = cy * 4_000_000L + cx

    /** Route points by small grid cell (0.03 by 0.06 degrees), so a charger only looks at the points near it. */
    private fun pointGrid(r: RouteData): HashMap<Long, ArrayList<Int>> {
        val g = HashMap<Long, ArrayList<Int>>()
        for (i in 0 until r.size) g.getOrPut(cellKey(Math.floor(r.lat[i] / 0.03).toLong(), Math.floor(r.lon[i] / 0.06).toLong())) { ArrayList() }.add(i)
        return g
    }

    /** Each time the route comes within 2.5 km of a point: the closest point of that pass and how far off. */
    private fun passes(r: RouteData, grid: HashMap<Long, ArrayList<Int>>, lat: Double, lon: Double): List<Pair<Int, Double>> {
        val cy = Math.floor(lat / 0.03).toLong(); val cx = Math.floor(lon / 0.06).toLong()
        val near = ArrayList<Int>()
        for (a in -1L..1L) for (b in -1L..1L) grid[cellKey(cy + a, cx + b)]?.let { near.addAll(it) }
        near.sort()
        val out = ArrayList<Pair<Int, Double>>()
        var best = -1; var bestD = Double.MAX_VALUE; var lastNear = -1.0
        for (i in near) {
            if (kotlin.math.abs(r.lat[i] - lat) > 0.03 || kotlin.math.abs(r.lon[i] - lon) > 0.06) continue
            val d = Geo.km(lat, lon, r.lat[i], r.lon[i])
            if (d > 2.5) continue
            if (best >= 0 && r.km[i] - lastNear > 15) { out.add(best to bestD); best = -1; bestD = Double.MAX_VALUE }
            if (d < bestD) { best = i; bestD = d }
            lastNear = r.km[i]
        }
        if (best >= 0) out.add(best to bestD)
        return out
    }

    /** Puts chargers on the route, drops doubles and far-off ones, and looks up prices. */
    private fun place(raw: List<RawCharger>, r: RouteData): List<PlanCharger> {
        val out = ArrayList<PlanCharger>()
        val grid = pointGrid(r)
        val seen = HashMap<Long, ArrayList<RawCharger>>()   // accepted chargers by cell, to drop doubles within 150 m
        for (c in raw.sortedByDescending { it.kw * 100 + it.points }) {
            val cy = Math.floor(c.lat / 0.01).toLong(); val cx = Math.floor(c.lon / 0.02).toLong()
            var dup = false
            loop@ for (a in -1L..1L) for (b in -1L..1L) {
                val l = seen[cellKey(cy + a, cx + b)] ?: continue
                for (o in l) if (Geo.km(o.lat, o.lon, c.lat, c.lon) < 0.15) { dup = true; break@loop }
            }
            if (dup) continue
            val passes = passes(r, grid, c.lat, c.lon)
            if (passes.isEmpty()) continue
            seen.getOrPut(cellKey(cy, cx)) { ArrayList() }.add(c)
            val price = prices.find(priceCountry, c.name + " " + c.operator, c.chargeTag)
            // A route that passes the same charger twice (a round trip on the same road) can use it both ways.
            passes.forEachIndexed { n, (idx, off) ->
                out.add(PlanCharger(if (n == 0) c.id else "${c.id}@$n", c.name, c.operator, c.lat, c.lon, c.kw, c.points, idx, r.km[idx], off, price?.first, price?.second.orEmpty(), c.free))
            }
        }
        return out.sortedBy { it.km }
    }

    // ---------------- changes to the plan ----------------

    fun setStart(pct: Int?) { startPct = pct?.coerceIn(1, 100); staleAlts(); recompute() }
    fun setMin(pct: Int) { minPct = pct.coerceIn(5, 50); app.settings.setRaw("planMin", minPct.toString()); staleAlts(); recompute() }
    fun setTarget(pct: Int) { targetPct = pct.coerceIn(50, 100); app.settings.setRaw("planTarget", targetPct.toString()); staleAlts(); recompute() }

    private fun put(e: StopEdit) {
        val old = edits.firstOrNull { it.chargerId == e.chargerId }
        // Keep what was already set for this stop (its own "charge to" and its break go together).
        edits = edits.filterNot { it.chargerId == e.chargerId } + StopEdit(e.chargerId, e.targetPct ?: old?.targetPct, e.minutes ?: old?.minutes)
        recompute()
    }
    /** Charge to this much at a stop. */
    fun chargeTo(id: String, pct: Int) = put(StopEdit(id, targetPct = pct.coerceIn(5, 100)))
    /** Stay this long at a stop. */
    fun stayFor(id: String, minutes: Int) = put(StopEdit(id, minutes = minutes.coerceIn(5, 180)))
    /** Charge at this charger for as long as the stay lasts (the tip under a via stop). */
    fun chargeDuringStay(id: String, minutes: Int) { excluded = excluded - id; put(StopEdit(id, minutes = minutes.coerceIn(5, 180))) }
    /** Stop at a charger the user picked. */
    fun addStop(id: String) { excluded = excluded - id; put(StopEdit(id)) }
    /** Don't stop here; another charger is found if one is needed. */
    fun removeStop(id: String) { edits = edits.filterNot { it.chargerId == id }; excluded = excluded + id; recompute() }
    /** Stop at another charger instead of this one. */
    fun swapStop(oldId: String, newId: String) {
        val old = edits.firstOrNull { it.chargerId == oldId }
        edits = edits.filterNot { it.chargerId == oldId || it.chargerId == newId } + StopEdit(newId, old?.targetPct, old?.minutes)
        excluded = excluded + oldId - newId
        recompute()
    }
    val changed: Boolean get() = edits.isNotEmpty() || excluded.isNotEmpty()
    fun resetStops() { edits = emptyList(); excluded = emptySet(); recompute() }

    fun addVia() { if (vias.size < 3) { vias = vias + null; stays = stays.take(vias.size - 1).let { it + List(vias.size - it.size) { 0 } } } }
    fun setVia(i: Int, place: Place?) { if (i in vias.indices) vias = vias.toMutableList().also { it[i] = place } }
    fun removeVia(i: Int) {
        if (i !in vias.indices) return
        vias = vias.filterIndexed { j, _ -> j != i }
        stays = stays.filterIndexed { j, _ -> j != i }
    }

    /** How long to stay at a via stop. A plan that is showing is worked out again at once (no new route needed). */
    fun setStay(i: Int, minutes: Int) {
        if (i !in vias.indices) return
        val list = stays.toMutableList(); while (list.size < vias.size) list.add(0)
        list[i] = minutes.coerceIn(0, 480); stays = list
        val shown = vias.indices.filter { vias[it] != null }
        val extra = if (planRound) 1 else 0
        if (route != null && shown.size + extra == planVias.size) {
            planStays = shown.map { stays.getOrElse(it) { 0 } } + (if (planRound) listOf(destStay) else emptyList()); recompute()
        }
    }

    /**
     * Minutes from departure until the car gets to a point on the route: driving, charging stops, ferry waits
     * and stays at earlier stops on the way. Null without a plan.
     */
    fun minutesTo(lat: Double, lon: Double): Double? {
        val r = route ?: return null
        val pl = plan ?: return null
        val idx = Geo.project(r, lat, lon).first
        val km = r.km[idx]
        val charging = pl.stops.filter { it.kwh > 0.01 && it.charger.idx < idx }.sumOf { it.minutes.toDouble() }
        val ferries = ferryInfo.filter { it.ferry.idx < idx }.sumOf { it.waitMin.toDouble() }
        val stays = planVias.indices.sumOf { i ->
            val k = r.km[Geo.project(r, planVias[i].lat, planVias[i].lon).first]
            if (k < km - 0.05) planStays.getOrElse(i) { 0 }.toDouble() else 0.0
        }
        return r.min[idx] + charging + ferries + stays
    }

    /** Round trip on or off; a plan that is showing is worked out again. */
    fun setRound(on: Boolean) {
        roundTrip = on
        if (route != null) go()
    }

    /** How long to stay at the destination on a round trip. */
    fun changeDestStay(minutes: Int) {
        destStay = minutes.coerceIn(0, 720)
        if (route != null && planRound && planStays.isNotEmpty()) { planStays = planStays.dropLast(1) + destStay; recompute() }
    }

    /**
     * Moves a stop to another place in the order. Positions count the stops on the way and then the destination,
     * so a stop can become the destination and the other way round. A plan that is showing is worked out again.
     */
    fun moveStop(fromPos: Int, toPos: Int) {
        val d = to ?: return
        val list = (vias.mapIndexed { i, v -> v to stays.getOrElse(i) { 0 } } + (d to 0)).toMutableList()
        if (fromPos !in list.indices || toPos !in list.indices || fromPos == toPos) return
        list.add(toPos, list.removeAt(fromPos))
        val last = list.removeAt(list.size - 1)
        if (last.first == null) return   // an empty stop can't be the destination
        to = last.first
        vias = list.map { it.first }; stays = list.map { it.second }
        if (route != null) go()
    }

    fun swapEnds() {
        vias = vias.reversed(); stays = stays.reversed()
        val a = from; val b = to
        if (b == null) return
        to = a ?: origin()
        from = b
    }

    /** Closes the planner and takes the route off the map. */
    fun close() {
        planJob?.cancel(); searchJob?.cancel(); altJob?.cancel(); alts = emptyList(); altIndex = 0; ferryInfo = emptyList()
        open = false; editing = true; busy = null; error = null
        prefetch?.cancel(); prefetch = null; prefetchFor = null
        route = null; plan = null; chargers = emptyList(); corridorFor = null; corridorRaw = emptyList(); viaRawKey = ""; viaRaw = emptyList(); edits = emptyList(); excluded = emptySet()
        results = emptyList()
    }
}
