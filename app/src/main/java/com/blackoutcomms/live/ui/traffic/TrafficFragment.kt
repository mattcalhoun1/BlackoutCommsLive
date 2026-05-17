package com.blackoutcomms.live.ui.traffic

import android.graphics.Color
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.blackoutcomms.live.R
import com.blackoutcomms.live.databinding.FragmentTrafficBinding
import com.blackoutcomms.live.model.TrafficEntry
import com.github.mikephil.charting.components.Legend
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import java.text.SimpleDateFormat
import java.util.*

class TrafficFragment : Fragment() {

    private var _binding: FragmentTrafficBinding? = null
    private val binding get() = _binding!!
    // Use activityViewModels() so the ViewModel is scoped to the Activity
    // rather than the Fragment. When the user switches tabs, replace()
    // destroys and recreates TrafficFragment — a fragment-scoped ViewModel
    // would be recreated too, and the chart would fail to render on first
    // invalidate() because its dimensions are still zero.
    private val viewModel: TrafficViewModel by activityViewModels()
    private lateinit var pingAdapter: PingAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTrafficBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupChart()
        setupPingList()
        setupObservers()
    }

    // ── Chart ─────────────────────────────────────────────────────────────────

    private fun setupChart() {
        binding.chart.apply {
            description.isEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
            setDrawGridBackground(false)
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)

            // X axis — time labels
            xAxis.apply {
                textColor = Color.parseColor("#6E7A63")
                gridColor = Color.parseColor("#1E2A1E")
                axisLineColor = Color.parseColor("#1E2A1E")
                textSize = 9f
                valueFormatter = TimeAxisFormatter()
                labelRotationAngle = -30f
                setLabelCount(6, false)
            }

            // Left axis — Packets (smaller scale)
            axisLeft.apply {
                textColor = Color.parseColor("#A8C48A")
                gridColor = Color.parseColor("#1E2A1E")
                axisLineColor = Color.parseColor("#1E2A1E")
                textSize = 9f
                setDrawZeroLine(true)
                zeroLineColor = Color.parseColor("#2A3A2A")
            }

            // Right axis — Bytes (larger scale)
            axisRight.apply {
                textColor = Color.parseColor("#C8A84B")
                gridColor = Color.TRANSPARENT
                axisLineColor = Color.parseColor("#1E2A1E")
                textSize = 9f
                isEnabled = true
            }

            // Legend
            legend.apply {
                textColor = Color.parseColor("#CDD4C0")
                textSize = 10f
                form = Legend.LegendForm.LINE
                orientation = Legend.LegendOrientation.HORIZONTAL
                verticalAlignment = Legend.LegendVerticalAlignment.BOTTOM
                horizontalAlignment = Legend.LegendHorizontalAlignment.CENTER
                isWordWrapEnabled = true
            }

            setNoDataText("Waiting for traffic data…")
            setNoDataTextColor(Color.parseColor("#6E7A63"))
        }
    }

    private fun updateChart(entries: List<TrafficEntry>) {
        if (entries.isEmpty()) {
            binding.chart.clear()
            return
        }

        // Packets datasets → left axis (smaller numbers)
        val packetsInData  = entries.mapIndexed { i, e -> Entry(i.toFloat(), e.packetsIn.toFloat()) }
        val packetsOutData = entries.mapIndexed { i, e -> Entry(i.toFloat(), e.packetsOut.toFloat()) }

        // Bytes datasets → right axis (larger numbers)
        val bytesInData    = entries.mapIndexed { i, e -> Entry(i.toFloat(), e.bytesIn.toFloat()) }
        val bytesOutData   = entries.mapIndexed { i, e -> Entry(i.toFloat(), e.bytesOut.toFloat()) }

        // Base factory for all datasets
        fun makeSet(
            data: List<Entry>, label: String, colorHex: String, axis: YAxis.AxisDependency
        ) = LineDataSet(data, label).apply {
            color = Color.parseColor(colorHex)
            setCircleColor(Color.parseColor(colorHex))
            lineWidth = 1.8f
            circleRadius = 2.5f
            setDrawCircleHole(false)
            setDrawValues(false)
            axisDependency = axis
            mode = LineDataSet.Mode.LINEAR
        }

        // Packets sets — thicker lines so they stand out over the filled byte areas
        val packetsInSet  = makeSet(packetsInData,  "Packets In",  "#7A9E5F", YAxis.AxisDependency.LEFT).apply {
            lineWidth = 3.0f
            circleRadius = 3.0f
        }
        val packetsOutSet = makeSet(packetsOutData, "Packets Out", "#5C7A8A", YAxis.AxisDependency.LEFT).apply {
            lineWidth = 3.0f
            circleRadius = 3.0f
        }

        // Bytes sets — semi-transparent line + filled area underneath
        // Color.parseColor only handles #RRGGBB, so we build ARGB ints manually:
        //   Bytes In  amber  #C8A84B → line at ~70% alpha (0xB3), fill at ~30% alpha (0x4D)
        //   Bytes Out orange #B06030 → line at ~70% alpha (0xB3), fill at ~30% alpha (0x4D)
        val bytesInSet = makeSet(bytesInData, "Bytes In", "#C8A84B", YAxis.AxisDependency.RIGHT).apply {
            color = Color.argb(0xB3, 0xC8, 0xA8, 0x4B)          // 70% opaque amber line
            setCircleColor(Color.argb(0xB3, 0xC8, 0xA8, 0x4B))
            setDrawFilled(true)
            fillColor = Color.argb(0x4D, 0xC8, 0xA8, 0x4B)      // 30% opaque amber fill
            fillAlpha = 100   // fillAlpha is applied on top of fillColor's own alpha in MPChart;
                              // set to 255 so fillColor alpha is used as-is
        }
        val bytesOutSet = makeSet(bytesOutData, "Bytes Out", "#B06030", YAxis.AxisDependency.RIGHT).apply {
            color = Color.argb(0xB3, 0xB0, 0x60, 0x30)          // 70% opaque orange line
            setCircleColor(Color.argb(0xB3, 0xB0, 0x60, 0x30))
            setDrawFilled(true)
            fillColor = Color.argb(0x4D, 0xB0, 0x60, 0x30)      // 30% opaque orange fill
            fillAlpha = 100
        }

        val lineData = LineData(
            // Bytes first so their fill renders beneath the packet lines
            bytesInSet,
            bytesOutSet,
            packetsInSet,
            packetsOutSet
        )

        // Store timestamps for the X axis formatter
        binding.chart.xAxis.valueFormatter = TimeAxisFormatter(entries.map { it.receivedMs })

        binding.chart.data = lineData
        binding.chart.notifyDataSetChanged()
        binding.chart.invalidate()
    }

    // ── Ping list ─────────────────────────────────────────────────────────────

    private fun setupPingList() {
        pingAdapter = PingAdapter(requireContext())
        binding.recyclerPings.apply {
            adapter = pingAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        viewModel.trafficEntries.observe(viewLifecycleOwner) { entries ->
            // Defer chart update to next layout pass. MPAndroidChart silently
            // does nothing if the chart view dimensions are still zero when
            // data is set — which happens on the initial emission right after
            // the fragment view is created (before first layout pass).
            binding.chart.post { updateChart(entries) }
        }

        viewModel.pingEntries.observe(viewLifecycleOwner) { pings ->
            pingAdapter.submitList(pings.toList())
        }

        viewModel.deviceStates.observe(viewLifecycleOwner) { states ->
            pingAdapter.updateDeviceStates(states)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ── X axis time formatter ─────────────────────────────────────────────────

    private class TimeAxisFormatter(
        private val timestamps: List<Long> = emptyList()
    ) : ValueFormatter() {
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        override fun getFormattedValue(value: Float): String {
            val idx = value.toInt().coerceIn(0, timestamps.size - 1)
            return if (timestamps.isEmpty()) "" else fmt.format(Date(timestamps[idx]))
        }
    }
}
