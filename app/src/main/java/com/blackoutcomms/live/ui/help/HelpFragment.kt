package com.blackoutcomms.live.ui.help

import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.blackoutcomms.live.databinding.FragmentHelpBinding

class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    companion object {
        private const val HELP_URL = "https://chatters.io/using-blackout-comms-live"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupWebView()
        setupRefreshButton()

        if (savedInstanceState != null) {
            // Restore WebView state on rotation rather than re-loading
            binding.webView.restoreState(savedInstanceState)
        } else {
            loadHelp(forceRefresh = false)
        }
    }

    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled      = true
                domStorageEnabled      = true
                loadWithOverviewMode   = true
                useWideViewPort        = true
                builtInZoomControls    = true
                displayZoomControls    = false

                // LOAD_DEFAULT: use cache if fresh per Cache-Control headers;
                // fall back to network if stale. If network is unreachable,
                // Android will automatically serve from cache regardless of age.
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            webViewClient = object : WebViewClient() {
                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: WebResourceError
                ) {
                    // Only show the error overlay for the main page load, not sub-resources
                    if (request.isForMainFrame) {
                        binding.progressBar.visibility = View.GONE
                        binding.errorGroup.visibility  = View.VISIBLE
                    }
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.errorGroup.visibility  = View.GONE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    binding.progressBar.visibility = View.GONE
                    binding.swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    private fun setupRefreshButton() {
        // Pull-to-refresh forces a network fetch, bypassing cache
        binding.swipeRefresh.setOnRefreshListener {
            loadHelp(forceRefresh = true)
        }
        binding.swipeRefresh.setColorSchemeResources(
            android.R.color.holo_green_light
        )

        // Retry button shown when the page fails to load
        binding.btnRetry.setOnClickListener {
            binding.errorGroup.visibility = View.GONE
            loadHelp(forceRefresh = false)
        }
    }

    /**
     * Loads the help URL.
     *
     * [forceRefresh] = false → LOAD_DEFAULT (use cache if fresh, network if stale,
     *                          cache if network unavailable)
     * [forceRefresh] = true  → LOAD_NO_CACHE (always fetch from network; used for
     *                          pull-to-refresh so the user can explicitly get updates)
     */
    private fun loadHelp(forceRefresh: Boolean) {
        binding.webView.settings.cacheMode = if (forceRefresh) {
            WebSettings.LOAD_NO_CACHE
        } else {
            WebSettings.LOAD_DEFAULT
        }
        binding.webView.loadUrl(HELP_URL)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroyView() {
        // Detach from parent before destroying to avoid WebView memory leaks
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        _binding = null
        super.onDestroyView()
    }
}
