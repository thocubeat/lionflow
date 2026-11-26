package no.lionflow.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import no.lionflow.app.api.StellantisConfig
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.Brand
import no.lionflow.app.model.Brands
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

/** Which carmaker's account to sign in with, and in which country. Brand names are shown as they are (not swapped). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandPicker(vm: AppViewModel, s: AppSettings, showBrands: Boolean = true) {
    val c = LocalLf.current
    val ctx = LocalContext.current
    val brand = Brand.of(s.brand)
    var pickCountry by remember { mutableStateOf(false) }
    val countries = remember(brand) { StellantisConfig.countries(ctx, brand) }
    val effective = listOf(s.country, "NO", "GB").firstOrNull { it in countries } ?: countries.firstOrNull() ?: s.country

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showBrands) Text("Car brand", style = Type.small, color = c.muted)
        if (showBrands) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Brand.entries.forEach { b ->
                val sel = b == brand
                Row(
                    Modifier.clip(CircleShape).background(if (sel) c.accent else c.card).border(1.dp, if (sel) c.accent else c.line, CircleShape)
                        .clickable { vm.settings.update { it.copy(brand = b.id) } }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    androidx.compose.material3.Text(b.display, style = Type.label, color = if (sel) c.onAccent else c.text)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .clickable { pickCountry = true }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Country of your account", style = Type.small, color = c.muted, modifier = Modifier.weight(1f))
            androidx.compose.material3.Text(countryName(effective), style = Type.label, color = c.text)
        }
    }
    if (pickCountry) {
        AlertDialog(
            onDismissRequest = { pickCountry = false },
            containerColor = c.card,
            title = { Text("Country of your account", color = c.text) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    countries.sortedBy { countryName(it) }.forEach { cc ->
                        androidx.compose.material3.Text(
                            countryName(cc), style = Type.body, color = if (cc == effective) c.accentInk else c.text,
                            fontWeight = if (cc == effective) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().clickable { vm.settings.update { it.copy(country = cc) }; pickCountry = false }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickCountry = false }) { Text("Close", color = c.accentInk) } },
        )
    }
}

private fun countryName(code: String): String = java.util.Locale("", code).displayCountry.ifBlank { code }

/** Plain-words list of what needs a subscription for the chosen brand. */
@Composable
fun SubscriptionNotice() {
    val c = LocalLf.current
    val b = Brands.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LfIcon(LfIcons.Shield, c.orangeInk, 18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What needs a subscription", style = Type.label, color = c.text)
            Text("This is decided by ${b.display}, not by LionFlow.", style = Type.tiny, color = c.muted)
            Text("Status, battery, range, trips and service: your ${b.app} account is enough.", style = Type.tiny.copy(lineHeight = 17.sp), color = c.text2)
            Text("Charging control and pre-heating (electric and plug-in hybrid cars): ${b.eRemotePack}.", style = Type.tiny.copy(lineHeight = 17.sp), color = c.text2)
            Text("Lock, unlock, lights and horn: ${b.doorsPack}.", style = Type.tiny.copy(lineHeight = 17.sp), color = c.text2)
            Text("Which pack is included, and for how long, depends on the model, country and order date. Check and activate it in ${b.app}. LionFlow can't activate it for you.", style = Type.tiny.copy(lineHeight = 17.sp), color = c.muted)
        }
    }
}

/** One line shown next to a remote function when remote control isn't working yet. */
@Composable
fun NeedsPackHint(electric: Boolean) {
    val c = LocalLf.current
    val b = Brands.current
    Text(
        "Needs " + (if (electric) b.eRemotePack else b.doorsPack) + " from ${b.display}. Activate it in ${b.app}, then set up remote control in Settings.",
        style = Type.tiny, color = c.muted,
    )
}

/** Step 1 of signing in: one large square button per brand. */
@Composable
fun BrandGrid(onPick: (Brand) -> Unit) {
    val c = LocalLf.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Brand.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { b ->
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(c.card)
                            .border(1.dp, c.line, RoundedCornerShape(24.dp)).clickable(role = Role.Button) { onPick(b) },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text(b.display, style = Type.title.copy(fontSize = 22.sp), color = c.text)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
