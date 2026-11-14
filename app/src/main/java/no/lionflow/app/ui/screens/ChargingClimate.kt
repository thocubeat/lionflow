package no.lionflow.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import no.lionflow.app.api.WeatherApi
import no.lionflow.app.data.AppSettings
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalConfirm
import no.lionflow.app.ui.LocalPlatform
import no.lionflow.app.ui.ScreenColumn
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.LfIcon
import no.lionflow.app.ui.components.LfIcons
import no.lionflow.app.ui.components.Pill
import no.lionflow.app.ui.components.PrimaryButton
import no.lionflow.app.ui.components.Ring
import no.lionflow.app.ui.components.ScreenHeader
import no.lionflow.app.ui.components.SecondaryButton
import no.lionflow.app.ui.fmtDistance
import no.lionflow.app.ui.fmtTemp
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChargingScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    ScreenColumn(wide) {
        ScreenHeader(if (st?.plugged == true) "Plugged in" else "Not plugged in", "Charging")
        Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
            Ring(((st?.batteryPct ?: 0.0) / 100).toFloat(), c.meter, 210.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(buildAnnotatedString {
                    append(st?.batteryPct?.let { "${it.toInt()}" } ?: "—")
                    withStyle(SpanStyle(color = c.muted, fontSize = 24.sp)) { append(" %") }
                }, style = Type.big.copy(fontSize = 54.sp), color = c.text)
                Text("≈ ${fmtDistance(st?.rangeKm, s)}", style = Type.small.copy(fontSize = 14.sp), color = c.muted)
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.okBg).border(1.dp, c.okLine, RoundedCornerShape(18.dp)).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            LfIcon(LfIcons.Bolt, c.accentInk, 20.dp)
            Text(chargingLine(st), style = Type.small.copy(fontSize = 14.sp), color = c.okText)
        }
        LfCard {
            DetailRow("Status", when (st?.chargingStatus) {
                "InProgress" -> "Charging"; "Finished" -> "Finished"; "Stopped" -> "Paused"; "Disconnected" -> "Unplugged"; "Failure" -> "Error"; null -> "—"; else -> st?.chargingStatus ?: "—"
            })
            Divider()
            DetailRow("Charging power", no.lionflow.app.model.ChargePower.label(st) ?: if (st?.isCharging == true) "Calculating…" else "—")
            Divider()
            DetailRow("Range added per hour", st?.chargingRateKmh?.takeIf { it > 0 }?.let { "${fmtDistance(it, s)}/h" } ?: "—")
            Divider()
            DetailRow("Type", when (st?.chargingMode) { "Quick" -> "Fast (DC)"; "Slow" -> "AC"; else -> "—" })
            Divider()
            DetailRow("Scheduled start", st?.nextDelayedHour?.let { String.format(Locale.US, "%02d:%02d", it, st?.nextDelayedMinute ?: 0) } ?: "Not set")
            Divider()
            DetailRow("Battery health", st?.batteryHealthPct?.let { "${it.toInt()} %" } ?: "—")
        }
        PrimaryButton(
            text = when {
                ui.commandInProgress?.contains("harg") == true -> "Sending…"
                st?.isCharging == true -> "Stop charging"
                else -> "Charge now"
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = st?.plugged == true && ui.commandInProgress == null,
        ) {
            val start = st?.isCharging != true
            confirm(if (start) "Start charging now" else "Stop charging") { vm.charging(start) }
        }
        Text(
            "Charging power is worked out by LionFlow (the car only reports range per hour): measured from the energy added between two car reports, or estimated from the time left until then. Scheduled start time is set in the car or in the MyPeugeot app; “Stop charging” waits until the scheduled time.",
            style = Type.tiny, color = c.muted,
        )
        ChargeLimitCard(vm, ui, s)
        ChargeLogSection(ui, s)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = LocalLf.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.small.copy(fontSize = 14.sp), color = c.muted, modifier = Modifier.weight(1f))
        Text(value, style = Type.label, color = c.text)
    }
}

@Composable
fun ClimateScreen(vm: AppViewModel, ui: UiState, s: AppSettings, wide: Boolean) {
    val c = LocalLf.current
    val st = ui.status
    val confirm = LocalConfirm.current
    val platform = LocalPlatform.current
    ScreenColumn(wide) {
        ScreenHeader("Outside ${fmtTemp(st?.outsideTempC ?: ui.weather?.tempC, s)}", "Climate")
        LfCard(padding = PaddingValues(20.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LfIcon(LfIcons.Fan, if (st?.preconditioningOn == true) c.orangeInk else c.muted, 40.dp)
                Text(if (st?.preconditioningOn == true) "Pre-heating is on" else "Pre-heating is off", style = Type.stat, color = c.text)
                PrimaryButton(
                    text = when {
                        ui.commandInProgress?.contains("heating") == true -> "Sending…"
                        st?.preconditioningOn == true -> "Stop pre-heating"
                        else -> "Start pre-heating now"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = c.orange,
                    enabled = st != null && ui.commandInProgress == null,
                ) {
                    val on = st?.preconditioningOn != true
                    confirm(if (on) "Start pre-heating" else "Stop pre-heating") { vm.preconditioning(on) }
                }
                Text("Heats or cools the cabin for up to about 30 minutes. Uses grid power when plugged in.", style = Type.tiny, color = c.muted)
            }
        }

        PreheatSchedules(vm, ui)

        // Weather where you are
        LfCard {
            val w = ui.weather
            if (w == null) {
                Text("Weather here", style = Type.label, color = c.text)
                Text("Allow location to see the weather and forecast where you are.", style = Type.small, color = c.muted)
                SecondaryButton("Allow location") { platform.requestLocation() }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WeatherIcon(WeatherApi.kind(w.symbol), 30)
                    Column {
                        Text("${fmtTemp(w.tempC, s)} · ${WeatherApi.describe(w.symbol)}", style = Type.stat, color = c.text)
                        Text("Your location · feels like ${fmtTemp(w.feelsLikeC, s)} · wind ${Math.round(w.windMs)} m/s", style = Type.tiny, color = c.muted)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    for (h in w.hours.take(6)) {
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(fmt.format(Date(h.timeMillis)), style = Type.tiny.copy(fontSize = 11.sp), color = c.muted)
                            WeatherIcon(WeatherApi.kind(h.symbol), 18)
                            Text(fmtTemp(h.tempC, s, unit = false), style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        }
                    }
                }
                w.tomorrowMorningC?.let { t ->
                    Text(
                        "Tomorrow 07:00: ${fmtTemp(t, s)}" + if (t < 0) " — pre-heating recommended" else "",
                        style = Type.tiny, color = c.muted,
                    )
                }
                Text("Weather data: MET Norway", style = Type.tiny.copy(fontSize = 11.sp), color = c.muted,
                    modifier = Modifier.clickable { platform.openUrl("https://www.met.no/en/free-meteorological-data") })
            }
        }
    }
}

@Composable
fun WeatherIcon(kind: String, size: Int) {
    val c = LocalLf.current
    when (kind) {
        "sun" -> LfIcon(LfIcons.Sun, c.orangeInk, size.dp)
        "snow" -> LfIcon(LfIcons.Snow, c.blueInk, size.dp)
        "rain" -> LfIcon(LfIcons.Rain, c.blueInk, size.dp)
        else -> LfIcon(LfIcons.Cloud, c.muted, size.dp)
    }
}

private fun daysLabel(days: List<String>): String {
    val all = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val set = days.toSet()
    return when {
        set.containsAll(all) -> "Every day"
        set == setOf("Mon", "Tue", "Wed", "Thu", "Fri") -> "Weekdays"
        set == setOf("Sat", "Sun") -> "Weekends"
        set.isEmpty() -> "Once"
        else -> all.filter { it in set }.joinToString(", ")
    }
}
