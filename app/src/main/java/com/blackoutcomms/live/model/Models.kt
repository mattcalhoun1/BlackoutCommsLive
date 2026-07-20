package com.blackoutcomms.live.model

import com.google.gson.annotations.SerializedName

// ── Self ──────────────────────────────────────────────────────────────────────

data class SelfPayload(
    @SerializedName("self") val self: SelfDevice
)

data class SelfDevice(
    val id: String,
    val address: String,
    val icon: String,
    val batteryLevel: String?,
    val name: String,
    val lat: String,
    val lon: String,
    val alt: String?,
    val head: String?,
    val speed: String?,
    val ts: String,
    val relayState: String?,
    val motion: String?,
    val temperature: String?,
    val cluster: String? = null
)

// ── Devices ───────────────────────────────────────────────────────────────────

data class DevicesPayload(
    @SerializedName("devices") val devices: List<ClusterDevice>
)

data class ClusterDevice(
    val id: String,
    val name: String,
    val nickname: String?,
    val address: String,
    val critical: Boolean = false,
    val icon: String
) {
    val displayName: String get() = nickname?.takeIf { it.isNotBlank() } ?: name
}

// ── Neighbors ────────────────────────────────────────────────────────────────

data class NeighborsPayload(
    @SerializedName("neighbors") val neighbors: Neighbors
)

data class Neighbors(
    val direct: List<NeighborEntry> = emptyList(),
    val indirect: List<NeighborEntry> = emptyList()
)

// Note: The spec shows "indirect" as an object with duplicate keys which is non-standard JSON.
// We treat it as a list (same structure as "direct") for correct parsing.
// The custom deserializer in NeighborsDeserializer handles both array and object forms.

data class NeighborEntry(
    val id: String,
    val ts: String?,
    val rssi: Int?,
    val battery: String?,
    val temperature: Double?,
    val motion: String?,
    val relayState: String?
)

/**
 * Represents the current radio/connection state of the connected Blackout Comms device.
 * Upserted from "conn" payloads sent over BLE. Only the most recent instance is kept.
 */
data class ClusterConnection(
    val net: String,           // e.g. "BC"
    val cfg: String,           // e.g. "LoRa@913.3 S+" — parse frequency from this
    val q: Int,                // quality / signal / link metric?
    val stlth: String,         // stealth mode "n" / "y" ?
    val la: Int                // (location age)
) {
    /** Extract frequency in MHz from cfg string (e.g. "913.3" from "LoRa@913.3 S+") */
    val frequencyMhz: String?
        get() {
            val match = Regex("""@(\d+\.?\d*)""").find(cfg)
            return match?.groupValues?.get(1)
        }

    enum class StealthLevel { NONE, LOW, MEDIUM, HIGH }
    val stealthLevel: StealthLevel
        get() = when (stlth.lowercase()) {
            "p" -> StealthLevel.LOW
            "u" -> StealthLevel.MEDIUM
            "s" -> StealthLevel.HIGH
            else -> StealthLevel.NONE   // 'n' or unknown
        }

    val isStealthActive: Boolean get() = stealthLevel != StealthLevel.NONE
    val qualityLevel: String get() = when (q) {
        in 0..3 -> "poor"
        in 4..6 -> "fair"
        else -> "good"
    }

    enum class GpsStatus { GREEN, YELLOW, GRAY }
    val gpsStatus: GpsStatus
        get() = when {
            la > 6000 -> GpsStatus.GRAY
            la <= 120 -> GpsStatus.GREEN   // fresh fix (≤ 2 min)
            else -> GpsStatus.YELLOW
        }
}

// ── Location ─────────────────────────────────────────────────────────────────

data class LocationPayload(
    @SerializedName("location") val location: List<LocationEntry>
)

data class LocationEntry(
    val id: String,
    val lat: String,
    val lon: String,
    val head: String?,
    val speed: String?,
    val ts: String
)

// ── Graph ────────────────────────────────────────────────────────────────────

data class GraphPayload(
    @SerializedName("graph") val graph: Map<String, Map<String, GraphRelationship>>
)

data class GraphRelationship(
    val direct: Int,
    val indirect: Int,
    val age: Long
)

// ── Message ──────────────────────────────────────────────────────────────────

data class MessagePayload(
    @SerializedName("message") val message: Message? = null,
    // Some payloads may be the message object directly
    val sender: String? = null,
    val recipient: String? = null,
    val delivery: String? = null,
    val status: String? = null,
    val ts: String? = null,
    val title: String? = null,
    val text: String? = null
) {
    fun toMessage(): Message? {
        if (message != null) return message
        if (sender != null && recipient != null) {
            return Message(
                sender = sender,
                recipient = recipient,
                delivery = delivery ?: "direct",
                status = status ?: "queued",
                ts = ts ?: "",
                title = title ?: "",
                text = text ?: ""
            )
        }
        return null
    }
}

data class Message(
    val id: String? = null,           // optional unique message ID from firmware
    val sender: String,
    val recipient: String,
    val delivery: String,             // "direct" or "mesh"
    val status: String,               // "delivered", "confirmed", "queued"
    val ts: String,
    val title: String,
    val text: String,
    val isNew: Boolean? = null,       // null/absent or true = new; false = saved/historical
    val priority: String? = null      // "Low", "Normal", "Medium", "High", "Critical"
)

// ── Composite device state (runtime) ─────────────────────────────────────────

data class DeviceState(
    val device: ClusterDevice,
    var lat: Double? = null,
    var lon: Double? = null,
    var head: Double? = null,
    var speed: Double? = null,
    var locationTs: String? = null,
    var battery: String? = null,
    var temperature: Double? = null,
    var motion: String? = null,
    var relayState: String? = null,
    var neighborType: NeighborType = NeighborType.NONE
)

enum class NeighborType { NONE, DIRECT, INDIRECT }

// ── Message Status Update ────────────────────────────────────────────────────

data class MessageStatus(
    val id: String,
    val sender: String,
    val recipient: String,
    val status: String
)

// ── Traffic ───────────────────────────────────────────────────────────────────

data class TrafficPayload(
    val bytesIn: Long,
    val bytesOut: Long,
    val packetsIn: Long,
    val packetsOut: Long,
    val unknownPackets: Long = 0
)

/** A traffic sample stamped with the wall-clock time it was received. */
data class TrafficEntry(
    val receivedMs: Long,
    val bytesIn: Long,
    val bytesOut: Long,
    val packetsIn: Long,
    val packetsOut: Long,
    val unknownPackets: Long = 0   // optional — absent in older firmware
)

// ── Ping (neighbor sighting) ──────────────────────────────────────────────────

/** One neighbor sighting extracted from a neighbors payload for the ping list. */
data class PingEntry(
    val receivedMs: Long,
    val deviceId: String,
    val rssi: Int?,
    val isDirect: Boolean,
    val distance: Float?
)
