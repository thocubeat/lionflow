package no.lionflow.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripCsvTest {
    @Test fun roundTripPreservesFuelAndItsProvenance() {
        val start = requireNotNull(TripImport.date("2026-10-06 10:30"))
        val trip = Trip("trip-a", start, start + 600_000, 12.5, 600.0, 1.25, 10.0, 75.0, 0.641, 5.128, true)
        val noFuel = trip.copy(id = "trip-b", liters = null, avgLPer100 = null, fuelUnitsEstimated = false)
        val zeroFuel = noFuel.copy(id = "trip-c", liters = 0.0, avgLPer100 = 0.0)
        val trips = listOf(trip, noFuel, zeroFuel)
        val exported = TripExport.csv(trips) { if (it == trip) "Commute" else null }
        val rows = TripImport.parse(exported)
        assertEquals("Commute", rows.first().tag)
        assertEquals(trips, rows.map { TripImport.restore(JSONObject(it.json.toString())) })
    }

    @Test fun previousLionFlowExportStillImportsWithoutFuel() {
        val row = TripImport.parse(
            """
            id,start,end,distance_km,duration_min,energy_kwh,avg_kwh_per_100km,avg_speed_kmh,tag
            legacy,2026-10-06 10:30,2026-10-06 10:40,12.5,10,1.25,10.0,75,Commute
            """.trimIndent()
        ).single()
        val trip = TripImport.restore(row.json)
        assertEquals("legacy", trip.id)
        assertEquals("Commute", row.tag)
        assertEquals(1.25, trip.kwh)
        assertNull(trip.liters)
        assertNull(trip.avgLPer100)
        assertFalse(trip.fuelUnitsEstimated)
    }

    @Test fun explicitlyFuelAverageDoesNotBecomeElectricalEnergy() {
        val row = TripImport.parse("start;distance;Average consumption (l/100 km)\n2026-10-06 10:30;12.5;5.2").single()
        assertNull(TripImport.restore(row.json).kwh)
    }

    @Test fun existingApiFuelHeuristicIsMarkedAsEstimated() {
        val trip = Trip.parse(JSONObject("""{"distance":10,"energyConsumptions":[{"type":"Fuel","consumption":500}]}"""))
        assertEquals(0.5, trip.liters)
        assertEquals(5.0, trip.avgLPer100)
        assertTrue(trip.fuelUnitsEstimated)
    }
}
