package com.tvonnet.debridxtreamiptv.ui.trailer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.tvonnet.debridxtreamiptv.R
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import android.widget.TextView
import com.tvonnet.debridxtreamiptv.BuildConfig

class TrailerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loading: View
    private lateinit var closeButton: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var overlayMessage: TextView
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var youtubeId: String? = null
    private var candidateUrls: List<String> = emptyList()
    private var candidateIndex: Int = -1
    private var isIframeMode: Boolean = false
    private var pendingAutoPlay: Boolean = false
    private var playbackStarted: Boolean = false
    private var loadToken: Int = 0
    private var playbackWatchdog: Runnable? = null
    private var isDestroyedActivity: Boolean = false
    private var externalFallbackAttempted: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trailer)

        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "TrailerActivity",
                "onCreate package=$packageName taskId=$taskId component=${intent.component}"
            )
        }

        webView = findViewById(R.id.webview_trailer)
        loading = findViewById(R.id.pb_loading)
        closeButton = findViewById(R.id.btn_close)
        fullscreenContainer = findViewById(R.id.fullscreen_container)
        overlayMessage = findViewById(R.id.tv_overlay_message)

        closeButton.setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack() else finish()
                }
            }
        )

        configureWebView(webView)
        webView.post {
            webView.post {
                webView.requestFocus()
            }
        }

        overlayMessage.visibility = View.GONE

        val parsedTrailer = TrailerValueParser.parse(intent.getStringExtra(EXTRA_TRAILER_VALUE))
        if (parsedTrailer == null) {
            loading.visibility = View.GONE
            showOverlay("Trailer unavailable on this device.")
            webView.postDelayed({ finish() }, INVALID_TRAILER_DISMISS_DELAY_MS)
            return
        }

        youtubeId = parsedTrailer.youtubeId
        candidateUrls = buildYoutubeCandidates(parsedTrailer.youtubeId)
        candidateIndex = -1
        pendingAutoPlay = true
        loadYoutubeIframe(parsedTrailer.youtubeId)
    }

    @Suppress("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // Some YouTube embeds require app identity via Referer/origin.
            // Keep a stable TV UA, but do not rely on YouTube watch-page redirects.
            userAgentString =
                "Mozilla/5.0 (Linux; Android 9; AFTKM) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Silk/122.0 like Chrome/122.0.0.0 Safari/537.36 DebridXtreamIPTV/Trailer"
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.addJavascriptInterface(YoutubeBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                loading.visibility = if (newProgress < 95) View.VISIBLE else View.GONE
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (fullscreenView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                fullscreenContainer.addView(
                    view,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
            }

            override fun onHideCustomView() {
                val view = fullscreenView ?: return
                fullscreenContainer.removeView(view)
                fullscreenView = null
                fullscreenContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                // Keep navigation inside the same WebView (avoid launching external browser).
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = webView
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme.orEmpty()

                if (scheme.equals("intent", true)) {
                    val fallback = uri.getQueryParameter("browser_fallback_url")
                    if (!fallback.isNullOrBlank()) {
                        webView.post { webView.loadUrl(fallback) }
                        return true
                    }
                    return true
                }

                if (scheme.equals("vnd.youtube", true)) {
                    val id = uri.schemeSpecificPart
                    if (!id.isNullOrBlank()) {
                        webView.post { openExternalYoutube(id) }
                        return true
                    }
                }

                val host = uri.host.orEmpty()
                val isYoutube = host.contains("youtube.com", true) || host.contains("youtu.be", true)
                if (isYoutube) return false

                // If a link navigates away from YouTube, open it externally.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: Exception) {
                    false
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return
                loading.visibility = View.GONE
                maybeTryNextCandidate()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Detect common YouTube block pages ("Error 153/150") and try alternate URLs.
                val hasYoutube = youtubeId != null
                if (!hasYoutube || view == null) return
                if (isIframeMode) return

                // Try to start playback on watch pages (useful on TV/FireOS where autoplay isn't reliable).
                view.evaluateJavascript(
                    """
                        (function() {
                          try {
                            var btn = document.querySelector('.ytp-large-play-button, button.ytp-large-play-button, button.ytp-play-button');
                            if (btn) { btn.click(); }
                            var v = document.querySelector('video');
                            if (v && v.play) { v.play().catch(function(){}); }
                          } catch (e) {}
                          return true;
                        })();
                    """.trimIndent(),
                    null
                )

                // If YouTube TV lands on home instead of the video, try next candidate.
                val id = youtubeId ?: return
                view.evaluateJavascript(
                    "(function(){try{return (location && location.href) ? location.href : ''}catch(e){return ''}})();"
                ) { href ->
                    val current = href?.lowercase().orEmpty()
                    val idLower = id.lowercase()
                    val isWatch = current.contains("watch") && current.contains("v=") && current.contains(idLower)
                    val isEmbed = current.contains("/embed/") && current.contains(idLower)
                    val looksLikeHome =
                        current.endsWith("youtube.com/") ||
                            current.endsWith("m.youtube.com/") ||
                            (current.contains("/tv") && !current.contains("watch"))
                    if (!isWatch && !isEmbed && looksLikeHome) {
                        android.util.Log.w("TrailerActivity", "YouTube landed on home; trying fallback URL")
                        maybeTryNextCandidate()
                    }
                }
                view.evaluateJavascript(
                    "(function(){return (document.body && document.body.innerText) ? document.body.innerText : ''})()"
                ) { text ->
                    val body = text?.lowercase().orEmpty()
                    val blocked =
                        body.contains("error 153") ||
                            body.contains("error 150") ||
                            body.contains("video unavailable") ||
                            body.contains("playback on other websites has been disabled") ||
                            body.contains("an error occurred") && body.contains("try again later")
                    if (blocked) {
                        android.util.Log.w("TrailerActivity", "YouTube blocked in WebView; trying fallback URL")
                        maybeTryNextCandidate()
                    }
                }
            }
        }
    }

    private fun loadYoutubeIframe(id: String) {
        cancelPlaybackWatchdog()
        loadToken++
        playbackStarted = false
        isIframeMode = true
        overlayMessage.visibility = View.GONE
        val safeId = id.trim().replace("'", "")

        loading.visibility = View.VISIBLE
        webView.loadDataWithBaseURL(APP_WEB_ORIGIN, buildIframeHtml(safeId), "text/html", "utf-8", null)
        schedulePlaybackWatchdog(loadToken)
    }

    private fun buildIframeHtml(safeId: String): String {
        return IFRAME_HTML_TEMPLATE
            .replace("__VIDEO_ID__", safeId)
            .replace("__ORIGIN__", APP_WEB_ORIGIN)
    }

    private fun buildYoutubeCandidates(id: String): List<String> {
        val safeId = id.trim()
        val encodedOrigin = Uri.encode(APP_WEB_ORIGIN)
        return listOf(
            "https://www.youtube.com/embed/$safeId?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=$encodedOrigin&widget_referrer=$encodedOrigin",
            "https://www.youtube-nocookie.com/embed/$safeId?autoplay=1&playsinline=1&rel=0&enablejsapi=1&origin=$encodedOrigin&widget_referrer=$encodedOrigin"
        )
    }

    private fun showOverlay(message: String) {
        overlayMessage.text = message
        overlayMessage.visibility = View.VISIBLE
    }

    private fun hideOverlay() {
        overlayMessage.visibility = View.GONE
        webView.post {
            webView.post {
                webView.requestFocus()
            }
        }
    }

    private fun attemptPlay() {
        val id = youtubeId
        if (id == null) return
        if (isDestroyedActivity) return

        if (isIframeMode) {
            webView.evaluateJavascript(
                "try{ if (typeof playTrailer === 'function') { playTrailer(); true; } else { false; } }catch(e){false;}",
                null
            )
            // If autoplay is blocked, prompt for OK/Enter (still no visible buttons).
            webView.postDelayed(
                {
                    webView.evaluateJavascript(
                        "try{ if (typeof isPlaying==='function') { isPlaying(); } else { false; } }catch(e){false;}"
                    ) { playing ->
                        if (playing?.contains("true") == true) {
                            playbackStarted = true
                            cancelPlaybackWatchdog()
                            hideOverlay()
                        } else {
                            showOverlay("Press OK to play trailer")
                        }
                    }
                },
                1200
            )
            return
        }

        // Watch page mode: try to click play and start the <video> element.
        webView.evaluateJavascript(
            """
                (function() {
                  try {
                    var btn = document.querySelector('.ytp-large-play-button, button.ytp-large-play-button, button.ytp-play-button');
                    if (btn) { btn.click(); }
                    var v = document.querySelector('video');
                    if (v && v.play) { v.play().catch(function(){}); }
                  } catch (e) {}
                  return true;
                })();
            """.trimIndent(),
            null
        )

        // If playback actually starts, hide the overlay.
        webView.postDelayed(
            {
                webView.evaluateJavascript(
                    "(function(){try{var v=document.querySelector('video');return (!!v && v.paused===false);}catch(e){return false;}})();"
                ) { playing ->
                    if (playing?.contains("true") == true) {
                        playbackStarted = true
                        cancelPlaybackWatchdog()
                        hideOverlay()
                    }
                }
            },
            800
        )
    }

    private fun maybeTryNextCandidate() {
        if (isDestroyedActivity || playbackStarted) return
        cancelPlaybackWatchdog()
        if (youtubeId == null || candidateUrls.isEmpty()) return
        if (candidateIndex + 1 >= candidateUrls.size) {
            // Exhausted WebView embeds. Do not keep the YouTube error page onscreen.
            if (openExternalYoutube(youtubeId)) return
            loading.visibility = View.GONE
            showOverlay("Trailer unavailable on this device.")
            return
        }
        candidateIndex++
        val next = candidateUrls[candidateIndex]
        isIframeMode = false
        webView.post {
            loading.visibility = View.VISIBLE
            overlayMessage.visibility = View.GONE
            loadToken++
            playbackStarted = false
            webView.loadUrl(next, mapOf("Referer" to APP_WEB_ORIGIN))
            schedulePlaybackWatchdog(loadToken)
        }
    }

    private fun openExternalYoutube(id: String?): Boolean {
        val safeId = id?.trim().orEmpty()
        if (safeId.isBlank() || externalFallbackAttempted || isDestroyedActivity) return false
        externalFallbackAttempted = true
        loading.visibility = View.GONE
        showOverlay("Opening trailer in YouTube...")
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$safeId")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            finish()
            true
        } catch (e: Exception) {
            android.util.Log.w("TrailerActivity", "External YouTube fallback unavailable", e)
            false
        }
    }

    private fun schedulePlaybackWatchdog(token: Int) {
        cancelPlaybackWatchdog()
        playbackWatchdog = Runnable {
            if (!isDestroyedActivity && token == loadToken && !playbackStarted) {
                maybeTryNextCandidate()
            }
        }
        webView.postDelayed(playbackWatchdog, PLAYBACK_WATCHDOG_DELAY_MS)
    }

    private fun cancelPlaybackWatchdog() {
        playbackWatchdog?.let { webView.removeCallbacks(it) }
        playbackWatchdog = null
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        isDestroyedActivity = true
        cancelPlaybackWatchdog()
        super.onDestroy()
        runCatching {
            fullscreenView?.let { fullscreenContainer.removeView(it) }
            fullscreenView = null
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    private inner class YoutubeBridge {
        @JavascriptInterface
        fun onReady() {
            runOnUiThread {
                loading.visibility = View.GONE
                if (pendingAutoPlay) {
                    pendingAutoPlay = false
                    attemptPlay()
                }
            }
        }

        @JavascriptInterface
        fun onStateChange(state: Int) {
            // YT.PlayerState.PLAYING = 1, PAUSED = 2, ENDED = 0
            runOnUiThread {
                when (state) {
                    1 -> {
                        playbackStarted = true
                        cancelPlaybackWatchdog()
                        loading.visibility = View.GONE
                        hideOverlay()
                    }
                    0 -> Unit
                    2 -> Unit
                }
            }
        }

        @JavascriptInterface
        fun onError(code: Int) {
            runOnUiThread {
                android.util.Log.w("TrailerActivity", "YouTube IFrame error=$code; trying fallback URL")
                loading.visibility = View.GONE
                // Common embed-block errors: 101/150; treat anything here as a fallback signal.
                maybeTryNextCandidate()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // Treat OK/Enter as the user gesture to start playback if autoplay is blocked.
                    attemptPlay()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val EXTRA_TRAILER_VALUE = "extra_trailer_value"
        private const val APP_WEB_ORIGIN = "https://debridxtream.local"
        private const val PLAYBACK_WATCHDOG_DELAY_MS = 10000L
        private const val INVALID_TRAILER_DISMISS_DELAY_MS = 1500L

        // The IFrame-API page loadYoutubeIframe renders; __VIDEO_ID__ / __ORIGIN__ are
        // substituted in buildIframeHtml. Body is the same markup that used to live inline.
        private val IFRAME_HTML_TEMPLATE = """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0" />
                <meta name="referrer" content="strict-origin-when-cross-origin" />
                <style>
                  html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
                  #player { position:absolute; inset:0; width:100%; height:100%; }
                </style>
              </head>
              <body>
                <div id="player"></div>
                <script>
                  var __player = null;
                  function onYouTubeIframeAPIReady() {
                    try {
                      __player = new YT.Player('player', {
                        height: '100%',
                        width: '100%',
                        videoId: '__VIDEO_ID__',
                        playerVars: {
                          autoplay: 1,
                          controls: 1,
                          enablejsapi: 1,
                          fs: 1,
                          rel: 0,
                          playsinline: 1,
                          modestbranding: 1,
                          origin: '__ORIGIN__',
                          widget_referrer: '__ORIGIN__'
                        },
                        events: {
                          'onReady': function(e) {
                            try { AndroidBridge.onReady(); } catch (err) {}
                          },
                          'onStateChange': function(e) {
                            try { AndroidBridge.onStateChange(e.data); } catch (err) {}
                          },
                          'onError': function(e) {
                            try { AndroidBridge.onError(e.data); } catch (err) {}
                          }
                        }
                      });
                    } catch (err) {
                      try { AndroidBridge.onError(999); } catch (e) {}
                    }
                  }
                  function playTrailer() {
                    try {
                      if (__player && __player.playVideo) { __player.playVideo(); return true; }
                    } catch (e) {}
                    return false;
                  }
                  function isPlaying() {
                    try {
                      if (__player && __player.getPlayerState) { return __player.getPlayerState() === 1; }
                    } catch (e) {}
                    return false;
                  }
                  (function(){
                    var tag = document.createElement('script');
                    tag.src = 'https://www.youtube.com/iframe_api';
                    document.head.appendChild(tag);
                  })();
                </script>
              </body>
            </html>
            """.trimIndent()

        fun createIntent(context: Context, trailerValue: String): Intent {
            return Intent(context, TrailerActivity::class.java).apply {
                putExtra(EXTRA_TRAILER_VALUE, trailerValue)
            }
        }
    }
}
