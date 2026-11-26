"""Copies the Android app's pure logic into a folder that builds without Android, for the fixture maker.

Files that are plain Kotlin are copied as they are. From files that also hold Android or Compose code only the logic is
taken out, by its exact text, so a change on the Android side that this script doesn't understand stops it loudly."""
import re, sys, os

root, out = sys.argv[1], sys.argv[2]
app = os.path.join(root, 'Android/app/src/main/java/no/lionflow/app')

def read(rel): return open(os.path.join(app, rel), encoding='utf-8').read()
def write(name, text): open(os.path.join(out, name), 'w', encoding='utf-8').write(text)
def between(text, start, end, rel):
    a = text.index(start)
    b = text.index(end, a) if end else len(text)
    return text[a:b]

# Plain Kotlin: as it is.
for rel in ['model/VehicleStatus.kt', 'model/FuelStats.kt', 'model/HybridSplit.kt', 'model/Brand.kt', 'data/HomeLayout.kt', 'model/TripImport.kt']:
    write(os.path.basename(rel), read(rel))

# The small JSON helpers at the end of Http.kt.
http = read('api/Http.kt')
write('JsonHelpers.kt', 'package no.lionflow.app.api\nimport org.json.JSONArray\nimport org.json.JSONObject\n' + between(http, '// ---- tiny JSON helpers used across the app ----', None, 'Http.kt'))

# How an HTTP error is put into words.
describe = between(http, '    /** "Peugeot server error 503', '    /** Human text for any exception', 'Http.kt')
write('HttpWords.kt', 'package no.lionflow.app.api\nobject HttpWords {\n' + describe + '}\n')

# The settings' data class only.
settings = read('data/Settings.kt')
write('AppSettings.kt', 'package no.lionflow.app.data\n' + between(settings, 'data class AppSettings(', '/** Plain (non-secret) preferences', 'Settings.kt'))

# The tabs, and what a car can do.
vm = read('ui/AppViewModel.kt')
tab = re.search(r'^enum class Tab \{[^}]*\}', vm, re.M).group(0)
write('Caps.kt', read('ui/Caps.kt') + '\n' + tab + '\n')

# Number and time formatting from Root.kt.
rootkt = read('ui/Root.kt')
fmt = between(rootkt, '// ---------------- formatting helpers ----------------', '@Composable\nfun Busy', 'Root.kt')
write('Fmt.kt', 'package no.lionflow.app.ui\nimport no.lionflow.app.data.AppSettings\n' + fmt)

# Translations: the same code, without the phone around it.
l10n = read('ui/l10n/L10n.kt')
def swap(text, a, b):
    assert a in text, 'The Android source changed: ' + a[:60]
    return text.replace(a, b)
l10n = swap(l10n, 'import android.content.Context\n', '')
l10n = swap(l10n, 'import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.setValue\n', '')
l10n = swap(l10n, 'var lang by mutableStateOf("en")', 'var lang = "en"')
l10n = swap(l10n, 'fun init(ctx: Context, preference: String)', 'fun init(ctx: java.io.File, preference: String)')
l10n = swap(l10n, 'private fun load(ctx: Context)', 'private fun load(ctx: java.io.File)')
l10n = swap(l10n, 'ctx.assets.open("l10n.tsv").bufferedReader(Charsets.UTF_8).readLines()', 'ctx.readLines(Charsets.UTF_8)')
l10n = l10n.replace('android.util.Log.', 'OracleLog.')
# Android's log is not there on a desktop: the same lines go to the terminal instead.
write('L10n.kt', l10n + '''
object OracleLog {
    private fun say(level: String, tag: String, msg: String, e: Throwable?): Int { System.err.println("$level/$tag: $msg" + (e?.let { " ($it)" } ?: "")); return 0 }
    fun i(a: String, b: String) = say("I", a, b, null)
    fun w(a: String, b: String, e: Throwable? = null) = say("W", a, b, e)
    fun e(a: String, b: String, e: Throwable? = null) = say("E", a, b, e)
}
''')
print('prepared', len(os.listdir(out)), 'files')

# ---- More of the app's logic, for Oracle2.kt ----

# A clock the fixture maker can set, in place of the phone's.
write('OracleClock.kt', 'object OracleClock { var now = 1_795_000_000_000L }\n')
def fixed_clock(text): return text.replace('System.currentTimeMillis()', 'OracleClock.now')

# The sign-in settings and the car record, from StellantisApi.kt.
api = read('api/StellantisApi.kt')
cfg = between(api, 'data class BrandConfig(', 'internal enum class MildHybridDetection', 'StellantisApi.kt')
cfg = swap(cfg, 'Base64.encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)',
           'java.util.Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(Charsets.UTF_8))')
cfg = swap(cfg, 'ctx.assets.open("stellantis_configs.json").bufferedReader().use { it.readText() }', 'ctx.readText()')
cfg = swap(cfg, 'ctx: Context', 'ctx: java.io.File')
car = between(api, 'internal enum class MildHybridDetection', 'class AuthExpiredException', 'StellantisApi.kt')
write('Vehicle.kt', 'package no.lionflow.app.api\nimport no.lionflow.app.model.VehicleStatus\nimport org.json.JSONArray\nimport org.json.JSONObject\nimport java.util.Locale\n' + cfg + car)

# Charging places and sessions (the data, and the two functions that need no phone), from ChargeLog.kt.
log = read('model/ChargeLog.kt')
models = between(log, '/** A place where you charge', '/**\n * Charging log.', 'ChargeLog.kt')
match = swap(between(log, '    fun match(s: ChargeSession, cfg: PlacesConfig = _places.value): PlaceMatch {', '    fun savePlaces(', 'ChargeLog.kt'),
             'cfg: PlacesConfig = _places.value', 'cfg: PlacesConfig')
distance = between(log, '    fun distanceM(', None, 'ChargeLog.kt')
write('ChargeModels.kt', 'package no.lionflow.app.model\nimport org.json.JSONObject\nimport kotlin.math.asin\nimport kotlin.math.cos\nimport kotlin.math.sin\nimport kotlin.math.sqrt\n'
      + models + 'object ChargeLog {\n' + match + distance)

# The sample car, without the phone's storage and with the settable clock.
demo = read('demo/Demo.kt')
demo = swap(demo, 'import android.content.Context\n', '')
demo = swap(demo, '    fun load(ctx: Context) { kind = prefs(ctx).getString("kind", "electric")?.takeIf { it in kinds } ?: "electric" }\n', '')
demo = swap(demo, '    fun choose(ctx: Context, k: String) {\n        kind = k.takeIf { it in kinds } ?: "electric"\n        prefs(ctx).edit().putString("kind", kind).apply()\n    }',
            '    fun choose(k: String) { kind = k.takeIf { it in kinds } ?: "electric" }')
demo = swap(demo, '    private fun prefs(ctx: Context) = ctx.getSharedPreferences("demo", Context.MODE_PRIVATE)\n    fun isOn(ctx: Context) = prefs(ctx).getBoolean("on", false)\n    fun setOn(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("on", on).apply() }\n', '')
assert 'Context' not in demo, 'Demo.kt changed: still uses the phone'
write('Demo.kt', fixed_clock(demo))

# Trip tags, without the screen framework.
tags = read('model/TripTags.kt')
tags = swap(tags, 'import androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.setValue\n', '')
tags = swap(tags, 'var data by mutableStateOf(parse(load()))', 'var data = parse(load())')
write('TripTags.kt', fixed_clock(tags))

# Weather words and the 12/24-hour clock.
services = read('api/Services.kt')
weather = between(services, 'data class WeatherHour(', 'object WeatherApi {', 'Services.kt')
words = between(services, '    fun describe(symbol: String): String = when {', '\n}\n\n\n// ---------------- Chargers: NOBIL' if '\n}\n\n\n// ---------------- Chargers: NOBIL' in services else '\n}\n\n// ---------------- Chargers: NOBIL', 'Services.kt')
write('Weather.kt', 'package no.lionflow.app.api\n' + weather + 'object WeatherApi {\n' + words + '\n}\n')
timefmt = read('ui/l10n/TimeFmt.kt')
write('TimeFmt.kt', 'package no.lionflow.app.ui.l10n\nobject TimeFmt {\n    var use24 = true\n' + between(timefmt, '    /** Turns a date pattern written with HH:mm into the chosen clock. */', None, 'TimeFmt.kt'))
print('prepared', len(os.listdir(out)), 'files in all')

# ---- The one-time-code generator, for Oracle3.kt: the same code, with the network call and the random bytes
# handed to the fixture maker, so a whole activation can be played through and written down. ----
otp = read('api/Otp.kt')
for line in ['import android.util.Xml\n', 'import okhttp3.HttpUrl.Companion.toHttpUrl\n', 'import okhttp3.Request\n', 'import org.xmlpull.v1.XmlPullParser\n', 'import java.io.StringReader\n']:
    otp = swap(otp, line, '')
call = between(otp, '    private suspend fun request(params: Map<String, String>, setup: Boolean): Map<String, String> {', '    /** Step 1 of activation, or step 1 of producing an OTP. */', 'Otp.kt')
otp = swap(otp, call, '    private fun request(params: Map<String, String>, setup: Boolean): Map<String, String> = OracleOtp.server(params, setup)\n\n')
xml = between(otp, '        /** Flattens the children of the given root element into tag -> text (first occurrence wins). */', '\nfun ByteArray.toHex()', 'Otp.kt')
otp = swap(otp, xml, '    }\n}\n')
assert otp.count('SecureRandom().nextBytes(it)') == 3, 'Otp.kt changed: random bytes'
otp = otp.replace('SecureRandom().nextBytes(it)', 'OracleOtp.fill(it)')
otp = otp.replace('suspend fun ', 'fun ')
for name in ['kma', 'getR', 'computeOtp', 'serial']:
    otp = swap(otp, '    private fun %s(' % name, '    fun %s(' % name)
assert 'Xml' not in otp and 'Http.' not in otp, 'Otp.kt changed: still uses the phone or the network'
write('Otp.kt', otp)
print('prepared', len(os.listdir(out)), 'files in all')
