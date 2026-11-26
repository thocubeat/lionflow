package no.lionflow.app.model

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * All app data in one file (settings, saved trips, tags, logs, recorded routes), to move it to a new install.
 * The sign-in is left out: it stays on this phone, and you sign in again after restoring.
 */
object Backup {
    private const val MARK = "lionflow-backup.txt"
    private val skipPrefs = setOf("lf_secure.xml", "demo.xml", "charge_log_demo.xml")

    fun export(ctx: Context): File {
        val data = ctx.applicationInfo.dataDir.let(::File)
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "lionflow-backup.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry(MARK)); z.write("LionFlow backup ${System.currentTimeMillis()}".toByteArray()); z.closeEntry()
            File(data, "shared_prefs").listFiles()?.filter { it.isFile && it.name !in skipPrefs && !it.name.startsWith("androidx.") }?.forEach { f ->
                z.putNextEntry(ZipEntry("shared_prefs/${f.name}")); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
            ctx.filesDir.walkTopDown().filter { it.isFile }.forEach { f ->
                z.putNextEntry(ZipEntry("files/" + f.relativeTo(ctx.filesDir).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
        return out
    }

    /** Puts the data from a backup back and restarts the app. False when the file isn't a LionFlow backup. */
    fun restore(ctx: Context, uri: Uri): Boolean {
        val data = File(ctx.applicationInfo.dataDir).canonicalFile
        val tmp = File(ctx.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var ok = false
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.name == MARK) { ok = true; continue }
                    if (e.isDirectory || !(e.name.startsWith("shared_prefs/") || e.name.startsWith("files/"))) continue
                    if (e.name.startsWith("shared_prefs/") && e.name.substringAfter("/") in skipPrefs) continue
                    val f = File(tmp, e.name).canonicalFile
                    if (!f.path.startsWith(tmp.canonicalPath + File.separator)) continue   // no paths outside the app
                    f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }
                }
            }
        }
        if (!ok) { tmp.deleteRecursively(); return false }
        tmp.walkTopDown().filter { it.isFile }.forEach { f ->
            val dest = File(data, f.relativeTo(tmp).path)
            dest.parentFile?.mkdirs(); f.copyTo(dest, overwrite = true)
        }
        tmp.deleteRecursively()
        // Start again at once, so nothing in memory writes the old settings back over the restored ones.
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) {
            val pi = PendingIntent.getActivity(ctx, 7, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.RTC, System.currentTimeMillis() + 600, pi)
        }
        Runtime.getRuntime().exit(0)
        return true
    }
}
