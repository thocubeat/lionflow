package no.lionflow.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEnergyTest {
    private fun trip(json: String) = Trip.parse(JSONObject(json))

    @Test fun carFigureIsKept() {
        val t = TripEnergy.fill(trip("""{"id":"a","distance":12.4,"energyConsumptions":[{"type":"Electric","consumption":2150,"avgConsumption":17340}]}"""), 73.0)
        assertEquals(2.15, t.kwh!!, 1e-9)
        assertEquals(17.34, t.avgKwhPer100!!, 1e-9)
        assertFalse(t.energyEstimated)
    }

    @Test fun kwhSentInsteadOfWhIsRescaled() {
        val t = TripEnergy.fill(trip("""{"id":"a","distance":20,"energyConsumptions":[{"type":"Electric","consumption":3.6}]}"""), 73.0)
        assertEquals(3.6, t.kwh!!, 1e-9)
        assertEquals(18.0, t.avgKwhPer100!!, 1e-9)
        assertFalse(t.energyEstimated)
    }

    @Test fun batteryDropGivesKwhWhenTheCarSendsNone() {
        val t = TripEnergy.fill(trip("""{"id":"a","distance":40,"energyConsumptions":[{"type":"Electric","consumption":0}],
            "startEnergies":[{"type":"Electric","level":80}],"endEnergies":[{"type":"Electric","level":70}]}"""), 73.0)
        assertEquals(7.3, t.kwh!!, 1e-9)
        assertEquals(18.25, t.avgKwhPer100!!, 1e-9)
        assertTrue(t.energyEstimated)
    }

    @Test fun averageTimesDistance() {
        val t = TripEnergy.fill(trip("""{"id":"a","distance":50,"energyConsumptions":[{"type":"Electric","avgConsumption":16000}]}"""), null)
        assertEquals(8.0, t.kwh!!, 1e-9)
        assertTrue(t.energyEstimated)
    }

    @Test fun loggedTripFillsTheGap() {
        val logged = Trip("car-1", 1_000_000L, 2_800_000L, 15.2, 1800.0, 2.9, 19.1, null)
        val t = TripEnergy.fill(Trip("s", 1_100_000L, 2_700_000L, 15.0, 1600.0, null, null, null), null, listOf(logged))
        assertEquals(2.9, t.kwh!!, 1e-9)
        assertTrue(t.energyEstimated)
    }

    @Test fun nonsenseIsLeftOut() {
        val t = TripEnergy.fill(trip("""{"id":"a","distance":2,
            "startEnergies":[{"type":"Electric","level":80}],"endEnergies":[{"type":"Electric","level":60}]}"""), 73.0)
        assertNull(t.kwh)
    }

    @Test fun mildHybridGetsNoBatteryEstimate() {
        val t = TripEnergy.fill(Trip.parse(JSONObject("""{"id":"a","distance":40,
            "startEnergies":[{"type":"Electric","level":80}],"endEnergies":[{"type":"Electric","level":70}]}"""), mildHybrid = true), 0.9)
        assertNull(t.kwh)
    }
}
