package no.lionflow.app.model

import no.lionflow.app.LionFlowApp
import no.lionflow.app.api.Vehicle

/**
 * Every car the user has registered, across accounts and brands, and the switching between them.
 * Used by the app, the widget, the watch and Android Auto, so they all change car the same way.
 */
object Garage {
    data class Entry(val accountId: String, val brand: Brand, val vehicle: Vehicle)

    /** Every car on every signed-in account, duplicates included. */
    fun allEntries(app: LionFlowApp): List<Entry> =
        app.settings.accounts().filter { app.api.isSignedInTo(it.id) }
            .flatMap { a -> app.api.cachedVehiclesOf(a.id).map { Entry(a.id, Brand.of(a.brand), it) } }

    /** Each car once, even when it is on two accounts (shared with family): the open account's entry wins. */
    fun entries(app: LionFlowApp): List<Entry> =
        app.settings.accounts().filter { app.api.isSignedInTo(it.id) }
            .sortedByDescending { it.id == app.settings.activeAccount }
            .flatMap { a -> app.api.cachedVehiclesOf(a.id).map { Entry(a.id, Brand.of(a.brand), it) } }
            .distinctBy { it.vehicle.vin }

    fun hasSeveral(app: LionFlowApp): Boolean = entries(app).size > 1

    fun current(app: LionFlowApp): Entry? {
        val vin = app.api.cachedVehicle()?.vin ?: return null
        return entries(app).firstOrNull { it.accountId == app.settings.activeAccount && it.vehicle.vin == vin }
    }

    fun name(app: LionFlowApp, e: Entry): String =
        if (current(app) == e) app.settings.value.carName else app.settings.nameFor(e.vehicle.vin) ?: (e.brand.display + " …" + e.vehicle.vin.takeLast(4))

    fun next(app: LionFlowApp): Entry? {
        val all = entries(app)
        if (all.size < 2) return null
        val i = all.indexOfFirst { it == current(app) }
        return all[(i + 1) % all.size]
    }

    /** Make [e] the active car. Only changes what is stored; screens that are open pick it up when they next look. */
    fun activate(app: LionFlowApp, e: Entry) {
        val s = app.settings
        current(app)?.let { s.setNameFor(it.vehicle.vin, s.value.carName) }
        if (e.accountId != s.activeAccount) {
            app.remote.disconnect()
            app.api.switchProfile(e.accountId)
            s.activeAccount = e.accountId
            val acc = s.accounts().first { it.id == e.accountId }
            s.update { it.copy(brand = acc.brand, country = acc.country) }
        }
        app.api.selectedVin = e.vehicle.vin
        s.pendingPrograms = ""
        s.update { it.copy(carName = s.nameFor(e.vehicle.vin) ?: ("My " + e.brand.display)) }
        ChargeLog.setVin(e.vehicle.vin)
        ChargePower.setVin(e.vehicle.vin)
        no.lionflow.app.widget.WidgetData.clear(app)
    }
}
