// Reading trip files from other apps: the Android app's own reader (TripImport.kt), run on the sample files and dates
// in import_inputs.json. What it made of each one is written down, so the iPhone app can be checked to read them the
// same way. Run with the clock set to Norwegian time (see build.sh), so summer and winter time are part of the test.
// Writes its own file only, and never over one that is there.
import no.lionflow.app.model.TripImport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

fun main(args: Array<String>) {
    val dir = File(args[0])
    val file = File(dir, "import_expected.json")
    if (file.exists()) { println("import_expected.json is already there: not written again"); return }
    val input = JSONObject(File(dir, "import_inputs.json").readText())
    val files = JSONArray()
    val inFiles = input.getJSONArray("files")
    for (i in 0 until inFiles.length()) {
        val f = inFiles.getJSONObject(i)
        val o = JSONObject().put("name", f.getString("name"))
        try {
            val rows = JSONArray()
            for (r in TripImport.parse(f.getString("text"))) rows.put(JSONObject().put("json", r.json.toString()).put("tag", r.tag ?: JSONObject.NULL))
            o.put("rows", rows)
        } catch (e: Exception) {
            o.put("error", e.javaClass.simpleName)
        }
        files.put(o)
    }
    val dates = JSONArray()
    val inDates = input.getJSONArray("dates")
    for (i in 0 until inDates.length()) {
        val s = inDates.getString(i)
        dates.put(JSONArray().put(s).put(TripImport.date(s)?.toString() ?: JSONObject.NULL))
    }
    file.writeText(JSONObject().put("zone", java.util.TimeZone.getDefault().id).put("files", files).put("dates", dates).toString(1))
    println("wrote import_expected.json: ${files.length()} files, ${dates.length()} dates, clock set to ${java.util.TimeZone.getDefault().id}")
}
