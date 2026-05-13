package com.blackoutcomms.live.data

import com.blackoutcomms.live.model.NeighborEntry
import com.blackoutcomms.live.model.Neighbors
import com.google.gson.*
import java.lang.reflect.Type

/**
 * Resilient deserializer for the "neighbors" payload.
 *
 * Handles several real-world firmware quirks:
 *
 *  1. "direct" or "indirect" may be absent entirely — treated as empty list.
 *  2. Either list may arrive as a JSON array OR a JSON object keyed by index.
 *  3. "temperature" may be sent as a JSON number (98.6) OR a JSON string ("23.8").
 *     Gson would throw JsonSyntaxException on the string form if the model field
 *     is Double, silently dropping the whole entry. We parse it manually instead.
 *  4. "ts" may use the compact format "YYMMDDHHmmss" (e.g. "260513155919") rather
 *     than the full "yyyy-MM-dd HH:mm:ss" form seen in other payloads. Both are
 *     stored as-is; callers that need to compare timestamps should use
 *     TimestampUtil.parseTs() which handles both formats.
 */
class NeighborsDeserializer : JsonDeserializer<Neighbors> {

    override fun deserialize(
        json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext
    ): Neighbors {
        if (!json.isJsonObject) return Neighbors()
        val obj = json.asJsonObject
        return Neighbors(
            direct   = parseEntryList(obj.get("direct")),
            indirect = parseEntryList(obj.get("indirect"))
        )
    }

    private fun parseEntryList(element: JsonElement?): List<NeighborEntry> {
        if (element == null || element.isJsonNull) return emptyList()
        return when {
            element.isJsonArray  -> element.asJsonArray.mapNotNull  { parseEntry(it) }
            element.isJsonObject -> element.asJsonObject.entrySet().mapNotNull { (_, v) -> parseEntry(v) }
            else -> emptyList()
        }
    }

    /**
     * Parse a single neighbor entry object manually so that individual field
     * type mismatches (e.g. temperature as String) never drop the whole entry.
     */
    private fun parseEntry(el: JsonElement): NeighborEntry? {
        if (!el.isJsonObject) return null
        val obj = el.asJsonObject

        val id = obj.get("id")?.asString ?: return null   // id is mandatory

        return NeighborEntry(
            id          = id,
            ts          = obj.get("ts")?.asString,
            rssi        = obj.get("rssi")?.asIntOrNull(),
            battery     = obj.get("battery")?.asString,
            temperature = obj.get("temperature")?.asDoubleOrNull(),  // handles String or Number
            motion      = obj.get("motion")?.asString,
            relayState  = obj.get("relayState")?.asString
        )
    }

    // ── Safe coercion helpers ─────────────────────────────────────────────────

    /** Returns Int regardless of whether the JSON value is a number or a numeric string. */
    private fun JsonElement.asIntOrNull(): Int? = try {
        when {
            isJsonPrimitive && asJsonPrimitive.isNumber -> asInt
            isJsonPrimitive -> asString.toIntOrNull()
            else -> null
        }
    } catch (_: Exception) { null }

    /** Returns Double regardless of whether the JSON value is a number or a numeric string. */
    private fun JsonElement.asDoubleOrNull(): Double? = try {
        when {
            isJsonPrimitive && asJsonPrimitive.isNumber -> asDouble
            isJsonPrimitive -> asString.toDoubleOrNull()
            else -> null
        }
    } catch (_: Exception) { null }
}
