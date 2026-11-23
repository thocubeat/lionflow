package no.lionflow.app.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import no.lionflow.app.ui.l10n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.api.WeatherApi
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalConfirm
import no.lionflow.app.ui.ScreenColumn
import no.lionflow.app.ui.Tab
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Chip
import no.lionflow.app.ui.components.CircleIconButton
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.LionLogo
import no.lionflow.app.ui.components.ProgressBar
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.minutesAgo
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

@Composable
fun HomeScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean, expanded: Boolean) {
    if (wide) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Box(Modifier.weight(1f)) { ScreenColumn(wide = true, maxWidth = 460) { NoStatusCard(ui); HomeMain(vm, ui, s, wide = true) } }
            Box(Modifier.weight(1f)) {
                ScreenColumn(wide = true, maxWidth = 460) {
                    Spacer(Modifier.height(4.dp))
                    // Things the left column doesn't already show
                    if (no.lionflow.app.ui.Caps.battery(ui.status)) ChargingSummary(vm, ui, s)
                    TripsClimateRow(vm, ui, s)
                    if (no.lionflow.app.ui.Caps.battery(ui.status)) MonthCostCard(vm)
                    ServiceCard(vm, ui, s)
                }
            }
        }
    } else {
        HomePhone(vm, ui, s)
    }
}

/** Phone home: full-width car photo with the name and status on it, then battery, lock state and actions. */
@Composable
private fun HomePhone(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
        Hero(vm, ui, s)
        if (vm.isDemo && !vm.hideDemoBanner) Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) { DemoBanner(vm) }
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            UpdateCard(vm, ui)
            NoStatusCard(ui)
            BatteryCard(vm, ui, s)
            LockLine(ui)
            QuickActions(vm, ui)
            NavCards(vm, ui, s)
        }
    }
}

private val OnPhoto = androidx.compose.ui.graphics.Color(0xFFF4F5F7)
private val PhotoShadow = androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color(0xB3000000), androidx.compose.ui.geometry.Offset(0f, 2f), 14f)
private val OnPhotoMuted = androidx.compose.ui.graphics.Color(0xD9E6E8EC)

/** Tap the car name to switch between cars (also across brands) when there is more than one. */
@Composable
private fun CarSwitcher(vm: AppViewModel, ui: UiState, name: @Composable () -> Unit) {
    val c = LocalLf.current
    var open by remember { mutableStateOf(false) }
    val garage = if (open || vm.hasSeveralCars()) vm.garage() else emptyList()
    val several = garage.sumOf { it.cars.size } > 1
    Box {
        Row(Modifier.then(if (several) Modifier.clip(RoundedCornerShape(10.dp)).clickable { open = true } else Modifier), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            name()
            if (several) androidx.compose.material3.Text("▾", color = c.muted)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            garage.forEach { acc ->
                acc.cars.forEach { v ->
                    val sel = acc.active && v.vin == ui.vehicle?.vin
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Column { Text(vm.garageName(acc, v), style = Type.body.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal), color = c.text); Text(acc.brand.display + " · " + vm.vehicleKind(v), style = Type.tiny, color = c.muted) } },
                        onClick = { open = false; vm.switchTo(acc.id, v.vin) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Hero(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    var spins by remember { mutableIntStateOf(0) }
    val angle by animateFloatAsState(spins * 360f, tween(900), label = "spin")
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.savePhoto(uri) }
    val path = ui.photoPath?.substringBefore("?")
    val bmp = remember(ui.photoPath) {
        path?.let { try { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } catch (_: Exception) { null } }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Box(Modifier.fillMaxWidth().height(400.dp)) {
        if (bmp != null) {
            Image(bmp, "Photo of your car", Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF23272D), Color(0xFF15171A)))))
        }
        // Shade at the top and bottom so the text stays readable on any photo, fading into the page.
        Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            0f to Color(0xD9000000), 0.22f to Color(0x99000000), 0.45f to Color(0x00000000), 0.62f to Color(0x00000000), 0.8f to Color(0x66000000), 0.92f to c.bg.copy(alpha = 0.9f), 1f to c.bg,
        )))

        // Top: brand, car name, last update, buttons
        Row(Modifier.fillMaxWidth().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LionLogo(22.dp)
                    Text(buildAnnotatedString { append("Lion"); withStyle(SpanStyle(color = c.accent)) { append("Flow") } },
                        style = Type.display.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, shadow = PhotoShadow), color = OnPhoto)
                }
                CarSwitcher(vm, ui) { Text(s.carName, style = Type.title.copy(fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp, shadow = PhotoShadow), color = OnPhoto, maxLines = 1) }
                Text(
                    if (ui.refreshing) "Waking car…" else "Updated ${minutesAgo(st?.updatedAtMillis ?: ui.lastRefresh, ui.now)}",
                    style = Type.small.copy(fontWeight = FontWeight.Medium, shadow = PhotoShadow), color = OnPhoto,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.rotate(angle)) { GlassButton(LfIcons.Refresh, "Refresh car data now") { spins++; vm.refresh() } }
            }
        }

        if (bmp == null) {
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).clickable { pick() }.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Camera, c.accent, 24.dp) }
                Spacer(Modifier.height(8.dp))
                Text("Add a photo of your car", style = Type.label, color = OnPhoto)
                Text("Saved on this phone", style = Type.tiny, color = OnPhotoMuted)
            }
        }

        // Bottom: change photo, weather, parked/driving
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (bmp != null) Row(
                Modifier.align(Alignment.End).clip(CircleShape).background(Color(0xB30D0E10)).clickable { pick() }.padding(horizontal = 14.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                LfIcon(LfIcons.Camera, OnPhoto, 16.dp)
                Text("Change photo", style = Type.small.copy(fontWeight = FontWeight.Medium), color = OnPhoto)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val w = ui.weather
                Row(Modifier.padding(end = 12.dp).clickable { vm.setTab(Tab.Climate) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (w != null) {
                        LfIcon(LfIcons.Cloud, c.text, 22.dp)
                        Text("${fmtTemp(w.tempC, s)} · ${WeatherApi.describe(w.symbol)}", style = Type.body.copy(shadow = if (c.isDark) PhotoShadow else null), color = c.text)
                    }
                }
                if (st != null) {
                    val label = parkedLabel(vm, st)
                    Row(Modifier.weight(1f).clickable { vm.setTab(Tab.Map) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        LfIcon(LfIcons.Pin, c.text, 20.dp)
                        Text(label, style = Type.body.copy(shadow = if (c.isDark) PhotoShadow else null), color = c.text, maxLines = 1, modifier = Modifier.weight(1f, fill = false),
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0x8C1A1C20))
            .border(1.dp, androidx.compose.ui.graphics.Color(0x26FFFFFF), CircleShape)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { LfIcon(icon, OnPhoto, 24.dp) }
}

/** Older petrol/diesel cars often have no live status from the carmaker; say so instead of failing. */
@Composable
private fun NoStatusCard(ui: UiState) {
    if (!ui.statusMissing) return
    val c = LocalLf.current
    LfCard(radius = 20.dp, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Text("No live status for this car", style = Type.label, color = c.text)
        Text("The carmaker doesn't send live data (battery, doors, position) for this model. Trips, fuel economy and service info still work.", style = Type.tiny, color = c.muted)
    }
}

@Composable
private fun UpdateCard(vm: AppViewModel, ui: UiState) {
    val c = LocalLf.current
    ui.update?.takeIf { ui.updateDismissed }?.let { r ->
        val platform = no.lionflow.app.ui.LocalPlatform.current
        LfCard(radius = 20.dp, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LfIcon(LfIcons.Download, c.accentInk, 22.dp)
                Column(Modifier.weight(1f)) {
                    Text("Update ${r.name} is ready", style = Type.label, color = c.text)
                    Text(ui.updateStatus ?: "Signed with LionFlow's key · installs in a few seconds", style = Type.tiny, color = c.muted)
                }
                val busy = ui.updateStatus?.let { it.startsWith("Downloading") || it.startsWith("Installing") } == true
                if (busy) no.lionflow.app.ui.Busy()
                else Text(if (ui.needsInstallPermission) "Allow" else "Install", style = Type.label, color = c.accentInk,
                    modifier = Modifier.clickable { if (ui.needsInstallPermission) platform.openInstallPermission() else vm.installUpdate() }.padding(8.dp))
            }
        }
    }
}

@Composable
private fun BatteryCard(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    LfCard(onClick = if (st?.isMildHybrid == true) null else ({ vm.setTab(Tab.Charging) }), radius = 28.dp, padding = androidx.compose.foundation.layout.PaddingValues(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (st?.usesFuelGauge == true) "Fuel" else "Battery", style = Type.body, color = c.muted)
                Text(buildAnnotatedString {
                    append(st?.displayLevelPct?.let { "${it.toInt()}" } ?: "—")
                    withStyle(SpanStyle(fontSize = 30.sp)) { append(" %") }
                }, style = Type.big.copy(fontSize = 64.sp, fontWeight = FontWeight.Bold), color = c.text)
            }
            Column(
                Modifier.clip(RoundedCornerShape(20.dp)).background(c.accent).padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val parts = fmtDistance(st?.displayRangeKm, s).split(" ")
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = 22.sp)) { append("≈") }
                    append(parts.firstOrNull() ?: "—")
                    if (parts.size > 1) withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)) { append(" " + parts.drop(1).joinToString(" ")) }
                }, style = Type.display.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), color = c.onAccent)
                Text(if (st?.rangeEstimated == true) "Range (est.)" else "Range", style = Type.small.copy(fontWeight = FontWeight.Medium), color = c.onAccent)
            }
        }
        Spacer(Modifier.height(6.dp))
        ProgressBar(((st?.displayLevelPct ?: 0.0) / 100.0).toFloat(), c.meter)
        if (st?.isMildHybrid != true) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LfIcon(if (st?.isCharging == true) LfIcons.Bolt else LfIcons.Plug, if (st?.isCharging == true) c.accentInk else c.text2, 20.dp)
            Text(chargingLine(st), style = Type.body, color = c.text2, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun LockLine(ui: UiState) {
    val c = LocalLf.current
    val st = ui.status
    val (icon, text, tint) = when (st?.locked) {
        true -> Triple(LfIcons.Lock, if (st.openDoors.isEmpty()) "Locked · Doors closed" else "Locked", c.accentInk)
        false -> Triple(LfIcons.Unlock, "Unlocked", c.orangeInk)
        null -> Triple(LfIcons.Lock, "Doors —", c.muted)
    }
    Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LfIcon(icon, tint, 24.dp)
        Text(text, style = Type.stat.copy(fontSize = 20.sp), color = c.text)
    }
}

@Composable
private fun QuickActions(vm: AppViewModel, ui: UiState) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val locked = st?.locked ?: true
        if (vm.remoteReady) {
            if (no.lionflow.app.ui.Caps.lockState(st)) QuickAction(Modifier.weight(1f), if (locked) LfIcons.Lock else LfIcons.Unlock, if (locked) "Unlock" else "Lock", highlighted = true, busy = ui.commandInProgress == "Lock" || ui.commandInProgress == "Unlock") {
                confirm(if (locked) "Unlock your car" else "Lock your car") { vm.lockCar(!locked) }
            }
            QuickAction(Modifier.weight(1f), LfIcons.Bulb, "Flash", busy = ui.commandInProgress == "Flash lights") { confirm("Flash the lights") { vm.lights() } }
            QuickAction(Modifier.weight(1f), LfIcons.Horn, "Horn", busy = ui.commandInProgress == "Horn") { confirm("Sound the horn") { vm.horn() } }
        }
        if (no.lionflow.app.ui.Caps.position(st)) QuickAction(Modifier.weight(1f), LfIcons.Pin, "Find car") { vm.setTab(Tab.Map) }
    }
    if (!vm.remoteReady && vm.remotePossible) {
        Text("Remote control isn't set up — tap to enable", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
            modifier = Modifier.clickable { vm.openRemoteSetup() }.padding(vertical = 4.dp))
        NeedsPackHint(electric = false)
    }
}

@Composable
private fun NavCards(vm: AppViewModel, ui: UiState, s: AppSettings) {
    TripsClimateRow(vm, ui, s)
    ServiceCard(vm, ui, s)
}

/** Trips and Climate side by side, each half the width. Cars without climate get the full-width trips card. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TripsClimateRow(vm: AppViewModel, ui: UiState, s: AppSettings) {
    if (!no.lionflow.app.ui.Caps.climate(ui.status)) { TripsCard(vm, ui, s); return }
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TripsTile(Modifier.weight(1f).fillMaxHeight(), vm, ui, s)
        ClimateTile(Modifier.weight(1f).fillMaxHeight(), vm, ui, s)
    }
}

@Composable
private fun TripsTile(modifier: Modifier, vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val dayMs = 86_400_000L
    val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val days = (6 downTo 0).map { cal.timeInMillis - it * dayMs }
    val week = ui.trips.filter { (it.startMillis ?: 0L) >= days.first() }
    val perDay = days.map { d -> week.filter { (it.startMillis ?: 0L) in d until d + dayMs }.sumOf { it.distanceKm } }
    val km = perDay.sum()
    val kwh = week.mapNotNull { it.kwh }.sum()
    val max = (perDay.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    LfCard(modifier, radius = 24.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp), onClick = { vm.setTab(Tab.Trips); vm.loadTrips() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LfIcon(LfIcons.Route, c.text, 22.dp)
            Text("Trips", style = Type.stat.copy(fontSize = 18.sp), color = c.text, modifier = Modifier.weight(1f))
            LfIcon(LfIcons.Chevron, c.muted, 18.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (ui.trips.isEmpty() && ui.tripsLoading) "…" else fmtDistance(km, s), style = Type.display.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), color = c.text)
            Text("Last 7 days · ${week.size} trips", style = Type.small, color = c.muted)
            if (km > 1 && kwh > 0) Text(if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", km / kwh / 1.609344) else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwh / km * 100), style = Type.tiny, color = c.muted)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            perDay.forEachIndexed { i, v ->
                Box(Modifier.width(9.dp).height((4 + 26 * (v / max)).dp).clip(RoundedCornerShape(4.dp)).background(if (i == 6) c.accent else if (v > 0) c.bar else c.track))
            }
        }
    }
}

@Composable
private fun ClimateTile(modifier: Modifier, vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    val on = st?.preconditioningOn == true
    LfCard(modifier, radius = 24.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp), onClick = { vm.setTab(Tab.Climate) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LfIcon(LfIcons.Thermo, if (on) c.orangeInk else c.text, 22.dp)
            Text("Climate", style = Type.stat.copy(fontSize = 18.sp), color = c.text, modifier = Modifier.weight(1f))
            LfIcon(LfIcons.Chevron, c.muted, 18.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (on) "Pre-heating" else "Off", style = Type.display.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), color = c.text)
            Text("Outside ${fmtTemp(st?.outsideTempC ?: ui.weather?.tempC, s)}", style = Type.small, color = c.muted)
            nextSchedule(st)?.let { Text("Next: $it", style = Type.tiny, color = c.muted) }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f, fill = false))
        if (vm.remoteReady) no.lionflow.app.ui.components.PrimaryButton(if (on) "Stop" else "Pre-heat", Modifier.fillMaxWidth(), color = c.orange) {
            confirm(if (!on) "Start pre-heating" else "Stop pre-heating") { vm.preconditioning(!on) }
        }
    }
}

/** Last 7 days: distance, trips and a small bar per day. */
@Composable
private fun TripsCard(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val now = System.currentTimeMillis()
    val dayMs = 86_400_000L
    val cal = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    val todayStart = cal.timeInMillis
    val days = (6 downTo 0).map { todayStart - it * dayMs }
    val week = ui.trips.filter { (it.startMillis ?: 0L) >= days.first() }
    val perDay = days.map { d -> week.filter { (it.startMillis ?: 0L) in d until d + dayMs }.sumOf { it.distanceKm } }
    val km = perDay.sum()
    val kwh = week.mapNotNull { it.kwh }.sum()
    LfCard(radius = 24.dp, padding = androidx.compose.foundation.layout.PaddingValues(18.dp), onClick = { vm.setTab(Tab.Trips); vm.loadTrips() }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfIcon(LfIcons.Route, c.text, 26.dp)
            Column(Modifier.weight(1f)) {
                Text("Trips", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
                Text("Last 7 days", style = Type.small, color = c.muted)
            }
            LfIcon(LfIcons.Chevron, c.muted, 20.dp)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (ui.trips.isEmpty() && ui.tripsLoading) "…" else fmtDistance(km, s), style = Type.display.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold), color = c.text)
                Text(
                    listOfNotNull(
                        "${week.size} trips",
                        if (km > 1 && kwh > 0) (if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%.1f mi/kWh", km / kwh / 1.609344) else String.format(java.util.Locale.US, "%.1f kWh/100 km", kwh / km * 100)) else null,
                        week.filter { it.liters != null }.let { f -> val l = f.sumOf { it.liters ?: 0.0 }; val d = f.sumOf { it.distanceKm }; if (d > 1 && l > 0) String.format(java.util.Locale.US, "%.1f l/100 km", l / d * 100) else null },
                    ).joinToString(" · "),
                    style = Type.small, color = c.muted,
                )
            }
            // One bar per day, today on the right.
            val max = (perDay.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            val dayFmt = java.text.SimpleDateFormat("EEEEE", no.lionflow.app.ui.l10n.L10n.locale)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                perDay.forEachIndexed { i, v ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.width(10.dp).height((4 + 40 * (v / max)).dp).clip(RoundedCornerShape(5.dp)).background(if (i == 6) c.accent else if (v > 0) c.bar else c.track))
                        Text(dayFmt.format(java.util.Date(days[i])), style = Type.tiny, color = c.muted)
                    }
                }
            }
        }
    }
}

/**
 * Next service: what Peugeot reports (km and/or days left), plus when you'll reach the km at your
 * current pace (average of the last 30 days of trips). Whichever comes first.
 */
@Composable
private fun ServiceCard(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    val since = System.currentTimeMillis() - 30 * 86_400_000L
    val perDay = ui.trips.filter { (it.startMillis ?: 0L) >= since }.sumOf { it.distanceKm } / 30.0
    val kmLeft = st?.kmToService
    val byKmDays = if (kmLeft != null && perDay > 1) kmLeft / perDay else null
    val byDate = st?.daysToService
    val soonest = listOfNotNull(byKmDays, byDate).minOrNull()
    val dateFmt = java.text.SimpleDateFormat("d MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale)
    LfCard(radius = 24.dp, padding = androidx.compose.foundation.layout.PaddingValues(18.dp), onClick = { vm.setTab(Tab.Service) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfIcon(LfIcons.Wrench, c.text, 26.dp)
            Column(Modifier.weight(1f)) {
                Text("Next service", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
                Text(
                    when {
                        kmLeft == null && byDate == null -> "View service details"
                        kmLeft != null -> "In ${fmtDistance(kmLeft, s)}"
                        else -> "In ${byDate!!.toInt()} days"
                    },
                    style = Type.small, color = c.muted,
                )
            }
            if (soonest != null) Column(horizontalAlignment = Alignment.End) {
                Text("≈ " + dateFmt.format(java.util.Date(System.currentTimeMillis() + (soonest * 86_400_000L).toLong())), style = Type.label, color = c.text)
                Text(if (byKmDays != null && (byDate == null || byKmDays <= byDate)) "at your current driving" else "by date", style = Type.tiny, color = c.muted)
            }
            LfIcon(LfIcons.Chevron, c.muted, 20.dp)
        }
    }
}

@Composable
private fun NavCard(modifier: Modifier, icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val c = LocalLf.current
    LfCard(modifier, radius = 24.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfIcon(icon, c.text, 28.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.stat.copy(fontSize = 20.sp), color = c.text, maxLines = 1)
                Text(subtitle, style = Type.small, color = c.muted, maxLines = 1)
            }
            LfIcon(LfIcons.Chevron, c.muted, 20.dp)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeMain(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean = false) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    var spins by remember { mutableIntStateOf(0) }
    val angle by animateFloatAsState(spins * 360f, tween(900), label = "spin")

    // Header
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LionLogo(20.dp)
                Text(buildAnnotatedString { append("Lion"); withStyle(SpanStyle(color = c.accentInk)) { append("Flow") } },
                    style = Type.display.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = c.text)
            }
            CarSwitcher(vm, ui) { Text(s.carName, style = Type.title, color = c.text) }
            Text(
                if (ui.refreshing) "Waking car…" else "Updated ${minutesAgo(st?.updatedAtMillis ?: ui.lastRefresh, ui.now)}" + if (s.autoRefresh) " · auto when open" else "",
                style = Type.tiny, color = c.muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.rotate(angle)) { CircleIconButton(LfIcons.Refresh, "Refresh car data now") { spins++; vm.refresh() } }
        }
    }

    ui.update?.takeIf { ui.updateDismissed }?.let { r ->
        val platform = no.lionflow.app.ui.LocalPlatform.current
        LfCard(radius = 20.dp, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LfIcon(LfIcons.Download, c.accentInk, 22.dp)
                Column(Modifier.weight(1f)) {
                    Text("Update ${r.name} is ready", style = Type.label, color = c.text)
                    Text(ui.updateStatus ?: "Signed with LionFlow's key · installs in a few seconds", style = Type.tiny, color = c.muted)
                }
                val busy = ui.updateStatus?.let { it.startsWith("Downloading") || it.startsWith("Installing") } == true
                if (busy) no.lionflow.app.ui.Busy()
                else Text(
                    if (ui.needsInstallPermission) "Allow" else "Install",
                    style = Type.label, color = c.accentInk,
                    modifier = Modifier.clickable { if (ui.needsInstallPermission) platform.openInstallPermission() else vm.installUpdate() }.padding(8.dp),
                )
            }
        }
    }

    if (vm.isDemo && !vm.hideDemoBanner) DemoBanner(vm)
    CarPhoto(vm, ui)

    // Status chips
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (st?.locked) {
            true -> Chip(if (st?.openDoors.isNullOrEmpty()) "Locked · doors closed" else "Locked", LfIcons.Lock, c.accentInk)
            false -> Chip("Unlocked", LfIcons.Unlock, c.orangeInk)
            null -> Chip("Doors —", LfIcons.Lock)
        }
        if (st != null) Chip(parkedLabel(vm, st), LfIcons.Pin) { vm.setTab(Tab.Map) }
        ui.weather?.let { w -> Chip("${fmtTemp(w.tempC, s)} · ${WeatherApi.describe(w.symbol)}", LfIcons.Cloud) { vm.setTab(Tab.Climate) } }
    }

    // Battery (on wide screens the charging card in the right column already shows it)
    if (!wide || st?.isMildHybrid == true) LfCard(onClick = if (st?.isMildHybrid == true) null else ({ vm.setTab(Tab.Charging) }), padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (st?.usesFuelGauge == true) "Fuel" else "Battery", style = Type.small, color = c.muted)
                Text(buildAnnotatedString {
                    append(st?.displayLevelPct?.let { "${it.toInt()}" } ?: "—")
                    withStyle(SpanStyle(color = c.muted, fontSize = 26.sp)) { append(" %") }
                }, style = Type.big, color = c.text)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("≈ ${fmtDistance(st?.displayRangeKm, s)}", style = Type.display.copy(fontSize = 24.sp), color = c.text)
                Text(if (ui.status?.rangeEstimated == true) "range (est.)" else "range", style = Type.small, color = c.muted)
            }
        }
        ProgressBar(((st?.displayLevelPct ?: 0.0) / 100.0).toFloat(), c.meter)
        if (st?.isMildHybrid != true) Row(Modifier.fillMaxWidth()) {
            Text(chargingLine(st), style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
            st?.chargingMode?.takeIf { st.isCharging }?.let { Text(if (it == "Quick") "Fast charging" else "AC charging", style = Type.small, color = c.muted) }
        }
    }

    if (!wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallCard(Modifier.weight(1f), LfIcons.Route, c.blueInk, "Trips", "See your drives") { vm.setTab(Tab.Trips); vm.loadTrips() }
        SmallCard(Modifier.weight(1f), LfIcons.Wrench, c.orangeInk, st?.kmToService?.let { fmtDistance(it, s) } ?: "Service", "To next service") { vm.setTab(Tab.Service) }
    }

    // Quick actions
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val locked = st?.locked ?: true
        if (vm.remoteReady) {
            if (no.lionflow.app.ui.Caps.lockState(st)) QuickAction(Modifier.weight(1f), if (locked) LfIcons.Lock else LfIcons.Unlock, if (locked) "Unlock" else "Lock", highlighted = !locked, busy = ui.commandInProgress == "Lock" || ui.commandInProgress == "Unlock") {
                confirm(if (locked) "Unlock your car" else "Lock your car") { vm.lockCar(!locked) }
            }
            QuickAction(Modifier.weight(1f), LfIcons.Bulb, "Flash", busy = ui.commandInProgress == "Flash lights") { confirm("Flash the lights") { vm.lights() } }
            QuickAction(Modifier.weight(1f), LfIcons.Horn, "Horn", busy = ui.commandInProgress == "Horn") { confirm("Sound the horn") { vm.horn() } }
        }
        if (no.lionflow.app.ui.Caps.position(st)) QuickAction(Modifier.weight(1f), LfIcons.Pin, "Find car") { vm.setTab(Tab.Map) }
    }
    if (!vm.remoteReady && vm.remotePossible) {
        Text("Remote control isn't set up — tap to enable", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
            modifier = Modifier.clickable { vm.openRemoteSetup() }.padding(vertical = 6.dp))
        NeedsPackHint(electric = false)
    }
}

fun chargingLine(st: no.lionflow.app.model.VehicleStatus?): String {
    if (st == null) return "Waiting for data…"
    val next = if (st.nextDelayedHour != null) no.lionflow.app.ui.l10n.TimeFmt.hm(st.nextDelayedHour, st.nextDelayedMinute ?: 0) else null
    return when {
        st.isCharging -> "Charging" + (no.lionflow.app.model.ChargePower.label(st)?.let { " at $it" } ?: "") + (no.lionflow.app.model.ChargePower.leftText(st) ?: "")
        st.plugged && next != null -> "Plugged in · charging from $next"
        st.plugged -> "Plugged in · " + when (st.chargingStatus) { "Finished" -> "full"; "Stopped" -> "paused"; else -> "not charging" }
        else -> "Not plugged in"
    }
}

@Composable
private fun CarPhoto(vm: AppViewModel, ui: UiState) {
    val c = LocalLf.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.savePhoto(uri) }
    val path = ui.photoPath?.substringBefore("?")
    val bmp = remember(ui.photoPath) {
        path?.let {
            try {
                val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(it, o)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }
    val shape = RoundedCornerShape(24.dp)
    if (bmp != null) {
        Box(Modifier.fillMaxWidth().height(170.dp).clip(shape)) {
            Image(bmp, "Photo of your car", Modifier.fillMaxWidth().height(170.dp), contentScale = ContentScale.Crop)
            Row(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xC70D0E10))
                    .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                LfIcon(LfIcons.Camera, androidx.compose.ui.graphics.Color(0xFFF2F3F5), 14.dp)
                Text("Change photo", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = androidx.compose.ui.graphics.Color(0xFFF2F3F5))
            }
        }
    } else {
        Column(
            Modifier.fillMaxWidth().height(150.dp).clip(shape).background(c.slot).border(1.5.dp, c.bar, shape)
                .clickable { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Camera, c.accentInk, 22.dp) }
            Spacer(Modifier.height(8.dp))
            Text("Add a photo of your car", style = Type.label, color = c.text)
            Text("Saved on this phone", style = Type.tiny, color = c.muted)
        }
    }
}

@Composable
private fun SmallCard(modifier: Modifier, icon: ImageVector, tint: androidx.compose.ui.graphics.Color, value: String, label: String, onClick: () -> Unit) {
    val c = LocalLf.current
    LfCard(modifier, radius = 22.dp, padding = androidx.compose.foundation.layout.PaddingValues(16.dp), onClick = onClick) {
        LfIcon(icon, tint, 22.dp)
        Text(value, style = Type.stat, color = c.text, maxLines = 1)
        Text(label, style = Type.small, color = c.muted)
    }
}

@Composable
private fun QuickAction(modifier: Modifier, icon: ImageVector, label: String, highlighted: Boolean = false, busy: Boolean = false, onClick: () -> Unit) {
    val c = LocalLf.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.heightIn(min = 96.dp).clip(shape).background(if (highlighted) c.accent else c.card).border(1.dp, if (highlighted) c.accent else c.line, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (busy) no.lionflow.app.ui.Busy() else LfIcon(icon, if (highlighted) c.onAccent else c.text, 28.dp)
        Text(label, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = if (highlighted) c.onAccent else c.text, maxLines = 1)
    }
}

// ---------- summaries used on wide screens ----------

@Composable
fun ChargingSummary(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    LfCard(onClick = { vm.setTab(Tab.Charging) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Charging", style = Type.label, color = c.text, modifier = Modifier.weight(1f))
            no.lionflow.app.ui.components.Pill(if (st?.isCharging == true) "Charging" else if (st?.plugged == true) "Plugged in" else "Unplugged", c.okBg, c.okText)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${st?.batteryPct?.toInt() ?: "—"} %", style = Type.display.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.width(10.dp))
            Text("≈ ${fmtDistance(st?.rangeKm, s)}", style = Type.small, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.weight(1f))
            if (s.chargeLimitOn) Text("Limit ${s.chargeLimitPct} %", style = Type.tiny, color = c.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        ProgressBar(((st?.batteryPct ?: 0.0) / 100).toFloat(), c.meter)
        Text(chargingLine(st), style = Type.small, color = c.text2)
        if (st?.isCharging == true) st.remainingMinutes?.let { rem ->
            val done = java.util.Date(System.currentTimeMillis() + rem * 60_000L)
            Text("Done at about " + java.text.SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale).format(done), style = Type.tiny, color = c.muted)
        }
        val last = no.lionflow.app.model.ChargeLog.sessions.collectAsState().value.firstOrNull()
        if (st?.isCharging != true && last != null) {
            val m = no.lionflow.app.model.ChargeLog.match(last)
            Text("Last: " + java.text.SimpleDateFormat("EEE d MMM", no.lionflow.app.ui.l10n.L10n.locale).format(java.util.Date(last.start)) +
                " · ${String.format(java.util.Locale.US, "%.1f", last.kwh)} kWh · ${m.label}", style = Type.tiny, color = c.muted)
        }
        if (vm.remoteReady) no.lionflow.app.ui.components.PrimaryButton(if (st?.isCharging == true) "Stop charging" else "Charge now", Modifier.fillMaxWidth(), enabled = st?.plugged == true) {
            val start = st?.isCharging != true
            confirm(if (start) "Start charging" else "Stop charging") { vm.charging(start) }
        }
    }
}

@Composable
fun ClimateSummary(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    LfCard(onClick = { vm.setTab(Tab.Climate) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Climate · outside ${fmtTemp(st?.outsideTempC ?: ui.weather?.tempC, s)}", style = Type.small, color = c.muted)
                Text(if (st?.preconditioningOn == true) "Pre-heating" else "Off", style = Type.stat, color = c.text)
                nextSchedule(st)?.let { Text("Next schedule: $it", style = Type.tiny, color = c.muted) }
            }
            if (vm.remoteReady) no.lionflow.app.ui.components.PrimaryButton(if (st?.preconditioningOn == true) "Stop" else "Pre-heat", color = c.orange) {
                val on = st?.preconditioningOn != true
                confirm(if (on) "Start pre-heating" else "Stop pre-heating") { vm.preconditioning(on) }
            }
        }
    }
}

@Composable
fun ServiceSummary(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val st = ui.status
    LfCard(onClick = { vm.setTab(Tab.Service) }) {
        Text("Next service", style = Type.label, color = c.text)
        Text(
            listOfNotNull(st?.kmToService?.let { "In ${fmtDistance(it, s)}" }, st?.daysToService?.let { "${(it / 30).toInt()} months" }).joinToString(" · ").ifEmpty { "No service data yet" },
            style = Type.stat, color = c.text,
        )
    }
}

@Composable
fun TripsSummary(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    LfCard(onClick = { vm.setTab(Tab.Trips); vm.loadTrips() }) {
        Text("Trips", style = Type.label, color = c.text)
        val week = ui.trips.filter { (it.startMillis ?: 0) > System.currentTimeMillis() - 7 * 86_400_000L }
        Text(if (ui.trips.isEmpty()) "Open to load your drives" else "${fmtDistance(week.sumOf { it.distanceKm }, s)} this week", style = Type.stat, color = c.text)
        Spacer(Modifier.width(1.dp))
    }
}

/** The next enabled pre-heating schedule, e.g. "Mon 07:30". */
private fun nextSchedule(st: no.lionflow.app.model.VehicleStatus?): String? {
    val progs = st?.programs?.filter { it.enabled && it.days.isNotEmpty() } ?: return null
    if (progs.isEmpty()) return null
    val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val cal = java.util.Calendar.getInstance()
    val today = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Mon = 0
    val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
    var best: Pair<Int, String>? = null
    for (p in progs) for (d in p.days) {
        val di = days.indexOf(d); if (di < 0) continue
        var offset = (di - today + 7) % 7 * 1440 + p.hour * 60 + p.minute - nowMin
        if (offset < 0) offset += 7 * 1440
        val label = (if (offset < 1440 && di == today) "today" else d) + " " + no.lionflow.app.ui.l10n.TimeFmt.hm(p.hour, p.minute)
        if (best == null || offset < best.first) best = offset to label
    }
    return best?.second
}

/** Charging cost and energy this month, split home/away. */
@Composable
private fun MonthCostCard(vm: AppViewModel) {
    val c = LocalLf.current
    val sessions by no.lionflow.app.model.ChargeLog.sessions.collectAsState()
    val cfg by no.lionflow.app.model.ChargeLog.places.collectAsState()
    if (sessions.isEmpty()) return
    val from = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis
    val month = sessions.filter { it.start >= from }
    val kwhSum = month.sumOf { it.kwh }
    val homeKwh = month.filter { no.lionflow.app.model.ChargeLog.match(it, cfg).kind == "home" }.sumOf { it.kwh }
    val cost = month.sumOf { it.kwh * (no.lionflow.app.model.ChargeLog.match(it, cfg).price ?: 0.0) }
    LfCard(onClick = { vm.setTab(Tab.Charging) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Charging this month", style = Type.label, color = c.text, modifier = Modifier.weight(1f))
            Text("${month.size} sessions", style = Type.tiny, color = c.muted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(java.util.Locale.US, "%.0f kWh", kwhSum), style = Type.display.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.weight(1f))
            Text("≈ " + String.format(java.util.Locale.US, "%.0f", cost) + " " + cfg.currency, style = Type.stat.copy(fontSize = 20.sp), color = c.accentInk)
        }
        ProgressBar(if (kwhSum > 0) (homeKwh / kwhSum).toFloat() else 0f, c.meter)
        Row {
            Text(String.format(java.util.Locale.US, "Home %.0f kWh", homeKwh), style = Type.tiny, color = c.text2)
            Spacer(Modifier.weight(1f))
            Text(String.format(java.util.Locale.US, "Away %.0f kWh", kwhSum - homeKwh), style = Type.tiny, color = c.muted)
        }
    }
}

/** Shown on Home in demo mode. */
@Composable
fun DemoBanner(vm: AppViewModel) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raisedBlue).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Demo mode", style = Type.label, color = c.blueInk)
            Text("Sample car and made-up data. Nothing is sent to a real car.", style = Type.tiny, color = c.text2)
        }
        Text("Sign in", style = Type.label, color = c.accentInk, modifier = Modifier.clickable { vm.exitDemo() }.padding(6.dp))
    }
}


/** "Driving", "Parked at Home", "Parked at <charger or address>", or just "Parked" while the place is still being looked up. */
@androidx.compose.runtime.Composable
private fun parkedLabel(vm: no.lionflow.app.ui.AppViewModel, st: no.lionflow.app.model.VehicleStatus): String {
    val place by vm.parkedAt.collectAsState()
    val places by no.lionflow.app.model.ChargeLog.places.collectAsState()
    androidx.compose.runtime.LaunchedEffect(st.lat, st.lon, st.moving, places) { if (!st.moving) vm.updateParkedPlace(st.lat, st.lon) }
    val p = place
    return when {
        st.moving -> "Driving"
        p == null -> "Parked"
        else -> "Parked at " + p.removePrefix("@")
    }
}
