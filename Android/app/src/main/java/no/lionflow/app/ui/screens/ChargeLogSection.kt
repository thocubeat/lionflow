package no.lionflow.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
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
import no.lionflow.app.ui.l10n.Text
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
import androidx.compose.ui.unit.sp
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

    CostCard(sessions, cfg)

    // ---- session in progress ----
    active?.let { a ->
        LfCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LfIcon(LfIcons.Bolt, c.accentInk, 18.dp)
                Text("Charging now · since ${SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale).format(Date(a.start))}", style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
            Text("${a.startPct.toInt()} % → ${a.endPct.toInt()} % · ${kwh(a.kwh)} so far · ${ChargeLog.match(a, cfg).label}", style = Type.small, color = c.text2)
        }
    }

    // ---- sessions ----
    if (sessions.isEmpty()) {
        if (active == null) Text("No charging sessions yet. LionFlow logs a session each time it sees the car charging (it checks every ~10 minutes while charging).", style = Type.small, color = c.muted)
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
    val day = SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("EEE d MMM, HH:mm"), no.lionflow.app.ui.l10n.L10n.locale).format(Date(sess.start))
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SessionDialog(sess: ChargeSession, cfg: PlacesConfig, s: AppSettings, onClose: () -> Unit) {
    val c = LocalLf.current
    val m = ChargeLog.match(sess, cfg)
    val day = SimpleDateFormat("EEE d MMM yyyy", no.lionflow.app.ui.l10n.L10n.locale)
    val hm = SimpleDateFormat(no.lionflow.app.ui.l10n.TimeFmt.pattern("HH:mm"), no.lionflow.app.ui.l10n.L10n.locale)
    var confirmDelete by remember { mutableStateOf(false) }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val home = m.kind == "home"
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose, sheetState = sheet, containerColor = c.bg,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line2)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header: place + date
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(if (home) c.okBg else c.raisedBlue), contentAlignment = Alignment.Center) {
                    LfIcon(if (home) LfIcons.Home else LfIcons.Bolt, if (home) c.accentInk else c.blueInk, 22.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text(m.label, style = Type.display.copy(fontSize = 24.sp), color = c.text, maxLines = 1)
                    Text("${day.format(Date(sess.start))} · ${hm.format(Date(sess.start))}–${hm.format(Date(sess.end))}", style = Type.small, color = c.muted)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.raised).clickable(onClick = onClose), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.text2, 18.dp) }
            }

            // Hero: energy + cost, battery bar
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Energy", style = Type.tiny, color = c.muted)
                        Text(kwh(sess.kwh), style = Type.display.copy(fontSize = 34.sp, fontWeight = FontWeight.SemiBold), color = c.text)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Cost", style = Type.tiny, color = c.muted)
                        Text(when { m.price == null -> "—"; m.price == 0.0 -> "Free"; else -> "≈ " + money(sess.kwh * m.price, cfg.currency) }, style = Type.display.copy(fontSize = 26.sp), color = c.accentInk)
                    }
                }
                // battery from → to
                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.bar)) {
                    val a = (sess.startPct / 100).toFloat().coerceIn(0f, 1f); val b = (sess.endPct / 100).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth(b).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.meter))
                    Box(Modifier.fillMaxWidth(a).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.line2))
                }
                Row {
                    Text("${sess.startPct.toInt()} %", style = Type.small, color = c.muted)
                    Spacer(Modifier.weight(1f))
                    Text("+${(sess.endPct - sess.startPct).toInt()} %", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Spacer(Modifier.weight(1f))
                    Text("${sess.endPct.toInt()} %", style = Type.small, color = c.text)
                }
                if (m.price != null && m.price > 0) Text("${m.price} ${cfg.currency}/kWh", style = Type.tiny, color = c.muted)
            }

            // Stat grid
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), "Duration", dur(sess.hours))
                StatTile(Modifier.weight(1f), "Type", if (sess.mode == "Quick") "Fast (DC)" else "AC")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(Modifier.weight(1f), "Average", sess.avgKw?.let { "${f1(it)} kW" } ?: "—")
                StatTile(Modifier.weight(1f), "Peak", sess.peakKw?.let { "${f1(it)} kW" } ?: "—")
            }

            // Where
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LfIcon(LfIcons.Pin, c.muted, 20.dp)
                Column(Modifier.weight(1f)) {
                    Text(when (m.kind) { "home" -> "Home"; "known" -> "${m.label} (your charger)"; else -> sess.source ?: "Public / other" }, style = Type.label, color = c.text)
                    Text(sess.address ?: if (sess.lat == null) "The car didn't report a position" else "Looking up address…", style = Type.small, color = c.muted)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (confirmDelete) { ChargeLog.delete(sess.id); onClose() } else confirmDelete = true }) {
                    Text(if (confirmDelete) "Tap again to delete" else "Delete session", color = c.danger)
                }
                Spacer(Modifier.weight(1f))
                no.lionflow.app.ui.components.PrimaryButton("Done") { onClose() }
            }
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier, label: String, value: String) {
    val c = LocalLf.current
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = Type.tiny, color = c.muted)
        Text(value, style = Type.stat.copy(fontSize = 20.sp), color = c.text)
    }
}

@Composable
private fun line(k: String, v: String) {
    val c = LocalLf.current
    Row { Text(k, style = Type.small, color = c.muted, modifier = Modifier.width(110.dp)); Text(v, style = Type.small, color = c.text) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlacesDialog(cfg: PlacesConfig, ui: UiState, onClose: () -> Unit) {
    val c = LocalLf.current
    var editing by remember { mutableStateOf<ChargePlace?>(null) }
    var ac by remember { mutableStateOf(if (cfg.publicAcPrice > 0) cfg.publicAcPrice.toString() else "") }
    var dc by remember { mutableStateOf(if (cfg.publicDcPrice > 0) cfg.publicDcPrice.toString() else "") }
    var cur by remember { mutableStateOf(cfg.currency) }
    val sheet = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun save() {
        ChargeLog.savePlaces(ChargeLog.places.value.copy(
            publicAcPrice = ac.replace(',', '.').toDoubleOrNull() ?: 0.0,
            publicDcPrice = dc.replace(',', '.').toDoubleOrNull() ?: 0.0,
            currency = cur.ifBlank { "kr" },
        ))
        onClose()
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onClose, sheetState = sheet, containerColor = c.bg,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line2)) },
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Places & prices", style = Type.display.copy(fontSize = 26.sp), color = c.text)
                    Text("Used to split home and away charging, and to estimate what it costs.", style = Type.small, color = c.muted)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.raised).clickable(onClick = onClose), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.text2, 18.dp) }
            }

            // Your places
            SheetLabel("Your places")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp))) {
                PlaceRow(LfIcons.Home, c.accentInk, c.okBg, "Home", cfg.home, cfg.currency) { editing = cfg.home ?: ChargePlace("home", "Home", "", null, null, 0.0) }
                cfg.known.forEach { k ->
                    Divider()
                    PlaceRow(LfIcons.Pin, c.blueInk, c.raisedBlue, k.name, k, cfg.currency) { editing = k }
                }
                if (cfg.known.size < 5) {
                    Divider()
                    Row(Modifier.fillMaxWidth().clickable { editing = ChargePlace("k" + System.currentTimeMillis(), "", "", null, null, 0.0) }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).border(1.5.dp, c.line2, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Plus, c.accentInk, 18.dp) }
                        Column(Modifier.weight(1f)) {
                            Text("Add a charger", style = Type.label, color = c.accentInk)
                            Text("Work, cabin, a friend's place · ${5 - cfg.known.size} left", style = Type.tiny, color = c.muted)
                        }
                    }
                }
            }

            // Public charging
            SheetLabel("Other chargers")
            Text("An estimate for charging anywhere else. Leave empty if you don't know.", style = Type.tiny, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PriceBox(Modifier.weight(1f), "Normal (AC)", ac, cur) { ac = it }
                PriceBox(Modifier.weight(1f), "Fast (DC)", dc, cur) { dc = it }
            }

            // Currency
            SheetLabel("Currency")
            (ChargeLog.CURRENCIES + cfg.currency).distinct().chunked(5).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { opt ->
                        val sel = cur == opt
                        Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent else c.card)
                            .border(1.dp, if (sel) c.accent else c.line, RoundedCornerShape(14.dp)).clickable { cur = opt }, contentAlignment = Alignment.Center) {
                            Text(opt, style = Type.label, color = if (sel) c.onAccent else c.text)
                        }
                    }
                    repeat(5 - line.size) { Box(Modifier.weight(1f)) }
                }
            }

            no.lionflow.app.ui.components.PrimaryButton("Save", Modifier.fillMaxWidth().padding(top = 6.dp)) { save() }
        }
    }
    editing?.let { p -> PlaceEditor(p, ui) { editing = null } }
}

@Composable
private fun SheetLabel(text: String) {
    val c = LocalLf.current
    Text(text.uppercase(), style = Type.tiny.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = c.muted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun PlaceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color,
                     title: String, p: ChargePlace?, currency: String, onClick: () -> Unit) {
    val c = LocalLf.current
    val set = p != null && p.lat != null
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(14.dp)).background(bg), contentAlignment = Alignment.Center) { LfIcon(icon, tint, 20.dp) }
        Column(Modifier.weight(1f)) {
            Text(title.ifBlank { "Charger" }, style = Type.label, color = c.text)
            Text(if (set) p!!.address.ifBlank { "Location set" } else "Not set — tap to add", style = Type.tiny, color = if (set) c.muted else c.accentInk, maxLines = 1)
        }
        if (set) Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.raised).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text(if (p!!.price == 0.0) "Free" else "${p.price} $currency/kWh", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        LfIcon(LfIcons.Chevron, c.muted, 16.dp)
    }
}

@Composable
private fun PriceBox(modifier: Modifier, label: String, value: String, currency: String, onChange: (String) -> Unit) {
    val c = LocalLf.current
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = Type.tiny, color = c.muted)
        Row(verticalAlignment = Alignment.Bottom) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = { onChange(it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6)) },
                singleLine = true,
                textStyle = Type.display.copy(fontSize = 24.sp, color = c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accentInk),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (value.isEmpty()) Text("0.00", style = Type.display.copy(fontSize = 24.sp), color = c.line2); inner() } },
            )
            Text("$currency/kWh", style = Type.tiny, color = c.muted, modifier = Modifier.padding(bottom = 4.dp))
        }
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
                if (!isHome) OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = name, onValueChange = { name = it.take(30) }, singleLine = true, colors = colors, label = { Text("Name (e.g. Work, Cabin)") })
                OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = address, onValueChange = { address = it.take(120); lat = null; lon = null }, singleLine = true, colors = colors, label = { Text("Address") })
                if (carLat != null && carLon != null) Text("Use the car's current location", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable {
                        lat = carLat; lon = carLon; status = "Using the car's location"
                        scope.launch { try { ChargeLog.reverseGeocode(carLat, carLon)?.let { address = it; lat = carLat; lon = carLon } } catch (_: Exception) { } }
                    }.padding(vertical = 4.dp))
                OutlinedTextField(modifier = Modifier.fillMaxWidth(), value = price, onValueChange = { price = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(6) }, singleLine = true, colors = colors,
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


/** Registered charging costs per period (sessions with a known price). Weeks start on Monday. */
@Composable
private fun CostCard(sessions: List<ChargeSession>, cfg: PlacesConfig) {
    val c = LocalLf.current
    if (sessions.isEmpty()) return
    fun start(field: Int, back: Int): Long = java.util.Calendar.getInstance().apply {
        firstDayOfWeek = java.util.Calendar.MONDAY
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        when (field) {
            java.util.Calendar.WEEK_OF_YEAR -> set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
            java.util.Calendar.MONTH -> set(java.util.Calendar.DAY_OF_MONTH, 1)
            java.util.Calendar.YEAR -> set(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        add(field, -back)
    }.timeInMillis
    val W = java.util.Calendar.WEEK_OF_YEAR; val M = java.util.Calendar.MONTH; val Y = java.util.Calendar.YEAR
    val far = Long.MAX_VALUE
    val periods = mutableListOf(
        Triple("This week", start(W, 0), far), Triple("Last week", start(W, 1), start(W, 0)),
        Triple("This month", start(M, 0), far), Triple("Last month", start(M, 1), start(M, 0)),
        Triple("This year", start(Y, 0), far),
    )
    if (sessions.any { it.start in start(Y, 1) until start(Y, 0) }) periods += Triple("Last year", start(Y, 1), start(Y, 0))
    LfCard {
        Text("Charging costs", style = Type.label, color = c.text)
        periods.forEachIndexed { i, (label, from, to) ->
            if (i > 0) Divider()
            val inP = sessions.filter { it.start in from until to }
            val kwhSum = inP.sumOf { it.kwh }
            val priced = inP.map { it to ChargeLog.match(it, cfg).price }
            val cost = priced.sumOf { (s, p) -> s.kwh * (p ?: 0.0) }
            val unpriced = priced.count { it.second == null }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = Type.body, color = c.text)
                    Text(kwh(kwhSum) + if (unpriced > 0) " · $unpriced without price" else "", style = Type.tiny, color = c.muted)
                }
                Text(if (inP.isEmpty()) "—" else money(cost, cfg.currency), style = Type.stat.copy(fontSize = 18.sp), color = c.text)
            }
        }
    }
}
