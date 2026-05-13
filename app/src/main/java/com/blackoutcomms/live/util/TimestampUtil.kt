package com.blackoutcomms.live.util

import java.text.SimpleDateFormat
import java.util.*

/**
 * Parses and formats timestamps from the Blackout Comms data feed.
 *
 * Input formats observed in the wild:
 *   - Full:    "yyyy-MM-dd HH:mm:ss"  e.g. "2026-05-11 13:55:55"
 *   - Compact: "yyMMddHHmmss"         e.g. "260513155919"  (2026-05-13 15:59:19)
 *
 * Display format used everywhere in the UI: "MM/dd/yyyy HH:mm:ss"
 */
object TimestampUtil {

    private val fmtFull    = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fmtCompact = SimpleDateFormat("yyMMddHHmmss",        Locale.US)
    private val fmtDisplay = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)

    /** Parse any supported feed timestamp string to epoch millis, or null on failure. */
    fun parseTs(ts: String?): Long? {
        if (ts.isNullOrBlank()) return null
        val trimmed = ts.trim()
        return try {
            when {
                trimmed.length == 19 && trimmed[4] == '-' ->
                    synchronized(fmtFull)    { fmtFull.parse(trimmed)?.time }
                trimmed.length == 12 && trimmed.all { it.isDigit() } ->
                    synchronized(fmtCompact) { fmtCompact.parse(trimmed)?.time }
                else ->
                    synchronized(fmtFull)    { fmtFull.parse(trimmed)?.time }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Parse any supported feed timestamp and re-format it as "MM/dd/yyyy HH:mm:ss".
     * Returns the original string unchanged if it cannot be parsed, so the UI
     * always has something to show rather than a blank.
     */
    fun formatTs(ts: String?): String {
        if (ts.isNullOrBlank()) return "—"
        val millis = parseTs(ts) ?: return ts   // unrecognised format — show as-is
        return synchronized(fmtDisplay) { fmtDisplay.format(Date(millis)) }
    }
}
