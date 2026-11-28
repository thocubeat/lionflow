package no.lionflow.app.car

import android.graphics.Canvas
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.car.ui.GarageModel
import no.lionflow.app.car.ui.GaragePaint
import no.lionflow.app.car.ui.Hit
import no.lionflow.app.car.ui.Kit
import no.lionflow.app.car.ui.Tone
import no.lionflow.app.door.DoorState
import no.lionflow.app.door.GarageDoor
import no.lionflow.app.ui.l10n.tr

/** Sends the command, says how it went, and goes back to where the button was. */
private fun Screen.garage(open: Boolean) {
    val ctx = carContext
    GarageDoor.sendThen(open) { msg -> try { CarToast.makeText(ctx, tr(msg), CarToast.LENGTH_LONG).show() } catch (_: Exception) {} }
    try { screenManager.pop() } catch (_: Exception) {}
}

private fun garageStatus(): Pair<String, Int> {
    val st = GarageDoor.state.value
    val text = GarageDoor.label(st).ifEmpty { GarageDoor.kindName(GarageDoor.config.value?.kind) }
    return tr(text) to if (st == DoorState.OPEN) Tone.WARN else Tone.MUTED
}

/** The question after the garage button: open, close or cancel. Nothing moves before one is chosen. */
internal class GarageScreen(ctx: CarContext, ui: CarUi) : DrawnScreen(ctx, ui) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { combine(GarageDoor.state, GarageDoor.config) { _, _ -> Unit }.collect { redraw() } }
        }
    }

    override fun shown() = GarageDoor.refreshSoon()

    override fun buttons(): List<Btn> = emptyList()

    override fun draw(c: Canvas, k: Kit, pressed: String?, hits: MutableList<Hit>) {
        val (status, tone) = garageStatus()
        val open = GarageDoor.state.value == DoorState.OPEN
        GaragePaint.draw(c, k, GarageModel(tr("Garage door"), status, tone, listOf(
            GarageModel.Choice("open", tr("Open"), main = !open),
            GarageModel.Choice("close", tr("Close"), main = open),
            GarageModel.Choice("cancel", tr("Cancel"), R.drawable.ic_car_close),
        )), pressed, hits)
    }

    override fun tapped(key: String) {
        when (key) {
            "open" -> garage(true)
            "close" -> garage(false)
            "cancel" -> goBack()
        }
    }
}

/** The same question as a plain list, for cars whose screen can't be touched. */
internal class GarageListScreen(ctx: CarContext) : Screen(ctx) {
    init {
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { GarageDoor.state.collect { invalidate() } } }
        GarageDoor.refreshSoon()
    }

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        list.addItem(Row.Builder().setTitle(tr("Open")).setImage(carContext.icon(R.drawable.ic_car_garage)).setOnClickListener { garage(true) }.build())
        list.addItem(Row.Builder().setTitle(tr("Close")).setImage(carContext.icon(R.drawable.ic_car_garage)).setOnClickListener { garage(false) }.build())
        list.addItem(Row.Builder().setTitle(tr("Cancel")).setImage(carContext.icon(R.drawable.ic_car_close)).setOnClickListener { screenManager.pop() }.build())
        val status = garageStatus().first
        return ListTemplate.Builder().setTitle(tr("Garage door") + if (status.isNotBlank()) " · $status" else "")
            .setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
