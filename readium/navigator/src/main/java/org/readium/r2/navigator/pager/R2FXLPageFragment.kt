/*
 * Module: r2-navigator-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann, Mostapha Idoubihi, Paul Stoica
 *
 * Copyright (c) 2018. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.navigator.pager

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.webkit.WebViewClientCompat
import org.readium.r2.navigator.R2BasicWebView
import org.readium.r2.navigator.databinding.ViewpagerFragmentEpubFxlBinding
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubNavigatorViewModel
import org.readium.r2.navigator.epub.fxl.R2FXLLayout
import org.readium.r2.navigator.epub.fxl.R2FXLOnDoubleTapListener
import java.lang.Math.abs
import java.lang.Math.round
import java.net.URI
import kotlin.math.roundToInt

class R2FXLPageFragment : Fragment() {

    private val firstResourceUrl: String?
        get() = requireArguments().getString("firstUrl")

    private val secondResourceUrl: String?
        get() = requireArguments().getString("secondUrl")

    private var webViews = mutableListOf<R2BasicWebView>()

    private var _binding: ViewpagerFragmentEpubFxlBinding? = null
    private val binding get() = _binding!!

    private val navigator: EpubNavigatorFragment?
        get() = parentFragment as? EpubNavigatorFragment

    private var scaleValue = 1.0f

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = ViewpagerFragmentEpubFxlBinding.inflate(inflater, container, false)
        val view: View = binding.root
        view.setPadding(0, 0, 0, 0)
        val r2FXLLayout = binding.r2FXLLayout
        val webview = binding.webView

        secondResourceUrl?.let {
            setupDoubleWebView(r2FXLLayout, webview, firstResourceUrl, secondResourceUrl)
            return view
        }?:run {
            setupWebView(r2FXLLayout, webview, firstResourceUrl)
            return view
        }
    }

    override fun onDetach() {
        super.onDetach()

        // Prevent the web views from leaking when their parent is detached.
        // See https://stackoverflow.com/a/19391512/1474476
        for (wv in webViews) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.removeAllViews()
            wv.destroy()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(viewport: ConstraintLayout, webView: R2BasicWebView, resourceUrl: String?) {
        webViews.add(webView)
        navigator?.let {
            webView.listener = it.webViewListener
        }

        webView.useLegacySettings = viewModel.useLegacySettings
        webView.settings.javaScriptEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        // If we don't explicitly override the [textZoom], it will be set by Android's
        // accessibility font size system setting which breaks the layout of some fixed layouts.
        // See https://github.com/readium/kotlin-toolkit/issues/76
        webView.settings.textZoom = 100

        webView.setPadding(0, 0, 0, 0)
        webView.addJavascriptInterface(webView, "Android")
        webView.setScaleGestureDetectorForScale()

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                (webView as? R2BasicWebView)?.shouldOverrideUrlLoading(request.url) ?: false

            // prevent favicon.ico to be loaded, this was causing NullPointerException in NanoHttp
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && request.url.path?.endsWith("/favicon.ico") == true) {
                    try {
                        return WebResourceResponse("image/png", null, null)
                    } catch (e: Exception) {
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url:String) {
                val dpRatio = context!!.resources.displayMetrics.density
                resourceUrl?.let {
                    val w = view.width
                    val h = view.height
                    webView.evaluateJavascript("setLayout(${w/dpRatio},${h/dpRatio});document.getElementById('page-center').src = '${it}';") {
                    }
                }
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                webView.scaleValue = newScale
            }
        }
        webView.isHapticFeedbackEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener {
            true
        }
        // Get base server for assets
        resourceUrl?.let {
          val url = URI(resourceUrl)
              url.scheme?.let {
                  webView.loadUrl("${url.scheme}://${url.host}:${url.port}/assets/html/fxl-spread.html")
              }
        }
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupDoubleWebView(viewport: ConstraintLayout, webView: R2BasicWebView, leftUrl: String?, rightUrl: String?) {
        webViews.add(webView)
        navigator?.let {
            webView.listener = it.webViewListener
        }

        webView.settings.javaScriptEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        webView.setPadding(0, 0, 0, 0)
        webView.addJavascriptInterface(webView, "Android")

        webView.webViewClient = object : WebViewClientCompat() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                (webView as? R2BasicWebView)?.shouldOverrideUrlLoading(request.url) ?: false

            // prevent favicon.ico to be loaded, this was causing NullPointerException in NanoHttp
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame && request.url.path?.endsWith("/favicon.ico") == true) {
                    try {
                        return WebResourceResponse("image/png", null, null)
                    } catch (e: Exception) {
                    }
                }
                return null
            }

            override fun onPageFinished(view: WebView, url:String) {
                val dpRatio = context!!.resources.displayMetrics.density

                leftUrl?.let {
                    val w = view.width/2
                    val h = viewport.height
                    webView.evaluateJavascript("setLayout(${w / dpRatio},${h / dpRatio});document.getElementById('page-left').src = '${it}';") {
                    }
                }

                rightUrl?.let {
                    val w = view.width/2
                    val h = viewport.height
                    webView.evaluateJavascript("setLayout(${w / dpRatio},${h / dpRatio});document.getElementById('page-right').src = '${it}';") {
                    }
                }
            }
        }

        webView.isHapticFeedbackEnabled = false
        webView.isLongClickable = false
        webView.setOnLongClickListener {
            true
        }
        // Get base server for assets
        if (leftUrl != null && leftUrl.isNotEmpty()) {
            val url = URI(leftUrl)
            url.scheme?.let {
                webView.loadUrl("${url.scheme}://${url.host}:${url.port}/assets/html/fxl-spread.html")
            }
        }
        else if (rightUrl != null && rightUrl.isNotEmpty()) {
            val url = URI(rightUrl)
            url.scheme?.let {
                webView.loadUrl("${url.scheme}://${url.host}:${url.port}/assets/html/fxl-spread.html")
            }
        }
    }

    companion object {

        fun newInstance(url: String?, url2: String? = null): R2FXLPageFragment =
            R2FXLPageFragment().apply {
                arguments = Bundle().apply {
                    putString("firstUrl", url)
                    putString("secondUrl", url2)
                }
            }
    }
}
