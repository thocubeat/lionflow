package no.lionflow.app.ui

import no.lionflow.app.ui.components.softShadow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import no.lionflow.app.ui.l10n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import no.lionflow.app.Platform
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.LionLogo
import no.lionflow.app.ui.screens.ChargingScreen
import no.lionflow.app.ui.screens.ClimateScreen
import no.lionflow.app.ui.screens.HomeScreen
import no.lionflow.app.ui.screens.LockScreen
import no.lionflow.app.ui.screens.MapScreen
import no.lionflow.app.ui.screens.PinDialog
import no.lionflow.app.ui.screens.RemoteSetupScreen
import no.lionflow.app.ui.screens.ServiceScreen
import no.lionflow.app.ui.screens.SettingsScreen
import no.lionflow.app.ui.screens.SignInScreen
import no.lionflow.app.ui.screens.TripsScreen
import no.lionflow.app.ui.theme.LionFlowTheme
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type

val LocalPlatform = staticCompositionLocalOf<Platform> { error("No platform") }

/** Ask for confirmation (fingerprint or security code) before a remote action, as configured in Settings. */
val LocalConfirm = staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, action -> action() } }

@Composable
fun LionFlowRoot(vm: AppViewModel, platform: Platform) {
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    val confirm: (String, () -> Unit) -> Unit = { label, action ->
        vm.prewakeForAction() // wake the car while the user confirms
        when {
            !settings.remoteConfirm -> action()
            settings.remoteBioOnly && platform.biometricAvailable -> platform.authenticate(
                title = label, subtitle = "Confirm with your fingerprint",
                onSuccess = action,
                onFail = { pinFor = label to action },
            )
            else -> pinFor = label to action
        }
    }

    LionFlowTheme(settings.theme) {
        CompositionLocalProvider(LocalPlatform provides platform, LocalConfirm provides confirm) {
            val c = LocalLf.current
            Box(Modifier.fillMaxSize().background(c.bg)) {
                when (ui.phase) {
                    Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LionLogo(96.dp) }
                    Phase.SignedOut -> SignInScreen(vm, ui, settings)
                    Phase.SetupRemote -> RemoteSetupScreen(vm, ui)
                    Phase.Locked -> LockScreen(vm, settings)
                    Phase.Ready -> MainShell(vm, ui, settings)
                }
                // After an update: what changed since the version last opened.
                val since = vm.whatsNewSince
                if (since != null && ui.phase == Phase.Ready && (ui.update == null || ui.updateDismissed)) {
                    val list = vm.releases.filter { it.versionCode > since && it.versionCode <= no.lionflow.app.BuildConfig.VERSION_CODE }
                    if (list.isNotEmpty()) androidx.compose.material3.AlertDialog(
                        onDismissRequest = { vm.dismissWhatsNew() }, containerColor = c.card,
                        title = { Text("What's new", color = c.text) },
                        text = { no.lionflow.app.ui.screens.ChangeList(list, Modifier.heightIn(max = 420.dp)) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.dismissWhatsNew() }) { Text("OK", color = c.accentInk) } },
                    )
                }
                val upd = ui.update
                if (upd != null && !ui.updateDismissed) {
                    val busy = ui.updateStatus?.let { it.startsWith("Downloading") || it.startsWith("Installing") } == true
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { if (!busy) vm.dismissUpdate() },
                        containerColor = c.card,
                        icon = { LfIcon(LfIcons.Download, c.accentInk, 28.dp) },
                        title = { Text("Update available", color = c.text) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("LionFlow ${upd.name} is ready. You have ${no.lionflow.app.BuildConfig.VERSION_NAME}.", style = Type.body, color = c.text)
                                // Everything since the installed version, not only the newest release.
                                LaunchedEffect(Unit) { vm.loadReleases() }
                                val since = vm.releases.filter { it.versionCode > no.lionflow.app.BuildConfig.VERSION_CODE }
                                if (since.isNotEmpty()) no.lionflow.app.ui.screens.ChangeList(since, Modifier.heightIn(max = 280.dp))
                                else no.lionflow.app.update.Updater.cleanNotes(upd.notes).takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, color = c.muted) }
                                ui.updateStatus?.let { st ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (busy) Busy()
                                        Text(st, style = Type.small, color = c.text2)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(enabled = !busy, onClick = {
                                if (ui.needsInstallPermission) platform.openInstallPermission() else vm.installUpdate()
                            }) { Text(if (ui.needsInstallPermission) "Allow" else "Install now", color = if (busy) c.muted else c.accentInk) }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(enabled = !busy, onClick = { vm.dismissUpdate() }) { Text("Later", color = c.muted) }
                        },
                    )
                }
                pinFor?.let { (label, action) ->
                    PinDialog(
                        title = label,
                        check = { vm.checkPin(it) },
                        onDismiss = { pinFor = null },
                        onSuccess = { pinFor = null; action() },
                    )
                }
                ui.commandInProgress?.let { label ->
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(top = 12.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.TopCenter) {
                        Row(
                            Modifier.widthIn(max = 520.dp).softShadow(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(16.dp))
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Busy()
                            Text("$label · ${ui.commandProgress ?: "Sending…"}", style = Type.small, color = c.text)
                        }
                    }
                }
                ui.message?.let { msg ->
                    LaunchedEffect(msg) { delay(if (msg.length > 60) 9000 else 4000); vm.clearMessage() }
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 100.dp, start = 16.dp, end = 16.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.widthIn(max = 520.dp).softShadow(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(if (c.isDark) Color(0xFFF2F3F5) else Color(0xFF16181B))
                                .clickable { vm.clearMessage() }.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) { Text(msg, style = Type.small, color = if (c.isDark) Color(0xFF111214) else Color(0xFFF2F3F5)) }
                    }
                }
            }
        }
    }
}

private data class NavItem(val tab: Tab, val label: String, val icon: ImageVector)

/** All tabs in menu order. The phone menu shows as many as fit; the rest go under "More". */
private val allTabs = listOf(
    NavItem(Tab.Home, "Home", LfIcons.Home),
    NavItem(Tab.Charging, "Charging", LfIcons.Bolt),
    NavItem(Tab.Climate, "Climate", LfIcons.Thermo),
    NavItem(Tab.Trips, "Trips", LfIcons.Route),
    NavItem(Tab.Map, "Map", LfIcons.Map),
    NavItem(Tab.Service, "Service", LfIcons.Wrench),
    NavItem(Tab.Settings, "Settings", LfIcons.Gear),
)
private val railTabs = listOf(
    NavItem(Tab.Home, "Home", LfIcons.Home),
    NavItem(Tab.Charging, "Charging", LfIcons.Bolt),
    NavItem(Tab.Map, "Map", LfIcons.Map),
    NavItem(Tab.Climate, "Climate", LfIcons.Thermo),
    NavItem(Tab.Trips, "Trips", LfIcons.Route),
    NavItem(Tab.Service, "Service", LfIcons.Wrench),
    NavItem(Tab.Settings, "Settings", LfIcons.Gear),
)

@Composable
private fun MainShell(vm: AppViewModel, ui: UiState, settings: AppSettings) {
    val c = LocalLf.current
    BackHandler(enabled = ui.tab != Tab.Home) { vm.setTab(Tab.Home) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val screenWidth = maxWidth
        val expanded = maxWidth >= 900.dp
        if (wide) {
            // Tablet / unfolded foldable: the navigation sits on the right, within thumb reach, with Home at the bottom.
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(vm, ui, settings, wide = true, expanded = expanded) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.navLine))
                Column(
                    Modifier.width(88.dp).fillMaxHeight().softShadow(androidx.compose.ui.graphics.RectangleShape, 8.dp).background(c.nav).windowInsetsPadding(WindowInsets.systemBars).padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LionLogo(32.dp)
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        for (item in railTabs.filter { Caps.tab(it.tab, ui.status) }.reversed()) RailItem(item, ui.tab == item.tab) { vm.setTab(item.tab) }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(vm, ui, settings, wide = false, expanded = false) }
                Row(
                    Modifier.fillMaxWidth().background(c.bg).windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .softShadow(RoundedCornerShape(30.dp), 12.dp).clip(RoundedCornerShape(30.dp)).background(c.card).border(1.dp, c.line, RoundedCornerShape(30.dp))
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    // About 72 dp per button: show all if they fit, otherwise keep room for "More".
                    val fit = ((screenWidth - 40.dp) / 72.dp).toInt()
                    val tabs = allTabs.filter { Caps.tab(it.tab, ui.status) }
                    val shown = if (fit >= tabs.size) tabs else tabs.take((fit - 1).coerceAtLeast(3))
                    val moreTabs = tabs - shown.toSet()
                    for (item in shown) {
                        PhoneNavItem(item.icon, item.label, ui.tab == item.tab, Modifier.weight(1f)) {
                            vm.setTab(item.tab); if (item.tab == Tab.Trips) vm.loadTrips()
                        }
                    }
                    if (moreTabs.isNotEmpty()) {
                    var moreOpen by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        PhoneNavItem(LfIcons.Menu, "More", moreTabs.any { it.tab == ui.tab }, Modifier.fillMaxWidth()) { moreOpen = true }
                        // Anchored to the far-right button, so the list lines up with the right edge.
                        androidx.compose.material3.DropdownMenu(
                            expanded = moreOpen, onDismissRequest = { moreOpen = false },
                            containerColor = c.card, shape = RoundedCornerShape(20.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, c.line),
                            offset = androidx.compose.ui.unit.DpOffset(0.dp, (-8).dp),
                        ) {
                            for (item in moreTabs) {
                                val active = ui.tab == item.tab
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(item.label, style = Type.body.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal), color = if (active) c.accentInk else c.text) },
                                    leadingIcon = { LfIcon(item.icon, if (active) c.accentInk else c.text2, 22.dp) },
                                    onClick = { moreOpen = false; vm.setTab(item.tab); if (item.tab == Tab.Trips) vm.loadTrips() },
                                    modifier = Modifier.widthIn(min = 200.dp),
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneNavItem(icon: ImageVector, label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalLf.current
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Tab, onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(52.dp).height(32.dp).clip(CircleShape).background(if (active) c.accent else Color.Transparent), contentAlignment = Alignment.Center) {
            LfIcon(icon, if (active) c.onAccent else c.text2, 22.dp, label)
        }
        Text(label, style = Type.tiny.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium), color = if (active) c.accentInk else c.text2, maxLines = 1)
    }
}

@Composable
private fun RailItem(item: NavItem, active: Boolean, onClick: () -> Unit) {
    val c = LocalLf.current
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Tab, onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.width(56.dp).height(32.dp).clip(CircleShape).background(if (active) c.accent else Color.Transparent), contentAlignment = Alignment.Center) {
            LfIcon(item.icon, if (active) c.onAccent else c.muted, 22.dp, item.label)
        }
        Text(item.label, style = Type.tiny.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium), color = if (active) c.text else c.muted)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabContent(vm: AppViewModel, ui: UiState, settings: AppSettings, wide: Boolean, expanded: Boolean) {
    // Pull down to refresh on every tab except the map (dragging there moves the map).
    if (ui.tab == Tab.Map) { MapScreen(vm, ui, settings); return }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = ui.refreshing,
        onRefresh = { vm.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) { TabScreens(vm, ui, settings, wide, expanded) }
}

@Composable
private fun TabScreens(vm: AppViewModel, ui: UiState, settings: AppSettings, wide: Boolean, expanded: Boolean) {
    when (ui.tab) {
        Tab.Home -> HomeScreen(vm, ui, settings, wide, expanded)
        Tab.Charging -> ChargingScreen(vm, ui, settings, wide)
        Tab.Map -> MapScreen(vm, ui, settings)
        Tab.Climate -> ClimateScreen(vm, ui, settings, wide)
        Tab.Trips -> TripsScreen(vm, ui, settings, wide)
        Tab.Service -> ServiceScreen(vm, ui, settings, wide)
        Tab.Settings -> SettingsScreen(vm, ui, settings)
    }
}

/** Shared screen container: scrolls, respects system bars, and keeps content at a readable width on big screens. */
@Composable
fun ScreenColumn(wide: Boolean = false, maxWidth: Int = 720, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = if (wide) maxWidth.dp else 10_000.dp)
                .fillMaxWidth()
                .windowInsetsPadding(if (wide) WindowInsets.systemBars else WindowInsets.statusBars)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

// ---------------- formatting helpers ----------------

fun fmtDistance(km: Double?, s: AppSettings): String {
    if (km == null) return "—"
    return if (s.distanceUnit == "mi") String.format(java.util.Locale.US, "%,d mi", Math.round(km * 0.621371))
    else String.format(java.util.Locale.US, "%,d km", Math.round(km))
}

/** A speed in the unit chosen for distances: "80 km/h" or "50 mph". */
fun fmtSpeed(kmh: Double, s: AppSettings): String =
    if (s.distanceUnit == "mi") "${Math.round(kmh * 0.621371)} mph" else "${Math.round(kmh)} km/h"

fun fmtTemp(c: Double?, s: AppSettings, unit: Boolean = true): String {
    if (c == null || c.isNaN()) return "—"
    return if (s.tempUnit == "f") "${Math.round(c * 9 / 5 + 32)}°${if (unit) "F" else ""}" else "${Math.round(c)}°${if (unit) "C" else ""}"
}

fun minutesAgo(then: Long, now: Long): String {
    if (then <= 0) return "not updated yet"
    val m = (now - then) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / 1440} d ago"
    }
}

@Composable
fun Busy(modifier: Modifier = Modifier) {
    val c = LocalLf.current
    CircularProgressIndicator(modifier.size(20.dp), color = c.accentInk, strokeWidth = 2.dp)
}

@Suppress("unused")
private fun Modifier.ring(c: Color) = this.border(1.dp, c, CircleShape)
