package no.lionflow.app.car

import android.content.Context
import android.location.Geocoder
import android.telephony.TelephonyManager
import java.util.Locale

/** Which country the car is in: from the mobile network the phone is on, else from the car's position. */
object Where {
    @Volatile private var cached: Pair<Long, String?>? = null
    @Volatile private var looking = false

    private fun fromNetwork(ctx: Context): String? = try {
        (ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager)?.networkCountryIso?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
    } catch (_: Exception) { null }

    /**
     * The country, as far as it is known right now. Never waits: the mobile network answers at once, and the lookup
     * from the car's position (which can take seconds) is done by [warm] in the background.
     */
    fun country(ctx: Context): String? {
        cached?.let { (t, c) -> if (c != null && System.currentTimeMillis() - t < 10 * 60_000L) return c }
        val cc = fromNetwork(ctx) ?: return cached?.second
        cached = System.currentTimeMillis() to cc
        return cc
    }

    /** Finds the country from the car's position when the mobile network doesn't say; [found] is called on the main thread if that gave one. */
    fun warm(ctx: Context, found: () -> Unit) {
        if (looking || country(ctx) != null) return
        val st = CarHub.status.value
        val la = st?.lat ?: return; val lo = st.lon ?: return
        val app = ctx.applicationContext
        looking = true
        Thread({
            val cc = try {
                @Suppress("DEPRECATION")
                Geocoder(app, Locale.ROOT).getFromLocation(la, lo, 1)?.firstOrNull()?.countryCode?.uppercase(Locale.ROOT)
            } catch (_: Exception) { null }
            if (cc != null) {
                cached = System.currentTimeMillis() to cc
                android.os.Handler(android.os.Looper.getMainLooper()).post { try { found() } catch (_: Exception) { } }
            }
            looking = false
        }, "LionFlowWhere").start()
    }

    fun inNorway(ctx: Context) = country(ctx) == "NO"

    /** The spoken language for where the car is (English where we don't know it). */
    fun speechLanguage(ctx: Context): String = when (country(ctx)) {
        "NO", "SJ" -> "nb-NO"; "SE" -> "sv-SE"; "DK" -> "da-DK"; "FI" -> "fi-FI"; "IS" -> "is-IS"
        "DE" -> "de-DE"; "AT" -> "de-AT"; "CH" -> "de-CH"; "LI" -> "de-DE"; "LU" -> "fr-LU"
        "FR", "MC" -> "fr-FR"; "BE" -> "fr-BE"; "NL" -> "nl-NL"; "ES" -> "es-ES"; "PT" -> "pt-PT"; "IT", "SM" -> "it-IT"
        "PL" -> "pl-PL"; "CZ" -> "cs-CZ"; "SK" -> "sk-SK"; "HU" -> "hu-HU"; "SI" -> "sl-SI"; "HR" -> "hr-HR"; "RO" -> "ro-RO"
        "GR" -> "el-GR"; "TR" -> "tr-TR"; "EE" -> "et-EE"; "LV" -> "lv-LV"; "LT" -> "lt-LT"
        "GB" -> "en-GB"; "IE" -> "en-IE"; "AU" -> "en-AU"; "CA" -> "en-CA"; "US" -> "en-US"
        else -> "en-GB"
    }
}
