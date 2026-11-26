package no.lionflow.app.ui.l10n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 12- or 24-hour clock, from Settings (or the phone's own setting). */
object TimeFmt {
    var use24 by mutableStateOf(true)
        private set

    fun setPreference(ctx: Context, pref: String) {
        use24 = when (pref) { "12" -> false; "24" -> true; else -> android.text.format.DateFormat.is24HourFormat(ctx) }
    }

    /** Turns a date pattern written with HH:mm into the chosen clock. */
    fun pattern(p: String): String = if (use24) p else p.replace("HH:mm:ss", "h:mm:ss a").replace("HH:mm", "h:mm a")

    fun hm(h: Int, m: Int): String =
        if (use24) String.format(java.util.Locale.US, "%02d:%02d", h, m)
        else String.format(java.util.Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}
