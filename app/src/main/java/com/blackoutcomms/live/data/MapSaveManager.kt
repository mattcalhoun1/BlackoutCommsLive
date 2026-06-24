package com.blackoutcomms.live.data

import android.content.Context
import android.util.Log
import com.blackoutcomms.live.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Saves and restores the full ClusterRepository state to/from a JSON file in
 * the app's private files directory. The save file is named "cluster_snapshot.json".
 *
 * Everything needed to reconstruct what the user sees on the map is included:
 *  - selfDevice
 *  - deviceMap (all DeviceState entries, including locations, battery, relay etc.)
 *  - graphMap  (accumulated mesh relationship data)
 *  - neighbors (last received neighbors payload)
 *  - messages  (received messages)
 *  - trafficEntries
 *  - pingEntries
 *
 * locationUpdates is a transient pulse — not saved.
 */
object MapSaveManager {
    private var lastSaveMs : Long = 0

    private const val TAG           = "MapSaveManager"
    private const val SNAPSHOT_FILE = "cluster_snapshot.json"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // ── Snapshot data class ───────────────────────────────────────────────────

    data class ClusterSnapshot(
        val savedAtMs: Long,
        val self: SelfDevice?,
        val devices: Map<String, DeviceState>,
        val graph: Map<String, Map<String, GraphRelationship>>,
        val neighbors: Neighbors?,
        val liveMessages: Map<String, Message> = emptyMap(),
        val savedMessages: Map<String, Message> = emptyMap(),
        val trafficEntries: List<TrafficEntry>,
        val pingEntries: List<PingEntry>
    )

    // ── Save ──────────────────────────────────────────────────────────────────

    fun save(context: Context): Boolean {
        if (System.currentTimeMillis() - lastSaveMs < 5000) {
            return true
        }
        return try {
            val snapshot = ClusterSnapshot(
                savedAtMs      = System.currentTimeMillis(),
                self           = ClusterRepository.selfDevice.value,
                devices        = ClusterRepository.deviceStates.value ?: emptyMap(),
                graph          = ClusterRepository.graphData.value?.graph ?: emptyMap(),
                neighbors      = ClusterRepository.neighbors.value,
                liveMessages   = ClusterRepository.liveMessageMap.toMap(),
                savedMessages  = ClusterRepository.savedMessageMap.toMap(),
                trafficEntries = ClusterRepository.trafficEntries.value ?: emptyList(),
                pingEntries    = ClusterRepository.pingEntries.value ?: emptyList()
            )
            val json = gson.toJson(snapshot)
            File(context.filesDir, SNAPSHOT_FILE).writeText(json)
            lastSaveMs = System.currentTimeMillis() // remember just saved
            Log.i(TAG, "Snapshot saved (${json.length} bytes, " +
                "${snapshot.devices.size} devices, ${snapshot.savedMessages.size} messages)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Save failed", e)
            false
        }
    }

    // ── Load + restore ────────────────────────────────────────────────────────

    fun restore(context: Context): Boolean {
        return try {
            val file = File(context.filesDir, SNAPSHOT_FILE)
            if (!file.exists()) {
                Log.w(TAG, "No snapshot file found")
                return false
            }
            val snapshot = gson.fromJson(file.readText(), ClusterSnapshot::class.java)
            ClusterRepository.restoreFromSnapshot(snapshot)
            Log.i(TAG, "Snapshot restored from ${snapshot.savedAtMs} " +
                "(${snapshot.devices.size} devices)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            false
        }
    }

    // ── Check ─────────────────────────────────────────────────────────────────

    /** Returns true if a saved snapshot exists. Used to enable/disable the menu item. */
    fun hasSavedSnapshot(context: Context): Boolean =
        File(context.filesDir, SNAPSHOT_FILE).exists()

    /** Returns the save timestamp in ms, or null if no snapshot exists. */
    fun savedAt(context: Context): Long? {
        val file = File(context.filesDir, SNAPSHOT_FILE)
        if (!file.exists()) return null
        return try {
            gson.fromJson(file.readText(), ClusterSnapshot::class.java).savedAtMs
        } catch (_: Exception) { null }
    }
}
