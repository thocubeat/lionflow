package no.lionflow.app.api

import no.lionflow.app.model.Trip
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

internal data class TripHistory(val trips: List<Trip>, val noTrips: Boolean = false) {
    companion object {
        private const val MAX_PAGES = 50

        fun cutoff(nowMillis: Long, days: Int): Long =
            Instant.ofEpochMilli(nowMillis).minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()

        fun inPeriod(trips: List<Trip>, nowMillis: Long, days: Int): List<Trip> {
            val since = cutoff(nowMillis, days)
            return trips.filter { it.startMillis?.let { start -> start in since..nowMillis } == true }
        }

        suspend fun load(
            sinceMillis: Long,
            fetch: suspend (sinceIso: String, pageToken: String?) -> JSONObject,
        ): TripHistory {
            val since = Instant.ofEpochMilli(sinceMillis).truncatedTo(ChronoUnit.SECONDS).toString()
            val trips = linkedMapOf<String, Trip>()
            val seenTokens = mutableSetOf<String>()
            var token: String? = null
            repeat(MAX_PAGES) { index ->
                val response = try {
                    fetch(since, token)
                } catch (e: ApiException) {
                    if (index == 0 && e.code == 404) return TripHistory(emptyList(), noTrips = true)
                    throw e
                }

                for (row in response.obj("_embedded")?.optJSONArray("trips")?.objects().orEmpty()) {
                    val trip = Trip.parse(row).withStableId() ?: continue
                    val previous = trips[trip.id]
                    if (previous == null || (previous.startMillis == null && trip.startMillis != null)) {
                        trips[trip.id] = trip
                    }
                }
                val next = response.obj("_links", "next") ?: return TripHistory(
                    trips.values.sortedByDescending { it.startMillis ?: Long.MIN_VALUE },
                )
                val nextToken = pageToken(next.str("href")) ?: throw ApiException(0, "Invalid trips pagination link")
                if (!seenTokens.add(nextToken)) throw ApiException(0, "Repeated trips pagination token")
                token = nextToken
            }
            throw ApiException(0, "Trips pagination exceeded $MAX_PAGES pages")
        }

        private fun pageToken(href: String?): String? {
            if (href.isNullOrBlank()) return null
            return runCatching {
                URI(href).rawQuery?.split('&')?.firstNotNullOfOrNull { parameter ->
                    val (name, value) = parameter.split('=', limit = 2).let { it.first() to it.getOrNull(1) }
                    value?.takeIf { name == "pageToken" && it.isNotBlank() }
                }?.let { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        private fun Trip.withStableId(): Trip? {
            if (id.isNotEmpty()) return this
            if (startMillis == null && endMillis == null) return null
            val key = listOf(startMillis, endMillis, distanceKm, durationSec).joinToString("|")
            return copy(id = "anonymous-" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)))
        }
    }
}
