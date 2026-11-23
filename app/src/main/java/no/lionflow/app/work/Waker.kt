package no.lionflow.app.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import no.lionflow.app.LionFlowApp
import no.lionflow.app.R
import no.lionflow.app.ui.l10n.tr
import java.util.concurrent.TimeUnit

/** Wakes the car from the background (never by default) and says so with a small heads-up. */
object Waker {
    private const val CHANNEL = "car_wake"

    /** Small, silent notice that disappears by itself. */
    fun headsUp(ctx: Context, text: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = NotificationManagerCompat.from(ctx)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, tr("Waking the car"), android.app.NotificationManager.IMPORTANCE_LOW))
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("LionFlow").setContentText(tr(text)).setTimeoutAfter(20_000).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        try { nm.notify(20, n) } catch (_: SecurityException) { }
    }

    /** Sends the wake-up and waits (briefly) until the car answers. False if remote control isn't set up. */
    suspend fun wake(app: LionFlowApp): Boolean {
        if (!app.remote.isSetUp) return false
        app.remote.warmUp()
        val awake = CompletableDeferred<Unit>()
        val sent = app.remote.preWake(0L) { awake.complete(Unit) }
        if (sent) withTimeoutOrNull(25_000) { awake.await() }
        return sent
    }

    /** Wake, read the status, update the widget. */
    suspend fun wakeAndRefresh(app: LionFlowApp) {
        val vehicle = app.api.cachedVehicle() ?: return
        wake(app)
        val st = no.lionflow.app.model.ChargePower.record(app.api.parseStatus(vehicle, app.api.status(vehicle)))
        no.lionflow.app.widget.WidgetData.save(app, st)
        app.remote.disconnect()
    }

    fun schedule(app: LionFlowApp) {
        val wm = androidx.work.WorkManager.getInstance(app)
        val h = app.settings.value.bgWakeHours
        if (h <= 0) wm.cancelUniqueWork("wake-bg") else wm.enqueueUniquePeriodicWork(
            "wake-bg", androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
            androidx.work.PeriodicWorkRequestBuilder<WakeWorker>(h.toLong(), TimeUnit.HOURS).setInputData(androidx.work.workDataOf("src" to "bg"))
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()).build())
        wm.cancelUniqueWork("wake-widget")
    }
}

class WakeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LionFlowApp
        val s = app.settings.value
        if (!app.api.isSignedIn || !app.remote.isSetUp) return Result.success()
        if (s.bgWakeHours <= 0) return Result.success()
        Waker.headsUp(app, "Waking your car to update LionFlow")
        return try { Waker.wakeAndRefresh(app); Result.success() } catch (e: Exception) { Result.success() }
    }
}
