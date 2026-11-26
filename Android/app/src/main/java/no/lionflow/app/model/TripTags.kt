package no.lionflow.app.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** A label the user puts on trips ("Work", "Client A", "Holiday"). [color] is an index into the 7 tag colours. */
data class TripTag(val id: String, val name: String, val color: Int, val from: TagPlace? = null, val to: TagPlace? = null)

/** An address the user chose as one end of a tag's route. */
data class TagPlace(val name: String, val lat: Double, val lon: Double)

/** Where a trip started and ended (from the route recorded on the phone). */
data class TripEnds(val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double)

/**
 * What a trip is tagged with. [tag] is empty when the user removed the tag on purpose (then it is never
 * tagged automatically again). [km] and [speed] are kept so later trips on the same route can be recognised.
 */
data class TagMark(val tag: String, val auto: Boolean, val km: Double, val speed: Double?, val at: Long, val ends: TripEnds? = null)

data class TagData(val tags: List<TripTag> = emptyList(), val marks: Map<String, TagMark> = emptyMap())

/** The user's trip tags, kept on the phone only. */
class TripTags(load: () -> String, private val save: (String) -> Unit) {
    var data by mutableStateOf(parse(load()))
        private set

    fun tagOf(tripId: String): TripTag? = data.marks[tripId]?.tag?.takeIf { it.isNotEmpty() }?.let { id -> data.tags.firstOrNull { it.id == id } }
    fun isAuto(tripId: String): Boolean = data.marks[tripId]?.auto == true
    fun byName(name: String): TripTag? = data.tags.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    fun create(name: String, color: Int): TripTag {
        byName(name)?.let { return it }
        val t = TripTag("t" + System.currentTimeMillis().toString(36) + data.tags.size, name.trim().take(MAX_NAME), color.coerceIn(0, COLORS - 1))
        put(data.copy(tags = data.tags + t))
        return t
    }

    fun edit(id: String, name: String, color: Int, from: TagPlace?, to: TagPlace?) =
        put(data.copy(tags = data.tags.map { if (it.id == id) it.copy(name = name.trim().take(MAX_NAME), color = color.coerceIn(0, COLORS - 1), from = from, to = to) else it }))

    /** Removes the tag everywhere. */
    fun delete(id: String) = put(TagData(data.tags.filter { it.id != id }, data.marks.filterValues { it.tag != id }))

    /** The user's own choice for a trip; null takes the tag off. */
    fun set(trip: Trip, tagId: String?, ends: TripEnds? = null) {
        val mark = TagMark(tagId.orEmpty(), false, trip.distanceKm, speedOf(trip), trip.startMillis ?: System.currentTimeMillis(), ends)
        // A trip that never had a tag just goes back to having none.
        val marks = if (tagId == null && data.marks[trip.id]?.auto != true) data.marks - trip.id else data.marks + (trip.id to mark)
        put(data.copy(marks = marks))
    }

    /**
     * Tags trips that look like a trip the user tagged before: about the same distance and average speed.
     * Trips the user has already decided on are left alone. Returns how many were tagged.
     *
     * With [ends] (the "same route" option), a trip whose recorded start and end are known is matched by
     * place instead: first against the addresses set on a tag, then against tagged trips that were recorded.
     * Both directions count, so the drive home gets the same tag. Trips without a recording, and tagged
     * trips without one, still go by distance and speed.
     */
    fun autoTag(trips: List<Trip>, ends: ((Trip) -> TripEnds?)? = null): Int {
        val known = data.marks.values.filter { !it.auto && it.tag.isNotEmpty() && data.tags.any { t -> t.id == it.tag } }
        val ruled = data.tags.filter { it.from != null && it.to != null }
        if (known.isEmpty() && (ends == null || ruled.isEmpty())) return 0
        val marks = data.marks.toMutableMap()
        var n = 0
        for (t in trips) {
            if (marks.containsKey(t.id)) continue
            val speed = speedOf(t)
            val e = ends?.invoke(t)
            var tag: String? = null
            if (e != null) {
                tag = ruled.mapNotNull { r -> apart(e, TripEnds(r.from!!.lat, r.from.lon, r.to!!.lat, r.to.lon), RULE_KM)?.let { it to r.id } }.minByOrNull { it.first }?.second
                    ?: known.mapNotNull { k -> k.ends?.let { ke -> apart(e, ke, PLACE_KM) }?.let { it to k.tag } }.minByOrNull { it.first }?.second
            }
            if (tag == null && speed != null) {
                // By distance and speed. A recorded trip is never matched this way against another recorded trip: their places differ.
                tag = known.filter { e == null || it.ends == null }.mapNotNull { k -> likeness(t.distanceKm, speed, k)?.let { it to k.tag } }.minByOrNull { it.first }?.second
            }
            if (tag == null) continue
            marks[t.id] = TagMark(tag, true, t.distanceKm, speed, t.startMillis ?: System.currentTimeMillis(), e)
            n++
        }
        if (n > 0) put(data.copy(marks = marks))
        return n
    }

    /** Takes off every tag that was put on automatically (used when the feature is turned off). */
    fun clearAuto() = put(data.copy(marks = data.marks.filterValues { !it.auto }))

    private fun put(d: TagData) {
        // Keep the newest marks only, so this never grows without end.
        val marks = if (d.marks.size > MAX_MARKS) d.marks.entries.sortedByDescending { it.value.at }.take(MAX_MARKS).associate { it.key to it.value } else d.marks
        data = d.copy(marks = marks)
        save(toJson(data))
    }

    companion object {
        const val COLORS = 7
        const val MAX_NAME = 24
        private const val MAX_MARKS = 3000
        /** How far apart two drives of the same route can be. */
        const val SPEED_TOL = 8.0
        /** How close (km) a trip's start and end must be to a tagged trip's, and to a tag's addresses. */
        const val PLACE_KM = 0.3
        const val RULE_KM = 0.5

        fun km(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
            val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLon / 2).let { it * it }
            return 6371.0 * 2 * Math.asin(Math.sqrt(h))
        }

        /** How far apart (km, both ends added) two trips are, driven either way; null when not the same two places. */
        fun apart(x: TripEnds, y: TripEnds, tol: Double): Double? {
            val same = km(x.aLat, x.aLon, y.aLat, y.aLon) to km(x.bLat, x.bLon, y.bLat, y.bLon)
            val back = km(x.aLat, x.aLon, y.bLat, y.bLon) to km(x.bLat, x.bLon, y.aLat, y.aLon)
            return listOf(same, back).filter { it.first <= tol && it.second <= tol }.minOfOrNull { it.first + it.second }
        }
        fun kmTol(km: Double): Double = (km * 0.1).coerceIn(0.5, 2.0)

        /** Average speed from distance and time (the car's own figure isn't reliable on every model). */
        fun speedOf(t: Trip): Double? =
            t.durationSec?.takeIf { it >= 60 && t.distanceKm > 0 }?.let { t.distanceKm / (it / 3600.0) } ?: t.avgSpeedKmh?.takeIf { it > 0 }

        /** Lower is more alike; null when the trip is not the same route. */
        fun likeness(km: Double, speed: Double, k: TagMark): Double? {
            val ks = k.speed ?: return null
            val dk = abs(km - k.km) / kmTol(k.km)
            val ds = abs(speed - ks) / SPEED_TOL
            return if (dk <= 1.0 && ds <= 1.0) dk + ds else null
        }

        fun parse(text: String): TagData = try {
            val j = JSONObject(text.ifBlank { "{}" })
            val tags = j.optJSONArray("tags")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
                .map { TripTag(it.optString("id"), it.optString("name"), it.optInt("color"), place(it.optJSONObject("from")), place(it.optJSONObject("to"))) }.filter { it.id.isNotEmpty() && it.name.isNotEmpty() }
            val m = j.optJSONObject("marks")
            val marks = HashMap<String, TagMark>()
            m?.keys()?.forEach { id ->
                val o = m.optJSONObject(id) ?: return@forEach
                val e = o.optJSONArray("ends")?.takeIf { it.length() == 4 }?.let { TripEnds(it.optDouble(0), it.optDouble(1), it.optDouble(2), it.optDouble(3)) }
                marks[id] = TagMark(o.optString("tag"), o.optBoolean("auto"), o.optDouble("km", 0.0), if (o.has("speed")) o.optDouble("speed") else null, o.optLong("at"), e)
            }
            TagData(tags, marks)
        } catch (_: Exception) { TagData() }

        private fun place(o: JSONObject?): TagPlace? = o?.takeIf { it.has("lat") && it.has("lon") }?.let { TagPlace(it.optString("n"), it.optDouble("lat"), it.optDouble("lon")) }
        private fun placeJson(p: TagPlace) = JSONObject().put("n", p.name).put("lat", p.lat).put("lon", p.lon)

        fun toJson(d: TagData): String {
            val tags = JSONArray()
            d.tags.forEach {
                val o = JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                it.from?.let { p -> o.put("from", placeJson(p)) }
                it.to?.let { p -> o.put("to", placeJson(p)) }
                tags.put(o)
            }
            val marks = JSONObject()
            d.marks.forEach { (id, k) ->
                val o = JSONObject().put("tag", k.tag).put("auto", k.auto).put("km", k.km).put("at", k.at)
                if (k.speed != null) o.put("speed", k.speed)
                k.ends?.let { e -> o.put("ends", JSONArray().put(e.aLat).put(e.aLon).put(e.bLat).put(e.bLon)) }
                marks.put(id, o)
            }
            return JSONObject().put("tags", tags).put("marks", marks).toString()
        }
    }
}
