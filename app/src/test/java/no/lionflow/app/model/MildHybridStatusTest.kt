package no.lionflow.app.model

import no.lionflow.app.api.Vehicle
import no.lionflow.app.ui.Caps
import no.lionflow.app.ui.Tab
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MildHybridStatusTest {
    private val mixedStatus = """
        {
          "energies": [
            {"type":"Fuel","level":59,"autonomy":360},
            {"type":"Electric","level":null,"autonomy":null,
             "extension":{"electric":{"battery":{"load":{"residual":480}}}}}
          ],
          "odometer":{"mileage":15000}
        }
    """.trimIndent()

    @Test fun mildHybridDisplaysFuelAndNeverTreatsResidualAsBatteryEnergy() {
        val status = ChargePower.record(VehicleStatus.parse(JSONObject(mixedStatus), null, mildHybrid = true))
        assertTrue(status.isMildHybrid)
        assertTrue(status.usesFuelGauge)
        assertEquals(59.0, status.displayLevelPct)
        assertEquals(360.0, status.displayRangeKm)
        assertEquals(15000.0, status.odometerKm)
        assertNull(status.batteryPct)
        assertNull(status.rangeKm)
        assertNull(status.residualKwh)
        assertNull(status.capacityKwh)
        assertNull(status.chargingStatus)
        assertFalse(status.plugged)
        assertFalse(Caps.tab(Tab.Charging, status))
        assertTrue(Caps.fuel(status))
    }

    @Test fun missingElectricReadingsAloneDoNotReclassifyAPlugIn() {
        val status = VehicleStatus.parse(JSONObject(mixedStatus), null)
        assertEquals("Hybrid", status.energyType)
        assertEquals(0.0, status.displayLevelPct)
        assertEquals(0.0, status.displayRangeKm)
        assertEquals(59.0, status.fuelPct)
        assertTrue(Caps.tab(Tab.Charging, status))
    }

    @Test fun preservesExistingElectricFuelAndPlugInReadings() {
        val electric = """{"type":"Electric","level":63,"autonomy":290,"extension":{"electric":{"charging":{"plugged":true,"status":"InProgress"}}}}"""
        val fuel = """{"type":"Fuel","level":75,"autonomy":500}"""
        val bev = VehicleStatus.parse(JSONObject("""{"energies":[$electric]}"""), null)
        assertEquals("Electric", bev.energyType)
        assertEquals(63.0, bev.displayLevelPct)
        assertEquals(290.0, bev.displayRangeKm)
        assertTrue(bev.plugged)
        assertTrue(bev.isCharging)
        val petrol = VehicleStatus.parse(JSONObject("""{"energies":[$fuel]}"""), null)
        assertEquals("Fuel", petrol.energyType)
        assertEquals(75.0, petrol.displayLevelPct)
        assertEquals(500.0, petrol.displayRangeKm)
        assertFalse(Caps.tab(Tab.Charging, petrol))
        val phev = VehicleStatus.parse(JSONObject("""{"energies":[$fuel,$electric]}"""), null)
        assertEquals("Hybrid", phev.energyType)
        assertEquals(63.0, phev.displayLevelPct)
        assertEquals(500.0, phev.fuelRangeKm)
        assertTrue(phev.isCharging)
    }

    @Test fun onlyExplicitMildHybridMotorizationActivatesAutomaticMode() {
        val vehicle = Vehicle("vehicle", "synthetic", "Hybrid", "Peugeot", null, null)
        assertFalse(vehicle.reportsMildHybrid)
        assertFalse(vehicle.copy(motorization = "PHEV").reportsMildHybrid)
        assertTrue(vehicle.copy(motorization = "MHEV").reportsMildHybrid)
        assertTrue(vehicle.copy(motorization = "Mild hybrid").reportsMildHybrid)
    }
}
