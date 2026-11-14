# LionFlow

![Downloads](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/thocubeat/lionflow/stats/downloads.json)

**[⬇ Download the latest version (APK)](https://github.com/thocubeat/lionflow/releases/latest/download/LionFlow.apk)** · [All releases](https://github.com/thocubeat/lionflow/releases)

### First sign-in (2 minutes)

1. **Have MyPeugeot working first.** Your car must already be added in the official MyPeugeot app, and you need its 4-digit security code.
2. **Install:** open the APK link above on your phone → tap the file → allow *Install unknown apps* for your browser when asked.
3. **Sign in:** open LionFlow → **Sign in with Peugeot** → log in on Peugeot's page with your MyPeugeot email and password.
4. **Back to the app:** if Android asks which app should open the link, choose **LionFlow** (not MyPeugeot). The app opens and loads your car.
5. **Remote control (optional):** tap **Send SMS code** → enter the SMS code and your MyPeugeot security code → **Activate**. Do it once; you can skip it and do it later under Settings › Security.
6. **Allow location and notifications** when asked — for the map, local weather and car alerts.

> Didn't come back to the app after signing in? Open LionFlow again and repeat step 3. Still stuck? Settings › Security › *Command & error log* shows what went wrong.

A faster, simpler personal alternative to the MyPeugeot Android app.

> **Unofficial, use at your own risk.** LionFlow is not affiliated with, endorsed by or supported by Peugeot or Stellantis. "Peugeot" and "MyPeugeot" are trademarks of their owners and are used only to say which cars the app works with.
>
> LionFlow talks to the same sign-in and connected-car API as the MyPeugeot app, the way the open-source projects under Credits do. That API is not public and may change or stop working at any time. Using it may go against Peugeot's terms of use; your account and car are your own responsibility. Remote commands act on a real car — make sure it's safe to lock, unlock, heat or charge it before you do.

## Install on your phone

1. On your phone, open **Releases** in this repo and download the newest `LionFlow-1.0.x.apk`.
2. Tap the file and allow "Install unknown apps" for your browser when Android asks.
3. Open LionFlow and tap **Sign in with Peugeot**. After you sign in on Peugeot's page, Android may ask which app should open the link: choose **LionFlow**.
4. Optional: turn on remote control with the SMS code and your MyPeugeot security code.

After the first install, LionFlow updates itself: it finds new releases, checks they're signed with the same key, and installs them (on Android 12+ without extra taps after the first update). Every push to `main` builds a new signed APK with GitHub Actions and publishes it as a release.

## How car data is updated

- **When you open the app** (or switch back to it), LionFlow fetches fresh status, unless it already did so less than 5 minutes ago.
- **While the app is open**, it updates by itself every 5 minutes.
- **The refresh button** (on Home and on the widget) updates right away, at any time.
- **In the background**, Android lets the app check about every 30 minutes. This keeps the widget and the notifications up to date.

Automatic updates can be turned off under Settings › Driving data. The refresh button always works.

## Android Auto

LionFlow works in Android Auto in two places:

- **App launcher → LionFlow:** a dark 3D vector map that follows you, with nearby chargers, where the car is parked and a battery/range card. Buttons: **Car** (status, pre-heat, charging, more actions), **Trips** (last 7 days summary and recent trips), **Chargers** (nearest first, tap to show on the map) and the **driver display** toggle.
- **Media view → LionFlow:** *Car* and *Actions* tabs (status, pre-heat, charging, lock, flash lights). It plays no audio.

On cars that let Android Auto use the driver display (cluster), LionFlow's map can be shown there. Starting navigation in Google Maps takes the display back.

Android Auto only runs map apps that Android records as **installed from Google Play**. Turning on "Unknown sources" isn't enough for the map. So LionFlow is also published to a private Google Play internal test (invite only). Installed from Play, it appears in Android Auto, and Play handles updates. In that copy, the GitHub updater switches off by itself.

For the GitHub APK, only the media view works, after turning on Android Auto's **Unknown sources** (Android Auto settings → tap *Version* 10 times → ⋮ → Developer settings).

### Publishing to Google Play yourself (for forks)

1. Create a Google Play developer account and an app with package `no.lionflow.app`. Upload the first `LionFlow-1.0.N.aab` from Releases to **Testing → Internal testing** by hand and add testers.
2. Advanced settings → Form factors → add **Android Auto**.
3. Create a Google Cloud service account, enable the Google Play Android Developer API, invite the service account in Play Console (Users and permissions) with release rights, and add its JSON key as the GitHub secret `PLAY_SERVICE_ACCOUNT_JSON`.
4. From then on, every push to `main` also uploads to internal testing.

Remote actions from the car only run while the phone is connected to Android Auto. You can turn them off under Settings → Android Auto.

## Features

- **Sign-in** on Peugeot's own page, "Remember me", and app lock with your security code or fingerprint
- **Status:** battery, range, charging, doors, odometer, position and next service
- **Remote control:** lock/unlock, lights, horn, pre-heating, and start/stop charging, confirmed with fingerprint or security code
- **Map:** free vector map (MapLibre + OpenFreeMap) in 2D/3D that you can drag, zoom and rotate, showing you, the car and charging stations — free from OpenStreetMap (no key), or from NOBIL if you add a free API key
- **Weather** now and the forecast where you are (MET Norway)
- **Trips** with CSV export and import, plus your own service history
- **Home-screen widget:** battery %, charging, range, pre-heating and outside temperature
- **Notifications:** charging complete or stopped, pre-heating finished, car left unlocked, low battery and service due
- Light, dark or system theme, km or miles, °C or °F
- Adapts to foldables and tablets with a side menu and a two-column home screen

## What the Peugeot API doesn't offer

- **Cabin temperature:** the car only reports the outside temperature. The widget shows the pre-heating state instead.
- **Pre-heating temperature and departure times:** start and stop work in the app, but the settings themselves are changed in the car or in MyPeugeot.
- **Charge limit and scheduled start time:** these are set in the car or in MyPeugeot. "Stop charging" waits until the scheduled time.
- **Remote-control codes:** Peugeot allows roughly 6 one-time codes per day, and too many wrong security codes can block remote control for a while.

## Security

- Tokens, the remote-control state and your security code are encrypted with an Android Keystore key (AES-256-GCM).
- Your password is only typed on Peugeot's own page. The app never sees it.
- There is no backend and no analytics. The app talks directly to Peugeot, OpenFreeMap, OpenStreetMap (Overpass), MET Norway, GitHub (updates) and — only if you choose it — NOBIL.
- The release signing key lives only in GitHub Secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). It is restored during the build and deleted afterwards.

## Download count

The badge at the top is GitHub's own public counter for the APK link, added up across releases. In-app updates download a different file, so they don't inflate it. LionFlow has no analytics or tracking: the app sends nothing anywhere about you.

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party code, licences and data sources are listed in [`NOTICE.md`](NOTICE.md).

## Build it yourself

Fork the repo and add four Actions secrets for signing: `KEYSTORE_B64` (base64 of a PKCS12 keystore), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Every push to `main` then builds a signed APK release. Without secrets, the *Compile check* workflow builds an unsigned debug APK on any other branch.

## Credits

The Stellantis sign-in, API and MQTT flow are ported from
[homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) (MIT), and the
one-time-code generator from [psa_car_controller](https://github.com/flobz/psa_car_controller) (GPL-3.0) by Florian Bezannier.
Thanks to both projects — LionFlow wouldn't exist without them. Details in `NOTICE.md`.
