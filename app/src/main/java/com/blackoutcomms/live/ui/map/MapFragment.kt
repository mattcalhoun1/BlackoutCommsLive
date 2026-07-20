package com.blackoutcomms.live.ui.map

import android.content.Context
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.blackoutcomms.live.R
import com.blackoutcomms.live.data.ClusterRepository
import com.blackoutcomms.live.databinding.FragmentMapBinding
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.util.TileSources
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import java.io.File
import com.blackoutcomms.live.util.themedAlertBuilder

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    // activityViewModels() shares the same instance as MainActivity.mapViewModel()
    // so setMaxAge() calls from outside the fragment (e.g. test mode) take effect immediately
    private val viewModel: MapViewModel by activityViewModels()

    private var deviceOverlay: DeviceOverlay? = null
    private var mgrsOverlay: MgrsOverlay? = null
    private var messageAdapter: MessageAdapter? = null

    private var mapInitialised = false
    private var lastZoom = 0.0
    private var downloadWarningShown = false

    private var rfEnvelopeOverlay: RfRangeEnvelopeOverlay? = null

    companion object {
        private const val PREFS_NAME = "map_prefs"
        private const val PREF_TILE_SOURCE = "tile_source"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        /*
        // moved earlier in app setup
        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            // filesDir = permanent private storage; never cleared by the OS.
            // Each tile source caches under its own subdirectory (keyed by source name)
            // so switching layers never invalidates the other source's cached tiles.
            osmdroidBasePath  = File(requireContext().filesDir, "osmdroid")
            osmdroidTileCache = File(requireContext().filesDir, "osmdroid/tiles")
            tileDownloadMaxQueueSize  = 40
            expirationOverrideDuration = -1L   // never expire cached tiles
        }
         */

        rfEnvelopeOverlay = RfRangeEnvelopeOverlay(requireContext()) { viewModel.selfDevice.value?.id }

        setupMap()
        setupMessagePanel()
        setupFilterControls()
        setupObservers()

        binding.mapView.overlays.add(0, rfEnvelopeOverlay)
    }

    // ── Map setup ─────────────────────────────────────────────────────────────

    private fun setupMap() {
        binding.mapView.apply {
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)
            controller.setZoom(14.0)

            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean = false
                override fun onZoom(event: ZoomEvent?): Boolean {
                    val newZoom = event?.zoomLevel ?: return false
                    if (!downloadWarningShown && newZoom > 17 && lastZoom <= 17) {
                        downloadWarningShown = true
                        showDownloadWarning()
                    }
                    lastZoom = newZoom
                    return false
                }
            })
        }
        // Apply saved tile source (defaults to ESRI Topo on first launch)
        applyTileSource(loadSavedTileSource())
    }

    // ── Tile source persistence ───────────────────────────────────────────────

    private fun loadSavedTileSource(): TileSources.Source {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key   = prefs.getString(PREF_TILE_SOURCE, TileSources.DEFAULT.prefKey)
        return TileSources.Source.values().firstOrNull { it.prefKey == key }
            ?: TileSources.DEFAULT
    }

    private fun saveTileSource(source: TileSources.Source) {
        requireContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_TILE_SOURCE, source.prefKey)
            .apply()
    }

    /**
     * Switches the map to [source] and updates the button label.
     * OSMDroid keeps each source's tiles in a subdirectory named after the
     * source's unique name string, so both caches accumulate independently —
     * switching back to a previously-used source instantly uses cached tiles.
     */
    private fun applyTileSource(source: TileSources.Source) {
        binding.mapView.setTileSource(TileSources.build(source))
        binding.btnMapLayer.text = source.label
    }

    private fun showTileSourcePicker() {
        val sources  = TileSources.Source.values()
        val labels   = sources.map { it.label }.toTypedArray()
        val current  = loadSavedTileSource()
        val checked  = sources.indexOf(current)

        requireContext().themedAlertBuilder()
            .setTitle("Map Tiles")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val chosen = sources[which]
                saveTileSource(chosen)
                applyTileSource(chosen)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDownloadWarning() {
        requireContext().themedAlertBuilder()
            .setTitle("Large Map Download")
            .setMessage(
                "Zooming in this far will download many map tiles for offline use. " +
                "This may use significant data and storage. Continue?"
            )
            .setPositiveButton("Download") { _, _ -> }
            .setNegativeButton("Zoom Back Out") { _, _ ->
                binding.mapView.controller.setZoom(16.0)
                downloadWarningShown = false
            }
            .show()
    }

    // ── Filter controls ───────────────────────────────────────────────────────

    private fun setupFilterControls() {
        binding.bottomFilterBar.visibility = View.VISIBLE

        // Max Age popup
        binding.btnMaxAge.setOnClickListener {
            val options = MapViewModel.MaxAge.values()
            val labels  = options.map { it.label }.toTypedArray()
            val current = viewModel.selectedMaxAge.value ?: MapViewModel.MaxAge.ALL_TIME
            requireContext().themedAlertBuilder()
                .setTitle("Maximum Age")
                .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                    viewModel.setMaxAge(options[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Device picker
        binding.btnDevices.setOnClickListener {
            val states = viewModel.allDeviceStates.value ?: emptyMap()
            if (states.isEmpty()) {
                requireContext().themedAlertBuilder()
                    .setMessage("No devices have been received yet.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }
            DevicePickerDialog().show(childFragmentManager, DevicePickerDialog.TAG)
        }

        // Map layer picker
        binding.btnMapLayer.setOnClickListener {
            showTileSourcePicker()
        }

        // Critical only checkbox
        binding.checkboxCritical.setOnCheckedChangeListener { _, checked ->
            viewModel.setCriticalOnly(checked)
        }

        // Mesh graph checkbox
        binding.checkboxMeshGraph.setOnCheckedChangeListener { _, checked ->
            deviceOverlay?.showMeshGraph = checked
            binding.mapView.invalidate()
        }

        // MGRS overlay checkbox — unchecked by default
        binding.checkboxMgrs.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                if (mgrsOverlay == null) {
                    mgrsOverlay = MgrsOverlay(requireContext()).also { overlay ->
                        // Pass the current bar height so bottom labels clear the bar.
                        // Use a ViewTreeObserver so we get the actual measured height,
                        // even if the bar hasn't been laid out yet when this runs.
                        val bar = binding.bottomFilterBar
                        if (bar.height > 0) {
                            overlay.bottomInsetPx = bar.height
                        } else {
                            bar.viewTreeObserver.addOnGlobalLayoutListener(
                                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                                    override fun onGlobalLayout() {
                                        overlay.bottomInsetPx = bar.height
                                        bar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                                    }
                                }
                            )
                        }
                    }
                    // Insert below device overlay so markers appear on top
                    val deviceIdx = binding.mapView.overlays.indexOf(deviceOverlay)
                    if (deviceIdx >= 0) {
                        binding.mapView.overlays.add(deviceIdx, mgrsOverlay!!)
                    } else {
                        binding.mapView.overlays.add(mgrsOverlay!!)
                    }
                }
            } else {
                mgrsOverlay?.let { binding.mapView.overlays.remove(it) }
                mgrsOverlay = null
            }
            binding.mapView.invalidate()
        }

        // Dismiss messages — also clear liveMessageMap so dismissed messages
        // don't reappear when the next BLE data packet triggers a re-emission
        binding.btnDismissMessages.setOnClickListener {
            com.blackoutcomms.live.data.ClusterRepository.clearLiveMessages()
            binding.messagePanel.visibility = View.GONE
            binding.bottomFilterBar.visibility = View.VISIBLE
        }

        // Recenter map on self
        binding.btnRecenter.setOnClickListener {
            val self = viewModel.selfDevice.value ?: return@setOnClickListener
            val lat  = self.lat.toDoubleOrNull() ?: return@setOnClickListener
            val lon  = self.lon.toDoubleOrNull() ?: return@setOnClickListener
            binding.mapView.controller.animateTo(GeoPoint(lat, lon))
        }
    }

    // ── Message panel ─────────────────────────────────────────────────────────

    private fun setupMessagePanel() {
        messageAdapter = MessageAdapter(requireContext())
        binding.recyclerMessages.adapter = messageAdapter
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupObservers() {
        // Create the overlay immediately with an empty selfId so other devices
        // are rendered as soon as their locations arrive — before self is known.
        // selfId is updated when the self payload arrives.
        deviceOverlay = DeviceOverlay(requireContext(), "") { state ->
            showDeviceDetail(state)
        }
        binding.mapView.overlays.add(deviceOverlay)

        viewModel.selfDevice.observe(viewLifecycleOwner) { self ->
            self ?: return@observe

            // Update cluster label
            binding.tvCluster.text = if (!self.cluster.isNullOrBlank())
                "Cluster: ${self.cluster}" else "Cluster: loading..."

            // Show self device name on second line once known
            val selfDisplay = self.name.takeIf { it.isNotBlank() }
            if (selfDisplay != null) {
                binding.tvSelfName.text = " $selfDisplay "
                binding.tvSelfName.visibility = View.VISIBLE
            } else {
                binding.tvSelfName.visibility = View.GONE
            }

            // Update selfId on the overlay so self is correctly identified
            deviceOverlay?.selfId = self.id

            // Centre on self if we have coordinates and haven't centred yet
            if (!mapInitialised) {
                val lat = self.lat.toDoubleOrNull() ?: return@observe
                val lon = self.lon.toDoubleOrNull() ?: return@observe
                binding.mapView.controller.setCenter(GeoPoint(lat, lon))
                mapInitialised = true
            }
        }

        viewModel.filteredDeviceStates.observe(viewLifecycleOwner) { states ->
            deviceOverlay?.deviceStates = states
            rfEnvelopeOverlay?.let { binding.mapView.invalidate() }  // or post a specific update

            binding.mapView.invalidate()

            // Fallback: if self location hasn't arrived yet but other devices have
            // known positions, centre on the first one we find. Once mapInitialised
            // is true this block never runs again.
            if (!mapInitialised) {
                val anchor = states.values.firstOrNull { it.lat != null && it.lon != null }
                if (anchor != null) {
                    binding.mapView.controller.setCenter(GeoPoint(anchor.lat!!, anchor.lon!!))
                    mapInitialised = true
                }
            }
        }

        viewModel.graphData.observe(viewLifecycleOwner) { graph ->
            deviceOverlay?.graphData = graph
            binding.mapView.invalidate()
        }

        viewModel.selectedMaxAge.observe(viewLifecycleOwner) { age ->
            binding.btnMaxAge.text = age.label
        }

        viewModel.hiddenDeviceIds.observe(viewLifecycleOwner) { hidden ->
            val total   = viewModel.allDeviceStates.value?.size ?: 0
            val visible = total - hidden.size
            binding.btnDevices.text = if (hidden.isEmpty()) "Devices: All"
                                      else "Devices: $visible/$total"
        }

        viewModel.allDeviceStates.observe(viewLifecycleOwner) { all ->
            val hidden  = viewModel.hiddenDeviceIds.value ?: emptySet()
            val visible = all.size - hidden.size
            binding.btnDevices.text = if (hidden.isEmpty()) "Devices: All"
                                      else "Devices: $visible/${all.size}"
        }

        // Messages — gated by the Show Messages preference
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            val showMessages = viewModel.showMessages.value ?: true
            if (messages.isNotEmpty() && showMessages) {
                val selfId = viewModel.selfDevice.value?.id ?: ""
                messageAdapter?.submitList(messages, selfId)
                binding.messagePanel.visibility = View.VISIBLE
                binding.bottomFilterBar.visibility = View.GONE
            } else {
                binding.messagePanel.visibility = View.GONE
                binding.bottomFilterBar.visibility = View.VISIBLE
            }
        }

        // When Show Messages is toggled, immediately re-evaluate current messages
        viewModel.showMessages.observe(viewLifecycleOwner) { show ->
            val messages = viewModel.messages.value ?: emptyList()
            if (messages.isNotEmpty() && show) {
                val selfId = viewModel.selfDevice.value?.id ?: ""
                messageAdapter?.submitList(messages, selfId)
                binding.messagePanel.visibility = View.VISIBLE
                binding.bottomFilterBar.visibility = View.GONE
            } else {
                binding.messagePanel.visibility = View.GONE
                binding.bottomFilterBar.visibility = View.VISIBLE
            }
        }

        // ── Status line ───────────────────────────────────────────────────────

        viewModel.statusText.observe(viewLifecycleOwner) { text ->
            binding.tvStatus.text = text
        }

        // Trigger status updates from each payload type
        viewModel.selfDevice.observe(viewLifecycleOwner) { self ->
            if (self != null) viewModel.onSelfReceived()
        }

        ClusterRepository.neighbors.observe(viewLifecycleOwner) { nbrs ->
            if (nbrs != null) viewModel.onPingReceived()
        }

        ClusterRepository.locationUpdates.observe(viewLifecycleOwner) { loc ->
            if (loc != null) viewModel.onPingReceived()
        }

        viewModel.graphData.observe(viewLifecycleOwner) { graph ->
            if (graph != null) viewModel.onGraphReceived()
        }

        viewModel.messages.observe(viewLifecycleOwner) { msgs ->
            // Only signal "Receiving Message" when a new message actually arrives
            // (list grows), not on initial subscription emit
            val prev = previousMessageCount
            previousMessageCount = msgs.size
            if (msgs.size > prev) viewModel.onMessageReceived()
        }
    }

    private var previousMessageCount = 0

    private fun showDeviceDetail(state: DeviceState) {
        DeviceDetailBottomSheet.newInstance(state.device.id)
            .show(childFragmentManager, "device_detail")
    }

    // ── OSMDroid lifecycle ────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        binding.mapView.onDetach()
        _binding = null
        super.onDestroyView()
    }
}
