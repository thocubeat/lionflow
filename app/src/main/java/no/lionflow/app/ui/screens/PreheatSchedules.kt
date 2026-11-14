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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
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
import no.lionflow.app.model.Program
import no.lionflow.app.ui.AppViewModel
import no.lionflow.app.ui.LocalConfirm
import no.lionflow.app.ui.UiState
import no.lionflow.app.ui.components.Divider
import no.lionflow.app.ui.components.LfCard
import no.lionflow.app.ui.components.Toggle
import no.lionflow.app.ui.theme.LocalLf
import no.lionflow.app.ui.theme.Type
import java.util.Locale

private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun daysText(days: List<String>): String = when {
    days.size == 7 -> "Every day"
    days.toSet() == DAYS.take(5).toSet() -> "Monday to Friday"
    days.toSet() == setOf("Sat", "Sun") -> "Weekends"
    days.isEmpty() -> "No days"
    else -> DAYS.filter { it in days }.joinToString(", ")
}

/** Pre-heating schedules, stored in the car's 4 program slots (they run even if the phone is off). */
@Composable
fun PreheatSchedules(vm: AppViewModel, ui: UiState) {
    val c = LocalLf.current
    val confirm = LocalConfirm.current
    val st = ui.status
    val programs = st?.programs.orEmpty().sortedBy { it.slot }
    var editing by remember { mutableStateOf<Program?>(null) }
    val busy = ui.commandInProgress != null

    fun save(list: List<Program>, label: String) = confirm(label) { vm.savePrograms(list) }

    LfCard(padding = PaddingValues(0.dp)) {
        Text("Pre-heating schedules", style = Type.label, color = c.text, modifier = Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp))
        Text("The car makes the cabin warm (or cool) by the time you set.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp))
        if (programs.isEmpty()) {
            Text("No schedules yet.", style = Type.small, color = c.muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        }
        for (p in programs) {
            Divider()
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { editing = p }.padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Ready at " + String.format(Locale.US, "%02d:%02d", p.hour, p.minute), style = Type.stat, color = if (p.enabled) c.text else c.muted)
                    Text(daysText(p.days), style = Type.small, color = c.muted)
                }
                Toggle(p.enabled, "Schedule on") { on ->
                    if (!busy) save(programs.map { if (it.slot == p.slot) it.copy(enabled = on) else it }, if (on) "Turn schedule on" else "Turn schedule off")
                }
            }
        }
        Divider()
        if (programs.size < 4) {
            Text(
                "+ Add schedule", style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = if (st == null || busy) c.muted else c.accentInk,
                modifier = Modifier.fillMaxWidth().clickable(enabled = st != null && !busy) {
                    val slot = (1..4).first { s -> programs.none { it.slot == s } }
                    editing = Program(slot, DAYS.take(5), 7, 0, true)
                }.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        } else {
            Text("The car has room for 4 schedules.", style = Type.tiny, color = c.muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }

    editing?.let { p ->
        val isNew = programs.none { it.slot == p.slot }
        ScheduleEditor(p, isNew,
            onSave = { np -> editing = null; save(programs.filter { it.slot != np.slot } + np, if (isNew) "Add schedule" else "Save schedule") },
            onDelete = { editing = null; save(programs.filter { it.slot != p.slot }, "Delete schedule") },
            onClose = { editing = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(p: Program, isNew: Boolean, onSave: (Program) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    val c = LocalLf.current
    val time = rememberTimePickerState(initialHour = p.hour.coerceIn(0, 23), initialMinute = p.minute.coerceIn(0, 59), is24Hour = true)
    var days by remember { mutableStateOf(p.days.toSet()) }
    AlertDialog(
        onDismissRequest = onClose, containerColor = c.card,
        title = { Text(if (isNew) "New schedule" else "Edit schedule", color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Ready at", style = Type.small, color = c.muted)
                TimeInput(state = time, colors = TimePickerDefaults.colors(
                    timeSelectorSelectedContainerColor = c.raised, timeSelectorUnselectedContainerColor = c.raised,
                    timeSelectorSelectedContentColor = c.text, timeSelectorUnselectedContentColor = c.text,
                ))
                Text("Days", style = Type.small, color = c.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DAYS.forEach { d ->
                        val on = d in days
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (on) c.accent else c.raised)
                                .border(1.dp, if (on) c.accent else c.line, CircleShape)
                                .clickable { days = if (on) days - d else days + d },
                            contentAlignment = Alignment.Center,
                        ) { Text(d.take(2), style = Type.tiny.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.onAccent else c.text) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    listOf("Weekdays" to DAYS.take(5), "Weekends" to listOf("Sat", "Sun"), "Every day" to DAYS).forEach { (label, set) ->
                        Text(label, style = Type.small.copy(fontWeight = FontWeight.SemiBold), color = c.accentInk, modifier = Modifier.clickable { days = set.toSet() })
                    }
                }
                Text("The car decides when to start so the cabin is ready at this time. Peugeot doesn't let apps choose how long it runs.", style = Type.tiny, color = c.muted)
            }
        },
        confirmButton = {
            TextButton(enabled = days.isNotEmpty(), onClick = {
                onSave(p.copy(hour = time.hour, minute = time.minute, days = DAYS.filter { it in days }, enabled = true))
            }) { Text("Save", color = if (days.isEmpty()) c.muted else c.accentInk) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text("Delete", color = c.danger) }
                TextButton(onClick = onClose) { Text("Cancel", color = c.muted) }
            }
        },
    )
}
