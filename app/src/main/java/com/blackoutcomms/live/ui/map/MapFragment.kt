package com.blackoutcomms.live.ui.map

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.blackoutcomms.live.R
import com.blackoutcomms.live.databinding.FragmentMapBinding
import com.blackoutcomms.live.model.DeviceState
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import java.io.File

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MapViewModel by viewModels()

    private var deviceOverlay: DeviceOverlay? = null
    private var messageAdapter: MessageAdapter? = null

    private var mapInitialised = false
    private var lastZoom = 0.0
    private var downloadWarningShown = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Configuration.getInstance().apply {
            userAgentValue = requireContext().packageName
            // Use filesDir (permanent private app storage) rather than cacheDir.
            // cacheDir is a temporary directory the OS can wipe at any time when
            // storage is low, or the user clears the app cache in Settings.
            // filesDir is only removed when the app is fully uninstalled.
            osmdroidBasePath  = File(requireContext().filesDir, "osmdroid")
            osmdroidTileCache = File(requireContext().filesDir, "osmdroid/tiles")
            // No expiry: once a tile is downloaded it is kept indefinitely.
            // Set to a positive value (milliseconds) if you want staleness eviction.
            tileDownloadMaxQueueSize = 40          // parallel download slots
            expirationOverrideDuration = -1L       // -1 = never expire cached tiles
        }

        setupMap()
        setupMessagePanel()
        setupFilterControls()
        setupObservers()
    }

    // ── Map setup ─────────────────────────────────────────────────────────────

    private fun setupMap() {
        binding.mapView.apply {
            // ESRI World Topo Map — muted earth tones, contour lines, terrain shading.
            // Completely free, no API key required.
            // ESRI uses {z}/{y}/{x} tile order (y before x), so we subclass
            // OnlineTileSourceBase to build the URL correctly.
            val esriTopo = object : OnlineTileSourceBase(
                "ESRI.WorldTopoMap", 0, 19, 256, "",
                arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Topo_Map/MapServer/tile/"),
                "© ESRI, HERE, Garmin, FAO, NOAA, USGS"
            ) {
                override fun getTileURLString(pMapTileIndex: Long): String {
                    val zoom = MapTileIndex.getZoom(pMapTileIndex)
                    val x    = MapTileIndex.getX(pMapTileIndex)
                    val y    = MapTileIndex.getY(pMapTileIndex)
                    return "${baseUrl}${zoom}/${y}/${x}"
                }
            }
            setTileSource(esriTopo)
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
    }

    private fun showDownloadWarning() {
        AlertDialog.Builder(requireContext())
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
        // Ensure the filter bar is visible on startup
        binding.bottomFilterBar.visibility = View.VISIBLE

        // Max Age popup
        binding.btnMaxAge.setOnClickListener {
            val options = MapViewModel.MaxAge.values()
            val labels  = options.map { it.label }.toTypedArray()
            val current = viewModel.selectedMaxAge.value ?: MapViewModel.MaxAge.ALL_TIME
            val checkedIdx = options.indexOf(current)

            AlertDialog.Builder(requireContext())
                .setTitle("Maximum Age")
                .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                    viewModel.setMaxAge(options[which])
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Device picker
        binding.btnDevices.setOnClickListener {
            // Only show if we have devices to pick from
            val states = viewModel.allDeviceStates.value ?: emptyMap()
            if (states.isEmpty()) {
                AlertDialog.Builder(requireContext())
                    .setMessage("No devices have been received yet.")
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }
            DevicePickerDialog().show(childFragmentManager, DevicePickerDialog.TAG)
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

        // Dismiss messages
        binding.btnDismissMessages.setOnClickListener {
            binding.messagePanel.visibility = View.GONE
            binding.bottomFilterBar.visibility = View.VISIBLE
        }
    }

    // ── Observers ─────────────────────────────────────────────────────────────

    private fun setupMessagePanel() {
        messageAdapter = MessageAdapter(requireContext())
        binding.recyclerMessages.adapter = messageAdapter
    }

    private fun setupObservers() {
        // Centre map on self once, create overlay
        viewModel.selfDevice.observe(viewLifecycleOwner) { self ->
            self ?: return@observe
            val lat = self.lat.toDoubleOrNull() ?: return@observe
            val lon = self.lon.toDoubleOrNull() ?: return@observe

            if (!mapInitialised) {
                binding.mapView.controller.setCenter(GeoPoint(lat, lon))
                mapInitialised = true
                deviceOverlay = DeviceOverlay(requireContext(), self.id) { state ->
                    showDeviceDetail(state)
                }
                binding.mapView.overlays.add(deviceOverlay)
            }
        }

        // Filtered device states → overlay
        viewModel.filteredDeviceStates.observe(viewLifecycleOwner) { states ->
            deviceOverlay?.deviceStates = states
            binding.mapView.invalidate()
        }

        // Graph data → overlay
        viewModel.graphData.observe(viewLifecycleOwner) { graph ->
            deviceOverlay?.graphData = graph
            binding.mapView.invalidate()
        }

        // Max age label on button
        viewModel.selectedMaxAge.observe(viewLifecycleOwner) { age ->
            binding.btnMaxAge.text = age.label
        }

        // Hidden devices count on button
        viewModel.hiddenDeviceIds.observe(viewLifecycleOwner) { hidden ->
            val total = viewModel.allDeviceStates.value?.size ?: 0
            val visible = total - hidden.size
            binding.btnDevices.text = when {
                hidden.isEmpty() -> "Devices: All"
                else             -> "Devices: $visible/$total"
            }
        }

        // Also update device button label when total device count changes
        viewModel.allDeviceStates.observe(viewLifecycleOwner) { all ->
            val hidden = viewModel.hiddenDeviceIds.value ?: emptySet()
            val visible = all.size - hidden.size
            binding.btnDevices.text = when {
                hidden.isEmpty() -> "Devices: All"
                else             -> "Devices: $visible/${all.size}"
            }
        }

        // Messages
        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            if (messages.isNotEmpty()) {
                val selfId = viewModel.selfDevice.value?.id ?: ""
                messageAdapter?.submitList(messages, selfId)
                binding.messagePanel.visibility = View.VISIBLE
                binding.bottomFilterBar.visibility = View.GONE
            } else {
                binding.messagePanel.visibility = View.GONE
                binding.bottomFilterBar.visibility = View.VISIBLE
            }
        }
    }

    private fun showDeviceDetail(state: DeviceState) {
        DeviceDetailBottomSheet.newInstance(state.device.id)
            .show(childFragmentManager, "device_detail")
    }

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
