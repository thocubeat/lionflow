package no.lionflow.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import no.lionflow.app.data.AppSettings
import no.lionflow.app.route.RouteService
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.GroupCard
import no.lionflow.app.ui.components.SectionTitle
import no.lionflow.app.ui.components.ToggleRow
import no.lionflow.app.ui.l10n.Text
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

/** Settings: record routes with the phone's GPS while connected to the car over Bluetooth. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RouteTrackingSettings(vm: AppViewModel, s: AppSettings) {
    val c = LocalLf.current
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) } // re-read permissions after each request
    var pick by remember { mutableStateOf(false) }
    fun set(block: (AppSettings) -> AppSettings) = vm.settings.update(block)

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (RouteService.hasLocation(ctx) && !RouteService.hasBackgroundLocation(ctx) && Build.VERSION.SDK_INT >= 29)
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (RouteService.hasBluetooth(ctx) && s.routeBtAddr.isEmpty()) pick = true
    }
    fun askAll() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        launcher.launch(perms.toTypedArray())
    }

    tick.let { } // read so permission state recomposes
    val loc = RouteService.hasLocation(ctx)
    val bg = RouteService.hasBackgroundLocation(ctx)
    val bt = RouteService.hasBluetooth(ctx)

    SectionTitle("Route tracking")
    GroupCard {
        // Scrolled into view and flashed when another screen sends you here ("Open Route tracking in Settings").
        val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
        val flash = remember { androidx.compose.animation.core.Animatable(0f) }
        var rowSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        val density = androidx.compose.ui.platform.LocalDensity.current
        val target = vm.focusSetting
        LaunchedEffect(target) {
            if (target != "routeTracking") return@LaunchedEffect
            kotlinx.coroutines.delay(350)   // let the screen finish drawing first
            val pad = with(density) { 140.dp.toPx() }
            bring.bringIntoView(androidx.compose.ui.geometry.Rect(0f, -pad, rowSize.width.toFloat(), rowSize.height + pad))
            // Highlight for 2 seconds: quick fade in, then a slow fade out.
            flash.snapTo(0f)
            flash.animateTo(1f, androidx.compose.animation.core.tween(250))
            flash.animateTo(0f, androidx.compose.animation.core.tween(1750))
            // Cleared last: clearing it changes the key above, which would cancel this effect half way.
            vm.focusSetting = null
        }
        Box(Modifier.fillMaxWidth().bringIntoViewRequester(bring).onSizeChanged { rowSize = it }
            .drawBehind { drawRect(c.accent.copy(alpha = 0.45f * flash.value)) }) {
            ToggleRow("Record routes", "Uses your phone's GPS while it's connected to the car over Bluetooth. Routes stay on this phone.", s.routeTracking) { on ->
                set { it.copy(routeTracking = on) }
                if (on) askAll() else RouteService.stop(ctx)
            }
        }
        if (s.routeTracking) {
            Column(Modifier.fillMaxWidth().clickable { if (bt) pick = true else askAll() }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Car's Bluetooth", style = Type.label, color = c.text)
                Text(s.routeBtName.ifEmpty { "Tap to choose your car" }, style = Type.small, color = if (s.routeBtName.isEmpty()) c.accentInk else c.muted)
            }
            val missing = listOfNotNull(
                "location".takeIf { !loc },
                "location all the time".takeIf { loc && !bg },
                "nearby devices".takeIf { !bt },
            )
            if (missing.isNotEmpty()) {
                Divider()
                Text("Needs permission: ${missing.joinToString(", ")} — tap to fix", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (loc && !bg && Build.VERSION.SDK_INT >= 30) {
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } else askAll()
                    }.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            Divider()
            Text("Delete all recorded routes", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                modifier = Modifier.fillMaxWidth().clickable { no.lionflow.app.route.Routes.deleteAll(ctx); vm.reloadRoutes() }.padding(horizontal = 16.dp, vertical = 12.dp))
        }
    }

    if (pick) BluetoothPicker(s.routeBtAddr, onDismiss = { pick = false }) { addr, name ->
        set { it.copy(routeBtAddr = addr, routeBtName = name) }
        pick = false
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothPicker(selected: String?, onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val devices = remember {
        try {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices.orEmpty()
                .map { it.address to (it.name ?: it.address) }.sortedBy { it.second.lowercase() }
        } catch (_: Exception) { emptyList() }
    }
    BluetoothDeviceDialog(devices, onDismiss, selected, onPick)
}

/** Names that are most likely the car's own Bluetooth. */
private val carWords = listOf("peugeot", "citro", "ds ", "ds7", "ds4", "ds3", "opel", "vauxhall", "my car", "car", "stellantis", "uconnect", "connect", "e-5008", "5008", "3008", "2008", "208", "308", "408", "corsa", "astra", "mokka", "grandland", "c4", "c5")

private fun looksLikeCar(name: String) = name.lowercase().let { n -> carWords.any { n.contains(it) } }

/**
 * Pick the car among the phone's paired Bluetooth devices. Devices that look like a car come first; with many
 * devices there is a search field, and the list shows clearly that it scrolls (fades at the edges, a scroll bar,
 * and "scroll for more").
 */
@Composable
internal fun BluetoothDeviceDialog(
    devices: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    selected: String? = null,
    onPick: (String, String) -> Unit,
) {
    val c = LocalLf.current
    var query by remember { mutableStateOf("") }
    val shown = remember(devices, query) {
        val q = query.trim().lowercase()
        devices.filter { q.isEmpty() || it.second.lowercase().contains(q) }
    }
    val cars = shown.filter { looksLikeCar(it.second) }
    val others = shown.filter { !looksLikeCar(it.second) }
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val canUp by remember { androidx.compose.runtime.derivedStateOf { state.canScrollBackward } }
    val canDown by remember { androidx.compose.runtime.derivedStateOf { state.canScrollForward } }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.card,
        title = {
            Column {
                Text("Choose your car", color = c.text)
                Text("The car's Bluetooth name, often the brand or \"My Peugeot\".", style = Type.small, color = c.muted)
            }
        },
        text = {
            if (devices.isEmpty()) {
                Text("No paired Bluetooth devices. Pair your phone with the car first.", style = Type.small, color = c.muted)
            } else Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                if (devices.size > 6) androidx.compose.material3.OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search ${devices.size} devices", color = c.muted) },
                    leadingIcon = { LfIcon(LfIcons.Search, c.muted, 18.dp) },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text, focusedBorderColor = c.accentInk, unfocusedBorderColor = c.line2, cursorColor = c.accentInk),
                )
                Box(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxWidth()
                            .drawWithContent {
                                drawContent()
                                // Scroll bar on the right, so it is clear the list goes on.
                                val info = state.layoutInfo
                                val total = info.totalItemsCount
                                val visible = info.visibleItemsInfo.size
                                if (total > visible && visible > 0) {
                                    val frac = visible.toFloat() / total
                                    val barH = (size.height * frac).coerceAtLeast(28.dp.toPx())
                                    val first = state.firstVisibleItemIndex.toFloat() / (total - visible).coerceAtLeast(1)
                                    val top = (size.height - barH) * first.coerceIn(0f, 1f)
                                    drawRoundRect(c.line2, androidx.compose.ui.geometry.Offset(size.width - 4.dp.toPx(), 0f),
                                        androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                                    drawRoundRect(c.accentInk, androidx.compose.ui.geometry.Offset(size.width - 4.dp.toPx(), top),
                                        androidx.compose.ui.geometry.Size(3.dp.toPx(), barH), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                                }
                            },
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        if (cars.isNotEmpty()) {
                            item(key = "h-car") { BtHeader("Looks like a car") }
                            items(cars, key = { "c" + it.first }) { (address, name) -> BtRow(name, address, true, address == selected) { onPick(address, name) } }
                        }
                        if (others.isNotEmpty()) {
                            if (cars.isNotEmpty()) item(key = "h-other") { BtHeader("Other devices") }
                            items(others, key = { "o" + it.first }) { (address, name) -> BtRow(name, address, false, address == selected) { onPick(address, name) } }
                        }
                        if (shown.isEmpty()) item(key = "none") { Text("No device matches.", style = Type.small, color = c.muted, modifier = Modifier.padding(8.dp)) }
                    }
                    // Fades at the edges where there is more to scroll.
                    if (canUp) Box(Modifier.fillMaxWidth().height(22.dp).align(Alignment.TopCenter)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.card, c.card.copy(alpha = 0f)))))
                    if (canDown) Box(Modifier.fillMaxWidth().height(34.dp).align(Alignment.BottomCenter)
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.card.copy(alpha = 0f), c.card))))
                }
                if (canDown) Text("Scroll for more ↓", style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}

@Composable
private fun BtHeader(t: String) {
    val c = LocalLf.current
    Text(t, style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp))
}

@Composable
private fun BtRow(name: String, address: String, car: Boolean, sel: Boolean, onClick: () -> Unit) {
    val c = LocalLf.current
    Row(
        Modifier.fillMaxWidth().padding(end = 8.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
            .background(if (sel) c.accent.copy(alpha = 0.16f) else c.raised).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (car) c.accent.copy(alpha = 0.18f) else c.card),
            contentAlignment = Alignment.Center) { LfIcon(if (car) LfIcons.Car else LfIcons.Bluetooth, if (car) c.accentInk else c.muted, 18.dp) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            androidx.compose.material3.Text(name, style = Type.body, color = c.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            androidx.compose.material3.Text("…" + address.takeLast(5), style = Type.tiny, color = c.muted)
        }
        if (sel) LfIcon(LfIcons.Check, c.accentInk, 18.dp)
    }
}
