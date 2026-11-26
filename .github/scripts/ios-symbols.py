#!/usr/bin/env python3
"""Checks that every icon the iPhone and watch apps use exists on the oldest systems they run on.

The icons are Apple's SF Symbols, asked for by name. A name that a system doesn't know shows up as an empty space,
and the compiler can't tell. macOS has the list of names with the year each one arrived: this compares.
Usage: ios-symbols.py <iOS folder>
"""
import glob
import os
import plistlib
import re
import sys

OLDEST = {"iOS": (15, 0), "watchOS": (8, 0)}
LIST = "/System/Library/CoreServices/CoreGlyphs.bundle/Contents/Resources/name_availability.plist"


def version(text):
    return tuple(int(n) for n in str(text).split("."))


def names_in(folder):
    """Icon names in the Swift files of one folder, with the file each one is in."""
    found = {}
    for path in sorted(glob.glob(os.path.join(folder, "*.swift"))):
        text = open(path, encoding="utf-8").read()
        names = re.findall(r'(?:systemName:|LfIcon\(|icon:)\s*"([a-z0-9.]+)"', text)
        block = re.search(r"enum LfIcons \{(.*?)\n\}", text, re.S)
        if block:
            names += re.findall(r'static let \w+ = "([a-z0-9.]+)"', block.group(1))
        for name in names:
            found.setdefault(name, os.path.basename(path))
    return found


def main():
    root = sys.argv[1] if len(sys.argv) > 1 else "."
    if not os.path.exists(LIST):
        print("This Mac has no list of icon names (" + LIST + "): the icons were NOT checked.")
        return 0
    with open(LIST, "rb") as f:
        data = plistlib.load(f)
    symbols, years = data["symbols"], data["year_to_release"]
    bad = 0
    for folder, system in (("LionFlow", "iOS"), ("Watch", "watchOS")):
        names = names_in(os.path.join(root, folder))
        for name, where in sorted(names.items()):
            year = symbols.get(name)
            if year is None:
                print(f"{where}: the icon \"{name}\" does not exist")
                bad += 1
                continue
            since = years.get(year, {}).get(system)
            if since is None or version(since) > OLDEST[system]:
                print(f"{where}: the icon \"{name}\" only exists from {system} {since}")
                bad += 1
        print(f"{folder}: {len(names)} icons checked against {system} {'.'.join(str(n) for n in OLDEST[system])}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
