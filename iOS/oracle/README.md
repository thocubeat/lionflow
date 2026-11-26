# Where the test answers come from

The iPhone app's logic is checked against the Android app's own code. `build.sh` takes the pure logic out of the
Android sources (`prepare.py`), builds it on a desktop together with `Oracle.kt`, and runs it on the inputs in
`../CoreTests/Fixtures`. What it prints into `*_expected.json` there is what the Swift tests compare with.

Run it again when the Android logic or the inputs change. It needs git, a JDK, python3 and `kotlinc`:

    KOTLINC=/path/to/kotlinc ./build.sh

Android's JSON classes (`org.json`) are not part of a desktop JDK, so the script uses
[open-json](https://github.com/tdunning/open-json), which is the same code with one difference that matters here:
Android writes every `/` in a text as `\/`, open-json only the one in `</`. The Swift code writes it the way Android
does, and the tests leave that one difference out of the comparison (see `sameJSONText` in the tests).
