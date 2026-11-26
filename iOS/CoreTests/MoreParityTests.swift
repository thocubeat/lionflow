import XCTest
import CryptoKit
@testable import LionFlowCore

/// More comparisons with the Android app's own answers: the sample car of demo mode, the car record, the sign-in
/// settings, charging places, trip tags and small pieces. (Fixtures written by iOS/oracle/Oracle2.kt.)
final class MoreParityTests: ParityTestCase {
    private let fixedNow: Int64 = 1_795_000_000_000

    override func tearDown() {
        Demo.clock = { nowMillis() }
        TimeFmt.setPreference("24")
        super.tearDown()
    }

    // MARK: Kotlin's random numbers

    func testRandomNumbersAreKotlins() throws {
        let w = try XCTUnwrap(expected("random_expected.json") as? [String: [String: [Any]]])
        XCTAssertEqual(w.count, 5)
        for (seedText, want) in w {
            var r = KotlinRandom(try XCTUnwrap(Int(seedText)))
            func ints(_ key: String) -> [String] { (want[key] ?? []).map { ($0 as? Int).map { String($0) } ?? "?" } }
            sameList("Random(\(seedText)).nextInt()", (0..<10).map { _ in String(r.nextInt()) }, want["ints"])
            same("Random(\(seedText)).nextInt(1, 4)", (0..<10).map { _ in String(r.nextInt(1, 4)) }.joined(separator: ","), ints("oneToFour").joined(separator: ","))
            same("Random(\(seedText)).nextInt(0, 50)", (0..<10).map { _ in String(r.nextInt(0, 50)) }.joined(separator: ","), ints("fifty").joined(separator: ","))
            same("Random(\(seedText)).nextInt(10)", (0..<10).map { _ in String(r.nextInt(10)) }.joined(separator: ","), ints("ten").joined(separator: ","))
            same("Random(\(seedText)).nextInt(0, 8)", (0..<10).map { _ in String(r.nextInt(0, 8)) }.joined(separator: ","), ints("eight").joined(separator: ","))
            same("Random(\(seedText)).nextInt(0, 1)", (0..<5).map { _ in String(r.nextInt(0, 1)) }.joined(separator: ","), ints("one").joined(separator: ","))
            let doubles = want["doubles"] ?? []
            for i in 0..<10 where i < doubles.count { sameNumber("Random(\(seedText)).nextDouble() #\(i)", r.nextDouble(), doubles[i]) }
            let ranged = want["ranged"] ?? []
            for i in 0..<10 where i < ranged.count { sameNumber("Random(\(seedText)).nextDouble(3, 28) #\(i)", r.nextDouble(3.0, 28.0), ranged[i]) }
        }
    }

    // MARK: The sample car

    private func vehicleText(_ v: Vehicle) -> String {
        [v.id, v.vin, v.motorization, v.brand, kt(v.pictureUrl), kt(v.maintenanceHref), kt(v.modelLabel), kt(v.legacyLabel)].joined(separator: "|")
    }

    private func sameSession(_ at: String, _ s: ChargeSession, _ w: [String: Any]) {
        sameLong(at + ".id", s.id, w["id"])
        sameLong(at + ".start", s.start, w["start"])
        sameLong(at + ".end", s.end, w["end"])
        sameNumber(at + ".startPct", s.startPct, w["startPct"])
        sameNumber(at + ".endPct", s.endPct, w["endPct"])
        sameNumber(at + ".kwh", s.kwh, w["kwh"])
        sameNumber(at + ".peakKw", s.peakKw, w["peakKw"])
        same(at + ".mode", s.mode, w["mode"] as? String)
        sameNumber(at + ".lat", s.lat, w["lat"])
        sameNumber(at + ".lon", s.lon, w["lon"])
        same(at + ".address", s.address, w["address"] as? String)
        same(at + ".source", s.source, w["source"] as? String)
        same(at + ".measured", s.measured, w["measured"])
        sameNumber(at + ".hours", s.hours, w["hours"])
        sameNumber(at + ".avgKw", s.avgKw, w["avgKw"])
    }

    private func placeText(_ p: ChargePlace) -> String { "\(p.id)|\(p.name)|\(p.address)|\(kt(p.lat))|\(kt(p.lon))|\(kt(p.price))" }

    func testSampleCarIsTheSameAsOnAndroid() throws {
        let w = try XCTUnwrap(expected("demo_expected.json") as? [String: Any])
        XCTAssertEqual(w["now"] as? String, String(fixedNow))
        Demo.clock = { 1_795_000_000_000 }
        let store = MemoryStore()
        let runs: [(String, String, Brand)] = [("electric", "electric", .peugeot), ("hybrid", "hybrid", .peugeot), ("hybrid-opel", "hybrid", .opel), ("fuel", "fuel", .peugeot)]
        for (key, kind, brand) in runs {
            let k = try XCTUnwrap(w[key] as? [String: Any], key)
            Brands.current = brand
            Demo.choose(store, kind)
            Demo.reset()
            same("\(key).kind", Demo.kind, k["kind"] as? String)
            same("\(key).vehicle", vehicleText(Demo.vehicle), k["vehicle"] as? String)
            same("\(key).carKind", Demo.vehicle.kind(nil), k["carKind"] as? String)
            sameStatus("\(key).status", Demo.status(), try XCTUnwrap(k["status"] as? [String: Any]))

            let trips = Demo.trips()
            let wantTrips = try XCTUnwrap(k["trips"] as? [[String: Any]])
            XCTAssertEqual(trips.count, wantTrips.count, "\(key) trips")
            XCTAssertGreaterThan(trips.count, 40)
            for (i, t) in trips.enumerated() where i < wantTrips.count { sameTrip("\(key).trip[\(i)]", t, wantTrips[i]) }

            let sessions = Demo.sessions()
            let wantSessions = try XCTUnwrap(k["sessions"] as? [[String: Any]])
            XCTAssertEqual(sessions.count, wantSessions.count, "\(key) sessions")
            for (i, s) in sessions.enumerated() where i < wantSessions.count { sameSession("\(key).session[\(i)]", s, wantSessions[i]) }

            let p = Demo.places()
            let wp = try XCTUnwrap(k["places"] as? [String: Any])
            same("\(key).places.home", p.home.map { placeText($0) }, wp["home"] as? String)
            sameList("\(key).places.known", p.known.map { placeText($0) }, wp["known"])
            sameNumber("\(key).places.ac", p.publicAcPrice, wp["ac"])
            sameNumber("\(key).places.dc", p.publicDcPrice, wp["dc"])
            same("\(key).places.currency", p.currency, wp["currency"] as? String)

            // Commands only change the sample car.
            for step in try XCTUnwrap(k["steps"] as? [[String: Any]]) {
                let label = try XCTUnwrap(step["label"] as? String)
                Demo.apply(label)
                sameStatus("\(key) after \(label)", Demo.status(), try XCTUnwrap(step["status"] as? [String: Any]))
            }
            Demo.reset()
        }
        Brands.current = .peugeot
        Demo.choose(store, "nonsense")
        same("unknown kind", Demo.kind, w["unknownKind"] as? String)
        XCTAssertEqual(store.string("kind"), "electric")
        Demo.choose(store, "fuel")
        Demo.choose(store, "electric")
        XCTAssertFalse(Demo.isOn(store))
        Demo.setOn(store, true)
        XCTAssertTrue(Demo.isOn(store))
    }

    // MARK: Cars as the server lists them

    private func detectionName(_ d: MildHybridDetection) -> String {
        switch d {
        case .vehicleMetadata: return "VehicleMetadata"
        case .modelName: return "ModelName"
        case .otherPowertrain: return "OtherPowertrain"
        case .insufficientMetadata: return "InsufficientMetadata"
        }
    }

    private func sameCar(_ at: String, _ v: Vehicle, _ w: [String: Any], _ statuses: JSONObject) throws {
        same(at + ".fields", vehicleText(v), w["fields"] as? String)
        same(at + ".detection", detectionName(v.mildHybridDetection), w["detection"] as? String)
        same(at + ".reportsMildHybrid", v.reportsMildHybrid, w["reportsMildHybrid"])
        same(at + ".needsBranding", v.needsBranding, w["needsBranding"])
        let overrides: [Bool?] = [nil, true, false]
        sameList(at + ".kind", overrides.map { v.kind($0) }, w["kind"])
        sameJSONText(at + ".json", v.toJson().toString(), w["json"] as? String)
        same(at + ".saved and read again", vehicleText(try Vehicle.fromJson(v.toJson())), w["again"] as? String)
        let ws = try XCTUnwrap(w["status"] as? [String: Any])
        XCTAssertEqual(ws.count, statuses.length())
        for name in statuses.keys() {
            let raw = try statuses.getJSONObject(name)
            let got = overrides.map { o -> String in
                let st = v.parseStatus(raw, nil, o)
                return "\(st.energyType)|\(kt(st.batteryPct))|\(kt(st.rangeKm))|\(st.isMildHybrid)|\(kt(st.fuelPct))|\(st.plugged)|\(kt(st.chargingStatus))"
            }
            sameList(at + ".status[\(name)]", got, ws[name])
        }
    }

    func testCarsAreReadLikeAndroid() throws {
        let input = try JSONObject(text("vehicles_inputs.json"))
        let statuses = try input.getJSONObject("statuses")
        let cases = try input.getJSONArray("cases").objects()
        let want = try XCTUnwrap((expected("vehicles_expected.json") as? [String: Any])?["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, want.count)
        XCTAssertGreaterThan(cases.count, 20)
        for (i, c) in cases.enumerated() where i < want.count {
            let w = want[i]
            let name = c.optString("name")
            same("car[\(i)].name", name, w["name"] as? String)
            let v = try Vehicle.fromApi(try c.getJSONObject("api"), c.optString("defaultBrand"))
            try sameCar(name, v, try XCTUnwrap(w["car"] as? [String: Any]), statuses)
            if let detail = c.optJSONObject("detail") {
                try sameCar(name + " with detail", v.withBrandingDetail(detail), try XCTUnwrap(w["detailed"] as? [String: Any]), statuses)
            } else {
                XCTAssertNil(w["detailed"], name)
            }
            same(name + " without detail", vehicleText(v.withBrandingDetail(nil)), w["noDetail"] as? String)
        }
    }

    // MARK: Sign-in settings

    private func sha(_ s: String) -> String { SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined() }

    func testSignInSettingsAreReadLikeAndroid() throws {
        try StellantisConfig.setText(try String(contentsOf: Self.assets.appendingPathComponent("stellantis_configs.json"), encoding: .utf8))
        let w = try XCTUnwrap(expected("configs_expected.json") as? [String: [String: Any]])
        XCTAssertEqual(w.count, Brand.allCases.count)
        for b in Brand.allCases {
            let wb = try XCTUnwrap(w[b.id])
            sameList("\(b.id).countries", StellantisConfig.countries(b), wb["countries"])
            for cc in ["NO", "GB", "DE", "FR", "IS", "XX", "no", ""] {
                let c = try StellantisConfig.load(b, cc)
                let got = [c.oauthUrl, c.realm, c.scheme, c.locale, c.clientId, c.culture, c.redirectUri, sha(c.clientSecret), sha(c.basicToken)].joined(separator: "|")
                same("\(b.id).load(\(cc))", got, wb["load:\(cc)"] as? String)
            }
        }
    }

    // MARK: Assorted small pieces

    private func doubles(_ a: JSONArray?) -> [[Double]] {
        var out: [[Double]] = []
        for i in 0..<(a?.length() ?? 0) {
            guard let row = a?.optJSONArray(i) else { continue }
            out.append((0..<row.length()).map { row.optDouble($0) })
        }
        return out
    }

    private func places(_ j: JSONObject) -> PlacesConfig {
        PlacesConfig(home: j.optJSONObject("home").map { ChargePlace.fromJson($0) },
                     known: (j.optJSONArray("known")?.objects() ?? []).map { ChargePlace.fromJson($0) },
                     publicAcPrice: j.optDouble("publicAc", 0.0), publicDcPrice: j.optDouble("publicDc", 0.0), currency: j.optString("currency", "kr"))
    }

    func testChargingPlacesAndHealthMatchAndroid() throws {
        let input = try JSONObject(text("misc_inputs.json"))
        let w = try XCTUnwrap(expected("misc_expected.json") as? [String: Any])

        let health = try input.getJSONArray("health").objects()
        let wantHealth = try XCTUnwrap(w["health"] as? [[String: Any]])
        XCTAssertEqual(health.count, wantHealth.count)
        for (i, h) in health.enumerated() where i < wantHealth.count {
            let sessions = (h.optJSONArray("sessions")?.objects() ?? []).compactMap { ChargeSession.fromJson($0) }
            let wantSessions = try XCTUnwrap(wantHealth[i]["sessions"] as? [[String: Any]])
            XCTAssertEqual(sessions.count, wantSessions.count)
            for (n, s) in sessions.enumerated() where n < wantSessions.count { sameSession("health[\(i)].session[\(n)]", s, wantSessions[n]) }
            let r = ChargeSession.healthEstimate(sessions, h.num("newKwh"))
            sameNumber("health[\(i)]", r?.0, wantHealth[i]["health"])
            same("health[\(i)].used", r.map { String($0.1) }, (wantHealth[i]["used"] as? Int).map { String($0) })
        }

        let matches = try input.getJSONArray("match").objects()
        let wantMatches = try XCTUnwrap(w["match"] as? [[Any]])
        XCTAssertEqual(matches.count, wantMatches.count)
        for (i, m) in matches.enumerated() where i < wantMatches.count {
            let cfg = places(try m.getJSONObject("places"))
            let got = (m.optJSONArray("sessions")?.objects() ?? []).compactMap { ChargeSession.fromJson($0) }.map { s -> String in
                let r = ChargeLog.match(s, cfg)
                return "\(r.kind)|\(r.label)|\(kt(r.price))"
            }
            sameList("match[\(i)]", got, wantMatches[i])
        }
        let first = (matches.first?.optJSONArray("sessions")?.objects() ?? [])
        let wantDistance = try XCTUnwrap(w["distance"] as? [Any])
        XCTAssertEqual(first.count, wantDistance.count)
        for (i, s) in first.enumerated() where i < wantDistance.count {
            var d: Double? = nil
            if let lat = s.num("lat"), let lon = s.num("lon") { d = ChargeLog.distanceM(63.4305, 10.3951, lat, lon) }
            closeNumber("distance[\(i)]", d, wantDistance[i])
        }
    }

    func testTripTagsMatchAndroid() throws {
        let input = try JSONObject(text("misc_inputs.json")).getJSONObject("tags")
        let w = try XCTUnwrap((expected("misc_expected.json") as? [String: Any])?["tags"] as? [String: Any])
        let saved = input.optString("text")
        let trips = try input.getJSONArray("trips").objects().map { Trip.parse($0) }
        let endsJson = try input.getJSONObject("ends")
        func ends(_ t: Trip) -> TripEnds? {
            guard let a = endsJson.optJSONArray(t.id), a.length() == 4 else { return nil }
            return TripEnds(aLat: a.optDouble(0), aLon: a.optDouble(1), bLat: a.optDouble(2), bLon: a.optDouble(3))
        }
        func fresh(_ onSave: @escaping (String) -> Void = { _ in }) -> TripTags {
            let t = TripTags(load: { saved }, save: onSave)
            t.clock = { 1_795_000_000_000 }
            return t
        }

        sameJSONValue("tags read and written again", TripTags.toJson(TripTags.parse(saved)), w["parsed"])
        same("no tags yet", TripTags.toJson(TripTags.parse("")), w["empty"] as? String)
        same("broken text", TripTags.toJson(TripTags.parse("{not json")), w["broken"] as? String)

        var written = ""
        let byLook = fresh { written = $0 }
        same("autoTag by distance and speed", String(byLook.autoTag(trips)), (w["autoByLook"] as? Int).map { String($0) })
        sameJSONValue("after autoTag by distance and speed", TripTags.toJson(byLook.data), w["afterByLook"])
        sameJSONValue("what was saved", written, w["savedByLook"])
        sameList("tagOf", trips.map { kt(byLook.tagOf($0.id)?.name) }, w["tagOf"])
        same("isAuto", trips.map { String(byLook.isAuto($0.id)) }.joined(separator: ","), ((w["isAuto"] as? [Bool]) ?? []).map { String($0) }.joined(separator: ","))
        same("autoTag a second time", String(byLook.autoTag(trips)), (w["againByLook"] as? Int).map { String($0) })

        let byPlace = fresh()
        same("autoTag by place", String(byPlace.autoTag(trips, ends)), (w["autoByPlace"] as? Int).map { String($0) })
        sameJSONValue("after autoTag by place", TripTags.toJson(byPlace.data), w["afterByPlace"])

        // The user's own choices.
        let own = fresh()
        own.set(trips[0], "th")
        own.set(trips[3], nil)
        own.set(trips[1], "tw", ends(trips[7]))
        sameJSONValue("after the user's own tags", TripTags.toJson(own.data), w["afterSet"])
        own.autoTag(trips)
        own.set(trips[2], nil)
        own.clearAuto()
        sameJSONValue("after clearing the automatic ones", TripTags.toJson(own.data), w["afterClearAuto"])
        let made = own.create("  A very long name for a tag, longer than it may be  ", 12)
        let again = own.create("a VERY long name for a tag", 3)
        same("created", "\(made.id)|\(made.name)|\(made.color)|\(again.id == made.id)", w["created"] as? String)
        sameList("byName", ["work", " CABIN ", "nope"].map { kt(own.byName($0)?.id) }, w["byName"])
        own.edit("th", "  Cabin by the lake  ", -4, nil, nil)
        own.delete("tw")
        sameJSONValue("after edit and delete", TripTags.toJson(own.data), w["afterEditDelete"])

        let wantSpeeds = try XCTUnwrap(w["speedOf"] as? [Any])
        for (i, t) in trips.enumerated() where i < wantSpeeds.count { sameNumber("speedOf[\(i)]", TripTags.speedOf(t), wantSpeeds[i]) }
        let pairs = doubles(input.optJSONArray("pairs"))
        let wantKm = try XCTUnwrap(w["km"] as? [Any])
        XCTAssertEqual(pairs.count, wantKm.count)
        for (i, p) in pairs.enumerated() where i < wantKm.count { closeNumber("km[\(i)]", TripTags.km(p[0], p[1], p[2], p[3]), wantKm[i]) }
        let wantApart = try XCTUnwrap(w["apart"] as? [Any])
        let office = TripEnds(aLat: pairs[0][0], aLon: pairs[0][1], bLat: pairs[0][2], bLon: pairs[0][3])
        var n = 0
        for tol in [0.3, 0.5, 20000.0] {
            for e in [TripEnds(aLat: 63.4306, aLon: 10.3953, bLat: 63.4399, bLon: 10.4002), TripEnds(aLat: 63.4401, aLon: 10.3999, bLat: 63.4304, bLon: 10.3950), TripEnds(aLat: 60.0, aLon: 11.0, bLat: 60.1, bLon: 11.1)] {
                closeNumber("apart[\(n)]", TripTags.apart(e, office, tol), wantApart[n])
                n += 1
            }
        }
        let wantLike = try XCTUnwrap(w["likeness"] as? [Any])
        for (i, l) in doubles(input.optJSONArray("likeness")).enumerated() where i < wantLike.count {
            sameNumber("likeness[\(i)]", TripTags.likeness(l[0], l[1], TagMark(tag: "x", auto: false, km: l[2], speed: l[3], at: 0)), wantLike[i])
        }
        XCTAssertNil(TripTags.likeness(10.0, 30.0, TagMark(tag: "x", auto: false, km: 10.0, speed: nil, at: 0)))
        XCTAssertTrue(w["likenessNoSpeed"] is NSNull)
        let wantTol = try XCTUnwrap(w["kmTol"] as? [Any])
        let tols = input.optJSONArray("kmTol")
        for i in 0..<wantTol.count { sameNumber("kmTol[\(i)]", TripTags.kmTol(tols?.optDouble(i) ?? .nan), wantTol[i]) }
    }

    func testWeatherClockAndAddressesMatchAndroid() throws {
        let input = try JSONObject(text("misc_inputs.json"))
        let w = try XCTUnwrap(expected("misc_expected.json") as? [String: Any])

        let weather = try input.getJSONObject("weather")
        let wantFeels = try XCTUnwrap(w["feels"] as? [Any])
        for (i, f) in doubles(weather.optJSONArray("feels")).enumerated() where i < wantFeels.count {
            closeNumber("feelsLike[\(i)]", Weather(tempC: f[0], windMs: f[1], symbol: "cloudy", hours: [], tomorrowMorningC: nil).feelsLikeC, wantFeels[i])
        }
        sameList("weather words", (weather.optJSONArray("symbols")?.strings() ?? []).map { WeatherApi.describe($0) + "|" + WeatherApi.kind($0) }, w["symbols"])

        let clock = doubles(input.optJSONArray("clock"))
        let patterns = input.optJSONArray("patterns")?.strings() ?? []
        let wt = try XCTUnwrap(w["timeFmt"] as? [String: Any])
        for use in [true, false] {
            TimeFmt.setPreference(use ? "24" : "12")
            XCTAssertEqual(TimeFmt.use24, use)
            sameList("hm (24-hour clock: \(use))", clock.map { TimeFmt.hm(Int($0[0]), Int($0[1])) }, wt["hm\(use)"])
            sameList("pattern (24-hour clock: \(use))", patterns.map { TimeFmt.pattern($0) }, wt["pattern\(use)"])
        }

        sameList("urlEncode", (input.optJSONArray("urls")?.strings() ?? []).map { urlEncode($0) }, w["urls"])
        let iso = input.optJSONArray("iso")
        sameList("isoUTC", (0..<(iso?.length() ?? 0)).map { isoUTC(iso?.optLong($0) ?? 0) }, w["iso"])
        let fixed = input.optJSONArray("fixed4")
        sameList("four decimals", (0..<(fixed?.length() ?? 0)).map { Fmt.fixed(fixed?.optDouble($0) ?? .nan, 4) }, w["fixed4"])
    }
}
