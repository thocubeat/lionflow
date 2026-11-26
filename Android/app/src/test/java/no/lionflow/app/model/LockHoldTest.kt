package no.lionflow.app.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the app shows after a lock or unlock from the app, while the car's report catches up (LockHold). */
class LockHoldTest {
    private val sent = 1_764_000_000_000L   // when the command was done

    private fun iso(ms: Long) = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))

    private fun status(lock: String, doorsAt: Long?, reportAt: Long = doorsAt ?: sent) = VehicleStatus.parse(JSONObject()
        .put("updatedAt", iso(reportAt))
        .put("doorsState", JSONObject().put("lockedStates", JSONArray().put(lock)).also { d -> doorsAt?.let { d.put("updatedAt", iso(it)) } }), null)

    @Test fun theOldReportDoesNotFlipTheScreenBack() {
        // Locked from the app; the server still has the report from before the command.
        val h = LockHold(want = true, since = sent)
        assertTrue(h.holds(status("Unlocked", sent - 60_000), sent + 3_000))
        // A report from the very moment of the command can still be the old one.
        assertTrue(h.holds(status("Unlocked", sent + 10_000), sent + 30_000))
    }

    @Test fun theCarLockingItselfAgainIsShown() {
        // Unlocked from the app, no door opened: about 30 s later the car locks itself and reports it.
        val h = LockHold(want = false, since = sent)
        assertFalse(h.holds(status("Locked", sent + 35_000), sent + 90_000))
    }

    @Test fun lockedWithTheKeyAfterAnUnlockFromTheAppIsShown() {
        val h = LockHold(want = false, since = sent)
        assertFalse(h.holds(status("SuperLocked", sent + 120_000), sent + 150_000))
    }

    @Test fun theWishIsLetGoWhenTheCarConfirmsItOrAfterFiveMinutes() {
        val h = LockHold(want = true, since = sent)
        assertFalse(h.holds(status("Locked", sent - 60_000), sent + 3_000))
        assertFalse(h.holds(status("Unlocked", sent - 60_000), sent + 300_001))
    }

    @Test fun withoutATimeOnTheDoorsTheWholeReportsTimeCounts() {
        val h = LockHold(want = false, since = sent)
        assertTrue(h.holds(status("Locked", doorsAt = null, reportAt = sent - 5_000), sent + 3_000))
        assertFalse(h.holds(status("Locked", doorsAt = null, reportAt = sent + 40_000), sent + 90_000))
        assertEquals(sent + 40_000, LockHold.reportedAt(status("Locked", doorsAt = null, reportAt = sent + 40_000)))
    }
}
