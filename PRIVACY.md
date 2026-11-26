# LionFlow privacy policy

_Last updated: 26 November 2026_

LionFlow is an unofficial, open-source app for controlling your own Peugeot. It has no servers of its own, no accounts of its own, no ads and no analytics. The developer receives none of your data.

## What the app handles, and where it goes

| Data | Why | Sent to |
|---|---|---|
| Your Peugeot sign-in (OAuth tokens), remote-control code state | To read your car's status and send commands | Stellantis/Peugeot (`api.groupe-psa.com`, `idpcvs.peugeot.com`, `mwa.mpsa.com`, `otp.mpsa.com`). Stored encrypted on your phone with Android Keystore. |
| Car status, trips, maintenance | Shown in the app, widget and Android Auto | Fetched from Stellantis; kept on your phone only |
| Your location (only while the app or Android Auto map is open) | Map position, weather, nearby chargers | Approximate coordinates go to MET Norway (weather), OpenStreetMap Overpass or NOBIL (chargers) OpenFreeMap (map tiles for the area you view) and OpenStreetMap (map tiles behind a trip in Android Auto) |
| Car photo, service history, imported trips, charging log, home and charger places | Your own notes | Stay on your phone |
| Address of a charging spot / your home or charger address | Show where you charged; find coordinates for an address you type | OpenStreetMap Nominatim (`nominatim.openstreetmap.org`) receives the coordinates or the address for the lookup |
| Trip planner (only when you plan a trip) | Address suggestions, the driving route and the fast chargers along it | The text you type and your approximate position go to Photon (`photon.komoot.io`) for address search; the start and destination go to the OSRM route service (`routing.openstreetmap.de`, or `router.project-osrm.org` as a fallback) and to FOSSGIS' Valhalla route service (`valhalla1.openstreetmap.de`) for routes with car ferries and other alternatives; the route line goes to OpenStreetMap Overpass to find chargers (or, for routes in the Nordic countries when a NOBIL key is available, boxes around the route go to NOBIL, which also tells how many charge points are free right now). Points along the route and the departure time go to Open-Meteo (`api.open-meteo.com`) for heights and the weather forecast. When the route has a car ferry, the two quays and the time you get there go to Entur (`api.entur.io`, Norway) or Transitous (`api.transitous.org`, elsewhere) for the next departure. Saved and recent addresses stay on your phone. |
| Walking directions to the car (only when you tap "Walk to the car") | Showing the way to where the car is parked | Google Maps opens with the car's position as the destination. Nothing is sent before you tap. |
| Voice search in Android Auto (only when you tap the microphone) | Turning what you say into a destination | The car microphone is used only while you speak; the sound goes to the speech recognition service on your phone (usually Google), in the language of the country you are in. LionFlow doesn't keep the recording. |
| Recorded routes (only if you turn on Route tracking) | Show your drives on a map | Stay on your phone. Recorded with the phone's GPS while it's connected to your car over Bluetooth. |
| Update checks (GitHub version only) | Finding new versions | GitHub (`api.github.com`) |
| Active-device count (GitHub version only, at most once a day) | Counting how many devices that installed LionFlow from GitHub are in use: the app downloads a tiny empty file (`ping.txt`) from the GitHub release, and GitHub's own download counter for that file is shown as a badge in the README. LionFlow sends no ID, no account or car details and nothing about your phone, and the developer receives no data, only the counter. Copies installed from Google Play don't do this. | GitHub (`github.com`) |

No data is sold or shared for advertising. Nothing is backed up to the cloud by the app (Android backup is turned off).

## The iPhone, iPad and Apple Watch app

The app for iPhone and iPad (in `iOS/`) handles the same data in the same way, with these differences:

- **Where the sign-in is kept:** in the iPhone's Keychain, marked "this device only", so it is not part of a backup and is not moved to a new phone.
- **The map is Apple Maps:** Apple receives the area of the map you look at, as with any app that shows Apple Maps. Charging stations still come from OpenStreetMap Overpass, the weather from MET Norway and addresses from OpenStreetMap Nominatim. OpenFreeMap and NOBIL are not used.
- **Directions:** "Navigate" and "Open in Maps" open Apple Maps with the charger as the destination, and "Walk to the car" opens Google Maps with the car's position. Nothing is sent before you tap.
- **Update checks:** when the app is opened, at most once an hour, and when Settings is opened, it downloads LionFlow's version list (`source.json`) from the GitHub release, to see whether a newer build is out. Nothing is sent with it: no ID, no account or car details. Copies built by hand in Xcode don't check.
- **The app's signature:** the app reads the date its own signature runs out from its own files, to warn you two days before. That date stays on the phone.
- **Not there, so nothing is sent for it:** the trip planner, route recording, voice search and the active-device count.
- **Apple Watch:** the watch asks the iPhone app for the car's status over Apple's own link between the two. The sign-in stays on the iPhone.
- **Your phone's own backup:** settings, the charging log, places, trip tags and service history are stored like any app's data, so they are in your iPhone's backup (iCloud or a computer) if you have turned that on. The developer has no access to it.
- **Deleting everything:** Settings → **Sign out** in LionFlow, or delete the app.

## Deleting your data

LionFlow has no servers, so there is nothing stored with the developer to delete. Everything LionFlow keeps is on your phone, and you delete it yourself:

1. **Delete everything:** open LionFlow → Settings → **Sign out**, or uninstall LionFlow (Android settings → Apps → LionFlow → Uninstall). This removes all data LionFlow stored: sign-in tokens, remote-control setup, car status, trips, charging log, places and prices, recorded routes, service history, car photo and settings.
2. **Delete parts of it:**
   - Recorded routes: Settings → Route tracking → **Delete all recorded routes**, or open a trip and tap **Delete route**.
   - A charging session: open it in Charging and tap **Delete session**.

Nothing is kept after this, and there is no retention period. Your MyPeugeot account and the data Peugeot/Stellantis holds about your car are managed by Peugeot; to delete those, contact Peugeot or use the MyPeugeot app.

## Contact

Open an issue at https://github.com/thocubeat/lionflow/issues.
