package com.blackoutcomms.live.ui.map

import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.blackoutcomms.live.R
import com.blackoutcomms.live.databinding.FragmentMapBinding
import com.blackoutcomms.live.model.DeviceState
import com.blackoutcomms.live.util.IconResolver
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
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
            osmdroidBasePath = File(requireContext().cacheDir, "osmdroid")
            osmdroidTileCache = File(requireContext().cacheDir, "osmdroid/tiles")
        }

        setupMap()
        setupMessagePanel()
        setupObservers()

        binding.checkboxMeshGraph.setOnCheckedChangeListener { _, checked ->
            deviceOverlay?.showMeshGraph = checked
            binding.mapView.invalidate()
        }

        binding.btnDismissMessages.setOnClickListener {
            binding.messagePanel.visibility = View.GONE
        }
    }

    private fun setupMap() {
        binding.mapView.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            setMultiTouchControls(true)
            controller.setZoom(14.0)

            // Warn user before very deep zoom (lots of tiles to download)
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
            .setPositiveButton("Download") { _, _ ->
                // Continue — tiles download automatically
            }
            .setNegativeButton("Zoom Back Out") { _, _ ->
                binding.mapView.controller.setZoom(16.0)
                downloadWarningShown = false
            }
            .show()
    }

    private fun setupMessagePanel() {
        messageAdapter = MessageAdapter(requireContext())
        binding.recyclerMessages.adapter = messageAdapter
    }

    private fun setupObservers() {
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

        viewModel.deviceStates.observe(viewLifecycleOwner) { states ->
            deviceOverlay?.deviceStates = states
            binding.mapView.invalidate()
        }

        viewModel.graphData.observe(viewLifecycleOwner) { graph ->
            deviceOverlay?.graphData = graph
            binding.mapView.invalidate()
        }

        viewModel.messages.observe(viewLifecycleOwner) { messages ->
            if (messages.isNotEmpty()) {
                val selfId = viewModel.selfDevice.value?.id ?: ""
                messageAdapter?.submitList(messages, selfId)
                binding.messagePanel.visibility = View.VISIBLE
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
