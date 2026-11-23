package no.lionflow.app.api

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import no.lionflow.app.model.Trip
import no.lionflow.app.model.VehicleStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripHistoryTest {
    @Test fun parsesStrictIsoInstantsWithFractionsAndOffsets() {
        val expected = Instant.parse("2026-10-06T08:30:00.123Z").toEpochMilli()
        for (time in listOf(
            "2026-10-06T08:30:00.123Z",
            "2026-10-06T10:30:00.123456+02:00",
            "2026-10-06T03:00:00.123-0530",
            "2026-10-06T10:30:00.123+0200",
        )) assertEquals(time, expected, VehicleStatus.parseIso(time))
        assertEquals(expected - 123, VehicleStatus.parseIso("2026-10-06T10:30:00+02:00"))
        for (time in listOf("2026-02-30T08:30:00Z", "2026-10-06T08:30:00Zjunk", "2026-10-06T08:30:00", "")) {
            assertNull(time, VehicleStatus.parseIso(time))
        }
    }

    @Test fun followsEncodedAndUnencodedOpaqueTokensAndDeduplicatesAcrossPages() = runBlocking {
        val pages = listOf(
            JSONObject("""
                {"_embedded":{"trips":[
                    {"id":"duplicate","startedAt":"invalid"},
                    {"id":null,"startedAt":"2026-10-06T08:30:00Z","distance":5},
                    {"id":"  ","startedAt":"2026-10-06T10:30:00+02:00","distance":5},
                    {"id":" ","startedAt":"2026-10-06T08:31:00Z","distance":5},
                    {"startedAt":"invalid"}
                ]},"_links":{"next":{"href":"/trips?pageToken=a%2B%2F%3D%26%25&unused=1"}}}
            """),
            JSONObject("""
                {"_embedded":{"trips":[
                    {"id":"duplicate","startedAt":"invalid","createdAt":"2026-10-06T08:32:00Z"}
                ]},"_links":{"next":{"href":"?pageToken=raw+token"}}}
            """),
            JSONObject("""{"_embedded":{"trips":[{"id":"last","startedAt":"2026-10-06T08:33:00Z"}]}}"""),
        )
        val tokens = mutableListOf<String?>()
        val result = TripHistory.load(Instant.parse("2026-10-06T00:00:00.850Z").toEpochMilli()) { since, token ->
            assertEquals("2026-10-06T00:00:00Z", since)
            tokens += token
            pages[tokens.lastIndex]
        }
        assertEquals(listOf(null, "a+/=&%", "raw+token"), tokens)
        assertEquals(4, result.trips.size)
        assertEquals(listOf("last", "duplicate"), result.trips.take(2).map { it.id })
        assertTrue(result.trips.drop(2).all { it.id.startsWith("anonymous-") })
        assertNotEquals(result.trips[2].id, result.trips[3].id)
    }

    @Test fun requestEncodesTheWholeCursorAndPreservesFilters() {
        val cursor = "a+/=&%"
        val request = "https://example.invalid/trips?client_id=synthetic".toHttpUrl()
            .withTripFilters("2026-10-06T00:00:00Z", cursor)
        assertEquals("synthetic", request.queryParameter("client_id"))
        assertEquals("0.1-", request.queryParameter("distance"))
        assertEquals("60", request.queryParameter("pageSize"))
        assertEquals("2026-10-06T00:00:00Z/", request.queryParameter("timestamps"))
        assertEquals(cursor, request.queryParameter("pageToken"))
        assertTrue(request.toString().contains("pageToken=a%2B%2F%3D%26%25"))
    }

    @Test fun repeatedCursorAndPageLimitFailExplicitly() = runBlocking {
        var calls = 0
        val repeated = runCatching {
            TripHistory.load(0) { _, _ ->
                calls++
                JSONObject("""{"_links":{"next":{"href":"?pageToken=repeat"}}}""")
            }
        }.exceptionOrNull()
        assertEquals("Repeated trips pagination token", repeated?.message)
        assertEquals(2, calls)

        calls = 0
        val limit = runCatching {
            TripHistory.load(0) { _, _ ->
                calls++
                JSONObject("""{"_links":{"next":{"href":"?pageToken=page-$calls"}}}""")
            }
        }.exceptionOrNull()
        assertEquals("Trips pagination exceeded 50 pages", limit?.message)
        assertEquals(50, calls)
    }

    @Test fun emptyFirstPageIsDistinctFromLaterRequestFailure() = runBlocking {
        assertTrue(TripHistory.load(0) { _, _ -> throw ApiException(404, "synthetic") }.noTrips)
        assertFalse(TripHistory.load(0) { _, _ -> JSONObject() }.noTrips)
        val failure = runCatching {
            TripHistory.load(0) { _, token ->
                if (token == null) JSONObject("""{"_links":{"next":{"href":"?pageToken=next"}}}""")
                else throw ApiException(404, "synthetic")
            }
        }.exceptionOrNull()
        assertEquals(404, (failure as? ApiException)?.code)
    }

    @Test fun rollingWindowIncludesBothBoundsAndExcludesMissingAndFutureDates() {
        val now = Instant.parse("2026-11-23T15:00:00Z").toEpochMilli()
        val lower = Instant.parse("2026-10-24T15:00:00Z").toEpochMilli()
        fun trip(id: String, at: Long?) = Trip(id, at, null, 1.0, null, null, null, null)
        val input = listOf(
            trip("now", now), trip("boundary", lower),
            trip("early", lower - 1), trip("future", now + 1), trip("unknown", null),
        )
        assertEquals(lower, TripHistory.cutoff(now, 30))
        assertEquals(listOf("now", "boundary"), TripHistory.inPeriod(input, now, 30).map { it.id })
        assertEquals(listOf("now"), TripHistory.inPeriod(input, now, 7).map { it.id })
    }
}
