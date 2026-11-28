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

open class LionFlowApp : Application() {
    lateinit var settings: SettingsStore
    lateinit var secure: SecureStore
    lateinit var api: StellantisApi
    lateinit var remote: RemoteControl

    override fun onCreate() {
        super.onCreate()
        // Android 9 and older have no TLS 1.3, and the route servers (OSRM, Valhalla) accept only TLS 1.3.
        // Bundled Conscrypt as the first provider gives OkHttp TLS 1.3 there; Android 10+ has it built in.
        if (android.os.Build.VERSION.SDK_INT < 29) {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        }
        settings = SettingsStore(this)
        no.lionflow.app.ui.l10n.L10n.init(this, settings.value.language)
        no.lionflow.app.ui.l10n.TimeFmt.setPreference(this, settings.value.timeFormat)
        secure = SecureStore(this)
        secure.profile = settings.activeAccount
        api = StellantisApi(this, secure, settings)
        remote = RemoteControl(api, secure)
        no.lionflow.app.model.ChargePower.init(this, settings, api.cachedVehicle()?.vin)
        no.lionflow.app.model.ChargeLog.init(this, api.cachedVehicle()?.vin)
        no.lionflow.app.door.GarageDoor.init(this) { remote.addLog(it) }

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
        no.lionflow.app.work.Waker.schedule(this)
        no.lionflow.app.work.SmartSchedule.schedule(this)
    }

    fun scheduleBackgroundChecks() {
        val req = PeriodicWorkRequestBuilder<StatusWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("status-check", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    companion object {
        const val CHANNEL = "car_updates"
    }
}
