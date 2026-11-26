package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import no.lionflow.app.ui.l10n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalConfirm
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.Segmented
import no.lionflow.app.ui.components.Toggle
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

/** Charge limit: the car's own daily/trip switch, plus LionFlow's "stop at X %". */
@Composable
fun ChargeLimitCard(vm: AppViewModel, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val confirm = LocalConfirm.current
    val st = ui.status
    var pct by remember(s.chargeLimitPct) { mutableFloatStateOf(s.chargeLimitPct.toFloat()) }

    LfCard {
        Text("Charge limit", style = Type.label, color = c.text)

        // 1) The car's own limit (percentage is set in the car)
        Text("Car's limit", style = Type.small, color = c.muted)
        val current = when (st?.chargeType) { "Partial" -> "daily"; "Full" -> "trip"; else -> "" }
        Segmented(listOf("daily" to "Daily limit", "trip" to "Full (trip)"), current, Modifier.fillMaxWidth()) { v ->
            if (v != current && ui.commandInProgress == null && st != null)
                confirm(if (v == "daily") "Use the car's daily charge limit" else "Charge to 100 % for a trip") { vm.setCarChargeLimit(v == "daily") }
        }
        Text(
            when (st?.chargeType) {
                "Partial" -> "The car stops at the daily limit set in the car's own settings."
                "Full" -> "The car charges to 100 %. Switch back to Daily limit after the trip."
                else -> "The car hasn't reported which limit is active."
            } + " The % for the daily limit can only be changed in the car.",
            style = Type.tiny, color = c.muted,
        )
        Divider()

        // 2) LionFlow's limit at any %
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Stop at ${pct.toInt()} %", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = if (s.chargeLimitOn) c.text else c.muted)
                Text("LionFlow stops charging for you", style = Type.tiny, color = c.muted)
            }
            Toggle(s.chargeLimitOn, "LionFlow charge limit") { on ->
                vm.settings.update { it.copy(chargeLimitOn = on, chargeForce = if (on) it.chargeForce else false) }
                // Without our limit, "charge past the car's limit" would just mean 100 %: put the car back on its own limit.
                if (!on && s.chargeForce && st != null && st.chargeType == "Full") vm.setCarChargeLimit(true)
            }
        }
        Slider(
            value = pct, onValueChange = { pct = (Math.round(it / 5f) * 5).toFloat() },
            onValueChangeFinished = { vm.settings.update { it.copy(chargeLimitPct = pct.toInt()) } },
            valueRange = 50f..100f, steps = 9,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.raised),
        )
        Text(
            "Works with any charger, also ones that ignore the car's limit. LionFlow checks every few minutes while charging (more often near the limit), so it can end 1–2 % over. The phone needs internet, and remote control must be set up.",
            style = Type.tiny, color = c.muted,
        )
        Text(
            "Note: this can only stop charging earlier than the car's own daily limit, never later. To stop at a higher number than the car's limit, use the option below.",
            style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.muted,
        )
        if (s.chargeLimitOn) {
            Divider()
            var warn by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Charge past the car's limit", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text("Car charges to 100 %, LionFlow stops it at ${pct.toInt()} %", style = Type.tiny, color = c.muted)
                }
                Toggle(s.chargeForce, "Charge past the car's limit") { on ->
                    if (on) { warn = true }
                    else { vm.settings.update { it.copy(chargeForce = false) }; if (st?.chargeType == "Full") vm.setCarChargeLimit(true) }
                }
            }
            if (s.chargeForce) Text("Active: the car is set to charge to 100 %. If your phone is offline or LionFlow can't run in the background, it will not stop at ${s.chargeLimitPct} %. After a stop, the car stays like this until you unplug, then goes back to its daily limit.", style = Type.tiny, color = c.orangeInk)
            if (warn) androidx.compose.material3.AlertDialog(
                onDismissRequest = { warn = false },
                title = { Text("Charge past the car's limit?") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The car will be told to charge to 100 %, and LionFlow will then stop it at ${pct.toInt()} %.")
                    Text("This only works if your phone has internet, LionFlow is allowed to run in the background and the car answers. If any of these fail, the car keeps charging to 100 %. Doing that often is not good for the battery in the long run.")
                    Text("It can end 1–2 % over your limit. LionFlow is an unofficial app and comes without any warranty. You use this at your own risk.")
                } },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { warn = false; vm.settings.update { it.copy(chargeForce = true) }; if (st != null) vm.setCarChargeLimit(false) }) { Text("I understand, turn on") } },
                dismissButton = { androidx.compose.material3.TextButton(onClick = { warn = false }) { Text("Cancel") } },
            )
        }

        // 3) Only charge between two times
        Divider()
        ChargeWindowSection(vm, s, st)
    }
}

/** "Only charge between" with a start and an end time, picked on a clock. */
@Composable
private fun ChargeWindowSection(vm: AppViewModel, s: AppSettings, st: no.lionflow.app.model.VehicleStatus?) {
    val c = LocalLf.current
    var editing by remember { mutableStateOf<String?>(null) }   // "from" | "to"
    fun hm(m: Int) = no.lionflow.app.ui.l10n.TimeFmt.hm(m / 60, m % 60)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Only charge between set times", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = if (s.chargeWindowOn) c.text else c.muted)
            Text("For example when power is cheapest", style = Type.tiny, color = c.muted)
        }
        Toggle(s.chargeWindowOn, "Only charge between set times") { on -> vm.setChargeWindow(on, s.chargeWindowFrom, s.chargeWindowTo) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("from" to ("From" to s.chargeWindowFrom), "to" to ("To" to s.chargeWindowTo)).forEach { (key, v) ->
            val (label, min) = v
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.raised)
                    .border(1.dp, if (s.chargeWindowOn) c.line2 else c.line, RoundedCornerShape(16.dp))
                    .clickable { editing = key }.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(label, style = Type.tiny, color = c.muted)
                androidx.compose.material3.Text(hm(min), style = Type.title.copy(fontSize = 26.sp), color = if (s.chargeWindowOn) c.text else c.muted)
            }
        }
    }
    if (st != null) WindowEstimate(s, st)
    if (s.chargeWindowOn) Text(
        "The car waits until %s and starts by itself. LionFlow stops charging at %s, and starts it if you plug in between these times. Needs internet on the phone and remote control.".let {
            no.lionflow.app.ui.l10n.L10n.tr(it).replaceFirst("%s", hm(s.chargeWindowFrom)).replaceFirst("%s", hm(s.chargeWindowTo))
        },
        style = Type.tiny, color = c.muted,
    )
    editing?.let { key ->
        val start = if (key == "from") s.chargeWindowFrom else s.chargeWindowTo
        ClockDialog(if (key == "from") "Start charging at" else "Stop charging at", start, onClose = { editing = null }) { m ->
            editing = null
            val from = if (key == "from") m else s.chargeWindowFrom
            val to = if (key == "to") m else s.chargeWindowTo
            // Picking a time also turns the option on.
            vm.setChargeWindow(true, from, to)
        }
    }
}

/** The clock picker (same look as "Leave at" in the trip planner). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ClockDialog(title: String, minutes: Int, onClose: () -> Unit, onSet: (Int) -> Unit) {
    val c = LocalLf.current
    val time = androidx.compose.material3.rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = no.lionflow.app.ui.l10n.TimeFmt.use24)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text(title, color = c.text) },
        text = {
            androidx.compose.material3.TimePicker(state = time, colors = androidx.compose.material3.TimePickerDefaults.colors(
                clockDialColor = c.raised, clockDialSelectedContentColor = c.onAccent, clockDialUnselectedContentColor = c.text,
                selectorColor = c.accent, containerColor = c.card,
                timeSelectorSelectedContainerColor = c.accent, timeSelectorUnselectedContainerColor = c.raised,
                timeSelectorSelectedContentColor = c.onAccent, timeSelectorUnselectedContentColor = c.text,
                periodSelectorSelectedContainerColor = c.accent, periodSelectorUnselectedContainerColor = c.raised,
                periodSelectorSelectedContentColor = c.onAccent, periodSelectorUnselectedContentColor = c.text,
            ))
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSet(time.hour * 60 + time.minute) }) { Text("Set", color = c.accentInk) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel", color = c.muted) } },
    )
}

/**
 * Home charging speed: measured right now when charging on AC, else the typical speed of the last AC sessions
 * in the charging log, else 7.4 kW. Second value: true when it comes from the car's own charging.
 */
private fun homeKw(st: no.lionflow.app.model.VehicleStatus, sessions: List<no.lionflow.app.model.ChargeSession>): Pair<Double, Boolean> {
    if (st.isCharging && st.chargingMode != "Quick") no.lionflow.app.model.ChargePower.reading(st)?.let { return it.kw to true }
    val rates = sessions.filter { it.mode != "Quick" && it.end - it.start > 30 * 60_000L && it.kwh > 1 }
        .sortedByDescending { it.start }.take(10)
        .map { it.kwh / ((it.end - it.start) / 3_600_000.0) }.filter { it in 1.0..23.0 }.sorted()
    return if (rates.size >= 2) rates[rates.size / 2] to true else 7.4 to false
}

/** What the battery will be at when the charging times end. */
@Composable
private fun WindowEstimate(s: AppSettings, st: no.lionflow.app.model.VehicleStatus) {
    val c = LocalLf.current
    val pct = st.batteryPct ?: return
    val sessions by no.lionflow.app.model.ChargeLog.sessions.collectAsState()
    val (kw, known) = homeKw(st, sessions)
    val now = no.lionflow.app.work.ChargeWindow.minutesNow()
    val from = s.chargeWindowFrom; val to = s.chargeWindowTo
    val inside = no.lionflow.app.work.ChargeWindow.inside(now, from, to)
    val minutes = (if (inside) (to - now + 1440) % 1440 else (to - from + 1440) % 1440).let { if (it == 0) 1440 else it }
    val cap = if (s.chargeLimitOn) s.chargeLimitPct.toDouble() else 100.0
    val perMin = kw * 0.9 / no.lionflow.app.model.ChargePower.usableKwh(st) * 100 / 60.0   // ~10 % lost on the way into the battery
    val end = (pct + perMin * minutes).coerceAtMost(cap)
    fun hm(m: Int) = no.lionflow.app.ui.l10n.TimeFmt.hm((m / 60) % 24, m % 60)
    val reachAt = if (pct < cap && pct + perMin * minutes >= cap) ((if (inside) now else from) + ((cap - pct) / perMin).toInt()) % 1440 else null
    val speed = String.format(java.util.Locale.US, "%.1f kW", kw)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.raisedBlue).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("About ${end.toInt()} % at ${hm(to)}", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Text(
            listOfNotNull(
                "From ${pct.toInt()} % now",
                reachAt?.let { "your limit is reached around ${hm(it)}" },
                if (known) "at about $speed (your home charging)" else "at $speed (a typical home charger)",
            ).joinToString(", ") { no.lionflow.app.ui.l10n.L10n.tr(it) } + if (st.plugged) "" else no.lionflow.app.ui.l10n.L10n.tr(". The car isn't plugged in."),
            style = Type.tiny, color = c.muted,
        )
        if (!s.chargeLimitOn) Text("The car may stop earlier at its own daily limit.", style = Type.tiny, color = c.muted)
    }
}
