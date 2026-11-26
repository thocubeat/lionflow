// The one-time codes behind remote control: the Android app's own generator (Otp.kt), played through a whole
// activation and several codes against a stand-in for the carmaker's code server, with fixed "random" bytes.
// Everything it sent and worked out is written down, so the iPhone app can be made to do exactly the same.
// Writes its own file only. See build.sh.
import no.lionflow.app.api.Otp
import no.lionflow.app.api.toHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object OracleOtp {
    private var state = 20261126
    /** Fixed "random" bytes, the same on both sides (a plain linear generator; nothing here is secret). */
    fun fill(b: ByteArray) {
        for (i in b.indices) {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            b[i] = (state ushr 16).toByte()
        }
    }
    fun reset(seed: Int) { state = seed }

    val transcript = JSONArray()
    var handler: (Map<String, String>, Boolean) -> Map<String, String> = { _, _ -> emptyMap() }
    fun server(params: Map<String, String>, setup: Boolean): Map<String, String> {
        val answer = handler(params, setup)
        val p = JSONArray(); params.forEach { (k, v) -> p.put(JSONArray().put(k).put(v)) }
        val a = JSONArray(); answer.forEach { (k, v) -> a.put(JSONArray().put(k).put(v)) }
        transcript.put(JSONObject().put("setup", setup).put("params", p).put("answer", a))
        return answer
    }
}

private val E = BigInteger("11", 16)

/** An RSA key with public exponent 17, from fixed random numbers. */
private class Key(seed: Long) {
    val n: BigInteger
    val d: BigInteger
    init {
        val rnd = java.util.Random(seed)
        var p: BigInteger; var q: BigInteger; var phi: BigInteger
        while (true) {
            p = BigInteger.probablePrime(512, rnd); q = BigInteger.probablePrime(512, rnd)
            phi = (p - BigInteger.ONE) * (q - BigInteger.ONE)
            if (p != q && phi.gcd(E) == BigInteger.ONE && (p * q).bitLength() == 1024) break
        }
        n = p * q; d = E.modInverse(phi)
    }
    val hex: String get() = unsigned(n).toHex()
}

private fun unsigned(x: BigInteger): ByteArray = x.toByteArray().let { if (it.size > 1 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it }
private fun sized(x: BigInteger, len: Int): ByteArray = unsigned(x).let { ByteArray(len - it.size) + it }
private fun sha(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
private fun mgf1(seed: ByteArray, length: Int): ByteArray {
    var out = ByteArray(0); var counter = 0
    while (out.size < length) { out += sha(seed + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())); counter++ }
    return out.copyOfRange(0, length)
}
private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

/** What the code server does with its private key: OAEP blocks the app opens with the public one (Otp.decodeOaep). */
private fun seal(data: ByteArray, key: Key, rnd: java.util.Random): String {
    val k = 128; val h = 32
    val out = StringBuilder()
    var off = 0
    while (off < data.size) {
        val chunk = data.copyOfRange(off, minOf(off + k - 2 * h - 2, data.size))
        val db = sha(ByteArray(0)) + ByteArray(k - chunk.size - 2 * h - 2) + byteArrayOf(1) + chunk
        val seed = ByteArray(h).also { rnd.nextBytes(it) }
        val maskedDb = xor(db, mgf1(seed, k - h - 1))
        val maskedSeed = xor(seed, mgf1(maskedDb, h))
        val em = BigInteger(1, byteArrayOf(0) + maskedSeed + maskedDb)
        out.append(sized(em.modPow(key.d, key.n), k).toHex())
        off += chunk.size
    }
    return out.toString()
}

/** Opens what the app sealed with a public key (Otp.oaepEncrypt), to show the stand-in server could read it. */
private fun open(encHex: String, key: Key): String {
    val k = 128; val h = 32
    val em = sized(BigInteger(encHex, 16).modPow(key.d, key.n), k)
    val maskedSeed = em.copyOfRange(1, h + 1); val maskedDb = em.copyOfRange(h + 1, k)
    val seed = xor(maskedSeed, mgf1(maskedDb, h))
    val db = xor(maskedDb, mgf1(seed, k - h - 1))
    check(db.copyOfRange(0, h).contentEquals(sha(ByteArray(0)))) { "not OAEP" }
    var i = h; while (db[i].toInt() == 0) i++
    check(db[i].toInt() == 1)
    return db.copyOfRange(i + 1, db.size).toHex()
}

private fun aes(keyHex: String, data: ByteArray, mode: Int): ByteArray =
    Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(Otp.hexToBytes(keyHex), "AES")) }.doFinal(data)

fun main(args: Array<String>) {
    val file = File(args[0], "otp_expected.json")
    check(!file.exists()) { "otp_expected.json is already there: this program only writes a new file" }
    val o = JSONObject()
    val pin = "4321"
    val sms = "K7QW2M"
    val factory = Key(101); val device = Key(202); val temp = Key(303)
    val serverRnd = java.util.Random(404)
    fun bytes(n: Int) = ByteArray(n).also { serverRnd.nextBytes(it) }
    o.put("pin", pin).put("sms", sms).put("deviceId", "0123456789abcdef").put("seed", 20261126)
    o.put("keys", JSONObject().put("factoryN", factory.hex).put("factoryD", unsigned(factory.d).toHex()).put("deviceN", device.hex).put("deviceD", unsigned(device.d).toHex())
        .put("tempN", temp.hex).put("tempD", unsigned(temp.d).toHex()))

    // ---- The stand-in code server
    var otpRound = 0
    val read = JSONArray()
    OracleOtp.handler = { p, setup ->
        val mode = p["mode"]
        val kma = Otp.sha256Hex("$pin;${p["serial"] ?: ""}").substring(0, 32)
        when {
            setup && mode == "activate" && p["code"] != sms -> linkedMapOf("err" to "NOK:Bad activation code")
            setup && mode == "activate" -> linkedMapOf("err" to "OK", "Kfact" to factory.hex, "Kiw" to seal(unsigned(device.n), factory, serverRnd))
            !setup && mode == "activate" -> {
                // What the app sealed for the server can be opened with the server's key.
                read.put(JSONObject().put("Kma", open(p["Kma"]!!, device)).put("pin", String(Otp.hexToBytes(open(p["pin"]!!, device)), Charsets.UTF_8)))
                linkedMapOf("err" to "OK", "id" to "iw-7f3a", "K0" to aes(kma, bytes(16), Cipher.ENCRYPT_MODE).toHex(), "K1" to aes(kma, bytes(16), Cipher.ENCRYPT_MODE).toHex(),
                    "s_n" to "1", "Tsync" to "1795000042", "ms_n" to "1", "ms_key" to seal(unsigned(temp.n), factory, serverRnd), "ms_id" to "ms-01", "s_id" to "sid-9c", "challenge" to "5e1f00d5")
            }
            !setup && mode == "ms" -> {
                read.put(JSONObject().put("ms_val0", open(p["ms_val0"]!!, temp)))
                linkedMapOf("err" to "OK", "dK1" to "a1b2c3", "s_n" to "1", "Tsync" to "1795000043", "empty" to "")
            }
            setup && mode == "otp" -> linkedMapOf("err" to "OK", "challenge" to "ch-" + (++otpRound))
            !setup && mode == "otp" -> when (otpRound) {
                1 -> linkedMapOf("err" to "OK", "defi" to "d-first", "J" to "1", "dK1" to "0f0f")
                2 -> linkedMapOf("err" to "OK", "defi" to "d-second")
                3 -> linkedMapOf("err" to "OK", "defi" to "d-third", "K1" to aes(Otp.sha256Hex("$pin;${p["serial"] ?: ""}").substring(0, 32), bytes(16), Cipher.ENCRYPT_MODE).toHex(), "s_n" to "", "Tsync" to "ignored")
                4 -> linkedMapOf("err" to "OK", "defi" to "1795000099", "s_n" to "2", "Tsync" to "1795000100", "id" to "iw-new")
                else -> linkedMapOf("err" to "NOK:Too many codes today")
            }
            else -> linkedMapOf("err" to "NOK:Unknown")
        }
    }

    // ---- A whole activation, then codes, as the app does it
    val steps = JSONArray()
    fun step(name: String, block: () -> Any?) {
        val before = OracleOtp.transcript.length()
        val result = try { block()?.toString() ?: "null" } catch (e: Exception) { "error: " + e.message }
        val calls = JSONArray(); for (i in before until OracleOtp.transcript.length()) calls.put(OracleOtp.transcript.get(i))
        steps.put(JSONObject().put("name", name).put("result", result).put("calls", calls))
    }
    OracleOtp.reset(20261126)
    val wrong = Otp(Otp.MACID, "0123456789abcdef")
    step("activate with a wrong SMS code") { wrong.activate("WRONG1", pin) }
    val otp = Otp(Otp.MACID, "0123456789abcdef")
    step("activate") { otp.activate(sms, pin) }
    o.put("afterActivate", JSONObject(otp.toJson()))
    o.put("serial", otp.serial()).put("kma", otp.kma(pin))
    step("first code (the server asks twice)") { otp.nextCode() }
    o.put("afterFirstCode", JSONObject(otp.toJson()))
    // Saved and read again in between, as the app does.
    val again = Otp.fromJson(otp.toJson())
    step("second code") { again.nextCode() }
    o.put("afterSecondCode", JSONObject(again.toJson()))
    step("third code") { again.nextCode() }
    o.put("afterThirdCode", JSONObject(again.toJson()))
    step("fourth code is refused") { again.nextCode() }
    o.put("steps", steps).put("serverCouldRead", read)

    // ---- Single pieces
    o.put("sha256", JSONArray(listOf("", "abc", "4321;0123456789abcdef/_/00", "æøå 🚗").map { JSONArray().put(it).put(Otp.sha256Hex(it)) }))
    o.put("hex", JSONArray(listOf("", "0", "abc", "00ff10", "ABCDEF", "123").map { JSONArray().put(it).put(Otp.hexToBytes(it).toHex()) }))
    val sealed = JSONArray()
    for (data in listOf(ByteArray(0), "a".toByteArray(), bytes(16), bytes(62), bytes(63), bytes(128), unsigned(device.n), bytes(200))) {
        val enc = seal(data, factory, serverRnd)
        sealed.put(JSONObject().put("data", data.toHex()).put("sealed", enc).put("opened", Otp.decodeOaep(enc, factory.hex)))
    }
    o.put("decodeOaep", sealed)
    o.put("decodeOaepWrongKey", try { Otp.decodeOaep(seal(bytes(16), factory, serverRnd), device.hex) } catch (e: Exception) { "error: " + e.message })
    val enc = JSONArray()
    OracleOtp.reset(7)
    for (data in listOf(ByteArray(0), "4321".toByteArray(), bytes(16), bytes(62))) {
        val c = Otp.oaepEncrypt(data, device.hex)
        enc.put(JSONObject().put("data", data.toHex()).put("encrypted", c.toHex()).put("opened", open(c.toHex(), device)))
    }
    o.put("oaepEncrypt", JSONObject().put("seed", 7).put("cases", enc))
    val codes = JSONArray()
    for ((k1, defi, sec) in listOf(Triple("00112233445566778899aabbccddeeff", "0", ""), Triple("", "", ""), Triple("ffeeddccbbaa99887766554433221100", "d-1", "0123456789abcdef0123456789abcdef"),
        Triple("a", "1795000099", "b"), Triple("00000000000000000000000000000000", "x", "y"))) {
        val t = Otp(Otp.MACID, "dev"); t.iwK1 = k1; t.defi = defi; t.iwsecval = sec
        codes.put(JSONArray().put(k1).put(defi).put(sec).put(t.computeOtp()))
    }
    o.put("computeOtp", codes)
    val r = Otp(Otp.MACID, "dev-1").apply { iwalea = "00aa"; iwK0 = "k0"; iwK1 = "k1"; challenge = "ch"; codepin = "9999" }
    o.put("getR", JSONObject(r.getR() as Map<*, *>))
    r.action = "synchro"
    o.put("getRSynchro", JSONObject(r.getR() as Map<*, *>))
    val fresh = Otp.fromJson("{\"deviceId\":\"d\",\"iwalea\":\"aa\"}")
    o.put("fromSparseJson", JSONObject(fresh.toJson()))
    file.writeText(o.toString(1))
    println("wrote otp_expected.json: " + steps.length() + " steps, " + OracleOtp.transcript.length() + " calls to the stand-in server")
}
