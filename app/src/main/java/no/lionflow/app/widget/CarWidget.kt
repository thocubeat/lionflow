package no.lionflow.app.widget

import no.lionflow.app.ui.l10n.tr

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.LionFlowApp
import no.lionflow.app.MainActivity
import no.lionflow.app.R
import no.lionflow.app.api.AuthExpiredException
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.minutesAgo
import org.json.JSONObject
import java.io.IOException

/** Last known values and refresh state shown by the home-screen widget (no secrets). */
object WidgetData {
    private const val PREFS = "lf_widget"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, st: VehicleStatus) {
        val now = System.currentTimeMillis()
        val j = JSONObject()
            .put("battery", st.batteryPct ?: -1.0)
            .put("range", st.rangeKm ?: -1.0)
            .put("charging", st.isCharging)
            .put("plugged", st.plugged)
            .put("remaining", no.lionflow.app.model.ChargePower.timeLeft(st)?.first ?: -1)
            .put("remainingTo", no.lionflow.app.model.ChargePower.timeLeft(st)?.second ?: -1)
            .put("fuel", st.energyType == "Fuel")
            .put("kw", no.lionflow.app.model.ChargePower.label(st) ?: "")
            .put("outside", st.outsideTempC ?: -999.0)
            .put("precond", st.preconditioningOn)
            .put("carTime", st.updatedAtMillis ?: now)
            .put("fetched", now)
        // Some cars don't report a position; JSON can't hold NaN, so only add real numbers.
        st.lat?.takeIf { it.isFinite() }?.let { j.put("lat", it) }
        st.lon?.takeIf { it.isFinite() }?.let { j.put("lon", it) }
        prefs(ctx).edit().putString("snap", j.toString()).putBoolean("refreshing", false).remove("status").apply()
        CarWidget.updateAll(ctx)
        val lat = st.lat; val lon = st.lon
        if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) resolvePlace(ctx, lat, lon)
    }

    /** "Home", a saved charger's name, or a street address (looked up once per spot). */
    private fun resolvePlace(ctx: Context, lat: Double, lon: Double) {
        val cl = no.lionflow.app.model.ChargeLog
        val cfg = cl.places.value
        cfg.home?.let { h -> if (h.lat != null && h.lon != null && cl.distanceM(lat, lon, h.lat, h.lon) <= 50) { setPlace(ctx, "Home"); return } }
        cfg.known.forEach { k -> if (k.lat != null && k.lon != null && cl.distanceM(lat, lon, k.lat, k.lon) <= 75) { setPlace(ctx, k.name.ifBlank { "Charger" }); return } }
        val key = String.format(java.util.Locale.US, "%.4f,%.4f", lat, lon)
        val p = prefs(ctx)
        if (p.getString("placeKey", null) == key && p.getString("place", null) != null) return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val a = try { cl.reverseGeocode(lat, lon) } catch (_: Exception) { null }
            if (a != null) { p.edit().putString("placeKey", key).apply(); setPlace(ctx, a, translate = false) }
        }
    }

    private fun setPlace(ctx: Context, place: String, translate: Boolean = true) {
        prefs(ctx).edit().putString("place", place).putBoolean("placeTr", translate).apply()
        CarWidget.updateAll(ctx)
    }

    fun place(ctx: Context): String? = prefs(ctx).getString("place", null)?.let { if (prefs(ctx).getBoolean("placeTr", false)) tr(it) else it }

    fun load(ctx: Context): JSONObject? = prefs(ctx).getString("snap", null)?.let { JSONObject(it) }

    /** refreshing = spinner on; status = short line under the panel (null = the normal "Updated …"). */
    fun setRefresh(ctx: Context, refreshing: Boolean, status: String?) {
        prefs(ctx).edit().putBoolean("refreshing", refreshing).putString("status", status).apply()
        CarWidget.updateAll(ctx)
    }

    fun isRefreshing(ctx: Context) = prefs(ctx).getBoolean("refreshing", false)
    fun status(ctx: Context): String? = prefs(ctx).getString("status", null)

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        CarWidget.updateAll(ctx)
    }
}

class CarWidget : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        no.lionflow.app.work.Waker.schedule(context.applicationContext as LionFlowApp)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        render(context, appWidgetManager, appWidgetIds)
        if (WidgetData.load(context) == null) refresh(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: android.os.Bundle) {
        render(context, appWidgetManager, intArrayOf(appWidgetId))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refresh(context, wake = true)
        if (intent.action == ACTION_NEXT_CAR) {
            val app = context.applicationContext as LionFlowApp
            no.lionflow.app.model.Garage.next(app)?.let { no.lionflow.app.model.Garage.activate(app, it); refresh(context) }
        }
    }

    companion object {
        const val ACTION_REFRESH = "no.lionflow.app.WIDGET_REFRESH"
        const val ACTION_NEXT_CAR = "no.lionflow.app.WIDGET_NEXT_CAR"

        /** Shows the spinner immediately, then fetches in the background. */
        fun refresh(ctx: Context, wake: Boolean = false) {
            val canWake = wake && (ctx.applicationContext as LionFlowApp).remote.isSetUp
            WidgetData.setRefresh(ctx, refreshing = true, status = if (canWake) "Waking the car…" else "Refreshing…")
            if (canWake) android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, tr("Waking your car — this can take a few seconds"), android.widget.Toast.LENGTH_SHORT).show()
            }
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "widget-refresh", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<WidgetRefreshWorker>().setInputData(androidx.work.workDataOf("wake" to canWake)).build()
            )
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CarWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, mgr, ids)
        }

        fun render(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            for (id in ids) {
                // Short widgets move the "Updated" line up under the car name so nothing gets cut off.
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                mgr.updateAppWidget(id, build(ctx, compact = h in 1..299))
            }
        }

        private fun build(ctx: Context, compact: Boolean): RemoteViews {
            val app = ctx.applicationContext as LionFlowApp
            val s = app.settings.value
            val snap = WidgetData.load(ctx)
            val refreshing = WidgetData.isRefreshing(ctx)
            val v = RemoteViews(ctx.packageName, R.layout.widget_car)

            val several = no.lionflow.app.model.Garage.hasSeveral(app)
            v.setTextViewText(R.id.w_name, tr(s.carName) + if (several) "  ⇄" else "")
            // Tap the name to switch car, only when more than one car is registered.
            if (several) v.setOnClickPendingIntent(R.id.w_name, PendingIntent.getBroadcast(
                ctx, 3, Intent(ctx, CarWidget::class.java).setAction(ACTION_NEXT_CAR), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            v.setViewVisibility(R.id.w_refresh_icon, if (refreshing) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_spinner, if (refreshing) View.VISIBLE else View.GONE)
            v.setTextViewText(R.id.w_range_unit, tr(if (s.distanceUnit == "mi") "mi" else "km"))

            if (snap == null || !app.api.isSignedIn) {
                v.setTextViewText(R.id.w_battery, tr("—"))
                v.setTextViewText(R.id.w_range, tr("—"))
                v.setProgressBar(R.id.w_bar, 100, 0, false)
                v.setViewVisibility(R.id.w_charging, View.VISIBLE)
                v.setTextViewText(R.id.w_charging, tr("No data yet"))
                v.setViewVisibility(R.id.w_bolt, View.GONE)
                v.setViewVisibility(R.id.w_plug, View.VISIBLE)
                v.setTextViewText(R.id.w_cabin, tr("—"))
                v.setTextViewText(R.id.w_outside, tr("—"))
            } else {
                val battery = snap.optDouble("battery", -1.0)
                val range = snap.optDouble("range", -1.0)
                val fuelOnly = snap.optBoolean("fuel")
                val charging = snap.optBoolean("charging")
                val plugged = snap.optBoolean("plugged")
                val remaining = snap.optInt("remaining", -1)
                val outside = snap.optDouble("outside", -999.0)
                val precond = snap.optBoolean("precond")
                val rangeShown = if (s.distanceUnit == "mi") range * 0.621371 else range

                v.setTextViewText(R.id.w_battery_label, tr(if (fuelOnly) "Fuel" else "Battery"))
                v.setTextViewText(R.id.w_battery, tr(if (battery >= 0) "${battery.toInt()}" else "—"))
                v.setTextViewText(R.id.w_range, tr(if (range >= 0) "≈${Math.round(rangeShown)}" else "—"))
                v.setProgressBar(R.id.w_bar, 100, battery.coerceIn(0.0, 100.0).toInt(), false)
                v.setViewVisibility(R.id.w_bolt, if (charging) View.VISIBLE else View.GONE)
                v.setViewVisibility(R.id.w_plug, if (charging || fuelOnly) View.GONE else View.VISIBLE)
                v.setViewVisibility(R.id.w_charging, if (fuelOnly) View.GONE else View.VISIBLE)
                v.setTextViewText(R.id.w_charging, tr(when {
                    charging && remaining > 0 -> "Charging${snap.optString("kw").takeIf { it.isNotBlank() }?.let { " $it" } ?: ""} · ${remaining / 60} h ${remaining % 60} min left${snap.optInt("remainingTo", -1).takeIf { it > 0 }?.let { " to $it %" } ?: ""}"
                    charging -> "Charging${snap.optString("kw").takeIf { it.isNotBlank() }?.let { " $it" } ?: ""}"
                    plugged -> "Plugged in · not charging"
                    else -> "Not charging"
                }))
                v.setTextColor(R.id.w_charging, ctx.getColor(if (charging) R.color.widget_text else R.color.widget_muted))
                // The car doesn't report cabin temperature, so "Car" shows the pre-heating state.
                v.setTextViewText(R.id.w_cabin, tr(if (precond) "Pre-heating" else "Not heating"))
                v.setImageViewResource(R.id.w_fan, if (precond) R.drawable.ic_w_fan_on else R.drawable.ic_w_fan)
                v.setTextViewText(R.id.w_outside, tr(if (outside > -100) fmtTemp(outside, s) else "—"))
            }

            val line = tr(WidgetData.status(ctx) ?: updatedLine(snap, app.api.isSignedIn))
            val place = if (snap != null && app.api.isSignedIn) WidgetData.place(ctx) else null
            // Under the name: where the car is. Short widgets also put the "Updated" line there.
            val top = listOfNotNull(place?.let { "📍 $it" }, line.takeIf { compact }).joinToString(" · ")
            v.setTextViewText(R.id.w_updated, line)
            v.setTextViewText(R.id.w_updated_top, top)
            v.setViewVisibility(R.id.w_updated, if (compact) View.GONE else View.VISIBLE)
            v.setViewVisibility(R.id.w_updated_top, if (top.isNotEmpty()) View.VISIBLE else View.GONE)

            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            val refresh = PendingIntent.getBroadcast(
                ctx, 2, Intent(ctx, CarWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_refresh, refresh)
            return v
        }

        private fun updatedLine(snap: JSONObject?, signedIn: Boolean): String {
            if (!signedIn) return "Open LionFlow to sign in"
            if (snap == null) return "Tap ↻ to load your car"
            val now = System.currentTimeMillis()
            val fetched = snap.optLong("fetched", snap.optLong("updated", 0L))
            val carTime = snap.optLong("carTime", fetched)
            // If the car itself hasn't reported for a while, say so — otherwise "Updated" would be misleading.
            return if (now - carTime > 30 * 60_000 && fetched - carTime > 30 * 60_000) {
                "Car asleep · last report ${minutesAgo(carTime, now)}"
            } else {
                "Updated ${minutesAgo(carTime, now)}"
            }
        }
    }
}

/** Fetches fresh status for the widget and explains slow refreshes as they happen. */
class WidgetRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val app = ctx as LionFlowApp
        if (!app.api.isSignedIn) {
            WidgetData.setRefresh(ctx, false, null)
            return Result.success()
        }
        val vehicle = app.api.cachedVehicle()
        if (vehicle == null) {
            WidgetData.setRefresh(ctx, false, "Open LionFlow to finish setup")
            return Result.success()
        }
        return coroutineScope {
            val explainer = launch {
                delay(2_000)
                WidgetData.setRefresh(ctx, true, "Still loading — Peugeot's servers are answering slowly")
                delay(6_000)
                WidgetData.setRefresh(ctx, true, "Taking longer than usual — weak signal or Peugeot is busy")
                delay(12_000)
                WidgetData.setRefresh(ctx, true, "Almost there — Peugeot sometimes needs up to 30 seconds")
            }
            try {
                // Show what the servers already know straight away; only wake the car if that data is old.
                var st = no.lionflow.app.model.ChargePower.record(app.api.readStatus(vehicle))
                explainer.cancel()
                WidgetData.save(ctx, st)
                val old = st.updatedAtMillis?.let { System.currentTimeMillis() - it > 5 * 60_000 } ?: true
                if (inputData.getBoolean("wake", false) && old && st.moving != true) {
                    WidgetData.setRefresh(ctx, true, "Waking the car…")
                    try { no.lionflow.app.work.Waker.wake(app) } catch (_: Exception) { }
                    try {
                        st = no.lionflow.app.model.ChargePower.record(app.api.readStatus(vehicle))
                    } catch (_: Exception) { }
                    WidgetData.save(ctx, st)
                }
            } catch (e: AuthExpiredException) {
                explainer.cancel()
                WidgetData.setRefresh(ctx, false, "Signed out by Peugeot — open LionFlow to sign in again")
            } catch (e: IOException) {
                explainer.cancel()
                WidgetData.setRefresh(ctx, false, "No connection — tap ↻ to try again")
            } catch (e: Exception) {
                explainer.cancel()
                WidgetData.setRefresh(ctx, false, "Peugeot didn't answer — tap ↻ to try again")
            }
            Result.success()
        }
    }
}
