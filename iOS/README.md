# LionFlow for iPhone, iPad and Apple Watch

The Android app in [`../Android`](../Android), written again in Swift. How to install it is in the
[main README](../README.md#iphone-ipad-and-apple-watch-preview). This page is for working on the code.

## What is where

| Folder | What |
|---|---|
| `Core/` | The car logic, a port of the Android app's Kotlin files of the same names: the carmaker's API, sign-in tokens, remote control (one-time codes and MQTT), status and trips, charging log, statistics, settings, translations, and the app's state (`AppModel`, a port of the Android view model). No screens, only Foundation. |
| `CoreTests/` | Tests for `Core/`. `Fixtures/*_expected.json` are the Android app's own answers. |
| `LionFlow/` | The iPhone and iPad app: SwiftUI screens and what only a phone can do (Keychain, Face ID, the sign-in page, location, Apple Maps). |
| `Watch/` | The Apple Watch app. It asks the phone app (`Core/WatchLink.swift` answers, `Core/WatchWire.swift` is the watch's side). |
| `oracle/` | The program that made the fixtures by running the Android app's own Kotlin code. |
| `l10n-ios.tsv` | The few texts only this app has. All the others are the Android app's own file, `../Android/app/src/main/assets/l10n.tsv`. |

iOS 15 and watchOS 8 or newer, Swift 5, SwiftUI, no third-party libraries.

## Build and test

```
brew install xcodegen
xcodegen                 # makes LionFlow.xcodeproj from project.yml
open LionFlow.xcodeproj

swift test               # the car logic on its own, on a Mac, without a simulator
```

`LIONFLOW_MQTT_TEST=1 swift test` also sends remote-control commands through a real message server on this machine
(`mosquitto -p 18830`).

Every push runs [`.github/workflows/ios.yml`](../.github/workflows/ios.yml): the tests, a check that every icon exists
on iOS 15, the build for iPhone, iPad and Apple Watch, and pictures of every screen in the simulators (demo mode),
which land on the [`ios-previews`](https://github.com/thocubeat/lionflow/tree/ios-previews) branch. A build of `main`
also puts `LionFlow.ipa` on the [download page](https://github.com/thocubeat/lionflow/releases/tag/ios-latest).

## Same answers as the Android app

The two apps must work things out the same way. So the tests don't check the Swift code against what its author
expected: they check it against the Android code. `oracle/` compiles the Android app's own Kotlin files and runs them
on a set of inputs (car reports, trips, texts in every language, settings, the sample cars, one-time codes), and the
answers are kept in `CoreTests/Fixtures`. The Swift tests feed the same inputs to the Swift code and compare value by
value.

When the Android logic changes, change the Swift file the same way, then make the fixtures again with
`oracle/build.sh` and look at what changed in them before committing: they are the reference, so they should only
change when the Android code did.

## Pictures and demo mode

The app takes a few arguments at launch, used for the pictures: `-demo electric|hybrid|fuel`, `-brand opel`,
`-tab Charging`, `-theme light`, `-lang nb`, `-clean` (no demo banner), `-signedout`. The watch app takes `-preview`
(a sample car, no phone needed). They only ever start or leave demo mode.
