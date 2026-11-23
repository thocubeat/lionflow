package no.lionflow.app.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.plan.Place
import no.lionflow.app.plan.PlanCharger
import no.lionflow.app.plan.Planner
import no.lionflow.app.plan.Stop
import no.lionflow.app.ui.Busy
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.l10n.TimeFmt
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------- small helpers ----------------

private fun strokeIcon(path: String, filled: Boolean = false): ImageVector =
    ImageVector.Builder("plan", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = addPathNodes(path),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

private const val STAR = "M12 3.5l2.6 5.4 5.9.8-4.3 4.1 1 5.9-5.2-2.8-5.2 2.8 1-5.9-4.3-4.1 5.9-.8z"
private val IconStar = strokeIcon(STAR)
private val IconStarOn = strokeIcon(STAR, filled = true)
private val IconSwap = strokeIcon("M7 4v16M7 4L3.5 7.5M7 4l3.5 3.5M17 20V4M17 20l-3.5-3.5M17 20l3.5-3.5")

/** Names and other data are shown as they are, never run through the translations. */
@Composable
private fun Plain(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, maxLines: Int = 1, align: TextAlign? = null) =
    androidx.compose.material3.Text(text, modifier = modifier, color = color, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)

/** Keeps touches on a panel from reaching the map under it. */
fun Modifier.blockTouches(): Modifier = this.pointerInput(Unit) { detectTapGestures { } }

fun placeName(p: Place): String = if (p.detail.isEmpty() && (p.name == "Car's position" || p.name == "My position" || p.name == "Where the car was")) L10n.tr(p.name) else p.name

private fun clockAt(p: Planner, minutes: Double): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = p.departMillis(); add(java.util.Calendar.MINUTE, minutes.roundToInt()) }
    return TimeFmt.hm(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

/** "1 h 5 min · arrive 12:05" for a stop on the way. */
private fun arriveLine(p: Planner, minutes: Double?): String? = minutes?.let {
    dur(it) + " · " + L10n.tr("arrive {0}").replace("{0}", clockAt(p, it))
}

private fun viaTag(stay: Int, dest: Boolean = false, pct: Double? = null): String =
    L10n.tr(if (dest) "Destination" else "Via") + (if (stay > 0) " · $stay min" else "") + (pct?.let { " · ${it.roundToInt()} %" } ?: "")

private fun dur(minutes: Double): String {
    val m = minutes.roundToInt().coerceAtLeast(0)
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

private fun money(v: Double, cur: String): String =
    if (cur == "€" || cur == "£" || cur == "CHF") String.format(Locale.US, "%.2f %s", v, cur) else "${v.roundToInt()} $cur"

private fun perKwh(c: PlanCharger): String? = c.price?.let { String.format(Locale.US, "%.2f %s/kWh", it, c.currency) }

private fun chargerLine(c: PlanCharger, s: AppSettings, price: Boolean = true): String = listOfNotNull(
    c.operator.takeIf { it.isNotBlank() && !c.name.contains(it, ignoreCase = true) },
    if (c.kw > 0) "${c.kw.roundToInt()} kW" else null,
    c.free?.let { f -> if (c.points >= f && c.points > 0) L10n.tr("$f of ${c.points} free") else L10n.tr("$f free") },
    fmtDistance(c.km, s),
    if (price) perKwh(c) else null,
).joinToString(" · ")

// ---------------- the form: addresses and battery numbers ----------------

@Composable
fun PlanForm(p: Planner, ui: UiState, s: AppSettings) {
    val c = LocalLf.current
    val focus = LocalFocusManager.current
    // The address being typed: 0 = from, 1 = to, 2.. = a stop on the way, -1 = none.
    var field by remember { mutableStateOf(if (p.to == null) 1 else -1) }
    var query by remember { mutableStateOf("") }
    val requester = remember { FocusRequester() }
    LaunchedEffect(field) { if (field >= 0) try { requester.requestFocus() } catch (_: Exception) { } }

    fun open(which: Int) { field = which; query = ""; p.clearSearch() }
    fun done() { query = ""; p.clearSearch(); field = if (p.to == null) 1 else -1; if (field < 0) focus.clearFocus() }
    fun pick(place: Place?) { if (field == 0) p.from = place else if (field == 1) p.to = place else p.setVia(field - 2, place); done() }

    val carHere = ui.status?.lat != null
    val fromText = p.from?.let { placeName(it) } ?: L10n.tr(if (carHere) "Car's position" else "My position")

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AddressRow(LfIcons.Car, c.orangeInk, "From", fromText, field == 0, query, requester, { query = it; p.search(it) }, { open(0) }, { done() })
                if (p.from != null && field < 0) Text(
                    if (carHere) "Start from the car's position now" else "Start from my position now",
                    style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) { p.startAtCar() }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                // Stops on the way and the destination: hold one and drag it to change the order.
                val count = p.vias.size + 1
                var dragging by remember { mutableStateOf(-1) }
                var dragY by remember { mutableStateOf(0f) }
                var rowH by remember { mutableStateOf(1f) }
                val gap = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.toPx() }
                val canDrag = field < 0 && p.to != null && p.vias.isNotEmpty()
                fun target(): Int = if (dragging < 0) -1 else (dragging + Math.round(dragY / (rowH + gap))).coerceIn(0, count - 1)
                for (pos in 0 until count) {
                    val t = target()
                    // While dragging, the other rows make room for the one being moved.
                    val shift = when {
                        dragging < 0 || pos == dragging -> 0f
                        pos in (dragging + 1)..t -> -(rowH + gap)
                        pos in t until dragging -> rowH + gap
                        else -> 0f
                    }
                    val mod = Modifier
                        .onGloballyPositioned { rowH = it.size.height.toFloat() }
                        .zIndex(if (pos == dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (pos == dragging) dragY else shift
                            if (pos == dragging) { scaleX = 1.02f; scaleY = 1.02f; shadowElevation = 12f }
                        }
                        .then(if (!canDrag) Modifier else Modifier.pointerInput(count, p.to) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = pos; dragY = 0f },
                                onDrag = { ch, d -> ch.consume(); dragY += d.y },
                                onDragEnd = { val to = target(); val from = dragging; dragging = -1; dragY = 0f; if (to >= 0 && to != from) p.moveStop(from, to) },
                                onDragCancel = { dragging = -1; dragY = 0f },
                            )
                        })
                    Box(mod) {
                        if (pos < p.vias.size) {
                            val i = pos; val v = p.vias[i]
                            AddressRow(LfIcons.Pin, c.muted, "Via", v?.name, field == i + 2, query, requester, { query = it; p.search(it) }, { open(i + 2) },
                                { if (v == null) p.removeVia(i); done() }, onRemove = { p.removeVia(i); done() })
                        } else {
                            AddressRow(LfIcons.Pin, c.accentInk, "Where to?", p.to?.name, field == 1, query, requester, { query = it; p.search(it) }, { open(1) }, { if (p.to != null) done() else { query = ""; p.clearSearch() } })
                        }
                    }
                }
                if (canDrag) Text("Hold and drag a stop to change the order", style = Type.tiny, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
                if (field < 0 && p.vias.size < 3) Text(
                    "Add a stop on the way", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.clickable(role = Role.Button) { p.addVia(); open(p.vias.size + 1) }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(c.raised).clickable(role = Role.Button) { p.swapEnds() }
                    .semantics { contentDescription = L10n.tr("Swap start and destination") },
                contentAlignment = Alignment.Center,
            ) { LfIcon(IconSwap, c.text2, 18.dp) }
        }

        if (field >= 0) {
            val q = query.trim()
            val rows = if (q.length < 2) p.saved + p.recent.filterNot { r -> p.saved.any { it.name == r.name && it.detail == r.detail } }
            else (p.saved.filter { it.name.contains(q, true) || it.detail.contains(q, true) } + p.results).distinctBy { it.name + "|" + it.detail }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                if (field == 0 && q.length < 2) SuggestRow(LfIcons.Car, c.orangeInk, L10n.tr(if (carHere) "Car's position" else "My position"), "", null, { }) { pick(null) }
                for (place in rows.take(8)) {
                    val saved = p.isSaved(place)
                    val recentOnly = !saved && q.length < 2 && p.recent.any { it.name == place.name && it.detail == place.detail }
                    SuggestRow(LfIcons.Pin, c.muted, place.name, place.detail, saved, { p.toggleSaved(place) }, if (recentOnly) ({ p.removeRecent(place) }) else null) { pick(place) }
                }
                val note = when {
                    q.length >= 2 && rows.isEmpty() && p.searching -> "Searching…"
                    q.length >= 2 && rows.isEmpty() -> "No places found"
                    q.length < 2 && rows.isEmpty() -> "Type an address or a place. Tap the star to save one."
                    else -> null
                }
                if (note != null) Text(note, style = Type.small, color = c.muted, modifier = Modifier.padding(14.dp))
            }
        } else {
            ViaStays(p)
            BatteryOptions(p)
            PrimaryButton(if (p.plan != null) "Plan again" else "Plan trip", Modifier.fillMaxWidth(), icon = LfIcons.Route, enabled = p.to != null && p.busy == null) { p.go() }
            if (p.plan == null && p.busy == null) SavedTrips(p, s)
        }
        p.busy?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Busy(); Text(msg, style = Type.small, color = c.text2) }
        }
        p.error?.let { Text(it, style = Type.small, color = c.danger) }
    }
}

@Composable
private fun SaveChoice(title: String, text: String, onClick: () -> Unit) {
    val c = LocalLf.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.raised).clickable(role = Role.Button, onClick = onClick).padding(14.dp)) {
        Text(title, style = Type.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        Text(text, style = Type.small, color = c.muted)
    }
}

/** Trips saved earlier: tap one to plan it again, the cross deletes it. */
@Composable
private fun SavedTrips(p: Planner, s: AppSettings) {
    val c = LocalLf.current
    val list = p.savedTrips.filter { it.setup != null }
    if (list.isEmpty()) return
    Text("Saved trips", style = Type.label, color = c.text, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).padding(vertical = 4.dp)) {
        for (t in list) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { p.openTrip(t) }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LfIcon(LfIcons.Route, c.accentInk, 18.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Plain(t.name, Type.body, c.text)
                    Plain(fmtDistance(t.totalKm, s) + " · " + L10n.tr(when (t.stops.size) { 0 -> "no charging stops"; 1 -> "1 charging stop"; else -> "${t.stops.size} charging stops" }), Type.tiny, c.muted)
                }
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { p.deleteTrip(t.id) }
                    .semantics { contentDescription = L10n.tr("Delete") }, contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.muted, 16.dp) }
            }
        }
    }
}

@Composable
private fun AddressRow(
    icon: ImageVector, tint: Color, hint: String, value: String?, active: Boolean, query: String, requester: FocusRequester,
    onQuery: (String) -> Unit, onOpen: () -> Unit, onClose: () -> Unit, onRemove: (() -> Unit)? = null,
) {
    val c = LocalLf.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape).background(c.raised).border(1.dp, if (active) c.accentInk else Color.Transparent, shape)
            .clickable(enabled = !active, role = Role.Button, onClick = onOpen).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LfIcon(icon, tint, 18.dp)
        if (active) {
            BasicTextField(
                value = query, onValueChange = { onQuery(it.take(80)) }, singleLine = true,
                textStyle = Type.body.copy(color = c.text), cursorBrush = SolidColor(c.accentInk),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(requester),
                decorationBox = { inner -> Box { if (query.isEmpty()) Plain(value ?: L10n.tr(hint), Type.body, c.muted); inner() } },
            )
            LfIcon(LfIcons.Close, c.muted, 18.dp, "Close", Modifier.clickable(onClick = onClose).padding(4.dp))
        } else if (value != null) Plain(value, Type.body, c.text, Modifier.weight(1f))
        else Text(hint, style = Type.body, color = c.muted, modifier = Modifier.weight(1f))
        if (!active && onRemove != null) LfIcon(LfIcons.Close, c.muted, 18.dp, "Remove this stop", Modifier.clickable(onClick = onRemove).padding(4.dp))
    }
}

@Composable
private fun SuggestRow(icon: ImageVector, tint: Color, name: String, detail: String, saved: Boolean?, onStar: () -> Unit, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalLf.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LfIcon(icon, tint, 18.dp)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Plain(name, Type.body, c.text)
            if (detail.isNotBlank()) Plain(detail, Type.tiny, c.muted)
        }
        if (onRemove != null) Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = L10n.tr("Remove from recent places") },
            contentAlignment = Alignment.Center,
        ) { LfIcon(LfIcons.Close, c.muted, 16.dp) }
        if (saved != null) Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onStar)
                .semantics { contentDescription = L10n.tr(if (saved) "Remove from saved places" else "Save this place") },
            contentAlignment = Alignment.Center,
        ) { LfIcon(if (saved) IconStarOn else IconStar, if (saved) c.orangeInk else c.icon, 18.dp) }
    }
}

/** Start charge, the lowest the battery may go, and how full to charge at a stop. */
@Composable
private fun BatteryOptions(p: Planner) {
    val c = LocalLf.current
    val start = p.startNow()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).padding(vertical = 4.dp)) {
        // Round trip: plan the drive back to the start too.
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Round trip", style = Type.body, color = c.text2)
                Text("Drive back to the start afterwards", style = Type.tiny, color = c.muted)
            }
            no.lionflow.app.ui.components.Toggle(p.roundTrip, "Round trip") { p.setRound(it) }
        }
        if (p.roundTrip) {
            val m = p.destStay
            OptionRow(p.to?.let { "Stay at ${placeName(it)}" } ?: "Stay at the destination", stayText(m),
                { p.changeDestStay(stayLess(m)) }, { p.changeDestStay(stayMore(m)) })
        }
        val dep = p.departAt?.takeIf { it > System.currentTimeMillis() }
        val leaveText = if (dep == null) L10n.tr("Now") else {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = dep }
            val today = java.util.Calendar.getInstance()
            val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) && cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR)
            (if (sameDay) "" else java.text.SimpleDateFormat("EEE ", L10n.locale).format(java.util.Date(dep))) +
                TimeFmt.hm(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        }
        var pickTime by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { pickTime = true }.padding(start = 14.dp, end = 14.dp).height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Leave at", style = Type.body, color = c.text2, modifier = Modifier.weight(1f))
            Plain(leaveText, Type.label, c.accentInk)
        }
        if (pickTime) DepartPicker(p.departAt?.takeIf { it > System.currentTimeMillis() }, { p.setDepart(it); pickTime = false }, { pickTime = false })
        OptionRow(if (p.startPct == null) "Start with (car now)" else "Start with", "$start %", { p.setStart(((start - 1) / 5) * 5) }, { p.setStart((start / 5 + 1) * 5) })
        if (p.startPct != null) Text(
            "Use the car's battery % now", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
            modifier = Modifier.clickable(role = Role.Button) { p.setStart(null) }.padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
        )
        OptionRow("Never below", "${p.minPct} %", { p.setMin(p.minPct - 5) }, { p.setMin(p.minPct + 5) })
        OptionRow("Charge to", "${p.targetPct} %", { p.setTarget(p.targetPct - 5) }, { p.setTarget(p.targetPct + 5) })
    }
}

/** A stop on the way. With a stay, a fast charger within 1 km is suggested: charge while you are there. */
@Composable
private fun ViaRow(p: Planner, v: Place, stay: Int, s: AppSettings, onShow: (Double, Double) -> Unit) {
    val c = LocalLf.current
    val dest = p.planRound && v === p.planVias.lastOrNull()
    // The battery when you get there.
    val pct = p.route?.let { r -> p.plan?.socAt?.getOrNull(no.lionflow.app.plan.Geo.project(r, v.lat, v.lon).first) }
    EndRow(if (dest) c.accentInk else c.muted, placeName(v), v.detail, viaTag(stay, dest, pct), arriveLine(p, p.minutesTo(v.lat, v.lon))) { onShow(v.lat, v.lon) }
    if (stay <= 0) return
    val near = p.chargers.map { it to no.lionflow.app.plan.Geo.km(v.lat, v.lon, it.lat, it.lon) }.filter { it.second < 1.0 }.minByOrNull { it.second } ?: return
    val ch = near.first
    if (p.plan?.stops?.any { it.charger.id == ch.id } == true) return     // already a charging stop
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.okBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Charge while you stay: ${ch.name} (${fmtDistance(near.second, s)} away)", style = Type.small, color = c.okText)
        Text("Charge there for $stay min", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
            modifier = Modifier.clickable(role = Role.Button) { p.chargeDuringStay(ch.id, stay) })
    }
}

/** How long to stay at each stop on the way. */
@Composable
private fun ViaStays(p: Planner) {
    val c = LocalLf.current
    val rows = p.vias.withIndex().filter { it.value != null }
    if (rows.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.raised).padding(vertical = 4.dp)) {
        for ((i, v) in rows) {
            val m = p.stays.getOrElse(i) { 0 }
            OptionRow("Stay at ${placeName(v!!)}", stayText(m), { p.setStay(i, stayLess(m)) }, { p.setStay(i, stayMore(m)) })
        }
    }
}

/** Stay steps: 5 min up to 30, 10 min up to 1 h, then 1 h 30 min and half hours (2 h, 2 h 30 min …). */
private fun stayMore(m: Int) = when { m < 30 -> (m / 5 + 1) * 5; m < 60 -> (m / 10 + 1) * 10; m < 90 -> 90; else -> (m / 30 + 1) * 30 }
private fun stayLess(m: Int) = when { m <= 30 -> ((m - 1) / 5) * 5; m <= 60 -> ((m - 1) / 10) * 10; m <= 90 -> 60; else -> ((m - 1) / 30) * 30 }.coerceAtLeast(0)
private fun stayText(m: Int) = if (m < 60) "$m min" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m"

@Composable
private fun OptionRow(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body, color = c.text2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        StepButton(LfIcons.Minus, "Less", onMinus)
        Plain(value, Type.label, c.text, Modifier.widthIn(min = 64.dp), align = TextAlign.Center)
        StepButton(LfIcons.Plus, "More", onPlus)
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalLf.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = L10n.tr(label) },
        contentAlignment = Alignment.Center,
    ) { LfIcon(icon, c.accentInk, 16.dp) }
}

@Composable
private fun Stepper(label: String, value: String, modifier: Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    val c = LocalLf.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(c.card).padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = Type.tiny, color = c.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton(LfIcons.Minus, "Less", onMinus)
            Plain(value, Type.label, c.text, Modifier.weight(1f), align = TextAlign.Center)
            StepButton(LfIcons.Plus, "More", onPlus)
        }
    }
}

// ---------------- the result: summary and stops ----------------

@Composable
fun PlanResult(p: Planner, s: AppSettings, options: Boolean, onShow: (Double, Double) -> Unit) {
    val plan = p.plan ?: return
    val c = LocalLf.current
    val platform = LocalPlatform.current
    val from = p.planFrom
    val to = p.planTo
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Summary
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(dur(plan.totalMin), style = Type.stat.copy(fontSize = 26.sp), color = c.text, modifier = Modifier.weight(1f))
                if (plan.ok) {
                    val cal = java.util.Calendar.getInstance().apply { timeInMillis = p.departMillis(); add(java.util.Calendar.MINUTE, (plan.totalMin + p.farStay).roundToInt()) }
                    Text("Arrive " + TimeFmt.hm(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)), style = Type.label, color = c.text2)
                }
            }
            val charging = plan.stops.count { it.kwh > 0.01 }
            Text(
                listOfNotNull(
                    fmtDistance(plan.totalKm, s),
                    when (charging) { 0 -> "No charging needed"; 1 -> "1 stop"; else -> "$charging stops" },
                    if (plan.chargeMin > 0) "${dur(plan.chargeMin.toDouble())} charging" else null,
                ).joinToString(" · "), style = Type.small, color = c.muted,
            )
            if (plan.ok) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag("Arrive with ${plan.arrivePct.roundToInt()} %", c.okBg, c.okText)
                plan.cost?.let { Tag("≈ " + money(it, plan.currency) + if (plan.unpriced > 0) " +" else "", c.card, c.text2, c.line2) }
            }
        }
        if (p.alts.size > 1) AltChooser(p, s)
        if (p.ferryInfo.isNotEmpty()) FerryRows(p)
        if (!plan.ok) Text(
            if (p.startNow() <= p.minPct) "The start charge is at or below your minimum. Start with more, or lower the minimum."
            else "No fast charger within reach after ${fmtDistance(plan.failKm, s)}. Lower the minimum, raise Charge to, charge more before, or pick a charger on the map.",
            style = Type.small, color = c.text,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.danger, RoundedCornerShape(14.dp)).padding(12.dp),
        )
        p.busy?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { Busy(); Text(msg, style = Type.small, color = c.text2) }
        }
        if (options) p.error?.let { Text(it, style = Type.small, color = c.danger) }

        // The trip, stop by stop
        if (from != null) EndRow(c.orangeInk, placeName(from), from.detail, "${p.startNow()} %") { onShow(from.lat, from.lon) }
        // Charging stops and the places the user asked to pass through, in the order they come along the road.
        val rt = p.route
        val vias = if (rt == null) emptyList() else p.planVias.mapIndexed { k, v -> Triple(v, rt.km[no.lionflow.app.plan.Geo.project(rt, v.lat, v.lon).first], p.planStays.getOrElse(k) { 0 }) }.sortedBy { it.second }
        var vi = 0
        plan.stops.forEachIndexed { i, st ->
            while (vi < vias.size && vias[vi].second <= st.charger.km) { ViaRow(p, vias[vi].first, vias[vi].third, s, onShow); vi++ }
            StopCard(i + 1, st, p, s, onShow)
        }
        while (vi < vias.size) { ViaRow(p, vias[vi].first, vias[vi].third, s, onShow); vi++ }
        if (to != null) {
            // On a round trip: how long the drive back takes (from leaving the destination).
            val back = if (p.planRound) p.planVias.lastOrNull()?.let { d -> p.minutesTo(d.lat, d.lon)?.let { plan.totalMin - it } } else null
            EndRow(c.accentInk, placeName(to), to.detail, if (plan.ok) "${plan.arrivePct.roundToInt()} %" else "—",
                back?.let { L10n.tr("Back: {0}").replace("{0}", dur(it)) + " · " + L10n.tr("arrive {0}").replace("{0}", clockAt(p, plan.totalMin + p.farStay)) }) { onShow(to.lat, to.lon) }
        }

        if (options) { ViaStays(p); BatteryOptions(p) }
        if (plan.ok && from != null && to != null) PrimaryButton("Open in Google Maps", Modifier.fillMaxWidth(), icon = LfIcons.Locate) {
            val rt2 = p.route
            val along = plan.stops.map { it.charger.km to "${it.charger.lat},${it.charger.lon}" } +
                (if (rt2 == null) emptyList() else p.planVias.map { v -> rt2.km[no.lionflow.app.plan.Geo.project(rt2, v.lat, v.lon).first] to "${v.lat},${v.lon}" })
            val stops = along.sortedBy { it.first }.take(9).joinToString("%7C") { it.second }
            platform.openUrl(
                "https://www.google.com/maps/dir/?api=1&travelmode=driving&origin=${from.lat},${from.lon}&destination=${to.lat},${to.lon}" +
                    if (stops.isNotEmpty()) "&waypoints=$stops" else "",
            )
        }
        if (plan.ok) {
            var saved by remember(plan) { mutableStateOf(false) }
            var ask by remember { mutableStateOf(false) }
            no.lionflow.app.ui.components.SecondaryButton(if (saved) "Trip saved" else "Save trip", Modifier.fillMaxWidth()) { if (!saved) ask = true }
            if (ask) androidx.compose.material3.AlertDialog(
                onDismissRequest = { ask = false }, containerColor = c.card,
                title = { Text("Save trip", color = c.text) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SaveChoice("Exactly as planned now", "Opens at once with this route, these stops, times and battery %.") { ask = false; if (p.saveTrip(true)) saved = true }
                        SaveChoice("Plan again when opened", "Keeps the places. Opening it plans again from the car's position and battery % at that time.") { ask = false; if (p.saveTrip(false)) saved = true }
                    }
                },
                confirmButton = {},
                dismissButton = { androidx.compose.material3.TextButton(onClick = { ask = false }) { Text("Cancel", color = c.muted) } },
            )
        }
        if (p.changed) Text("Undo my changes to the stops", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk, modifier = Modifier.clickable { p.resetStops() })

        Text(
            if (p.model.trips > 0) "Battery use is worked out from your ${p.model.trips} longest trips (${String.format(Locale.US, "%.1f", p.model.at(90.0))} kWh/100 km at 90 km/h)."
            else "Battery use is a typical figure until LionFlow has your trips.",
            style = Type.tiny, color = c.muted,
        )
        p.route?.let { rt ->
            val cd = rt.conditions
            val lines = when {
                cd == null -> listOf("Hills, wind and temperature are not counted (the forecast could not be fetched).")
                cd.hasHills && cd.hasWeather -> listOf(
                    "Hills (${cd.climbM.roundToInt()} m up, ${cd.descentM.roundToInt()} m down, with regeneration downhill), wind and temperature along the route are included.",
                    "Forecast along the route: ${cd.tMin!!.roundToInt()} to ${cd.tMax!!.roundToInt()} °C, wind up to ${cd.windMax!!.roundToInt()} m/s.",
                )
                cd.hasHills -> listOf("Hills (${cd.climbM.roundToInt()} m up, ${cd.descentM.roundToInt()} m down, with regeneration downhill) are included. No forecast for wind and temperature (too far ahead, or not available).")
                else -> listOf("Forecast along the route: ${cd.tMin!!.roundToInt()} to ${cd.tMax!!.roundToInt()} °C, wind up to ${cd.windMax!!.roundToInt()} m/s.")
            }
            for (l in lines) Text(L10n.tr(l), style = Type.tiny, color = c.muted)
        }
        Text(
            if (p.fitCount > 0) "Charging time matches your last ${p.fitCount} fast charges (top speed about ${p.carKw.roundToInt()} kW)."
            else "Charging time is a typical figure until LionFlow has seen your fast charges.",
            style = Type.tiny, color = c.muted,
        )
        if (plan.stops.any { it.cost != null }) Text("Prices are the usual drop-in prices (checked ${p.pricesAsOf}), not live prices.", style = Type.tiny, color = c.muted)
        Text("Map and chargers © OpenStreetMap contributors · Route: OSRM, Valhalla (FOSSGIS) · Address search: Photon", style = Type.tiny.copy(fontSize = 10.sp), color = c.muted,
            modifier = Modifier.clickable { platform.openUrl("https://www.openstreetmap.org/copyright") })
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color, line: Color? = null) {
    val m = Modifier.clip(CircleShape).background(bg)
    Box((if (line != null) m.border(1.dp, line, CircleShape) else m).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

@Composable
private fun EndRow(dot: Color, name: String, detail: String, pct: String, time: String? = null, onClick: () -> Unit) {
    val c = LocalLf.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).clip(CircleShape).background(dot)) }
        Column(Modifier.weight(1f)) {
            Plain(name, Type.label, c.text)
            if (time != null) Plain(time, Type.tiny.copy(fontWeight = FontWeight.SemiBold), c.accentInk)
            if (detail.isNotBlank()) Plain(detail, Type.tiny, c.muted)
        }
        Plain(pct, Type.label, c.text2)
    }
}

@Composable
private fun StopCard(n: Int, st: Stop, p: Planner, s: AppSettings, onShow: (Double, Double) -> Unit) {
    val c = LocalLf.current
    val ch = st.charger
    var others by remember(ch.id) { mutableStateOf(false) }
    val arrive = st.arrivePct.roundToInt()
    val leave = st.leavePct.roundToInt()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.raised).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onShow(ch.lat, ch.lon) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { Plain("$n", Type.label.copy(fontSize = 14.sp), c.onAccent) }
                Column(Modifier.weight(1f)) {
                    Plain(ch.name, Type.label, c.text)
                    Plain(chargerLine(ch, s), Type.tiny, c.muted)
                }
            }
            LfIcon(LfIcons.Close, c.muted, 18.dp, "Remove this stop", Modifier.clip(CircleShape).clickable { p.removeStop(ch.id) }.padding(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Plain("$arrive % → $leave %", Type.stat.copy(fontSize = 18.sp), c.text, Modifier.weight(1f))
            st.cost?.takeIf { it > 0.5 }?.let { Plain("≈ " + money(it, ch.currency), Type.small, c.text2) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stepper("Charge to", "$leave %", Modifier.weight(1f),
                { p.chargeTo(ch.id, (((leave - 1) / 5) * 5).coerceAtLeast(ceil(st.arrivePct).toInt())) },
                { p.chargeTo(ch.id, ((leave / 5 + 1) * 5).coerceAtMost(100)) })
            Stepper("Break", "${st.minutes} min", Modifier.weight(1f),
                { p.stayFor(ch.id, st.minutes - 5) }, { p.stayFor(ch.id, (st.minutes / 5 + 1) * 5) })
        }
        if (st.alternatives.isNotEmpty()) {
            Text(if (others) "Hide other chargers" else "Choose another charger", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.clickable { others = !others })
            if (others) for (a in st.alternatives) Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.card).clickable { others = false; p.swapStop(ch.id, a.id) }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Plain(a.name, Type.body, c.text)
                    Plain(chargerLine(a, s), Type.tiny, c.muted)
                }
            }
        }
    }
}

/** A charger tapped on the map while planning: add it as a stop, or take it out. */
@Composable
fun PlanChargerCard(ch: PlanCharger, isStop: Boolean, s: AppSettings, modifier: Modifier, onAdd: () -> Unit, onRemove: () -> Unit, onClose: () -> Unit) {
    val c = LocalLf.current
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(20.dp)).blockTouches().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Plain(ch.name, Type.label.copy(fontSize = 16.sp), c.text)
                Plain(chargerLine(ch, s), Type.small, c.muted, maxLines = 2)
            }
            LfIcon(LfIcons.Close, c.muted, 20.dp, "Close", Modifier.clip(CircleShape).clickable(onClick = onClose).padding(4.dp))
        }
        Box(
            Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(if (isStop) c.raised else c.accent).clickable(role = Role.Button, onClick = if (isStop) onRemove else onAdd),
            contentAlignment = Alignment.Center,
        ) { Text(if (isStop) "Remove this stop" else "Charge here", style = Type.label, color = if (isStop) c.text else c.onAccent) }
    }
}

/** Up to three routes side by side, each labelled with what it is best at (fastest, shortest, cheapest). */
@Composable
private fun AltChooser(p: Planner, s: AppSettings) {
    val c = LocalLf.current
    val alts = p.alts
    val done = alts.filter { it.plan?.ok == true }
    val fastest = done.minByOrNull { it.plan!!.totalMin }
    val shortest = done.minByOrNull { it.route.totalKm }
    val cheapest = done.filter { it.plan!!.cost != null }.minByOrNull { it.plan!!.cost!! }
    fun stops(a: Planner.AltRoute) = a.plan!!.stops.count { it.kwh > 0.01 }
    val fewest = done.minOfOrNull { stops(it) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Routes", style = Type.label, color = c.text2)
        if (p.autoPicked && p.altIndex != 0) Text("Switched to the route with the fewest charging stops and the least driving.", style = Type.tiny, color = c.muted)
        alts.forEachIndexed { i, a ->
            val sel = i == p.altIndex
            val pl = a.plan
            val tags = listOfNotNull(
                if (a.route.ferries.isNotEmpty()) "⛴ " + L10n.tr("Ferry") else null,
                if (pl?.ok == true && done.size > 1 && stops(a) == fewest && done.count { stops(it) == fewest } < done.size) L10n.tr("Fewest stops") else null,
                if (a === fastest) L10n.tr("Fastest") else null,
                if (a === shortest) L10n.tr("Shortest") else null,
                if (a === cheapest && done.size > 1) L10n.tr("Cheapest") else null,
            ).joinToString(" · ")
            val info = if (pl == null) L10n.tr("Working it out…")
                else listOfNotNull(dur(pl.totalMin), pl.stops.count { it.kwh > 0.01 }.let { n -> if (n == 1) L10n.tr("1 stop") else L10n.tr("$n stops") }, fmtDistance(a.route.totalKm, s), if (pl.ok) L10n.tr("arrive ${pl.arrivePct.roundToInt()} %") else null, pl.cost?.let { "≈ " + money(it, pl.currency) }).joinToString(" · ")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .border(1.dp, if (sel) c.accentInk else c.line2, RoundedCornerShape(14.dp))
                    .clickable { p.selectRoute(i) }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Plain(info, Type.body.copy(fontWeight = FontWeight.SemiBold), c.text)
                    if (tags.isNotEmpty()) Plain(tags, Type.tiny, c.muted)
                }
            }
        }
    }
}

/** The car ferries on the way, with when they leave and how long the wait is. */
@Composable
private fun FerryRows(p: Planner) {
    val c = LocalLf.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        p.ferryInfo.forEach { f ->
            val at = f.departMs?.let { ms -> java.util.Calendar.getInstance().apply { timeInMillis = ms }.let { " · " + L10n.tr("leaves") + " " + TimeFmt.hm(it.get(java.util.Calendar.HOUR_OF_DAY), it.get(java.util.Calendar.MINUTE)) } } ?: ""
            Plain("⛴ ${f.ferry.name} · ${dur(f.ferry.minutes)} · ${L10n.tr("wait")} ${f.waitMin} min$at", Type.small, c.text2, maxLines = 2)
        }
        val platform = LocalPlatform.current
        Text("Ferry times: Entur and Transitous where the operator publishes them, otherwise a typical wait is assumed.", style = Type.tiny, color = c.muted,
            modifier = Modifier.clickable { platform.openUrl("https://transitous.org/sources/") })
    }
}

/** Pick the day (today and the next six) and the time to leave, or "Now". */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DepartPicker(current: Long?, onSet: (Long?) -> Unit, onClose: () -> Unit) {
    val c = LocalLf.current
    val start = java.util.Calendar.getInstance().apply { timeInMillis = current ?: (System.currentTimeMillis() + 15 * 60_000L) }
    val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
    var day by remember { mutableStateOf(((start.timeInMillis - today.timeInMillis) / 86_400_000L).toInt().coerceIn(0, 6)) }
    val time = androidx.compose.material3.rememberTimePickerState(
        initialHour = start.get(java.util.Calendar.HOUR_OF_DAY),
        initialMinute = start.get(java.util.Calendar.MINUTE) / 5 * 5,
        is24Hour = TimeFmt.use24,
    )
    fun chosen(): Long = (today.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, day); set(java.util.Calendar.HOUR_OF_DAY, time.hour); set(java.util.Calendar.MINUTE, time.minute)
    }.timeInMillis
    val past = chosen() <= System.currentTimeMillis()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text("Leave at", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..6).forEach { i ->
                        val on = i == day
                        val label = when (i) {
                            0 -> L10n.tr("Today"); 1 -> L10n.tr("Tomorrow")
                            else -> java.text.SimpleDateFormat("EEE d", L10n.locale).format(java.util.Date(today.timeInMillis + i * 86_400_000L + 3_600_000L * 12))
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) c.accent else c.raised)
                                .border(1.dp, if (on) c.accent else c.line, RoundedCornerShape(50))
                                .clickable { day = i }.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) { Plain(label, Type.small.copy(fontWeight = FontWeight.SemiBold), if (on) c.onAccent else c.text) }
                    }
                }
                androidx.compose.material3.TimePicker(state = time, colors = androidx.compose.material3.TimePickerDefaults.colors(
                    clockDialColor = c.raised, clockDialSelectedContentColor = c.onAccent, clockDialUnselectedContentColor = c.text,
                    selectorColor = c.accent, containerColor = c.card,
                    timeSelectorSelectedContainerColor = c.accent, timeSelectorUnselectedContainerColor = c.raised,
                    timeSelectorSelectedContentColor = c.onAccent, timeSelectorUnselectedContentColor = c.text,
                    periodSelectorSelectedContainerColor = c.accent, periodSelectorUnselectedContainerColor = c.raised,
                    periodSelectorSelectedContentColor = c.onAccent, periodSelectorUnselectedContentColor = c.text,
                ))
                if (past) Text("That time has passed. Pick a later time or a later day.", style = Type.tiny, color = c.danger)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !past, onClick = { onSet(chosen()) }) { Text("Set", color = if (past) c.muted else c.accentInk) }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = { onSet(null) }) { Text("Now", color = c.accentInk) }
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Cancel", color = c.muted) }
            }
        },
    )
}
