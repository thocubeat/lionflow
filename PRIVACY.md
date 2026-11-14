# LionFlow privacy policy

_Last updated: 14 November 2026_

LionFlow is an unofficial, open-source app for controlling your own Peugeot. It has no servers of its own, no accounts of its own, no ads and no analytics. The developer receives none of your data.

## What the app handles, and where it goes

| Data | Why | Sent to |
|---|---|---|
| Your Peugeot sign-in (OAuth tokens), remote-control code state | To read your car's status and send commands | Stellantis/Peugeot (`api.groupe-psa.com`, `idpcvs.peugeot.com`, `mwa.mpsa.com`, `otp.mpsa.com`). Stored encrypted on your phone with Android Keystore. |
| Car status, trips, maintenance | Shown in the app, widget and Android Auto | Fetched from Stellantis; kept on your phone only |
| Your location (only while the app or Android Auto map is open) | Map position, weather, nearby chargers | Approximate coordinates go to MET Norway (weather), OpenStreetMap Overpass or NOBIL (chargers) and OpenFreeMap (map tiles for the area you view) |
| Car photo, service history, imported trips | Your own notes | Stay on your phone |
| Update checks (GitHub version only) | Finding new versions | GitHub (`api.github.com`) |

No data is sold or shared for advertising. Nothing is backed up to the cloud by the app (Android backup is turned off).

## Deleting your data

Sign out in Settings → Sign out, or uninstall the app. That removes everything LionFlow stored on your phone.

## Contact

Open an issue at https://github.com/thocubeat/lionflow/issues.
