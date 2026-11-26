import Foundation

// MARK: Weather: MET Norway Locationforecast (free, no key)

struct WeatherHour: Equatable {
    var timeMillis: Int64
    var tempC: Double
    var symbol: String
}

struct Weather: Equatable {
    var tempC: Double
    var windMs: Double
    var symbol: String
    var hours: [WeatherHour]
    var tomorrowMorningC: Double?

    var feelsLikeC: Double {
        // Wind chill (valid for t <= 10 °C and wind > 1.3 m/s), otherwise the air temperature.
        let v = windMs * 3.6
        if tempC <= 10 && v > 4.8 {
            return 13.12 + 0.6215 * tempC - 11.37 * pow(v, 0.16) + 0.3965 * tempC * pow(v, 0.16)
        }
        return tempC
    }
}

enum WeatherApi {
    static func forecast(_ lat: Double, _ lon: Double) async throws -> Weather {
        let url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=\(Fmt.fixed(lat, 4))&lon=\(Fmt.fixed(lon, 4))"
        let req = try Http.request(url, headers: [("User-Agent", Http.contactAgent)])
        guard case .object(let j)? = try await Http.json(req) else { throw ApiException(0, "No forecast data") }
        return try parse(j, now: nowMillis(), calendar: Calendar.current)
    }

    /** Reads MET's answer. `now` and `calendar` say what "the coming hours" and "tomorrow at 7" mean. */
    static func parse(_ j: JSONObject, now: Int64, calendar: Calendar) throws -> Weather {
        var hours: [(WeatherHour, Double)] = []
        for t in j.obj("properties")?.optJSONArray("timeseries")?.objects() ?? [] {
            // MET writes times as "2026-11-26T09:00:00Z" only.
            let text = t.optString("time")
            guard text.hasSuffix("Z"), !text.contains("."), let time = VehicleStatus.parseIso(text), let details = t.obj("data", "instant", "details") else { continue }
            let sym = t.obj("data", "next_1_hours", "summary")?.str("symbol_code") ?? t.obj("data", "next_6_hours", "summary")?.str("symbol_code") ?? "cloudy"
            hours.append((WeatherHour(timeMillis: time, tempC: details.optDouble("air_temperature"), symbol: sym), details.optDouble("wind_speed", 0.0)))
        }
        guard let firstOfAll = hours.first else { throw ApiException(0, "No forecast data") }
        let upcoming = hours.filter { $0.0.timeMillis >= now - 3_600_000 }
        let first = upcoming.first ?? firstOfAll
        // Tomorrow at 07 (this minute's seconds kept, as on Android).
        let nowDate = Date(timeIntervalSince1970: Double(now) / 1000)
        var target = now
        if let next = calendar.date(byAdding: .day, value: 1, to: nowDate) {
            var c = calendar.dateComponents([.year, .month, .day, .second, .nanosecond], from: next)
            c.hour = 7
            c.minute = 0
            if let d = calendar.date(from: c) { target = Int64((d.timeIntervalSince1970 * 1000).rounded()) }
        }
        var tomorrow: Double? = nil
        if let nearest = hours.min(by: { abs($0.0.timeMillis - target) < abs($1.0.timeMillis - target) }), abs(nearest.0.timeMillis - target) < 3 * 3_600_000 {
            tomorrow = nearest.0.tempC
        }
        return Weather(tempC: first.0.tempC, windMs: first.1, symbol: first.0.symbol, hours: Array(upcoming.map { $0.0 }.prefix(12)), tomorrowMorningC: tomorrow)
    }

    static func describe(_ symbol: String) -> String {
        if symbol.hasPrefix("clearsky") { return "Clear" }
        if symbol.hasPrefix("fair") { return "Fair" }
        if symbol.hasPrefix("partlycloudy") { return "Partly cloudy" }
        if symbol.hasPrefix("cloudy") { return "Cloudy" }
        if symbol.hasPrefix("fog") { return "Fog" }
        if symbol.contains("thunder") { return "Thunder" }
        if symbol.contains("sleet") { return "Sleet" }
        if symbol.contains("snow") { return "Snow" }
        if symbol.contains("rain") { return "Rain" }
        return "Cloudy"
    }

    static func kind(_ symbol: String) -> String {
        if symbol.contains("snow") || symbol.contains("sleet") { return "snow" }
        if symbol.contains("rain") || symbol.contains("thunder") { return "rain" }
        if symbol.hasPrefix("clearsky") || symbol.hasPrefix("fair") { return "sun" }
        return "cloud"
    }
}

// MARK: Chargers and fuel stations: OpenStreetMap via Overpass (free, no key)

struct Charger: Equatable, Identifiable {
    var id: String
    var name: String
    var lat: Double
    var lon: Double
    var maxKw: Double
    var points: Int
    var address: String
    var distanceM: Int?

    func toJson() -> JSONObject {
        let j = JSONObject()
        j.put("id", id)
        j.put("name", name)
        j.put("lat", lat)
        j.put("lon", lon)
        j.put("kw", maxKw)
        j.put("points", points)
        j.put("address", address)
        return j
    }

    static func fromJson(_ j: JSONObject) -> Charger {
        Charger(id: j.optString("id"), name: j.optString("name"), lat: j.optDouble("lat"), lon: j.optDouble("lon"),
                maxKw: j.optDouble("kw", 0.0), points: j.optInt("points"), address: j.optString("address"), distanceM: nil)
    }
}

/** All the free charger servers are busy right now. */
struct BusyException: Error, LocalizedError {
    var errorDescription: String? { "The free charger servers are busy right now" }
}

enum OsmChargers {
    /** Free public Overpass servers, tried in order — the main one is often busy (HTTP 429). */
    private static let endpoints = [
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    ]

    static func near(_ lat: Double, _ lon: Double, radiusM: Int = 12000, limit: Int = 80, amenity: String = "charging_station") async throws -> [Charger] {
        var last: Error? = nil
        for url in endpoints {
            do {
                return try await query(url, lat, lon, radiusM, limit, amenity)
            } catch let e as ApiException {
                last = e
                if e.code != 429 && e.code < 500 { throw e }
            } catch let e as URLError {
                last = e
            }
        }
        if let e = last, !(e is ApiException) { throw e }
        throw BusyException()
    }

    private static let number = try! NSRegularExpression(pattern: "([0-9]+(?:[.,][0-9]+)?)")

    /** The first number in a text ("50 kW", "22,5"), or nil. */
    static func firstNumber(_ v: String) -> Double? {
        let ns = v as NSString
        guard let m = number.firstMatch(in: v, options: [], range: NSRange(location: 0, length: ns.length)) else { return nil }
        return Double(ns.substring(with: m.range(at: 1)).replacingOccurrences(of: ",", with: "."))
    }

    private static func query(_ endpoint: String, _ lat: Double, _ lon: Double, _ radiusM: Int, _ limit: Int, _ amenity: String) async throws -> [Charger] {
        let la = JSONValue.javaDouble(lat)
        let lo = JSONValue.javaDouble(lon)
        let q = "[out:json][timeout:25];(" +
            "node[\"amenity\"=\"\(amenity)\"](around:\(radiusM),\(la),\(lo));" +
            "way[\"amenity\"=\"\(amenity)\"](around:\(radiusM),\(la),\(lo));" +
            ");out center \(limit);"
        let req = try Http.request(endpoint, method: "POST", headers: [("User-Agent", Http.userAgent), ("Content-Type", "application/x-www-form-urlencoded")],
                                   body: Data(("data=" + urlEncode(q)).utf8))
        guard case .object(let j)? = try await Http.json(req) else { return [] }
        return parse(j, amenity)
    }

    /** Reads an Overpass answer into chargers (or fuel stations). */
    static func parse(_ j: JSONObject, _ amenity: String) -> [Charger] {
        var out: [Charger] = []
        for e in j.optJSONArray("elements")?.objects() ?? [] {
            guard let la = e.has("lat") ? e.optDouble("lat") : e.optJSONObject("center")?.optDouble("lat"),
                  let lo = e.has("lon") ? e.optDouble("lon") : e.optJSONObject("center")?.optDouble("lon") else { continue }
            let t = e.optJSONObject("tags") ?? JSONObject()
            var maxKw = 0.0
            for k in t.keys() where k.hasSuffix(":output") || k == "maxpower" || k == "charging_station:output" {
                let v = t.optString(k).lowercased()
                guard let num = firstNumber(v) else { continue }
                var kw = num
                if v.contains("mw") { kw = num * 1000 }
                else if v.contains("kw") { kw = num }
                else if num > 1000 { kw = num / 1000 }   // plain watts
                if kw > maxKw { maxKw = kw }
            }
            let name = ["name", "operator", "brand", "network"].map { t.optString($0) }.first { !$0.allSatisfy({ $0.isWhitespace }) }
                ?? (amenity == "fuel" ? "Fuel station" : "Charging station")
            let address = [t.optString("addr:street"), t.optString("addr:housenumber"), t.optString("addr:city")]
                .filter { !$0.allSatisfy({ $0.isWhitespace }) }.joined(separator: " ")
            out.append(Charger(id: "osm-\(e.optString("type"))-\(e.optLong("id"))", name: name, lat: la, lon: lo, maxKw: maxKw,
                               points: Int(t.optString("capacity")) ?? 0, address: address, distanceM: nil))
        }
        return out
    }
}

// MARK: Addresses: OpenStreetMap Nominatim (free)

enum Geocoder {
    /** "Street 12, City" for a position. */
    static func reverse(_ lat: Double, _ lon: Double) async throws -> String? {
        let url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&addressdetails=1&lat=\(JSONValue.javaDouble(lat))&lon=\(JSONValue.javaDouble(lon))"
        let j = try JSONObject(try await Http.text(try Http.request(url, headers: [("User-Agent", Http.contactAgent)])))
        return address(j)
    }

    static func address(_ j: JSONObject) -> String? {
        func blank(_ s: String) -> Bool { s.allSatisfy({ $0.isWhitespace }) }
        let display = j.optString("display_name")
        guard let a = j.optJSONObject("address") else { return blank(display) ? nil : display }
        let street = [a.optString("road"), a.optString("house_number")].filter { !blank($0) }.joined(separator: " ")
        let city = ["city", "town", "village", "municipality", "suburb"].map { a.optString($0) }.first { !blank($0) } ?? ""
        let out = [street, city].filter { !blank($0) }.joined(separator: ", ")
        return blank(out) ? display : out
    }

    /** Address → coordinates. */
    static func find(_ address: String) async throws -> (Double, Double)? {
        let url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + urlEncode(address)
        let a = try JSONArray(try await Http.text(try Http.request(url, headers: [("User-Agent", Http.contactAgent)])))
        guard let first = a.optJSONObject(0), let lat = JSONValue.javaParseDouble(first.optString("lat")), let lon = JSONValue.javaParseDouble(first.optString("lon")) else { return nil }
        return (lat, lon)
    }
}
