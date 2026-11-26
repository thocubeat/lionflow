// More of the Android app's answers, for the parts of the iPhone app written after Oracle.kt: the sample car, the car
// record, the sign-in settings and assorted small pieces. Writes its own files only. See build.sh.
import no.lionflow.app.api.StellantisConfig
import no.lionflow.app.api.Vehicle
import no.lionflow.app.api.Weather
import no.lionflow.app.api.WeatherApi
import no.lionflow.app.demo.Demo
import no.lionflow.app.model.Brand
import no.lionflow.app.model.Brands
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.ChargePlace
import no.lionflow.app.model.ChargeSession
import no.lionflow.app.model.PlacesConfig
import no.lionflow.app.model.TagMark
import no.lionflow.app.model.Trip
import no.lionflow.app.model.TripEnds
import no.lionflow.app.model.TripTags
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.l10n.TimeFmt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.random.Random

private fun dd(v: Double?): Any = v?.toString() ?: JSONObject.NULL
private fun ll(v: Long?): Any = v?.toString() ?: JSONObject.NULL
private fun nn(v: Any?): Any = v ?: JSONObject.NULL
private fun list(items: Iterable<Any?>): JSONArray = JSONArray().also { a -> items.forEach { a.put(nn(it)) } }
private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

private fun trip(t: Trip): JSONObject = JSONObject()
    .put("id", t.id).put("start", ll(t.startMillis)).put("end", ll(t.endMillis)).put("km", dd(t.distanceKm)).put("dur", dd(t.durationSec))
    .put("kwh", dd(t.kwh)).put("avgKwh", dd(t.avgKwhPer100)).put("speed", dd(t.avgSpeedKmh)).put("liters", dd(t.liters)).put("avgL", dd(t.avgLPer100))
    .put("fs", dd(t.fuelStartPct)).put("fe", dd(t.fuelEndPct))

private fun status(st: VehicleStatus): JSONObject = JSONObject()
    .put("batteryPct", dd(st.batteryPct)).put("rangeKm", dd(st.rangeKm)).put("plugged", st.plugged).put("chargingStatus", nn(st.chargingStatus))
    .put("chargingMode", nn(st.chargingMode)).put("chargingRateKmh", dd(st.chargingRateKmh)).put("remainingMinutes", nn(st.remainingMinutes?.toString()))
    .put("nextDelayedHour", nn(st.nextDelayedHour?.toString())).put("nextDelayedMinute", nn(st.nextDelayedMinute?.toString()))
    .put("batteryHealthPct", dd(st.batteryHealthPct)).put("odometerKm", dd(st.odometerKm)).put("locked", nn(st.locked?.toString()))
    .put("openDoors", list(st.openDoors)).put("outsideTempC", dd(st.outsideTempC)).put("preconditioningOn", st.preconditioningOn)
    .put("programs", list(st.programs.map { "${it.slot}|${it.days.joinToString(",")}|${it.hour}|${it.minute}|${it.enabled}" }))
    .put("lat", dd(st.lat)).put("lon", dd(st.lon)).put("ignition", nn(st.ignition)).put("moving", st.moving).put("updatedAtMillis", ll(st.updatedAtMillis))
    .put("kmToService", dd(st.kmToService)).put("daysToService", dd(st.daysToService)).put("residualKwh", dd(st.residualKwh)).put("capacityKwh", dd(st.capacityKwh))
    .put("energyAtMillis", ll(st.energyAtMillis)).put("rangeEstimated", st.rangeEstimated).put("chargeType", nn(st.chargeType)).put("energyType", st.energyType)
    .put("fuelPct", dd(st.fuelPct)).put("fuelRangeKm", dd(st.fuelRangeKm)).put("isMildHybrid", st.isMildHybrid).put("heading", dd(st.heading))
    .put("privacyState", nn(st.privacyState)).put("fogLightsOn", list(st.fogLightsOn)).put("alarmOn", nn(st.alarmOn?.toString())).put("fuelTotalRaw", dd(st.fuelTotalRaw))
    .put("raw", st.raw.toString())

private fun session(s: ChargeSession): JSONObject = JSONObject().put("id", s.id.toString()).put("start", s.start.toString()).put("end", s.end.toString())
    .put("startPct", dd(s.startPct)).put("endPct", dd(s.endPct)).put("kwh", dd(s.kwh)).put("peakKw", dd(s.peakKw)).put("mode", nn(s.mode))
    .put("lat", dd(s.lat)).put("lon", dd(s.lon)).put("address", nn(s.address)).put("source", nn(s.source)).put("measured", s.measured)
    .put("hours", dd(s.hours)).put("avgKw", dd(s.avgKw))

private fun place(p: ChargePlace?): Any = if (p == null) JSONObject.NULL else "${p.id}|${p.name}|${p.address}|${p.lat}|${p.lon}|${p.price}"

private fun vehicle(v: Vehicle): String = listOf(v.id, v.vin, v.motorization, v.brand, v.pictureUrl, v.maintenanceHref, v.modelLabel, v.legacyLabel).joinToString("|") { it ?: "null" }

private fun placesOf(j: JSONObject): PlacesConfig = PlacesConfig(
    home = j.optJSONObject("home")?.let { ChargePlace.fromJson(it) },
    known = objects(j.getJSONArray("known")).map { ChargePlace.fromJson(it) },
    publicAcPrice = j.optDouble("publicAc", 0.0), publicDcPrice = j.optDouble("publicDc", 0.0), currency = j.optString("currency", "kr"),
)

fun main(args: Array<String>) {
    val dir = File(args[0])
    val assets = File(args[1])
    fun out(name: String, v: JSONObject) {
        val f = File(dir, name)
        check(!f.exists()) { "$name is already there: this program only writes new files" }
        f.writeText(v.toString(1))
        println("wrote $name")
    }

    // ---- Kotlin's random numbers, which the sample car is made from
    run {
        val o = JSONObject()
        for (seed in listOf(42, 7, 0, -1, 123456789)) {
            val r = Random(seed)
            val s = JSONObject()
            s.put("ints", list((1..10).map { r.nextInt().toString() }))
            s.put("oneToFour", list((1..10).map { r.nextInt(1, 4) }))
            s.put("fifty", list((1..10).map { r.nextInt(0, 50) }))
            s.put("ten", list((1..10).map { r.nextInt(10) }))
            s.put("eight", list((1..10).map { r.nextInt(0, 8) }))
            s.put("one", list((1..5).map { r.nextInt(0, 1) }))
            s.put("doubles", list((1..10).map { r.nextDouble().toString() }))
            s.put("ranged", list((1..10).map { r.nextDouble(3.0, 28.0).toString() }))
            o.put(seed.toString(), s)
        }
        out("random_expected.json", o)
    }

    // ---- The sample car in demo mode
    run {
        val o = JSONObject().put("now", OracleClock.now.toString())
        for (kind in listOf("electric", "hybrid", "fuel")) for (brand in listOf(Brand.PEUGEOT, Brand.OPEL)) {
            if (brand == Brand.OPEL && kind != "hybrid") continue
            Brands.current = brand
            Demo.choose(kind)
            Demo.reset()
            val k = JSONObject()
            k.put("kind", Demo.kind).put("vehicle", vehicle(Demo.vehicle)).put("carKind", Demo.vehicle.kind(null))
            k.put("status", status(Demo.status()))
            k.put("trips", list(Demo.trips().map { trip(it) }))
            k.put("sessions", list(Demo.sessions().map { session(it) }))
            val p = Demo.places()
            k.put("places", JSONObject().put("home", place(p.home)).put("known", list(p.known.map { place(it) }))
                .put("ac", dd(p.publicAcPrice)).put("dc", dd(p.publicDcPrice)).put("currency", p.currency))
            // Commands only change the sample car.
            val steps = JSONArray()
            for (label in listOf("Unlock", "Pre-heating", "Stop charging", "Full charge for a trip", "Lock", "Stop pre-heating", "Charge now", "Daily charge limit", "Horn")) {
                Demo.apply(label)
                steps.put(JSONObject().put("label", label).put("status", status(Demo.status())))
            }
            k.put("steps", steps)
            Demo.reset()
            o.put(if (brand == Brand.PEUGEOT) kind else "$kind-${brand.id}", k)
        }
        Brands.current = Brand.PEUGEOT
        Demo.choose("nonsense")
        o.put("unknownKind", Demo.kind)
        Demo.choose("electric")
        out("demo_expected.json", o)
    }

    // ---- Cars as the server lists them
    run {
        val inp = JSONObject(File(dir, "vehicles_inputs.json").readText())
        val statuses = inp.getJSONObject("statuses")
        val res = JSONArray()
        for (c in objects(inp.getJSONArray("cases"))) {
            val v = Vehicle.fromApi(c.getJSONObject("api"), c.getString("defaultBrand"))
            fun describe(v: Vehicle): JSONObject {
                val d = JSONObject().put("fields", vehicle(v)).put("detection", v.mildHybridDetection.name).put("reportsMildHybrid", v.reportsMildHybrid)
                    .put("needsBranding", v.needsBranding).put("kind", list(listOf(null, true, false).map { v.kind(it) }))
                    .put("json", v.toJson().toString()).put("again", vehicle(Vehicle.fromJson(v.toJson())))
                val parsed = JSONObject()
                for (name in statuses.keys()) parsed.put(name, list(listOf(null, true, false).map { o ->
                    val st = v.parseStatus(statuses.getJSONObject(name), null, o)
                    "${st.energyType}|${st.batteryPct}|${st.rangeKm}|${st.isMildHybrid}|${st.fuelPct}|${st.plugged}|${st.chargingStatus}"
                }))
                return d.put("status", parsed)
            }
            val one = JSONObject().put("name", c.getString("name")).put("car", describe(v))
            if (c.has("detail")) one.put("detailed", describe(v.withBrandingDetail(c.getJSONObject("detail"))))
            one.put("noDetail", vehicle(v.withBrandingDetail(null)))
            res.put(one)
        }
        out("vehicles_expected.json", JSONObject().put("cases", res))
    }

    // ---- Sign-in settings per brand and country (the secrets themselves are not written down again, only a fingerprint)
    run {
        val file = File(assets, "stellantis_configs.json")
        fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val o = JSONObject()
        for (b in Brand.entries) {
            val per = JSONObject().put("countries", list(StellantisConfig.countries(file, b)))
            for (cc in listOf("NO", "GB", "DE", "FR", "IS", "XX", "no", "")) {
                val c = StellantisConfig.load(file, b, cc)
                per.put("load:$cc", listOf(c.oauthUrl, c.realm, c.scheme, c.locale, c.clientId, c.culture, c.redirectUri, sha(c.clientSecret), sha(c.basicToken)).joinToString("|"))
            }
            o.put(b.id, per)
        }
        out("configs_expected.json", o)
    }

    // ---- Assorted small pieces
    run {
        val inp = JSONObject(File(dir, "misc_inputs.json").readText())
        val o = JSONObject()

        o.put("health", list(objects(inp.getJSONArray("health")).map { h ->
            val sessions = objects(h.getJSONArray("sessions")).map { ChargeSession.fromJson(it) }
            val r = ChargeSession.healthEstimate(sessions, if (h.isNull("newKwh")) null else h.getDouble("newKwh"))
            JSONObject().put("sessions", list(sessions.map { session(it) })).put("health", dd(r?.first)).put("used", nn(r?.second))
        }))

        o.put("match", list(objects(inp.getJSONArray("match")).map { m ->
            val cfg = placesOf(m.getJSONObject("places"))
            list(objects(m.getJSONArray("sessions")).map { s -> ChargeLog.match(ChargeSession.fromJson(s), cfg).let { "${it.kind}|${it.label}|${it.price}" } })
        }))
        o.put("distance", list(objects(inp.getJSONArray("match"))[0].getJSONArray("sessions").let { a -> objects(a) }.map { s ->
            if (s.isNull("lat") || s.isNull("lon")) JSONObject.NULL else dd(ChargeLog.distanceM(63.4305, 10.3951, s.getDouble("lat"), s.getDouble("lon")))
        }))

        val t = inp.getJSONObject("tags")
        val text = t.getString("text")
        val trips = objects(t.getJSONArray("trips")).map { Trip.parse(it) }
        val endsJson = t.getJSONObject("ends")
        fun ends(trip: Trip): TripEnds? = endsJson.optJSONArray(trip.id)?.let { TripEnds(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3)) }
        fun marks(tags: TripTags): JSONObject = JSONObject(TripTags.toJson(tags.data))
        val tg = JSONObject()
        tg.put("parsed", JSONObject(TripTags.toJson(TripTags.parse(text))))
        tg.put("empty", TripTags.toJson(TripTags.parse("")))
        tg.put("broken", TripTags.toJson(TripTags.parse("{not json")))
        var saved = ""
        val byLook = TripTags({ text }, { saved = it })
        tg.put("autoByLook", byLook.autoTag(trips)).put("afterByLook", marks(byLook)).put("savedByLook", JSONObject(saved))
        tg.put("tagOf", list(trips.map { byLook.tagOf(it.id)?.name })).put("isAuto", list(trips.map { byLook.isAuto(it.id) }))
        tg.put("againByLook", byLook.autoTag(trips))
        val byPlace = TripTags({ text }, {})
        tg.put("autoByPlace", byPlace.autoTag(trips, ::ends)).put("afterByPlace", marks(byPlace))
        // The user's own choices.
        val own = TripTags({ text }, {})
        own.set(trips[0], "th")
        own.set(trips[3], null)
        own.set(trips[1], "tw", ends(trips[7]))
        tg.put("afterSet", marks(own))
        own.autoTag(trips)
        own.set(trips[2], null)
        own.clearAuto()
        tg.put("afterClearAuto", marks(own))
        val made = own.create("  A very long name for a tag, longer than it may be  ", 12)
        val same = own.create("a VERY long name for a tag", 3)
        tg.put("created", "${made.id}|${made.name}|${made.color}|${same.id == made.id}")
        tg.put("byName", list(listOf("work", " CABIN ", "nope").map { own.byName(it)?.id }))
        own.edit("th", "  Cabin by the lake  ", -4, null, null)
        own.delete("tw")
        tg.put("afterEditDelete", marks(own))
        tg.put("speedOf", list(trips.map { dd(TripTags.speedOf(it)) }))
        tg.put("km", list(objects2(t.getJSONArray("pairs")).map { dd(TripTags.km(it[0], it[1], it[2], it[3])) }))
        val ps = objects2(t.getJSONArray("pairs"))
        tg.put("apart", list(listOf(0.3, 0.5, 20000.0).flatMap { tol -> listOf(
            TripTags.apart(TripEnds(63.4306, 10.3953, 63.4399, 10.4002), TripEnds(ps[0][0], ps[0][1], ps[0][2], ps[0][3]), tol),
            TripTags.apart(TripEnds(63.4401, 10.3999, 63.4304, 10.3950), TripEnds(ps[0][0], ps[0][1], ps[0][2], ps[0][3]), tol),
            TripTags.apart(TripEnds(60.0, 11.0, 60.1, 11.1), TripEnds(ps[0][0], ps[0][1], ps[0][2], ps[0][3]), tol),
        ).map { dd(it) } }))
        tg.put("likeness", list(objects2(t.getJSONArray("likeness")).map { dd(TripTags.likeness(it[0], it[1], TagMark("x", false, it[2], it[3], 0L))) }))
        tg.put("likenessNoSpeed", dd(TripTags.likeness(10.0, 30.0, TagMark("x", false, 10.0, null, 0L))))
        tg.put("kmTol", list((0 until t.getJSONArray("kmTol").length()).map { dd(TripTags.kmTol(t.getJSONArray("kmTol").getDouble(it))) }))
        o.put("tags", tg)

        val w = inp.getJSONObject("weather")
        o.put("feels", list(objects2(w.getJSONArray("feels")).map { dd(Weather(it[0], it[1], "cloudy", emptyList(), null).feelsLikeC) }))
        o.put("symbols", list((0 until w.getJSONArray("symbols").length()).map { w.getJSONArray("symbols").getString(it) }.map { WeatherApi.describe(it) + "|" + WeatherApi.kind(it) }))

        val clock = objects2(inp.getJSONArray("clock"))
        val patterns = (0 until inp.getJSONArray("patterns").length()).map { inp.getJSONArray("patterns").getString(it) }
        val tf = JSONObject()
        for (use in listOf(true, false)) {
            TimeFmt.use24 = use
            tf.put("hm$use", list(clock.map { TimeFmt.hm(it[0].toInt(), it[1].toInt()) })).put("pattern$use", list(patterns.map { TimeFmt.pattern(it) }))
        }
        TimeFmt.use24 = true
        o.put("timeFmt", tf)

        o.put("urls", list((0 until inp.getJSONArray("urls").length()).map { java.net.URLEncoder.encode(inp.getJSONArray("urls").getString(it), "UTF-8") }))
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        o.put("iso", list((0 until inp.getJSONArray("iso").length()).map { iso.format(java.util.Date(inp.getJSONArray("iso").getLong(it))) }))
        o.put("fixed4", list((0 until inp.getJSONArray("fixed4").length()).map { String.format(Locale.US, "%.4f", inp.getJSONArray("fixed4").getDouble(it)) }))
        out("misc_expected.json", o)
    }
}

/** A list of lists of numbers. */
private fun objects2(a: JSONArray): List<List<Double>> = (0 until a.length()).map { i -> a.getJSONArray(i).let { r -> (0 until r.length()).map { r.getDouble(it) } } }
