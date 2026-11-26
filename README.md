# LionFlow

![Downloads](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/thocubeat/lionflow/stats/downloads.json) ![Active users (last 24h)](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/thocubeat/lionflow/stats/devices.json)

**[⬇ Download the latest version (APK)](https://github.com/thocubeat/lionflow/releases/latest/download/LionFlow.apk)** · [All releases](https://github.com/thocubeat/lionflow/releases) · [iPhone and iPad (preview)](#iphone-ipad-and-apple-watch-preview)

## What is LionFlow?

LionFlow is a free, open-source Android app (with an iPhone and iPad version on the way) for your own connected Peugeot, Citroën, DS, Opel or Vauxhall. It's an unofficial alternative to the MyPeugeot, MyCitroën, MyDS, myOpel and MyVauxhall apps. It signs in with your normal account for your car's brand and talks to the same Stellantis servers, so it shows the same car data and sends the same remote commands. The difference is how it's built and what it does with that data.

**Subscriptions (decided by the carmaker, not by LionFlow)**
Status, battery, range, trips and service data come with your brand account. Charging control and pre-heating on electric and plug-in hybrid cars need the brand's e-Remote Control service (included in the Connect ONE pack on many new cars, otherwise Connect PLUS or a separate subscription). Lock, unlock, lights and horn need the Remote Control service (Connect PLUS). What is included, and for how long, depends on model, country and order date. Activate it in the brand's own app first; LionFlow can't do that for you. The app shows this for your brand before sign-in and next to the functions that need it.

**How it differs from MyPeugeot**

- **Quick and to the point:** opens straight to battery, range and charging, with no menus or offers in the way. It wakes the car in the background when the app opens, so commands get through faster, and shows each step while a command is on its way ("Waking the car…", "Car received it").
- **Real charging power in kW:** the car only reports "km of range per hour". LionFlow works out the actual kW from the energy going into the battery.
- **Charging log:** every session with time, % from–to, kWh, duration, average and peak kW, and where. Splits home from away, and estimates cost from your home price and up to 5 known chargers.
- **Charge limit:** switch the car between its daily limit and a full charge for a trip, or let LionFlow stop charging at any % you choose, even on chargers that ignore the car's limit.
- **Pre-heating schedules:** set "ready at 07:00, Monday to Friday" in a few taps.
- **Range that makes sense:** when Peugeot's server reports 0 km by mistake, LionFlow estimates from your own driving instead.
- **Clear errors:** when something fails you get the actual reason and code, not a generic "try again later".
- **More on screen:** a Wear OS watch app, a home-screen widget, a free 2D/3D charger map, local weather and forecast, and layouts for foldables and tablets.
- **Your own home screen:** choose which cards Home shows and in what order, and what the watch and Android Auto show (Settings → Home screens).
- **Everything the car reports:** all values in plain words, every single value under the carmaker's own name, and the raw data. Warnings from the car, where it is parked and which way it faces (with the walk to it in Google Maps), and notes when it is being driven, when privacy mode is on or when fog lights are left on.
- **Fuel cars and hybrids:** fuel cost per trip and per month, monthly totals, fill-ups read from the fuel gauge, records, and the average since new.
- **Stays signed in:** the carmaker hands out a new sign-in token at every renewal and the old one stops working. LionFlow writes the new one to encrypted storage before it does anything else, and never runs two renewals at once.
- **Your language:** English, French, German, Spanish, Norwegian, Dutch or Finnish (Settings → Appearance → Language), or follow the phone.
- **Several cars:** switch between the cars on your MyPeugeot account, including petrol and diesel ones (fuel level and range).
- **Private and secure:** no ads, no analytics, no tracking. Sign-in tokens are encrypted on the phone, and remote actions can require your fingerprint. All the code is public, so anyone can check what it does.

### First sign-in (2 minutes)

1. **Have the brand's own app working first** (MyPeugeot, MyCitroën, MyDS, myOpel or MyVauxhall). Your car must already be added there, and you need its 4-digit security code.
2. **Install:** open the APK link above on your phone → tap the file → allow *Install unknown apps* for your browser when asked.
3. **Sign in:** open LionFlow → pick your **car brand** and country → **Sign in** → log in on the brand's own page with the same email and password as its app.
4. **Back to the app:** if Android asks which app should open the link, choose **LionFlow** (not MyPeugeot). The app opens and loads your car.
5. **Remote control (optional):** tap **Send SMS code** → enter the SMS code and your MyPeugeot security code → **Activate**. Do it once; you can skip it and do it later under Settings › Security.
6. **Allow location and notifications** when asked — for the map, local weather and car alerts.

> Didn't come back to the app after signing in? Open LionFlow again and repeat step 3. Still stuck? Settings › Security › *Command & error log* shows what went wrong.

## Updates

LionFlow updates itself. When a new version is ready, a popup shows what's new; tap **Install now**. Only versions signed with LionFlow's key are installed. You can turn the check off under Settings › App updates.

## How car data is updated

- **When you open the app** (or switch back to it), LionFlow fetches fresh status, unless it already did so less than 5 minutes ago.
- **While the app is open**, it updates by itself every 5 minutes.
- **The refresh button** (on Home and on the widget) updates right away, at any time.
- **In the background**, Android lets the app check about every 30 minutes. This keeps the widget and the notifications up to date.

Automatic updates can be turned off under Settings › Driving data. The refresh button always works.

## Charging log

The Charging tab logs every charging session: when, from what % to what %, energy (kWh), duration, average and peak kW, AC or DC, and where.

Under **Places & prices**, set your **Home** (sessions within 50 m count as home) and up to **5 known chargers** (work, cabin, a free charger) with a price per kWh. You can also set prices for other AC and DC chargers. The log shows home vs away kWh and estimated costs for the last 30 days. Everything stays on the phone.

## Wear OS watch

LionFlow also has a watch app for Wear OS (Wear OS 3 or newer). It shows battery % and range, kilometres driven today and yesterday, and where the car is parked. You can lock or unlock, flash the lights and start pre-heating, and every action asks you to confirm first.

The watch gets everything through LionFlow on your phone, so your Peugeot sign-in stays on the phone. Keep the phone app updated and signed in.

**Install on the watch:**

1. Download `Watch-LionFlow-1.1.x.apk` from the [latest release](https://github.com/thocubeat/lionflow/releases/latest) to your computer, and install [Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools) (adb).
2. On the watch: **Settings → System → About** → tap **Build number** 7 times. Then in **Developer options**, turn on **ADB debugging** and **Wireless debugging**.
3. Under Wireless debugging, tap **Pair new device** and run these on the computer, using the address and code the watch shows:
   ```
   adb pair <IP:port> <code>
   adb connect <IP:port>
   adb install Watch-LionFlow-1.1.x.apk
   ```
4. Open LionFlow on the watch. Your phone needs to be nearby, with LionFlow installed.

The watch app doesn't update itself. Install a new version the same way when you want it.

## Features

- **Sign-in** on Peugeot's own page, "Remember me", and app lock with your security code or fingerprint
- **Status:** battery, range, charging, doors, odometer, position and next service
- **Remote control:** lock/unlock, lights, horn, pre-heating, and start/stop charging, confirmed with fingerprint or security code
- **Map:** free vector map (MapLibre + OpenFreeMap) in 2D/3D that you can drag, zoom and rotate, showing you, the car and charging stations — free from OpenStreetMap (no key), or from NOBIL if you add a free API key
- **EV trip planner** (fully electric cars): type where you are going, and LionFlow draws the route and picks fast-charging stops from your start charge and the lowest charge you accept. Battery use is tuned with your own longest trips. Change how full to charge or how long to stay at each stop, swap chargers, and save addresses. Prices are the operators' usual drop-in prices from a small built-in table (Norway and Germany so far), not live prices
- **Weather** now and the forecast where you are (MET Norway)
- **Trips** with CSV export and import, plus your own service history
- **Home-screen widget:** battery %, charging, range, pre-heating and outside temperature
- **Notifications:** charging complete or stopped, pre-heating finished, car left unlocked, low battery and service due
- Light, dark or system theme, km or miles, °C or °F
- Adapts to foldables and tablets with a side menu and a two-column home screen

## Security

- Tokens, the remote-control state and your security code are encrypted with an Android Keystore key (AES-256-GCM).
- Your password is only typed on Peugeot's own page. The app never sees it.
- There is no backend and no analytics. The app talks directly to Peugeot, OpenFreeMap, OpenStreetMap (Overpass), Photon and OSRM (trip planner), MET Norway, GitHub (updates) and — only if you choose it — NOBIL.


## iPhone, iPad and Apple Watch (preview)

LionFlow is also being built for iPhone and iPad (iOS and iPadOS 15 or newer, so an iPhone 6s from 2015 or an iPad Air 2 from 2014 is new enough), with a small Apple Watch app. The code is in [`iOS/`](iOS), written in Swift from the Android app, part by part.

**Where it stands.** Nobody has tried it yet **on a real iPhone, with a real account and a real car**. So treat it as a preview, expect rough edges, and please [tell me what you find](https://github.com/thocubeat/lionflow/issues). What is checked, by a build machine every time the code changes:

- the car logic gives the same answers as the Android app's own code, value by value;
- the app builds for iPhone, iPad and Apple Watch, and every icon it uses exists on iOS 15;
- a walk through the whole app in the iPhone and iPad simulators with a sample car, tapping and scrolling the way a person would: every tab from top to bottom, the sheets and editors, commands to the car, pulling a page down to read the car again, the first screens ([pictures](https://github.com/thocubeat/lionflow/tree/ios-previews-tour));
- most of that walk-through again on iOS 16, the oldest system the simulator can still get, as an iPhone 8, and the main screens as an iPad from 2017 ([pictures](https://github.com/thocubeat/lionflow/tree/ios-previews-old-ios16-macos-26)). iOS 15 itself is no longer offered for the simulator, so nothing has been run on it;
- the carmaker's servers accept the kind of encrypted connection iOS insists on.

**What it has:** sign-in on the brand's own page, status, the Home screen you arrange yourself, charging with the charging log, places and prices, pre-heating and its schedules, trips with fuel and hybrid statistics, tags and CSV export/import, service, the charger map (Apple Maps), car data, several cars and accounts, remote control (lock, lights, horn, pre-heating, charging) confirmed with Face ID, Touch ID or your security code, the demo mode, all seven languages, iPad layouts, and a home-screen widget in three sizes (it shows what the app last read; iOS lets the app read the car in the background now and then, often about once an hour, and the widget's ↻ opens the app and reads it right away).

**What it doesn't have (yet):** CarPlay (Apple has to approve each app for it), the trip planner, drive recording over Bluetooth, the in-app updater, and everything the Android app does on its own while it is closed: notifications, LionFlow's own charge limit, charging times and the smart pre-heating schedule. iOS gives an app that is not open very little time to work. The car's own daily charge limit, start/stop charging and the pre-heating schedules stored in the car all work.

### Install on iPhone or iPad (outside the App Store)

LionFlow isn't in the App Store. You install it with a free tool that signs the app with your own Apple ID, which Apple allows for personal use. A normal, free Apple ID is enough, with Apple's limits for those:

- Apple's signature on the app lasts **7 days**. Then it has to be renewed ("refreshed"), or the app won't open. Your sign-in and data stay either way. The tools below can do this by themselves (see *Keep it working by itself*).
- at most **3** apps installed this way at a time (SideStore itself is one of them).
- on iOS 16 and newer you turn on **Developer Mode** once (Settings → Privacy & Security → Developer Mode).

An iPhone app can't renew its own signature or install its own updates: Apple leaves that to these tools. So the easiest way is **SideStore**, which does both on the phone itself. You need a computer once, to set it up.

**1. Install SideStore** (once, with a Windows PC, Mac or Linux computer and a cable):

1. On the iPhone, install **LocalDevVPN** from the App Store. SideStore uses it to install and refresh apps without a computer.
2. On the computer, install **iloader** and use it to put SideStore on the iPhone. Follow SideStore's own guide, which is kept up to date: [docs.sidestore.io](https://docs.sidestore.io).
3. Open SideStore, sign in with the same Apple ID, and refresh SideStore itself once (My Apps). From now on you don't need the computer.

**2. Add LionFlow's source and install LionFlow:**

1. In SideStore, open **Sources**, tap **+**, and add this address:
   ```
   https://github.com/thocubeat/lionflow/releases/download/ios-latest/source.json
   ```
2. Open LionFlow in that source and install it (connect LocalDevVPN first).
3. If iOS asks: Settings → General → **VPN & Device Management** → your Apple ID → **Trust**, and turn on Developer Mode (see above).
4. Open LionFlow and sign in as described under *First sign-in* above. After you have signed in on the brand's page, you come back to LionFlow by yourself.

AltStore reads the same source, so this works there too (AltStore needs AltServer running on a computer on the same Wi-Fi).

### Keep it working by itself

- **The 7-day signature.** SideStore renews it in the background while LocalDevVPN is connected. To be sure it happens, let the Shortcuts app run it every night: open **Shortcuts** → **Automation** → **+** → **Time of Day** (for example 03:00, Daily) → **Run Immediately** → add the action **Refresh All Apps** (from SideStore) → **Done**. LocalDevVPN has to be connected at that time.
- **New versions.** Every new build is added to LionFlow's source. SideStore notices by itself and says *New Update Available*; tap **Update** under My Apps. LionFlow says so too, with an **Install now** button that hands the new build to SideStore or AltStore. It is installed over the one you have, so your sign-in and data stay.
- **A warning, just in case.** Settings → App in LionFlow shows how long the signature is valid, and Home warns you two days before it runs out, with a button that opens SideStore.

**Other ways.** With **Sideloadly** ([sideloadly.io](https://sideloadly.io), Windows or Mac) you install the file from the computer instead:

**Download:** [`LionFlow.ipa`](https://github.com/thocubeat/lionflow/releases/download/ios-latest/LionFlow.ipa) (always the newest build).

1. Install Sideloadly on the computer and connect the iPhone or iPad with a cable (tap **Trust** on the phone).
2. Drag `LionFlow.ipa` into Sideloadly, type your Apple ID, leave **automatic refresh** on, and press **Start**.
3. On the phone: Settings → General → **VPN & Device Management** → your Apple ID → **Trust**. On iOS 16 or newer, also turn on Developer Mode (see above).

Sideloadly renews the signature by itself while its helper (the Sideloadly Daemon) runs on the computer and the phone is on the same Wi-Fi. New versions don't come by themselves that way: when LionFlow says one is ready, install the new `LionFlow.ipa` the same way, with the same Apple ID.

| Tool | You need | Signature renewed by itself | New versions |
|---|---|---|---|
| [SideStore](https://sidestore.io) | a computer for the first set-up only | on the phone (LocalDevVPN connected) | from LionFlow's source, one tap |
| [AltStore](https://altstore.io) | a computer on the same Wi-Fi running AltServer | when phone and computer are on the same Wi-Fi | from LionFlow's source, one tap |
| [Sideloadly](https://sideloadly.io) | a computer on the same Wi-Fi running its daemon | when phone and computer are on the same Wi-Fi | install the new file yourself |

**With a Mac and Xcode** you can build it yourself, which is also the only way to get the Apple Watch app (see below):

```
git clone https://github.com/thocubeat/lionflow.git
cd lionflow/iOS
brew install xcodegen && xcodegen
open LionFlow.xcodeproj
```

In `iOS/project.yml`, change `LF_BUNDLE_ID` to a name of your own (for example `com.yourname.lionflow`) and run `xcodegen` again. In Xcode, pick your Apple ID under *Signing & Capabilities* for both targets, choose your iPhone at the top, and press Run.

### Apple Watch

The watch app shows battery or fuel level and range, kilometres driven today and yesterday, and where the car is. It can lock or unlock, flash the lights and start pre-heating, and asks before each. Like the Wear OS app it has no sign-in of its own: it asks LionFlow on the iPhone. Choose what it shows under Settings → Home screens → Watch.

What the watch asks and what the phone answers are tested against each other, and the watch's screens are looked at in the watch simulator. The link between a real watch and a real iPhone has not been tried yet.

The tools above can't put a watch app on the watch with a free Apple ID (they install the iPhone app only), so for now the watch app needs a Mac: build with Xcode as above, then choose the **LionFlowWatch** scheme and your watch at the top, and press Run. `LionFlow-with-Watch.ipa` on the [download page](https://github.com/thocubeat/lionflow/releases/tag/ios-latest) is the same app with the watch app inside, for tools that can install it.


## Disclaimer

LionFlow is an independent app, not made, endorsed or supported by Stellantis, Peugeot, Citroën, DS Automobiles, Opel or Vauxhall. All brand and app names are trademarks of their owners and are only used to say which cars the app works with. Remote commands act on a real car, so make sure it's safe to lock, unlock, heat or charge it.

## Support LionFlow

LionFlow is free, has no ads and never will. If it makes your car a bit nicer to live with, you may donate what you want, so that I'm able to work with this in my spare time:

[![Support on Ko-fi](https://img.shields.io/badge/Support-Ko--fi-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/thocubeat)

Thank you! ☕

## License

GPL-3.0 — see [`LICENSE`](LICENSE). Third-party code, licences and data sources are listed in [`NOTICE.md`](NOTICE.md).

## Credits

The Stellantis sign-in, API and MQTT flow are ported from
[homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles) (MIT), and the
one-time-code generator from [psa_car_controller](https://github.com/flobz/psa_car_controller) (GPL-3.0) by Florian Bezannier.
Thanks to both projects — LionFlow wouldn't exist without them. Details in `NOTICE.md`.
