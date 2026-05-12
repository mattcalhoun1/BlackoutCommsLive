package com.blackoutcomms.live.data

import com.blackoutcomms.live.model.NeighborEntry
import com.blackoutcomms.live.model.Neighbors
import com.google.gson.*
import java.lang.reflect.Type

/**
 * The "neighbors" payload spec shows "indirect" as a JSON object (with duplicate keys),
 * which is technically malformed. In practice the firmware may send it as either an array
 * or an object keyed by index. This deserializer handles both forms gracefully.
 */
class NeighborsDeserializer : JsonDeserializer<Neighbors> {

    override fun deserialize(json: JsonElement, typeOfT: Type, ctx: JsonDeserializationContext): Neighbors {
        if (!json.isJsonObject) return Neighbors()
        val obj = json.asJsonObject

        val direct   = parseEntryList(obj.get("direct"),   ctx)
        val indirect = parseEntryList(obj.get("indirect"), ctx)

        return Neighbors(direct = direct, indirect = indirect)
    }

    private fun parseEntryList(element: JsonElement?, ctx: JsonDeserializationContext): List<NeighborEntry> {
        if (element == null || element.isJsonNull) return emptyList()

        return when {
            element.isJsonArray -> {
                element.asJsonArray.mapNotNull { el ->
                    try { ctx.deserialize(el, NeighborEntry::class.java) } catch (_: Exception) { null }
                }
            }
            element.isJsonObject -> {
                // Object form: {"0": {entry}, "1": {entry}, ...} — pick the values
                element.asJsonObject.entrySet().mapNotNull { (_, value) ->
                    try { ctx.deserialize(value, NeighborEntry::class.java) } catch (_: Exception) { null }
                }
            }
            else -> emptyList()
        }
    }
}
