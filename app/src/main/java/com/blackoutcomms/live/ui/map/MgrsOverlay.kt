package com.blackoutcomms.live.ui.map

import android.content.Context
import android.graphics.*
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.*

/**
 * OSMDroid overlay that draws a Military Grid Reference System (MGRS) grid
 * over the map at appropriate precision for the current zoom level.
 *
 * Grid precision by zoom:
 *   zoom <  8  — GZD labels only (100,000m zones, no grid lines)
 *   zoom  8–10 — 100km grid (GZD boundaries + 100km square labels)
 *   zoom 11–13 — 10km grid
 *   zoom 14–16 — 1km grid
 *   zoom  17+  — 100m grid
 *
 * The grid uses a locally-flat approximation of UTM which is accurate to
 * within a few metres per km — sufficient for tactical tracking purposes.
 * The approximation breaks down near zone boundaries (every 6° of longitude)
 * and at very high latitudes, but is excellent for the mid-latitude field
 * use case this app targets.
 *
 * MGRS labels show the 100km square designator at lower zooms and the
 * numeric easting/northing at higher zooms.
 */
class MgrsOverlay(private val context: Context) : Overlay() {

    /**
     * Height in pixels of the bottom UI bar (status line + filter buttons).
     * Set by MapFragment after the bar is laid out so bottom-edge labels are
     * drawn above it rather than hidden underneath.
     */
    var bottomInsetPx: Int = 0

    // ── Paints ────────────────────────────────────────────────────────────────

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 220, 100)   // amber, more opaque
        strokeWidth = 2.5f                        // thicker lines
        style = Paint.Style.STROKE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 230, 120)    // fully opaque, brighter
        textSize = 42f                            // larger
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(5f, 1f, 1f, Color.BLACK)
    }

    private val labelSmallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 220, 100)
        textSize = 34f                            // larger
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }

    private val gzdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 210, 60)     // fully opaque
        textSize = 52f                            // larger
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(6f, 1f, 1f, Color.BLACK)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zoom = mapView.zoomLevelDouble
        val bb   = mapView.boundingBox ?: return

        val minLat = bb.latSouth
        val maxLat = bb.latNorth
        val minLon = bb.lonWest
        val maxLon = bb.lonEast

        // Determine grid spacing in metres based on zoom
        val gridMetres = when {
            zoom >= 17 -> 100
            zoom >= 14 -> 1_000
            zoom >= 11 -> 10_000
            zoom >= 8  -> 100_000
            else       -> -1    // no grid lines, GZD labels only
        }

        if (gridMetres > 0) {
            drawGrid(canvas, mapView, minLat, maxLat, minLon, maxLon, gridMetres)
        }

        // Always draw GZD labels
        if (zoom >= 5) {
            drawGzdLabels(canvas, mapView, minLat, maxLat, minLon, maxLon)
        }
    }

    // ── Grid drawing ──────────────────────────────────────────────────────────

    private fun drawGrid(
        canvas: Canvas, mapView: MapView,
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        gridMetres: Int
    ) {
        val proj = mapView.projection

        // Centre of visible area — use for UTM zone reference
        val centreLat = (minLat + maxLat) / 2.0
        val centreLon = (minLon + maxLon) / 2.0

        val utmZone = utmZoneNumber(centreLon)

        // Convert bounding box corners to UTM
        val sw = latLonToUtm(minLat, minLon, utmZone)
        val ne = latLonToUtm(maxLat, maxLon, utmZone)

        val eMin = (sw.easting  / gridMetres).toLong() * gridMetres
        val eMax = (ne.easting  / gridMetres).toLong() * gridMetres + gridMetres
        val nMin = (sw.northing / gridMetres).toLong() * gridMetres
        val nMax = (ne.northing / gridMetres).toLong() * gridMetres + gridMetres

        val isNorth = centreLat >= 0

        val canvasW = canvas.width.toFloat()
        val canvasH = canvas.height.toFloat()
        val margin  = 8f   // minimum pixel inset from canvas edge

        // Draw northing lines (east-west lines) with left-edge-anchored labels
        var n = nMin
        while (n <= nMax) {
            val lat = utmToLatLon(sw.easting + (ne.easting - sw.easting) / 2, n.toDouble(), utmZone, isNorth)
            val ptW = proj.toPixels(GeoPoint(lat, minLon), null)
            val ptE = proj.toPixels(GeoPoint(lat, maxLon), null)
            canvas.drawLine(ptW.x.toFloat(), ptW.y.toFloat(), ptE.x.toFloat(), ptE.y.toFloat(), linePaint)

            // Label directly from the loop variable n — exact integer multiple
            // of gridMetres, no rounding or re-projection needed. The round-trip
            // snap approach caused duplicate labels when adjacent northing values
            // both snapped to the same grid boundary after floating-point drift.
            val labelText = mgrsNorthingLabel(n, gridMetres)
            if (labelText.isNotEmpty()) {
                val paint   = if (gridMetres >= 10_000) labelPaint else labelSmallPaint
                val textW   = paint.measureText(labelText)
                val textH   = paint.textSize
                val lineY   = ptW.y.toFloat()

                // Clamp label Y so it stays within canvas vertically
                val labelY  = lineY.coerceIn(textH + margin, canvasH - margin)

                // Left edge label
                canvas.drawText(labelText, margin, labelY, paint)

                // Right edge label — mirror on the right side
                canvas.drawText(labelText, canvasW - textW - margin, labelY, paint)
            }
            n += gridMetres
        }

        // Get the northing at centreLat so we can use the full inverse for easting lines.
        // Using full inverse (easting + centreLat-northing) rather than a fixed-lat
        // approximation eliminates the ~46m systematic error in line positioning.
        val centreNorthing = latLonToUtm(centreLat, centreLon, utmZone).northing

        // Draw easting lines (north-south lines) with top-edge-anchored labels
        var e = eMin
        while (e <= eMax) {
            val (_, lonForE) = utmFullInverse(e.toDouble(), centreNorthing, utmZone, centreLat >= 0)

            if (lonForE in minLon..maxLon) {
                val ptN = proj.toPixels(GeoPoint(maxLat, lonForE), null)
                val ptS = proj.toPixels(GeoPoint(minLat, lonForE), null)
                canvas.drawLine(ptN.x.toFloat(), ptN.y.toFloat(), ptS.x.toFloat(), ptS.y.toFloat(), linePaint)

                // Label directly from the loop variable e — it is an exact integer
                // multiple of gridMetres, so no rounding or re-projection is needed.
                // The round-trip snap approach caused duplicate labels when floating-point
                // drift made two adjacent loop values snap to the same grid boundary.
                val labelText = mgrsEastingLabel(e, gridMetres)
                if (labelText.isNotEmpty()) {
                    val paint  = if (gridMetres >= 10_000) labelPaint else labelSmallPaint
                    val textW  = paint.measureText(labelText)
                    val lineX  = ptN.x.toFloat()

                    // Clamp label X so it stays within canvas horizontally
                    val labelX = lineX.coerceIn(margin, canvasW - textW - margin)

                    // Top edge label
                    val topY = paint.textSize + margin + 4f
                    canvas.drawText(labelText, labelX, topY, paint)

                    // Bottom edge label — sit above the filter bar
                    val botY = canvasH - bottomInsetPx - margin
                    canvas.drawText(labelText, labelX, botY, paint)
                }
            }
            e += gridMetres
        }
    }

    // ── GZD labels ────────────────────────────────────────────────────────────

    private fun drawGzdLabels(
        canvas: Canvas, mapView: MapView,
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double
    ) {
        val proj = mapView.projection

        // GZD zones: 6° longitude bands, 8° latitude bands
        val lonStart = (floor(minLon / 6.0) * 6).toInt()
        val lonEnd   = (ceil(maxLon  / 6.0) * 6).toInt()
        val latStart = (floor(minLat / 8.0) * 8).toInt().coerceAtLeast(-80)
        val latEnd   = (ceil(maxLat  / 8.0) * 8).toInt().coerceAtMost(84)

        val latBands = "CDEFGHJKLMNPQRSTUVWX"

        var lonZ = lonStart
        while (lonZ < lonEnd) {
            var latZ = latStart
            while (latZ < latEnd) {
                val zoneNum  = (lonZ + 180) / 6 + 1
                val bandIdx  = ((latZ + 80) / 8).coerceIn(0, latBands.length - 1)
                val bandChar = latBands[bandIdx]
                val label    = "$zoneNum$bandChar"

                // Centre of this GZD cell
                val centreLat = latZ + 4.0
                val centreLon = lonZ + 3.0

                if (centreLon in minLon..maxLon && centreLat in minLat..maxLat) {
                    val pt = proj.toPixels(GeoPoint(centreLat, centreLon), null)
                    canvas.drawText(label, pt.x.toFloat(), pt.y.toFloat(), gzdPaint)
                }
                latZ += 8
            }
            lonZ += 6
        }
    }

    // ── UTM math ──────────────────────────────────────────────────────────────

    private data class UtmCoord(val easting: Double, val northing: Double, val zone: Int)

    private fun utmZoneNumber(lon: Double): Int = ((lon + 180.0) / 6.0).toInt() + 1

    private fun latLonToUtm(lat: Double, lon: Double, zone: Int): UtmCoord {
        val latR = Math.toRadians(lat)
        val lonR = Math.toRadians(lon)

        val a  = 6378137.0
        val f  = 1.0 / 298.257223563
        val b  = a * (1 - f)
        val e2 = 1 - (b * b) / (a * a)
        val e  = sqrt(e2)
        val e4 = e2 * e2
        val e6 = e2 * e4

        val k0       = 0.9996
        val lon0R    = Math.toRadians((zone - 1) * 6.0 - 180.0 + 3.0)

        val N = a / sqrt(1 - e2 * sin(latR).pow(2))
        val T = tan(latR).pow(2)
        val C = (e2 / (1 - e2)) * cos(latR).pow(2)
        val A = cos(latR) * (lonR - lon0R)

        val M = a * ((1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * latR
                    - (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * latR)
                    + (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * latR)
                    - 35 * e6 / 3072 * sin(6 * latR))

        val easting = k0 * N * (A + (1 - T + C) * A.pow(3) / 6
                    + (5 - 18 * T + T * T + 72 * C - 58 * (e2 / (1 - e2))) * A.pow(5) / 120) + 500_000.0

        var northing = k0 * (M + N * tan(latR) * (A.pow(2) / 2
                    + (5 - T + 9 * C + 4 * C * C) * A.pow(4) / 24
                    + (61 - 58 * T + T * T + 600 * C - 330 * (e2 / (1 - e2))) * A.pow(6) / 720))

        if (lat < 0) northing += 10_000_000.0  // southern hemisphere offset

        return UtmCoord(easting, northing, zone)
    }

    private fun utmToLatLon(easting: Double, northing: Double, zone: Int, isNorth: Boolean): Double {
        val lon0 = ((zone - 1) * 6.0 - 180.0 + 3.0)
        val a    = 6378137.0
        val f    = 1.0 / 298.257223563
        val b    = a * (1 - f)
        val e2   = 1 - (b * b) / (a * a)
        val e1   = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val k0   = 0.9996

        val x = easting - 500_000.0
        val y = if (isNorth) northing else northing - 10_000_000.0

        val M  = y / k0
        val mu = M / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))

        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
                   (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
                   151 * e1.pow(3) / 96 * sin(6 * mu)

        val N1 = a / sqrt(1 - e2 * sin(phi1).pow(2))
        val T1 = tan(phi1).pow(2)
        val C1 = e2 / (1 - e2) * cos(phi1).pow(2)
        val R1 = a * (1 - e2) / (1 - e2 * sin(phi1).pow(2)).pow(1.5)
        val D  = x / (N1 * k0)

        val lat = phi1 - (N1 * tan(phi1) / R1) *
                  (D * D / 2 - (5 + 3 * T1 + 10 * C1 - 4 * C1 * C1 - 9 * e2 / (1 - e2)) * D.pow(4) / 24 +
                  (61 + 90 * T1 + 298 * C1 + 45 * T1 * T1 - 252 * e2 / (1 - e2) - 3 * C1 * C1) * D.pow(6) / 720)

        return Math.toDegrees(lat)
    }

    /**
     * Full inverse UTM projection: (easting, northing, zone) → (lat°, lon°).
     * Returns a Pair(latDeg, lonDeg). Uses the complete inverse formula with
     * no fixed-latitude approximation, giving sub-millimetre round-trip accuracy.
     * This replaces the previous utmToLon(easting, fixedLat) which had a
     * systematic ~46m westward error because it used a fixed reference latitude
     * rather than computing the true latitude from the northing.
     */
    private fun utmFullInverse(easting: Double, northing: Double, zone: Int, isNorth: Boolean): Pair<Double, Double> {
        val a   = 6378137.0
        val f   = 1.0 / 298.257223563
        val b   = a * (1 - f)
        val e2  = 1 - (b * b) / (a * a)
        val e1  = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val k0  = 0.9996
        val lon0Deg = (zone - 1) * 6.0 - 180.0 + 3.0

        val x   = easting - 500_000.0
        val y   = if (isNorth) northing else northing - 10_000_000.0

        val M   = y / k0
        val mu  = M / (a * (1 - e2 / 4 - 3 * e2.pow(2) / 64 - 5 * e2.pow(3) / 256))

        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1.pow(2) / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            151 * e1.pow(3) / 96 * sin(6 * mu)

        val N1  = a / sqrt(1 - e2 * sin(phi1).pow(2))
        val T1  = tan(phi1).pow(2)
        val C1  = e2 / (1 - e2) * cos(phi1).pow(2)
        val R1  = a * (1 - e2) / (1 - e2 * sin(phi1).pow(2)).pow(1.5)
        val D   = x / (N1 * k0)

        val latRad = phi1 - (N1 * tan(phi1) / R1) *
            (D.pow(2) / 2 -
            (5 + 3 * T1 + 10 * C1 - 4 * C1.pow(2) - 9 * e2 / (1 - e2)) * D.pow(4) / 24 +
            (61 + 90 * T1 + 298 * C1 + 45 * T1.pow(2) - 252 * e2 / (1 - e2) - 3 * C1.pow(2)) * D.pow(6) / 720)

        val lonRad = Math.toRadians(lon0Deg) +
            (D - (1 + 2 * T1 + C1) * D.pow(3) / 6 +
            (5 - 2 * C1 + 28 * T1 - 3 * C1.pow(2) + 8 * e2 / (1 - e2) + 24 * T1.pow(2)) * D.pow(5) / 120) /
            cos(phi1)

        return Pair(Math.toDegrees(latRad), Math.toDegrees(lonRad))
    }

    // ── MGRS label formatting ─────────────────────────────────────────────────

    /**
     * Format a northing value as an MGRS label for the given grid spacing.
     * MGRS northing labels show the last 2 or 3 significant digits of the
     * 100km-square-relative value, which is northing % 100_000.
     * e.g. northing=4,502,200, gridMetres=100 → 4502200 % 100000 = 2200 → "022"
     */
    private fun mgrsNorthingLabel(northing: Long, gridMetres: Int): String {
        val within100km = northing % 100_000L
        return when (gridMetres) {
            100_000 -> ""   // 100km — use GZD label instead
            10_000  -> "%d".format(within100km  / 10_000L)
            1_000   -> "%02d".format(within100km / 1_000L)
            100     -> "%03d".format(within100km / 100L)
            else    -> northing.toString()
        }
    }

    /**
     * Format an easting value as an MGRS label for the given grid spacing.
     * MGRS easting labels show the last 2 or 3 significant digits of the
     * 100km-square-relative value, which is (easting - 100_000) % 100_000
     * (UTM easting starts at 100,000m at zone edge, so subtract base offset).
     * e.g. easting=671,600, gridMetres=100 → (671600-100000)%100000=71600 → "016"
     */
    private fun mgrsEastingLabel(easting: Long, gridMetres: Int): String {
        // UTM easting false origin is 500,000m at central meridian; easting
        // within the 100km square is derived by taking easting % 100_000
        // (values run from ~100,000 to ~999,999 in a zone)
        val within100km = easting % 100_000L
        return when (gridMetres) {
            100_000 -> ""
            10_000  -> "%d".format(within100km  / 10_000L)
            1_000   -> "%02d".format(within100km / 1_000L)
            100     -> "%03d".format(within100km / 100L)
            else    -> easting.toString()
        }
    }
}
