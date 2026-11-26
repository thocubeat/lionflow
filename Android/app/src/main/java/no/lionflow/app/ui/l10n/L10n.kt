package no.lionflow.app.ui.l10n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Translations. The app is written in English; every text is looked up here right before it is shown
 * (screens, toasts, notifications, widget, Android Auto). Data values are never translated, so app
 * logic can't break. Texts with values in them ("Stop at 80 %") are matched against templates
 * ("Stop at {0} %"), and the values are translated too when they are known words.
 */
object L10n {
    /** Languages offered in Settings: code to native name. "system" follows the phone. */
    val OPTIONS = listOf(
        "system" to "System default",
        "en" to "English",
        "fr" to "Français",
        "de" to "Deutsch",
        "es" to "Español",
        "nb" to "Norsk (bokmål)",
        "nl" to "Nederlands",
    )
    private val COLUMNS = listOf("fr", "de", "es", "nb", "nl")

    /** The language in use right now (never "system"). Compose re-draws when it changes. */
    var lang by mutableStateOf("en")
        private set

    val locale: Locale get() = when (lang) { "nb" -> Locale.forLanguageTag("nb-NO"); "en" -> Locale.ENGLISH; else -> Locale.forLanguageTag(lang) }

    private var exact: Map<String, Array<String>> = emptyMap()
    private var templates: List<Triple<Regex, String, Array<String>>> = emptyList()   // pattern, English template, translations
    private val cache = HashMap<String, String>()
    private var loaded = false

    fun init(ctx: Context, preference: String) {
        if (!loaded) load(ctx)
        setPreference(preference)
    }

    fun setPreference(preference: String) {
        val l = if (preference == "system" || preference.isBlank()) systemLang() else preference
        synchronized(cache) { cache.clear() }
        lang = if (l in COLUMNS || l == "en") l else "en"
        android.util.Log.i("L10n", "language $preference -> $lang")
    }

    private fun systemLang(): String = when (val l = Locale.getDefault().language) {
        "no", "nb", "nn" -> "nb"
        else -> l
    }

    private fun load(ctx: Context) {
        try {
            val lines = ctx.assets.open("l10n.tsv").bufferedReader(Charsets.UTF_8).readLines().drop(1)
            val ex = HashMap<String, Array<String>>()
            val tp = ArrayList<Triple<Regex, String, Array<String>>>()
            for (line in lines) {
                val cols = line.split('\t').map { unesc(it) }
                if (cols.size < 5) continue
                val en = cols[0]
                val tr = Array(COLUMNS.size) { cols.getOrElse(it + 1) { "" } }
                ex[en] = tr
                // Fragments that are glued to other text: "Ready at " + time, base + " · auto when open".
                var t = en; var trs = tr
                if (t.isNotBlank()) {
                    if (t.endsWith(" ")) { t += "{9}"; trs = trs.map { it + "{9}" }.toTypedArray() }
                    else if (t.startsWith(" ")) { t = "{9}$t"; trs = trs.map { "{9}$it" }.toTypedArray() }
                }
                if (Regex("\\{\\d\\}").containsMatchIn(t)) try { tp += Triple(toRegex(t), t, trs) } catch (e: Exception) { android.util.Log.w("L10n", "bad template: $t", e) }
            }
            exact = ex
            // Most specific (most literal text) first.
            templates = tp.sortedByDescending { it.second.replace(Regex("\\{\\d\\}"), "").length }
            loaded = true
            android.util.Log.i("L10n", "loaded ${ex.size} texts, ${tp.size} templates")
        } catch (e: Exception) { android.util.Log.e("L10n", "couldn't load translations", e) }
    }

    private fun unesc(s: String): String {
        if (!s.contains('\\')) return s
        val b = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) { 'n' -> b.append('\n'); 't' -> b.append('\t'); '\\' -> b.append('\\'); else -> b.append(s[i + 1]) }
                i += 2
            } else { b.append(c); i++ }
        }
        return b.toString()
    }

    private fun toRegex(t: String): Regex {
        val sb = StringBuilder("^")
        var i = 0
        val m = Regex("\\{(\\d)\\}")
        var last = 0
        for (r in m.findAll(t)) {
            sb.append(Regex.escape(t.substring(last, r.range.first)))
            sb.append("(?<p").append(r.groupValues[1]).append(">.+?)")
            last = r.range.last + 1
            i++
        }
        sb.append(Regex.escape(t.substring(last))).append("$")
        return Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL)
    }

    /** Translate a text that is about to be shown. */
    fun tr(s: String): String = no.lionflow.app.model.Brands.apply(trRaw(s))

    private fun trRaw(s: String): String {
        val l = lang
        if (l == "en" || s.isBlank()) return s
        synchronized(cache) { cache[s]?.let { return it } }
        val col = COLUMNS.indexOf(l)
        val out = try { translate(s, col, 0) } catch (_: Exception) { s }
        synchronized(cache) { if (cache.size > 3000) cache.clear(); cache[s] = out }
        return out
    }

    private fun translate(s: String, col: Int, depth: Int): String {
        exact[s]?.get(col)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (depth > 3) return s
        // Several templates can fit ("Updated {0}" and "{0} min ago" both fit "Updated 3 min ago"):
        // pick the one with the most fixed text whose values translate cleanly.
        var best: String? = null
        var bestScore = Int.MIN_VALUE
        for ((re, en, tr) in templates) {
            val m = re.matchEntire(s) ?: continue
            var out = tr[col]
            if (out.isEmpty()) continue
            var penalty = 0
            for (n in Regex("\\{(\\d)\\}").findAll(en).map { it.groupValues[1] }.distinct()) {
                val v = m.groups["p$n"]?.value ?: continue
                val t = translate(v, col, depth + 1)
                val letters = v.count { it.isLetter() }
                penalty += letters + if (t == v) letters * 30 else 0
                out = out.replace("{$n}", t)
            }
            val score = en.replace(Regex("\\{\\d\\}"), "").length * 10 - penalty
            if (score > bestScore) { bestScore = score; best = out }
        }
        best?.let { return it }
        // Lists joined with " · " or lines: translate each part.
        for (sep in listOf("\n", " · ", " — ")) {
            if (s.contains(sep)) {
                val parts = s.split(sep)
                val t = parts.map { translate(it, col, depth + 1) }
                if (t != parts) return t.joinToString(sep)
            }
        }
        return s
    }
}

/** Short alias for non-Compose code (Android Auto, widget, notifications). */
fun tr(s: String): String = L10n.tr(s)
