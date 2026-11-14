package no.lionflow.app.api

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.data.SecureStore
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext

sealed class CommandResult {
    data object Success : CommandResult()
    /** The car has received the command; the final answer arrives later via [Tracker.onFinal]. */
    data object Delivered : CommandResult()
    data class Failed(val reason: String) : CommandResult()
}

/** Follows one command: progress lines while waiting, and the car's final answer if it comes after we stopped waiting. */
class Tracker(val onProgress: (String) -> Unit = {}, val onFinal: (CommandResult) -> Unit = {})

/**
 * Remote commands (lock, climate, charging, horn, lights) go over MQTT, exactly like the
 * MyPeugeot app. The MQTT password is a short-lived token obtained with a one-time password
 * from [Otp]; a refresh token (valid ~3 days) avoids asking for new OTPs too often.
 */
class RemoteControl(private val api: StellantisApi, private val secure: SecureStore) {

    private val lock = Mutex()
    private var client: MqttClient? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()
    private val progress = ConcurrentHashMap<String, (String) -> Unit>()
    private val wakeWaiters = ConcurrentHashMap<String, () -> Unit>()
    private val finals = ConcurrentHashMap<String, (CommandResult) -> Unit>()
    private val services = ConcurrentHashMap<String, String>()
    private val reasons = ConcurrentHashMap<String, String>()
    private val peugeotReasons = ConcurrentHashMap<String, String>()
    private val wakeIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var wakeDone: CompletableDeferred<Unit>? = null

    /** Last answers from the car, newest first — shown in Settings › Command log for troubleshooting. */
    val log = java.util.concurrent.ConcurrentLinkedDeque<String>()
    fun addLog(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        log.addFirst("$t  $line")
        while (log.size > 40) log.pollLast()
    }
    @Volatile private var lastWake = 0L

    val isSetUp: Boolean get() = secure.get(K_OTP) != null

    /** Activate remote control with the SMS code and the MyPeugeot security code. */
    suspend fun activate(smsCode: String, pin: String) {
        val deviceId = (api.currentAccessToken() ?: Otp.randomHex(8)).take(16)
        val otp = Otp(Otp.MACID, deviceId)
        otp.activate(smsCode.trim(), pin.trim())
        secure.put(K_OTP, otp.toJson())
        secure.put(K_PIN, pin.trim())
        // Trade a first OTP for an MQTT token so the setup is verified end-to-end.
        passwordGrant(otp)
    }

    /** Local check of the security code before a remote action (fingerprint can replace it). */
    fun checkPin(pin: String): Boolean = secure.get(K_PIN)?.let { it == pin } ?: false
    val hasPin: Boolean get() = secure.get(K_PIN) != null

    private suspend fun passwordGrant(otp: Otp) {
        val code = otp.nextCode()
        secure.put(K_OTP, otp.toJson())
        val j = api.mqttToken(JSONObject().put("grant_type", "password").put("password", code))
        saveToken(j, withRefresh = true)
    }

    private fun saveToken(j: JSONObject, withRefresh: Boolean) {
        val access = j.str("access_token") ?: throw ApiException(0, "No remote-control token in response")
        secure.put(K_MQTT_ACCESS, access)
        val exp = System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000
        secure.put(K_MQTT_EXP, exp.toString())
        val refresh = j.str("refresh_token")
        if (refresh != null) {
            secure.put(K_MQTT_REFRESH, refresh)
            if (withRefresh || secure.get(K_MQTT_REFRESH_EXP) == null) {
                secure.put(K_MQTT_REFRESH_EXP, (System.currentTimeMillis() + 3L * 24 * 3600 * 1000).toString())
            }
        }
    }

    private suspend fun ensureToken(force: Boolean, marginMs: Long = 60_000) {
        val exp = secure.get(K_MQTT_EXP)?.toLongOrNull() ?: 0L
        if (!force && secure.get(K_MQTT_ACCESS) != null && System.currentTimeMillis() < exp - marginMs) return
        val refreshExp = secure.get(K_MQTT_REFRESH_EXP)?.toLongOrNull() ?: 0L
        val refresh = secure.get(K_MQTT_REFRESH)
        if (refresh != null && System.currentTimeMillis() < refreshExp) {
            try {
                saveToken(api.mqttToken(JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh)), withRefresh = false)
                return
            } catch (_: ApiException) { /* fall through to a fresh OTP */ }
        }
        val otpJson = secure.get(K_OTP) ?: throw ApiException(0, "Remote control isn't set up yet")
        val otp = Otp.fromJson(otpJson)
        try {
            passwordGrant(otp)
        } catch (e: Exception) {
            if (refresh != null) {
                saveToken(api.mqttToken(JSONObject().put("grant_type", "refresh_token").put("refresh_token", refresh)), withRefresh = false)
            } else throw e
        }
    }

    private suspend fun connect(): MqttClient = withContext(Dispatchers.IO) {
        client?.let { if (it.isConnected) return@withContext it }
        try { client?.close() } catch (_: Exception) {}
        // Peugeot's broker only accepts an empty client id (the server assigns one), like the MyPeugeot app.
        val c = MqttClient("ssl://mwa.mpsa.com:8885", "", MemoryPersistence())
        val opts = MqttConnectOptions().apply {
            userName = "IMA_OAUTH_ACCESS_TOKEN"
            password = (secure.get(K_MQTT_ACCESS) ?: "").toCharArray()
            isCleanSession = true
            keepAliveInterval = 120
            connectionTimeout = 20
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            socketFactory = SSLContext.getDefault().socketFactory
        }
        c.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) {}
            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val body = message?.payload?.toString(Charsets.UTF_8) ?: return
                val data = try { JSONObject(body) } catch (_: Exception) { return }
                val id = data.optString("correlation_id")
                val code = data.str("return_code") ?: data.str("process_code") ?: return
                val svc = services[id] ?: if (id in wakeIds) "/VehCharge/state (wake)" else "?"
                addLog("$svc → $code" + (data.str("reason")?.let { " · $it" } ?: "") + (data.optJSONObject("resp_data")?.let { " · resp_data=$it" } ?: ""))
                // A pre-wake: the car is reachable once it's forwarded (903) or finished.
                if (id in wakeIds) {
                    if (code == "903" || code !in IN_PROGRESS) wakeDone?.complete(Unit)
                    if (code !in IN_PROGRESS) {
                        wakeIds.remove(id)
                        wakeWaiters.remove(id)?.let { cb -> if (code == "0" || code == "1") cb() }
                    }
                    return
                }
                // 900 = accepted, 901 = car asleep (being woken), 902 = checking state, 903 = delivered to the car.
                if (code == "900" || code == "901" || code == "902") {
                    progress[id]?.invoke(when (code) { "900" -> "Sent to Peugeot…"; "901" -> "Waking the car…"; else -> "Checking the car…" })
                    return
                }
                if (code == "903") {
                    progress[id]?.invoke("Car received it")
                    pending[id]?.complete("903")
                    return
                }
                val reason = data.optString("reason")
                val finalCode = if (code == "400" && reason.contains("no.matching.service.key")) "not_compatible" else code
                if (finalCode != "0" && finalCode != "1") {
                    describeRespData(services[id], data.optJSONObject("resp_data"))?.let { reasons[id] = it }
                    reason.trim('[', ']').ifBlank { null }?.let { peugeotReasons[id] = it }
                }
                val w = pending[id]
                if (w != null && !w.isCompleted) w.complete(finalCode)
                else finals.remove(id)?.invoke(mapCode(finalCode, reasons.remove(id), peugeotReasons.remove(id)))
            }
        })
        c.connect(opts)
        c.subscribe("psa/RemoteServices/to/cid/${api.customerId}/#", 0)
        client = c
        c
    }

    private fun mapCode(code: String, reason: String? = null, peugeotReason: String? = null): CommandResult {
        if (code == "0" || code == "1") return CommandResult.Success
        val meaning = when (code) {
            "400" -> "The car refused the command"
            "not_compatible" -> "Your car or subscription doesn't support this command"
            "502" -> "The car is still busy with another request — try again in a moment"
            "300" -> "The car didn't answer in time — it may have poor coverage"
            "500" -> "Peugeot's remote-control server reported an internal error"
            "422" -> "Privacy mode is on in the car, so remote commands are blocked"
            "506" -> "The car's mobile provider isn't supported for remote commands"
            "507" -> "The car has no GPS coverage right now"
            "113" -> "Peugeot couldn't match the car (VIN)"
            else -> "Peugeot answered with an unknown result"
        }
        val codeLabel = if (code == "not_compatible") "400" else code
        val extra = listOfNotNull(peugeotReason?.ifBlank { null }?.let { "Peugeot says: $it" }).joinToString()
        val text = (reason ?: meaning) + " (code $codeLabel" + (if (extra.isNotEmpty()) " · $extra" else "") + ")"
        return CommandResult.Failed(text)
    }

    /** The car's own reason for refusing, from resp_data (same enums as the MyPeugeot app). */
    private fun describeRespData(service: String?, rd: JSONObject?): String? {
        if (service == null || rd == null) return null
        fun int(k: String) = if (rd.has(k)) rd.optInt(k, -1) else null
        return when {
            service == "/Doors" -> when (int("error")) {
                0 -> "The engine is running"
                else -> when (int("lock_resp_state")) {
                    2 -> "A door is open"
                    3 -> "The car refused the request"
                    5 -> "The key is inside the car"
                    4 -> "The car refused without giving a reason — check that doors, boot, bonnet and windows are fully closed and the key isn't in or right next to the car"
                    else -> null
                }
            }
            service == "/Horn" || service == "/Lights" -> when (int("error")) {
                0 -> "The engine is running"
                1, 2 -> "Just done — wait a moment before sending again"
                else -> null
            }
            service == "/ThermalPrecond" -> when (int("error")) {
                0 -> "The engine is running"
                1 -> "Pre-heating is already running"
                2 -> "Communication problem in the car"
                3 -> "Can't stop while the engine is running"
                4 -> "Pre-heating wasn't running"
                else -> null
            }
            service.startsWith("/VehCharge") -> when (int("error")) {
                0 -> "The charging system is in standby"
                1 -> "It's already charging"
                2 -> "Charging communication problem in the car"
                3 -> "The engine is running"
                else -> null
            }
            else -> null
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    private suspend fun send(service: String, params: JSONObject, tracker: Tracker, retried: Boolean = false): CommandResult = lock.withLock {
        try {
            if (client?.isConnected != true || retried) tracker.onProgress("Connecting…")
            ensureToken(force = retried)
            if (retried) disconnectQuietly()
            val c = connect()
            // If a wake-up is on its way, let it reach the car first — sending now would be refused as a duplicate (502).
            wakeDone?.let { d ->
                if (!d.isCompleted) {
                    tracker.onProgress("Waking the car…")
                    withTimeoutOrNull(45_000) { d.await() }
                }
            }
            var attempt = 0
            var outcome: CommandResult? = null
            while (true) {
                attempt++
                val waiter = CompletableDeferred<String>()
                val correlation = publish(c, service, params) { id ->
                    pending[id] = waiter
                    progress[id] = tracker.onProgress
                    finals[id] = tracker.onFinal
                    services[id] = service
                }
                lastWake = System.currentTimeMillis() // any command wakes the car too
                tracker.onProgress("Sent — waiting for the car…")
                val code = withTimeoutOrNull(90_000) { waiter.await() }
                pending.remove(correlation)
                progress.remove(correlation)
                if (code == "903") {
                    // Delivered: stop making the user wait. The final answer (if any) goes to tracker.onFinal.
                    scope.launch { delay(3 * 60_000); finals.remove(correlation); services.remove(correlation); reasons.remove(correlation) }
                    outcome = CommandResult.Delivered
                    break
                }
                finals.remove(correlation)
                services.remove(correlation)
                val reason = reasons.remove(correlation)
                val pReason = peugeotReasons.remove(correlation)
                if (code == "300" && attempt == 1) {
                    // No answer in time — often the car was waking up or briefly out of coverage. That attempt woke it; try once more.
                    tracker.onProgress("Car didn't answer — trying again…")
                    delay(5_000)
                    continue
                }
                if (code == "502" && attempt == 1) {
                    // The car was still busy with an earlier request: wait a little and send once more.
                    tracker.onProgress("Car was busy — trying again…")
                    delay(6_000)
                    continue
                }
                outcome = when (code) {
                    null -> CommandResult.Failed("No answer from the car yet — it may be asleep or out of coverage")
                    "400" -> if (!retried) null else mapCode(code, reason, pReason)
                    else -> mapCode(code, reason, pReason)
                }
                break
            }
            outcome
        } catch (e: Exception) {
            val msg = if (e is org.eclipse.paho.client.mqttv3.MqttException)
                "Couldn't connect to Peugeot's remote-control server: ${e.message} (MQTT code ${e.reasonCode})"
            else Http.describe(e)
            addLog("error: $msg")
            CommandResult.Failed(msg)
        }
    }.let { r -> r ?: send(service, params, tracker, retried = true) }

    /** Builds and publishes one request; [beforeSend] registers listeners before the message goes out. */
    private suspend fun publish(c: MqttClient, service: String, params: JSONObject, beforeSend: (String) -> Unit): String {
        val customer = api.customerId ?: throw ApiException(0, "Missing customer id")
        val vin = api.cachedVehicles().firstOrNull()?.vin ?: throw ApiException(0, "No vehicle")
        val now = Date()
        val stamp = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val reqDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
        val correlation = UUID.randomUUID().toString().replace("-", "") + stamp
        val payload = JSONObject()
            .put("access_token", secure.get(K_MQTT_ACCESS))
            .put("customer_id", customer)
            .put("correlation_id", correlation)
            .put("req_date", reqDate)
            .put("vin", vin)
            .put("req_parameters", params)
        beforeSend(correlation)
        addLog("$service ← sent ${params}")
        withContext(Dispatchers.IO) {
            c.publish("psa/RemoteServices/from/cid/$customer$service", MqttMessage(payload.toString().toByteArray()).apply { qos = 0 })
        }
        return correlation
    }

    /**
     * Sends a wake-up to the car without waiting for it, so it is already awake when a
     * real command arrives. Skipped if a wake-up (or any command) went out within [minIntervalMs].
     * [onAwake] runs when the car confirms it is awake.
     */
    suspend fun preWake(minIntervalMs: Long, onAwake: () -> Unit = {}): Boolean {
        if (!isSetUp) return false
        val now = System.currentTimeMillis()
        if (now - lastWake < minIntervalMs) return false
        lastWake = now
        return try {
            lock.withLock {
                ensureToken(force = false)
                val c = connect()
                wakeDone = CompletableDeferred()
                publish(c, "/VehCharge/state", JSONObject().put("action", "state")) { id ->
                    wakeIds.add(id)
                    wakeWaiters[id] = onAwake
                }
                // Never let a lost answer hold up later commands.
                val d = wakeDone
                scope.launch { delay(45_000); d?.complete(Unit) }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets everything ready before the user presses a button: a fresh MQTT token
     * (refreshed if it expires within 10 minutes) and an open connection to Peugeot.
     */
    suspend fun warmUp() {
        if (!isSetUp) return
        try {
            lock.withLock {
                ensureToken(force = false, marginMs = 10 * 60_000)
                connect()
            }
        } catch (_: Exception) { }
    }

    fun disconnect() = disconnectQuietly()

    private fun disconnectQuietly() {
        try { client?.disconnect(500) } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    // ---------------- commands ----------------

    suspend fun lock(lock: Boolean, t: Tracker = Tracker()) = send("/Doors", JSONObject().put("action", if (lock) "lock" else "unlock"), t)
    suspend fun horn(t: Tracker = Tracker()) = send("/Horn", JSONObject().put("nb_horn", "2").put("action", "activate"), t)
    suspend fun lights(t: Tracker = Tracker()) = send("/Lights", JSONObject().put("duration", "10").put("action", "activate"), t)
    suspend fun wakeUp(t: Tracker = Tracker()) = send("/VehCharge/state", JSONObject().put("action", "state"), t)

    suspend fun preconditioning(on: Boolean, programs: JSONObject, t: Tracker = Tracker()) =
        send("/ThermalPrecond", JSONObject().put("asap", if (on) "activate" else "deactivate").put("programs", programs), t)

    /** The car's own charge limit: daily = stop at the limit set in the car, trip = charge to 100 %. */
    suspend fun chargeLimit(daily: Boolean, t: Tracker = Tracker()) =
        send("/VehCharge/limit", JSONObject().put("action", if (daily) "daily" else "trip"), t)

    /** immediate = start now; delayed = wait for the scheduled start time (i.e. stop now). */
    suspend fun charging(startNow: Boolean, hour: Int, minute: Int, t: Tracker = Tracker()) =
        send("/VehCharge", JSONObject()
            .put("program", JSONObject().put("hour", hour).put("minute", minute))
            .put("type", if (startNow) "immediate" else "delayed"), t)

    fun forget() {
        disconnectQuietly()
        listOf(K_OTP, K_PIN, K_MQTT_ACCESS, K_MQTT_EXP, K_MQTT_REFRESH, K_MQTT_REFRESH_EXP).forEach { secure.put(it, null) }
    }

    companion object {
        private val IN_PROGRESS = setOf("900", "901", "902")
        const val K_OTP = "otp_state"
        const val K_PIN = "security_code"
        const val K_MQTT_ACCESS = "mqtt_access"
        const val K_MQTT_EXP = "mqtt_expires"
        const val K_MQTT_REFRESH = "mqtt_refresh"
        const val K_MQTT_REFRESH_EXP = "mqtt_refresh_expires"
    }
}
