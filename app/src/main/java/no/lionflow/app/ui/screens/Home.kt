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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
            Box(Modifier.weight(1f)) { ScreenColumn(wide = true, maxWidth = 460) { HomeMain(vm, ui, s) } }
            Box(Modifier.weight(1f)) {
                ScreenColumn(wide = true, maxWidth = 460) {
                    Spacer(Modifier.height(4.dp))
                    ChargingSummary(vm, ui, s)
                    ClimateSummary(vm, ui, s)
                    ServiceSummary(vm, ui, s)
                    if (expanded) TripsSummary(vm, ui, s)
                }
            }
        }
    } else {
        ScreenColumn { HomeMain(vm, ui, s) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeMain(vm: AppViewModel, ui: UiState, s: AppSettings) {
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
            Text(s.carName, style = Type.title, color = c.text)
            Text(
                if (ui.refreshing) "Waking car…" else "Updated ${minutesAgo(st?.updatedAtMillis ?: ui.lastRefresh, ui.now)}" + if (s.autoRefresh) " · auto when open" else "",
                style = Type.tiny, color = c.muted,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.rotate(angle)) { CircleIconButton(LfIcons.Refresh, "Refresh car data now") { spins++; vm.refresh() } }
            CircleIconButton(if (c.isDark) LfIcons.Sun else LfIcons.Moon, if (c.isDark) "Switch to light mode" else "Switch to dark mode") {
                vm.settings.update { it.copy(theme = if (c.isDark) "light" else "dark") }
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.accent).clickable(role = Role.Button) { vm.setTab(Tab.Settings) }
                    .semantics { contentDescription = "Settings" },
                contentAlignment = Alignment.Center,
            ) { Text(s.carName.firstOrNull()?.uppercase() ?: "L", style = Type.label.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold), color = c.onAccent) }
        }
    }

    CarPhoto(vm, ui)

    // Status chips
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (st?.locked) {
            true -> Chip(if (st?.openDoors.isNullOrEmpty()) "Locked · doors closed" else "Locked", LfIcons.Lock, c.accentInk)
            false -> Chip("Unlocked", LfIcons.Unlock, c.orangeInk)
            null -> Chip("Doors —", LfIcons.Lock)
        }
        if (st != null) Chip(if (st.moving) "Driving" else "Parked", LfIcons.Pin) { vm.setTab(Tab.Map) }
        ui.weather?.let { w -> Chip("${fmtTemp(w.tempC, s)} · ${WeatherApi.describe(w.symbol)}", LfIcons.Cloud) { vm.setTab(Tab.Climate) } }
    }

    // Battery
    LfCard(onClick = { vm.setTab(Tab.Charging) }, padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("Battery", style = Type.small, color = c.muted)
                Text(buildAnnotatedString {
                    append(st?.batteryPct?.let { "${it.toInt()}" } ?: "—")
                    withStyle(SpanStyle(color = c.muted, fontSize = 26.sp)) { append(" %") }
                }, style = Type.big, color = c.text)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("≈ ${fmtDistance(st?.rangeKm, s)}", style = Type.display.copy(fontSize = 24.sp), color = c.text)
                Text("range", style = Type.small, color = c.muted)
            }
        }
        ProgressBar(((st?.batteryPct ?: 0.0) / 100.0).toFloat(), c.meter)
        Row(Modifier.fillMaxWidth()) {
            Text(chargingLine(st), style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
            st?.chargingMode?.takeIf { st.isCharging }?.let { Text(if (it == "Quick") "Fast charging" else "AC charging", style = Type.small, color = c.muted) }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallCard(Modifier.weight(1f), LfIcons.Route, c.blueInk, "Trips", "See your drives") { vm.setTab(Tab.Trips); vm.loadTrips() }
        SmallCard(Modifier.weight(1f), LfIcons.Wrench, c.orangeInk, st?.kmToService?.let { fmtDistance(it, s) } ?: "Service", "To next service") { vm.setTab(Tab.Service) }
    }

    // Quick actions
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val locked = st?.locked ?: true
        QuickAction(Modifier.weight(1f), if (locked) LfIcons.Lock else LfIcons.Unlock, if (locked) "Unlock" else "Lock", highlighted = !locked, busy = ui.commandInProgress == "Lock" || ui.commandInProgress == "Unlock") {
            confirm(if (locked) "Unlock your car" else "Lock your car") { vm.lockCar(!locked) }
        }
        QuickAction(Modifier.weight(1f), LfIcons.Bulb, "Flash", busy = ui.commandInProgress == "Flash lights") { confirm("Flash the lights") { vm.lights() } }
        QuickAction(Modifier.weight(1f), LfIcons.Horn, "Horn", busy = ui.commandInProgress == "Horn") { confirm("Sound the horn") { vm.horn() } }
        QuickAction(Modifier.weight(1f), LfIcons.Pin, "Find car") { vm.setTab(Tab.Map) }
    }
    if (!vm.remoteReady) {
        Text("Remote control isn't set up — tap to enable", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
            modifier = Modifier.clickable { vm.openRemoteSetup() }.padding(vertical = 6.dp))
    }
}

fun chargingLine(st: no.lionflow.app.model.VehicleStatus?): String {
    if (st == null) return "Waiting for data…"
    val next = if (st.nextDelayedHour != null) String.format("%02d:%02d", st.nextDelayedHour, st.nextDelayedMinute ?: 0) else null
    return when {
        st.isCharging -> "Charging" + (st.remainingMinutes?.let { " · ${it / 60} h ${it % 60} min left" } ?: "")
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
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.heightIn(min = 72.dp).clip(shape).background(if (highlighted) c.accent else c.card).border(1.dp, c.line, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (busy) no.lionflow.app.ui.Busy() else LfIcon(icon, if (highlighted) c.onAccent else c.text, 22.dp)
        Text(label, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = if (highlighted) c.onAccent else c.text)
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
        Text(chargingLine(st), style = Type.small, color = c.muted)
        no.lionflow.app.ui.components.PrimaryButton(if (st?.isCharging == true) "Stop charging" else "Charge now", Modifier.fillMaxWidth(), enabled = st?.plugged == true) {
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
            }
            no.lionflow.app.ui.components.PrimaryButton(if (st?.preconditioningOn == true) "Stop" else "Pre-heat", color = c.orange) {
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
