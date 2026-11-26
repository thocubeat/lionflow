// Runs the Android app's own logic on the fixture inputs and writes down its answers. See build.sh.
import no.lionflow.app.api.HttpWords
import no.lionflow.app.api.num
import no.lionflow.app.api.str
import no.lionflow.app.data.AppSettings
import no.lionflow.app.data.HomeLayout
import no.lionflow.app.model.Brand
import no.lionflow.app.model.Brands
import no.lionflow.app.model.CarAlert
import no.lionflow.app.model.CarData
import no.lionflow.app.model.FuelStats
import no.lionflow.app.model.HybridSplit
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.Caps
import no.lionflow.app.ui.Tab
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.minutesAgo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

fun d(v: Double?): Any = v?.toString() ?: JSONObject.NULL
fun l(v: Long?): Any = v?.toString() ?: JSONObject.NULL
fun n(v: Any?): Any = v ?: JSONObject.NULL
fun arr(list: Iterable<Any?>): JSONArray = JSONArray().also { a -> list.forEach { a.put(n(it)) } }

fun tripJson(t: Trip): JSONObject = JSONObject()
    .put("id", t.id).put("start", l(t.startMillis)).put("end", l(t.endMillis)).put("km", d(t.distanceKm)).put("dur", d(t.durationSec))
    .put("kwh", d(t.kwh)).put("avgKwh", d(t.avgKwhPer100)).put("speed", d(t.avgSpeedKmh)).put("liters", d(t.liters)).put("avgL", d(t.avgLPer100))
    .put("fs", d(t.fuelStartPct)).put("fe", d(t.fuelEndPct))

fun main(args: Array<String>) {
    val dir = File(args[0])
    fun out(name: String, v: Any) { File(dir, name).writeText(if (v is JSONObject) v.toString(1) else (v as JSONArray).toString(1)); println("wrote $name") }

    // ---- JSON as Android reads and writes it
    run {
        val inp = JSONObject(File(dir, "json_inputs.json").readText())
        val keys = inp.getJSONArray("keys").let { a -> (0 until a.length()).map { a.getString(it) } }
        val res = JSONArray()
        val texts = inp.getJSONArray("texts")
        for (i in 0 until texts.length()) {
            val j = JSONObject(texts.getString(i))
            val probes = JSONObject()
            for (k in keys) probes.put(k, JSONObject().put("has", j.has(k)).put("isNull", j.isNull(k)).put("optString", j.optString(k))
                .put("optDouble", j.optDouble(k).toString()).put("optInt", j.optInt(k).toString()).put("optLong", j.optLong(k).toString())
                .put("optBoolean", j.optBoolean(k)).put("optBooleanT", j.optBoolean(k, true)).put("str", n(j.str(k))).put("num", d(j.num(k)))
                .put("isObject", j.optJSONObject(k) != null).put("isArray", j.optJSONArray(k) != null))
            val a = j.optJSONArray("arr")
            val items = JSONArray()
            if (a != null) for (x in 0..a.length()) items.put(JSONObject().put("optString", a.optString(x)).put("optDouble", a.optDouble(x).toString())
                .put("optInt", a.optInt(x).toString()).put("optBoolean", a.optBoolean(x)).put("isNull", a.isNull(x)))
            res.put(JSONObject().put("keys", arr(j.keys().asSequence().toList())).put("toString", j.toString()).put("toString2", j.toString(2)).put("probes", probes).put("arr", items))
        }
        out("json_expected.json", res)
    }

    // ---- The car's status
    run {
        val cases = JSONArray(File(dir, "status_inputs.json").readText())
        val res = JSONArray()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val st = VehicleStatus.parse(c.getJSONObject("status"), c.optJSONObject("maintenance"), c.optBoolean("mildHybrid"), c.optBoolean("requireChargingEvidence"))
            val o = JSONObject().put("name", c.getString("name"))
                .put("batteryPct", d(st.batteryPct)).put("rangeKm", d(st.rangeKm)).put("plugged", st.plugged).put("chargingStatus", n(st.chargingStatus))
                .put("chargingMode", n(st.chargingMode)).put("chargingRateKmh", d(st.chargingRateKmh)).put("remainingMinutes", n(st.remainingMinutes?.toString()))
                .put("nextDelayedHour", n(st.nextDelayedHour?.toString())).put("nextDelayedMinute", n(st.nextDelayedMinute?.toString()))
                .put("batteryHealthPct", d(st.batteryHealthPct)).put("odometerKm", d(st.odometerKm)).put("locked", n(st.locked?.toString()))
                .put("openDoors", arr(st.openDoors)).put("outsideTempC", d(st.outsideTempC)).put("preconditioningOn", st.preconditioningOn)
                .put("programs", arr(st.programs.map { "${it.slot}|${it.days.joinToString(",")}|${it.hour}|${it.minute}|${it.enabled}" }))
                .put("lat", d(st.lat)).put("lon", d(st.lon)).put("ignition", n(st.ignition)).put("moving", st.moving).put("updatedAtMillis", l(st.updatedAtMillis))
                .put("kmToService", d(st.kmToService)).put("daysToService", d(st.daysToService)).put("residualKwh", d(st.residualKwh)).put("capacityKwh", d(st.capacityKwh))
                .put("energyAtMillis", l(st.energyAtMillis)).put("chargeType", n(st.chargeType)).put("energyType", st.energyType).put("fuelPct", d(st.fuelPct))
                .put("fuelRangeKm", d(st.fuelRangeKm)).put("isMildHybrid", st.isMildHybrid).put("heading", d(st.heading)).put("privacyState", n(st.privacyState))
                .put("fogLightsOn", arr(st.fogLightsOn)).put("alarmOn", n(st.alarmOn?.toString())).put("fuelTotalRaw", d(st.fuelTotalRaw))
                .put("isCharging", st.isCharging).put("privacyOn", st.privacyOn).put("programsJson", st.programsJson().toString())
                .put("caps", arr(listOf(Caps.battery(st), Caps.fuel(st), Caps.position(st), Caps.climate(st), Caps.lockState(st)) + Tab.entries.map { Caps.tab(it, st) }))
                .put("flatten", arr(CarData.flatten(st.raw).map { it.first + " = " + it.second })).put("pretty", CarData.pretty(st.raw))
                .put("lifetimeLiters", d(FuelStats.lifetimeLiters(st))).put("lifetimeLPer100", d(FuelStats.lifetimeLPer100(st)))
            res.put(o)
        }
        out("status_expected.json", res)
    }

    // ---- Trips and what is worked out from them
    run {
        val raw = JSONArray(File(dir, "trips_inputs.json").readText())
        val objs = (0 until raw.length()).map { raw.getJSONObject(it) }
        val trips = objs.map { Trip.parse(it) }
        val o = JSONObject().put("trips", arr(trips.map { tripJson(it) })).put("mild", arr(objs.map { tripJson(Trip.parse(it, true)) }))
        o.put("monthly", arr(FuelStats.monthly(trips).map { JSONObject().put("start", l(it.startMillis)).put("km", d(it.km)).put("trips", it.trips).put("liters", d(it.liters))
            .put("kmWithFuel", d(it.kmWithFuel)).put("seconds", d(it.seconds)).put("lPer100", d(it.lPer100)) }))
        val tank = FuelStats.tankEstimate(trips)
        o.put("tank", d(tank)).put("liters", d(FuelStats.liters(trips))).put("cost", d(FuelStats.cost(FuelStats.liters(trips), 21.49))).put("noCost", d(FuelStats.cost(0.0, 20.0)))
        for ((name, t) in listOf("fillUps" to tank, "fillUps50" to 50.0, "fillUpsNoTank" to null))
            o.put(name, arr(FuelStats.fillUps(trips, t).map { JSONObject().put("at", l(it.atMillis)).put("from", d(it.fromPct)).put("to", d(it.toPct)).put("liters", d(it.liters)).put("km", d(it.kmSincePrevious)) }))
        val r = FuelStats.records(trips)
        o.put("records", JSONObject().put("longest", n(r.longest?.id)).put("longestTime", n(r.longestTime?.id)).put("mostEconomical", n(r.mostEconomical?.id))
            .put("thirstiest", n(r.thirstiest?.id)).put("bestDay", l(r.bestDayMillis)).put("bestDayKm", d(r.bestDayKm)))
        val e = FuelStats.records(emptyList())
        o.put("recordsEmpty", "${e.longest}|${e.longestTime}|${e.mostEconomical}|${e.thirstiest}|${e.bestDayMillis}|${e.bestDayKm}")
        val h = HybridSplit.of(trips)
        o.put("hybrid", JSONObject().put("totalKm", d(h.totalKm)).put("evKm", d(h.evKm)).put("engineKm", d(h.engineKm)).put("kwh", d(h.kwh)).put("evKwhPer100", d(h.evKwhPer100)).put("evShare", d(h.evShare)))
        o.put("perUnit", arr(listOf(84_000.0 to 14_000.0, 210_000.0 to 14_000.0, 10.0 to 100.0, 900.0 to 9000.0, 5.5 to 100000.0, 600.0 to 299.0, 0.0 to 5000.0).map { d(FuelStats.litersPerUnit(it.first, it.second)) }))
        out("trips_expected.json", o)
    }

    // ---- Warnings from the car
    run {
        val list = listOf(
            """{"type":"TyrePressureFault","startedAt":"2026-11-20T10:00:00Z"}""", """{"type":"LOW_OIL_LEVEL","active":false}""", """{"label":"ABSFault2Detected","createdAt":"2026-11-21T10:00:00.000Z"}""",
            """{"code":"P0420","endAt":null,"active":true}""", """{"type":"TyrePressureFault"}""", """{"type":"Ended","endAt":"2026-11-22T10:00:00Z"}""", """{"type":null,"label":" ","code":""}""",
            """{"type":"washerFluid_low","startedAt":"","createdAt":"2026-11-23T08:00:00+01:00"}""").map { JSONObject(it) }
        out("alerts_expected.json", arr(CarAlert.parse(list).map { "${it.type}|${it.label}|${it.atMillis}" }))
    }

    // ---- Numbers and times as text
    run {
        val o = JSONObject()
        val rnd = java.util.Random(11)
        val values = mutableListOf(0.0, -0.0, 0.05, 0.25, 0.35, 0.5, 1.5, 2.5, 0.15, 0.45, 0.049, 0.95, 0.995, 1.005, 9.995, 12.345, 99.95, 999.5, 1234.5, 13412.4, 13411.5, 1e7, 123456789.125, -0.04, -0.05, -2.5, -1234.56, 5e-5, 72.99999999999999, 17.25, 6.125, 0.1 + 0.2)
        repeat(300) { values += Math.round(rnd.nextDouble() * 2000_00) / 1000.0 }
        repeat(100) { values += rnd.nextDouble() * 300 - 50 }
        o.put("fixed", arr(values.flatMap { v -> listOf(0, 1, 2).map { p -> JSONArray().put(v.toString()).put(p).put(String.format(Locale.US, "%.${p}f", v)) } }))
        o.put("grouped", arr(listOf(0L, 7L, 999L, 1000L, 13412L, 1234567L, -1234L, -999L).map { JSONArray().put(it.toString()).put(String.format(Locale.US, "%,d", it)) }))
        o.put("round", arr(listOf(0.0, 0.5, 1.5, 2.5, -0.5, -1.5, -2.5, 2.4999, 1e12 + 0.5, -7.5000001, 63.5, 64.49).map { JSONArray().put(it.toString()).put(Math.round(it).toString()) }))
        o.put("javaDouble", arr(listOf(0.0, 1.0, 12.0, 0.1, 0.001, 0.0001, 1234567.0, 12345678.0, 1.0E7, 9999999.0, 1e21, 1e-5, 123.456, -42.5, 6.02e23, 0.5, 100.0, 3.3e-4).map { JSONArray().put(it.toString()) }))
        val km = AppSettings(); val mi = AppSettings(distanceUnit = "mi", tempUnit = "f")
        o.put("distance", arr(listOf(null, 0.0, 0.4, 0.5, 12.5, 999.5, 13412.4, 1234567.8).flatMap { v -> listOf(km, mi).map { s -> JSONArray().put(d(v)).put(s.distanceUnit).put(fmtDistance(v, s)) } }))
        o.put("temp", arr(listOf(null, Double.NaN, 0.0, -0.5, 4.5, 21.4, -12.6, 37.0).flatMap { v -> listOf(km, mi).flatMap { s -> listOf(true, false).map { u -> JSONArray().put(d(v)).put(s.tempUnit).put(u).put(fmtTemp(v, s, u)) } } }))
        val now = 1_800_000_000_000L
        o.put("minutesAgo", arr(listOf(0L, -5L, now, now - 59_999, now - 60_000, now - 59 * 60_000L, now - 60 * 60_000L, now - 23 * 3_600_000L - 59 * 60_000L, now - 24 * 3_600_000L, now - 9 * 86_400_000L, now + 120_000).map { JSONArray().put(it.toString()).put(minutesAgo(it, now)) }))
        o.put("now", now.toString())
        o.put("pt", arr(listOf(null, "", " ", "PT7H30M", "PT45M", "PT2H", "PT0S", "PT12H5M30S", "7:30", "PTxHyM", "PT1H2H3M4M", "P1DT2H").map { JSONArray().put(n(it)).put(n(VehicleStatus.parsePt(it)?.let { p -> "${p.first}:${p.second}" })) }))
        o.put("iso", arr(listOf(null, "", "2026-11-25T17:42:10Z", "2026-11-25T17:42:10.250Z", "2026-06-01T08:00:00+02:00", "2026-06-01T08:00:00.000+02:00", "2026-06-01T08:00:00-0500", "2026-11-25T17:42:10", "2026-11-25 17:42:10Z",
            "1970-01-01T00:00:00Z", "2024-02-29T23:59:59Z", "2026-13-01T00:00:00Z", "2026-11-25T25:61:61Z", "2026-11-25T17:42:10.5Z", "2026-11-25T17:42:10.1234567Z", "2026-11-25T17:42:10Zjunk", "2026-1-5T7:4:1Z", "2026-11-25T17:42:10+02", " 2026-11-25T17:42:10Z", "1999-12-31T23:59:59.999-11:30")
            .map { JSONArray().put(n(it)).put(l(VehicleStatus.parseIso(it))) }))
        o.put("compass", arr(listOf(0.0, 22.4, 22.5, 44.9, 90.0, 118.0, 180.0, 247.4, 247.5, 337.4, 337.5, 359.9, 360.0, 400.0, -10.0).map { JSONArray().put(it.toString()).put(CarData.compass(it)) }))
        out("fmt_expected.json", o)
    }

    // ---- Home screen choices
    run {
        val o = JSONObject()
        val apply: (HomeLayout.Item) -> Boolean = { it.needs != "fuel" }
        val orders = listOf("", "parked,battery", "cardata,unknown, service ,warnings", "fuel,charging,monthCost,cardata,service,tripsClimate,parked,actions,lock,battery,warnings")
        o.put("ordered", arr(orders.map { HomeLayout.ordered(HomeLayout.PHONE, it).joinToString(",") { i -> i.id } }))
        o.put("orderedWatch", HomeLayout.ordered(HomeLayout.WATCH, "service,doors").joinToString(",") { it.id })
        val moves = JSONArray()
        for (order in orders) for (id in listOf("warnings", "parked", "service", "cardata", "fuel", "nope")) for (up in listOf(true, false))
            moves.put(JSONArray().put(order).put(id).put(up).put(HomeLayout.move(HomeLayout.PHONE, order, id, up, apply)))
        o.put("moves", moves)
        o.put("toggle", arr(listOf(Triple("charging,monthCost", "charging", true), Triple("a", "b", false), Triple("a,b", "b", false), Triple("", "x", true), Triple(" a , b ,", "a", true), Triple("a,a", "a", true))
            .map { JSONArray().put(it.first).put(it.second).put(it.third).put(HomeLayout.toggle(it.first, it.second, it.third)) }))
        o.put("phoneCards", arr(listOf(AppSettings(), AppSettings(homeOrder = "cardata,parked", homeHidden = "battery,lock, fuel"), AppSettings(homeHidden = "")).map { HomeLayout.phoneCards(it).joinToString(",") }))
        o.put("defaults", arr(listOf(HomeLayout.PHONE_HIDDEN, HomeLayout.WATCH_HIDDEN, HomeLayout.CAR_HIDDEN, HomeLayout.PHONE.joinToString(";") { "${it.id}|${it.title}|${it.note}|${it.needs}" },
            HomeLayout.WATCH.joinToString(";") { "${it.id}|${it.title}|${it.note}|${it.needs}" })))
        out("layout_expected.json", o)
    }

    // ---- HTTP errors in words
    run {
        val cases = listOf(
            Triple(503, "<html><body><h1>503 Service Temporarily Unavailable</h1></body></html>", "api.groupe-psa.com"),
            Triple(400, """{"error":"invalid_grant","error_description":"grant is invalid"}""", "idpcvs.peugeot.com"),
            Triple(404, """{"code":40400,"uuid":"x","message":"NOT_FOUND"}""", "api-cert.groupe-psa.com"),
            Triple(404, """{"code":404,"message":""}""", "api.met.no"),
            Triple(429, "", "nobil.no"), Triple(500, """{"errors":[{"code":"E12","message":""}],"error":null}""", "example.org"),
            Triple(418, "  <p>I am   a\n teapot</p> plain  text " + "x".repeat(200), "mwa.mpsa.com"),
            Triple(401, """{"message":"Unauthorized","error":"Unauthorized","code":"401"}""", "stellantis.example"),
            Triple(409, """[1,2]""", "example.org"), Triple(502, """{"message":"up \/ down"}""", "example.org"))
        out("http_expected.json", arr(cases.map { JSONArray().put(it.first).put(it.second).put(it.third).put(HttpWords.describeHttpError(it.first, it.second, it.third)) }))
    }

    // ---- Translations
    run {
        val tsv = File(args[1])
        val keys = tsv.readLines(Charsets.UTF_8).drop(1).map { it.split('\t')[0] }.filter { it.isNotEmpty() }
        fun unesc(s: String) = s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\")
        val samples = listOf("5", "12:30", "Home", "Peugeot", "3 min ago", "Charging", "80 %", "E-5008", "north-east")
        val inputs = LinkedHashSet<String>()
        var x = 0
        for (k in keys) {
            val en = unesc(k)
            inputs += en
            if (Regex("\\{\\d\\}").containsMatchIn(en)) repeat(2) { inputs += Regex("\\{(\\d)\\}").replace(en) { m -> samples[(m.groupValues[1].toInt() + x++) % samples.size] } }
            if (en.endsWith(" ") && en.isNotBlank()) inputs += en + samples[x++ % samples.size]
            if (en.startsWith(" ") && en.isNotBlank()) inputs += samples[x++ % samples.size] + en
        }
        inputs += listOf("Locked · Doors closed", "Plugged in · Charging · 80 %", "Updated 3 min ago", "Updated just now", "Parked at Home", "Charging\nPlugged in", "Yes — No", "not a known text", "", "  ",
            "MyPeugeot needs a subscription", "Peugeot's servers are down or busy right now (error 503). Nothing is wrong with your car. LionFlow will try again in a minute.", "7 days: 1,234 km", "1 warning from the car", "12 warnings from the car",
            "Facing north-east", "Last report 2 h ago. LionFlow shows what the car last sent to the carmaker's server. If the official app has no new data either, the car's connection is asleep or the carmaker's service has a problem, and no app can fix that from the phone.")
        val o = JSONObject().put("inputs", arr(inputs))
        for (lang in listOf("en", "fr", "de", "es", "nb", "nl")) {
            L10n.init(tsv, lang)
            o.put(lang, arr(inputs.map { L10n.tr(it) }))
        }
        val brandInputs = listOf("MyPeugeot", "Peugeot", "Open MyPeugeot and sign in", "My Peugeot", "not affiliated with Peugeot", "Sign in with your MyPeugeot account", "Peugeot's servers are down or busy right now (error 503). Nothing is wrong with your car. LionFlow will try again in a minute.")
        val b = JSONObject()
        for (brand in Brand.entries) {
            Brands.current = brand
            val per = JSONObject()
            for (lang in listOf("en", "nb", "fr")) { L10n.init(tsv, lang); per.put(lang, arr(brandInputs.map { L10n.tr(it) })) }
            per.put("notice", Brands.subscriptionNotice()).put("fields", "${brand.id}|${brand.jsonKey}|${brand.display}|${brand.app}|${brand.workshopQuery}|${brand.eRemotePack}|${brand.doorsPack}")
            b.put(brand.id, per)
        }
        Brands.current = Brand.PEUGEOT
        o.put("brandInputs", arr(brandInputs)).put("brands", b)
        o.put("fromCar", arr(listOf("Citroen", "CITROËN", " ds ", "Opel", "vauxhall", "Fiat", null, "peugeot").map { JSONArray().put(n(it)).put(n(Brand.fromCar(it)?.id)) }))
        out("l10n_expected.json", o)
    }
}
