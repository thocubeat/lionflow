package no.lionflow.wear

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** What the phone sends back. Texts arrive already formatted in your language and units. */
data class CarState(
    val name: String, val battery: String, val range: String, val fuel: Boolean, val locked: Boolean?,
    val place: String, val today: String, val yesterday: String, val updated: String, val status: String, val cars: Int = 1,
    /** Parts the user turned off on the phone: distance, lock, flash, preheat, place. */
    val hide: Set<String> = emptySet(),
    /** More rows chosen on the phone (doors, odometer …): label to value. */
    val extras: List<Pair<String, String>> = emptyList(),
)

/**
 * Talks to LionFlow on the phone through the Wear OS Data Layer. Your Peugeot sign-in never leaves
 * the phone; Wear OS only connects apps with the same package and signing key.
 */
class WearVm(app: Application) : AndroidViewModel(app), MessageClient.OnMessageReceivedListener {
    val state = MutableStateFlow<CarState?>(null)
    val message = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val noPhone = MutableStateFlow(false)
    private val messages = Wearable.getMessageClient(app)

    init {
        messages.addListener(this)
        refresh()
    }

    override fun onCleared() { messages.removeListener(this) }

    private suspend fun send(path: String, payload: String = ""): Boolean {
        val nodes = try { Wearable.getNodeClient(getApplication<Application>()).connectedNodes.await() } catch (_: Exception) { emptyList() }
        if (nodes.isEmpty()) { noPhone.value = true; return false }
        noPhone.value = false
        nodes.forEach { n -> try { messages.sendMessage(n.id, path, payload.toByteArray()).await() } catch (_: Exception) { } }
        return true
    }

    fun refresh() = viewModelScope.launch {
        busy.value = true
        if (!send("/lionflow/status")) busy.value = false
        // If the phone app doesn't answer (closed and not running), stop the spinner after a while.
        delay(30_000); if (busy.value && state.value == null) { busy.value = false; noPhone.value = true }
    }

    /** Only offered when more than one car is registered on the phone. */
    fun nextCar() = viewModelScope.launch {
        busy.value = true
        message.value = null
        if (!send("/lionflow/cmd", "nextcar")) busy.value = false
    }

    fun command(cmd: String) = viewModelScope.launch {
        busy.value = true
        message.value = null
        if (!send("/lionflow/cmd", cmd)) busy.value = false
    }

    override fun onMessageReceived(e: MessageEvent) {
        val j = try { JSONObject(String(e.data)) } catch (_: Exception) { return }
        when (e.path) {
            "/lionflow/status/reply" -> {
                busy.value = false
                if (j.has("error")) { message.value = j.optString("error"); return }
                state.value = CarState(
                    j.optString("name"), j.optString("battery"), j.optString("range"), j.optBoolean("fuel"),
                    if (j.isNull("locked")) null else j.optBoolean("locked"),
                    j.optString("place"), j.optString("today"), j.optString("yesterday"), j.optString("updated"), j.optString("status"), j.optInt("cars", 1),
                    hide = j.optJSONArray("hide")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
                    extras = j.optJSONArray("extras")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optString("label") to it.optString("value") } } ?: emptyList(),
                )
            }
            "/lionflow/cmd/reply" -> {
                message.value = j.optString("text")
                if (j.optBoolean("final", true)) { busy.value = false; viewModelScope.launch { delay(3000); refresh() } }
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WearApp() } }
    }
}

private val Lime = Color(0xFFC6F36B)
private val Ink = Color(0xFF101114)

@Composable
fun WearApp(vm: WearVm = viewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val noPhone by vm.noPhone.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<String?>(null) }
    val list = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(Modifier.fillMaxWidth(), state = list, horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text(st?.name ?: stringResource(R.string.app_name), color = Lime, fontWeight = FontWeight.Bold) }
            if ((st?.cars ?: 1) > 1) item { Chip(label = { Text("⇄ " + stringResource(R.string.switch_car)) }, onClick = { vm.nextCar() }, enabled = !busy, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
            val s = st
            if (s == null) {
                item {
                    Text(
                        if (noPhone) stringResource(R.string.no_phone) else msg ?: stringResource(R.string.loading),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                item { Chip(label = { Text(stringResource(R.string.refresh)) }, onClick = { vm.refresh() }, colors = ChipDefaults.secondaryChipColors()) }
            } else {
                // Battery % and range
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.battery, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                        Text("${stringResource(if (s.fuel) R.string.fuel else R.string.battery)} · ${s.range}", color = Color(0xFFB8BCC4))
                        if (s.status.isNotBlank()) Text(s.status, fontSize = 12.sp, color = Color(0xFF9A9EA6), textAlign = TextAlign.Center)
                    }
                }
                // Distance today / yesterday
                if ("distance" !in s.hide) item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.today, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.today), fontSize = 11.sp, color = Color(0xFF9A9EA6))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.yesterday, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.yesterday), fontSize = 11.sp, color = Color(0xFF9A9EA6))
                        }
                    }
                }
                msg?.let { m -> item { Text(m, color = Lime, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 10.dp)) } }
                // Actions
                val locked = s.locked != false
                if ("lock" !in s.hide) item {
                    Chip(
                        label = { Text(stringResource(if (locked) R.string.unlock else R.string.lock), color = Ink, fontWeight = FontWeight.SemiBold) },
                        onClick = { confirm = if (locked) "unlock" else "lock" }, enabled = !busy,
                        colors = ChipDefaults.chipColors(backgroundColor = Lime), modifier = Modifier.fillMaxWidth(),
                    )
                }
                if ("flash" !in s.hide) item { Chip(label = { Text(stringResource(R.string.flash)) }, onClick = { confirm = "lights" }, enabled = !busy, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                if (!s.fuel && "preheat" !in s.hide) item { Chip(label = { Text(stringResource(R.string.preheat)) }, onClick = { confirm = "preheat" }, enabled = !busy, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth()) }
                // Where the car is (address only)
                if ("place" !in s.hide) item {
                    Chip(
                        label = { Text(stringResource(R.string.find_car), fontSize = 11.sp) },
                        secondaryLabel = { Text(s.place.ifBlank { "—" }, maxLines = 3) },
                        onClick = {}, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                // More rows chosen on the phone (doors, odometer, outside temperature, next service)
                s.extras.forEach { (label, value) ->
                    item {
                        Chip(
                            label = { Text(label, fontSize = 11.sp) },
                            secondaryLabel = { Text(value, maxLines = 2) },
                            onClick = {}, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item { Text(stringResource(R.string.updated, s.updated), fontSize = 11.sp, color = Color(0xFF9A9EA6)) }
                item { Chip(label = { Text(stringResource(R.string.refresh)) }, onClick = { vm.refresh() }, enabled = !busy, colors = ChipDefaults.secondaryChipColors()) }
            }
            if (busy) item { Text(stringResource(R.string.sending), fontSize = 12.sp, color = Color(0xFF9A9EA6)) }
        }
    }

    Dialog(showDialog = confirm != null, onDismissRequest = { confirm = null }) {
        val c = confirm ?: "unlock"
        Alert(
            title = {
                Text(stringResource(when (c) { "unlock" -> R.string.confirm_unlock; "lock" -> R.string.confirm_lock; "lights" -> R.string.confirm_flash; else -> R.string.confirm_preheat }), textAlign = TextAlign.Center)
            },
            negativeButton = { Button(onClick = { confirm = null }, colors = ButtonDefaults.secondaryButtonColors()) { Text(stringResource(R.string.no)) } },
            positiveButton = { Button(onClick = { confirm = null; vm.command(c) }, colors = ButtonDefaults.buttonColors(backgroundColor = Lime)) { Text(stringResource(R.string.yes), color = Ink) } },
        )
    }

    LaunchedEffect(Unit) { }
}
