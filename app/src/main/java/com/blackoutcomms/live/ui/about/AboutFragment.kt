package com.blackoutcomms.live.ui.about

import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.*
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import com.blackoutcomms.live.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.imgLogo.setImageResource(com.blackoutcomms.live.R.drawable.blackout_comms_logo)

        binding.tvAbout1.text = "Blackout Comms Live is a graphical tracking and monitoring application designed for use with Blackout Comms mesh clusters. It operates fully offline, displaying the real-time state of your cluster — up to 90 devices — as received through a connected Blackout Comms device via Bluetooth Low Energy (BLE)."

        binding.tvAbout2.text = "The app shows device locations on a cached map, displays mesh graph relationships, battery levels, relay states, neighbour connections, and in-cluster messages. Map tiles are downloaded and stored locally for use when no internet connection is available, making the app fully functional in remote and off-grid environments."

        val linkHtml = "<a href=\"https://chatters.io\">Visit chatters.io</a>"
        binding.tvWebsite.text = HtmlCompat.fromHtml(linkHtml, HtmlCompat.FROM_HTML_MODE_LEGACY)
        binding.tvWebsite.movementMethod = LinkMovementMethod.getInstance()

        // Show bottom image — drop about_bottom_image.png into res/drawable/ to replace
        // the placeholder. The ImageView is always shown so the PNG appears automatically.
        binding.imgAboutBottom.setImageResource(com.blackoutcomms.live.R.drawable.about_bottom_image)
        binding.imgAboutBottom.visibility = android.view.View.VISIBLE
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
