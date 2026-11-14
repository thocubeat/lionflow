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
            val still = l.hasSpeed() && l.speed < 1f && l.accuracy < 50f
            if (still) { if (gpsStillSince == 0L) gpsStillSince = now } else gpsStillSince = 0L
            update()
        }
        @Deprecated("old API") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        try {
            ctx.getCarService(CarHardwareManager::class.java).carInfo.addSpeedListener(ContextCompat.getMainExecutor(ctx), speedListener)
        } catch (_: Exception) { }
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
    }
}
