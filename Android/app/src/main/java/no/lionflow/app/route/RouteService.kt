package no.lionflow.app.route

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import no.lionflow.app.LionFlowApp
import no.lionflow.app.MainActivity
import no.lionflow.app.R
import no.lionflow.app.ui.l10n.tr
import java.io.File

/** Starts and stops route recording when the phone connects to / disconnects from the car's Bluetooth. */
class CarBluetoothReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as? LionFlowApp ?: return
        val s = app.settings.value
        if (!s.routeTracking || s.routeBtAddr.isEmpty()) return
        @Suppress("DEPRECATION")
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        if (dev?.address != s.routeBtAddr) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> RouteService.start(ctx)
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> RouteService.stop(ctx)
        }
    }
}

/** Records the phone's GPS position to a file while running. */
class RouteService : Service(), LocationListener {
    private var file: File? = null
    private var startMillis = 0L
    private var last: Location? = null
    private var points = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { finish(); stopSelf(); return START_NOT_STICKY }
        if (file != null) return START_STICKY
        val n = notification(tr("Recording route"), tr("LionFlow is saving your route while connected to the car"))
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, n)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!hasLocation(this)) { stopSelf(); return START_NOT_STICKY }
        startMillis = System.currentTimeMillis()
        file = File(Routes.dir(this), "rec-$startMillis.part").also { it.writeText("") }
        listen()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        val lm = getSystemService(LocationManager::class.java)
        try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3_000L, 10f, this, Looper.getMainLooper()) } catch (_: Exception) {}
    }

    override fun onLocationChanged(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 40f) return
        val prev = last
        if (prev != null && prev.distanceTo(loc) < 8f) return
        last = loc
        points++
        file?.appendText("${loc.latitude},${loc.longitude},${loc.time}\n")
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** Keeps the route if it's a real drive, under a name that holds start, end and distance. */
    private fun finish() {
        try { getSystemService(LocationManager::class.java).removeUpdates(this) } catch (_: Exception) {}
        val f = file ?: return
        file = null
        val pts = Routes.points(f)
        val km = Routes.distanceKm(pts)
        if (pts.size >= 3 && km >= 0.3) {
            f.renameTo(File(Routes.dir(this), "${pts.first().third}-${pts.last().third}-${String.format(java.util.Locale.US, "%.2f", km)}.csv"))
        } else f.delete()
    }

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }

    private fun notification(title: String, text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Route recording"), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setContentIntent(open)
            .build()
    }

    companion object {
        const val CHANNEL = "route_recording"
        const val ID = 41
        const val ACTION_STOP = "no.lionflow.app.ROUTE_STOP"

        fun hasLocation(ctx: Context) =
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundLocation(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBluetooth(ctx: Context) = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            if (!hasLocation(ctx)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RouteService::class.java))
            } catch (e: Exception) {
                // Android may refuse to start from the background; let the user start it with one tap.
                askToStart(ctx)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.startService(Intent(ctx, RouteService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {
                ctx.stopService(Intent(ctx, RouteService::class.java))
            }
        }

        private fun askToStart(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Route recording"), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 8, Intent(ctx, MainActivity::class.java).setAction(ACTION_START_FROM_TAP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID + 1, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(tr("Connected to your car"))
                .setContentText(tr("Tap to record this drive's route"))
                .setAutoCancel(true).setContentIntent(open).build())
        }

        const val ACTION_START_FROM_TAP = "no.lionflow.app.ROUTE_START_TAP"
    }
}
