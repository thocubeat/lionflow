package no.lionflow.app.route

import android.content.Context
import java.io.File

/** A route recorded with the phone's GPS. Points are kept on this phone only. */
data class Route(val file: File, val startMillis: Long, val endMillis: Long, val km: Double)

object Routes {
    fun dir(ctx: Context) = File(ctx.filesDir, "routes").apply { mkdirs() }

    /** Points as (lat, lon, time). File lines: "lat,lon,time". */
    fun points(f: File): List<Triple<Double, Double, Long>> = try {
        f.readLines().mapNotNull { l ->
            val p = l.split(",")
            if (p.size < 3) null else Triple(p[0].toDouble(), p[1].toDouble(), p[2].toLong())
        }
    } catch (_: Exception) { emptyList() }

    fun distanceKm(pts: List<Triple<Double, Double, Long>>): Double =
        pts.zipWithNext().sumOf { (a, b) -> no.lionflow.app.model.ChargeLog.distanceM(a.first, a.second, b.first, b.second) } / 1000.0

    /** Finished routes, newest first (the one being recorded is excluded). */
    fun list(ctx: Context): List<Route> = dir(ctx).listFiles { f -> f.name.endsWith(".csv") }.orEmpty().mapNotNull { f ->
        val name = f.name.removeSuffix(".csv").split("-")
        val start = name.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        val end = name.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
        val km = name.getOrNull(2)?.toDoubleOrNull() ?: 0.0
        Route(f, start, end, km)
    }.sortedByDescending { it.startMillis }

    /** The recorded route that overlaps a Peugeot trip the most, if any. */
    fun forTrip(routes: List<Route>, start: Long?, end: Long?): Route? {
        if (start == null) return null
        val e = end ?: (start + 60_000)
        return routes.filter { it.startMillis < e + 5 * 60_000 && it.endMillis > start - 5 * 60_000 }
            .maxByOrNull { minOf(it.endMillis, e) - maxOf(it.startMillis, start) }
    }

    fun delete(r: Route) { r.file.delete() }
    fun deleteAll(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }
}
