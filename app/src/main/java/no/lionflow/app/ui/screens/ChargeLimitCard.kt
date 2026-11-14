package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
            Toggle(s.chargeLimitOn, "LionFlow charge limit") { on -> vm.settings.update { it.copy(chargeLimitOn = on) } }
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
    }
}
