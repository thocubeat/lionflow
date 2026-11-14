# Third-party notices

LionFlow is licensed under the GNU General Public License v3.0 (see `LICENSE`).

## psa_car_controller — GPL-3.0
The one-time-password generator for remote commands (`app/src/main/java/no/lionflow/app/api/Otp.kt`)
is a Kotlin port of the OTP code in [psa_car_controller](https://github.com/flobz/psa_car_controller)
by Florian Bezannier, licensed under the GNU General Public License v3.0.

## homeassistant-stellantis-vehicles — MIT
The Stellantis sign-in, API and MQTT command flow (`StellantisApi.kt`, `RemoteControl.kt`, `VehicleStatus.kt`)
and `app/src/main/assets/stellantis_configs.json` are based on
[homeassistant-stellantis-vehicles](https://github.com/andreadegiovine/homeassistant-stellantis-vehicles).
Its OTP step is itself a fork of psa_car_controller.

```
MIT License

Copyright (c) 2023

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries
- AndroidX, Jetpack Compose, OkHttp, WorkManager — Apache License 2.0
- MapLibre Native for Android — BSD 2-Clause
- Eclipse Paho MQTT client — Eclipse Distribution License 1.0 (BSD-style; used under EDL)
- Kotlin coroutines — Apache License 2.0

## Data and services
- Map data © OpenStreetMap contributors (ODbL), tiles by OpenFreeMap
- Weather data from MET Norway (NLOD / CC BY 4.0)
- Charging stations from OpenStreetMap via the Overpass API (ODbL, default), or from NOBIL (CC BY 3.0) with your own free API key
