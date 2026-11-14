package no.lionflow.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.lionflow.app.BuildConfig
import no.lionflow.app.api.Http
import okhttp3.Request
import org.json.JSONObject
import java.io.File

data class Release(val tag: String, val versionCode: Int, val apkUrl: String, val notes: String)

/**
 * In-app updates from GitHub Releases. The downloaded APK must be signed with the same key as the
 * installed app; then it's installed with PackageInstaller. On Android 12+ updates need no extra tap
 * once LionFlow has installed itself once (Android's "update ownership" rule).
 */
object Updater {
    /**
     * True when this copy came from Google Play. Then Play handles updates: installing a GitHub
     * build on top would drop the "from Google Play" mark that Android Auto needs.
     */
    fun installedFromPlay(ctx: Context): Boolean = try {
        val pm = ctx.packageManager
        val installer = if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(ctx.packageName)
        installer == "com.android.vending"
    } catch (_: Exception) { false }

    private const val LATEST = "https://api.github.com/repos/thocubeat/lionflow/releases/latest"

    suspend fun latest(): Release? {
        val req = Request.Builder().url(LATEST)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", Http.USER_AGENT)
            .get().build()
        val j = Http.json(req) as? JSONObject ?: return null
        val tag = j.optString("tag_name")
        val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
        val assets = j.optJSONArray("assets") ?: return null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            // The versioned file; the plain "LionFlow.apk" is left for first-time downloads (that's what the download count shows).
            if (a.optString("name").matches(Regex("LionFlow-.*\\.apk"))) { url = a.optString("browser_download_url"); break }
        }
        return Release(tag, code, url ?: return null, j.optString("body"))
    }

    fun isNewer(r: Release) = r.versionCode > BuildConfig.VERSION_CODE

    suspend fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "LionFlow-${r.tag}.apk")
        Http.client.newCall(Request.Builder().url(r.apkUrl).header("User-Agent", Http.USER_AGENT).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw no.lionflow.app.api.ApiException(resp.code, "Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw IllegalStateException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done * 100 / total).toInt())
                    }
                }
            }
        }
        f
    }

    /** True only if the APK is LionFlow and signed with exactly the same certificate as this install. */
    @Suppress("DEPRECATION")
    fun sameSigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val a = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return false
                if (a.packageName != ctx.packageName) return false
                val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners ?: return false
                val theirs = a.signingInfo?.apkContentsSigners ?: return false
                mine.map { it.toCharsString() }.toSet() == theirs.map { it.toCharsString() }.toSet()
            } else {
                val a = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES) ?: return false
                if (a.packageName != ctx.packageName) return false
                val mine = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures ?: return false
                mine.map { it.toCharsString() }.toSet() == a.signatures?.map { it.toCharsString() }?.toSet()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            apk.inputStream().use { input -> s.openWrite("base.apk", 0, apk.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Receives the install result; if Android wants the user to confirm (first self-update), shows that screen. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> { /* the new version starts the next time the app is opened */ }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                (context.applicationContext as? no.lionflow.app.LionFlowApp)?.remote?.addLog("update failed: $msg")
            }
        }
    }
}
