package no.lionflow.app.car

import android.annotation.SuppressLint
import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * YouTube on the car screen — only while parked. A WebView limited to YouTube is drawn on the
 * surface Android Auto gives us. As soon as the car moves, the page is destroyed and the screen closes.
 */
class YouTubeScreen(ctx: CarContext, private val guard: ParkGuard) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null
    private var root: FrameLayout? = null
    private var fullscreen: View? = null
    private var pendingUrl = HOME
    private var closing = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycleScope.launch {
            // Re-check every second; leave immediately when the car is no longer parked.
            while (isActive) {
                guard.update()
                if (!guard.parked.value) { close("Video stopped — the car is moving"); break }
                delay(1000)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        try { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) } catch (_: Exception) { }
        main.post { tearDown() }
    }

    private fun close(msg: String) {
        if (closing) return
        closing = true
        main.post { tearDown() }
        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
        screenManager.pop()
    }

    fun search(q: String) {
        val url = "https://m.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
        pendingUrl = url
        main.post { web?.loadUrl(url) }
    }

    override fun onGetTemplate(): Template {
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("Search").setOnClickListener {
                screenManager.push(YouTubeSearchScreen(carContext) { q -> search(q) })
            }.build())
            .addAction(Action.Builder().setTitle("Back").setOnClickListener {
                main.post { web?.let { if (it.canGoBack()) it.goBack() } }
            }.build())
            .addAction(Action.Builder().setTitle("Close").setOnClickListener { close("YouTube closed") }.build())
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    // ---------------- surface ----------------

    override fun onSurfaceAvailable(c: SurfaceContainer) { main.post { setUp(c) } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { main.post { tearDown() } }
    override fun onVisibleAreaChanged(visibleArea: Rect) {
        main.post { root?.setPadding(visibleArea.left, visibleArea.top, (root!!.width - visibleArea.right).coerceAtLeast(0), (root!!.height - visibleArea.bottom).coerceAtLeast(0)) }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        main.post { web?.evaluateJavascript("window.scrollBy(${distanceX.toInt()},${distanceY.toInt()})", null) }
    }

    override fun onClick(x: Float, y: Float) {
        main.post {
            val target: View = fullscreen ?: web ?: return@post
            val loc = IntArray(2); target.getLocationInWindow(loc)
            val lx = x - loc[0]; val ly = y - loc[1]
            val t = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, lx, ly, 0)
            val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, lx, ly, 0)
            target.dispatchTouchEvent(down); target.dispatchTouchEvent(up)
            down.recycle(); up.recycle()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp(c: SurfaceContainer) {
        tearDown()
        if (closing || !guard.parked.value) return
        val surface = c.surface ?: return
        if (c.width <= 0 || c.height <= 0) return
        val vd = carContext.getSystemService(DisplayManager::class.java).createVirtualDisplay(
            "LionFlowYouTube", c.width, c.height, c.dpi, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)
        display = vd
        val p = Presentation(carContext, vd.display)
        val r = FrameLayout(p.context).apply { setBackgroundColor(Color.BLACK) }
        val w = WebView(p.context).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = !allowed(req.url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreen = view
                    r.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    fullscreen?.let { r.removeView(it) }
                    fullscreen = null
                }
            }
        }
        r.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        p.setContentView(r)
        p.show()
        presentation = p; web = w; root = r
        w.loadUrl(pendingUrl)
    }

    private fun tearDown() {
        web?.let { w ->
            pendingUrl = w.url ?: pendingUrl
            try { w.loadUrl("about:blank"); w.stopLoading(); w.destroy() } catch (_: Exception) { }
        }
        web = null; fullscreen = null; root = null
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        display?.release(); display = null
    }

    companion object {
        const val HOME = "https://m.youtube.com/"
        private val HOSTS = listOf("youtube.com", "youtu.be", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com")

        /** Only YouTube (and the Google pages it needs, like the cookie consent page) may open. */
        fun allowed(u: Uri): Boolean {
            if (u.scheme != "https") return false
            val h = u.host ?: return false
            return HOSTS.any { h == it || h.endsWith(".$it") }
        }
    }
}

class YouTubeSearchScreen(ctx: CarContext, private val onSearch: (String) -> Unit) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) onSearch(searchText.trim())
                screenManager.pop()
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search YouTube")
            .setShowKeyboardByDefault(true)
            .build()
}
