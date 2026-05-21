package com.blackoutcomms.live.ui.about

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import android.view.*
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import com.blackoutcomms.live.R
import com.blackoutcomms.live.databinding.FragmentAboutBinding
import com.blackoutcomms.live.service.ConnectionService
import com.blackoutcomms.live.ui.MainActivity

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    // ── 5-tap Easter egg ──────────────────────────────────────────────────────
    private var tapCount = 0
    private val tapHandler = Handler(Looper.getMainLooper())
    private val resetTapRunnable = Runnable { tapCount = 0 }
    private val TAPS_REQUIRED = 5
    private val TAP_RESET_MS  = 2_000L   // reset counter if no tap for 2 seconds

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.imgLogo.setImageResource(R.drawable.blackout_comms_logo)

        binding.tvAbout1.text = "Blackout Comms Live is a graphical tracking and monitoring application designed for use with Blackout Comms mesh clusters. It operates fully offline, displaying the real-time state of your cluster — up to 90 devices — as received through a connected Blackout Comms device via Bluetooth Low Energy (BLE)."

        binding.tvAbout2.text = "The app shows device locations on a cached map, displays mesh graph relationships, battery levels, relay states, neighbour connections, and in-cluster messages. Map tiles are downloaded and stored locally for use when no internet connection is available, making the app fully functional in remote and off-grid environments."

        val linkHtml = "<a href=\"https://chatters.io\">Visit chatters.io</a>"
        binding.tvWebsite.text = HtmlCompat.fromHtml(linkHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
        binding.tvWebsite.movementMethod = LinkMovementMethod.getInstance()

        binding.imgAboutBottom.setImageResource(R.drawable.about_bottom_image)
        binding.imgAboutBottom.visibility = View.VISIBLE

        // ── 5-tap Easter egg on logo ──────────────────────────────────────────
        // Tapping the logo 5 times within 2 seconds activates test mode for
        // the remainder of the session, replaying data from assets/test_data/.
        binding.imgLogo.setOnClickListener {
            if (ConnectionService.testModeActive) {
                Toast.makeText(requireContext(), "Test mode already active", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tapHandler.removeCallbacks(resetTapRunnable)
            tapCount++

            val remaining = TAPS_REQUIRED - tapCount
            if (remaining > 0 && remaining <= 2) {
                // Subtle hint when getting close
                Toast.makeText(requireContext(), "$remaining more...", Toast.LENGTH_SHORT).show()
            }

            if (tapCount >= TAPS_REQUIRED) {
                tapCount = 0
                activateTestMode()
            } else {
                tapHandler.postDelayed(resetTapRunnable, TAP_RESET_MS)
            }
        }
    }

    private fun activateTestMode() {
        val activity = requireActivity() as? MainActivity
        if (activity?.connectionService?.testModeActive == true) {
            Toast.makeText(requireContext(), "Test mode already active", Toast.LENGTH_SHORT).show()
            return
        }
        activity?.connectionService?.activateTestMode()

        // Switch map Maximum Age to "All Time" so all test devices are visible
        // regardless of their timestamps. Use mapViewModel() (activity-scoped) rather
        // than activeMapViewModel() which only works when MapFragment is the active tab.
        activity?.mapViewModel()?.setMaxAge(
            com.blackoutcomms.live.ui.map.MapViewModel.MaxAge.ALL_TIME
        )

        Toast.makeText(
            requireContext(),
            "Test mode activated — replaying test data",
            Toast.LENGTH_LONG
        ).show()
    }

    override fun onDestroyView() {
        tapHandler.removeCallbacks(resetTapRunnable)
        _binding = null
        super.onDestroyView()
    }
}
