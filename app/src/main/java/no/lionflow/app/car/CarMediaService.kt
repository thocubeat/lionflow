package no.lionflow.app.car

import android.graphics.Bitmap
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.car.app.connection.CarConnection
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Observer
import androidx.media.MediaBrowserServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp

/**
 * LionFlow as a source in Android Auto's media view: tabs with the car's status and quick actions.
 * It plays no audio and never takes audio focus; "playing" an item runs it.
 *
 * Security: only Android Auto (or LionFlow itself) may browse, and actions only run while the
 * phone is actually connected to Android Auto. Unlock and horn are not offered here.
 */
class CarMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projected = false
    private val connObserver = Observer<Int> { projected = it == CarConnection.CONNECTION_TYPE_PROJECTION || it == CarConnection.CONNECTION_TYPE_NATIVE }
    private lateinit var conn: CarConnection
    private val icons = HashMap<Int, Bitmap>()

    override fun onCreate() {
        super.onCreate()
        session = MediaSessionCompat(this, "LionFlow").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (!trustedController()) return
                    run(mediaId ?: return)
                }
                override fun onPlay() {
                    if (!trustedController()) return
                    CarHub.refresh(this@CarMediaService, force = true)
                    CarHub.say("Updating car status…")
                }
                override fun onPause() {}
                override fun onStop() {}
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        conn = CarConnection(this)
        conn.type.observeForever(connObserver)
        publish()
        scope.launch {
            combine(CarHub.status, CarHub.message, CarHub.busy) { _, _, _ -> Unit }.collect {
                publish()
                notifyChildrenChanged(TAB_STATUS)
                notifyChildrenChanged(TAB_ACTIONS)
            }
        }
    }

    override fun onDestroy() {
        conn.type.removeObserver(connObserver)
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    // ---------------- who may browse ----------------

    private fun trustedPackage(pkg: String?) = pkg == packageName || pkg in TRUSTED

    private fun trustedController(): Boolean {
        val pkg = try { session.currentControllerInfo.packageName } catch (_: Exception) { null }
        return trustedPackage(pkg)
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        if (!trustedPackage(clientPackageName)) return null
        CarHub.refresh(this)
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, STYLE_LIST)
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        result.sendResult(when (parentId) {
            ROOT -> mutableListOf(
                browsable(TAB_STATUS, "Car", R.drawable.ic_car_battery),
                browsable(TAB_ACTIONS, "Actions", R.drawable.ic_car_climate),
            )
            TAB_STATUS -> statusItems()
            TAB_ACTIONS -> actionItems()
            else -> mutableListOf()
        })
    }

    private fun statusItems(): MutableList<MediaItem> {
        val st = CarHub.status.value
        val s = CarHub.app(this).settings.value
        if (st == null) return mutableListOf(playable("refresh", CarHub.batteryLine(this), "Tap to update", R.drawable.ic_car_battery))
        return mutableListOf(
            playable("refresh", CarHub.batteryLine(this), CarHub.chargingLine(st), R.drawable.ic_car_battery),
            playable("refresh:2", if (st.preconditioningOn) "Pre-heating is on" else "Pre-heating is off",
                st.outsideTempC?.let { "${fmtTemp(it, s)} outside" } ?: "Outside temperature unknown", R.drawable.ic_car_climate),
            playable("refresh:3", "Doors: ${CarHub.doorsLine(st)}", st.odometerKm?.let { "Odometer ${fmtDistance(it, s)}" } ?: "", R.drawable.ic_car_lock),
            playable("refresh:4", CarHub.message.value ?: CarHub.updatedLine(), "Tap any row to update", R.drawable.ic_car_refresh),
        )
    }

    private fun actionItems(): MutableList<MediaItem> {
        CarHub.actionsAllowed(this)?.let { return mutableListOf(playable("none", "Remote actions unavailable", it, R.drawable.ic_car_lock)) }
        val st = CarHub.status.value
        val heat = st?.preconditioningOn == true
        val charging = st?.isCharging == true
        return mutableListOf(
            playable(if (heat) "act:heat_off" else "act:heat_on", if (heat) "Stop pre-heating" else "Pre-heat", "Heats or cools the cabin for about 30 minutes", R.drawable.ic_car_climate),
            playable(if (charging) "act:charge_off" else "act:charge_on", if (charging) "Stop charging" else "Charge now", st?.let { CarHub.chargingLine(it) } ?: "", R.drawable.ic_car_bolt),
            playable("act:lock", "Lock", "Lock all doors", R.drawable.ic_car_lock),
            playable("act:lights", "Flash lights", "Find the car in a car park", R.drawable.ic_car_lights),
        )
    }

    private fun run(id: String) {
        when {
            id.startsWith("refresh") -> { CarHub.refresh(this, force = true); CarHub.say("Updating car status…") }
            id.startsWith("act:") -> {
                if (!projected) { CarHub.say("Actions only work while connected to Android Auto"); return }
                when (id) {
                    "act:heat_on" -> CarHub.preheat(this, true)
                    "act:heat_off" -> CarHub.preheat(this, false)
                    "act:charge_on" -> CarHub.charge(this, true)
                    "act:charge_off" -> CarHub.charge(this, false)
                    "act:lock" -> CarHub.lock(this, true)
                    "act:lights" -> CarHub.lights(this)
                }
            }
        }
        publish()
    }

    /** The "now playing" card: car status as title/subtitle, progress while a command runs. */
    private fun publish() {
        val msg = CarHub.message.value
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, CarHub.batteryLine(this))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, msg ?: CarHub.detailLine(this))
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, CarHub.app(this).settings.value.carName)
            .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, icon(R.mipmap.ic_launcher, 256))
            .putBitmap(MediaMetadataCompat.METADATA_KEY_ART, icon(R.mipmap.ic_launcher, 512))
            .build())
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(if (CarHub.busy.value) PlaybackStateCompat.STATE_BUFFERING else PlaybackStateCompat.STATE_PAUSED, 0, 0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID)
            .build())
    }

    private fun icon(res: Int, size: Int = 96): Bitmap = icons.getOrPut(res * 31 + size) {
        ContextCompat.getDrawable(this, res)!!.toBitmap(size, size)
    }

    private fun browsable(id: String, title: String, iconRes: Int) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setIconBitmap(icon(iconRes)).build(),
        MediaItem.FLAG_BROWSABLE,
    )

    private fun playable(id: String, title: String, subtitle: String, iconRes: Int) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).setIconBitmap(icon(iconRes)).build(),
        MediaItem.FLAG_PLAYABLE,
    )

    companion object {
        private const val ROOT = "root"
        private const val TAB_STATUS = "status"
        private const val TAB_ACTIONS = "actions"
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val STYLE_LIST = 1
        private val TRUSTED = setOf(
            "com.google.android.projection.gearhead",       // Android Auto
            "com.google.android.carassistant",              // Android Automotive media
            "com.android.car.media",
        )
    }
}
