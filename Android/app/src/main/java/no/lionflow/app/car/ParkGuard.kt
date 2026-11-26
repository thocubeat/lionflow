package no.lionflow.app.car

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Decides whether the car is parked. Fails closed: if neither the car nor the phone's GPS can
 * confirm standing still, the answer is "not parked".
 *  - Car speed (Android Auto) = 0 → parked.
 *  - No car speed: phone GPS speed under 1 m/s for at least 10 s with a fresh fix → parked.
 */
class ParkGuard(private val ctx: CarContext) {
    private val _parked = MutableStateFlow(false)
    val parked: StateFlow<Boolean> = _parked

    var reason: String = "Checking whether the car is parked…"
        private set

    /** How fast the car goes now, in m/s: from the car when it says, else from the phone's GPS. Null when unknown. */
    private val _speed = MutableStateFlow<Float?>(null)
    val speed: StateFlow<Float?> = _speed

    /** Where the phone (and so the car) is now, from its GPS; null without a fresh fix. */
    private val _fix = MutableStateFlow<Location?>(null)
    val fix: StateFlow<Location?> = _fix
    private var gpsSpeed: Float? = null

    private var carSpeed: Float? = null
    private var carSpeedAt = 0L
    private var gpsStillSince = 0L
    private var gpsAt = 0L
    private var started = false

    private val speedListener = OnCarDataAvailableListener<Speed> { s ->
        val v: CarValue<Float> = s.displaySpeedMetersPerSecond
        if (v.status == CarValue.STATUS_SUCCESS && v.value != null) {
            carSpeed = Math.abs(v.value!!)
            carSpeedAt = System.currentTimeMillis()
        }
        update()
    }

    private val gps = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val now = System.currentTimeMillis()
            gpsAt = now
            gpsSpeed = if (l.hasSpeed()) l.speed else null
            if (l.accuracy < 100f) _fix.value = l
            val still = l.hasSpeed() && l.speed < 1f && l.accuracy < 50f
            if (still) { if (gpsStillSince == 0L) gpsStillSince = now } else gpsStillSince = 0L
            update()
        }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    /** Battery and range straight from the car (much fresher than the brand's server). */
    private val energyListener = OnCarDataAvailableListener<androidx.car.app.hardware.info.EnergyLevel> { e ->
        val pct = e.batteryPercent.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value?.toDouble()?.takeIf { it in 0.0..100.0 }
        val range = e.rangeRemainingMeters.takeIf { it.status == CarValue.STATUS_SUCCESS }?.value?.toDouble()?.takeIf { it >= 0 }?.div(1000.0)
        if (pct != null || range != null) CarHub.live.value = CarHub.Live(pct, range)
    }

    /** The car's speed and energy need permissions the user gives once; asked for when missing. */
    fun askPermissions() {
        val want = listOf(PERM_SPEED, PERM_FUEL).filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        if (want.isEmpty() || asked) return
        asked = true
        try { ctx.requestPermissions(want) { granted, _ -> if (granted.isNotEmpty()) { stop(); start() } } } catch (_: Exception) { }
    }
    private var asked = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        val hw = try { ctx.getCarService(CarHardwareManager::class.java).carInfo } catch (_: Exception) { null }
        try { hw?.addSpeedListener(ContextCompat.getMainExecutor(ctx), speedListener) } catch (_: Exception) { }
        try { hw?.addEnergyLevelListener(ContextCompat.getMainExecutor(ctx), energyListener) } catch (_: Exception) { }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                ctx.getSystemService(LocationManager::class.java)
                    .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gps, Looper.getMainLooper())
            } catch (_: Exception) { }
        }
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        try { ctx.getCarService(CarHardwareManager::class.java).carInfo.removeSpeedListener(speedListener) } catch (_: Exception) { }
        try { ctx.getCarService(CarHardwareManager::class.java).carInfo.removeEnergyLevelListener(energyListener) } catch (_: Exception) { }
        CarHub.live.value = CarHub.Live(null, null)
        try { ctx.getSystemService(LocationManager::class.java).removeUpdates(gps) } catch (_: Exception) { }
    }

    /** Call regularly (e.g. every second) so stale readings expire. */
    fun update() {
        val now = System.currentTimeMillis()
        val car = carSpeed?.takeIf { now - carSpeedAt < 5_000 }
        val p: Boolean
        if (car != null) {
            p = car < 0.3f
            reason = if (p) "Parked" else "Video is only available while parked"
        } else if (now - gpsAt < 10_000 && gpsStillSince > 0 && now - gpsStillSince >= 10_000) {
            p = true
            reason = "Parked (GPS)"
        } else {
            p = false
            reason = if (now - gpsAt < 10_000) "Video is only available while parked" else "Can't confirm the car is parked (no speed from the car or GPS yet)"
        }
        _parked.value = p
        _speed.value = car ?: gpsSpeed?.takeIf { now - gpsAt < 5_000 }
        if (now - gpsAt > 30_000) _fix.value = null
    }
}

private const val PERM_SPEED = "com.google.android.gms.permission.CAR_SPEED"
private const val PERM_FUEL = "com.google.android.gms.permission.CAR_FUEL"
