package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.data.HomeLayout
import no.lionflow.app.model.CarData
import no.lionflow.app.model.FuelStats
import no.lionflow.app.model.VehicleStatus
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.Caps
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.Tab
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.components.Toggle
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtSpeed
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.l10n.tr
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// The parts of Home that can be shown, hidden and moved (see HomeLayout), the notes at its top, and the screen with
// everything the car reports. Several of these came from lionCar, the iPhone port of LionFlow.

/** Notes at the top of Home. Each one can be turned off in Settings. */
@Composable
internal fun HomeBanners(ui: UiState, s: AppSettings) {
    val st = ui.status ?: return
    val c = LocalLf.current
    if (s.bannerDriving && st.moving) Banner(LfIcons.Car, c.blueInk, "The car is being driven", "The numbers are from its last report and can lag behind while it is on the road.")
    if (s.bannerPrivacy && st.privacyOn) Banner(LfIcons.Shield, c.orangeInk, "Privacy mode is on in the car", "It blocks remote commands and can hide where the car is. It is turned off on the car's own screen.")
    if (s.bannerFog && st.fogLightsOn.isNotEmpty() && !st.moving) Banner(LfIcons.Bulb, c.orangeInk, "Fog lights are on", "The car is parked with its fog lights on.")
}

@Composable
private fun Banner(icon: ImageVector, tint: Color, title: String, text: String) {
    val c = LocalLf.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line2, shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(icon, tint, 20.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.label, color = c.text)
            Text(text, style = Type.tiny, color = c.muted)
        }
    }
}

/** Warnings the car itself reports. Nothing is drawn when there are none. */
@Composable
internal fun WarningsCard(ui: UiState) {
    if (ui.alerts.isEmpty()) return
    val c = LocalLf.current
    val fmt = SimpleDateFormat(TimeFmt.pattern("d MMM · HH:mm"), L10n.locale)
    LfCard(radius = 24.dp, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfIcon(LfIcons.Shield, c.orangeInk, 26.dp)
            Text(if (ui.alerts.size == 1) "1 warning from the car" else "${ui.alerts.size} warnings from the car", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
        }
        ui.alerts.take(8).forEach { a ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The car's own name for the warning, shown as it is.
                androidx.compose.material3.Text(a.label, style = Type.body, color = c.text, modifier = Modifier.weight(1f))
                a.atMillis?.let { androidx.compose.material3.Text(fmt.format(Date(it)), style = Type.tiny, color = c.muted) }
            }
        }
        Text("As the car reports them. The car's own screen and handbook say what to do.", style = Type.tiny, color = c.muted)
    }
}

/** "north-east" for a heading in degrees. */
private fun compassWord(deg: Double): String =
    listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[(((deg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

/** Where the car is parked, which way it points, and the walk to it in Google Maps. */
@Composable
internal fun ParkedCard(vm: AppViewModel, ui: UiState) {
    val st = ui.status ?: return
    val lat = st.lat ?: return
    val lon = st.lon ?: return
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val place by vm.parkedAt.collectAsState()
    LaunchedEffect(lat, lon, st.moving) { if (!st.moving) vm.updateParkedPlace(lat, lon) }
    val where = place?.removePrefix("@")
    LfCard(radius = 24.dp, padding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfIcon(LfIcons.Pin, c.text, 26.dp)
            Column(Modifier.weight(1f)) {
                Text(when { st.moving -> "Driving"; where == null -> "Parked"; else -> "Parked at $where" }, style = Type.stat.copy(fontSize = 20.sp), color = c.text, maxLines = 2)
                Text(
                    if (st.heading != null && !st.moving) "Facing ${compassWord(st.heading)}" else if (st.moving) "Where it last reported" else "Where the car last reported",
                    style = Type.small, color = c.muted,
                )
            }
            // A small compass: the arrow points the way the car's nose does, north is up.
            if (st.heading != null && !st.moving) Box(
                Modifier.size(46.dp).clip(CircleShape).background(c.raised).border(1.dp, c.line2, CircleShape)
                    .semantics { contentDescription = tr("Facing ${compassWord(st.heading)}") },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Text("N", style = Type.tiny.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold), color = c.muted, modifier = Modifier.align(Alignment.TopCenter).padding(top = 2.dp))
                LfIcon(LfIcons.Chevron, c.accentInk, 22.dp, modifier = Modifier.rotate((st.heading - 90).toFloat()))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!st.moving) PrimaryButton("Walk to the car", Modifier.weight(1f)) {
                platform.openUrl(String.format(Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f&travelmode=walking", lat, lon))
            }
            SecondaryButton("Show on map", Modifier.weight(1f)) { vm.setTab(Tab.Map) }
        }
    }
}

/** Petrol, diesel and hybrid cars: distance, fuel and what it cost so far this month. */
@Composable
internal fun FuelMonthCard(vm: AppViewModel, ui: UiState, s: AppSettings) {
    if (!Caps.fuel(ui.status)) return
    val c = LocalLf.current
    val from = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val month = ui.trips.filter { (it.startMillis ?: 0L) >= from }
    val km = month.sumOf { it.distanceKm }
    val liters = FuelStats.liters(month)
    val fuelKm = month.filter { it.liters != null }.sumOf { it.distanceKm }
    val cost = FuelStats.cost(liters, s.fuelPrice)
    val cfg by no.lionflow.app.model.ChargeLog.places.collectAsState()
    LfCard(onClick = { vm.setTab(Tab.Trips); vm.loadTrips() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fuel this month", style = Type.label, color = c.text, modifier = Modifier.weight(1f))
            Text(if (month.size == 1) "1 trip" else "${month.size} trips", style = Type.tiny, color = c.muted)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            androidx.compose.material3.Text(if (liters > 0) String.format(Locale.US, "%.1f l", liters) else "—", style = Type.display.copy(fontSize = 30.sp, fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.weight(1f))
            if (cost != null) androidx.compose.material3.Text("≈ " + fuelMoney(cost, cfg.currency), style = Type.stat.copy(fontSize = 20.sp), color = c.accentInk)
            else if (liters > 0) Text("Set the fuel price", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk)
        }
        Row {
            androidx.compose.material3.Text(fmtDistance(km, s), style = Type.tiny, color = c.text2)
            Spacer(Modifier.weight(1f))
            if (fuelKm > 0 && liters > 0) androidx.compose.material3.Text(String.format(Locale.US, "%.1f l/100 km", liters / fuelKm * 100), style = Type.tiny, color = c.muted)
        }
    }
}

internal fun fuelMoney(v: Double, currency: String): String = String.format(Locale.US, if (v < 10) "%.1f" else "%.0f", v) + " " + currency

/** The 12 V battery as the car shares it: volts on most cars, a level on some. */
private fun battery12(st: VehicleStatus): String? {
    val b = st.raw.optJSONObject("battery") ?: return null
    if (!b.has("voltage") || b.isNull("voltage")) return null
    val v = b.optDouble("voltage")
    if (v.isNaN()) return null
    return if (v > 30) "${v.roundToInt()} %" else String.format(Locale.US, "%.1f V", v)
}

/** A few of the car's numbers, and the way in to all of them. */
@Composable
internal fun CarDataCard(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val st = ui.status ?: return
    if (ui.statusMissing) return
    val c = LocalLf.current
    var open by remember { mutableStateOf(false) }
    LfCard(radius = 24.dp, padding = PaddingValues(18.dp), onClick = { open = true }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfIcon(LfIcons.Car, c.text, 26.dp)
            Column(Modifier.weight(1f)) {
                Text("Car data", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
                Text("Every value the car reports", style = Type.small, color = c.muted)
            }
            LfIcon(LfIcons.Chevron, c.muted, 16.dp)
        }
        val cells = listOfNotNull(
            st.odometerKm?.let { "Odometer" to fmtDistance(it, s) },
            // Some cars don't report it: then the weather where the car is.
            (st.outsideTempC ?: ui.weather?.tempC)?.let { "Outside" to fmtTemp(it, s) },
            battery12(st)?.let { "12 V battery" to it },
        )
        if (cells.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    androidx.compose.material3.Text(value, style = Type.label, color = c.text, maxLines = 1)
                    Text(label, style = Type.tiny, color = c.muted, maxLines = 1)
                }
            }
        }
    }
    if (open) CarDataSheet(vm, ui, s) { open = false }
}

/** The car's status in plain words: section title to its rows (label, value). Values are already in the app's language. */
private fun plainRows(st: VehicleStatus, s: AppSettings): List<Pair<String, List<Pair<String, String>>>> {
    fun onOff(b: Boolean) = tr(if (b) "On" else "Off")
    fun one(v: Double) = String.format(Locale.US, "%.1f", v)
    val fmt = SimpleDateFormat(TimeFmt.pattern("d MMM yyyy · HH:mm"), L10n.locale)
    val out = ArrayList<Pair<String, List<Pair<String, String>>>>()
    fun section(title: String, rows: List<Pair<String, String?>>) {
        val kept = rows.mapNotNull { (k, v) -> if (v.isNullOrBlank()) null else k to v }
        if (kept.isNotEmpty()) out += title to kept
    }
    section("Report", listOf(
        "Last report" to st.updatedAtMillis?.let { fmt.format(Date(it)) },
        "Odometer" to st.odometerKm?.let { fmtDistance(it, s) },
    ))
    val fuelOnly = st.energyType == "Fuel"
    section("Energy", listOf(
        "Battery" to st.batteryPct?.takeIf { !fuelOnly }?.let { "${it.roundToInt()} %" },
        "Range" to st.rangeKm?.takeIf { !fuelOnly }?.let { fmtDistance(it, s) },
        "Energy in the battery" to (if (st.residualKwh != null && st.capacityKwh != null) "${one(st.residualKwh)} / ${one(st.capacityKwh)} kWh" else st.capacityKwh?.let { "${one(it)} kWh" }),
        "Battery health" to st.batteryHealthPct?.takeIf { !fuelOnly }?.let { "${it.roundToInt()} %" },
        "Charging" to (if (fuelOnly) null else tr(when { st.isCharging -> "Charging"; st.chargingStatus == "Finished" -> "Charging complete"; st.plugged -> "Plugged in"; else -> "Not plugged in" })),
        "Fuel" to (st.fuelPct ?: st.batteryPct.takeIf { fuelOnly })?.let { "${it.roundToInt()} %" },
        "Fuel range" to (st.fuelRangeKm ?: st.rangeKm.takeIf { fuelOnly })?.let { fmtDistance(it, s) },
        "Average since new" to FuelStats.lifetimeLPer100(st)?.let { "≈ ${one(it)} l/100 km" },
        "Fuel since new" to FuelStats.lifetimeLiters(st)?.let { "≈ ${it.roundToInt()} l" },
        "12 V battery" to battery12(st),
    ))
    val speed = st.raw.optJSONObject("kinetic")?.let { k -> if (k.has("speed") && !k.isNull("speed")) k.optDouble("speed") else null }?.takeIf { !it.isNaN() }
    section("Car", listOf(
        "Doors" to no.lionflow.app.car.CarHub.doorsLine(st),
        "Alarm" to st.alarmOn?.let { onOff(it) },
        "Ignition" to st.ignition?.let { tr(it) },
        "Speed" to (if (st.moving || (speed ?: 0.0) > 0.5) fmtSpeed(speed ?: 0.0, s) else tr("Parked")),
        "Outside" to st.outsideTempC?.let { fmtTemp(it, s) },
        "Pre-heating" to (if (Caps.climate(st)) onOff(st.preconditioningOn) else null),
        "Fog lights" to (if (st.raw.optJSONObject("lightingSystem")?.has("fog") == true) onOff(st.fogLightsOn.isNotEmpty()) else null),
        "Privacy mode" to st.privacyState?.let { tr(it) },
    ))
    section("Position", listOf(
        "Latitude, longitude" to (if (st.lat != null && st.lon != null) String.format(Locale.US, "%.5f, %.5f", st.lat, st.lon) else null),
        "Facing" to st.heading?.let { "${CarData.compass(it)} · ${it.roundToInt()}°" },
    ))
    section("Service", listOf(
        "Next service" to listOfNotNull(st.kmToService?.let { fmtDistance(it, s) }, st.daysToService?.let { tr("{0} days").replace("{0}", "${it.roundToInt()}") }).joinToString(" · ").ifEmpty { null },
    ))
    return out
}

/** Everything the car reported: first in plain words, then every single value, then the raw answer. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CarDataSheet(vm: AppViewModel, ui: UiState, s: AppSettings, onDismiss: () -> Unit) {
    val c = LocalLf.current
    val st = ui.status
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showRaw by remember { mutableStateOf(false) }
    val lang = L10n.lang
    val plain = remember(st, s, lang) { st?.let { plainRows(it, s) }.orEmpty() }
    val all = remember(st) { st?.let { CarData.flatten(it.raw) }.orEmpty() }
    val raw = remember(st) { st?.let { CarData.pretty(it.raw) }.orEmpty() }

    @Composable
    fun rows(list: List<Pair<String, String>>, translateLabel: Boolean) {
        LfCard(radius = 20.dp, padding = PaddingValues(0.dp)) {
            Column {
                list.forEachIndexed { i, (label, value) ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (translateLabel) Text(label, style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
                        else androidx.compose.material3.Text(label, style = Type.tiny, color = c.muted, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Text(value, style = if (translateLabel) Type.label else Type.small, color = c.text, modifier = Modifier.weight(1f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line2)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Car, c.text2, 22.dp) }
                Column(Modifier.weight(1f)) {
                    Text("All car data", style = Type.display.copy(fontSize = 24.sp), color = c.text, maxLines = 1)
                    Text("What the car last sent to the carmaker's server", style = Type.small, color = c.muted)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.raised).clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.text2, 18.dp) }
            }
            if (st == null || ui.statusMissing || all.isEmpty()) {
                Text("The carmaker's server has no data from this car yet.", style = Type.small, color = c.muted)
            } else {
                plain.forEach { (title, list) ->
                    Text(title, style = Type.label, color = c.text, modifier = Modifier.padding(top = 4.dp))
                    rows(list, translateLabel = true)
                }
                Text("Every value", style = Type.label, color = c.text, modifier = Modifier.padding(top = 4.dp))
                Text("Each value the car sent, under the carmaker's own names.", style = Type.tiny, color = c.muted)
                rows(all, translateLabel = false)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(if (showRaw) "Hide raw data" else "Show raw data", Modifier.weight(1f)) { showRaw = !showRaw }
                    SecondaryButton("Copy raw data", Modifier.weight(1f)) {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(raw))
                        vm.toastText("Copied")
                    }
                }
                Text("The raw data holds the car's position. Mind who you share it with.", style = Type.tiny, color = c.muted)
                if (showRaw) androidx.compose.material3.Text(raw, style = Type.tiny.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = c.text2,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp))
            }
        }
    }
}

/** Choose what a home screen shows: "phone" (also the order), "watch" or "car" (Android Auto). */
@Composable
fun HomeEditor(vm: AppViewModel, ui: UiState, s: AppSettings, target: String, onClose: () -> Unit) {
    val c = LocalLf.current
    val st = ui.status
    val all = when (target) { "watch" -> HomeLayout.WATCH; "car" -> HomeLayout.CAR; else -> HomeLayout.PHONE }
    fun applies(i: HomeLayout.Item) = when (i.needs) { "battery" -> Caps.battery(st); "fuel" -> Caps.fuel(st); else -> true }
    val hiddenCsv = when (target) { "watch" -> s.watchHidden; "car" -> s.carHidden; else -> s.homeHidden }
    val hidden = HomeLayout.hidden(hiddenCsv)
    val items = (if (target == "phone") HomeLayout.ordered(all, s.homeOrder) else all).filter { applies(it) }
    fun setHidden(csv: String) = vm.settings.update {
        when (target) { "watch" -> it.copy(watchHidden = csv); "car" -> it.copy(carHidden = csv); else -> it.copy(homeHidden = csv) }
    }

    @Composable
    fun arrow(up: Boolean, enabled: Boolean, id: String, name: String) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .clickable(enabled = enabled, role = Role.Button) { vm.settings.update { it.copy(homeOrder = HomeLayout.move(all, it.homeOrder, id, up) { item -> applies(item) }) } }
                .semantics { contentDescription = tr(if (up) "Move up" else "Move down") + ": " + tr(name) },
            contentAlignment = Alignment.Center,
        ) { LfIcon(LfIcons.Chevron, if (enabled) c.text2 else c.line2, 16.dp, modifier = Modifier.rotate(if (up) -90f else 90f)) }
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text(when (target) { "watch" -> "On the watch"; "car" -> "In Android Auto"; else -> "Home screen" }, color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    when (target) {
                        "watch" -> "Choose what the watch shows. It changes the next time the watch updates."
                        "car" -> "Choose the tiles and the lines next to the battery on LionFlow's start screen in the car."
                        else -> "Choose the cards on Home, and move them with the arrows."
                    },
                    style = Type.small, color = c.muted, modifier = Modifier.padding(bottom = 8.dp),
                )
                items.forEachIndexed { i, item ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (target == "phone") {
                            arrow(true, i > 0, item.id, item.title)
                            arrow(false, i < items.size - 1, item.id, item.title)
                            Spacer(Modifier.width(6.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.title, style = Type.label.copy(fontSize = 14.sp), color = if (item.id in hidden) c.muted else c.text)
                            if (item.note.isNotEmpty()) Text(item.note, style = Type.tiny, color = c.muted)
                        }
                        Spacer(Modifier.width(8.dp))
                        Toggle(item.id !in hidden, item.title) { show -> setHidden(HomeLayout.toggle(hiddenCsv, item.id, show)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done", color = c.accentInk) } },
        dismissButton = {
            TextButton(onClick = {
                vm.settings.update {
                    when (target) {
                        "watch" -> it.copy(watchHidden = HomeLayout.WATCH_HIDDEN)
                        "car" -> it.copy(carHidden = HomeLayout.CAR_HIDDEN)
                        else -> it.copy(homeHidden = HomeLayout.PHONE_HIDDEN, homeOrder = "")
                    }
                }
            }) { Text("Reset", color = c.muted) }
        },
    )
}
