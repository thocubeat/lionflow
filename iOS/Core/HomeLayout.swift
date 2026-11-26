import Foundation

/// What the home screens show, and in which order: the phone's cards and the watch's rows. Kept as plain lists of
/// ids in the settings, so a part added later simply shows up at the end.
enum HomeLayout {
    /** `needs`: "battery" for parts only cars with a traction battery have, "fuel" for cars with a tank, "" for all. */
    struct Item: Equatable {
        let id: String
        let title: String
        var note: String = ""
        var needs: String = ""
    }

    static let phone = [
        Item(id: "warnings", title: "Warnings from the car", note: "Only there when the car reports one"),
        Item(id: "battery", title: "Battery or fuel level"),
        Item(id: "lock", title: "Locked or unlocked"),
        Item(id: "actions", title: "Lock, lights, horn and Find car"),
        Item(id: "parked", title: "Where the car is parked", note: "Address, which way it faces, walking directions"),
        Item(id: "tripsClimate", title: "Trips and climate"),
        Item(id: "service", title: "Next service"),
        Item(id: "fuel", title: "Fuel this month", note: "Distance, litres and cost", needs: "fuel"),
        Item(id: "charging", title: "Charging", note: "State, speed and limit", needs: "battery"),
        Item(id: "monthCost", title: "Charging this month", note: "Energy and cost, home and away", needs: "battery"),
        Item(id: "cardata", title: "Car data", note: "Odometer, outside temperature, 12 V battery and all the rest"),
    ]
    static let phoneHidden = "charging,monthCost"

    static let watch = [
        Item(id: "distance", title: "Distance today and yesterday"),
        Item(id: "lock", title: "Lock and unlock"),
        Item(id: "flash", title: "Flash the lights"),
        Item(id: "preheat", title: "Pre-heating", needs: "battery"),
        Item(id: "place", title: "Where the car is"),
        Item(id: "doors", title: "Doors and windows"),
        Item(id: "odometer", title: "Odometer"),
        Item(id: "outside", title: "Outside temperature"),
        Item(id: "service", title: "Next service"),
    ]
    static let watchHidden = "doors,odometer,outside,service"

    /** Android Auto's tiles and lines. Kept so a backup from the Android app keeps its choice; iPhone has no in-car screen. */
    static let carHidden = "odometer,service"

    static func ids(_ csv: String) -> [String] {
        csv.components(separatedBy: ",").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
    }
    static func hidden(_ csv: String) -> Set<String> { Set(ids(csv)) }

    /** `all` in the user's `order`; parts the order doesn't mention keep their usual place at the end. */
    static func ordered(_ all: [Item], _ order: String) -> [Item] {
        let o = ids(order)
        func rank(_ item: Item) -> Int {
            if let i = o.firstIndex(of: item.id) { return i }
            return 1000 + (all.firstIndex(of: item) ?? 0)
        }
        // A stable sort, as on Android.
        return all.enumerated().sorted { a, b in
            let ra = rank(a.element), rb = rank(b.element)
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map { $0.element }
    }

    /** The phone's cards to draw, top to bottom. */
    static func phoneCards(_ s: AppSettings) -> [String] {
        let h = hidden(s.homeHidden)
        return ordered(phone, s.homeOrder).map { $0.id }.filter { !h.contains($0) }
    }

    /** Moves `id` one step up or down among the parts that apply to this car, and returns the new order. */
    static func move(_ all: [Item], _ order: String, _ id: String, up: Bool, applies: (Item) -> Bool) -> String {
        var list = ordered(all, order)
        guard let i = list.firstIndex(where: { $0.id == id }) else { return order }
        var j = i + (up ? -1 : 1)
        while j >= 0 && j < list.count && !applies(list[j]) { j += up ? -1 : 1 }
        if j < 0 || j >= list.count { return order }
        let item = list.remove(at: i)
        list.insert(item, at: j)
        return list.map { $0.id }.joined(separator: ",")
    }

    static func toggle(_ hiddenCsv: String, _ id: String, show: Bool) -> String {
        var h = ids(hiddenCsv)
        if show { if let i = h.firstIndex(of: id) { h.remove(at: i) } } else if !h.contains(id) { h.append(id) }
        return h.joined(separator: ",")
    }
}
