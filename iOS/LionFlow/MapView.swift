import SwiftUI
import MapKit

// The Map tab: the car, the phone and the chargers nearby, on Apple's map.

/** Where the map should go next. Each one has its own number, so the same place can be asked for twice. */
private struct MapFocus: Equatable {
    let serial: Int
    let lat: Double
    let lon: Double
    let span: Double
}

private final class ChargerPin: NSObject, MKAnnotation {
    let charger: Charger
    init(_ charger: Charger) { self.charger = charger }
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: charger.lat, longitude: charger.lon) }
    var title: String? { charger.name }
}

private final class CarPin: NSObject, MKAnnotation {
    @objc dynamic var coordinate: CLLocationCoordinate2D
    let title: String?
    init(_ coordinate: CLLocationCoordinate2D, _ title: String) {
        self.coordinate = coordinate
        self.title = title
    }
}

/** The height of the card at the bottom of the map. */
private struct CardHeight: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

private struct CarMap: UIViewRepresentable {
    let car: CLLocationCoordinate2D?
    let carName: String
    let chargers: [Charger]
    let showsUser: Bool
    let dark: Bool
    let focus: MapFocus?
    /** How much of the bottom of the map the card on top of it covers. */
    let covered: CGFloat
    let onSelect: (Charger?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.showsCompass = true
        map.showsScale = true
        map.pointOfInterestFilter = .excludingAll
        map.register(MKMarkerAnnotationView.self, forAnnotationViewWithReuseIdentifier: "charger")
        map.register(MKMarkerAnnotationView.self, forAnnotationViewWithReuseIdentifier: "car")
        return map
    }

    func updateUIView(_ map: MKMapView, context: Context) {
        let coordinator = context.coordinator
        coordinator.parent = self
        map.overrideUserInterfaceStyle = dark ? .dark : .light
        map.showsUserLocation = showsUser
        // Apple's logo and legal link, the compass and the scale stay clear of the app's own buttons and card.
        let margins = UIEdgeInsets(top: 68, left: 4, bottom: covered, right: 4)
        if map.layoutMargins != margins { map.layoutMargins = margins }

        // The car.
        let carPin = map.annotations.compactMap { $0 as? CarPin }.first
        if let car = car {
            if let pin = carPin {
                if pin.coordinate.latitude != car.latitude || pin.coordinate.longitude != car.longitude { pin.coordinate = car }
            } else {
                map.addAnnotation(CarPin(car, carName))
            }
        } else if let pin = carPin {
            map.removeAnnotation(pin)
        }

        // The chargers: only what changed is added or taken away, so the map doesn't flicker.
        let wanted = Dictionary(chargers.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let shown = map.annotations.compactMap { $0 as? ChargerPin }
        let gone = shown.filter { wanted[$0.charger.id] == nil }
        if !gone.isEmpty { map.removeAnnotations(gone) }
        let have = Set(shown.map { $0.charger.id })
        let fresh = chargers.filter { !have.contains($0.id) }
        if !fresh.isEmpty { map.addAnnotations(fresh.map { ChargerPin($0) }) }

        if let f = focus, f.serial != coordinator.lastFocus {
            let first = coordinator.lastFocus == nil
            coordinator.lastFocus = f.serial
            let region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: f.lat, longitude: f.lon),
                                            span: MKCoordinateSpan(latitudeDelta: f.span, longitudeDelta: f.span))
            map.setRegion(region, animated: !first)
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var parent: CarMap
        var lastFocus: Int?
        init(_ parent: CarMap) { self.parent = parent }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            if let pin = annotation as? ChargerPin {
                let view = mapView.dequeueReusableAnnotationView(withIdentifier: "charger", for: annotation) as? MKMarkerAnnotationView
                let fast = pin.charger.maxKw >= 50
                view?.markerTintColor = fast ? UIColor(red: 1.0, green: 0.71, blue: 0.28, alpha: 1) : UIColor(red: 0.78, green: 0.95, blue: 0.42, alpha: 1)
                view?.glyphImage = UIImage(systemName: "bolt.fill")
                view?.glyphTintColor = UIColor(red: 0.05, green: 0.055, blue: 0.063, alpha: 1)
                view?.clusteringIdentifier = "charger"
                view?.displayPriority = .defaultHigh
                view?.titleVisibility = .hidden
                return view
            }
            if annotation is CarPin {
                let view = mapView.dequeueReusableAnnotationView(withIdentifier: "car", for: annotation) as? MKMarkerAnnotationView
                view?.markerTintColor = UIColor(red: 0.05, green: 0.055, blue: 0.063, alpha: 1)
                view?.glyphImage = UIImage(systemName: "car.fill")
                view?.glyphTintColor = UIColor(red: 0.78, green: 0.95, blue: 0.42, alpha: 1)
                view?.displayPriority = .required
                view?.clusteringIdentifier = nil
                return view
            }
            return nil
        }

        func mapView(_ mapView: MKMapView, didSelect view: MKAnnotationView) {
            if let pin = view.annotation as? ChargerPin {
                parent.onSelect(pin.charger)
            } else if let cluster = view.annotation as? MKClusterAnnotation {
                // A bunch of chargers: go closer, so they come apart.
                mapView.showAnnotations(cluster.memberAnnotations, animated: true)
            }
        }

        func mapView(_ mapView: MKMapView, didDeselect view: MKAnnotationView) {
            if view.annotation is ChargerPin { parent.onSelect(nil) }
        }
    }
}

struct MapView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var platform: Platform
    @Environment(\.lf) private var c
    @State private var selected: Charger?
    @State private var filter = "all"
    @State private var focus: MapFocus?
    @State private var serial = 0
    @State private var cardHeight: CGFloat = 96

    private func go(_ lat: Double, _ lon: Double, _ span: Double) {
        serial += 1
        focus = MapFocus(serial: serial, lat: lat, lon: lon, span: span)
    }

    private func showMe() {
        model.loadLocationAndWeather()
        if let me = platform.lastKnownLocation() {
            go(me.lat, me.lon, 0.03)
        } else if platform.locationDenied {
            platform.openAppSettings()
        } else if !platform.locationAllowed {
            platform.requestLocation()
        } else if let st = model.ui.status, let lat = st.lat, let lon = st.lon {
            go(lat, lon, 0.03)
        }
    }

    private func start() {
        let ui = model.ui
        if focus == nil {
            if let st = ui.status, let lat = st.lat, let lon = st.lon {
                go(lat, lon, 0.045)
            } else if let lat = ui.myLat, let lon = ui.myLon {
                go(lat, lon, 0.045)
            }
        }
        if !platform.locationAllowed && !platform.locationDenied { platform.requestLocation() }
        if ui.chargers.isEmpty { model.loadChargers() }
    }

    var body: some View {
        let ui = model.ui
        let st = ui.status
        let chargers = filter == "fast" ? ui.chargers.filter { $0.maxKw >= 50 } : ui.chargers
        var car: CLLocationCoordinate2D? = nil
        if let lat = st?.lat, let lon = st?.lon { car = CLLocationCoordinate2D(latitude: lat, longitude: lon) }
        return ZStack {
            CarMap(car: car, carName: model.prefs.carName, chargers: chargers, showsUser: platform.locationAllowed, dark: c.isDark, focus: focus, covered: cardHeight + 20) { selected = $0 }
                .ignoresSafeArea(edges: .top)
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top, spacing: 8) {
                    FilterChip(text: "All", active: filter == "all") { filter = "all" }
                    FilterChip(text: "Fast 50 kW+", active: filter == "fast") { filter = "fast" }
                    Spacer(minLength: 0)
                    Button(action: showMe) {
                        LfIcon("location.fill", Color(hex: 0xFF3D7BE0), 20).frame(width: 48, height: 48)
                            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(c.card))
                            .softShadow(c, 6)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(LfPress())
                    .accessibilityLabel(Text(verbatim: tr("Show my location")))
                }
                if let error = ui.chargerError {
                    T(error).font(LfType.small).foregroundColor(c.text).lineLimit(2)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(c.card))
                        .tap { model.loadChargers() }
                }
                Spacer(minLength: 0)
                bottomCard(st)
                    .background(GeometryReader { g in Color.clear.preference(key: CardHeight.self, value: g.size.height) })
            }
            .padding(12)
            .onPreferenceChange(CardHeight.self) { h in
                if abs(h - cardHeight) > 1 { cardHeight = h }
            }
        }
        .onAppear(perform: start)
        .onChange(of: ui.status?.lat) { _ in if focus == nil { start() } }
    }

    /** The tapped charger, or the car. */
    private func bottomCard(_ st: VehicleStatus?) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if let sel = selected {
                chargerCard(sel)
            } else {
                HStack(spacing: 12) {
                    LfIcon(LfIcons.car, c.accentInk, 20).frame(width: 40, height: 40).background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(c.raised))
                    VStack(alignment: .leading, spacing: 0) {
                        T("Your car").font(LfType.label).foregroundColor(c.text)
                        T(st?.lat != nil ? (st?.moving == true ? "Driving" : "Parked") + " · tap a charger for details" : "Position not available yet")
                            .font(LfType.small).foregroundColor(c.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if let lat = st?.lat, let lon = st?.lon {
                        T("Go").font(LfType.label).foregroundColor(c.accentInk).padding(8).tap { go(lat, lon, 0.012) }
                    }
                }
            }
            T(model.ui.chargers.isEmpty ? "Map: Apple Maps" : "Map: Apple Maps · Chargers: OpenStreetMap").font(.system(size: 10)).foregroundColor(c.muted)
                .tap { platform.openUrl("https://www.openstreetmap.org/copyright") }
        }
        .padding(16)
        .frame(maxWidth: 520, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(c.card))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(c.line, lineWidth: 1))
        .softShadow(c)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder private func chargerCard(_ sel: Charger) -> some View {
        let name = sel.name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
        let place = "\(Fmt.fixed(sel.lat, 6)),\(Fmt.fixed(sel.lon, 6))"
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 3) {
                Plain(sel.name).font(.system(size: 17, weight: .semibold)).foregroundColor(c.text)
                T(details(sel)).font(LfType.small).foregroundColor(c.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button(action: { selected = nil }) { LfIcon(LfIcons.close, c.muted, 20).frame(width: 36, height: 36).contentShape(Rectangle()) }
                .buttonStyle(LfPress())
                .accessibilityLabel(Text(verbatim: tr("Close")))
        }
        HStack(spacing: 10) {
            PrimaryButton("Navigate", icon: "location.fill") { platform.openUrl("https://maps.apple.com/?daddr=\(place)&dirflg=d") }
            SecondaryButton("Open in Maps", fill: false) { platform.openUrl("https://maps.apple.com/?ll=\(place)&q=\(name)") }
        }
    }

    private func details(_ sel: Charger) -> String {
        var bits: [String] = []
        if !sel.address.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { bits.append(sel.address) }
        if sel.maxKw > 0 { bits.append("\(JSONValue.javaInt(sel.maxKw)) kW") }
        bits.append("\(sel.points) points")
        return bits.joined(separator: " · ")
    }
}

private struct FilterChip: View {
    @Environment(\.lf) private var c
    let text: String
    let active: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            T(text).font(.system(size: 13, weight: .semibold)).foregroundColor(active ? c.onAccent : c.text)
                .padding(.horizontal, 14)
                .frame(minHeight: 40)
                .background(Capsule().fill(active ? c.accent : c.card))
                .overlay(Capsule().strokeBorder(active ? c.accent : c.line, lineWidth: 1))
                .softShadow(c, 4)
                .contentShape(Capsule())
        }
        .buttonStyle(LfPress())
        .accessibilityAddTraits(active ? [.isSelected] : [])
    }
}
