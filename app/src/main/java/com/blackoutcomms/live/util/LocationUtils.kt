package com.blackoutcomms.live.util

import kotlin.math.*

object LocationUtils {

    /**
     * Returns distance in **meters** between two lat/lon points using Haversine formula.
     * Earth radius ≈ 6371000 m.
     */
    fun distanceMeters(
        lat1: Double?, lon1: Double?,
        lat2: Double?, lon2: Double?
    ): Double {
        val R = 6371000.0  // Earth radius in meters

        if (lat1 != null && lon1 != null && lat2 != null && lon2 != null) {

            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)

            val a = sin(dLat / 2) * sin(dLat / 2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLon / 2) * sin(dLon / 2)

            val c = 2 * atan2(sqrt(a), sqrt(1 - a))

            return R * c
        }
        return 0.0
    }
}