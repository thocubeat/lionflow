package no.lionflow.app.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.lionflow.app.R
import no.lionflow.app.plan.PlanApi
import no.lionflow.app.plan.Place
import no.lionflow.app.ui.l10n.L10n
import no.lionflow.app.ui.l10n.tr

/**
 * Search for a destination in the car, by the on-screen keyboard or the microphone button (as in Google Maps).
 * Picking a place plans the trip from where the car is, with its battery now, and shows it (see [TripScreen]).
 */
internal class TripSearchScreen(ctx: CarContext, private val ui: CarUi, first: String = "") : Screen(ctx) {
    private var query = ""
    private var results: List<Place> = emptyList()
    private var loading = false
    private var job: Job? = null

    init { if (first.isNotBlank()) search(first, true) }

    private fun search(q: String, now: Boolean) {
        query = q
        job?.cancel()
        if (q.trim().length < 2) { results = emptyList(); loading = false; invalidate(); return }
        job = lifecycleScope.launch {
            if (!now) delay(400)
            loading = true; invalidate()
            val st = CarHub.status.value
            results = try { PlanApi.search(q.trim(), st?.lat, st?.lon, L10n.lang) } catch (_: Exception) { emptyList() }
            loading = false; invalidate()
        }
    }

    /** Places starred in the trip planner on the phone. */
    private fun savedPlaces(): List<Place> = try {
        val a = org.json.JSONArray(CarHub.app(carContext).settings.raw("planSaved").ifBlank { "[]" })
        (0 until a.length()).map { i -> a.getJSONObject(i).let { o -> Place(o.optString("n"), o.optString("d"), o.getDouble("la"), o.getDouble("lo"), o.optString("c")) } }
    } catch (_: Exception) { emptyList() }

    private var listening = false

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        val typing = query.trim().length >= 2
        if (results.isEmpty() && typing && !loading) list.setNoItemsMessage(tr("No places found"))
        // With nothing typed: home and the known chargers (from charging costs on the phone), then the starred places.
        val shown: List<Pair<Place, Int>> = if (typing) results.map { it to R.drawable.ic_car_route } else {
            val cfg = no.lionflow.app.model.ChargeLog.places.value
            val chargePlaces = (listOfNotNull(cfg.home?.let { it.copy(name = it.name.ifBlank { tr("Home") }) }) + cfg.known)
                .filter { it.lat != null && it.lon != null }
                .map { Place(it.name.ifBlank { tr("Known charger") }, it.address, it.lat!!, it.lon!!) to R.drawable.ic_car_charger }
            val starred = savedPlaces().filter { sp -> chargePlaces.none { (cp, _) -> no.lionflow.app.plan.Geo.km(cp.lat, cp.lon, sp.lat, sp.lon) < 0.1 } }
            chargePlaces + starred.map { it to R.drawable.ic_car_star }
        }
        if (!typing && shown.isEmpty()) list.setNoItemsMessage(tr("Star places in the trip planner on your phone to see them here."))
        for ((p, ic) in shown.take(12)) {
            list.addItem(Row.Builder().setTitle(p.name.ifBlank { "—" }).apply { if (p.detail.isNotBlank()) addText(p.detail) }
                .setImage(carContext.icon(ic))
                .setOnClickListener { ui.safe("opening the trip") { screenManager.push(TripScreen.plan(carContext, ui, p)) } }.build())
        }
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, false)
            override fun onSearchSubmitted(searchText: String) = search(searchText, true)
        }).setHeaderAction(Action.BACK).setSearchHint(tr(if (listening) "Listening…" else "Where to?")).setShowKeyboardByDefault(false)
            .setInitialSearchText(query)
        // Our own microphone button: listens in the language of the country the car is in.
        if (CarVoice.possible(carContext)) b.setActionStrip(androidx.car.app.model.ActionStrip.Builder().addAction(
            Action.Builder().setIcon(carContext.icon(R.drawable.ic_car_mic)).setOnClickListener {
                if (listening) return@setOnClickListener
                listening = true; invalidate()
                CarVoice.listen(carContext) { text ->
                    listening = false
                    if (text != null) search(text, true)
                    else { androidx.car.app.CarToast.makeText(carContext, tr("Didn't catch that. Try again, or type."), androidx.car.app.CarToast.LENGTH_LONG).show(); invalidate() }
                }
            }.build()).build())
        return if (loading && results.isEmpty()) b.setLoading(true).build() else b.setItemList(list.build()).build()
    }
}
