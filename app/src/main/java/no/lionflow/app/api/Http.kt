package no.lionflow.app.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    const val USER_AGENT = "LionFlow/1.0 (personal app; github.com/thocubeat/lionflow)"

    suspend fun text(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(resp.code, describeHttpError(resp.code, body, req.url.host))
            body
        }
    }

    suspend fun json(req: Request): Any? = parse(text(req))

    /** "Peugeot server error 503 (temporarily unavailable): <the server's own message>" */
    fun describeHttpError(code: Int, body: String, host: String): String {
        val who = when {
            host.contains("psa") || host.contains("mpsa") || host.contains("stellantis") || host.contains("peugeot") -> "Peugeot"
            host.contains("met.no") -> "MET weather"
            host.contains("nobil") -> "NOBIL"
            else -> host
        }
        val meaning = when (code) {
            400 -> "request rejected"
            401 -> "not signed in / session expired"
            403 -> "access denied"
            404 -> "not found"
            408 -> "timed out"
            409 -> "conflict"
            429 -> "too many requests — wait a little"
            500 -> "internal server error"
            502 -> "bad gateway"
            503 -> "temporarily unavailable"
            504 -> "gateway timeout"
            else -> "error"
        }
        val detail = try {
            val j = org.json.JSONObject(body)
            listOfNotNull(
                j.optString("error_description").ifBlank { null },
                j.optString("message").ifBlank { null },
                j.optJSONArray("errors")?.optJSONObject(0)?.let { e -> e.optString("message").ifBlank { e.optString("code") } }?.ifBlank { null },
                j.optString("error").ifBlank { null },
                j.optString("code").ifBlank { null }?.takeIf { it != code.toString() },
            ).distinct().joinToString(" · ")
        } catch (_: Exception) {
            body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(160)
        }
        return "$who error $code ($meaning)" + if (detail.isNotBlank()) ": $detail" else ""
    }

    /** Human text for any exception from a network call. */
    fun describe(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Server error ${e.code}"
        is java.net.UnknownHostException -> "No internet connection (couldn't find ${e.message ?: "the server"})"
        is java.net.SocketTimeoutException -> "The server didn't answer in time (timeout)"
        is java.net.ConnectException -> "Couldn't connect to the server"
        is javax.net.ssl.SSLException -> "Secure connection failed: ${e.message}"
        is java.io.IOException -> "Network problem: ${e.message ?: e.javaClass.simpleName}"
        else -> e.message ?: e.javaClass.simpleName
    }

    fun parse(s: String): Any? {
        val t = s.trim()
        if (t.isEmpty()) return null
        return when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> JSONObject(t)
            else -> t
        }
    }
}

// ---- tiny JSON helpers used across the app ----

fun JSONObject.obj(vararg path: String): JSONObject? {
    var cur: JSONObject? = this
    for (p in path) cur = cur?.optJSONObject(p)
    return cur
}

fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun JSONObject.num(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

fun JSONArray.objects(): List<JSONObject> =
    (0 until length()).mapNotNull { optJSONObject(it) }
