package com.blackoutcomms.live.util

/**
 * Tracks the connected device's clock so freshness checks follow the mesh,
 * not the phone's time zone or DST.
 *
 * Device timestamps are timezone-naive wall-clock strings. [TimestampUtil]
 * parses them in UTC so the digits are preserved. Each time a self or
 * location payload arrives we record that parsed time plus the phone's
 * millis at receipt. [nowMs] then advances with the phone's elapsed time
 * from that anchor, giving an estimate of "what time is it on the device"
 * between messages.
 *
 * Before any device timestamp has been seen, falls back to phone time.
 */
object DeviceClock {

    @Volatile private var deviceAnchorMs: Long? = null
    @Volatile private var phoneAnchorMs: Long = 0L

    /** Record a device timestamp string as the latest known device time. */
    fun observe(ts: String?) {
        val parsed = TimestampUtil.parseTs(ts) ?: return
        synchronized(this) {
            val current = deviceAnchorMs
            // Only move the anchor forward (or set it the first time).
            // A slightly older location in the same batch should not rewind "now".
            if (current == null || parsed >= current) {
                deviceAnchorMs = parsed
                phoneAnchorMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Estimated current time on the connected device, in the same epoch
     * space as [TimestampUtil.parseTs]. Falls back to phone time until the
     * first device timestamp arrives.
     */
    fun nowMs(): Long {
        val anchor = deviceAnchorMs ?: return System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - phoneAnchorMs
        return anchor + elapsed.coerceAtLeast(0L)
    }

    /** True once at least one device timestamp has been observed. */
    fun hasDeviceTime(): Boolean = deviceAnchorMs != null

    /** Clear on disconnect / full reset so a new device starts clean. */
    fun reset() {
        synchronized(this) {
            deviceAnchorMs = null
            phoneAnchorMs = 0L
        }
    }
}
