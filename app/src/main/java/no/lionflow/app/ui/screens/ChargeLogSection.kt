package no.lionflow.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.ChargeLog
import no.lionflow.app.model.ChargePlace
import no.lionflow.app.model.ChargeSession
import no.lionflow.app.model.PlacesConfig
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.ProgressBar
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.components.SectionTitle
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun f1(v: Double) = String.format(Locale.US, if (v < 10) "%.1f" else "%.0f", v)
private fun kwh(v: Double) = String.format(Locale.US, if (v < 100) "%.1f kWh" else "%.0f kWh", v)
private fun money(v: Double, cur: String) = String.format(Locale.US, if (v < 10) "%.1f" else "%.0f", v) + " " + cur
private fun dur(h: Double): String { val m = (h * 60).toInt(); return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }

/** Charging log + home/away totals + cost estimates, shown at the bottom of the Charging tab. */
@Composable
fun ChargeLogSection(ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val sessions by ChargeLog.sessions.collectAsState()
    val active by ChargeLog.active.collectAsState()
    val cfg by ChargeLog.places.collectAsState()
    var showAll by remember { mutableStateOf(false) }
    var editPlaces by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ChargeSession?>(null) }

    SectionTitle("Charging log")

    // ---- totals for the last 30 days ----
    val since = System.currentTimeMillis() - 30 * 86_400_000L
    val recent = sessions.filter { it.start >= since }
    val matches = recent.map { it to ChargeLog.match(it, cfg) }
    val total = recent.sumOf { it.kwh }
    val home = matches.filter { it.second.kind == "home" }.sumOf { it.first.kwh }
    val away = total - home
    val priced = matches.filter { it.second.price != null }
    val cost = priced.sumOf { it.first.kwh * (it.second.price ?: 0.0) }
    LfCard {
        Text("Last 30 days", style = Type.small, color = c.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(kwh(total), style = Type.display.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.weight(1f))
            Text("${recent.size} sessions", style = Type.small, color = c.muted)
        }
        ProgressBar(if (total > 0) (home / total).toFloat() else 0f, c.meter)
        Row {
            Text("Home ${kwh(home)}", style = Type.small, color = c.text)
            Spacer(Modifier.weight(1f))
            Text("Away ${kwh(away)}", style = Type.small, color = c.text2)
        }
        Divider()
        Text(
            when {
                priced.isEmpty() -> "Add prices under Places & prices to see costs"
                priced.size < matches.size -> "≈ ${money(cost, cfg.currency)} for ${priced.size} of ${matches.size} sessions (others have no price)"
                else -> "≈ ${money(cost, cfg.currency)} estimated cost" + if (total > 0) " · ${money(cost / total, cfg.currency)}/kWh average" else ""
            },
            style = Type.small, color = c.text2,
        )
        if (cfg.home?.lat == null) Text("Set your home under Places & prices to split home and away.", style = Type.tiny, color = c.muted)
    }

    // ---- session in progress ----
    active?.let { a ->
        LfCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LfIcon(LfIcons.Bolt, c.accentInk, 18.dp)
                Text("Charging now · since ${SimpleDateFormat("HH:mm", Locale.ENGLISH).format(Date(a.start))}", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
            Text("${a.startPct.toInt()} % → ${a.endPct.toInt()} % · ${kwh(a.kwh)} so far · ${ChargeLog.match(a, cfg).label}", style = Type.small, color = c.text2)
        }
    }

    // ---- sessions ----
    if (sessions.isEmpty() && active == null) {
        Text("No charging sessions yet. LionFlow logs a session each time it sees the car charging (it checks every ~10 minutes while charging).", style = Type.small, color = c.muted)
    } else {
        LfCard {
            val list = if (showAll) sessions else sessions.take(12)
            list.forEachIndexed { i, sess ->
                if (i > 0) Divider()
                SessionRow(sess, cfg) { detail = sess }
            }
            if (sessions.size > 12) {
                Divider()
                Text(if (showAll) "Show fewer" else "Show all ${sessions.size}", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.fillMaxWidth().clickable { showAll = !showAll }.padding(vertical = 8.dp))
            }
        }
    }
    SecondaryButton("Places & prices", modifier = Modifier.fillMaxWidth()) { editPlaces = true }
    Text(
        "Built from the car's own reports, so start and end can be up to ~10 minutes off. Energy is what went into the battery; the meter at the charger reads a few percent more (charging losses).",
        style = Type.tiny, color = c.muted,
    )

    if (editPlaces) PlacesDialog(cfg, ui) { editPlaces = false }
    detail?.let { d -> SessionDialog(d, cfg, s) { detail = null } }
}

@Composable
private fun SessionRow(sess: ChargeSession, cfg: PlacesConfig, onClick: () -> Unit) {
    val c = LocalLf.current
    val m = ChargeLog.match(sess, cfg)
    val day = SimpleDateFormat("EEE d MMM, HH:mm", Locale.ENGLISH).format(Date(sess.start))
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(day, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Spacer(Modifier.weight(1f))
            Text(m.label.take(28), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (m.kind == "home") c.accentInk else c.text2)
        }
        Text("${sess.startPct.toInt()} % → ${sess.endPct.toInt()} % · ${kwh(sess.kwh)} · ${dur(sess.hours)}", style = Type.small, color = c.text2)
        val bits = mutableListOf<String>()
        sess.avgKw?.let { bits += "avg ${f1(it)} kW" }
        sess.peakKw?.let { bits += "peak ${f1(it)} kW" }
        bits += if (sess.mode == "Quick") "DC" else "AC"
        bits += when { m.price == null -> "no price"; m.price == 0.0 -> "free"; else -> "≈ ${money(sess.kwh * m.price, cfg.currency)}" }
        Text(bits.joinToString(" · "), style = Type.tiny, color = c.muted)
    }
}

@Composable
private fun SessionDialog(sess: ChargeSession, cfg: PlacesConfig, s: AppSettings, onClose: () -> Unit) {
    val c = LocalLf.current
    val m = ChargeLog.match(sess, cfg)
    val fmt = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.ENGLISH)
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text(m.label, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                line("Started", fmt.format(Date(sess.start)))
                line("Ended", fmt.format(Date(sess.end)))
                line("Duration", dur(sess.hours))
                line("Battery", "${sess.startPct.toInt()} % → ${sess.endPct.toInt()} %")
                line("Energy", kwh(sess.kwh))
                line("Average", sess.avgKw?.let { "${f1(it)} kW" } ?: "—")
                line("Peak", sess.peakKw?.let { "${f1(it)} kW" } ?: "—")
                line("Type", if (sess.mode == "Quick") "Fast charger (DC)" else "AC")
                line("Place", when (m.kind) { "home" -> "Home"; "known" -> "${m.label} (your charger)"; else -> sess.source ?: "Public / other" })
                line("Address", sess.address ?: if (sess.lat == null) "Car didn't report a position" else "Looking up…")
                line("Cost", when { m.price == null -> "No price set"; m.price == 0.0 -> "Free"; else -> "≈ ${money(sess.kwh * m.price, cfg.currency)} (${m.price} ${cfg.currency}/kWh)" })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close", color = c.accentInk) } },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) { ChargeLog.delete(sess.id); onClose() } else confirmDelete = true }) {
                Text(if (confirmDelete) "Tap again to delete" else "Delete", color = c.danger)
            }
        },
    )
}

@Composable
private fun line(k: String, v: String) {
    val c = LocalLf.current
    Row { Text(k, style = Type.small, color = c.muted, modifier = Modifier.width(110.dp)); Text(v, style = Type.small, color = c.text) }
}

@Composable
private fun PlacesDialog(cfg: PlacesConfig, ui: UiState, onClose: () -> Unit) {
    val c = LocalLf.current
    var editing by remember { mutableStateOf<ChargePlace?>(null) }
    var ac by remember { mutableStateOf(if (cfg.publicAcPrice > 0) cfg.publicAcPrice.toString() else "") }
    var dc by remember { mutableStateOf(if (cfg.publicDcPrice > 0) cfg.publicDcPrice.toString() else "") }
    var cur by remember { mutableStateOf(cfg.currency) }
    val colors = fieldColors()
    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text("Places & prices", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaceLine("Home", cfg.home) { editing = cfg.home ?: ChargePlace("home", "Home", "", null, null, 0.0) }
                Text("Known chargers (up to 5)", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                cfg.known.forEach { k -> PlaceLine(k.name, k) { editing = k } }
                if (cfg.known.size < 5) Text("+ Add a charger", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable { editing = ChargePlace("k" + System.currentTimeMillis(), "", "", null, null, 0.0) }.padding(vertical = 6.dp))
                Divider()
                Text("Other chargers (estimate)", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                OutlinedTextField(ac, { ac = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) }, singleLine = true, colors = colors,
                    label = { Text("AC price per kWh") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(dc, { dc = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) }, singleLine = true, colors = colors,
                    label = { Text("Fast charger (DC) price per kWh") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(cur, { cur = it.take(4) }, singleLine = true, colors = colors, label = { Text("Currency") })
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ChargeLog.savePlaces(ChargeLog.places.value.copy(
                    publicAcPrice = ac.replace(',', '.').toDoubleOrNull() ?: 0.0,
                    publicDcPrice = dc.replace(',', '.').toDoubleOrNull() ?: 0.0,
                    currency = cur.ifBlank { "kr" },
                ))
                onClose()
            }) { Text("Save", color = c.accentInk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = c.muted) } },
    )
    editing?.let { p -> PlaceEditor(p, ui) { editing = null } }
}

@Composable
private fun PlaceLine(title: String, p: ChargePlace?, onClick: () -> Unit) {
    val c = LocalLf.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(title.ifBlank { "Charger" }, style = Type.body, color = c.text)
        Text(
            if (p == null || p.lat == null) "Not set — tap to add"
            else (p.address.ifBlank { "Location set" }) + " · " + if (p.price == 0.0) "free" else "${p.price}/kWh",
            style = Type.tiny, color = c.muted,
        )
    }
}

@Composable
private fun PlaceEditor(p: ChargePlace, ui: UiState, onClose: () -> Unit) {
    val c = LocalLf.current
    val scope = rememberCoroutineScope()
    val isHome = p.id == "home"
    var name by remember { mutableStateOf(p.name) }
    var address by remember { mutableStateOf(p.address) }
    var price by remember { mutableStateOf(if (p.price > 0) p.price.toString() else "") }
    var lat by remember { mutableStateOf(p.lat) }
    var lon by remember { mutableStateOf(p.lon) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val colors = fieldColors()
    val carLat = ui.status?.lat; val carLon = ui.status?.lon

    fun store(la: Double?, lo: Double?) {
        val cfg = ChargeLog.places.value
        val np = p.copy(name = if (isHome) "Home" else name.trim().ifBlank { "Charger" }, address = address.trim(), lat = la, lon = lo,
            price = price.replace(',', '.').toDoubleOrNull() ?: 0.0)
        ChargeLog.savePlaces(if (isHome) cfg.copy(home = np) else cfg.copy(known = (cfg.known.filter { it.id != p.id } + np).take(5)))
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text(if (isHome) "Home" else if (p.name.isBlank()) "Add charger" else p.name, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isHome) OutlinedTextField(name, { name = it.take(30) }, singleLine = true, colors = colors, label = { Text("Name (e.g. Work, Cabin)") })
                OutlinedTextField(address, { address = it.take(120); lat = null; lon = null }, singleLine = true, colors = colors, label = { Text("Address") })
                if (carLat != null && carLon != null) Text("Use the car's current location", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable {
                        lat = carLat; lon = carLon; status = "Using the car's location"
                        scope.launch { try { ChargeLog.reverseGeocode(carLat, carLon)?.let { address = it; lat = carLat; lon = carLon } } catch (_: Exception) { } }
                    }.padding(vertical = 4.dp))
                OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) }, singleLine = true, colors = colors,
                    label = { Text("Price per kWh (empty = free)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text(if (isHome) "Sessions within 50 m of this spot count as home." else "Sessions within 75 m of this spot use this charger's price.", style = Type.tiny, color = c.muted)
                status?.let { Text(it, style = Type.tiny, color = c.text2) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (lat != null && lon != null) store(lat, lon)
                else if (address.isBlank()) status = "Enter an address or use the car's location"
                else {
                    busy = true; status = "Finding the address…"
                    scope.launch {
                        val r = try { ChargeLog.geocode(address) } catch (_: Exception) { null }
                        busy = false
                        if (r == null) status = "Couldn't find that address. Try adding the town, or use the car's location."
                        else store(r.first, r.second)
                    }
                }
            }) { Text(if (busy) "Saving…" else "Save", color = c.accentInk) }
        },
        dismissButton = {
            Row {
                if (!isHome && p.name.isNotBlank()) TextButton(onClick = {
                    val cfg = ChargeLog.places.value; ChargeLog.savePlaces(cfg.copy(known = cfg.known.filter { it.id != p.id })); onClose()
                }) { Text("Remove", color = c.danger) }
                TextButton(onClick = onClose) { Text("Cancel", color = c.muted) }
            }
        },
    )
}

@Composable
private fun fieldColors() = LocalLf.current.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted, cursorColor = c.accentInk,
    )
}
