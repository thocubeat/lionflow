package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import java.util.Calendar
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
    LaunchedEffect(Unit) { if (ui.trips.isEmpty() && !ui.tripsLoading) vm.loadTrips() }
    val days = if (period == "week") 7 else 30
    val since = System.currentTimeMillis() - days * 86_400_000L
    val list = ui.trips.filter { (it.startMillis ?: 0L) >= since }
    val km = list.sumOf { it.distanceKm }
    val kwh = list.mapNotNull { it.kwh }.sum()
    val avg = if (km > 0 && kwh > 0) kwh / km * 100 else null

    ScreenColumn(wide) {
        if (!wide) BackLink("Home") { vm.setTab(Tab.Home) }
        ScreenHeader(if (period == "week") "Last 7 days" else "Last 30 days", "Trips") {
            Segmented(listOf("week" to "Week", "month" to "Month"), period) { period = it }
        }
        LfCard {
            Row(Modifier.fillMaxWidth()) {
                Stat(Modifier.weight(1f), fmtDistance(km, s).substringBefore(" "), if (s.distanceUnit == "mi") "mi driven" else "km driven")
                Stat(Modifier.weight(1f), avg?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "kWh/100 km")
                Stat(Modifier.weight(1f), "${list.size}", "trips")
            }
            // bars per day (week) or per week (month)
            val buckets = if (period == "week") 7 else 5
            val bucketMs = if (period == "week") 86_400_000L else 7 * 86_400_000L
            val start = startOfToday() - (buckets - 1) * bucketMs
            val sums = DoubleArray(buckets)
            for (t in list) {
                val i = (((t.startMillis ?: 0L) - start) / bucketMs).toInt()
                if (i in 0 until buckets) sums[i] += t.distanceKm
            }
            val max = (sums.maxOrNull() ?: 0.0).coerceAtLeast(1.0)
            val labels = if (period == "week") (0 until 7).map { SimpleDateFormat("EEE", Locale.getDefault()).format(Date(start + it * bucketMs)).take(2) }
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
        if (ui.tripsLoading) Row(verticalAlignment = Alignment.CenterVertically) { Busy(); Text("  Loading trips…", style = Type.small, color = c.muted) }
        if (list.isEmpty() && !ui.tripsLoading) Text("No trips in this period.", style = Type.small, color = c.muted)
        if (list.isNotEmpty()) {
            LfCard(padding = PaddingValues(0.dp)) {
                val fmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
                list.take(50).forEachIndexed { i, t ->
                    if (i > 0) Divider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(c.raisedBlue), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Route, c.blueInk, 20.dp) }
                        Column(Modifier.weight(1f)) {
                            Text(t.startMillis?.let { fmt.format(Date(it)) } ?: "Trip", style = Type.label, color = c.text)
                            Text(listOfNotNull(t.durationSec?.let { "${(it / 60).toInt()} min" }, t.avgSpeedKmh?.let { "${it.toInt()} km/h avg" }).joinToString(" · "), style = Type.small, color = c.muted)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(fmtDistance(t.distanceKm, s), style = Type.label.copy(fontSize = 17.sp), color = c.text)
                            t.avgKwhPer100?.let { Text(String.format(Locale.US, "%.1f kWh/100", it), style = Type.tiny, color = c.muted) }
                        }
                    }
                }
            }
        }
    }
}

private fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

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

        // Next service
        val kmLeft = st?.kmToService
        val daysLeft = st?.daysToService
        LfCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Next service", style = Type.small, color = c.muted)
                    if (kmLeft == null && daysLeft == null) {
                        Text(if (st == null) "Waiting for data…" else "Not reported", style = Type.display.copy(fontSize = 24.sp), color = c.text)
                        if (st != null) Text("Peugeot hasn't sent service data for this car yet. It usually appears after the car has reported a few times.", style = Type.small, color = c.muted)
                    } else {
                        if (kmLeft != null) {
                            Text("In ${fmtDistance(kmLeft, s)}", style = Type.display.copy(fontSize = 28.sp), color = c.text)
                            st?.odometerKm?.let { odo -> Text("at about ${fmtDistance(odo + kmLeft, s)}", style = Type.small, color = c.muted) }
                        }
                        if (daysLeft != null) {
                            val due = Date(System.currentTimeMillis() + (daysLeft * 86_400_000L).toLong())
                            val dateText = SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(due)
                            val months = (daysLeft / 30.4).toInt()
                            Text(
                                (if (kmLeft != null) "or by " else "By ") + dateText + " (" + (if (months >= 1) "$months months" else "${daysLeft.toInt()} days") + ")" +
                                    if (kmLeft != null) " · whichever comes first" else "",
                                style = Type.small, color = c.muted,
                            )
                        }
                    }
                }
                if (kmLeft != null || daysLeft != null) {
                    val soon = (kmLeft ?: 1e9) < 1000 || (daysLeft ?: 1e9) < 30
                    Pill(if (soon) "Due soon" else "On track", if (soon) c.raised else c.okBg, if (soon) c.orangeInk else c.okText)
                }
            }
            daysLeft?.let { ProgressBar((1 - it / 365.0).toFloat(), c.blueInk, 8.dp) }
            SecondaryButton("Find a workshop", Modifier.fillMaxWidth()) {
                platform.openUrl("https://www.google.com/maps/search/Peugeot+verksted")
            }
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
            val fmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
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
