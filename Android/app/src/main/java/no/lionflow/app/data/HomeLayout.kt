package no.lionflow.app.data

/**
 * What the home screens show, and in which order: the phone's cards, the watch's rows, and Android Auto's tiles and
 * status lines. Kept as plain lists of ids in the settings, so a part added later simply shows up at the end.
 */
object HomeLayout {
    /** [needs]: "battery" for parts only cars with a traction battery have, "fuel" for cars with a tank, "" for all. */
    data class Item(val id: String, val title: String, val note: String = "", val needs: String = "")

    val PHONE = listOf(
        Item("warnings", "Warnings from the car", "Only there when the car reports one"),
        Item("battery", "Battery or fuel level"),
        Item("lock", "Locked or unlocked"),
        Item("actions", "Lock, lights, horn and Find car"),
        Item("parked", "Where the car is parked", "Address, which way it faces, walking directions"),
        Item("tripsClimate", "Trips and climate"),
        Item("service", "Next service"),
        Item("fuel", "Fuel this month", "Distance, litres and cost", needs = "fuel"),
        Item("charging", "Charging", "State, speed and limit", needs = "battery"),
        Item("monthCost", "Charging this month", "Energy and cost, home and away", needs = "battery"),
        Item("cardata", "Car data", "Odometer, outside temperature, 12 V battery and all the rest"),
    )
    const val PHONE_HIDDEN = "charging,monthCost"

    val WATCH = listOf(
        Item("distance", "Distance today and yesterday"),
        Item("lock", "Lock and unlock"),
        Item("flash", "Flash the lights"),
        Item("preheat", "Pre-heating", needs = "battery"),
        Item("place", "Where the car is"),
        Item("doors", "Doors and windows"),
        Item("odometer", "Odometer"),
        Item("outside", "Outside temperature"),
        Item("service", "Next service"),
    )
    const val WATCH_HIDDEN = "doors,odometer,outside,service"

    val CAR = listOf(
        Item("plan", "Plan a trip", needs = "battery"),
        Item("chargers", "Chargers and fuel stations"),
        Item("saved", "Saved routes"),
        Item("trips", "Previous trips"),
        Item("info", "Car Info"),
        Item("speed", "Speed", "A line next to the battery"),
        Item("doors", "Doors and lock", "A line next to the battery"),
        Item("charging", "Charging state", "A line next to the battery", needs = "battery"),
        Item("outside", "Outside temperature", "A line next to the battery"),
        Item("odometer", "Odometer", "A line next to the battery"),
        Item("service", "Next service", "A line next to the battery"),
    )
    const val CAR_HIDDEN = "odometer,service"

    fun ids(csv: String): List<String> = csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun hidden(csv: String): Set<String> = ids(csv).toSet()

    /** [all] in the user's [order]; parts the order doesn't mention keep their usual place at the end. */
    fun ordered(all: List<Item>, order: String): List<Item> {
        val o = ids(order)
        return all.sortedBy { item -> o.indexOf(item.id).let { if (it < 0) 1000 + all.indexOf(item) else it } }
    }

    /** The phone's cards to draw, top to bottom. */
    fun phoneCards(s: AppSettings): List<String> {
        val h = hidden(s.homeHidden)
        return ordered(PHONE, s.homeOrder).map { it.id }.filter { it !in h }
    }

    /** Moves [id] one step up or down among the parts that apply to this car, and returns the new order. */
    fun move(all: List<Item>, order: String, id: String, up: Boolean, applies: (Item) -> Boolean): String {
        val list = ordered(all, order).toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return order
        var j = i + if (up) -1 else 1
        while (j in list.indices && !applies(list[j])) j += if (up) -1 else 1
        if (j !in list.indices) return order
        val item = list.removeAt(i)
        list.add(j, item)
        return list.joinToString(",") { it.id }
    }

    fun toggle(hiddenCsv: String, id: String, show: Boolean): String {
        val h = ids(hiddenCsv).toMutableList()
        if (show) h.remove(id) else if (id !in h) h.add(id)
        return h.joinToString(",")
    }
}
