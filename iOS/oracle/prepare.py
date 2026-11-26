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
for rel in ['model/VehicleStatus.kt', 'model/FuelStats.kt', 'model/HybridSplit.kt', 'model/Brand.kt', 'data/HomeLayout.kt']:
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
    assert a in text, 'L10n.kt changed: ' + a[:60]
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
