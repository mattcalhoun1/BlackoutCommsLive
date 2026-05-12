package com.blackoutcomms.live.util

import com.blackoutcomms.live.R
import com.blackoutcomms.live.model.Message

/**
 * Maps string identifiers from the data feed to drawable resource IDs.
 */
object IconResolver {

    fun deviceIcon(iconName: String?): Int = when (iconName?.lowercase()) {
        "root"      -> R.drawable.device_root
        "node"      -> R.drawable.base
        "relay"     -> R.drawable.switch_icon
        "proximity" -> R.drawable.motion_sensor
        "thermal"   -> R.drawable.thermal
        else        -> R.drawable.device_nonroot  // "user" or fallback
    }

    fun batteryIcon(level: String?): Int? = when (level?.lowercase()) {
        "high"    -> R.drawable.battery_high
        "medium"  -> R.drawable.battery_medium
        "low"     -> R.drawable.battery_low
        "unknown" -> R.drawable.battery_unknown
        else      -> null
    }

    fun relayIcon(state: String?): Int? = when (state?.lowercase()) {
        "on"  -> R.drawable.relay_on_22
        "off" -> R.drawable.relay_off_22
        else  -> null
    }

    fun movementIcon(speedMps: Double?): Int? {
        if (speedMps == null) return null
        return when {
            speedMps > 4.0  -> R.drawable.moving_fast   // > ~15 km/h
            speedMps > 0.5  -> R.drawable.moving_med
            speedMps > 0.1  -> R.drawable.moving_slow
            else            -> null                      // none
        }
    }

    fun messageTypeIcon(message: Message, selfId: String): Int {
        return if (message.recipient == "[all devices]") {
            if (message.sender == selfId)
                R.drawable.broadcast_sent_round
            else
                R.drawable.broadcast_received_round
        } else {
            if (message.sender == selfId)
                R.drawable.dm_sent_round
            else
                R.drawable.dm_received_round
        }
    }

    fun messageStatusIcon(message: Message): Int {
        return if (message.recipient == "[all devices]") {
            if (message.status == "confirmed")
                R.drawable.mesh_accepted
            else
                R.drawable.mesh_queued
        } else if (message.delivery == "direct") {
            when (message.status) {
                "confirmed" -> R.drawable.dm_confirmed
                "delivered" -> R.drawable.dm_delivered
                else        -> R.drawable.mesh_queued
            }
        } else { // mesh
            when (message.status) {
                "confirmed" -> R.drawable.mesh_confirmed
                "delivered" -> R.drawable.mesh_delivered
                else        -> R.drawable.mesh_queued
            }
        }
    }

    /** Color int for a graph link strength value */
    fun graphLineColor(strength: Int): Int? = when {
        strength in 80..100 -> 0xCC4CAF50.toInt()   // green, semi-transparent
        strength in 50..79  -> 0xCC2196F3.toInt()   // blue
        strength in 30..49  -> 0xCCFFEB3B.toInt()   // yellow
        strength in 6..29   -> 0xCCFF9800.toInt()   // orange
        //strength == 5       -> 0xCC9E9E9E.toInt()   // gray
        else                -> null                  // no line
    }
}
