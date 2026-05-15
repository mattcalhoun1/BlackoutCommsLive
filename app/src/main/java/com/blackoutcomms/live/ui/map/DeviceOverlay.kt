package com.blackoutcomms.live.ui.map

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.model.GraphPayload
import com.blackoutcomms.live.model.NeighborType
import com.blackoutcomms.live.util.IconResolver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * Custom overlay that draws all device markers, neighbour rings, heading arrows,
 * and mesh graph lines.
 */
class DeviceOverlay(
    private val context: Context,
    private val selfId: String,
    private val onDeviceTapped: (DeviceState) -> Unit
) : Overlay() {

    var deviceStates: Map<String, DeviceState> = emptyMap()
    var graphData: GraphPayload? = null
    var showMeshGraph: Boolean = false

    private val iconCache = mutableMapOf<Int, Bitmap>()
    private val ICON_SIZE_DP  = 36f
    private val RING_RADIUS_DP = 48f
    private val ARROW_LENGTH_DP = 28f   // shaft length beyond icon edge
    private val ARROW_HEAD_DP  = 9f    // arrowhead wing size
    private val SPEED_THRESHOLD = 2.0  // m/s — only draw arrow above this

    // ── Paints ────────────────────────────────────────────────────────────────

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CDD4C0")
        textSize = 28f
        typeface = Typeface.MONOSPACE
        setShadowLayer(4f, 0f, 1f, Color.BLACK)
    }

    private val directRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 90, 160, 80)
        style = Paint.Style.FILL
    }

    private val indirectRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 180, 160, 50)
        style = Paint.Style.FILL
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C8A84B")   // amber
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    // Reusable path for the arrowhead
    private val arrowPath = Path()

    // ── Draw ──────────────────────────────────────────────────────────────────

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val projection = mapView.projection
        val density    = context.resources.displayMetrics.density
        val iconPx     = (ICON_SIZE_DP * density).toInt()
        val ringPx     = RING_RADIUS_DP * density

        if (showMeshGraph) drawMeshGraph(canvas, mapView)

        for ((_, state) in deviceStates) {
            val lat = state.lat ?: continue
            val lon = state.lon ?: continue
            val pt  = projection.toPixels(GeoPoint(lat, lon), null)
            val cx  = pt.x.toFloat()
            val cy  = pt.y.toFloat()

            // Neighbour ring
            when (state.neighborType) {
                NeighborType.DIRECT   -> canvas.drawCircle(cx, cy, ringPx, directRingPaint)
                NeighborType.INDIRECT -> canvas.drawCircle(cx, cy, ringPx, indirectRingPaint)
                NeighborType.NONE     -> {}
            }

            // Heading arrow — drawn before the icon so the icon sits on top of the shaft
            val speed   = state.speed ?: 0.0
            val heading = state.head
            if (speed > SPEED_THRESHOLD && heading != null) {
                drawHeadingArrow(canvas, cx, cy, heading.toFloat(), iconPx / 2f, density)
            }

            // Device icon
            val bmp = getBitmap(IconResolver.deviceIcon(state.device.icon), iconPx)
            canvas.drawBitmap(bmp, cx - iconPx / 2f, cy - iconPx / 2f, null)

            // Nickname label — monospace, above the marker
            val label = state.device.displayName
            val textW = textPaint.measureText(label)
            canvas.drawText(label, cx - textW / 2f, cy - iconPx / 2f - 10f, textPaint)
        }
    }

    /**
     * Draws an amber directional arrow for [headingDeg] in firmware convention:
     *   0 = west, 90 = north, 180 = east, 270 = south
     *
     * Conversion to screen vector (Android Y-axis increases downward):
     *   compassHeading = firmwareHeading - 90  (aligns firmware's 90=north with compass 0=north)
     *   dx = sin(compassHeading),  dy = -cos(compassHeading)
     *
     * Verified:
     *   firmware  0 (west)  → compass -90° → dx=-1, dy=0  ✓
     *   firmware 90 (north) → compass   0° → dx=0,  dy=-1 ✓ (up on screen)
     *   firmware 180 (east) → compass  90° → dx=+1, dy=0  ✓
     *   firmware 270 (south)→ compass 180° → dx=0,  dy=+1 ✓ (down on screen)
     */
    private fun drawHeadingArrow(
        canvas: Canvas, cx: Float, cy: Float,
        headingDeg: Float, iconRadius: Float, density: Float
    ) {
        val arrowLen  = ARROW_LENGTH_DP * density
        val headSize  = ARROW_HEAD_DP  * density

        // Convert firmware heading to compass heading, then to screen vector
        val compassDeg = headingDeg - 90.0
        val rad  = Math.toRadians(compassDeg)
        val dx   =  Math.sin(rad).toFloat()
        val dy   = -Math.cos(rad).toFloat()

        // Shaft start (at icon edge) and tip
        val startX = cx + dx * iconRadius
        val startY = cy + dy * iconRadius
        val tipX   = cx + dx * (iconRadius + arrowLen)
        val tipY   = cy + dy * (iconRadius + arrowLen)

        // Draw shaft
        canvas.drawLine(startX, startY, tipX, tipY, arrowPaint)

        // Arrowhead: two wing points perpendicular to travel direction
        val perpX = -dy   // perpendicular unit vector (rotate 90°)
        val perpY =  dx

        arrowPath.reset()
        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(
            tipX - dx * headSize + perpX * headSize / 2,
            tipY - dy * headSize + perpY * headSize / 2
        )
        arrowPath.lineTo(
            tipX - dx * headSize - perpX * headSize / 2,
            tipY - dy * headSize - perpY * headSize / 2
        )
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    // ── Mesh graph ────────────────────────────────────────────────────────────

    private fun drawMeshGraph(canvas: Canvas, mapView: MapView) {
        val graph = graphData?.graph ?: return
        val projection = mapView.projection
        val processed  = mutableSetOf<String>()
        val visibleIds = deviceStates.keys.toSet()

        for ((fromAddrStr, relations) in graph) {
            for ((toAddrStr, rel) in relations) {
                val fromState = ClusterRepository.deviceByAddress(fromAddrStr) ?: continue
                val toState   = ClusterRepository.deviceByAddress(toAddrStr)   ?: continue

                if (fromState.device.id !in visibleIds) continue
                if (toState.device.id   !in visibleIds) continue

                val fromLat = fromState.lat ?: continue
                val fromLon = fromState.lon ?: continue
                val toLat   = toState.lat   ?: continue
                val toLon   = toState.lon   ?: continue

                val color = IconResolver.graphLineColor(rel.direct) ?: continue

                val key    = listOf(fromAddrStr, toAddrStr).sorted().joinToString("-")
                val fromPt = projection.toPixels(GeoPoint(fromLat, fromLon), null)
                val toPt   = projection.toPixels(GeoPoint(toLat, toLon), null)
                val offset = if (key in processed) 4f else -4f
                processed.add(key)

                linePaint.color = color
                canvas.drawLine(
                    fromPt.x + offset, fromPt.y + offset,
                    toPt.x + offset, toPt.y + offset,
                    linePaint
                )
            }
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val density    = context.resources.displayMetrics.density
        val hitRadius  = ICON_SIZE_DP * density

        for ((_, state) in deviceStates) {
            val lat = state.lat ?: continue
            val lon = state.lon ?: continue
            val pt  = projection.toPixels(GeoPoint(lat, lon), null)
            val dx  = e.x - pt.x
            val dy  = e.y - pt.y
            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                onDeviceTapped(state)
                return true
            }
        }
        return false
    }

    // ── Bitmap cache ──────────────────────────────────────────────────────────

    private fun getBitmap(resId: Int, sizePx: Int): Bitmap {
        return iconCache.getOrPut(resId * 10000 + sizePx) {
            val drawable = ContextCompat.getDrawable(context, resId)!!
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
                val c = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(c)
            }
        }
    }
}
