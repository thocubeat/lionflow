package no.lionflow.app.ui

import no.lionflow.app.model.VehicleStatus

/** What the current car can do, so screens can hide what doesn't apply. Unknown (no status yet) = show. */
object Caps {
    fun battery(st: VehicleStatus?) = st == null || !st.usesFuelGauge
    fun fuel(st: VehicleStatus?) = st != null && st.energyType != "Electric"
    fun position(st: VehicleStatus?) = st?.lat != null && st.lon != null
    fun climate(st: VehicleStatus?) = st == null || st.raw.has("preconditioning") || st.raw.has("airConditioning") || !st.usesFuelGauge
    fun lockState(st: VehicleStatus?) = st == null || st.locked != null

    fun tab(tab: Tab, st: VehicleStatus?) = when (tab) {
        Tab.Charging -> battery(st)
        Tab.Map -> battery(st) || position(st)
        Tab.Climate -> climate(st)
        else -> true
    }
}
