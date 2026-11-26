package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.HybridSplit
import no.lionflow.app.model.Trip
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
private fun SplitStat(modifier: Modifier, value: String, label: String) {
    val c = LocalLf.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = Type.display.copy(fontSize = 22.sp), color = c.text)
        Text(label, style = Type.tiny, color = c.muted)
    }
}

/** Plug-in hybrids: electric vs engine driving for the period, and engine km since the last oil change. */
@Composable
fun HybridStats(vm: AppViewModel, trips: List<Trip>, allTrips: List<Trip>, s: AppSettings) {
    val c = LocalLf.current
    val r = HybridSplit.of(trips)
    if (r.totalKm <= 0) return
    LfCard {
        Text("Electric and engine driving", style = Type.label, color = c.text)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            SplitStat(Modifier.weight(1f), "${Math.round(r.evShare * 100)} %", "electric")
            SplitStat(Modifier.weight(1f), fmtDistance(r.evKm, s).substringBefore(" "), if (s.distanceUnit == "mi") "mi electric" else "km electric")
            SplitStat(Modifier.weight(1f), fmtDistance(r.engineKm, s).substringBefore(" "), if (s.distanceUnit == "mi") "mi engine" else "km engine")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            SplitStat(Modifier.weight(1f), String.format(Locale.US, "%.1f", r.kwh), "kWh from battery")
            SplitStat(Modifier.weight(2f), r.evKwhPer100?.let { String.format(Locale.US, "%.1f", it) } ?: "—", "kWh/100 km on electric trips")
        }
        Text("Electric km is estimated: trips without fuel are fully electric, and mixed trips are split using your own electric consumption.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(top = 10.dp))
    }

    // Engine km since the last oil change: not an official service counter, just a useful estimate.
    val since = s.oilChangeMillis
    LaunchedEffect(since) {
        if (since > 0) {
            val days = ((System.currentTimeMillis() - since) / 86_400_000L).toInt() + 1
            if (days > 30) vm.loadTrips(days)
        }
    }
    LfCard {
        Text("Engine km since oil change", style = Type.label, color = c.text)
        if (since <= 0) {
            Text("Tell LionFlow when the oil was last changed to count the engine km driven since. This is an estimate, not Peugeot's service counter.", style = Type.small, color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            SecondaryButton("Oil was changed today", Modifier.fillMaxWidth()) { vm.settings.update { it.copy(oilChangeMillis = System.currentTimeMillis()) } }
        } else {
            val after = allTrips.filter { (it.startMillis ?: 0L) >= since }
            val res = HybridSplit.of(after)
            val fmt = SimpleDateFormat("d MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale)
            val oldest = allTrips.mapNotNull { it.startMillis }.minOrNull()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                SplitStat(Modifier.weight(1f), fmtDistance(res.engineKm, s), "on the engine")
                SplitStat(Modifier.weight(1f), fmtDistance(res.totalKm, s), "driven in total")
            }
            Text("Since " + fmt.format(java.util.Date(since)) + if (oldest != null && oldest > since + 86_400_000L) ". Peugeot's trip history only goes back to " + fmt.format(java.util.Date(oldest)) + ", so this may be too low." else ".",
                style = Type.tiny, color = c.muted, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            SecondaryButton("Oil changed again today", Modifier.fillMaxWidth()) { vm.settings.update { it.copy(oilChangeMillis = System.currentTimeMillis()) } }
        }
    }
}
