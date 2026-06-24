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

    /**
     * Optional callback invoked each time any valid payload is ingested.
     * Used by ConnectionService to detect the first data arriving after a
     * PIN exchange, triggering PIN verification and credential storage.
     */
    var onDataIngested: (() -> Unit)? = null

    /**
     * Pulses true each time any valid JSON payload is successfully ingested.
     * Observed by MainActivity to show the data-activity spinner on the toolbar.
     * The value toggles so that repeated emissions are always distinct.
     */
    private val _dataActivity = MutableLiveData(false)
    val dataActivity: LiveData<Boolean> = _dataActivity
    private var _activityToggle = false

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

    // Saved/historical messages upserted by id — keyed by message id, newest first
    // Both maps keyed by "id|recipient" composite key so messages with the same
    // id but different recipients are stored independently.
    internal val liveMessageMap   = mutableMapOf<String, Message>()
    internal val savedMessageMap  = mutableMapOf<String, Message>()
    private val _savedMessages = MutableLiveData<List<Message>>(emptyList())
    val savedMessages: LiveData<List<Message>> = _savedMessages

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
        // Notify listener on successful ingestion (used for PIN verification)
        if (raw.isNotEmpty())
            onDataIngested?.invoke()

        try {
            val json = JsonParser.parseString(raw.trim()).asJsonObject
            Log.d("json", "JSON Keys: ${json.keySet()}")
            when {
                json.has("self")      -> processSelf(json)
                json.has("devices")   -> processDevices(json)
                json.has("neighbors") -> processNeighbors(json)
                json.has("location")  -> processLocation(json)
                json.has("graph")     -> processGraph(json)
                json.has("sender")    -> processMessage(json)
                json.has("message")   -> processMessage(json)
                json.has("traffic")       -> processTraffic(json)
                json.has("messageStatus") -> processMessageStatus(json)
            }
            // Notify listener on successful ingestion (used for PIN verification)
            onDataIngested?.invoke()

            // Pulse the data-activity LiveData so the toolbar can show a spinner
            _activityToggle = !_activityToggle
            _dataActivity.postValue(_activityToggle)
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

        _deviceStates.postValue(deviceMap.toMap())
        _locationUpdates.postValue(payload.location.firstOrNull()?.ts)

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

    private fun messageKey(msg: Message): String {
        val id = msg.id ?: "${msg.sender}_${msg.ts}"
        return "$id|${msg.recipient}"
    }

    private fun processMessage(json: JsonObject) {
        try {
            val msgJson = if (json.has("message")) json.getAsJsonObject("message") else json
            val msg = gson.fromJson(msgJson, Message::class.java)
            val key = messageKey(msg)

            // A "deleted" status means the message should be removed from both maps
            if (msg.status.equals("deleted", ignoreCase = true)) {
                val liveRemoved  = liveMessageMap.remove(key) != null
                val savedRemoved = savedMessageMap.remove(key) != null
                if (liveRemoved)  _messages.postValue(liveMessageMap.values.sortedByDescending { it.ts }.toList())
                if (savedRemoved) _savedMessages.postValue(savedMessageMap.values.sortedByDescending { it.ts }.toList())
                return
            }

            if (msg.isNew == false) {
                // Saved/historical message — upsert to savedMessages only
                savedMessageMap[key] = msg
                _savedMessages.postValue(
                    savedMessageMap.values.sortedByDescending { it.ts }.toList()
                )
            } else {
                // New (isNew absent or true) — upsert to both live and saved
                liveMessageMap[key] = msg
                _messages.postValue(
                    liveMessageMap.values.sortedByDescending { it.ts }.toList()
                )
                savedMessageMap[key] = msg
                _savedMessages.postValue(
                    savedMessageMap.values.sortedByDescending { it.ts }.toList()
                )
            }
        } catch (e: Exception) {
            // ignore malformed payload
        }
    }

    private fun processMessageStatus(json: JsonObject) {
        try {
            val inner = json.getAsJsonObject("messageStatus") ?: return
            val status = gson.fromJson(inner, MessageStatus::class.java)
            val key = "${status.id}|${status.recipient}"

            val isDeleted = status.status.equals("deleted", ignoreCase = true)

            if (isDeleted) {
                // Remove the message from both maps
                val liveRemoved  = liveMessageMap.remove(key) != null
                val savedRemoved = savedMessageMap.remove(key) != null
                if (liveRemoved)  _messages.postValue(liveMessageMap.values.sortedByDescending { it.ts }.toList())
                if (savedRemoved) _savedMessages.postValue(savedMessageMap.values.sortedByDescending { it.ts }.toList())
            } else {
                var liveUpdated  = false
                var savedUpdated = false

                liveMessageMap[key]?.let { existing ->
                    liveMessageMap[key] = existing.copy(status = status.status)
                    liveUpdated = true
                }
                savedMessageMap[key]?.let { existing ->
                    savedMessageMap[key] = existing.copy(status = status.status)
                    savedUpdated = true
                }

                if (liveUpdated) {
                    _messages.postValue(
                        liveMessageMap.values.sortedByDescending { it.ts }.toList()
                    )
                }
                if (savedUpdated) {
                    _savedMessages.postValue(
                        savedMessageMap.values.sortedByDescending { it.ts }.toList()
                    )
                }
            }
        } catch (e: Exception) {
            // ignore malformed payload
        }
    }

    private fun processTraffic(json: JsonObject) {
        val inner = json.getAsJsonObject("traffic") ?: return
        val entry = TrafficEntry(
            receivedMs     = System.currentTimeMillis(),
            bytesIn        = inner.get("bytesIn")?.asLong        ?: 0L,
            bytesOut       = inner.get("bytesOut")?.asLong       ?: 0L,
            packetsIn      = inner.get("packetsIn")?.asLong      ?: 0L,
            packetsOut     = inner.get("packetsOut")?.asLong     ?: 0L,
            unknownPackets = inner.get("unknownPackets")?.asLong ?: 0L
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

    /**
     * Clears the live message map and posts an empty list to _messages.
     * Called when the user dismisses the map message panel so dismissed
     * messages don't reappear when new data arrives.
     */
    fun clearLiveMessages() {
        liveMessageMap.clear()
        _messages.postValue(emptyList())
    }

    fun reset() {
        deviceMap.clear()
        graphMap.clear()
        _selfDevice.postValue(null)
        _deviceStates.postValue(emptyMap())
        _neighbors.postValue(null)
        _graphData.postValue(null)
        liveMessageMap.clear()
        savedMessageMap.clear()
        _messages.postValue(emptyList())
        _savedMessages.postValue(emptyList())
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
        liveMessageMap.clear()
        liveMessageMap.putAll(snapshot.liveMessages)
        _messages.postValue(liveMessageMap.values.sortedByDescending { it.ts }.toList())
        savedMessageMap.clear()
        savedMessageMap.putAll(snapshot.savedMessages)
        _savedMessages.postValue(savedMessageMap.values.sortedByDescending { it.ts }.toList())
        _trafficEntries.postValue(snapshot.trafficEntries)
        _pingEntries.postValue(snapshot.pingEntries)
        _locationUpdates.postValue(null)
    }
}
