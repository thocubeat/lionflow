package no.lionflow.app.model

import android.content.SharedPreferences
import no.lionflow.app.api.MildHybridDetection
import no.lionflow.app.api.Vehicle
import no.lionflow.app.data.SettingsStore
import no.lionflow.app.ui.Caps
import no.lionflow.app.ui.Tab
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class MildHybridStatusTest {
    private val fuel = JSONObject("""{"type":"Fuel","level":59,"autonomy":360}""")
    private val electric = JSONObject("""{
        "type":"Electric","level":63,"autonomy":240,
        "extension":{"electric":{"charging":{"status":"InProgress","plugged":true},
        "battery":{"load":{"residual":480,"capacity":36000}}}}
    }""")

    private fun status(vararg energies: JSONObject) =
        JSONObject().put("energies", JSONArray(energies.toList()))

    @Test fun mildHybridUsesFuelAndDiscardsChargingData() {
        val st = ChargePower.record(VehicleStatus.parse(status(fuel, electric), null, mildHybrid = true))
        assertEquals("Fuel", st.energyType)
        assertTrue(st.isMildHybrid)
        assertEquals(59.0, st.batteryPct)
        assertEquals(360.0, st.rangeKm)
        assertFalse(st.plugged)
        assertFalse(Caps.tab(Tab.Charging, st))
        assertNull(st.chargingStatus)
        assertNull(st.residualKwh)
        assertNull(st.capacityKwh)
        assertFalse(st.rangeEstimated)
    }

    @Test fun missingFuelRangeIsNotEstimatedFromTheBattery() {
        val partialFuel = JSONObject("""{"type":"Fuel","level":59}""")
        val st = ChargePower.record(VehicleStatus.parse(status(partialFuel, electric), null, mildHybrid = true))
        assertNull(st.rangeKm)
        assertFalse(st.rangeEstimated)
        assertEquals("Fuel", VehicleStatus.parse(status(electric), null, mildHybrid = true).energyType)
    }

    @Test fun existingBatteryAndTankInterpretationsStillApply() {
        val bev = VehicleStatus.parse(status(electric), null)
        assertEquals("Electric", bev.energyType)
        assertEquals(240.0, bev.rangeKm)
        assertTrue(bev.isCharging)
        val phev = VehicleStatus.parse(status(fuel, electric), null)
        assertEquals("Hybrid", phev.energyType)
        assertEquals(63.0, phev.batteryPct)
        assertEquals(360.0, phev.fuelRangeKm)
        assertTrue(Caps.tab(Tab.Charging, phev))
        val missingElectric = JSONObject("""{"type":"Electric"}""")
        assertEquals("Hybrid", VehicleStatus.parse(status(fuel, missingElectric), null).energyType)
        val petrol = VehicleStatus.parse(status(fuel), null)
        assertEquals("Fuel", petrol.energyType)
        assertFalse(petrol.isMildHybrid)
        assertEquals(59.0, petrol.batteryPct)
    }

    private fun apiVehicle(motorization: String? = "Hybrid", label: String? = null): JSONObject =
        JSONObject().put("id", "vehicle").put("vin", "synthetic").put("brand", "Peugeot")
            .put("motorization", motorization).put("label", label)

    @Test fun explicitMetadataTakesPrecedenceOverModelName() {
        for (motorization in listOf("MHEV", "48 V Mild hybrid", "Hybrid 48V", "MHEV (48 V)")) {
            val vehicle = Vehicle.fromApi(apiVehicle(motorization, "208 Hybrid 100 e-DCS6"), "Other")
            assertEquals(motorization, MildHybridDetection.VehicleMetadata, vehicle.mildHybridDetection)
            val withoutLabel = vehicle.copy(modelLabel = null, brand = "Other")
            assertTrue(withoutLabel.reportsMildHybrid)
            assertFalse(withoutLabel.needsBranding)
        }
    }

    @Test fun recognizesThePeugeotModelAcrossTrimsAndFormats() {
        val labels = listOf(
            "208 Hybrid 100 e-DCS6",
            "208 5P - STYLE HYBRID 100 E-DCS6",
            "208 Hybrid 100 eDCS6",
            "208 Hybrid 100 e-DCS6 Allure",
            "PEUGEOT 208\u00a05P / ALLURE — HYBRID 100 (e‑DCS 6)",
        )
        for (label in labels) {
            val vehicle = Vehicle.fromApi(apiVehicle(label = label), "Other")
            val cached = Vehicle.fromJson(vehicle.toJson())
            assertEquals(label, MildHybridDetection.ModelName, cached.mildHybridDetection)
            assertFalse(label, cached.needsBranding)
        }
        assertTrue(Vehicle.fromApi(apiVehicle(motorization = null, label = labels.first()), "Other").reportsMildHybrid)
    }

    @Test fun rejectsGenericHybridAndOtherModels() {
        val labels = listOf(
            "208", "208 Hybrid", "208 Hybrid 136 e-DCS6", "2008 Hybrid 100 e-DCS6",
            "308 Hybrid 100 e-DCS6", "208 308 Hybrid 100 e-DCS6", "208 Hybrid 100 e-DCS7",
        )
        for (label in labels) {
            assertFalse(label, Vehicle.fromApi(apiVehicle(label = label), "Other").reportsMildHybrid)
        }
        val otherBrand = apiVehicle(label = "208 Hybrid 100 e-DCS6").put("brand", "Other")
        assertFalse(Vehicle.fromApi(otherBrand, "Peugeot").reportsMildHybrid)
    }

    @Test fun rejectsPlugInElectricAndCombustionPowertrainsEvenWithMatchingModel() {
        for (powertrain in listOf("PHEV", "Plug-in Hybrid", "BEV", "Electric", "Thermic", "Thermal")) {
            val vehicle = Vehicle.fromApi(apiVehicle(powertrain, "208 Hybrid 100 e-DCS6"), "Other")
            assertEquals(powertrain, MildHybridDetection.OtherPowertrain, vehicle.mildHybridDetection)
            assertFalse(powertrain, vehicle.needsBranding)
        }
        for (label in listOf("208 Plug-In Hybrid 100 e-DCS6", "e-208 Hybrid 100 e-DCS6")) {
            assertFalse(label, Vehicle.fromApi(apiVehicle(label = label), "Other").reportsMildHybrid)
        }
    }

    @Test fun usesBothBrandingAndTopLevelLabels() {
        val payload = apiVehicle(label = "208 5P - STYLE HYBRID 100 E-DCS6").put("_embedded", JSONObject("""{
            "extension":{"branding":{"brand":"Peugeot","label":"208"}}
        }"""))
        assertTrue(Vehicle.fromJson(Vehicle.fromApi(payload, "Other").toJson()).reportsMildHybrid)
        payload.put("brand", "").remove("label")
        payload.getJSONObject("_embedded").getJSONObject("extension").getJSONObject("branding")
            .put("label", "208 Hybrid 100 e-DCS6 Allure")
        assertEquals(MildHybridDetection.ModelName, Vehicle.fromApi(payload, "Other").mildHybridDetection)
    }

    @Test fun incompleteLegacyAndRefreshedCachesStayEligibleForBranding() {
        val cached = Vehicle.fromJson(apiVehicle().put("brandingLoaded", true).put("modelLabel", "208"))
        assertFalse(cached.reportsMildHybrid)
        assertTrue(cached.needsBranding)
        assertTrue(cached.copy(brand = "").needsBranding)

        val partial = Vehicle.fromApi(apiVehicle(motorization = null, label = "208"), "Other")
        assertTrue(Vehicle.fromJson(partial.toJson()).needsBranding)

        val refreshed = Vehicle.fromApi(apiVehicle(label = "208 5P - STYLE HYBRID 100 E-DCS6"), "Other")
        val updatedCache = Vehicle.fromJson(refreshed.toJson())
        assertEquals(cached.vin, updatedCache.vin)
        assertEquals(MildHybridDetection.ModelName, updatedCache.mildHybridDetection)
        assertFalse(updatedCache.needsBranding)

        val st = VehicleStatus.parse(status(fuel, electric), null, mildHybrid = updatedCache.reportsMildHybrid)
        assertEquals(59.0, st.batteryPct)
        assertEquals(360.0, st.rangeKm)
        assertFalse(Caps.tab(Tab.Charging, st))
    }

    @Test fun undocumentedVehicleTypeDoesNotClassifyTheCar() {
        val vehicle = Vehicle.fromApi(apiVehicle().put("typeVehicle", 5), "Other")
        val cached = Vehicle.fromJson(vehicle.toJson())
        assertFalse(cached.reportsMildHybrid)
        assertFalse(vehicle.toJson().has("typeVehicle"))
        assertTrue(cached.needsBranding)
    }

    @Test fun detailCanRecognizeAStyleMissingFromTheVehicleList() {
        val listed = Vehicle.fromApi(apiVehicle(), "Peugeot")
        assertTrue(listed.needsBranding)

        val detail = JSONObject("""{
            "_embedded":{"extension":{"branding":{"label":"208 5P - STYLE HYBRID 100 E-DCS6"}}}
        }""")
        val detailed = Vehicle.fromJson(listed.withBrandingDetail(detail).toJson())
        assertEquals(MildHybridDetection.ModelName, detailed.mildHybridDetection)
        assertFalse(detailed.needsBranding)
        assertEquals(listed.motorization, detailed.motorization)
        assertEquals(listed.id, detailed.id)
    }

    @Test fun unavailableOrInconclusiveDetailsLeaveAutomaticUnclassified() {
        val listed = Vehicle.fromApi(apiVehicle(), "Peugeot")
        val inconclusive = listed.withBrandingDetail(JSONObject().put("motorization", "Hybrid"))
        assertFalse(inconclusive.reportsMildHybrid)
        assertTrue(inconclusive.needsBranding)
        assertEquals("Hybrid (type unknown)", inconclusive.kind(null))

        val label = "208 5P - STYLE HYBRID 100 E-DCS6"
        val unavailable = listOf(
            null,
            apiVehicle(label = label).put("id", "different-vehicle"),
            apiVehicle(label = label).put("vin", "different-vin"),
        )
        for (detail in unavailable) {
            val checked = Vehicle.fromJson(listed.withBrandingDetail(detail).toJson())
            assertFalse(checked.reportsMildHybrid)
            assertTrue(checked.needsBranding)
        }
    }

    @Test fun genericHybridKeepsFuelAndHidesChargingWithoutExternalChargingEvidence() {
        val vehicle = Vehicle.fromApi(apiVehicle(), "Peugeot")
        val electricOnlyTelemetry = JSONObject("""{"type":"Electric","level":63,"autonomy":240,
            "extension":{"electric":{"charging":{"status":"Disconnected","plugged":false}}}}""")
        for (override in listOf(null, false)) {
            val st = vehicle.parseStatus(status(fuel, electricOnlyTelemetry), null, override)
            assertEquals("Hybrid (type unknown)", vehicle.kind(override))
            assertFalse(st.isMildHybrid)
            assertEquals(59.0, st.batteryPct)
            assertEquals(360.0, st.rangeKm)
            assertFalse(Caps.tab(Tab.Charging, st))
            assertNull(st.chargingStatus)
        }
        val connected = vehicle.parseStatus(status(fuel, electric), null, null)
        assertTrue(Caps.tab(Tab.Charging, connected))
        assertEquals("Hybrid (type unknown)", vehicle.kind(null))
        val forced = vehicle.parseStatus(status(fuel, electric), null, true)
        assertEquals("48 V mild hybrid", vehicle.kind(true))
        assertFalse(Caps.tab(Tab.Charging, forced))
    }

    @Test fun missingVehicleMetadataDoesNotInferPlugInFromFuelAndElectricReadings() {
        val car = Vehicle.fromApi(apiVehicle(motorization = null), "Peugeot")
        val battery = JSONObject("""{"type":"Electric","level":63}""")
        assertFalse(car.reportsMildHybrid)
        assertFalse(Caps.tab(Tab.Charging, car.parseStatus(status(fuel, battery), null, null)))
        assertTrue(Caps.tab(Tab.Charging, car.parseStatus(status(battery), null, null)))
    }

    @Test fun mildHybridTripKeepsTheExistingFuelAndDrivingStatistics() {
        val raw = JSONObject("""{
            "id":"test","distance":20,"duration":1200,"energyConsumptions":[
                {"type":"Fuel","consumption":1.2,"consumptionUnit":"l","avgConsumption":6,"avgConsumptionUnit":"l/100km"},
                {"type":"Electric","consumption":2100,"avgConsumption":10500}
            ]
        }""")
        val standard = Trip.parse(raw)
        val mildHybrid = Trip.parse(raw, mildHybrid = true)
        assertEquals(standard.copy(kwh = null, avgKwhPer100 = null), mildHybrid)
        assertEquals(20.0, mildHybrid.distanceKm, 0.0)
        assertEquals(1.2, mildHybrid.liters!!, 0.0)
        assertEquals(60.0, mildHybrid.avgSpeedKmh!!, 0.0)
    }

    @Test fun explicitPlugInAndElectricVehiclesRetainChargingCapabilities() {
        val electricWithoutChargeState = JSONObject("""{"type":"Electric","level":63,"autonomy":240}""")
        val phev = Vehicle.fromApi(apiVehicle("PHEV"), "Peugeot")
        val bev = Vehicle.fromApi(apiVehicle("BEV"), "Peugeot")
        val thermic = Vehicle.fromApi(apiVehicle("Thermic"), "Peugeot")
        assertEquals("Plug-in hybrid", phev.kind(null))
        assertTrue(Caps.tab(Tab.Charging, phev.parseStatus(status(fuel, electricWithoutChargeState), null, null)))
        assertEquals("Electric", bev.kind(null))
        assertTrue(Caps.tab(Tab.Charging, bev.parseStatus(status(electricWithoutChargeState), null, null)))
        assertEquals("Petrol / diesel", thermic.kind(null))
        assertFalse(Caps.tab(Tab.Charging, thermic.parseStatus(status(fuel), null, null)))
    }

    @Test fun automaticOnAndOffPersistIndependentlyForEachVehicle() {
        val recognized = Vehicle.fromApi(apiVehicle("MHEV"), "Other")
        val ambiguous = Vehicle.fromApi(apiVehicle(), "Other").copy(vin = "synthetic-other")
        val preferences = inMemoryPreferences()
        val settings = SettingsStore(preferences)
        assertNull(settings.mildHybridOverride(recognized.vin))
        settings.setMildHybridOverride(recognized.vin, false)
        settings.setMildHybridOverride(ambiguous.vin, true)

        val reopened = SettingsStore(preferences)
        assertEquals(false, reopened.mildHybridOverride(recognized.vin))
        assertEquals(true, reopened.mildHybridOverride(ambiguous.vin))
        assertFalse(recognized.isMildHybrid(reopened.mildHybridOverride(recognized.vin)))
        assertTrue(ambiguous.isMildHybrid(reopened.mildHybridOverride(ambiguous.vin)))
        reopened.setMildHybridOverride(recognized.vin, null)
        reopened.setMildHybridOverride(ambiguous.vin, null)

        val automatic = SettingsStore(preferences)
        assertNull(automatic.mildHybridOverride(recognized.vin))
        assertNull(automatic.mildHybridOverride(ambiguous.vin))
        assertTrue(recognized.isMildHybrid(automatic.mildHybridOverride(recognized.vin)))
        assertFalse(ambiguous.isMildHybrid(automatic.mildHybridOverride(ambiguous.vin)))
    }

    private fun inMemoryPreferences(): SharedPreferences {
        val values = mutableMapOf<String, String>()
        val editorType = SharedPreferences.Editor::class.java
        val editor = Proxy.newProxyInstance(editorType.classLoader, arrayOf(editorType)) { self, method, args ->
            when (method.name) {
                "putString" -> {
                    val key = args[0] as String
                    val value = args[1] as String?
                    if (value == null) values.remove(key) else values[key] = value
                    self
                }
                "apply" -> null
                else -> error("Unexpected preference write: ${method.name}")
            }
        }
        val prefsType = SharedPreferences::class.java
        return Proxy.newProxyInstance(prefsType.classLoader, arrayOf(prefsType)) { _, method, args ->
            when (method.name) {
                "getString" -> values[args[0]] ?: args[1]
                "getBoolean", "getFloat", "getInt", "getLong" -> args[1]
                "edit" -> editor
                else -> error("Unexpected preference read: ${method.name}")
            }
        } as SharedPreferences
    }
}
