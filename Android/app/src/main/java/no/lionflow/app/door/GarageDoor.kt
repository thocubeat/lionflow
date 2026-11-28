package no.lionflow.app.door

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import no.lionflow.app.data.SecureStore

/**
 * The garage door: which opener it is (set up in Settings), where the door stands, and opening and closing it.
 * Used by the Home card and the button in Android Auto. Passwords, tokens and links are kept encrypted on the phone
 * and only ever sent to the opener itself.
 */
object GarageDoor {
    /** The openers to choose from, in the order they are offered. */
    val KINDS = listOf("ismartgate" to "ismartgate", "gogogate2" to "Gogogate 2", "homeassistant" to "Home Assistant", "links" to "Web links")

    /** [host]: the opener's or Home Assistant's address; [door]: which door on it; [doorName]: its name, to show. */
    data class Config(val kind: String, val host: String = "", val user: String = "", val door: String = "1", val doorName: String = "")

    private var prefs: SharedPreferences? = null
    private var secure: SecureStore? = null
    private var log: (String) -> Unit = {}
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config
    private val _state = MutableStateFlow(DoorState.UNKNOWN)
    val state: StateFlow<DoorState> = _state
    private val _busy = MutableStateFlow(false)
    /** True while a command is on its way. */
    val busy: StateFlow<Boolean> = _busy

    private var service: DoorService? = null
    private var target: DoorState? = null
    private var targetUntil = 0L
    private var lastRead = 0L
    private var poll: Job? = null

    val configured: Boolean get() = _config.value != null

    fun init(ctx: Context, log: (String) -> Unit) {
        this.log = log
        val p = ctx.getSharedPreferences("lf_door", Context.MODE_PRIVATE)
        prefs = p
        secure = SecureStore(ctx, "lf_door_secure")
        val kind = p.getString("kind", null)
        _config.value = kind?.let { Config(it, p.getString("host", "").orEmpty(), p.getString("user", "").orEmpty(), p.getString("door", "1").orEmpty(), p.getString("doorName", "").orEmpty()) }
    }

    /** What the setup form holds besides [Config]: the password or token, and the two links. */
    data class Secrets(val secret: String = "", val openUrl: String = "", val closeUrl: String = "")

    fun secrets(): Secrets = secure?.let { Secrets(it.get("secret").orEmpty(), it.get("open").orEmpty(), it.get("close").orEmpty()) } ?: Secrets()

    fun serviceFor(c: Config, s: Secrets): DoorService = when (c.kind) {
        "ismartgate" -> GateApi(c.host, c.user, s.secret, ismartgate = true)
        "gogogate2" -> GateApi(c.host, c.user, s.secret, ismartgate = false)
        "homeassistant" -> HomeAssistantApi(c.host, s.secret)
        else -> LinksApi(s.openUrl.trim(), s.closeUrl.trim())
    }

    private fun current(): DoorService? {
        val c = _config.value ?: return null
        return service ?: serviceFor(c, secrets()).also { service = it }
    }

    fun save(c: Config, s: Secrets) {
        prefs?.edit()?.putString("kind", c.kind)?.putString("host", c.host.trim())?.putString("user", c.user.trim())
            ?.putString("door", c.door)?.putString("doorName", c.doorName)?.apply()
        secure?.put("secret", s.secret.takeIf { it.isNotEmpty() })
        secure?.put("open", s.openUrl.trim().takeIf { it.isNotEmpty() })
        secure?.put("close", s.closeUrl.trim().takeIf { it.isNotEmpty() })
        service = null; target = null; lastRead = 0L
        _state.value = DoorState.UNKNOWN
        _config.value = c.copy(host = c.host.trim(), user = c.user.trim())
        log("garage: set up (${c.kind})")
        scope.launch { refresh(force = true) }
    }

    fun remove() {
        prefs?.edit()?.clear()?.apply()
        listOf("secret", "open", "close").forEach { secure?.put(it, null) }
        service = null; target = null; poll?.cancel()
        _config.value = null
        _state.value = DoorState.UNKNOWN
        log("garage: removed")
    }

    /** Reads where the door stands (at most every 15 seconds unless [force]). */
    suspend fun refresh(force: Boolean = false) {
        val svc = current() ?: return
        val c = _config.value ?: return
        if (c.kind == "links") { settle(DoorState.UNKNOWN); return }
        if (!force && System.currentTimeMillis() - lastRead < 15_000L) return
        lastRead = System.currentTimeMillis()
        val read = try { lock.withLock { svc.state(c.door) } } catch (_: Exception) { return }
        settle(read)
    }

    /** Asks for the state in the background (for screens that come on show). */
    fun refreshSoon() { if (configured) scope.launch { refresh() } }

    /** While the door is moving, "Opening…" or "Closing…" stays until the opener reports it there (or a minute has passed). */
    private fun settle(read: DoorState) {
        val t = target
        if (t != null && read != t && System.currentTimeMillis() < targetUntil) {
            _state.value = if (t == DoorState.OPEN) DoorState.OPENING else DoorState.CLOSING
            return
        }
        target = null
        _state.value = read
    }

    /** Opens or closes the door. Returns what to tell the person. */
    suspend fun send(open: Boolean): String {
        val svc = current() ?: return "No garage door is set up"
        val c = _config.value ?: return "No garage door is set up"
        if (_busy.value) return "Wait a moment, the last command is still on its way"
        _busy.value = true
        return try {
            val sent = lock.withLock { svc.set(c.door, open) }
            if (!sent) {
                settle(if (open) DoorState.OPEN else DoorState.CLOSED)
                log("garage: already ${if (open) "open" else "closed"} (${c.kind})")
                if (open) "The garage door is already open" else "The garage door is already closed"
            } else {
                log("garage: ${if (open) "open" else "close"} sent (${c.kind})")
                target = if (open) DoorState.OPEN else DoorState.CLOSED
                targetUntil = System.currentTimeMillis() + if (c.kind == "links") 20_000L else 60_000L
                _state.value = if (open) DoorState.OPENING else DoorState.CLOSING
                watch()
                if (open) "Opening the garage door" else "Closing the garage door"
            }
        } catch (e: Exception) {
            val msg = (e as? DoorException)?.message ?: e.message ?: e.javaClass.simpleName
            log("garage: failed (${c.kind}): $msg")
            msg
        } finally {
            _busy.value = false
        }
    }

    /** [send] in the background; [done] gets the text on the main thread (a screen may be gone by then). */
    fun sendThen(open: Boolean, done: (String) -> Unit) {
        scope.launch {
            val msg = send(open)
            withContext(Dispatchers.Main) { try { done(msg) } catch (_: Exception) {} }
        }
    }

    /** Follows the door until it has got there. */
    private fun watch() {
        poll?.cancel()
        poll = scope.launch {
            while (target != null && System.currentTimeMillis() < targetUntil + 1_000L) {
                delay(4_000L)
                refresh(force = true)
            }
            if (target != null) { target = null; refresh(force = true) }
        }
    }

    /** The state as a short text ("" when it isn't known). */
    fun label(s: DoorState): String = when (s) {
        DoorState.OPEN -> "Door is open"
        DoorState.CLOSED -> "Door is closed"
        DoorState.OPENING -> "Opening…"
        DoorState.CLOSING -> "Closing…"
        DoorState.UNKNOWN -> ""
    }

    fun kindName(kind: String?): String = KINDS.firstOrNull { it.first == kind }?.second ?: "Garage door"
}
