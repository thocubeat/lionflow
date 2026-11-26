import Foundation

/** A warning the car itself has reported (tyre pressure, a lamp, a fault …). */
struct CarAlert: Equatable {
    var type: String
    var atMillis: Int64?

    private static let camel = try! NSRegularExpression(pattern: "(?<=[a-z0-9])(?=[A-Z])")

    /** "TyrePressureFault" or "TYRE_PRESSURE_FAULT" as "Tyre pressure fault". */
    var label: String {
        let under = type.replacingOccurrences(of: "_", with: " ")
        let spaced = CarAlert.camel.stringByReplacingMatches(in: under, range: NSRange(location: 0, length: (under as NSString).length), withTemplate: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return spaced.prefix(1).uppercased() + spaced.dropFirst()
    }

    /** Only warnings that still stand: ones the car has marked as ended are left out. */
    static func parse(_ list: [JSONObject]) -> [CarAlert] {
        var out: [CarAlert] = []
        for j in list {
            if j.has("active") && !j.isNull("active") && !j.optBoolean("active", true) { continue }
            if j.has("endAt") && !j.isNull("endAt") && !j.optString("endAt").allSatisfy({ $0.isWhitespace }) { continue }
            guard let type = ["type", "label", "code"].compactMap({ k -> String? in
                let v = j.optString(k)
                return (!v.allSatisfy({ $0.isWhitespace }) && !j.isNull(k)) ? v : nil
            }).first else { continue }
            if out.contains(where: { $0.type == type }) { continue }
            let started = j.optString("startedAt")
            out.append(CarAlert(type: type, atMillis: VehicleStatus.parseIso(started.allSatisfy({ $0.isWhitespace }) ? j.optString("createdAt") : started)))
        }
        return out
    }
}

/** Everything in the car's status, one line per value, for the "All car data" screen. */
enum CarData {
    /** "energies › Electric › level" to "80", in the order of the names. Positions inside lists are numbered. */
    static func flatten(_ j: JSONObject) -> [(String, String)] {
        var out: [(String, String)] = []
        func walk(_ path: String, _ v: JSONValue?) {
            guard let v = v else { return }
            switch v {
            case .object(let o):
                for k in o.keys().filter({ !$0.hasPrefix("_") }).sorted() { walk(path.isEmpty ? k : path + " › " + k, o.opt(k)) }
            case .array(let a):
                let simple = a.items.allSatisfy { item -> Bool in
                    switch item { case .object, .array: return false; default: return true }
                }
                if a.length() > 0 && simple {
                    out.append((path, a.items.map { $0.javaString }.joined(separator: ", ")))
                } else {
                    for (i, item) in a.items.enumerated() {
                        // A list of typed things (energies, doors) reads better by its type than by its number.
                        var name = "\(i + 1)"
                        if case .object(let o) = item, let named = ["type", "identifier", "slot"].map({ o.optString($0) }).first(where: { !$0.allSatisfy({ $0.isWhitespace }) }) {
                            name = named
                        }
                        walk(path + " › " + name, item)
                    }
                }
            case .null: break
            default: out.append((path, v.javaString))
            }
        }
        walk("", .object(j))
        return out
    }

    /** The raw answer, indented, as the carmaker's server sent it. */
    static func pretty(_ j: JSONObject) -> String { j.toString(2) }

    /** N, NE, E … for a heading in degrees. */
    static func compass(_ deg: Double) -> String {
        let d = (deg.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][Int((d + 22.5) / 45) % 8]
    }

    /** "north-east" for a heading in degrees. */
    static func compassWord(_ deg: Double) -> String {
        let d = (deg.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
        return ["north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"][Int((d + 22.5) / 45) % 8]
    }
}
