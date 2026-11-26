import Foundation

/** The app's main screens. (Called `Tab` in the Android code; SwiftUI has its own `Tab`.) */
enum AppTab: String, CaseIterable {
    case home = "Home", charging = "Charging", map = "Map", climate = "Climate", service = "Service", trips = "Trips", settings = "Settings"
}

/** What the current car can do, so screens can hide what doesn't apply. Unknown (no status yet) = show. */
enum Caps {
    static func battery(_ st: VehicleStatus?) -> Bool { st == nil || st!.energyType != "Fuel" }
    static func fuel(_ st: VehicleStatus?) -> Bool { st != nil && st!.energyType != "Electric" }
    static func position(_ st: VehicleStatus?) -> Bool { st?.lat != nil && st?.lon != nil }
    static func climate(_ st: VehicleStatus?) -> Bool {
        guard let st = st else { return true }
        return st.raw.has("preconditioning") || st.raw.has("airConditioning") || st.energyType != "Fuel"
    }
    static func lockState(_ st: VehicleStatus?) -> Bool { st == nil || st!.locked != nil }

    static func tab(_ tab: AppTab, _ st: VehicleStatus?) -> Bool {
        switch tab {
        case .charging: return battery(st)
        case .map: return battery(st) || position(st)
        case .climate: return climate(st)
        default: return true
        }
    }
}
