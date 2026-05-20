package com.blackoutcomms.live.ui.messages

import android.content.Context
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blackoutcomms.live.R
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.model.Message
import com.blackoutcomms.live.util.IconResolver
import com.blackoutcomms.live.util.TimestampUtil

class MessageAdapter(
    private val context: Context,
    private val onReply: (senderId: String) -> Unit = {}
) : ListAdapter<Message, MessageAdapter.MessageViewHolder>(DIFF) {

    private var deviceStates: Map<String, DeviceState> = emptyMap()
    private var selfId: String = ""
    private var bleConnected: Boolean = false

    fun updateDeviceStates(states: Map<String, DeviceState>) {
        deviceStates = states
        notifyDataSetChanged()
    }

    fun updateSelfId(id: String) {
        selfId = id
        notifyDataSetChanged()
    }

    fun updateBleConnected(connected: Boolean) {
        bleConnected = connected
        notifyDataSetChanged()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(a: Message, b: Message) =
                a.id == b.id && a.recipient == b.recipient
            override fun areContentsTheSame(a: Message, b: Message) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message_row, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        // Alternate background: even rows slightly lighter than odd rows
        val bgColor = if (position % 2 == 0)
            android.graphics.Color.parseColor("#1A231A")   // slightly lighter dark green
        else
            android.graphics.Color.TRANSPARENT
        holder.itemView.setBackgroundColor(bgColor)
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgType:       ImageView = view.findViewById(R.id.img_msg_type)
        private val tvTitle:       TextView  = view.findViewById(R.id.tv_msg_title)
        private val tvTimestamp:   TextView  = view.findViewById(R.id.tv_msg_timestamp)
        private val tvPriority:    TextView  = view.findViewById(R.id.tv_msg_priority)
        private val imgFromDevice: ImageView = view.findViewById(R.id.img_msg_from_device)
        private val tvFrom:        TextView  = view.findViewById(R.id.tv_msg_from)
        private val imgToDevice:   ImageView = view.findViewById(R.id.img_msg_to_device)
        private val tvTo:          TextView  = view.findViewById(R.id.tv_msg_to)
        private val tvText:        TextView  = view.findViewById(R.id.tv_msg_text)
        private val imgCommand:    ImageView = view.findViewById(R.id.img_msg_command)
        private val imgStatus:     ImageView = view.findViewById(R.id.img_msg_status)
        private val tvStatus:      TextView  = view.findViewById(R.id.tv_msg_status)
        private val tvDelivery:    TextView  = view.findViewById(R.id.tv_msg_delivery)
        private val btnReply:      android.widget.Button = view.findViewById(R.id.btn_msg_reply)

        fun bind(msg: Message) {
            val fromState = deviceStates[msg.sender]
            val toState   = deviceStates[msg.recipient]

            // ── Message type icon (sent/received, direct/broadcast) ────────────
            imgType.setImageResource(IconResolver.messageTypeIcon(msg, selfId))

            // ── Title + timestamp ─────────────────────────────────────────────
            tvTitle.text     = msg.title.takeIf { it.isNotBlank() } ?: "(no subject)"
            tvTimestamp.text = TimestampUtil.formatTs(msg.ts)

            // ── Priority badge ─────────────────────────────────────────────────
            if (!msg.priority.isNullOrBlank()) {
                tvPriority.visibility = android.view.View.VISIBLE
                tvPriority.text = "Priority: ${msg.priority}"
                val bgColor = when (msg.priority.lowercase()) {
                    "low"      -> android.graphics.Color.parseColor("#4CAF50")
                    "normal"   -> android.graphics.Color.parseColor("#2196F3")
                    "medium"   -> android.graphics.Color.parseColor("#FFC107")
                    "high"     -> android.graphics.Color.parseColor("#FF6600")
                    "critical" -> android.graphics.Color.parseColor("#F44336")
                    else       -> android.graphics.Color.parseColor("#2196F3")
                }
                tvPriority.setBackgroundColor(bgColor)
            } else {
                tvPriority.visibility = android.view.View.GONE
            }

            // ── From: hide if sender is self device ───────────────────────────
            val fromRow = itemView.findViewById<android.view.View>(R.id.row_msg_from)
            if (msg.sender == selfId) {
                fromRow.visibility = android.view.View.GONE
            } else {
                fromRow.visibility = android.view.View.VISIBLE
                imgFromDevice.setImageResource(IconResolver.deviceIcon(fromState?.device?.icon))
                tvFrom.text = "From: ${fromState?.device?.displayName ?: msg.sender}"
            }

            // ── To: hide if recipient is self device or "All Devices" broadcast ──
            val toRow = itemView.findViewById<android.view.View>(R.id.row_msg_to)
            val isSelfRecipient    = msg.recipient == selfId
            val isBroadcastAll     = msg.recipient.equals("[all devices]", ignoreCase = true)
            if (isSelfRecipient || isBroadcastAll) {
                toRow.visibility = android.view.View.GONE
            } else {
                toRow.visibility = android.view.View.VISIBLE
                imgToDevice.setImageResource(IconResolver.deviceIcon(toState?.device?.icon))
                tvTo.text = "To: ${toState?.device?.displayName ?: msg.recipient}"
            }

            // ── Body — hide text and show command icon for CFG: messages ──────
            val isCommand = msg.text.startsWith("CFG:", ignoreCase = true)
            if (isCommand) {
                tvText.visibility     = android.view.View.GONE
                imgCommand.visibility = android.view.View.VISIBLE
            } else {
                tvText.visibility     = android.view.View.VISIBLE
                tvText.text           = msg.text
                imgCommand.visibility = android.view.View.GONE
            }

            // ── Status icon + labels ──────────────────────────────────────────
            imgStatus.setImageResource(IconResolver.messageStatusIcon(msg))
            tvStatus.text   = msg.status.uppercase()
            tvDelivery.text = msg.delivery.uppercase()

            // ── Reply button — shown when self is recipient, enabled only when connected
            if (msg.recipient == selfId && selfId.isNotBlank()) {
                btnReply.visibility = android.view.View.VISIBLE
                btnReply.isEnabled  = bleConnected
                btnReply.alpha      = if (bleConnected) 1.0f else 0.4f
                btnReply.setOnClickListener { if (bleConnected) onReply(msg.sender) }
            } else {
                btnReply.visibility = android.view.View.GONE
            }
        }
    }
}
