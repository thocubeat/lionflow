import Foundation
import Combine

// What the Apple Watch app knows and does. This file is part of the watch app, and of the phone app's tests, so the
// two ends of the conversation are tested against each other. It only needs Foundation.

/** One more row chosen on the phone (doors, odometer …). */
struct WatchRow: Equatable {
    var label: String
    var value: String
}

/** What the phone sends back. Texts arrive already formatted in your language and units. */
struct WatchCar: Equatable {
    var name: String
    var battery: String
    var range: String
    var fuel: Bool
    var locked: Bool?
    var place: String
    var today: String
    var yesterday: String
    var updated: String
    var status: String
    var cars: Int = 1
    /** Parts the user turned off on the phone: distance, lock, flash, preheat, place. */
    var hide: Set<String> = []
    var extras: [WatchRow] = []
}

/** The messages between the watch and the phone: the same paths and answers as the Android watch uses. */
enum WatchWire {
    static let statusPath = "/lionflow/status"
    static let statusReply = "/lionflow/status/reply"
    static let cmdPath = "/lionflow/cmd"
    static let cmdReply = "/lionflow/cmd/reply"

    static func object(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    /** Text for a key: a missing key is "", and a number or a yes/no is written out. */
    static func string(_ j: [String: Any], _ key: String) -> String {
        guard let v = j[key] else { return "" }
        if let s = v as? String { return s }
        if v is NSNull { return "null" }
        if let n = v as? NSNumber {
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return n.stringValue
        }
        return ""
    }

    static func bool(_ j: [String: Any], _ key: String, _ fallback: Bool = false) -> Bool {
        guard let v = j[key] else { return fallback }
        if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue }
        if let s = v as? String {
            if s.lowercased() == "true" { return true }
            if s.lowercased() == "false" { return false }
        }
        return fallback
    }

    static func int(_ j: [String: Any], _ key: String, _ fallback: Int) -> Int {
        guard let v = j[key] else { return fallback }
        if let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID() { return n.intValue }
        if let s = v as? String, let d = Double(s) { return Int(d) }
        return fallback
    }

    static func car(_ j: [String: Any]) -> WatchCar {
        var locked: Bool? = nil
        if let v = j["locked"], !(v is NSNull) { locked = bool(j, "locked") }
        var hide = Set<String>()
        for v in j["hide"] as? [Any] ?? [] {
            if let s = v as? String { hide.insert(s) }
        }
        var extras: [WatchRow] = []
        for v in j["extras"] as? [Any] ?? [] {
            if let o = v as? [String: Any] { extras.append(WatchRow(label: string(o, "label"), value: string(o, "value"))) }
        }
        return WatchCar(name: string(j, "name"), battery: string(j, "battery"), range: string(j, "range"), fuel: bool(j, "fuel"),
                        locked: locked, place: string(j, "place"), today: string(j, "today"), yesterday: string(j, "yesterday"),
                        updated: string(j, "updated"), status: string(j, "status"), cars: int(j, "cars", 1), hide: hide, extras: extras)
    }
}

/// Talks to LionFlow on the phone. Your sign-in never leaves the phone: the watch only asks, and shows the answers.
@MainActor
final class WatchModel: ObservableObject {
    @Published private(set) var state: WatchCar? = nil
    @Published private(set) var message: String? = nil
    @Published private(set) var busy = false
    @Published private(set) var noPhone = false

    /** Sends a message to the phone. False when no phone can be reached right now. */
    var send: @MainActor (_ path: String, _ payload: String) -> Bool = { _, _ in false }
    /** Runs something after a number of seconds. */
    var later: @MainActor (_ seconds: Double, _ work: @escaping @MainActor () -> Void) -> Void = { seconds, work in
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
            work()
        }
    }

    private func post(_ path: String, _ payload: String = "") -> Bool {
        let sent = send(path, payload)
        noPhone = !sent
        return sent
    }

    func refresh() {
        busy = true
        if !post(WatchWire.statusPath) { busy = false }
        // If the phone app doesn't answer (closed and not reachable), stop waiting after a while.
        later(30) {
            if self.busy && self.state == nil {
                self.busy = false
                self.noPhone = true
            }
        }
    }

    /** Only offered when more than one car is registered on the phone. */
    func nextCar() {
        busy = true
        message = nil
        if !post(WatchWire.cmdPath, "nextcar") { busy = false }
    }

    func command(_ cmd: String) {
        busy = true
        message = nil
        if !post(WatchWire.cmdPath, cmd) { busy = false }
    }

    /** The phone could not be reached after all (the watch found out a moment after sending). */
    func sendFailed() {
        busy = false
        noPhone = true
    }

    /** A message from the phone. */
    func receive(_ path: String, _ data: String) {
        guard let j = WatchWire.object(data) else { return }
        switch path {
        case WatchWire.statusReply:
            busy = false
            if j["error"] != nil {
                message = WatchWire.string(j, "error")
                return
            }
            state = WatchWire.car(j)
        case WatchWire.cmdReply:
            message = WatchWire.string(j, "text")
            if WatchWire.bool(j, "final", true) {
                busy = false
                later(3) { self.refresh() }
            }
        default:
            break
        }
    }
}

/// The watch's own few words, in the language of the watch (the same words as the Android watch app). Everything
/// about the car arrives from the phone, already in the language chosen there.
enum WatchText {
    static let table: [String: [String: String]] = [
        "nb": [
            "Battery": "Batteri", "Fuel": "Drivstoff", "Range": "Rekkevidde", "Today": "I dag", "Yesterday": "I går",
            "Unlock": "Lås opp", "Lock": "Lås", "Flash lights": "Blink", "Pre-heat": "Forvarm", "Car is at": "Bilen står",
            "Refresh": "Oppdater", "Getting car status…": "Henter bilstatus…",
            "Open LionFlow on your phone. The watch gets its data from there.": "Åpne LionFlow på telefonen. Klokka henter dataene derfra.",
            "Unlock the car?": "Låse opp bilen?", "Lock the car?": "Låse bilen?", "Flash the lights?": "Blinke med lysene?",
            "Start pre-heating?": "Starte forvarming?", "Yes": "Ja", "No": "Nei", "Sending to the car…": "Sender til bilen…",
            "Updated %s": "Oppdatert %s", "Switch car": "Bytt bil",
        ],
        "fr": [
            "Battery": "Batterie", "Fuel": "Carburant", "Range": "Autonomie", "Today": "Aujourd'hui", "Yesterday": "Hier",
            "Unlock": "Déverrouiller", "Lock": "Verrouiller", "Flash lights": "Feux", "Pre-heat": "Préchauffer",
            "Car is at": "La voiture est à", "Refresh": "Actualiser", "Getting car status…": "Récupération du statut…",
            "Open LionFlow on your phone. The watch gets its data from there.": "Ouvrez LionFlow sur votre téléphone. La montre reçoit ses données depuis celui-ci.",
            "Unlock the car?": "Déverrouiller la voiture ?", "Lock the car?": "Verrouiller la voiture ?",
            "Flash the lights?": "Faire clignoter les feux ?", "Start pre-heating?": "Lancer le préchauffage ?", "Yes": "Oui", "No": "Non",
            "Sending to the car…": "Envoi à la voiture…", "Updated %s": "Mis à jour %s", "Switch car": "Changer de voiture",
        ],
        "de": [
            "Battery": "Batterie", "Fuel": "Kraftstoff", "Range": "Reichweite", "Today": "Heute", "Yesterday": "Gestern",
            "Unlock": "Entriegeln", "Lock": "Verriegeln", "Flash lights": "Lichthupe", "Pre-heat": "Vorklimatisieren",
            "Car is at": "Das Auto steht", "Refresh": "Aktualisieren", "Getting car status…": "Status wird abgerufen…",
            "Open LionFlow on your phone. The watch gets its data from there.": "Öffnen Sie LionFlow auf dem Telefon. Die Uhr bekommt ihre Daten von dort.",
            "Unlock the car?": "Auto entriegeln?", "Lock the car?": "Auto verriegeln?", "Flash the lights?": "Lichthupe betätigen?",
            "Start pre-heating?": "Vorklimatisierung starten?", "Yes": "Ja", "No": "Nein",
            "Sending to the car…": "Wird an das Auto gesendet…", "Updated %s": "Aktualisiert %s", "Switch car": "Auto wechseln",
        ],
        "es": [
            "Battery": "Batería", "Fuel": "Combustible", "Range": "Autonomía", "Today": "Hoy", "Yesterday": "Ayer",
            "Unlock": "Desbloquear", "Lock": "Bloquear", "Flash lights": "Luces", "Pre-heat": "Preacondicionar",
            "Car is at": "El coche está en", "Refresh": "Actualizar", "Getting car status…": "Obteniendo el estado…",
            "Open LionFlow on your phone. The watch gets its data from there.": "Abre LionFlow en tu teléfono. El reloj obtiene los datos de allí.",
            "Unlock the car?": "¿Desbloquear el coche?", "Lock the car?": "¿Bloquear el coche?", "Flash the lights?": "¿Encender las luces?",
            "Start pre-heating?": "¿Iniciar el preacondicionamiento?", "Yes": "Sí", "No": "No",
            "Sending to the car…": "Enviando al coche…", "Updated %s": "Actualizado %s", "Switch car": "Cambiar de coche",
        ],
    ]

    /** "nb" for a watch set to Norwegian ("nb-NO", "no", "nn"), "fr" for "fr-CA", and so on. */
    static func languageCode(_ preferred: [String]) -> String {
        let first = (preferred.first ?? "en").lowercased()
        let code = first.split(whereSeparator: { $0 == "-" || $0 == "_" }).first.map { String($0) } ?? "en"
        return code == "no" || code == "nn" ? "nb" : code
    }

    static var language = languageCode(Locale.preferredLanguages)

    static func t(_ english: String) -> String { table[language]?[english] ?? english }

    /** "Updated 3 min ago". */
    static func updated(_ when: String) -> String { t("Updated %s").replacingOccurrences(of: "%s", with: when) }
}
