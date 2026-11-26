import Foundation

/// The Stellantis brands whose connected-car app LionFlow can sign in to.
/// All of them use the same backend; only the sign-in settings, names and subscription packs differ.
/// "Peugeot" in any text is swapped for the chosen brand by `Brands.apply`.
enum Brand: String, CaseIterable {
    case peugeot, citroen, ds, opel, vauxhall

    var id: String { rawValue }

    /** Key in stellantis_configs.json. */
    var jsonKey: String {
        switch self {
        case .peugeot: return "MyPeugeot"
        case .citroen: return "MyCitroen"
        case .ds: return "MyDS"
        case .opel: return "MyOpel"
        case .vauxhall: return "MyVauxhall"
        }
    }

    /** "Citroën" */
    var display: String {
        switch self {
        case .peugeot: return "Peugeot"
        case .citroen: return "Citroën"
        case .ds: return "DS"
        case .opel: return "Opel"
        case .vauxhall: return "Vauxhall"
        }
    }

    /** "MyCitroën" */
    var app: String {
        switch self {
        case .peugeot: return "MyPeugeot"
        case .citroen: return "MyCitroën"
        case .ds: return "MyDS"
        case .opel: return "myOpel"
        case .vauxhall: return "MyVauxhall"
        }
    }

    var workshopQuery: String {
        switch self {
        case .peugeot: return "Peugeot+verksted"
        case .citroen: return "Citroën+verksted"
        case .ds: return "DS+Automobiles+verksted"
        case .opel: return "Opel+verksted"
        case .vauxhall: return "Vauxhall+garage"
        }
    }

    /** The subscription that gives remote charging and pre-conditioning (electric / plug-in cars). */
    var eRemotePack: String {
        switch self {
        case .peugeot: return "Remote e-Controls (in the Connect ONE pack on new electric and plug-in hybrid cars; older cars need the e-Remote Control or Connect PLUS subscription)"
        case .citroen: return "e-Remote Control (in the Connect ONE pack on new electric and plug-in hybrid cars; older cars need the e-Remote Control or Connect PLUS subscription)"
        case .ds: return "e-Remote Control (in the Connect ONE pack on new electric and plug-in hybrid cars; older cars need the E-TENSE Remote Control or Connect PLUS subscription)"
        case .opel, .vauxhall: return "e-Remote Control (Connect PLUS pack)"
        }
    }

    /** The subscription that gives lock/unlock, lights and horn. */
    var doorsPack: String { "Remote Control (Connect PLUS pack)" }

    static func of(_ id: String?) -> Brand { allCases.first { $0.id == id } ?? .peugeot }

    /** From the car's own "brand" field, e.g. "Citroen", "DS", "Opel". */
    static func fromCar(_ name: String?) -> Brand? {
        guard let n = name?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: "ë", with: "e") else { return nil }
        return allCases.first { $0.display.lowercased().replacingOccurrences(of: "ë", with: "e") == n || $0.id == n }
    }
}

enum Brands {
    private static let lock = NSLock()
    private static var value: Brand = .peugeot

    static var current: Brand {
        get { lock.lock(); defer { lock.unlock() }; return value }
        set { lock.lock(); value = newValue; lock.unlock() }
    }

    /** Texts are written with "Peugeot"/"MyPeugeot"; show the chosen brand's names instead. */
    static func apply(_ text: String) -> String {
        let b = current
        if b == .peugeot || !text.contains("eugeot") || text.contains("affiliated") { return text }
        return text.replacingOccurrences(of: "MyPeugeot", with: b.app).replacingOccurrences(of: "Peugeot", with: b.display)
    }

    /** What needs a paid or activated service, in plain words, for the chosen brand. */
    static func subscriptionNotice(_ b: Brand = Brands.current) -> String {
        "What needs a subscription (decided by \(b.display), not by LionFlow):\n" +
        "• Status, battery, range, trips and service: your \(b.app) account.\n" +
        "• Charging control and pre-heating (electric and plug-in hybrid cars): \(b.eRemotePack).\n" +
        "• Lock, unlock, lights and horn: \(b.doorsPack).\n" +
        "Which pack is included, and for how long, depends on the model, country and order date. Check and activate it in \(b.app); LionFlow can't activate it for you."
    }
}
