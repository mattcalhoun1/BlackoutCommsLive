package com.blackoutcomms.live.ui.messages

import org.json.JSONObject

/**
 * Builds outgoing message JSON payloads for the two send types.
 *
 * Broadcast: {"bc":{"msg":"...","nodes":true,"priority":"Normal","expiry":60}}
 * Direct:    {"dm":{"to":"abc123","msg":"...","priority":"Normal","fm":false,"expiry":30}}
 */
object SendMessageManager {

    enum class Priority(val label: String, val value: String) {
        LOW("Low", "Low"),
        NORMAL("Normal", "Normal"),
        MEDIUM("Medium", "Medium"),
        HIGH("High", "High"),
        CRITICAL("Critical", "Critical");

        override fun toString() = label
    }

    /** Expiry options for Direct Messages (minutes). */
    val DM_EXPIRY_OPTIONS = listOf(
        ExpiryOption("5 Minutes",  5),
        ExpiryOption("10 Minutes", 10),
        ExpiryOption("30 Minutes", 30),
        ExpiryOption("1 Hour",     60),
        ExpiryOption("2 Hours",    120),
        ExpiryOption("24 Hours",   1440)
    )

    /** Expiry options for Broadcast Messages (minutes). */
    val BC_EXPIRY_OPTIONS = listOf(
        ExpiryOption("5 Minutes",  5),
        ExpiryOption("10 Minutes", 10),
        ExpiryOption("30 Minutes", 30),
        ExpiryOption("1 Hour",     60),
        ExpiryOption("2 Hours",    120)
    )

    data class ExpiryOption(val label: String, val minutes: Int) {
        override fun toString() = label
    }

    /**
     * Build a broadcast message JSON string.
     *
     * @param msg      Message text (max 255 chars, restricted charset)
     * @param nodes    Whether to include relay nodes in delivery
     * @param priority Message priority
     * @param expiry   Expiry duration in minutes
     */
    fun buildBroadcast(
        msg: String,
        nodes: Boolean,
        priority: Priority,
        expiry: Int
    ): String {
        val inner = JSONObject().apply {
            put("msg",      msg)
            put("nodes",    nodes)
            put("priority", priority.value)
            put("expiry",   expiry)
        }
        return JSONObject().put("bc", inner).toString()
    }

    /**
     * Build a direct message JSON string.
     *
     * @param to       Recipient device id
     * @param msg      Message text (max 255 chars, restricted charset)
     * @param priority Message priority
     * @param fm       Force mesh routing
     * @param expiry   Expiry duration in minutes
     */
    fun buildDirectMessage(
        to: String,
        msg: String,
        priority: Priority,
        fm: Boolean,
        expiry: Int
    ): String {
        val inner = JSONObject().apply {
            put("to",       to)
            put("msg",      msg)
            put("priority", priority.value)
            put("fm",       fm)
            put("expiry",   expiry)
        }
        return JSONObject().put("dm", inner).toString()
    }

    /** Sanitize input: only allow letters, numbers, spaces, periods, commas. Max 255 chars. */
    fun sanitizeText(input: String): String =
        input.filter { it.isLetterOrDigit() || it == ' ' || it == '.' || it == ',' }
            .take(255)
}
