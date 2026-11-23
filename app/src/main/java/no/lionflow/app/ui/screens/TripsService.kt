package no.lionflow.app.ui.screens

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import no.lionflow.app.ui.l10n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.api.TripHistory
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.Busy
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.ScreenColumn
import no.lionflow.app.ui.ServiceEntry
import no.lionflow.app.ui.Tab
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.Pill
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.ProgressBar
import no.lionflow.app.ui.components.ScreenHeader
import no.lionflow.app.ui.components.Segmented
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackLink(label: String, onClick: () -> Unit) {
    val c = LocalLf.current
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        LfIcon(LfIcons.Back, c.muted, 16.dp)
        Text(label, style = Type.small.copy(fontSize = 14.sp), color = c.muted)
    }
}

@Composable
fun TripsScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean) {
    val c = LocalLf.current
    var period by remember { mutableStateOf("week") }
    var openTrip by remember { mutableStateOf<no.lionflow.app.model.Trip?>(null) }
    var openRoute by remember { mutableStateOf<no.lionflow.app.route.Route?>(null) }
    LaunchedEffect(Unit) { if (ui.trips.isEmpty() && !ui.tripsLoading) vm.loadTrips() }
    val days = if (period == "week") 7 else 30
    val now = if (ui.now > 0L) ui.now else System.currentTimeMillis()
    val since = TripHistory.cutoff(now, days)
    val list = TripHistory.inPeriod(ui.trips, now, days)
    var visibleTrips by remember(period) { mutableStateOf(50) }
    val km = list.sumOf { it.distanceKm }
    val kwh = list.mapNotNull { it.kwh }.sum()
    val avg = if (km > 0 && kwh > 0) kwh / km * 100 else null
    val liters = list.mapNotNull { it.liters }.sum()
    val fuelKm = list.filter { it.liters != null }.sumOf { it.distanceKm }
    val avgL = if (fuelKm > 0 && liters > 0) liters / fuelKm * 100 else null
    // Show l/100 km for petrol cars, or when only fuel was used (hybrids show both if both exist).
    val showFuel = avgL != null && (avg == null || no.lionflow.app.ui.Caps.fuel(ui.status))

    ScreenColumn(wide) {
        if (!wide) BackLink("Home") { vm.setTab(Tab.Home) }
        ScreenHeader(if (period == "week") "Last 7 days" else "Last 30 days", "Trips") {
            Segmented(listOf("week" to "Week", "month" to "Month"), period) { period = it }
        }
        LfCard {
            Row(Modifier.fillMaxWidth()) {
                Stat(Modifier.weight(1f), fmtDistance(km, s).substringBefore(" "), if (s.distanceUnit == "mi") "mi driven" else "km driven")
                if (showFuel) Stat(Modifier.weight(1f), String.format(Locale.US, "%.1f", avgL), "l/100 km")
                if (!showFuel || (avg != null && no.lionflow.app.ui.Caps.battery(ui.status))) Stat(Modifier.weight(1f), avg?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "kWh/100 km")
                Stat(Modifier.weight(1f), "${list.size}", "trips")
            }
            // bars per day (week) or per week (month)
            val buckets = if (period == "week") 7 else 5
            val bucketMs = if (period == "week") 86_400_000L else 7 * 86_400_000L
            val sums = DoubleArray(buckets)
            for (t in list) {
                val i = (((t.startMillis ?: since) - since) / bucketMs).toInt().coerceAtMost(buckets - 1)
                if (i in 0 until buckets) sums[i] += t.distanceKm
            }
            val max = (sums.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            val labels = if (period == "week") (0 until 7).map { SimpleDateFormat("EEE", no.lionflow.app.ui.l10n.L10n.locale).format(Date(since + (it + 1) * bucketMs)).take(2) }
            else (0 until 5).map { "W${it + 1}" }
            Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                for (i in 0 until buckets) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().height((4 + 68 * sums[i] / max).dp).clip(RoundedCornerShape(8.dp)).background(if (i == buckets - 1) c.meter else c.bar))
                        Text(labels[i], style = Type.tiny.copy(fontSize = 11.sp), color = c.muted)
                    }
                }
            }
        }
        if (ui.status?.energyType == "Hybrid" && list.isNotEmpty()) HybridStats(vm, list, ui.trips, s)
        TagStats(vm, ui, s, list)
        if (ui.tripsLoading) Row(verticalAlignment = Alignment.CenterVertically) { Busy(); Text("  Loading trips…", style = Type.small, color = c.muted) }
        if (list.isEmpty() && !ui.tripsLoading) {
            Text("No trips in this period.", style = Type.small, color = c.muted)
            // Peugeot's server answered "no trips found" (some cars, e.g. newer E-5008, don't share trips with other apps).
            if (vm.tripsNone && ui.trips.isEmpty()) Text(
                "Peugeot doesn't give trips for this car to other apps, even when the official app shows them. You can import them: in the official app export your trips as CSV (or the full .myp file), then use Settings → Import driving data here.",
                style = Type.tiny, color = c.muted)
        }
        if (list.isNotEmpty()) {
            LfCard(padding = PaddingValues(0.dp)) {
                val fmt = SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("EEE d MMM · HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
                list.take(visibleTrips).forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    val route = no.lionflow.app.route.Routes.forTrip(ui.routes, t.startMillis, t.endMillis)
                    Row(Modifier.fillMaxWidth().clickable { openTrip = t; openRoute = route }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.raisedBlue), contentAlignment = Alignment.Center) { LfIcon(if (route != null) LfIcons.Map else LfIcons.Route, c.blueInk, 20.dp) }
                        Column(Modifier.weight(1f)) {
                            Text(t.startMillis?.let { fmt.format(Date(it)) } ?: "Trip", style = Type.label, color = c.text)
                            Text(listOfNotNull(t.durationSec?.let { "${(it / 60).toInt()} min" }, t.avgSpeedKmh?.let { "${it.toInt()} km/h avg" }).joinToString(" · "), style = Type.small, color = c.muted)
                        }
                        TripTagSlot(vm, s, t)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtDistance(t.distanceKm, s), style = Type.label.copy(fontSize = 17.sp), color = c.text)
                            t.avgKwhPer100?.let { Text(String.format(Locale.US, "%.1f kWh/100", it), style = Type.tiny, color = c.muted) }
                            // Battery used on the trip, in % and kWh.
                            val used = t.kwh?.takeIf { it > 0 } ?: t.avgKwhPer100?.let { it * t.distanceKm / 100 }
                            val battery = ui.status?.let { no.lionflow.app.model.ChargePower.usableKwh(it) }
                            if (used != null && battery != null && battery > 0) androidx.compose.material3.Text(
                                String.format(Locale.US, "−%.0f %% · %.1f kWh", used / battery * 100, used), style = Type.tiny, color = c.muted)
                            t.avgLPer100?.let { Text(String.format(Locale.US, "%.1f l/100", it), style = Type.tiny, color = c.muted) }
                        }
                    }
                }
            }
        }
        if (visibleTrips < list.size) {
            SecondaryButton("Show more", Modifier.fillMaxWidth()) { visibleTrips += 50 }
        }
        // Routes recorded on the phone that don't match a trip from Peugeot
        val unmatched = ui.routes.filter { r -> r.startMillis >= since && ui.trips.none { t -> no.lionflow.app.route.Routes.forTrip(listOf(r), t.startMillis, t.endMillis) != null } }
        if (unmatched.isNotEmpty()) {
            Text("Recorded routes", style = Type.label, color = c.text)
            LfCard(padding = PaddingValues(0.dp)) {
                val fmt = SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("EEE d MMM · HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
                unmatched.take(30).forEachIndexed { i, r ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().clickable { openTrip = null; openRoute = r }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.raisedBlue), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Map, c.blueInk, 20.dp) }
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(Date(r.startMillis)), style = Type.label, color = c.text)
                            Text("${((r.endMillis - r.startMillis) / 60_000)} min", style = Type.small, color = c.muted)
                        }
                        Text(fmtDistance(r.km, s), style = Type.label.copy(fontSize = 17.sp), color = c.text)
                    }
                }
            }
        }
        if (!s.routeTracking) Text("Tip: turn on Route tracking in Settings to see your drives on a map.", style = Type.tiny, color = c.muted)
        if (openTrip != null || openRoute != null) TripDetail(vm, openTrip, openRoute, s) { openTrip = null; openRoute = null }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TripDetail(vm: AppViewModel, trip: no.lionflow.app.model.Trip?, route: no.lionflow.app.route.Route?, s: AppSettings, onDismiss: () -> Unit) {
    val c = LocalLf.current
    val pts = remember(route) { route?.let { no.lionflow.app.route.Routes.points(it.file) }.orEmpty() }
    val day = SimpleDateFormat("EEE d MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale)
    val hm = SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
    val start = trip?.startMillis ?: route?.startMillis
    val end = trip?.endMillis ?: route?.endMillis ?: start?.let { st -> trip?.durationSec?.let { st + (it * 1000).toLong() } }
    val km = trip?.distanceKm ?: route?.km ?: 0.0
    val minutes = trip?.durationSec?.let { it / 60 } ?: route?.let { (it.endMillis - it.startMillis) / 60_000.0 }
    // Average speed from distance and time (the car's own figure isn't reliable on every model).
    val speed = minutes?.takeIf { it >= 1 && km > 0 }?.let { km / (it / 60) } ?: trip?.avgSpeedKmh
    var confirmDelete by remember { mutableStateOf(false) }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val shape = RoundedCornerShape(18.dp)
    @Composable fun tile(m: Modifier, label: String, value: String) =
        Column(m.clip(shape).background(c.card).border(1.dp, c.line, shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(label, style = Type.tiny, color = c.muted)
            Text(value, style = Type.stat.copy(fontSize = 20.sp), color = c.text)
        }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line2)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.raisedBlue), contentAlignment = Alignment.Center) {
                    LfIcon(LfIcons.Route, c.blueInk, 22.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(start?.let { day.format(Date(it)) } ?: "Trip", style = Type.display.copy(fontSize = 24.sp), color = c.text, maxLines = 1)
                    if (start != null) Text(hm.format(Date(start)) + (end?.let { "–" + hm.format(Date(it)) } ?: ""), style = Type.small, color = c.muted)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.raised).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.text2, 18.dp) }
            }

            // Map, or a note about route tracking
            if (pts.size >= 2) RouteMap(pts)
            else Row(Modifier.fillMaxWidth().clip(shape).background(c.card).border(1.dp, c.line, shape).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LfIcon(LfIcons.Map, c.muted, 20.dp)
                Text(if (s.routeTracking) "No route was recorded for this trip." else "Turn on Route tracking in Settings to see your drives on a map.", style = Type.small, color = c.muted,
                    modifier = Modifier.weight(1f))
            }

            // Hero: distance + duration
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(18.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Distance", style = Type.tiny, color = c.muted)
                    Text(fmtDistance(km, s), style = Type.display.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold), color = c.text)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Duration", style = Type.tiny, color = c.muted)
                    Text(minutes?.let { m -> if (m >= 60) "${(m / 60).toInt()} h ${(m % 60).toInt()} min" else "${m.toInt()} min" } ?: "—",
                        style = Type.display.copy(fontSize = 26.sp), color = c.accentInk)
                }
            }

            // Stats
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tile(Modifier.weight(1f), "Average speed", speed?.let { if (s.distanceUnit == "mi") "${Math.round(it * 0.621371)} mph" else "${Math.round(it)} km/h" } ?: "—")
                when {
                    trip?.avgKwhPer100 != null -> tile(Modifier.weight(1f), "Consumption", String.format(Locale.US, "%.1f kWh/100", trip.avgKwhPer100))
                    trip?.avgLPer100 != null -> tile(Modifier.weight(1f), "Fuel", String.format(Locale.US, "%.1f l/100", trip.avgLPer100))
                    else -> tile(Modifier.weight(1f), "Consumption", "—")
                }
            }
            val energy = trip?.kwh?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f kWh", it) }
            val fuel = trip?.liters?.let { String.format(Locale.US, "%.1f l", it) }
            if (energy != null || fuel != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (energy != null) tile(Modifier.weight(1f), "Energy used", energy)
                if (fuel != null) tile(Modifier.weight(1f), "Fuel used", fuel)
                if (energy == null || fuel == null) Spacer(Modifier.weight(1f))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (route != null) TextButton(onClick = { if (confirmDelete) { vm.deleteRoute(route); onDismiss() } else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap again to delete" else "Delete route", color = c.danger)
                }
                Spacer(Modifier.weight(1f))
                no.lionflow.app.ui.components.PrimaryButton("Done") { onDismiss() }
            }
        }
    }
}

@Composable
private fun Stat(modifier: Modifier, value: String, label: String) {
    val c = LocalLf.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = Type.display.copy(fontSize = 24.sp), color = c.text)
        Text(label, style = Type.tiny, color = c.muted)
    }
}

@Composable
fun ServiceScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean) {
    val c = LocalLf.current
    val st = ui.status
    val platform = LocalPlatform.current
    var adding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ServiceEntry?>(null) }

    ScreenColumn(wide) {
        ScreenHeader(null, "Service")

        // Odometer
        LfCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Car, c.text2, 20.dp) }
                Column(Modifier.weight(1f)) {
                    Text("Odometer", style = Type.small, color = c.muted)
                    Text(fmtDistance(st?.odometerKm, s), style = Type.display.copy(fontSize = 28.sp), color = c.text)
                }
            }
        }

        // Timeline: next service (top), today, last logged service (bottom)
        LaunchedEffect(Unit) { if (ui.trips.isEmpty() && !ui.tripsLoading) vm.loadTrips() }
        ServiceTimeline(vm, ui, s)
        SecondaryButton("Find a workshop", Modifier.fillMaxWidth()) {
            platform.openUrl("https://www.google.com/maps/search/" + no.lionflow.app.model.Brands.current.workshopQuery)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LfCard(Modifier.weight(1f), radius = 20.dp, padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LfIcon(LfIcons.Check, c.accentInk, 14.dp); Text("Doors", style = Type.small, color = c.muted)
                }
                Text(if (st?.openDoors.isNullOrEmpty()) "All closed" else "${st?.openDoors?.size} open", style = Type.label, color = c.text)
            }
            LfCard(Modifier.weight(1f), radius = 20.dp, padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LfIcon(LfIcons.Check, c.accentInk, 14.dp); Text("Battery health", style = Type.small, color = c.muted)
                }
                Text(st?.batteryHealthPct?.let { "${it.toInt()} %" } ?: "—", style = Type.label, color = c.text)
                EstimatedHealth(s, st, compact = true)
            }
        }
        LfCard(padding = PaddingValues(0.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = Type.label, color = c.text, modifier = Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("Add", color = c.accentInk, fontWeight = FontWeight.SemiBold) }
            }
            if (ui.serviceHistory.isEmpty()) {
                Text("Keep your own log of services, tyre changes and repairs here.", style = Type.small, color = c.muted, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp))
            }
            val fmt = SimpleDateFormat("MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale)
            ui.serviceHistory.forEachIndexed { i, e ->
                Divider()
                Row(Modifier.fillMaxWidth().clickable { toDelete = e }.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.padding(top = 5.dp).size(10.dp).clip(CircleShape).background(if (i < 2) c.blueInk else c.bar))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = Type.label, color = c.text)
                        if (e.note.isNotBlank()) Text(e.note, style = Type.small, color = c.muted)
                    }
                    Text(fmt.format(Date(e.dateMillis)), style = Type.small, color = c.muted)
                }
            }
        }
    }

    if (adding) {
        var title by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        val colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
            focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted, cursorColor = c.accentInk,
        )
        AlertDialog(
            onDismissRequest = { adding = false },
            containerColor = c.card,
            title = { Text("Add to history", color = c.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("What was done") }, singleLine = true, colors = colors)
                    OutlinedTextField(note, { note = it }, label = { Text("Workshop, km, notes") }, colors = colors)
                }
            },
            confirmButton = { TextButton(onClick = { if (title.isNotBlank()) vm.addServiceEntry(title.trim(), note.trim()); adding = false }) { Text("Save", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel", color = c.muted) } },
        )
    }
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = c.card,
            title = { Text("Delete “${e.title}”?", color = c.text) },
            confirmButton = { TextButton(onClick = { vm.deleteServiceEntry(e); toDelete = null }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep", color = c.muted) } },
        )
    }
}

/** Next service, today and the last logged service as a vertical timeline (like the MyPeugeot app). */
@Composable
private fun ServiceTimeline(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    var editInterval by remember { mutableStateOf(false) }
    // Peugeot doesn't send the interval; default 12 500 km / 1 year (as in the MyPeugeot plan).
    val intKm = if (s.serviceKm > 0) s.serviceKm else 12_500
    val intMonths = if (s.serviceMonths > 0) s.serviceMonths else 12
    val st = ui.status
    val now = System.currentTimeMillis()
    val odo = st?.odometerKm
    // If Peugeot doesn't send service data, work it out from the interval: the next multiple of it on the odometer.
    val fromInterval = st?.kmToService == null && st?.daysToService == null && odo != null
    val kmLeft = st?.kmToService ?: if (fromInterval) (Math.floor(odo!! / intKm) + 1) * intKm - odo else null
    val daysLeft = st?.daysToService
    // Your driving: km per day over the last 30 days of trips
    val since = now - 30 * 86_400_000L
    val perDay = ui.trips.filter { (it.startMillis ?: 0L) >= since }.sumOf { it.distanceKm } / 30.0
    val byKmDays = if (kmLeft != null && perDay > 0.5) kmLeft / perDay else null
    val soonest = listOfNotNull(byKmDays, daysLeft).minOrNull()
    val dateFmt = SimpleDateFormat("d MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale)
    fun inText(days: Double): String {
        val m = (days / 30.4).toInt()
        return when { days < 1 -> "today"; m >= 1 -> "in about $m months"; else -> "in ${days.toInt()} days" }
    }
    val last = ui.serviceHistory.maxByOrNull { it.dateMillis }

    LfCard(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp)) {
        Text("Service plan", style = Type.label, color = c.text)
        // The one after next: next service + one interval
        if (kmLeft != null || daysLeft != null) {
            val km2 = (kmLeft ?: 0.0) + intKm
            val days2 = (daysLeft ?: 0.0) + intMonths * 30.44
            val byKm2 = if (perDay > 0.5) km2 / perDay else null
            val soon2 = listOfNotNull(byKm2, days2).minOrNull()
            TimelineRow(icon = LfIcons.Wrench, dotColor = c.muted, big = true, lineBelow = true) {
                Text(soon2?.let { "Estimated " + inText(it) } ?: "Later", style = Type.small, color = c.muted)
                Text("Following service", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
                listOfNotNull(
                    odo?.let { "At ≈ ${fmtDistance(it + km2, s)}" },
                    "By date: " + dateFmt.format(Date(now + (days2 * 86_400_000L).toLong())),
                    byKm2?.let { "At your driving: " + dateFmt.format(Date(now + (it * 86_400_000L).toLong())) },
                ).forEach { Text(it, style = Type.small, color = c.muted) }
                Text("Every ${fmtDistance(intKm.toDouble(), s)} or $intMonths months — tap to change", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable { editInterval = true }.padding(vertical = 4.dp))
            }
        }
        // Next service
        TimelineRow(icon = LfIcons.Wrench, dotColor = if ((kmLeft ?: 1e9) < 1000 || (daysLeft ?: 1e9) < 30) c.orangeInk else c.text, big = true, lineBelow = true) {
            if (kmLeft == null && daysLeft == null) {
                Text(if (st == null) "Waiting for data…" else "Peugeot hasn't sent service data yet", style = Type.small, color = c.muted)
                Text("Next periodic service", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
            } else {
                Text(soonest?.let { "Estimated " + inText(it) } ?: "Upcoming", style = Type.small, color = c.blueInk)
                Text("Next periodic service", style = Type.stat.copy(fontSize = 20.sp), color = c.text)
                if (fromInterval) Text("Worked out from the service interval — Peugeot doesn't send service data for this car", style = Type.tiny, color = c.muted)
                listOfNotNull(
                    kmLeft?.let { k -> "In ${fmtDistance(k, s)}" + (odo?.let { " (at ≈ ${fmtDistance(it + k, s)})" } ?: "") },
                    daysLeft?.let { "By date: " + dateFmt.format(Date(now + (it * 86_400_000L).toLong())) },
                    byKmDays?.let { "At your driving (≈ ${fmtDistance(perDay * 30, s)}/month): " + dateFmt.format(Date(now + (it * 86_400_000L).toLong())) },
                ).forEach { Text(it, style = Type.small, color = c.muted) }
                if (kmLeft != null && daysLeft != null) Text("Whichever comes first", style = Type.tiny, color = c.muted)
            }
        }
        // Today
        TimelineRow(icon = null, dotColor = c.accent, big = false, lineBelow = last != null) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.accent).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Today  ", style = Type.small, color = c.onAccent)
                Text(fmtDistance(odo, s), style = Type.label, color = c.onAccent)
            }
        }
        // Last logged service
        if (last != null) TimelineRow(icon = LfIcons.Check, dotColor = c.okText, big = true, lineBelow = false) {
            Text("Done " + SimpleDateFormat("d MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale).format(Date(last.dateMillis)), style = Type.small, color = c.okText)
            Text(last.title, style = Type.stat.copy(fontSize = 20.sp), color = c.text)
            if (last.note.isNotBlank()) Text(last.note, style = Type.small, color = c.muted)
        }
    }
    if (editInterval) IntervalDialog(intKm, intMonths, onDismiss = { editInterval = false }) { km, months ->
        vm.settings.update { it.copy(serviceKm = km, serviceMonths = months) }
        editInterval = false
    }
}

private fun st0(ui: UiState) = ui.status

@Composable
private fun IntervalDialog(km: Int, months: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    val c = LocalLf.current
    var kmText by remember { mutableStateOf(km.toString()) }
    var moText by remember { mutableStateOf(months.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Service interval") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("See your service booklet. Used to estimate the service after next.", style = Type.small, color = c.muted)
                androidx.compose.material3.OutlinedTextField(kmText, { kmText = it.filter(Char::isDigit).take(6) }, label = { Text("Kilometres") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                androidx.compose.material3.OutlinedTextField(moText, { moText = it.filter(Char::isDigit).take(3) }, label = { Text("Months") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(kmText.toIntOrNull()?.coerceIn(1000, 100_000) ?: km, moText.toIntOrNull()?.coerceIn(1, 60) ?: months) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimelineRow(icon: androidx.compose.ui.graphics.vector.ImageVector?, dotColor: androidx.compose.ui.graphics.Color, big: Boolean, lineBelow: Boolean, content: @Composable () -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.width(48.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(3.dp).height(10.dp).background(c.bar))
            val size = if (big) 44.dp else 18.dp
            Box(Modifier.size(size).clip(CircleShape).background(dotColor), contentAlignment = Alignment.Center) {
                if (icon != null) LfIcon(icon, c.card, 22.dp)
            }
            if (lineBelow) Box(Modifier.width(3.dp).weight(1f).background(c.bar))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}
