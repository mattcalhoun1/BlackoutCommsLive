package com.blackoutcomms.live.ui.traffic

import android.content.Context
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.blackoutcomms.live.R
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.model.PingEntry
import com.blackoutcomms.live.util.IconResolver
import com.blackoutcomms.live.util.TimestampUtil
import java.text.SimpleDateFormat
import java.util.*

class PingAdapter(private val context: Context) :
    ListAdapter<PingEntry, PingAdapter.PingViewHolder>(DIFF) {

    private var deviceStates: Map<String, DeviceState> = emptyMap()
    private val timeFmt = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.US)

    fun updateDeviceStates(states: Map<String, DeviceState>) {
        deviceStates = states
        notifyDataSetChanged()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PingEntry>() {
            override fun areItemsTheSame(a: PingEntry, b: PingEntry) =
                a.receivedMs == b.receivedMs && a.deviceId == b.deviceId
            override fun areContentsTheSame(a: PingEntry, b: PingEntry) = a == b
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ping, parent, false)
        return PingViewHolder(view)
    }

    override fun onBindViewHolder(holder: PingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PingViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val imgDevice: ImageView = view.findViewById(R.id.img_ping_device)
        private val tvName:    TextView  = view.findViewById(R.id.tv_ping_name)
        private val tvTime:    TextView  = view.findViewById(R.id.tv_ping_time)
        private val tvRssi:    TextView  = view.findViewById(R.id.tv_ping_rssi)
        private val tvType:    TextView  = view.findViewById(R.id.tv_ping_type)

        fun bind(ping: PingEntry) {
            val state = deviceStates[ping.deviceId]
            val iconRes = IconResolver.deviceIcon(state?.device?.icon)
            imgDevice.setImageResource(iconRes)
            tvName.text = state?.device?.displayName ?: ping.deviceId
            tvTime.text = timeFmt.format(Date(ping.receivedMs))
            tvRssi.text = ping.rssi?.let { "$it dBm" } ?: "—"
            tvType.text = if (ping.isDirect) "DIRECT" else "INDIRECT"
        }
    }
}
