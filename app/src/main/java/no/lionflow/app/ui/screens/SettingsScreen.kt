package no.lionflow.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.BuildConfig
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.ScreenColumn
import no.lionflow.app.ui.Tab
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.GroupCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.SectionTitle
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.components.Segmented
import no.lionflow.app.ui.components.ToggleRow
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

@Composable
fun SettingsScreen(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val ctx = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var editNobil by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } } catch (_: Exception) { null }
            if (text != null) vm.importTripsCsv(text)
        }
    }
    fun set(block: (AppSettings) -> AppSettings) = vm.settings.update(block)

    ScreenColumn(wide = false) {
        BackLink("Home") { vm.setTab(Tab.Home) }
        Text("Settings", style = Type.title, color = c.text)

        SectionTitle("Profile")
        GroupCard {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(56.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Text(s.carName.firstOrNull()?.uppercase() ?: "L", style = Type.display.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = c.onAccent)
                }
                Column(Modifier.weight(1f)) {
                    Text(s.carName, style = Type.label.copy(fontSize = 17.sp), color = c.text)
                    Text("MyPeugeot account" + (ui.vehicle?.vin?.let { " · VIN …${it.takeLast(6)}" } ?: ""), style = Type.small, color = c.muted)
                }
                Text("Edit", style = Type.label.copy(fontSize = 14.sp), color = c.accentInk, modifier = Modifier.clickable { editName = true }.padding(8.dp))
            }
        }

        SectionTitle("Units")
        GroupCard {
            SegRow("Distance", listOf("km" to "km", "mi" to "miles"), s.distanceUnit) { v -> set { it.copy(distanceUnit = v) } }
            Divider()
            SegRow("Temperature", listOf("c" to "°C", "f" to "°F"), s.tempUnit) { v -> set { it.copy(tempUnit = v) } }
        }

        SectionTitle("Notifications")
        GroupCard {
            ToggleRow("Charging complete", "When charging finishes", s.notifyChargeDone) { v -> set { it.copy(notifyChargeDone = v) } }
            ToggleRow("Charging stopped", "If charging is interrupted", s.notifyChargeStopped) { v -> set { it.copy(notifyChargeStopped = v) } }
            ToggleRow("Pre-heating finished", "When pre-heating has run", s.notifyPreheat) { v -> set { it.copy(notifyPreheat = v) } }
            ToggleRow("Car left unlocked", "After about 10 minutes parked and unlocked", s.notifyUnlocked) { v -> set { it.copy(notifyUnlocked = v) } }
            ToggleRow("Low battery", "Below 20 %", s.notifyLowBattery) { v -> set { it.copy(notifyLowBattery = v) } }
            ToggleRow("Service reminders", "Before the next service is due", s.notifyService, showDivider = false) { v -> set { it.copy(notifyService = v) } }
        }
        Text("Checked in the background about every 30 minutes.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(start = 4.dp))

        SectionTitle("Security")
        GroupCard {
            ToggleRow("Unlock with fingerprint", "Instead of typing your security code", s.bioUnlock) { v -> set { it.copy(bioUnlock = v) } }
            ToggleRow("Confirm remote actions", "Lock, unlock, climate and charging need confirmation", s.remoteConfirm) { v -> set { it.copy(remoteConfirm = v) } }
            ToggleRow("Fingerprint only", "Skip the code — your fingerprint confirms remote actions", s.remoteBioOnly, showDivider = true) { v -> set { it.copy(remoteBioOnly = v) } }
            LinkRow(if (vm.remoteReady) "Remote control is set up" else "Set up remote control", if (vm.remoteReady) "Set up again with a new SMS code" else "SMS code + MyPeugeot security code", LfIcons.Shield) {
                vm.openRemoteSetup()
            }
            LinkRow("Command & error log", "Exact answers and errors from Peugeot", LfIcons.Route, last = true) { showLog = true }
        }

        SectionTitle("Driving data")
        GroupCard {
            LinkRow("Export driving data", "Trips as a CSV file", LfIcons.Download) {
                if (ui.trips.isEmpty()) vm.loadTrips()
                platform.share(vm.exportTripsCsv(), "text/csv")
            }
            LinkRow("Import driving data", "From a LionFlow CSV export", LfIcons.Upload) {
                importer.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
            }
            ToggleRow("Auto-refresh when open", "When the app opens or is in use — at most every 5 min", s.autoRefresh) { v -> set { it.copy(autoRefresh = v) } }
            ToggleRow("Wake car when app opens", "Commands go through faster. Uses a little of the 12 V battery; at most every 10 min", s.wakeOnOpen, showDivider = false) { v -> set { it.copy(wakeOnOpen = v) } }
        }

        SectionTitle("Chargers on the map")
        GroupCard {
            SegRow("Source", listOf("osm" to "OpenStreetMap", "nobil" to "NOBIL"), s.chargerSource) { v -> set { it.copy(chargerSource = v) }; vm.loadChargers() }
            Divider()
            if (s.chargerSource == "nobil") {
                LinkRow(if (s.nobilKey.isBlank()) "Add NOBIL API key" else "NOBIL API key added", "Free key from info.nobil.no/api · official Nordic register", LfIcons.Bolt, last = true) { editNobil = true }
            } else {
                Text("Free and worldwide, no key needed. Data from OpenStreetMap volunteers — usually complete, power is sometimes missing.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(16.dp))
            }
        }

        SectionTitle("App updates")
        GroupCard {
            LinkRow(
                if (ui.update != null) "Install ${ui.update?.tag}" else "Check for updates",
                ui.updateStatus ?: "You have ${BuildConfig.VERSION_NAME}",
                LfIcons.Download,
            ) { if (ui.update != null) { if (ui.needsInstallPermission) platform.openInstallPermission() else vm.installUpdate() } else vm.checkForUpdate(force = true) }
            ToggleRow("Check for updates when the app opens", "Shows a popup when a new version is ready. Only versions signed with LionFlow's key are installed", s.autoUpdate, showDivider = false) { v -> set { it.copy(autoUpdate = v) } }
        }

        SectionTitle("Appearance")
        GroupCard {
            SegRow("Theme", listOf("dark" to "Dark", "light" to "Light", "system" to "System"), s.theme) { v -> set { it.copy(theme = v) } }
        }

        SecondaryButton("Sign out", Modifier.fillMaxWidth().padding(top = 14.dp), textColor = c.danger) { confirmSignOut = true }
        Text(
            "LionFlow ${BuildConfig.VERSION_NAME} · Not affiliated with Peugeot or Stellantis.\nMap and chargers © OpenStreetMap contributors, OpenFreeMap · Weather MET Norway · Chargers NOBIL (optional)",
            style = Type.tiny, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted, cursorColor = c.accentInk,
    )
    if (editName) {
        var name by remember { mutableStateOf(s.carName) }
        AlertDialog(
            onDismissRequest = { editName = false }, containerColor = c.card,
            title = { Text("Car name", color = c.text) },
            text = { OutlinedTextField(name, { name = it.take(24) }, singleLine = true, colors = fieldColors) },
            confirmButton = { TextButton(onClick = { set { it.copy(carName = name.trim().ifEmpty { "My Peugeot" }) }; editName = false }) { Text("Save", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { editName = false }) { Text("Cancel", color = c.muted) } },
        )
    }
    if (editNobil) {
        var key by remember { mutableStateOf(s.nobilKey) }
        AlertDialog(
            onDismissRequest = { editNobil = false }, containerColor = c.card,
            title = { Text("NOBIL API key", color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Chargers come from NOBIL, the official Nordic charging-station database. Ask for a free key, then paste it here.", style = Type.small, color = c.muted)
                    Text("Open info.nobil.no/api", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                        modifier = Modifier.clickable { platform.openUrl("https://info.nobil.no/api") })
                    OutlinedTextField(key, { key = it.trim() }, singleLine = true, label = { Text("API key") }, colors = fieldColors)
                }
            },
            confirmButton = { TextButton(onClick = { set { it.copy(nobilKey = key) }; editNobil = false; vm.loadChargers() }) { Text("Save", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { editNobil = false }) { Text("Cancel", color = c.muted) } },
        )
    }
    if (showLog) {
        val text = vm.commandLog()
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showLog = false }, containerColor = c.card,
            title = { Text("Command & error log", color = c.text) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, style = Type.tiny.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp), color = c.text2)
                }
            },
            confirmButton = { TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Text("Copy", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { showLog = false }) { Text("Close", color = c.muted) } },
        )
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false }, containerColor = c.card,
            title = { Text("Sign out?", color = c.text) },
            text = { Text("This removes your Peugeot session and remote-control setup from this phone.", color = c.muted) },
            confirmButton = { TextButton(onClick = { confirmSignOut = false; vm.signOut() }) { Text("Sign out", color = c.danger) } },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel", color = c.muted) } },
        )
    }
}

@Composable
private fun SegRow(title: String, options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.label, color = c.text, modifier = Modifier.weight(1f))
        Segmented(options, selected, onSelect = onSelect)
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, icon: ImageVector, last: Boolean = false, onClick: () -> Unit) {
    val c = LocalLf.current
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(icon, c.text2, 18.dp) }
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.label, color = c.text)
                Text(subtitle, style = Type.tiny, color = c.muted)
            }
            LfIcon(LfIcons.Chevron, c.muted, 16.dp)
        }
        if (!last) Divider()
    }
}
