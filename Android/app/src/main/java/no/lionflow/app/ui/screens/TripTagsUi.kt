package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.model.TagPlace
import no.lionflow.app.model.Trip
import no.lionflow.app.model.TripTag
import no.lionflow.app.model.TripTags
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.Segmented
import no.lionflow.app.ui.components.Toggle
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The seven tag colours: red, orange, yellow, green, teal, blue, purple. */
val TagColors = listOf(
    Color(0xFFE5484D), Color(0xFFF08C3A), Color(0xFFF2C94C), Color(0xFF57B36A), Color(0xFF2BB3B0), Color(0xFF4C8DF6), Color(0xFF9B6BE8),
)
private val TagDark = Color(0xFF0D0E10)

fun tagColor(i: Int): Color = TagColors[i.coerceIn(0, TagColors.size - 1)]

/** Black or white, whichever is easier to read on the colour. */
fun onTag(bg: Color): Color {
    val l = bg.luminance()
    val white = 1.05f / (l + 0.05f)
    val dark = (l + 0.05f) / (TagDark.luminance() + 0.05f)
    return if (dark >= white) TagDark else Color.White
}

/** Text shown as written (tag names, dates, numbers): never translated. */
@Composable
private fun Raw(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) =
    androidx.compose.material3.Text(text, modifier = modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)

@Composable
fun TagChip(tag: TripTag, auto: Boolean = false, maxWidth: Dp = 120.dp, onClick: (() -> Unit)? = null) {
    val bg = tagColor(tag.color)
    var m = Modifier.widthIn(max = maxWidth).clip(CircleShape).background(bg)
    if (onClick != null) m = m.clickable(onClick = onClick)
    // A star marks a tag LionFlow put on by itself.
    Raw(if (auto) tag.name + " ✦" else tag.name, Type.tiny.copy(fontWeight = FontWeight.SemiBold), onTag(bg), m.padding(horizontal = 10.dp, vertical = 5.dp))
}

/** In a trip row: the trip's tag, or a small tag button when it has none. Opens the tag picker. */
@Composable
fun TripTagSlot(vm: AppViewModel, s: AppSettings, trip: Trip) {
    val c = LocalLf.current
    var open by remember { mutableStateOf(false) }
    val data = vm.tags.data   // read here so the row redraws when tags change
    val tag = data.marks[trip.id]?.tag?.let { id -> data.tags.firstOrNull { it.id == id } }
    if (tag != null) TagChip(tag, vm.tags.isAuto(trip.id), 96.dp) { open = true }
    else {
        val label = L10n.tr("Tag this trip")
        Box(Modifier.size(34.dp).clip(CircleShape).clickable { open = true }.semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
            LfIcon(LfIcons.Tag, c.icon, 18.dp)
        }
    }
    if (open) TagPicker(vm, s, trip) { open = false }
}

@Composable
private fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TagColors.forEachIndexed { i, col ->
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(2.dp, if (i == selected) c.text else Color.Transparent, CircleShape).padding(4.dp)
                    .clip(CircleShape).background(col).clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) { if (i == selected) LfIcon(LfIcons.Check, onTag(col), 14.dp) }
        }
    }
}

@Composable
private fun tagFieldColors() = LocalLf.current.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2,
        cursorColor = c.accentInk, focusedLabelColor = c.accentInk, unfocusedLabelColor = c.muted,
    )
}

/** Pick, make, change or delete tags for one trip. */
@Composable
fun TagPicker(vm: AppViewModel, s: AppSettings, trip: Trip, onDismiss: () -> Unit) {
    val c = LocalLf.current
    val data = vm.tags.data
    val current = data.marks[trip.id]?.tag?.takeIf { it.isNotEmpty() }
    var editing by remember { mutableStateOf<TripTag?>(null) }
    var adding by remember { mutableStateOf(data.tags.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(data.tags.size % TagColors.size) }
    var confirmDelete by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf<TagPlace?>(null) }
    var to by remember { mutableStateOf<TagPlace?>(null) }
    var placeFor by remember { mutableStateOf<String?>(null) }

    val ed = editing
    if (ed != null && placeFor != null) {
        PlaceSearch(vm, if (placeFor == "from") "Start address" else "End address", onDismiss = { placeFor = null }) { p ->
            if (placeFor == "from") from = p else to = p
            placeFor = null
        }
        return
    }
    if (ed != null) {
        AlertDialog(
            onDismissRequest = { editing = null }, containerColor = c.card,
            title = { Text("Change tag", color = c.text) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(name, { name = it.take(TripTags.MAX_NAME) }, singleLine = true, label = { Text("Name") }, colors = tagFieldColors())
                    ColorDots(color) { color = it }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Route for this tag (optional)", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Text("Recorded trips between these two addresses get this tag, in either direction. Works when auto-tagging by route is on.", style = Type.tiny, color = c.muted)
                        PlaceRow("From", from, { placeFor = "from" }) { from = null }
                        PlaceRow("To", to, { placeFor = "to" }) { to = null }
                    }
                    if (confirmDelete) Text("Delete this tag from all trips?", style = Type.small, color = c.danger)
                    Text(if (confirmDelete) "Yes, delete" else "Delete tag", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.danger,
                        modifier = Modifier.clickable { if (confirmDelete) { vm.tags.delete(ed.id); editing = null; confirmDelete = false } else confirmDelete = true }.padding(vertical = 6.dp))
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { vm.editTag(ed.id, name, color, from, to); editing = null }) { Text("Save", color = c.accentInk) } },
            dismissButton = { TextButton(onClick = { editing = null; confirmDelete = false }) { Text("Cancel", color = c.muted) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text("Tag this trip", color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                data.tags.forEach { t ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.tagTrip(trip, t.id); onDismiss() }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TagChip(t, maxWidth = 170.dp)
                        Spacer(Modifier.width(8.dp))
                        if (t.id == current) LfIcon(LfIcons.Check, c.accentInk, 18.dp)
                        Spacer(Modifier.weight(1f))
                        Text("Edit", style = Type.small, color = c.muted, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { name = t.name; color = t.color; from = t.from; to = t.to; editing = t }.padding(8.dp))
                    }
                }
                if (current != null) Text("Remove the tag from this trip", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.danger,
                    modifier = Modifier.clickable { vm.tagTrip(trip, null); onDismiss() }.padding(vertical = 6.dp))
                if (data.tags.isNotEmpty()) Divider()
                if (!adding) Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { adding = true; name = ""; color = data.tags.size % TagColors.size }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LfIcon(LfIcons.Plus, c.accentInk, 16.dp)
                    Text("New tag", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk)
                } else {
                    OutlinedTextField(name, { name = it.take(TripTags.MAX_NAME) }, singleLine = true, label = { Text("New tag") },
                        placeholder = { Text("Work, holiday, a client…", color = c.muted) }, colors = tagFieldColors(), modifier = Modifier.fillMaxWidth())
                    ColorDots(color) { color = it }
                    if (name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TagChip(TripTag("", name.trim(), color), maxWidth = 150.dp)
                        Text("Add to this trip", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { vm.tagTrip(trip, vm.tags.create(name, color).id); onDismiss() }.padding(8.dp))
                    }
                }
                Divider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Tag similar trips automatically", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        Text("A trip with about the same distance and average speed as one you tagged gets the same tag, marked ✦. You can always change it.", style = Type.tiny, color = c.muted)
                    }
                    Spacer(Modifier.width(10.dp))
                    Toggle(s.autoTagTrips, "Tag similar trips automatically") { vm.setAutoTag(it) }
                }
                if (s.autoTagTrips) AutoRouteOption(vm, s, onDismiss)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = c.muted) } },
    )
}

/** Driving split by tag, for the trips of the period shown on the Trips screen. */
@Composable
fun TagStats(vm: AppViewModel, ui: UiState, s: AppSettings, trips: List<Trip>) {
    val c = LocalLf.current
    val data = vm.tags.data
    if (data.tags.isEmpty()) {
        if (ui.trips.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LfIcon(LfIcons.Tag, c.muted, 16.dp)
            Text("Tip: tag your trips (work, holiday, a client…) to see statistics per tag.", style = Type.tiny, color = c.muted)
        }
        return
    }
    var metric by remember { mutableStateOf("km") }
    fun value(t: Trip): Double = when (metric) { "trips" -> 1.0; "time" -> (t.durationSec ?: 0.0) / 3600.0; else -> t.distanceKm }
    fun show(v: Double): String = when (metric) {
        "trips" -> "${v.toInt()}"
        "time" -> if (v >= 1) "${v.toInt()} h ${((v - v.toInt()) * 60).toInt()} min" else "${(v * 60).toInt()} min"
        else -> fmtDistance(v, s)
    }
    val groups = trips.groupBy { t -> data.marks[t.id]?.tag?.let { id -> data.tags.firstOrNull { it.id == id } } }
    val rows = (data.tags.map { it to groups[it].orEmpty() } + listOf(null to groups[null].orEmpty()))
        .map { (tag, list) -> Triple(tag, list.sumOf { value(it) }, list.size) }
        .filter { it.third > 0 || it.first != null }.sortedByDescending { it.second }
    val total = rows.sumOf { it.second }

    LfCard {
        Text("Trip statistics", style = Type.label, color = c.text)
        Segmented(listOf("km" to "Distance", "trips" to "Trips", "time" to "Time"), metric, Modifier.fillMaxWidth()) { metric = it }
        if (total <= 0) Text("No trips in this period.", style = Type.small, color = c.muted)
        else {
            // One bar split by tag, then a line per tag.
            Row(Modifier.fillMaxWidth().height(18.dp).clip(CircleShape).background(c.track)) {
                rows.filter { it.second > 0 }.forEach { (tag, v, _) ->
                    Box(Modifier.weight((v / total).toFloat().coerceAtLeast(0.004f)).height(18.dp).background(tag?.let { tagColor(it.color) } ?: c.bar))
                }
            }
            val max = rows.maxOf { it.second }.coerceAtLeast(1e-9)
            rows.forEach { (tag, v, n) ->
                val col = tag?.let { tagColor(it.color) } ?: c.bar
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (tag != null) TagChip(tag, maxWidth = 150.dp) else Text("No tag", style = Type.small, color = c.muted)
                        Spacer(Modifier.weight(1f))
                        if (metric != "trips") Text(if (n == 1) "1 trip" else "$n trips", style = Type.tiny, color = c.muted)
                        Raw(show(v), Type.label.copy(fontSize = 14.sp), c.text)
                        Raw(String.format(Locale.US, "%.0f %%", v / total * 100), Type.tiny, c.muted, Modifier.width(38.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(c.track)) {
                        Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0f, 1f)).height(6.dp).clip(CircleShape).background(col))
                    }
                }
            }
        }
    }
}

/** The "same route" choice for auto-tagging, with a link to Route tracking when that is off. */
@Composable
fun AutoRouteOption(vm: AppViewModel, s: AppSettings, onLeave: () -> Unit = {}) {
    val c = LocalLf.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Go by the route driven", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Text("Uses where the trip started and ended, from the route recorded on your phone, or the addresses you set on a tag (under Edit).", style = Type.tiny, color = c.muted)
        }
        Spacer(Modifier.width(10.dp))
        Toggle(s.autoTagRoute, "Go by the route driven") { vm.setAutoTagRoute(it) }
    }
    if (s.autoTagRoute && !s.routeTracking) {
        Text("Route tracking is off, so trips are matched by distance and speed for now. Turn it on for this to work.", style = Type.tiny, color = c.orangeInk)
        no.lionflow.app.ui.components.SecondaryButton("Open Route tracking in Settings", Modifier.fillMaxWidth()) { onLeave(); vm.openSetting("routeTracking") }
    }
}

@Composable
private fun PlaceRow(label: String, place: TagPlace?, onPick: () -> Unit, onClear: () -> Unit) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, c.line2, RoundedCornerShape(12.dp)).clickable(onClick = onPick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = Type.tiny, color = c.muted, modifier = Modifier.width(40.dp))
        if (place == null) Text("Add address", style = Type.small, color = c.accentInk, modifier = Modifier.weight(1f))
        else {
            Raw(place.name, Type.small, c.text, Modifier.weight(1f))
            Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClear), contentAlignment = Alignment.Center) { LfIcon(LfIcons.Close, c.muted, 14.dp) }
        }
    }
}

/** Type an address, pick one of the matches. */
@Composable
private fun PlaceSearch(vm: AppViewModel, title: String, onDismiss: () -> Unit, onPick: (TagPlace) -> Unit) {
    val c = LocalLf.current
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<no.lionflow.app.plan.Place>>(emptyList()) }
    var state by remember { mutableStateOf("") }   // "", busy, none, error
    LaunchedEffect(q) {
        if (q.trim().length < 3) { results = emptyList(); state = ""; return@LaunchedEffect }
        kotlinx.coroutines.delay(450)
        state = "busy"
        try { results = vm.searchPlace(q); state = if (results.isEmpty()) "none" else "" }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { results = emptyList(); state = "error" }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = { Text(title, color = c.text) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("Search address or place") }, colors = tagFieldColors(), modifier = Modifier.fillMaxWidth())
                when (state) {
                    "busy" -> Text("Searching…", style = Type.tiny, color = c.muted)
                    "none" -> Text("No matches.", style = Type.tiny, color = c.muted)
                    "error" -> Text("Couldn't search right now. Check your connection.", style = Type.tiny, color = c.danger)
                }
                results.forEach { p ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        onPick(TagPlace(listOf(p.name, p.detail).filter { it.isNotBlank() }.joinToString(", ").take(80), p.lat, p.lon))
                    }.padding(vertical = 8.dp)) {
                        Raw(p.name, Type.small.copy(fontWeight = FontWeight.SemiBold), c.text)
                        if (p.detail.isNotBlank()) Raw(p.detail, Type.tiny, c.muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}
