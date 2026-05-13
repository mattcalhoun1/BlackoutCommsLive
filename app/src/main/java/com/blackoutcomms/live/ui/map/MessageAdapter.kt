package com.blackoutcomms.live.ui.map

import android.content.Context
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.blackoutcomms.live.R
import com.blackoutcomms.live.model.Message
import com.blackoutcomms.live.util.IconResolver
import com.blackoutcomms.live.util.TimestampUtil

class MessageAdapter(private val context: Context) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    private var messages: List<Message> = emptyList()
    private var selfId: String = ""

    fun submitList(list: List<Message>, selfId: String) {
        this.selfId = selfId
        this.messages = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position], selfId)
    }

    override fun getItemCount() = messages.size

    inner class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgType: ImageView   = view.findViewById(R.id.img_message_type)
        private val tvTimestamp: TextView = view.findViewById(R.id.tv_message_timestamp)
        private val tvTitle: TextView    = view.findViewById(R.id.tv_message_title)
        private val imgStatus: ImageView = view.findViewById(R.id.img_message_status)
        private val tvBody: TextView     = view.findViewById(R.id.tv_message_body)

        fun bind(msg: Message, selfId: String) {
            imgType.setImageResource(IconResolver.messageTypeIcon(msg, selfId))
            imgStatus.setImageResource(IconResolver.messageStatusIcon(msg))
            tvTimestamp.text = TimestampUtil.formatTs(msg.ts)
            tvTitle.text     = msg.title
            tvBody.text      = msg.text
        }
    }
}
