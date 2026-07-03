package com.blackoutcomms.live.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.ContextCompat
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.model.DeviceState
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class RfRangeEnvelopeOverlay(
    private val context: Context,
    private val selfIdProvider: () -> String?   // or pass selfDevice LiveData reference
) : Overlay() {

    private val envelopePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rf_envelope_fill) // define in colors.xml: ~#40FF0000 (semi-transparent red)
        style = Paint.Style.FILL
        alpha = 80  // mostly transparent; tweak as needed
    }

    private val envelopeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.rf_envelope_stroke) // e.g. #80FF0000
        style = Paint.Style.STROKE
        strokeWidth = 6f
        alpha = 180
    }

    private val path = Path()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val selfId = selfIdProvider() ?: return
        val selfState = ClusterRepository.deviceStates.value?.get(selfId) ?: return
        val selfLat = selfState.lat ?: return
        val selfLon = selfState.lon ?: return

        val directIds = ClusterRepository.activeDirectNeighborIds
        if (directIds.isEmpty()) return  // nothing to enclose

        val projection = mapView.projection
        val points = mutableListOf<GeoPoint>()

        // Always include self
        points.add(GeoPoint(selfLat, selfLon))

        // Add active direct neighbors
        ClusterRepository.deviceStates.value?.forEach { (id, state) ->
            if (id in directIds && state.lat != null && state.lon != null) {
                points.add(GeoPoint(state.lat!!, state.lon!!))
            }
        }

        if (points.size <= 1) return

        // Compute convex hull for a tight polygon (or use circle for simplicity)
        // For starters, a simple minimum bounding circle or convex hull works well.
        // OSMDroid Polygon overlay exists but since we're in custom draw, build Path.

        // Option A: Quick & good enough — Minimum Bounding Circle (approx)
        val center = calculateCentroid(points)  // or use self position
        val radiusMeters = points.maxOf { p ->
            center.distanceToAsDouble(p)
        } * 1.15  // small buffer

        val screenCenter = projection.toPixels(center, null)
        val screenRadius = projection.metersToEquatorPixels(radiusMeters.toFloat())  // approx for small areas

        path.reset()
        path.addCircle(screenCenter.x.toFloat(), screenCenter.y.toFloat(), screenRadius, Path.Direction.CW)

        canvas.drawPath(path, envelopePaint)
        canvas.drawPath(path, envelopeStrokePaint)

        // Option B (tighter): Convex hull polygon (more accurate for spread-out neighbors)
        // Implement a simple Graham scan or use a library; for few points (<20) it's fast.
    }

    private fun calculateCentroid(pts: List<GeoPoint>): GeoPoint {
        var latSum = 0.0
        var lonSum = 0.0
        pts.forEach { latSum += it.latitude; lonSum += it.longitude }
        return GeoPoint(latSum / pts.size, lonSum / pts.size)
    }
}