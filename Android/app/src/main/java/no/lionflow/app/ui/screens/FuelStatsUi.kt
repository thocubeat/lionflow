package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.FuelStats
import no.lionflow.app.model.Trip
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.Busy
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Fuel cost and fuel statistics, for petrol, diesel and hybrid cars. The idea and the method (fill-ups read from the
// fuel gauge, the average since new from the car's own counter) come from lionCar, the iPhone port of LionFlow.

private fun one(v: Double) = String.format(Locale.US, "%.1f", v)
private fun hours(sec: Double): String {
    val m = (sec / 60).toInt()
    return if (m >= 60) L10n.tr("${m / 60} h ${m % 60} min") else "$m min"
}

/** On the Trips screen: fuel used and what it cost in the period, the price per litre, and the way in to the statistics. */
@Composable
fun FuelCard(vm: AppViewModel, ui: UiState, s: AppSettings, trips: List<Trip>) {
    val c = LocalLf.current
    val cfg by ChargeLog.places.collectAsState()
    var editPrice by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    val liters = FuelStats.liters(trips)
    val cost = FuelStats.cost(liters, s.fuelPrice)
    LfCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fuel cost", style = Type.label, color = c.text, modifier = Modifier.weight(1f))
            androidx.compose.material3.Text(
                if (s.fuelPrice > 0) String.format(Locale.US, "%.2f %s/l", s.fuelPrice, cfg.currency) else L10n.tr("Set the fuel price"),
                style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { editPrice = true }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                androidx.compose.material3.Text(if (liters > 0) "${one(liters)} l" else "—", style = Type.display.copy(fontSize = 24.sp), color = c.text)
                Text("fuel used", style = Type.tiny, color = c.muted)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                androidx.compose.material3.Text(cost?.let { "≈ " + fuelMoney(it, cfg.currency) } ?: "—", style = Type.display.copy(fontSize = 24.sp), color = c.text)
                Text("fuel cost", style = Type.tiny, color = c.muted)
            }
        }
        if (liters <= 0 && trips.isNotEmpty()) Text("The car hasn't sent fuel figures for these trips.", style = Type.tiny, color = c.muted)
        SecondaryButton("Months, fill-ups and records", Modifier.fillMaxWidth()) { showStats = true }
    }
    if (editPrice) FuelPriceDialog(vm, s, cfg.currency) { editPrice = false }
    if (showStats) FuelStatsSheet(vm, ui, s) { showStats = false }
}

/** Price per litre, and the tank size (left empty, it is worked out from the fuel gauge). */
@Composable
private fun FuelPriceDialog(vm: AppViewModel, s: AppSettings, currency: String, onClose: () -> Unit) {
    val c = LocalLf.current
    var price by remember { mutableStateOf(if (s.fuelPrice > 0) String.format(Locale.US, "%.2f", s.fuelPrice) else "") }
    var tank by remember { mutableStateOf(if (s.tankLiters > 0) s.tankLiters.toInt().toString() else "") }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted, cursorColor = c.accentInk,
    )
    val numbers = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text("Fuel price", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("What a litre costs you. It is used for the fuel cost of trips, months and fill-ups. The currency is the one set for charging costs.", style = Type.small, color = c.muted)
                OutlinedTextField(price, { price = it.replace(',', '.').filter { ch -> ch.isDigit() || ch == '.' }.take(7) }, singleLine = true,
                    label = { androidx.compose.material3.Text("$currency/l") }, colors = colors, keyboardOptions = numbers)
                Text("Tank size in litres. Leave it empty and LionFlow works it out from the fuel gauge.", style = Type.small, color = c.muted)
                OutlinedTextField(tank, { tank = it.filter { ch -> ch.isDigit() }.take(3) }, singleLine = true,
                    label = { androidx.compose.material3.Text("l") }, colors = colors, keyboardOptions = numbers)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = price.toDoubleOrNull()?.takeIf { it > 0 && it < 100_000 } ?: 0.0
                val t = tank.toDoubleOrNull()?.takeIf { it in 10.0..200.0 } ?: 0.0
                vm.settings.update { it.copy(fuelPrice = p, tankLiters = t) }
                onClose()
            }) { Text("Save", color = c.accentInk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = c.muted) } },
    )
}

/** Months, fill-ups, records and the figures since new. Loads a year of trips the first time it opens. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FuelStatsSheet(vm: AppViewModel, ui: UiState, s: AppSettings, onDismiss: () -> Unit) {
    val c = LocalLf.current
    val cfg by ChargeLog.places.collectAsState()
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    LaunchedEffect(Unit) { vm.loadTrips(365) }
    val st = ui.status
    val trips = ui.trips
    val months = remember(trips) { FuelStats.monthly(trips).take(13) }
    val tankGuess = remember(trips) { FuelStats.tankEstimate(trips) }
    val tank = if (s.tankLiters > 0) s.tankLiters else tankGuess
    val fills = remember(trips, tank) { FuelStats.fillUps(trips, tank).take(30) }
    val rec = remember(trips) { FuelStats.records(trips) }
    val monthFmt = SimpleDateFormat("LLLL yyyy", L10n.locale)
    val dayFmt = SimpleDateFormat("EEE d MMM yyyy", L10n.locale)
    val timeFmt = SimpleDateFormat(TimeFmt.pattern("d MMM · HH:mm"), L10n.locale)
    fun money(liters: Double?): String? = FuelStats.cost(liters, s.fuelPrice)?.let { "≈ " + fuelMoney(it, cfg.currency) }

    @Composable
    fun line(label: String, value: String, note: String?, first: Boolean) {
        if (!first) Divider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Type.small, color = c.text)
                if (note != null) androidx.compose.material3.Text(note, style = Type.tiny, color = c.muted)
            }
            androidx.compose.material3.Text(value, style = Type.label, color = c.text)
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheet, containerColor = c.bg,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line2)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(c.raised), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Route, c.text2, 22.dp) }
                Column(Modifier.weight(1f)) {
                    Text("Fuel statistics", style = Type.display.copy(fontSize = 24.sp), color = c.text, maxLines = 1)
                    Text("From your trips and the car's own counters", style = Type.small, color = c.muted)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.raised).clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.text2, 18.dp) }
            }
            if (ui.tripsLoading) Row(verticalAlignment = Alignment.CenterVertically) { Busy(); Text("  Loading trips…", style = Type.small, color = c.muted) }

            // Since new, from the car's own fuel counter and odometer
            val lifeL = FuelStats.lifetimeLiters(st)
            val lifeAvg = FuelStats.lifetimeLPer100(st)
            if (st?.odometerKm != null || lifeL != null) {
                Text("Since new", style = Type.label, color = c.text, modifier = Modifier.padding(top = 4.dp))
                LfCard(radius = 20.dp, padding = PaddingValues(0.dp)) {
                    Column {
                        line("Odometer", fmtDistance(st?.odometerKm, s), null, true)
                        if (lifeAvg != null) line("Average since new", "≈ ${one(lifeAvg)} l/100 km", null, false)
                        if (lifeL != null) line("Fuel since new", "≈ ${Math.round(lifeL)} l", money(lifeL), false)
                    }
                }
                if (lifeL == null) Text("The car doesn't share its fuel counter, so there is no average since new.", style = Type.tiny, color = c.muted)
            }

            Text("Months", style = Type.label, color = c.text, modifier = Modifier.padding(top = 4.dp))
            if (months.isEmpty()) Text("No trips yet.", style = Type.small, color = c.muted)
            else LfCard(radius = 20.dp, padding = PaddingValues(0.dp)) {
                Column {
                    months.forEachIndexed { i, m ->
                        val bits = listOfNotNull(
                            fmtDistance(m.km, s), L10n.tr(if (m.trips == 1) "1 trip" else "${m.trips} trips"), hours(m.seconds).takeIf { m.seconds > 0 },
                            m.lPer100?.let { "${one(it)} l/100 km" },
                        ).joinToString(" · ")
                        val value = listOfNotNull(if (m.liters > 0) "${one(m.liters)} l" else null, money(m.liters.takeIf { it > 0 })).joinToString("  ").ifEmpty { "—" }
                        if (i > 0) Divider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                androidx.compose.material3.Text(monthFmt.format(Date(m.startMillis)).replaceFirstChar { it.uppercase() }, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                androidx.compose.material3.Text(bits, style = Type.tiny, color = c.muted)
                            }
                            androidx.compose.material3.Text(value, style = Type.label, color = c.text)
                        }
                    }
                }
            }

            Text("Fill-ups", style = Type.label, color = c.text, modifier = Modifier.padding(top = 4.dp))
            if (fills.isEmpty()) Text("No fill-ups found yet. They are read from the fuel gauge before and after your trips, when the car reports it.", style = Type.small, color = c.muted)
            else {
                LfCard(radius = 20.dp, padding = PaddingValues(0.dp)) {
                    Column {
                        fills.forEachIndexed { i, f ->
                            val note = listOfNotNull("${Math.round(f.fromPct)} % → ${Math.round(f.toPct)} %", f.kmSincePrevious?.let { fmtDistance(it, s) }).joinToString(" · ")
                            val value = listOfNotNull(f.liters?.let { "≈ ${Math.round(it)} l" }, money(f.liters)).joinToString("  ").ifEmpty { "+${Math.round(f.toPct - f.fromPct)} %" }
                            if (i > 0) Divider()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    androidx.compose.material3.Text(timeFmt.format(Date(f.atMillis)), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                                    androidx.compose.material3.Text(note, style = Type.tiny, color = c.muted)
                                }
                                androidx.compose.material3.Text(value, style = Type.label, color = c.text)
                            }
                        }
                    }
                }
                Text(
                    when {
                        s.tankLiters > 0 -> "Litres are worked out from your tank size (${s.tankLiters.toInt()} l) and the fuel gauge, so they are estimates."
                        tankGuess != null -> "Litres are estimates: the tank looks like about ${Math.round(tankGuess)} l, worked out from the fuel gauge. You can set the tank size under the fuel price."
                        else -> "Set the tank size under the fuel price to see litres and cost for each fill-up."
                    },
                    style = Type.tiny, color = c.muted,
                )
            }

            Text("Records", style = Type.label, color = c.text, modifier = Modifier.padding(top = 4.dp))
            if (rec.longest == null) Text("No trips yet.", style = Type.small, color = c.muted)
            else LfCard(radius = 20.dp, padding = PaddingValues(0.dp)) {
                Column {
                    fun whenOf(t: Trip?) = t?.startMillis?.let { dayFmt.format(Date(it)) }
                    line("Longest trip", fmtDistance(rec.longest.distanceKm, s), whenOf(rec.longest), true)
                    rec.longestTime?.let { line("Longest time at the wheel", hours(it.durationSec ?: 0.0), whenOf(it), false) }
                    rec.bestDayMillis?.let { line("Most in one day", fmtDistance(rec.bestDayKm, s), dayFmt.format(Date(it)), false) }
                    rec.mostEconomical?.let { line("Most economical trip", "${one(it.avgLPer100 ?: 0.0)} l/100 km", listOfNotNull(whenOf(it), fmtDistance(it.distanceKm, s)).joinToString(" · "), false) }
                    rec.thirstiest?.let { line("Thirstiest trip", "${one(it.avgLPer100 ?: 0.0)} l/100 km", listOfNotNull(whenOf(it), fmtDistance(it.distanceKm, s)).joinToString(" · "), false) }
                }
            }
            Text("Trips of 10 km or more count for the most economical and the thirstiest. The carmaker keeps about a year of trips.", style = Type.tiny, color = c.muted)
        }
    }
}
