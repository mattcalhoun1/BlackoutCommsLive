package com.blackoutcomms.live.ui.help

import android.content.Context
import android.os.Bundle
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.blackoutcomms.live.R
import com.blackoutcomms.live.databinding.FragmentHelpBinding
import com.blackoutcomms.live.util.themedAlertBuilder
import com.google.gson.Gson
import java.io.BufferedReader

data class HelpSection(val id: String, val title: String, val file: String)

class HelpFragment : Fragment() {

    private var _binding: FragmentHelpBinding? = null
    private val binding get() = _binding!!

    private lateinit var webView: WebView
    private var sections: List<HelpSection> = emptyList()

    private var currentSectionId: String = "overview"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHelpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        webView = binding.webView
        setupWebView()

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun showToc() {
                requireActivity().runOnUiThread { showTableOfContents() }
            }
        }, "Android")

        loadIndex()
        setupSearch()
        setupTocButton()
        loadSection("overview") // default
    }

    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Inject back-to-TOC link styling if needed
            }
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
            setSupportZoom(true)
        }
    }

    private fun setupTocButton() {
        binding.btnToc.setOnClickListener {
            showTableOfContents()
        }
    }

    private fun showTableOfContents() {
        val titles = sections.map { it.title }.toTypedArray()   // Only titles

        requireContext().themedAlertBuilder()
            .setTitle("Topics")
            .setItems(titles) { _, which ->
                val section = sections[which]
                currentSectionId = section.id
                loadSection(section.id)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun loadIndex() {
        try {
            val json = requireContext().assets.open("help/index.json").bufferedReader().use(BufferedReader::readText)
            sections = Gson().fromJson(json, HelpIndex::class.java).sections
        } catch (e: Exception) {
            // fallback
        }
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrBlank()) {
                    performSearch(query.trim())
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (newText.isNullOrBlank()) {
                    loadSection(currentSectionId) // return to current page
                }
                return true
            }
        })
    }

    private fun performSearch(query: String) {
        val lowerQuery = query.lowercase()

        val match = sections.find { section ->
            val content = try {
                requireContext().assets.open("help/${section.file}")
                    .bufferedReader().use { it.readText() }.lowercase()
            } catch (e: Exception) { "" }

            section.title.lowercase().contains(lowerQuery) || content.contains(lowerQuery)
        }

        if (match != null) {
            loadSection(match.id)
        } else {
            val noResultsHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { 
                        font-family: sans-serif; 
                        padding: 40px 20px; 
                        line-height: 1.6; 
                        color: #CDD4C0; 
                        background: #1C2526; 
                        text-align: center;
                    }
                    h2 { color: #FF6B6B; }
                    a { 
                        color: #A8C48A; 
                        text-decoration: none; 
                        font-weight: bold;
                    }
                    a:hover { text-decoration: underline; }
                </style>
            </head>
            <body>
                <h2>No results found for "$query"</h2>
                <p>Try different keywords or browse the topics.</p>
                <p><a href="#" onclick="Android.showToc()">← Table of Contents</a></p>
            </body>
            </html>
        """.trimIndent()

            webView.loadDataWithBaseURL("file:///android_asset/help/", noResultsHtml, "text/html", "UTF-8", null)
        }
    }

    private fun markdownToSimpleHtml(md: String, title: String): String {
        var html = md
            .replace(Regex("^# (.*)$", RegexOption.MULTILINE), "<h1>$1</h1>")
            .replace(Regex("^## (.*)$", RegexOption.MULTILINE), "<h2>$1</h2>")
            .replace(Regex("^### (.*)$", RegexOption.MULTILINE), "<h3>$1</h3>")
            .replace(Regex("(?m)^- (.*)$"), "<li>$1</li>")
            .replace("\n", "<br>")

        html = html.replace(Regex("!\\[([^]]*)\\]\\(([^)]+)\\)")) { match ->
            val alt = match.groupValues[1]
            val src = match.groupValues[2]
            """<img src="$src" alt="$alt" style="max-width:100%; height:auto; margin:16px 0; border-radius:8px; box-shadow: 0 4px 8px rgba(0,0,0,0.3);">"""
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
            <style>
                body { 
                    font-family: 'Roboto', sans-serif; 
                    padding: 20px; 
                    line-height: 1.7; 
                    color: #CDD4C0; 
                    background: #1C2526; 
                    font-size: 18px;   /* Base font size */
                }
                h1 { font-size: 28px; color: #A8C48A; margin-top: 0; }
                h2 { font-size: 24px; color: #A8C48A; }
                h3 { font-size: 20px; }
                p, li { font-size: 18px; }
                img { 
                    max-width: 100%; 
                    height: auto; 
                    display: block; 
                    margin: 20px auto; 
                    border-radius: 8px; 
                }
                ul { padding-left: 24px; }
            </style>
        </head>
        <body>
            <h1>$title</h1>
            $html
        </body>
        </html>
    """.trimIndent()
    }

    private fun loadSection(sectionId: String) {
        currentSectionId = sectionId
        val section = sections.find { it.id == sectionId } ?: return

        try {
            val md = requireContext().assets.open("help/${section.file}").bufferedReader().use(BufferedReader::readText)
            val html = markdownToImprovedHtml(md, section.title)
            //webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            webView.loadDataWithBaseURL(
                "file:///android_asset/help/",
                html,
                "text/html",
                "UTF-8",
                null
            )
        } catch (e: Exception) {
            webView.loadData("<h1>Error loading ${section.title}</h1>", "text/html", "UTF-8")
        }
    }

    private fun markdownToImprovedHtml(md: String, title: String): String {
        var html = md.trim()

        // Better paragraph and line break handling
        html = html
            .replace("\r\n", "\n")                    // Normalize line endings
            .replace(Regex("\n\n+"), "</p><p>")       // Paragraphs from double newlines
            .replace(Regex("^# (.*)$", RegexOption.MULTILINE), "<h1>$1</h1>")
            .replace(Regex("^## (.*)$", RegexOption.MULTILINE), "<h2>$1</h2>")
            .replace(Regex("^### (.*)$", RegexOption.MULTILINE), "<h3>$1</h3>")
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
            .replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
            .replace(Regex("(?m)^- (.*)$"), "<li>$1</li>")
            .replace(Regex("(?m)^\\d+\\. (.*)$"), "<li>$1</li>")

        // Wrap loose text in paragraphs and fix lists
        if (!html.contains("<h1>") && !html.contains("<p>")) {
            html = "<p>$html</p>"
        }
        html = html.replace("</li>\n<li>", "</li><li>")   // Clean lists

        // Image support
        html = html.replace(Regex("!\\[([^]]*)\\]\\(([^)]+)\\)")) { match ->
            val alt = match.groupValues[1]
            var src = match.groupValues[2]
            if (!src.startsWith("images/")) src = "images/$src"
            """<img src="$src" alt="$alt" style="max-width:100%; height:auto; margin:20px auto; display:block; border-radius:8px;">"""
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=yes">
            <style>
                body { 
                    font-family: sans-serif; 
                    padding: 20px; 
                    line-height: 1.75; 
                    color: #CDD4C0; 
                    background: #1C2526; 
                    font-size: 18px;
                }
                h1, h2, h3 { color: #A8C48A; margin-top: 28px; }
                p { margin: 16px 0; }
                strong { color: #E8B923; }
                ul, ol { padding-left: 28px; margin: 16px 0; }
                li { margin: 6px 0; }
                img { 
                    max-width: 100%; 
                    height: auto; 
                    display: block; 
                    margin: 24px auto; 
                    border-radius: 8px; 
                }
                .toc-link {
                    display: inline-block;
                    margin: 16px 0;
                    padding: 10px 18px;
                    background: #2A3A2A;
                    color: #A8C48A;
                    text-decoration: none;
                    border-radius: 6px;
                    font-weight: bold;
                }
            </style>
        </head>
        <body>
            $html
        </body>
        </html>
    """.trimIndent()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }


    data class HelpIndex(val sections: List<HelpSection>)
}