// SPDX-License-Identifier: GPL-3.0-or-later
package no.lionflow.app.api

import android.util.Xml
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Kotlin port of the inWebo OTP generator used by the MyPeugeot app for remote commands.
 * Ported from psa_car_controller (GPL-3.0, Florian Bezannier) via homeassistant-stellantis-vehicles.
 *
 * Activation (once): SMS code + the MyPeugeot security code (PIN).
 * After that it produces one-time passwords that are traded for an MQTT token.
 * The whole state, including the PIN, is persisted encrypted in [no.lionflow.app.data.SecureStore].
 */
class Otp(private val macid: String, var deviceId: String) {

    class OtpException(message: String) : Exception(message)

    var iwalea: String = randomHex(16)
    var kiw: String? = null
    var kfact: String? = null
    var codepin: String = ""
    var smsCode: String = ""
    var challenge: String = ""
    var action: String = ""
    var mode: String = ACTIVATE_MODE
    var defi: String = "0"

    // IWData fields actually used by the protocol (defaults from the generator's default token)
    var iwid: String = ""
    var iwTsync: String = "0"
    var iwK0: String = ""
    var iwK1: String = ""
    var iwsecid: String = ""
    var iwsecval: String = ""

    private fun serial() = "$deviceId/_/$iwalea"

    private fun kma(pin: String): String = sha256Hex("$pin;${serial()}").substring(0, 32)

    private fun getR(): Map<String, String> {
        val iw = iwK0
        val r2 = if (action == "synchro") "$challenge;$iw;$codepin" else "$challenge;$iw;"
        val r0 = "$challenge;$iw;${serial()}"
        val r1 = "$challenge;$iw;$iwK1"
        return mapOf("R0" to sha256Hex(r0), "R1" to sha256Hex(r1), "R2" to sha256Hex(r2))
    }

    private suspend fun request(params: Map<String, String>, setup: Boolean): Map<String, String> {
        val b = "$HOST/iwws/MAC".toHttpUrl().newBuilder()
        for ((k, v) in params) b.addQueryParameter(k, v)
        val req = Request.Builder().url(b.build())
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 8.0.0; Android SDK built for x86_64 Build/OSR1.180418.004)")
            .get().build()
        val raw = Http.text(req)
        val root = if (setup) "ActionSetup" else "ActionFinalize"
        return parseXml(raw, root) ?: throw OtpException("Bad response from OTP server")
    }

    /** Step 1 of activation, or step 1 of producing an OTP. */
    suspend fun activationStart(): Boolean {
        val p = mutableMapOf(
            "action" to "ActionSetup", "mode" to mode, "id" to iwid, "lastsync" to iwTsync,
            "version" to "Generator-1.0/0.2.11", "macid" to macid
        )
        if (mode == OTP_MODE) p["sid"] = iwsecid else if (mode == ACTIVATE_MODE) p["code"] = smsCode
        val xml = request(p, setup = true)
        if (xml["err"] == "OK") {
            if (mode == ACTIVATE_MODE) {
                kfact = xml["Kfact"] ?: throw OtpException("Missing Kfact")
                kiw = decodeOaep(xml["Kiw"] ?: throw OtpException("Missing Kiw"), kfact!!)
            } else if (mode == OTP_MODE) {
                challenge = xml["challenge"] ?: ""
            }
            return true
        }
        throw OtpException("OTP setup refused: ${xml["err"]}")
    }

    /** Returns OK, OTP_TWICE or throws. */
    suspend fun activationFinalize(): Int {
        val r = getR()
        val p = mutableMapOf(
            "action" to "ActionFinalize", "mode" to mode, "id" to iwid, "lastsync" to iwTsync,
            "version" to "Generator-1.0/0.2.11", "lang" to "fr", "ack" to "", "macid" to macid
        )
        if (mode == OTP_MODE) {
            p["keytype"] = "0"; p["sid"] = iwsecid
        } else if (mode == ACTIVATE_MODE) {
            val modulus = kiw ?: throw OtpException("Not initialised")
            val kmaCrypt = oaepEncrypt(hexToBytes(kma(codepin)), modulus).toHex()
            val pinCrypt = oaepEncrypt(codepin.toByteArray(Charsets.UTF_8), modulus).toHex()
            p["serial"] = serial(); p["code"] = smsCode; p["Kma"] = kmaCrypt; p["pin"] = pinCrypt
            p["name"] = "Android SDK built for x86_64 / UNKNOWN"
        }
        p.putAll(r)
        val xml = request(p, setup = false)
        if (xml["err"] != "OK") throw OtpException("OTP error: ${xml["err"]}")
        synchro(xml, kma(codepin))

        if (mode == OTP_MODE) {
            defi = xml["defi"] ?: throw OtpException("Missing defi in OTP response")
            return if (xml.containsKey("J")) OTP_TWICE else OK
        }

        val msN = xml["ms_n"]
        if (msN == null || xml["ms_key"] == null) return OK
        if ((msN.toIntOrNull() ?: 0) > 1) throw OtpException("Unsupported ms_n=$msN")

        challenge = xml["challenge"] ?: ""
        action = "synchro"
        val tempModulus = decodeOaep(xml["ms_key"]!!, kfact!!)
        val random = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val kpubEncode = oaepEncrypt(random, tempModulus)
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hexToBytes(kma(codepin)), "AES"))
        iwsecval = aes.doFinal(random).toHex()
        iwsecid = xml["s_id"] ?: ""

        val p2 = mutableMapOf(
            "action" to "ActionFinalize", "mode" to MS_MODE,
            "ms_id0" to (xml["ms_id"] ?: ""), "ms_val0" to kpubEncode.toHex(), "macid" to macid,
            "id" to iwid, "lastsync" to iwTsync, "ms_n" to "1"
        )
        p2.putAll(getR())
        val xml2 = request(p2, setup = false)
        synchro(xml2, kma(codepin))
        return OK
    }

    private fun synchro(x: Map<String, String>, key: String) {
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(key), "AES"))
        x["id"]?.takeIf { it.isNotEmpty() }?.let { iwid = it }
        x["K0"]?.takeIf { it.isNotEmpty() }?.let { iwK0 = aes.doFinal(hexToBytes(it)).toHex() }
        x["K1"]?.takeIf { it.isNotEmpty() }?.let { iwK1 = aes.doFinal(hexToBytes(it)).toHex() }
        x["dK1"]?.takeIf { it.isNotEmpty() }?.let { iwK1 = sha256Hex("$iwK1;$it").substring(0, 32) }
        if (!x["s_n"].isNullOrEmpty()) {
            iwTsync = x["Tsync"] ?: iwTsync
        }
    }

    private fun computeOtp(): String {
        val res = MessageDigest.getInstance("SHA-256").digest("$iwK1:$defi:$iwsecval".toByteArray(Charsets.UTF_8))
        val a = BigInteger(1, res.copyOfRange(0, 4)).toLong() and 0xfffffff
        val b = BigInteger(1, res.copyOfRange(4, 8)).toLong() and 1023
        var n = a * 1024 + b
        if (n == 0L) return "a"
        val sb = StringBuilder()
        while (n > 0) {
            sb.append(BASE36[(n % 36).toInt()])
            n /= 36
        }
        return sb.toString()
    }

    /** Activate with SMS code and PIN. Throws on failure. */
    suspend fun activate(sms: String, pin: String) {
        smsCode = sms; codepin = pin; mode = ACTIVATE_MODE
        if (activationStart()) activationFinalize()
    }

    /** Produce a one-time password. Limited by Stellantis to roughly 6 per day. */
    suspend fun nextCode(): String {
        mode = OTP_MODE
        if (!activationStart()) throw OtpException("Can't start OTP")
        var res = activationFinalize()
        if (res == OTP_TWICE) {
            mode = OTP_MODE
            activationStart()
            res = activationFinalize()
            if (res != OK) throw OtpException("Can't get OTP code")
        }
        return computeOtp()
    }

    fun toJson(): String = JSONObject().apply {
        put("deviceId", deviceId); put("iwalea", iwalea); put("kiw", kiw ?: ""); put("kfact", kfact ?: "")
        put("codepin", codepin); put("action", action); put("defi", defi)
        put("iwid", iwid); put("iwTsync", iwTsync); put("iwK0", iwK0); put("iwK1", iwK1)
        put("iwsecid", iwsecid); put("iwsecval", iwsecval)
    }.toString()

    companion object {
        const val OK = 0
        const val OTP_TWICE = 10
        const val ACTIVATE_MODE = "activate"
        const val OTP_MODE = "otp"
        const val MS_MODE = "ms"
        const val MACID = "bb8e981582b0f31353108fb020bead1c"
        private const val HOST = "https://otp.mpsa.com"
        private val EXPONENT = BigInteger("11", 16)
        private val BASE36 = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

        fun fromJson(json: String): Otp {
            val j = JSONObject(json)
            return Otp(MACID, j.getString("deviceId")).apply {
                iwalea = j.getString("iwalea")
                kiw = j.optString("kiw").ifEmpty { null }
                kfact = j.optString("kfact").ifEmpty { null }
                codepin = j.optString("codepin")
                action = j.optString("action")
                defi = j.optString("defi", "0")
                iwid = j.optString("iwid")
                iwTsync = j.optString("iwTsync", "0")
                iwK0 = j.optString("iwK0")
                iwK1 = j.optString("iwK1")
                iwsecid = j.optString("iwsecid")
                iwsecval = j.optString("iwsecval")
            }
        }

        // ---------- crypto helpers ----------

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).toHex()

        fun randomHex(bytes: Int): String = ByteArray(bytes).also { SecureRandom().nextBytes(it) }.toHex()

        fun hexToBytes(hex: String): ByteArray {
            val clean = if (hex.length % 2 == 1) "0$hex" else hex
            return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }

        private fun mgf1(seed: ByteArray, length: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            var counter = 0
            while (out.size() < length) {
                val md = MessageDigest.getInstance("SHA-256")
                md.update(seed)
                md.update(byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte()))
                out.write(md.digest())
                counter++
            }
            return out.toByteArray().copyOfRange(0, length)
        }

        private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { i -> (a[i].toInt() xor b[i].toInt()).toByte() }

        private fun i2osp(x: BigInteger, len: Int): ByteArray {
            val raw = x.toByteArray()
            return when {
                raw.size == len -> raw
                raw.size > len -> raw.copyOfRange(raw.size - len, raw.size)
                else -> ByteArray(len - raw.size) + raw
            }
        }

        /** RSA-OAEP (SHA-256, MGF1-SHA-256, empty label) encryption with public exponent 0x11. */
        fun oaepEncrypt(message: ByteArray, modulusHex: String): ByteArray {
            val n = BigInteger(modulusHex, 16)
            val k = (n.bitLength() + 7) / 8
            val hLen = 32
            val lHash = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            val ps = ByteArray(k - message.size - 2 * hLen - 2)
            val db = lHash + ps + byteArrayOf(1) + message
            val seed = ByteArray(hLen).also { SecureRandom().nextBytes(it) }
            val maskedDb = xor(db, mgf1(seed, k - hLen - 1))
            val maskedSeed = xor(seed, mgf1(maskedDb, hLen))
            val em = byteArrayOf(0) + maskedSeed + maskedDb
            val c = BigInteger(1, em).modPow(EXPONENT, n)
            return i2osp(c, k)
        }

        /** The vendor's "decrypt with the public key" OAEP unwrap, block by block (128-byte blocks). */
        fun decodeOaep(encHex: String, modulusHex: String): String {
            val n = BigInteger(modulusHex, 16)
            val k = (n.bitLength() + 7) / 8
            val hLen = 32
            val lHash = MessageDigest.getInstance("SHA-256").digest(ByteArray(0))
            val enc = hexToBytes(encHex)
            val sb = StringBuilder()
            var off = 0
            while (off < enc.size) {
                val end = minOf(off + 128, enc.size)
                val block = enc.copyOfRange(off, end)
                val m = BigInteger(1, block).modPow(EXPONENT, n)
                val em = i2osp(m, k)
                val maskedSeed = em.copyOfRange(1, hLen + 1)
                val maskedDb = em.copyOfRange(hLen + 1, em.size)
                val seed = xor(maskedSeed, mgf1(maskedDb, hLen))
                val db = xor(maskedDb, mgf1(seed, k - hLen - 1))
                if (!db.copyOfRange(0, hLen).contentEquals(lHash)) throw OtpException("Incorrect decryption")
                var onePos = -1
                for (i in hLen until db.size) {
                    if (db[i].toInt() == 1) { onePos = i; break }
                    if (db[i].toInt() != 0) break
                }
                if (onePos < 0) throw OtpException("Incorrect decryption")
                sb.append(db.copyOfRange(onePos + 1, db.size).toHex())
                off = end
            }
            return sb.toString()
        }

        /** Flattens the children of the given root element into tag -> text (first occurrence wins). */
        fun parseXml(raw: String, rootTag: String): Map<String, String>? {
            val start = raw.indexOf("?>")
            val xmlText = if (start >= 0) raw.substring(start + 2) else raw
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xmlText))
            val out = HashMap<String, String>()
            var depth = 0
            var inRoot = false
            var currentTag: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 1) inRoot = parser.name == rootTag
                        else if (depth == 2 && inRoot) {
                            currentTag = parser.name
                            if (!out.containsKey(parser.name)) out[parser.name] = ""
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val tag = currentTag
                        if (inRoot && depth == 2 && tag != null) {
                            val t = parser.text?.trim().orEmpty()
                            if (t.isNotEmpty() && out[tag].isNullOrEmpty()) out[tag] = t
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2) currentTag = null
                        depth--
                    }
                }
                event = parser.next()
            }
            return if (out.isEmpty()) null else out
        }
    }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append(String.format("%02x", b.toInt() and 0xff))
    return sb.toString()
}
