package com.blackoutcomms.live.data

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.blackoutcomms.live.model.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.collections.get

/**
 * Singleton repository that holds all cluster state parsed from the data feed.
 * All UI observes LiveData from here.
 */
object ClusterRepository {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Neighbors::class.java, NeighborsDeserializer())
        .create()

    // ── Observable state ─────────────────────────────────────────────────────

    private val _selfDevice = MutableLiveData<SelfDevice?>()
    val selfDevice: LiveData<SelfDevice?> = _selfDevice

    private val _deviceStates = MutableLiveData<Map<String, DeviceState>>(emptyMap())
    val deviceStates: LiveData<Map<String, DeviceState>> = _deviceStates

    private val _neighbors = MutableLiveData<Neighbors?>()
    val neighbors: LiveData<Neighbors?> = _neighbors

    private val _graphData = MutableLiveData<GraphPayload?>()
    val graphData: LiveData<GraphPayload?> = _graphData

    // Internal mutable graph map — upserted incrementally as partial messages arrive
    private val graphMap = mutableMapOf<String, MutableMap<String, GraphRelationship>>()

    private val _messages = MutableLiveData<List<Message>>(emptyList())
    val messages: LiveData<List<Message>> = _messages

    // Pulses whenever a location payload arrives — used by the status line
    // Holds the timestamp of the most recent location update
    private val _locationUpdates = MutableLiveData<String?>()
    val locationUpdates: LiveData<String?> = _locationUpdates

    private val _trafficEntries = MutableLiveData<List<TrafficEntry>>(emptyList())
    val trafficEntries: LiveData<List<TrafficEntry>> = _trafficEntries

    private val _pingEntries = MutableLiveData<List<PingEntry>>(emptyList())
    val pingEntries: LiveData<List<PingEntry>> = _pingEntries

    private val MAX_TRAFFIC_MS = 20L * 60 * 1000   // 20 minutes
    private val MAX_PINGS = 30

    // internal mutable map
    private val deviceMap = mutableMapOf<String, DeviceState>()

    private val queuedLocationMap = mutableMapOf<String, LocationEntry>()

    // ── Ingestion ─────────────────────────────────────────────────────────────

    fun ingest(raw: String) {
        try {
            val json = JsonParser.parseString(raw.trim()).asJsonObject
            Log.w("json", "JSON Keys: ${json.keySet()}")
            when {
                json.has("self")      -> processSelf(json)
                json.has("devices")   -> processDevices(json)
                json.has("neighbors") -> processNeighbors(json)
                json.has("location")  -> processLocation(json)
                json.has("graph")     -> processGraph(json)
                json.has("sender")    -> processMessage(json)
                json.has("message")   -> processMessage(json)
                json.has("traffic")   -> processTraffic(json)
            }
        } catch (e: Exception) {
            // Malformed JSON — ignore
        }
    }

    private fun processSelf(json: JsonObject) {
        val payload = gson.fromJson(json, SelfPayload::class.java)
        _selfDevice.postValue(payload.self)

        // Also upsert self into deviceMap as a synthetic ClusterDevice
        val s = payload.self
        val syntheticDevice = ClusterDevice(
            id = s.id,
            name = s.name,
            nickname = null,
            address = s.address,
            critical = false,
            icon = s.icon
        )
        val existing = deviceMap[s.id]
        if (existing == null) {
            deviceMap[s.id] = DeviceState(
                device = syntheticDevice,
                lat = s.lat.toDoubleOrNull(),
                lon = s.lon.toDoubleOrNull(),
                head = s.head?.toDoubleOrNull(),
                speed = s.speed?.toDoubleOrNull(),
                locationTs = s.ts,
                battery = s.batteryLevel,
                temperature = s.temperature?.toDoubleOrNull(),
                motion = s.motion,
                relayState = s.relayState
            )
        } else {
            // Update location + vitals from self
            val updated = existing.copy(
                device = syntheticDevice,
                lat = s.lat.toDoubleOrNull(),
                lon = s.lon.toDoubleOrNull(),
                head = s.head?.toDoubleOrNull(),
                speed = s.speed?.toDoubleOrNull(),
                locationTs = s.ts,
                battery = s.batteryLevel,
                temperature = s.temperature?.toDoubleOrNull(),
                motion = s.motion,
                relayState = s.relayState
            )
            deviceMap[s.id] = updated
        }
        _deviceStates.postValue(deviceMap.toMap())
    }

    private fun processDevices(json: JsonObject) {
        val payload = gson.fromJson(json, DevicesPayload::class.java)
        payload.devices.forEach { dev ->
            //Log.w("json", "Ingest device: ${dev.name}")
            val existing = deviceMap[dev.id]
            if (existing == null) {
                deviceMap[dev.id] = DeviceState(device = dev)

                // check if unprocessed locations
                val queuedLoc = queuedLocationMap[dev.id]
                if (queuedLoc != null) {
                    val justCreated = deviceMap[dev.id]
                    if (justCreated != null) {
                        deviceMap[dev.id] = justCreated.copy(
                            lat = queuedLoc.lat.toDoubleOrNull(),
                            lon = queuedLoc.lon.toDoubleOrNull(),
                            head = queuedLoc.head?.toDoubleOrNull(),
                            speed = queuedLoc.speed?.toDoubleOrNull(),
                            locationTs = queuedLoc.ts
                        )
                        queuedLocationMap.remove(dev.id)
                    }
                }
            } else {
                deviceMap[dev.id] = existing.copy(device = dev)
            }
        }
        _deviceStates.postValue(deviceMap.toMap())
    }

    private fun processNeighbors(json: JsonObject) {
        Log.w("json", "Processing neighbors")
        val payload = gson.fromJson(json, NeighborsPayload::class.java)
        val nbrs = payload.neighbors

        // Reset neighbor types
        //deviceMap.forEach { (id, state) ->
        //    deviceMap[id] = state.copy(neighborType = NeighborType.NONE)
        //}


        nbrs.direct.forEach { entry ->
            Log.w("json", "Direct: Battery ${entry.battery}, temp: ${entry.battery}")
            deviceMap[entry.id]?.let { state ->
                val updated = state.copy(
                    neighborType = NeighborType.DIRECT,
                    battery = entry.battery ?: state.battery,
                    temperature = entry.temperature ?: state.temperature,
                    motion = entry.motion ?: state.motion,
                    relayState = entry.relayState ?: state.relayState
                )
                deviceMap[entry.id] = updated
            }
        }
        nbrs.indirect.forEach { entry ->
            val current = deviceMap[entry.id]
            Log.w("json", "Indirect: Battery ${entry.battery}, temp: ${entry.battery}")
            if (current != null && (current.neighborType == NeighborType.NONE || current.neighborType == NeighborType.INDIRECT)) {
                val updated = current.copy(
                    neighborType = NeighborType.INDIRECT,
                    battery = entry.battery ?: current.battery,
                    temperature = entry.temperature ?: current.temperature,
                    motion = entry.motion ?: current.motion,
                    relayState = entry.relayState ?: current.relayState
                )
                deviceMap[entry.id] = updated
            }
        }

        _neighbors.postValue(nbrs)
        _deviceStates.postValue(deviceMap.toMap())

        // Emit a PingEntry for every direct and indirect neighbor sighting
        val nowMs = System.currentTimeMillis()
        val newPings = mutableListOf<PingEntry>()
        nbrs.direct.forEach { entry ->
            newPings.add(PingEntry(receivedMs = nowMs, deviceId = entry.id, rssi = entry.rssi, isDirect = true))
        }
        nbrs.indirect.forEach { entry ->
            newPings.add(PingEntry(receivedMs = nowMs, deviceId = entry.id, rssi = entry.rssi, isDirect = false))
        }
        if (newPings.isNotEmpty()) {
            val current = _pingEntries.value?.toMutableList() ?: mutableListOf()
            current.addAll(0, newPings)   // newest first
            _pingEntries.postValue(current.take(MAX_PINGS))
        }
    }

    private fun processLocation(json: JsonObject) {
        val payload = gson.fromJson(json, LocationPayload::class.java)
        payload.location.forEach { loc ->
            val existing = deviceMap[loc.id]
            if (existing != null) {
                deviceMap[loc.id] = existing.copy(
                    lat = loc.lat.toDoubleOrNull(),
                    lon = loc.lon.toDoubleOrNull(),
                    head = loc.head?.toDoubleOrNull(),
                    speed = loc.speed?.toDoubleOrNull(),
                    locationTs = loc.ts
                )
            } else {
                Log.w("json", "${loc.id} not found in ${deviceMap.keys}")
                queuedLocationMap[loc.id] = loc
            }
        }
        _deviceStates.postValue(deviceMap.toMap())
        // Pulse the location update signal for the status line
        _locationUpdates.postValue(payload.location.firstOrNull()?.ts)
    }

    private fun processGraph(json: JsonObject) {
        val payload = gson.fromJson(json, GraphPayload::class.java)
        // Upsert: merge incoming entries into the accumulated graph map.
        // This handles partial graph messages that carry only a subset of nodes.
        payload.graph.forEach { (fromAddr, relations) ->
            val existing = graphMap.getOrPut(fromAddr) { mutableMapOf() }
            relations.forEach { (toAddr, rel) ->
                existing[toAddr] = rel
            }
        }
        // Publish a new GraphPayload snapshot from the merged map
        _graphData.postValue(GraphPayload(graphMap.toMap().mapValues { it.value.toMap() }))
    }

    private fun processMessage(json: JsonObject) {
        try {
            // Try wrapping in "message" if needed
            val wrapper = if (json.has("message")) {
                gson.fromJson(json.get("message"), Message::class.java)
            } else {
                gson.fromJson(json, Message::class.java)
            }
            val current = _messages.value?.toMutableList() ?: mutableListOf()
            current.add(0, wrapper) // newest first
            _messages.postValue(current)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun processTraffic(json: JsonObject) {
        val inner = json.getAsJsonObject("traffic") ?: return
        val entry = TrafficEntry(
            receivedMs  = System.currentTimeMillis(),
            bytesIn     = inner.get("bytesIn")?.asLong    ?: 0L,
            bytesOut    = inner.get("bytesOut")?.asLong   ?: 0L,
            packetsIn   = inner.get("packetsIn")?.asLong  ?: 0L,
            packetsOut  = inner.get("packetsOut")?.asLong ?: 0L
        )
        val nowMs   = System.currentTimeMillis()
        val cutoff  = nowMs - MAX_TRAFFIC_MS
        val updated = (_trafficEntries.value?.toMutableList() ?: mutableListOf()).also { list ->
            list.add(entry)
            list.removeAll { it.receivedMs < cutoff }
        }
        _trafficEntries.postValue(updated)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun getDeviceById(id: String): DeviceState? = deviceMap[id]

    /** Normalise address string to integer for comparison */
    fun normaliseAddress(addr: String): Int = addr.trimStart('0').toIntOrNull() ?: 0

    fun deviceByAddress(address: String): DeviceState? {
        val target = normaliseAddress(address)
        return deviceMap.values.firstOrNull { normaliseAddress(it.device.address) == target }
    }

    fun reset() {
        deviceMap.clear()
        graphMap.clear()
        _selfDevice.postValue(null)
        _deviceStates.postValue(emptyMap())
        _neighbors.postValue(null)
        _graphData.postValue(null)
        _messages.postValue(emptyList())
        _locationUpdates.postValue(null)
        _trafficEntries.postValue(emptyList())
        _pingEntries.postValue(emptyList())
    }

    /**
     * Restores all state from a [MapSaveManager.ClusterSnapshot].
     * Called by MapSaveManager.restore(); replaces current state entirely.
     */
    fun restoreFromSnapshot(snapshot: MapSaveManager.ClusterSnapshot) {
        // Rebuild internal maps
        deviceMap.clear()
        deviceMap.putAll(snapshot.devices)

        graphMap.clear()
        snapshot.graph.forEach { (fromAddr, relations) ->
            graphMap[fromAddr] = relations.toMutableMap()
        }

        // Post all LiveData values
        _selfDevice.postValue(snapshot.self)
        _deviceStates.postValue(snapshot.devices.toMap())
        _neighbors.postValue(snapshot.neighbors)
        _graphData.postValue(
            if (snapshot.graph.isEmpty()) null
            else GraphPayload(snapshot.graph)
        )
        _messages.postValue(snapshot.messages)
        _trafficEntries.postValue(snapshot.trafficEntries)
        _pingEntries.postValue(snapshot.pingEntries)
        _locationUpdates.postValue(null)
    }
}
