#!/usr/bin/env python3
"""Writes LionFlow's source: the file SideStore and AltStore read to install the iPhone app and to find new versions.

    ios-source.py <LionFlow.ipa> <download URL of that file> <what is new> <output .json>

Everything about the build is read from the file itself (version, build number, oldest iOS, the permissions it asks
for), so the source always matches what is downloaded: both tools check the version, the build number, the size and
the SHA-256 against the source before they install, and refuse a build that asks for permissions the source doesn't
list. The format is AltStore's (https://faq.altstore.io/developers/make-a-source), which SideStore reads too.
"""
import datetime
import hashlib
import json
import os
import plistlib
import sys
import zipfile

REPO = "https://github.com/thocubeat/lionflow"
RAW = "https://raw.githubusercontent.com/thocubeat/lionflow"
TINT = "C6F36B"


def read_app(ipa):
    with zipfile.ZipFile(ipa) as z:
        names = [n for n in z.namelist() if n.startswith("Payload/") and n.count("/") == 2 and n.endswith(".app/Info.plist")]
        if not names:
            sys.exit("no Payload/<name>.app/Info.plist in " + ipa)
        info = plistlib.loads(z.read(names[0]))
        # Every NS…UsageDescription in the app and in anything inside it (an extension asks for its own).
        privacy = {}
        for n in z.namelist():
            if n.endswith("/Info.plist") and n.startswith(names[0].rsplit("/", 1)[0] + "/"):
                try:
                    inner = plistlib.loads(z.read(n))
                except Exception:
                    continue
                for k, v in inner.items():
                    if k.startswith("NS") and k.endswith("UsageDescription") and isinstance(v, str):
                        privacy[k] = v
        return info, privacy


def main():
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    ipa, url, notes, out = sys.argv[1:]
    info, privacy = read_app(ipa)
    data = open(ipa, "rb").read()
    version = info["CFBundleShortVersionString"]
    build = str(info["CFBundleVersion"])
    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
    source = {
        "name": "LionFlow",
        "identifier": "no.lionflow.source",
        "subtitle": "For Peugeot, Citroën, DS, Opel and Vauxhall",
        "description": "LionFlow for iPhone and iPad, straight from its GitHub page. Every new build is added here, so "
                       "SideStore and AltStore can tell you about it and install it over the one you have.",
        "iconURL": RAW + "/main/iOS/LionFlow/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
        "website": REPO,
        "tintColor": TINT,
        "featuredApps": [info["CFBundleIdentifier"]],
        "apps": [{
            "name": "LionFlow",
            "bundleIdentifier": info["CFBundleIdentifier"],
            "developerName": "thocubeat",
            "subtitle": "A faster app for your Peugeot, Citroën, DS, Opel or Vauxhall",
            "localizedDescription": "Status, charging, pre-heating, trips, service and remote control for cars that use "
                                    "the MyPeugeot, MyCitroën, MyDS, myOpel or myVauxhall app. Open source, no ads, "
                                    "and it talks only to your car's own servers.\n\nA preview: tested by programs and "
                                    "in the simulator. Please tell what you find: " + REPO + "/issues",
            "iconURL": RAW + "/main/iOS/LionFlow/Assets.xcassets/AppIcon.appiconset/icon-1024.png",
            "tintColor": TINT,
            "category": "utilities",
            "screenshotURLs": [RAW + "/ios-previews/phone-electric-" + tab + ".png" for tab in ("Home", "Charging", "Climate", "Trips")],
            "versions": [{
                "version": version,
                "buildVersion": build,
                "date": now.isoformat().replace("+00:00", "Z"),
                "localizedDescription": notes.strip() or "A new build.",
                "downloadURL": url,
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "minOSVersion": info.get("MinimumOSVersion", "15.0"),
            }],
            "appPermissions": {
                # The file is not signed, so it carries no entitlements: the tool that signs it adds its own.
                "entitlements": [],
                "privacy": privacy,
            },
        }],
        "news": [],
    }
    with open(out, "w", encoding="utf-8") as f:
        json.dump(source, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"{out}: LionFlow {version} ({build}), {len(data)} bytes, {len(privacy)} permissions, from {url}")


if __name__ == "__main__":
    main()
