package no.lionflow.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import no.lionflow.app.api.RemoteControl
import no.lionflow.app.api.StellantisApi
import no.lionflow.app.data.SecureStore
import no.lionflow.app.data.SettingsStore
import no.lionflow.app.work.StatusWorker
import java.util.concurrent.TimeUnit

class LionFlowApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var secure: SecureStore
    lateinit var api: StellantisApi
    lateinit var remote: RemoteControl

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        secure = SecureStore(this)
        api = StellantisApi(this, secure, settings)
        remote = RemoteControl(api, secure)

        // "Remember me" off: start every cold launch signed out.
        if (!settings.value.rememberMe && api.isSignedIn) {
            remote.forget()
            api.signOut()
        }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Car updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Charging, pre-heating, doors, battery and service"
        })
        scheduleBackgroundChecks()
    }

    fun scheduleBackgroundChecks() {
        val req = PeriodicWorkRequestBuilder<StatusWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("status-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    companion object {
        const val CHANNEL = "car_updates"
    }
}
