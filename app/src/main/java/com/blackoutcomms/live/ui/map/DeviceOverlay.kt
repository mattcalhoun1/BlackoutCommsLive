package com.blackoutcomms.live.ui.map

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.model.NeighborType
import com.blackoutcomms.live.util.IconResolver
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.infowindow.BasicInfoWindow

/**
 * Custom overlay that draws all device markers, neighbour rings, and mesh graph lines.
 */
class DeviceOverlay(
    private val context: Context,
    private val selfId: String,
    private val onDeviceTapped: (DeviceState) -> Unit
) : Overlay() {

    var deviceStates: Map<String, DeviceState> = emptyMap()
        set(value) { field = value }

    var graphData: com.blackoutcomms.live.model.GraphPayload? = null
    var showMeshGraph: Boolean = false

    private val iconCache = mutableMapOf<Int, Bitmap>()
    private val ICON_SIZE_DP = 36f
    private val RING_RADIUS_DP = 48f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val directRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 200, 0)
        style = Paint.Style.FILL
    }

    private val indirectRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 220, 0)
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val projection = mapView.projection
        val density = context.resources.displayMetrics.density
        val iconPx = (ICON_SIZE_DP * density).toInt()
        val ringPx = RING_RADIUS_DP * density

        // Draw mesh graph lines first (bottom layer)
        if (showMeshGraph) drawMeshGraph(canvas, mapView)

        // Draw devices
        for ((id, state) in deviceStates) {
            val lat = state.lat ?: continue
            val lon = state.lon ?: continue

            val gp = GeoPoint(lat, lon)
            val screenPt = projection.toPixels(gp, null)

            // Neighbour ring
            when (state.neighborType) {
                NeighborType.DIRECT   -> canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), ringPx, directRingPaint)
                NeighborType.INDIRECT -> canvas.drawCircle(screenPt.x.toFloat(), screenPt.y.toFloat(), ringPx, indirectRingPaint)
                NeighborType.NONE     -> {}
            }

            // Device icon
            val iconRes = IconResolver.deviceIcon(state.device.icon)
            val bmp = getBitmap(iconRes, iconPx)
            canvas.drawBitmap(bmp, (screenPt.x - iconPx / 2).toFloat(), (screenPt.y - iconPx / 2).toFloat(), null)

            // Nickname label above marker
            val label = state.device.displayName
            val textW = textPaint.measureText(label)
            canvas.drawText(label, screenPt.x - textW / 2, screenPt.y - iconPx / 2f - 8f, textPaint)
        }
    }

    private fun drawMeshGraph(canvas: Canvas, mapView: MapView) {
        val graph = graphData?.graph ?: return
        val projection = mapView.projection
        val processed = mutableSetOf<String>()

        for ((fromAddrStr, relations) in graph) {
            for ((toAddrStr, rel) in relations) {
                val key = listOf(fromAddrStr, toAddrStr).sorted().joinToString("-")
                val fromState = ClusterRepository.deviceByAddress(fromAddrStr) ?: continue
                val toState   = ClusterRepository.deviceByAddress(toAddrStr)   ?: continue

                val fromLat = fromState.lat ?: continue
                val fromLon = fromState.lon ?: continue
                val toLat   = toState.lat   ?: continue
                val toLon   = toState.lon   ?: continue

                val color = IconResolver.graphLineColor(rel.direct) ?: continue

                val fromPt = projection.toPixels(GeoPoint(fromLat, fromLon), null)
                val toPt   = projection.toPixels(GeoPoint(toLat, toLon), null)

                // If we've already drawn the reverse direction, offset the line slightly
                val offset = if (key in processed) 4f else -4f
                processed.add(key)

                linePaint.color = color
                canvas.drawLine(
                    fromPt.x.toFloat() + offset, fromPt.y.toFloat() + offset,
                    toPt.x.toFloat() + offset, toPt.y.toFloat() + offset,
                    linePaint
                )
            }
        }
    }

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        val projection = mapView.projection
        val density = context.resources.displayMetrics.density
        val hitRadius = ICON_SIZE_DP * density

        for ((_, state) in deviceStates) {
            val lat = state.lat ?: continue
            val lon = state.lon ?: continue
            val pt = projection.toPixels(GeoPoint(lat, lon), null)
            val dx = e.x - pt.x
            val dy = e.y - pt.y
            if (dx * dx + dy * dy <= hitRadius * hitRadius) {
                onDeviceTapped(state)
                return true
            }
        }
        return false
    }

    private fun getBitmap(resId: Int, sizePx: Int): Bitmap {
        return iconCache.getOrPut(resId * 10000 + sizePx) {
            val drawable = ContextCompat.getDrawable(context, resId)!!
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, sizePx, sizePx)
                drawable.draw(canvas)
            }
        }
    }
}
