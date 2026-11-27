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
import no.lionflow.app.ui.l10n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import no.lionflow.app.api.MildHybridDetection
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
    val showChargingSettings = no.lionflow.app.ui.Caps.battery(ui.status)
    val platform = LocalPlatform.current
    val ctx = LocalContext.current
    var editName by remember { mutableStateOf(false) }
    var editNobil by remember { mutableStateOf(false) }
    var editBattery by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showCarData by remember { mutableStateOf(false) }
    var editHome by remember { mutableStateOf<String?>(null) }
    var askRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    val restorer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) askRestore = uri }
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

        GroupCard {
            val system = s.theme == "system"
            ToggleRow("Dark mode", if (system) "Follows the phone right now" else null, c.isDark) { v -> set { it.copy(theme = if (v) "dark" else "light") } }
            ToggleRow("Follow the phone's setting", "Switches between light and dark with the phone", system, showDivider = false) { v ->
                set { it.copy(theme = if (v) "system" else if (c.isDark) "dark" else "light") }
            }
        }

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
            val garage = vm.garage()
            if (garage.sumOf { it.cars.size } > 1 || garage.size > 1) {
                Divider()
                Text("My cars", style = Type.small, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                garage.forEach { acc ->
                    acc.cars.forEach { v ->
                        val sel = acc.active && v.vin == ui.vehicle?.vin
                        Row(Modifier.fillMaxWidth().clickable { vm.switchTo(acc.id, v.vin) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(vm.garageName(acc, v), style = Type.body.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal), color = c.text)
                                Text(acc.brand.display + " · " + vm.vehicleKind(v) + " · VIN …" + v.vin.takeLast(6), style = Type.tiny, color = c.muted)
                            }
                            if (sel) Text("Selected", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk)
                        }
                    }
                }
            }
            // Not for a car that is plainly electric (e-5008, ë-C3 …), unless it was set by hand.
            val plainElectric = ui.vehicle?.let { v -> vm.vehicleKind(v) == "Electric" || ui.status?.let { it.energyType == "Electric" && it.fuelPct == null } == true } == true
            if (!vm.isDemo && !(plainElectric && ui.vehicle?.let { vm.settings.mildHybridOverride(it.vin) } == null)) ui.vehicle?.let { vehicle ->
                var override by remember(vehicle.vin) { mutableStateOf(vm.settings.mildHybridOverride(vehicle.vin)) }
                val detection = vehicle.mildHybridDetection
                val result = when (detection) {
                    MildHybridDetection.VehicleMetadata -> "Automatic · Recognized from vehicle metadata"
                    MildHybridDetection.ModelName -> "Automatic · Recognized from model name"
                    MildHybridDetection.OtherPowertrain -> "Automatic · Different powertrain"
                    MildHybridDetection.InsufficientMetadata -> "Automatic · Not sure yet (a plug-in hybrid: choose Off)"
                }
                Divider()
                DropdownRow("48 V mild hybrid", result,
                    listOf(0 to "Automatic", 1 to "On", 2 to "Off (plug-in hybrid)"),
                    when (override) { null -> 0; true -> 1; false -> 2 },
                ) { mode ->
                    override = if (mode == 0) null else mode == 1
                    vm.setMildHybridOverride(override)
                }
            }
            Divider()
            Text("Add another car or account", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.fillMaxWidth().clickable { vm.addAccount() }.padding(horizontal = 16.dp, vertical = 12.dp))
            Text("Another brand (for example Citroën next to Peugeot) needs its own sign-in. Your other accounts stay signed in.", style = Type.tiny, color = c.muted,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
            Divider()
            Text(if (ui.reloadingVehicles) "Reloading cars…" else "Reload cars from MyPeugeot",
                style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !ui.reloadingVehicles) { vm.reloadVehicles() }
                    .padding(horizontal = 16.dp, vertical = 12.dp))
        }

        SectionTitle("Home screens")
        GroupCard {
            LinkRow("Phone", "Choose the cards on Home and their order", LfIcons.Home) { editHome = "phone" }
            LinkRow("Watch", "Choose what the watch shows", LfIcons.Gear) { editHome = "watch" }
            LinkRow("Android Auto", "Choose the tiles and lines on the start screen in the car", LfIcons.Car) { editHome = "car" }
            ToggleRow("Note when the car is being driven", "At the top of Home", s.bannerDriving) { v -> set { it.copy(bannerDriving = v) } }
            ToggleRow("Note when privacy mode is on", "It blocks remote commands and can hide where the car is", s.bannerPrivacy) { v -> set { it.copy(bannerPrivacy = v) } }
            ToggleRow("Note when fog lights are left on", "While the car is parked", s.bannerFog, showDivider = false) { v -> set { it.copy(bannerFog = v) } }
        }
        editHome?.let { target -> HomeEditor(vm, ui, s, target) { editHome = null } }

        SectionTitle("Units")
        GroupCard {
            SegRow("Distance", listOf("km" to "km", "mi" to "miles"), s.distanceUnit) { v -> set { it.copy(distanceUnit = v) } }
            Divider()
            SegRow("Temperature", listOf("c" to "°C", "f" to "°F"), s.tempUnit) { v -> set { it.copy(tempUnit = v) } }
            Divider()
            SegRow("Clock", listOf("24" to "24 h", "12" to "12 h", "system" to "System"), s.timeFormat) { v ->
                set { it.copy(timeFormat = v) }
                no.lionflow.app.ui.l10n.TimeFmt.setPreference(ctx, v)
            }
            Divider()
            val places by no.lionflow.app.model.ChargeLog.places.collectAsState()
            val currencies = (no.lionflow.app.model.ChargeLog.CURRENCIES + places.currency).distinct()
            DropdownRow("Currency", "For charging and fuel prices", currencies.mapIndexed { i, cur -> i to cur }, currencies.indexOf(places.currency)) { i ->
                no.lionflow.app.model.ChargeLog.savePlaces(no.lionflow.app.model.ChargeLog.places.value.copy(currency = currencies[i]))
            }
            if (showChargingSettings) {
                Divider()
                LinkRow("Battery size", if (s.batteryKwh > 0) "${s.batteryKwh.toInt()} kWh usable · used to work out charging kW" else "Automatic (from the car) · used to work out charging kW and energy", LfIcons.Bolt, last = true) { editBattery = true }
            }
        }

        SectionTitle("Notifications")
        GroupCard {
            if (showChargingSettings) {
                ToggleRow("Charging complete", "When charging finishes", s.notifyChargeDone) { v -> set { it.copy(notifyChargeDone = v) } }
                ToggleRow("Charging stopped", "If charging is interrupted", s.notifyChargeStopped) { v -> set { it.copy(notifyChargeStopped = v) } }
            }
            ToggleRow("Pre-heating finished", "When pre-heating has run", s.notifyPreheat) { v -> set { it.copy(notifyPreheat = v) } }
            ToggleRow("Car left unlocked", "After about 10 minutes parked and unlocked", s.notifyUnlocked) { v -> set { it.copy(notifyUnlocked = v) } }
            if (showChargingSettings) {
                ToggleRow("Low battery", "Below 20 %", s.notifyLowBattery) { v -> set { it.copy(notifyLowBattery = v) } }
            }
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
        SubscriptionNotice()

        SectionTitle("Driving data")
        GroupCard {
            LinkRow("All car data", "Every value the car reports, and the raw data", LfIcons.Car) { showCarData = true }
            LinkRow("Back up app data", "Settings, saved trips, tags and logs in one file, to move to a new install", LfIcons.Download) {
                try { platform.share(no.lionflow.app.model.Backup.export(ctx), "application/zip") } catch (_: Exception) { vm.toastText("Couldn't make the backup") }
            }
            LinkRow("Restore from backup", "Puts everything back from a LionFlow backup file", LfIcons.Upload) {
                restorer.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }
            LinkRow("Export driving data", "Trips as a CSV file", LfIcons.Download) {
                if (ui.trips.isEmpty()) vm.loadTrips()
                platform.share(vm.exportTripsCsv(), "text/csv")
            }
            LinkRow("Import driving data", "From LionFlow, or the official app's CSV or .myp export", LfIcons.Upload) {
                importer.launch(arrayOf("text/*", "text/csv", "application/csv", "*/*"))
            }
            ToggleRow("Tag similar trips automatically", "A trip with about the same distance and average speed as one you tagged gets the same tag", s.autoTagTrips) { v -> vm.setAutoTag(v) }
            if (s.autoTagTrips) { Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { AutoRouteOption(vm, s) }; Divider() }
            ToggleRow("Auto-refresh when open", "When the app opens or is in use — at most every 5 min", s.autoRefresh) { v -> set { it.copy(autoRefresh = v) } }
            ToggleRow("Wake car when app opens", "Commands go through faster. Uses a little of the 12 V battery; at most every 10 min. When this is off, the car is only woken when you tap refresh", s.wakeOnOpen) { v -> set { it.copy(wakeOnOpen = v) } }
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext as no.lionflow.app.LionFlowApp
            DropdownRow("Wake car automatically", "While the app is closed, widget included. Each wake uses a little of the 12 V battery and shows a small notice. The widget's refresh button always wakes it.",
                listOf(0 to "Never", 1 to "Every hour", 3 to "Every 3 hours", 6 to "Every 6 hours", 12 to "Every 12 hours", 24 to "Once a day"), s.bgWakeHours) { v ->
                set { it.copy(bgWakeHours = v) }; no.lionflow.app.work.Waker.schedule(appCtx)
            }
        }

        if (showCarData) CarDataSheet(vm, ui, s) { showCarData = false }

        RouteTrackingSettings(vm, s)

        SectionTitle("Chargers on the map")
        GroupCard {
            if (no.lionflow.app.BuildConfig.NOBIL_KEY.isNotEmpty()) {
                SegRow("Source", listOf("osm" to "OpenStreetMap", "nobil" to "NOBIL"), s.chargerSource) { v -> set { it.copy(chargerSource = v) }; vm.loadChargers() }
                Divider()
            }
            if (s.chargerSource == "nobil" && no.lionflow.app.BuildConfig.NOBIL_KEY.isNotEmpty()) {
                Text("The official charging-station register for Norway, Sweden, Denmark, Finland and Iceland — accurate power and connector data.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(16.dp))
            } else {
                Text("Free and worldwide, no key needed. Data from OpenStreetMap volunteers — usually complete, power is sometimes missing.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(16.dp))
            }
        }

        SectionTitle("App updates")
        GroupCard {
            LinkRow("What's new", "Everything added and fixed, version by version", LfIcons.Bolt) { vm.loadReleases(); showChanges = true }
            LinkRow("Send feedback", "Ideas, problems or bugs, straight to the developer", LfIcons.Mail, last = true) { showFeedback = true }
        }
        if (showChanges) androidx.compose.material3.AlertDialog(
            onDismissRequest = { showChanges = false }, containerColor = c.card,
            title = { Text("What's new", color = c.text) },
            text = {
                if (vm.releases.isEmpty()) Text("Loading…", style = Type.small, color = c.muted)
                else ChangeList(vm.releases, Modifier.heightIn(max = 480.dp))
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showChanges = false }) { Text("Close", color = c.muted) } },
        )
        if (vm.fromPlay) {
            GroupCard {
                LinkRow("Updates come from Google Play", "You have ${BuildConfig.VERSION_NAME}. Installed from Play, so Android Auto trusts it", LfIcons.Download) { }
            }
        } else {
            GroupCard {
                LinkRow(
                    if (ui.update != null) "Install ${ui.update?.tag}" else "Check for updates",
                    ui.updateStatus ?: "You have ${BuildConfig.VERSION_NAME}",
                    LfIcons.Download,
                ) { if (ui.update != null) { if (ui.needsInstallPermission) platform.openInstallPermission() else vm.installUpdate() } else vm.checkForUpdate(force = true) }
                ToggleRow("Check for updates when the app opens", "Shows a popup when a new version is ready. Only versions signed with LionFlow's key are installed", s.autoUpdate, showDivider = false) { v -> set { it.copy(autoUpdate = v) } }
            }
        }

        SectionTitle("Android Auto")
        GroupCard {
            ToggleRow("Line up the video picture with the car's sound", "YouTube and NRK in the car. Android Auto plays sound a little late. This holds the picture back by about 0.4 seconds so they match. Turn it off if playback looks choppy", s.carVideoSync) { v -> set { it.copy(carVideoSync = v) } }
            ToggleRow("Plain lists in the car", "For cars whose screen can't be touched (a dial or a touchpad instead). Android Auto's own tiles and lists are shown in place of the screens LionFlow draws. Takes effect the next time the car connects", s.carPlain, showDivider = false) { v -> set { it.copy(carPlain = v) } }
        }
        Text(
            if (vm.fromPlay) "Installed from Google Play, so LionFlow shows up in Android Auto's app launcher."
            else "Android Auto only shows LionFlow when it's installed from Google Play (internal test link).",
            style = Type.tiny, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
        )

        SectionTitle("Appearance")
        GroupCard {
            LinkRow("Language", no.lionflow.app.ui.l10n.L10n.OPTIONS.firstOrNull { it.first == s.language }?.second ?: "System default", LfIcons.Globe, last = true) { pickLanguage = true }
        }
        if (pickLanguage) {
            AlertDialog(
                onDismissRequest = { pickLanguage = false }, containerColor = c.card,
                title = { Text("Language", color = c.text) },
                text = {
                    Column {
                        no.lionflow.app.ui.l10n.L10n.OPTIONS.forEach { (code, name) ->
                            Row(Modifier.fillMaxWidth().clickable {
                                set { it.copy(language = code) }
                                no.lionflow.app.ui.l10n.L10n.setPreference(code)
                                pickLanguage = false
                            }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                // Native names are shown as-is so you can always find your language.
                                androidx.compose.material3.Text(if (code == "system") no.lionflow.app.ui.l10n.L10n.tr("System default") else name,
                                    style = Type.body.copy(fontWeight = if (code == s.language) FontWeight.SemiBold else FontWeight.Normal),
                                    color = if (code == s.language) c.accentInk else c.text, modifier = Modifier.weight(1f))
                                if (code == s.language) Text("✓", color = c.accentInk)
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text("Close", color = c.muted) } },
            )
        }

        SectionTitle("Support LionFlow")
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("LionFlow is free, has no ads and never will. It's made in my spare time. If it makes your Peugeot a bit nicer to live with, a small donation helps keep it going. Thank you! ☕", style = Type.small, color = c.text2)
                no.lionflow.app.ui.components.PrimaryButton("Buy me a coffee on Ko-fi", Modifier.fillMaxWidth()) {
                    platform.openUrl("https://ko-fi.com/thocubeat")
                }
            }
        }

        if (vm.isDemo) SecondaryButton("Exit demo", Modifier.fillMaxWidth().padding(top = 14.dp), textColor = c.danger) { vm.exitDemo() }
        else SecondaryButton("Sign out", Modifier.fillMaxWidth().padding(top = 14.dp), textColor = c.danger) { confirmSignOut = true }
        Text(
            "LionFlow ${BuildConfig.VERSION_NAME} · Unofficial: not affiliated with Stellantis, Peugeot, Citroën, DS Automobiles, Opel or Vauxhall.\nMap and chargers © OpenStreetMap contributors, OpenFreeMap · Weather MET Norway · Chargers NOBIL (optional)",
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
            confirmButton = { TextButton(onClick = { set { it.copy(carName = name.trim().ifEmpty { "My " + no.lionflow.app.model.Brands.current.display }) }; editName = false }) { Text("Save", color = c.accentInk) } },
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
    if (editBattery) {
        var txt by remember { mutableStateOf(if (s.batteryKwh > 0) s.batteryKwh.toInt().toString() else "") }
        AlertDialog(
            onDismissRequest = { editBattery = false }, containerColor = c.card,
            title = { Text("Battery size", color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Usable capacity in kWh. Leave empty to use what the car reports. Examples: E-5008 73 kWh, e-208/e-2008 46–51 kWh, plug-in hybrids about 10–12 kWh.", style = Type.small, color = c.muted)
                    OutlinedTextField(txt, { txt = it.filter { ch -> ch.isDigit() || ch == '.' }.take(5) }, singleLine = true, label = { Text("kWh") }, colors = fieldColors,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                }
            },
            confirmButton = { TextButton(onClick = { val v = txt.toDoubleOrNull()?.takeIf { it in 10.0..200.0 } ?: 0.0; set { it.copy(batteryKwh = v) }; editBattery = false }) { Text("Save", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { editBattery = false }) { Text("Cancel", color = c.muted) } },
        )
    }
    if (showFeedback) FeedbackDialog(vm, fieldColors) { showFeedback = false }
    askRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { askRestore = null }, containerColor = c.card,
            title = { Text("Restore from backup", color = c.text) },
            text = { Text("Replaces the app's settings and data on this phone with the ones in the backup. The app then starts again. Sign in afterwards if asked.", style = Type.small, color = c.text2) },
            confirmButton = { TextButton(onClick = {
                askRestore = null
                val ok = try { no.lionflow.app.model.Backup.restore(ctx, uri) } catch (_: Exception) { false }
                if (!ok) vm.toastText("That isn't a LionFlow backup")
            }) { Text("Restore", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { askRestore = null }) { Text("Cancel", color = c.muted) } },
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
private fun DropdownRow(title: String, subtitle: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalLf.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = true }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.label, color = c.text)
                Text(subtitle, style = Type.tiny, color = c.muted)
            }
            Text((options.firstOrNull { it.first == selected }?.second ?: "Never") + " ▾", style = Type.label, color = c.accentInk, modifier = Modifier.padding(start = 12.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, label) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label, style = Type.body, color = c.text) }, onClick = { open = false; onSelect(v) })
            }
        }
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

/**
 * Release notes as points: a line starting with "-" or "*" starts a point, and the lines after it (the notes are
 * wrapped at a fixed width) belong to it until the next point or an empty line. Text outside points comes as
 * paragraphs. Each entry: the text, and whether it is a point.
 */
internal fun notePoints(notes: String): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    var cur: StringBuilder? = null
    var bullet = false
    fun flush() { cur?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it to bullet }; cur = null }
    for (raw in notes.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> flush()
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ") -> { flush(); cur = StringBuilder(line.substring(2).trim()); bullet = true }
            // The first line is a heading of its own.
            out.isEmpty() && cur == null -> { cur = StringBuilder(line); bullet = false; flush() }
            cur == null -> { cur = StringBuilder(line); bullet = false }
            else -> cur!!.append(' ').append(line)
        }
    }
    flush()
    return out
}

/** Releases, newest first: version, date-free heading and the notes. The installed version is marked. */
@Composable
fun ChangeList(list: List<no.lionflow.app.update.Release>, modifier: Modifier = Modifier) {
    val c = LocalLf.current
    Column(modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val mine = r.versionCode == BuildConfig.VERSION_CODE
                androidx.compose.material3.Text(r.name + if (mine) "  ·  " + no.lionflow.app.ui.l10n.L10n.tr("installed") else "",
                    style = Type.label, color = if (mine) c.accentInk else c.text)
                val points = notePoints(r.notes)
                points.forEachIndexed { i, (text, bullet) ->
                    // The first line is the release's heading; the rest are its points (or plain paragraphs).
                    if (i == 0 && !bullet) androidx.compose.material3.Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    else if (bullet) Row {
                        androidx.compose.material3.Text("•  ", style = Type.small, color = c.text2)
                        androidx.compose.material3.Text(text, style = Type.small, color = c.text2)
                    }
                    else androidx.compose.material3.Text(text, style = Type.small, color = c.text2)
                }
            }
        }
    }
}

/** Feedback to the developer by e-mail, optionally with technical details (no position, names or VIN). */
@Composable
private fun FeedbackDialog(vm: AppViewModel, fieldColors: androidx.compose.material3.TextFieldColors, onClose: () -> Unit) {
    val c = LocalLf.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var msg by remember { mutableStateOf("") }
    var details by remember { mutableStateOf(true) }
    var showDetails by remember { mutableStateOf(false) }
    val tech = remember { vm.techDetails() }
    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text("Send feedback", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Write anything: an idea, something that doesn't work, or what happened just before a problem.", style = Type.small, color = c.muted)
                OutlinedTextField(msg, { msg = it.take(5000) }, colors = fieldColors, minLines = 5, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Your message", color = c.muted) })
                Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { details = !details }, verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(details, { details = it }, colors = androidx.compose.material3.CheckboxDefaults.colors(
                        checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.muted))
                    Column(Modifier.weight(1f)) {
                        Text("Include technical details", style = Type.label, color = c.text)
                        Text("App and Android version, phone and car model, the car's latest battery data and the command & error log. No position, names, e-mail or VIN. The more I get, the easier it is to help.", style = Type.tiny, color = c.muted)
                    }
                }
                if (details) Text(if (showDetails) "Hide what is sent" else "Show what is sent", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) { showDetails = !showDetails }.padding(vertical = 4.dp))
                if (details && showDetails) Text(tech, style = Type.tiny.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp), color = c.text2)
                Text("Opens your e-mail app with everything filled in. Tap send there.", style = Type.tiny, color = c.muted)
            }
        },
        confirmButton = {
            TextButton(enabled = msg.isNotBlank(), onClick = {
                val body = msg.trim() + if (details) "\n\n---- Technical details ----\n$tech" else ""
                val subject = "LionFlow feedback (${no.lionflow.app.BuildConfig.VERSION_NAME})"
                // A "send" with an e-mail-only selector: opens the e-mail app with everything filled in, without the share sheet.
                val mail = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf("antpqth@gmail.com"))
                    putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                    putExtra(android.content.Intent.EXTRA_TEXT, body)
                    selector = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:"))
                }
                try { ctx.startActivity(mail); onClose() }
                catch (_: Exception) {
                    try {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO,
                            android.net.Uri.parse("mailto:antpqth@gmail.com?subject=" + android.net.Uri.encode(subject) + "&body=" + android.net.Uri.encode(body.take(6000)))))
                        onClose()
                    } catch (_: Exception) { vm.toastText("No e-mail app found") }
                }
            }) { Text("Send", color = if (msg.isNotBlank()) c.accentInk else c.muted) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = c.muted) } },
    )
}
