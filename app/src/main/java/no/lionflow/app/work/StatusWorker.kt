package no.lionflow.app.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import no.lionflow.app.LionFlowApp
import no.lionflow.app.MainActivity
import no.lionflow.app.R
import no.lionflow.app.model.VehicleStatus
import org.json.JSONObject

/**
 * Checks the car every ~30 minutes in the background (Android decides the exact timing)
 * and posts the notifications the user switched on in Settings.
 */
class StatusWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as LionFlowApp
        val s = app.settings.value
        val anyOn = s.notifyChargeDone || s.notifyChargeStopped || s.notifyPreheat || s.notifyUnlocked || s.notifyLowBattery || s.notifyService
        if (!app.api.isSignedIn) return Result.success()
        val vehicle = app.api.cachedVehicles().firstOrNull() ?: return Result.success()
        return try {
            val st = VehicleStatus.parse(app.api.status(vehicle), null)
            no.lionflow.app.widget.WidgetData.save(applicationContext, st)
            if (!anyOn) return Result.success()
            val prev = JSONObject(app.settings.notifySnapshot)
            val now = System.currentTimeMillis()

            val prevCharging = prev.optString("charging")
            if (s.notifyChargeDone && prevCharging == "InProgress" && st.chargingStatus == "Finished")
                notify(1, "Charging complete", "Battery at ${st.batteryPct?.toInt() ?: "?"} %.")
            if (s.notifyChargeStopped && prevCharging == "InProgress" && (st.chargingStatus == "Stopped" || st.chargingStatus == "Failure"))
                notify(2, "Charging stopped", "Charging was interrupted at ${st.batteryPct?.toInt() ?: "?"} %.")
            if (s.notifyPreheat && prev.optBoolean("precond") && !st.preconditioningOn)
                notify(3, "Pre-heating finished", "Your car should be warm and ready.")

            val unlockedSince = if (st.locked == false && st.ignition == "Stop") {
                if (prev.optLong("unlockedSince", 0L) > 0) prev.optLong("unlockedSince") else now
            } else 0L
            if (s.notifyUnlocked && unlockedSince > 0 && now - unlockedSince > 10 * 60_000 && !prev.optBoolean("unlockedNotified"))
                notify(4, "Car left unlocked", "Your car has been parked unlocked for a while.")

            val lowNow = (st.batteryPct ?: 100.0) < 20.0
            if (s.notifyLowBattery && lowNow && !prev.optBoolean("low"))
                notify(5, "Low battery", "Battery is at ${st.batteryPct?.toInt()} %.")

            val serviceSoon = (st.kmToService ?: Double.MAX_VALUE) < 1000 || (st.daysToService ?: Double.MAX_VALUE) < 30
            if (s.notifyService && serviceSoon && !prev.optBoolean("service"))
                notify(6, "Service coming up", "Your next service is due soon.")

            app.settings.notifySnapshot = JSONObject()
                .put("charging", st.chargingStatus ?: "")
                .put("precond", st.preconditioningOn)
                .put("unlockedSince", unlockedSince)
                .put("unlockedNotified", unlockedSince > 0 && (prev.optBoolean("unlockedNotified") || now - unlockedSince > 10 * 60_000))
                .put("low", lowNow)
                .put("service", serviceSoon)
                .toString()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun notify(id: Int, title: String, text: String) {
        val ctx = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, LionFlowApp.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}
