package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.Brand
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

/**
 * The step after "Try without an account": which kind of sample car to look at (electric, plug-in hybrid, or petrol
 * or diesel) and of which brand. The brand decides the names the app uses while the demo runs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoSetup(vm: AppViewModel, settings: AppSettings, onBack: () -> Unit) {
    val c = LocalLf.current
    var kind by rememberSaveable { mutableStateOf("electric") }
    var brand by rememberSaveable { mutableStateOf(Brand.of(settings.brand).id) }

    Text("Try a sample car", style = Type.title, color = c.text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text("Sample car and made-up data. Nothing is sent to a real car.", style = Type.small, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

    Text("Kind of car", style = Type.label, color = c.text)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("electric", "Electric", "Battery, charging and pre-heating"),
            Triple("hybrid", "Plug-in hybrid", "Battery for short trips, fuel for long ones"),
            Triple("fuel", "Petrol / diesel", "Fuel level, fuel cost and statistics"),
        ).forEach { (id, title, note) ->
            val on = id == kind
            val shape = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(c.card).border(if (on) 2.dp else 1.dp, if (on) c.accentInk else c.line, shape)
                    .clickable(role = Role.RadioButton) { kind = id }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = Type.label, color = c.text)
                    Text(note, style = Type.tiny, color = c.muted)
                }
                Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) c.accent else c.raised), contentAlignment = Alignment.Center) {
                    if (on) LfIcon(LfIcons.Check, c.onAccent, 14.dp)
                }
            }
        }
    }

    Text("Brand", style = Type.label, color = c.text)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Brand.entries.forEach { b ->
            val on = b.id == brand
            // Brand names are shown as they are, in every language.
            androidx.compose.material3.Text(
                b.display, style = Type.label, color = if (on) c.onAccent else c.text,
                modifier = Modifier.clip(CircleShape).background(if (on) c.accent else c.card).border(1.dp, if (on) c.accent else c.line, CircleShape)
                    .clickable(role = Role.RadioButton) { brand = b.id }.padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }

    PrimaryButton("Start the demo", Modifier.fillMaxWidth()) { vm.startDemo(kind, brand) }
    SecondaryButton("Back", Modifier.fillMaxWidth(), onClick = onBack)
}
