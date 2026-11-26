package no.lionflow.app.model

/**
 * The Stellantis brands whose connected-car app LionFlow can sign in to.
 * All of them use the same backend; only the sign-in settings, names and subscription packs differ.
 * "Peugeot" in any text is swapped for the chosen brand by [Brands.apply].
 */
enum class Brand(
    val id: String,
    val jsonKey: String,      // key in stellantis_configs.json
    val display: String,      // "Citroën"
    val app: String,          // "MyCitroën"
    val workshopQuery: String,
    /** The subscription that gives remote charging and pre-conditioning (electric / plug-in cars). */
    val eRemotePack: String,
    /** The subscription that gives lock/unlock, lights and horn. */
    val doorsPack: String,
) {
    PEUGEOT("peugeot", "MyPeugeot", "Peugeot", "MyPeugeot", "Peugeot+verksted",
        "Remote e-Controls (in the Connect ONE pack on new electric and plug-in hybrid cars; older cars need the e-Remote Control or Connect PLUS subscription)",
        "Remote Control (Connect PLUS pack)"),
    CITROEN("citroen", "MyCitroen", "Citroën", "MyCitroën", "Citroën+verksted",
        "e-Remote Control (in the Connect ONE pack on new electric and plug-in hybrid cars; older cars need the e-Remote Control or Connect PLUS subscription)",
        "Remote Control (Connect PLUS pack)"),
    DS("ds", "MyDS", "DS", "MyDS", "DS+Automobiles+verksted",
        "e-Remote Control (in the Connect ONE pack on new electric and plug-in hybrid cars; older cars need the E-TENSE Remote Control or Connect PLUS subscription)",
        "Remote Control (Connect PLUS pack)"),
    OPEL("opel", "MyOpel", "Opel", "myOpel", "Opel+verksted",
        "e-Remote Control (Connect PLUS pack)",
        "Remote Control (Connect PLUS pack)"),
    VAUXHALL("vauxhall", "MyVauxhall", "Vauxhall", "MyVauxhall", "Vauxhall+garage",
        "e-Remote Control (Connect PLUS pack)",
        "Remote Control (Connect PLUS pack)");

    companion object {
        fun of(id: String?): Brand = entries.firstOrNull { it.id == id } ?: PEUGEOT
        /** From the car's own "brand" field, e.g. "Citroen", "DS", "Opel". */
        fun fromCar(name: String?): Brand? {
            val n = name?.trim()?.lowercase()?.replace("ë", "e") ?: return null
            return entries.firstOrNull { it.display.lowercase().replace("ë", "e") == n || it.id == n }
        }
    }
}

object Brands {
    @Volatile var current: Brand = Brand.PEUGEOT

    /** Texts are written with "Peugeot"/"MyPeugeot"; show the chosen brand's names instead. */
    fun apply(text: String): String {
        val b = current
        if (b == Brand.PEUGEOT || !text.contains("eugeot") || text.contains("affiliated")) return text
        return text.replace("MyPeugeot", b.app).replace("Peugeot", b.display)
    }

    /** What needs a paid or activated service, in plain words, for the chosen brand. */
    fun subscriptionNotice(b: Brand = current): String =
        "What needs a subscription (decided by ${b.display}, not by LionFlow):\n" +
        "• Status, battery, range, trips and service: your ${b.app} account.\n" +
        "• Charging control and pre-heating (electric and plug-in hybrid cars): ${b.eRemotePack}.\n" +
        "• Lock, unlock, lights and horn: ${b.doorsPack}.\n" +
        "Which pack is included, and for how long, depends on the model, country and order date. Check and activate it in ${b.app}; LionFlow can't activate it for you."
}
