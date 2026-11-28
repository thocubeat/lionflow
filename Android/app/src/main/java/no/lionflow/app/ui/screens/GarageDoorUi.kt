package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.data.AppSettings
import no.lionflow.app.data.HomeLayout
import no.lionflow.app.door.Door
import no.lionflow.app.door.DoorException
import no.lionflow.app.door.DoorState
import no.lionflow.app.door.GarageDoor
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.components.ToggleRow
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.l10n.tr
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

/** The line in Settings: what is set up, and the way into setting it up. */
fun garageSummary(): String {
    val c = GarageDoor.config.value ?: return tr("Not set up · ismartgate, Home Assistant and more")
    return GarageDoor.kindName(c.kind) + (c.doorName.takeIf { it.isNotBlank() && c.kind != "links" }?.let { " · $it" } ?: "")
}

/**
 * Setting up the garage door: choose the opener, sign in, check it answers, choose the door, and whether there is a
 * button in Android Auto and a card on Home.
 */
@Composable
fun GarageSetup(vm: AppViewModel, s: AppSettings, onClose: () -> Unit) {
    val c = LocalLf.current
    val scope = rememberCoroutineScope()
    val saved = GarageDoor.config.collectAsState().value
    val savedSecrets = remember { GarageDoor.secrets() }
    var kind by remember { mutableStateOf(saved?.kind ?: "ismartgate") }
    var host by remember { mutableStateOf(saved?.host.orEmpty()) }
    var user by remember { mutableStateOf(saved?.user.orEmpty()) }
    var secret by remember { mutableStateOf(savedSecrets.secret) }
    var openUrl by remember { mutableStateOf(savedSecrets.openUrl) }
    var closeUrl by remember { mutableStateOf(savedSecrets.closeUrl) }
    var doors by remember { mutableStateOf<List<Door>?>(saved?.let { listOf(Door(it.door, it.doorName, DoorState.UNKNOWN)) }) }
    var door by remember { mutableStateOf(saved?.door) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var carButton by remember { mutableStateOf("garage" !in HomeLayout.hidden(s.carHidden)) }
    var homeCard by remember { mutableStateOf(saved != null && "garage" !in HomeLayout.hidden(s.homeHidden)) }
    var confirmRemove by remember { mutableStateOf(false) }
    // Anything changed in the login means connecting again.
    fun edited() { doors = null; door = null; error = null }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted, cursorColor = c.accentInk,
    )
    fun config() = GarageDoor.Config(kind, host.trim(), user.trim(), door ?: "1", doors?.firstOrNull { it.id == door }?.name.orEmpty())
    fun secrets() = GarageDoor.Secrets(secret, openUrl, closeUrl)
    val filled = when (kind) {
        "ismartgate", "gogogate2" -> host.isNotBlank() && user.isNotBlank() && secret.isNotEmpty()
        "homeassistant" -> host.isNotBlank() && secret.isNotBlank()
        else -> openUrl.isNotBlank() && closeUrl.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text("Garage door", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Open and close your garage door from LionFlow and Android Auto. Choose your opener:", style = Type.small, color = c.muted)
                GarageDoor.KINDS.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (id, name) ->
                            val on = id == kind
                            Box(
                                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (on) c.accent else c.raised)
                                    .clickable(role = Role.RadioButton) { if (!on) { kind = id; edited() } }
                                    .padding(horizontal = 10.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(name, style = Type.label.copy(fontSize = 14.sp), color = if (on) c.onAccent else c.text) }
                        }
                    }
                }
                Text(
                    when (kind) {
                        "ismartgate" -> "Use the same username and password as in the ismartgate app. The UDI is in the ismartgate app under Settings → Device; with it the door works from anywhere. The IP address only works on your home Wi-Fi."
                        "gogogate2" -> "Use the same username and password as in the Gogogate app. Works on the same Wi-Fi as the opener."
                        "homeassistant" -> "Make a token in Home Assistant: your profile → Security → Long-lived access tokens. Use the address you reach Home Assistant at from outside to open the door away from home."
                        else -> "LionFlow calls the first link to open and the second to close. Works with IFTTT, Homey, Shelly and most other services that have web links."
                    },
                    style = Type.tiny, color = c.muted,
                )
                when (kind) {
                    "ismartgate", "gogogate2" -> {
                        OutlinedTextField(host, { host = it; edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text(if (kind == "ismartgate") "UDI or IP address" else "IP address") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                        OutlinedTextField(user, { user = it; edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Username") }, keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        OutlinedTextField(secret, { secret = it; edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                    }
                    "homeassistant" -> {
                        OutlinedTextField(host, { host = it; edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Home Assistant address") }, placeholder = { androidx.compose.material3.Text("https://…") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                        OutlinedTextField(secret, { secret = it.trim(); edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Access token") }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                    }
                    else -> {
                        OutlinedTextField(openUrl, { openUrl = it; edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Link to open") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                        OutlinedTextField(closeUrl, { closeUrl = it; edited() }, singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Link to close") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    }
                }

                if (doors == null) {
                    SecondaryButton(if (connecting) "Connecting…" else if (kind == "links") "Check the links" else "Connect", Modifier.fillMaxWidth()) {
                        if (connecting || !filled) return@SecondaryButton
                        connecting = true; error = null
                        scope.launch {
                            try {
                                val found = GarageDoor.serviceFor(config(), secrets()).doors()
                                if (found.isEmpty()) error = tr("No doors are set up on the opener")
                                else { doors = found; door = found.first().id }
                            } catch (e: DoorException) { error = tr(e.message.orEmpty()) }
                            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                            connecting = false
                        }
                    }
                    if (!filled) Text("Fill in all the fields first.", style = Type.tiny, color = c.muted)
                }
                error?.let { Text(it, style = Type.small, color = c.danger) }

                doors?.let { list ->
                    if (list.size > 1) {
                        Text("Which door?", style = Type.label, color = c.text)
                        list.forEach { d ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.RadioButton) { door = d.id }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(if (d.id == door) c.accent else c.line2), contentAlignment = Alignment.Center) {
                                    if (d.id == door) Box(Modifier.size(8.dp).clip(CircleShape).background(c.onAccent))
                                }
                                Spacer(Modifier.width(10.dp))
                                androidx.compose.material3.Text(d.name, style = Type.body, color = c.text, modifier = Modifier.weight(1f))
                                GarageDoor.label(d.state).takeIf { it.isNotEmpty() }?.let { Text(it, style = Type.tiny, color = c.muted) }
                            }
                        }
                    } else if (kind != "links") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LfIcon(LfIcons.Check, c.accentInk, 18.dp)
                            androidx.compose.material3.Text(tr("Connected") + (list.firstOrNull()?.name?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = Type.label, color = c.accentInk)
                        }
                    }
                    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(c.raised)) {
                        ToggleRow("Button in Android Auto", "Next to the YouTube button. It asks before anything moves", carButton) { carButton = it }
                        ToggleRow("Card on Home", "Shows whether the door is open, and opens or closes it", homeCard, showDivider = false) { homeCard = it }
                    }
                }
                if (saved != null) {
                    Text("Remove the garage door", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.danger,
                        modifier = Modifier.clickable { confirmRemove = true }.padding(vertical = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = doors != null && door != null, onClick = {
                GarageDoor.save(config(), secrets())
                vm.settings.update {
                    it.copy(homeHidden = HomeLayout.toggle(it.homeHidden, "garage", homeCard), carHidden = HomeLayout.toggle(it.carHidden, "garage", carButton))
                }
                onClose()
            }) { Text("Save", color = if (doors != null) c.accentInk else c.muted) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = c.muted) } },
    )
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false }, containerColor = c.card,
        title = { Text("Remove the garage door?", color = c.text) },
        text = { Text("The login is deleted from the phone. The button in Android Auto and the card on Home go away.", style = Type.small, color = c.muted) },
        confirmButton = { TextButton(onClick = { GarageDoor.remove(); confirmRemove = false; onClose() }) { Text("Remove", color = c.danger) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel", color = c.muted) } },
    )
}

/** The card on Home: where the door stands, and a button that asks whether to open or close it. */
@Composable
fun GarageCard(vm: AppViewModel) {
    val c = LocalLf.current
    val cfg = GarageDoor.config.collectAsState().value ?: return
    val state by GarageDoor.state.collectAsState()
    val busy by GarageDoor.busy.collectAsState()
    var ask by remember { mutableStateOf(false) }
    // Fresh state while the card is on show.
    LaunchedEffect(cfg) { while (true) { GarageDoor.refresh(); delay(30_000L) } }
    val moving = state == DoorState.OPENING || state == DoorState.CLOSING
    LfCard(radius = 24.dp, padding = PaddingValues(18.dp), onClick = { ask = true }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (state == DoorState.OPEN || moving) c.accent else c.raised), contentAlignment = Alignment.Center) {
                LfIcon(LfIcons.Garage, if (state == DoorState.OPEN || moving) c.onAccent else c.text, 24.dp)
            }
            Column(Modifier.weight(1f)) {
                Text("Garage door", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
                Text(GarageDoor.label(state).ifEmpty { GarageDoor.kindName(cfg.kind) }, style = Type.small, color = if (state == DoorState.OPEN) c.orangeInk else c.muted)
            }
            PrimaryButton(if (busy) "…" else "Open / close", enabled = !busy) { ask = true }
        }
    }
    if (ask) GarageAsk(state, onClose = { ask = false }) { open ->
        ask = false
        GarageDoor.sendThen(open) { vm.toastText(it) }
    }
}

/** Open, close or cancel: asked before anything moves. */
@Composable
fun GarageAsk(state: DoorState, onClose: () -> Unit, onChoice: (Boolean) -> Unit) {
    val c = LocalLf.current
    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        icon = { LfIcon(LfIcons.Garage, c.text, 28.dp) },
        title = { Text("Garage door", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(when (state) {
                    DoorState.OPEN -> "The door is open. What do you want to do?"
                    DoorState.CLOSED -> "The door is closed. What do you want to do?"
                    else -> "Do you want to open or close the garage door?"
                }, style = Type.small, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state == DoorState.OPEN) {
                        PrimaryButton("Close", Modifier.weight(1f)) { onChoice(false) }
                        SecondaryButton("Open", Modifier.weight(1f)) { onChoice(true) }
                    } else {
                        PrimaryButton("Open", Modifier.weight(1f)) { onChoice(true) }
                        SecondaryButton("Close", Modifier.weight(1f)) { onChoice(false) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = c.muted) } },
    )
}
