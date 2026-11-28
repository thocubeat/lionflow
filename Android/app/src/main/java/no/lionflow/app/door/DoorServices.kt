package no.lionflow.app.door

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.lionflow.app.api.Http
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Where a garage door stands. */
enum class DoorState { OPEN, CLOSED, OPENING, CLOSING, UNKNOWN }

/** One door (or gate) the opener knows: its id for the opener, a name to show and where it stands. */
data class Door(val id: String, val name: String, val state: DoorState)

/** Something that opens and closes a garage door. */
interface DoorService {
    /** The doors there are to choose from (also checks the login). */
    suspend fun doors(): List<Door>
    suspend fun state(door: String): DoorState
    /** Opens or closes [door]. False when it already was that way, so nothing was sent. */
    suspend fun set(door: String, open: Boolean): Boolean
}

/** A message meant for the person: a wrong password, an address that can't be reached. */
class DoorException(message: String) : Exception(message)

internal val doorClient: OkHttpClient by lazy {
    Http.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
}

private suspend fun call(req: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
    doorClient.newCall(req.newBuilder().header("User-Agent", Http.USER_AGENT).build()).execute().use { it.code to (it.body?.string() ?: "") }
}

// ---------------------------------------------------------------- ismartgate and Gogogate 2

/**
 * ismartgate and Gogogate 2 openers, through the opener's own API (api.php): the command is sent AES-encrypted, and
 * the answer is XML. ismartgate derives the key from the username and password; Gogogate 2 uses one fixed key.
 * The protocol follows the MIT-licensed ismartgate library (github.com/bdraco/ismartgate) that Home Assistant uses.
 *
 * An ismartgate is reached at home by its IP address, and from anywhere by its UDI (UDI.isgaccess.com, the opener's
 * remote access). The opener has one button per door that starts and stops it, so the door's state is checked first.
 */
class GateApi(host: String, private val user: String, private val password: String, private val ismartgate: Boolean = true) : DoorService {
    private val key: String = if (ismartgate) isgKey(user, password) else GOGOGATE_KEY
    private val bases: List<String> = bases(host, ismartgate)
    private var working: String? = null

    companion object {
        const val GOGOGATE_KEY = "0e3b7%i1X9@54cAf"
        private val IP = Regex("""^\d{1,3}(\.\d{1,3}){3}(:\d+)?$""")

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun isgKey(user: String, password: String): String {
            val h = sha1(user.lowercase() + password)
            return h.substring(32, 36) + "a" + h.substring(7, 10) + "!" + h.substring(18, 21) + "*#" + h.substring(24, 26)
        }

        fun token(user: String): String = sha1(user.lowercase() + "@ismartgate")

        /**
         * The addresses to try for what was typed: a UDI ("A1B2C3") becomes UDI.isgaccess.com (https first, then
         * http); an IP address is reached directly on the home network.
         */
        fun bases(typed: String, ismartgate: Boolean): List<String> {
            val t = typed.trim()
            val scheme = Regex("^(https?)://", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.lowercase()
            val host = t.replace(Regex("^https?://", RegexOption.IGNORE_CASE), "").substringBefore('/').trim()
            if (host.isEmpty()) return emptyList()
            if (scheme != null) return listOf("$scheme://$host")
            if (IP.matches(host) || host.endsWith(".local") || host.contains(':')) return listOf("http://$host")
            val name = if (ismartgate && !host.contains('.')) "$host.isgaccess.com" else host
            return listOf("https://$name", "http://$name")
        }

        private fun pad(b: ByteArray): ByteArray {
            val n = 16 - b.size % 16
            return b + ByteArray(n) { n.toByte() }
        }

        /** AES/CBC/PKCS5 as the opener wants it: 16 characters of IV in front, then the ciphertext in Base64. */
        fun encrypt(key: String, content: String, iv: String = randomIv()): String {
            val ivb = iv.toByteArray(Charsets.UTF_8).copyOf(16)
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(ivb))
            return String(ivb, Charsets.UTF_8) + java.util.Base64.getEncoder().encodeToString(c.doFinal(pad(content.toByteArray(Charsets.UTF_8))))
        }

        fun decrypt(key: String, content: String): String {
            if (content.length <= 16) throw IllegalArgumentException("too short")
            val iv = content.substring(0, 16).toByteArray(Charsets.UTF_8)
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv))
            val out = c.doFinal(java.util.Base64.getDecoder().decode(content.substring(16).trim()))
            val n = out.lastOrNull()?.toInt() ?: 0
            if (n !in 1..16) throw IllegalArgumentException("bad padding")
            return String(out, 0, out.size - n, Charsets.UTF_8)
        }

        private fun randomIv(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        /** The command as Python's json.dumps writes it (", " between items, non-ASCII as \u escapes). */
        fun command(vararg parts: String): String = parts.joinToString(", ", "[", "]") { s ->
            val sb = StringBuilder("\"")
            for (ch in s) when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '\u000C' -> sb.append("\\f")
                ch.code < 0x20 || ch.code > 0x7E && ch.code != 0x7F -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
            sb.append('"').toString()
        }

        private fun tag(xml: String, name: String): String? =
            Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

        fun stateOf(s: String?): DoorState = when (s?.lowercase()) { "opened" -> DoorState.OPEN; "closed" -> DoorState.CLOSED; else -> DoorState.UNKNOWN }

        /** The doors in an info answer; for ismartgate, the code each needs to be moved. */
        fun parseDoors(xml: String, ismartgate: Boolean): List<Pair<Door, String>> {
            val shared = tag(xml, "apicode").takeUnless { ismartgate }
            return (1..3).mapNotNull { i ->
                val d = tag(xml, "door$i") ?: return@mapNotNull null
                val code = (if (ismartgate) tag(d, "apicode") else shared) ?: return@mapNotNull null
                val name = tag(d, "name").orEmpty()
                val on = if (ismartgate) tag(d, "enabled")?.lowercase() == "yes" else name.isNotEmpty()
                if (!on || name.isEmpty()) return@mapNotNull null
                Door(i.toString(), name, stateOf(tag(d, "status"))) to code
            }
        }

        /** Throws the opener's own error, if the answer is one. */
        fun checkError(xml: String) {
            val err = tag(xml, "error") ?: return
            val code = tag(err, "errorcode")?.toIntOrNull()
            val msg = tag(err, "errormsg").orEmpty()
            throw DoorException(when (code) {
                11, 21, 22 -> "Wrong username or password"
                else -> "The opener said: " + msg.ifBlank { "error $code" }
            })
        }
    }

    private suspend fun request(option: String, arg1: String = "", arg2: String = ""): String {
        if (bases.isEmpty()) throw DoorException("Type the opener's address")
        val data = encrypt(key, command(user, password, option, arg1, arg2))
        var last: Exception? = null
        for (base in listOfNotNull(working) + bases.filter { it != working }) {
            val url = "$base/api.php".toHttpUrlOrNull()?.newBuilder() ?: continue
            url.addQueryParameter("data", data)
            if (ismartgate) {
                url.addQueryParameter("t", (1..100_000_000).random().toString())
                url.addQueryParameter("token", token(user))
            }
            try {
                val (code, body) = call(Request.Builder().url(url.build()).get().build())
                if (code !in 200..299) { last = DoorException("The opener answered with error $code"); continue }
                // Errors come back unencrypted.
                val xml = try { decrypt(key, body) } catch (_: Exception) { body }
                if (!xml.contains("<")) { last = DoorException("The opener's answer couldn't be read. Check the address"); continue }
                checkError(xml)
                working = base
                return xml
            } catch (e: DoorException) {
                if (e.message?.startsWith("Wrong") == true || e.message?.startsWith("The opener said") == true) throw e
                last = e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last as? DoorException ?: DoorException("Couldn't reach the opener" + (last?.let { " (" + Http.describe(it) + ")" } ?: ""))
    }

    private suspend fun info(): List<Pair<Door, String>> = parseDoors(request("info"), ismartgate)

    override suspend fun doors(): List<Door> = info().map { it.first }

    override suspend fun state(door: String): DoorState = info().firstOrNull { it.first.id == door }?.first?.state ?: DoorState.UNKNOWN

    override suspend fun set(door: String, open: Boolean): Boolean {
        val (d, code) = info().firstOrNull { it.first.id == door } ?: throw DoorException("The door isn't set up on the opener any more")
        if (d.state == (if (open) DoorState.OPEN else DoorState.CLOSED)) return false
        val xml = request("activate", door, code)
        val result = Regex("<result>(.*?)</result>").find(xml)?.groupValues?.get(1)?.trim()
        if (result != null && !result.equals("OK", ignoreCase = true)) throw DoorException("The opener said: $result")
        return true
    }
}

// ---------------------------------------------------------------- Home Assistant

/**
 * A garage door (a "cover") in Home Assistant, through its REST API with a long-lived access token.
 * The address can be the one at home or a remote one (Nabu Casa, a reverse proxy).
 */
class HomeAssistantApi(address: String, private val token: String) : DoorService {
    private val base = address.trim().trimEnd('/').let { if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(it)) it else "http://$it" }

    companion object {
        fun stateOf(s: String?): DoorState = when (s?.lowercase()) {
            "open" -> DoorState.OPEN; "closed" -> DoorState.CLOSED; "opening" -> DoorState.OPENING; "closing" -> DoorState.CLOSING; else -> DoorState.UNKNOWN
        }

        /** The covers in a /api/states answer: garage doors and gates first. */
        fun parseCovers(arr: JSONArray): List<Door> {
            val out = ArrayList<Pair<Door, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("entity_id")
                if (!id.startsWith("cover.")) continue
                val attrs = o.optJSONObject("attributes")
                val name = attrs?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id.removePrefix("cover.")
                val rank = when (attrs?.optString("device_class")) { "garage" -> 0; "gate" -> 1; else -> 2 }
                out += Door(id, name, stateOf(o.optString("state"))) to rank
            }
            return out.sortedBy { it.second }.map { it.first }
        }
    }

    private suspend fun get(path: String): String {
        val url = "$base$path".toHttpUrlOrNull() ?: throw DoorException("The address doesn't look right")
        val (code, body) = try { call(Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()) }
        catch (e: Exception) { throw DoorException("Couldn't reach Home Assistant (" + Http.describe(e) + ")") }
        if (code == 401 || code == 403) throw DoorException("Home Assistant didn't accept the token")
        if (code !in 200..299) throw DoorException("Home Assistant answered with error $code")
        return body
    }

    override suspend fun doors(): List<Door> {
        val covers = try { parseCovers(JSONArray(get("/api/states"))) } catch (e: org.json.JSONException) { throw DoorException("That doesn't look like Home Assistant") }
        if (covers.isEmpty()) throw DoorException("Home Assistant has no garage doors (covers)")
        return covers
    }

    override suspend fun state(door: String): DoorState = stateOf(JSONObject(get("/api/states/$door")).optString("state"))

    override suspend fun set(door: String, open: Boolean): Boolean {
        val now = state(door)
        if (now == (if (open) DoorState.OPEN else DoorState.CLOSED) || now == (if (open) DoorState.OPENING else DoorState.CLOSING)) return false
        val url = "$base/api/services/cover/${if (open) "open_cover" else "close_cover"}".toHttpUrlOrNull() ?: throw DoorException("The address doesn't look right")
        val body = JSONObject().put("entity_id", door).toString().toRequestBody("application/json".toMediaType())
        val (code, _) = try { call(Request.Builder().url(url).header("Authorization", "Bearer $token").post(body).build()) }
        catch (e: Exception) { throw DoorException("Couldn't reach Home Assistant (" + Http.describe(e) + ")") }
        if (code !in 200..299) throw DoorException("Home Assistant answered with error $code")
        return true
    }
}

// ---------------------------------------------------------------- links

/**
 * Two web links, one to open and one to close (IFTTT, Homey, Shelly, Node-RED and most other services have them).
 * Called with GET; a link that wants POST (it answers 405) is called again with POST. There is no state to read.
 */
class LinksApi(private val openUrl: String, private val closeUrl: String) : DoorService {
    override suspend fun doors(): List<Door> {
        if (openUrl.toHttpUrlOrNull() == null || closeUrl.toHttpUrlOrNull() == null) throw DoorException("Both links must start with http:// or https://")
        return listOf(Door("1", "Garage door", DoorState.UNKNOWN))
    }

    override suspend fun state(door: String) = DoorState.UNKNOWN

    override suspend fun set(door: String, open: Boolean): Boolean {
        val url = (if (open) openUrl else closeUrl).toHttpUrlOrNull() ?: throw DoorException("The link doesn't look right")
        try {
            var (code, _) = call(Request.Builder().url(url).get().build())
            if (code == 405) code = call(Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()).first
            if (code !in 200..299) throw DoorException("The link answered with error $code")
        } catch (e: DoorException) { throw e } catch (e: Exception) { throw DoorException("Couldn't reach the link (" + Http.describe(e) + ")") }
        return true
    }
}

