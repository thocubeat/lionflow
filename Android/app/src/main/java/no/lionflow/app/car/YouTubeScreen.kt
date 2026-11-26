package no.lionflow.app.car

import no.lionflow.app.ui.l10n.tr

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
/** A video site that can be shown on the car screen while parked. */
class VideoSite(val name: String, val home: String, val hosts: List<String>, val searchFirst: Boolean, val searchUrl: (String) -> String) {
    fun allows(u: Uri): Boolean {
        if (u.scheme != "https") return false
        val h = u.host ?: return false
        return hosts.any { h == it || h.endsWith(".$it") }
    }
    companion object {
        val YouTube = VideoSite("YouTube", "https://m.youtube.com/", listOf("youtube.com", "youtu.be", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com"), true) {
            "https://m.youtube.com/results?search_query=" + URLEncoder.encode(it, "UTF-8")
        }
        val NrkTv = VideoSite("NRK TV", "https://tv.nrk.no/", listOf("nrk.no"), false) {
            "https://tv.nrk.no/sok?q=" + URLEncoder.encode(it, "UTF-8")
        }
    }
}

internal class YouTubeScreen(ctx: CarContext, private val ui: CarUi, private val site: VideoSite = VideoSite.YouTube) : Screen(ctx), SurfaceCallback, DefaultLifecycleObserver {
    private val guard get() = ui.guard
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var web: WebView? = null
    private var root: FrameLayout? = null
    private var fullscreen: View? = null
    private var pendingUrl = site.home
    private var closing = false

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        // The drawn screens let go of the surface, and this screen gets it (and its taps and swipes) until it closes:
        // only one thing at a time can put pictures on it.
        ui.surface.lend(this)
        lifecycleScope.launch {
            // Re-check every second; leave immediately when the car is no longer parked.
            while (isActive) {
                guard.update()
                if (!guard.parked.value) { close("Video stopped — the car is moving"); break }
                delay(1000)
            }
        }
    }

    /** YouTube's start page is empty when nobody is signed in, so go straight to search the first time. */
    private var askedSearch = false
    override fun onStart(owner: LifecycleOwner) {
        if (!site.searchFirst || askedSearch || closing || pendingUrl != site.home) return
        askedSearch = true
        main.post {
            if (!closing) try { screenManager.push(YouTubeSearchScreen(carContext, site.name) { q -> search(q) }) } catch (_: Exception) { }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        closing = true
        try { tearDown() } catch (e: Exception) { ui.log("closing the video", e) }
        // The surface goes back to the drawn screens.
        ui.surface.takeBack(this)
    }

    private fun close(msg: String) {
        if (closing) return
        closing = true
        main.post { tearDown() }
        CarToast.makeText(carContext, tr(msg), CarToast.LENGTH_LONG).show()
        screenManager.pop()
    }

    fun search(q: String) {
        val url = site.searchUrl(q)
        pendingUrl = url
        main.post { web?.loadUrl(url) }
    }

    private fun ytIcon(res: Int) = carContext.icon(res)

    /** Holds the picture back so it matches the car's late audio (see SYNC_JS). */
    private fun injectSync(w: WebView) {
        if (!CarHub.app(carContext).settings.value.carVideoSync) return
        w.evaluateJavascript(SYNC_JS.replace("__DELAY__", "400"), null)
    }

    // ---------------- controls on the left of the video ----------------

    private var controls: android.widget.LinearLayout? = null
    private val controlActions = ArrayList<Pair<View, () -> Unit>>()

    private fun buildControls(ctx: android.content.Context): android.widget.LinearLayout {
        fun dp(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
        controlActions.clear()
        val col = android.widget.LinearLayout(ctx).apply { gravity = android.view.Gravity.CENTER_VERTICAL; orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(8f), 0, 0, 0) }
        fun add(icon: Int, action: () -> Unit) {
            val b = android.widget.ImageView(ctx).apply {
                setImageResource(icon); setColorFilter(Color.WHITE); scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
                background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Color.parseColor("#B3141619")) }
            }
            col.addView(b, android.widget.LinearLayout.LayoutParams(dp(60f), dp(60f)).apply { topMargin = dp(5f); bottomMargin = dp(5f) })
            controlActions += b to action
        }
        add(no.lionflow.app.R.drawable.ic_car_search) { screenManager.push(YouTubeSearchScreen(carContext, site.name) { q -> search(q) }) }
        add(no.lionflow.app.R.drawable.ic_car_back) { web?.let { if (it.canGoBack()) it.goBack() } }
        return col
    }

    /** Taps on the controls are ours; everything else goes to the page. */
    private fun hitControl(x: Float, y: Float): Boolean {
        if (controls?.visibility != View.VISIBLE) return false
        for ((v, action) in controlActions) {
            val loc = IntArray(2); v.getLocationInWindow(loc)
            if (x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height) { action(); return true }
        }
        return false
    }

    override fun onGetTemplate(): Template {
        // Close X, top right.
        // In fullscreen the X is swapped for an empty, do-nothing action (the template needs one).
        val strip = ActionStrip.Builder().addAction(
            if (fullscreen != null) Action.Builder().setIcon(ytIcon(no.lionflow.app.R.drawable.ic_blank)).setOnClickListener { }.build()
            else Action.Builder().setIcon(ytIcon(no.lionflow.app.R.drawable.ic_car_close)).setOnClickListener { close(site.name + " " + tr("closed")) }.build()
        ).build()
        return NavigationTemplate.Builder()
            .setActionStrip(strip)
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
            .build()
    }

    // ---------------- surface ----------------

    override fun onSurfaceAvailable(c: SurfaceContainer) { main.post { if (!closing) try { setUp(c) } catch (e: Exception) { ui.log("starting the video", e) } } }
    override fun onSurfaceDestroyed(c: SurfaceContainer) { main.post { tearDown() } }
    private var visible: Rect? = null
    override fun onVisibleAreaChanged(visibleArea: Rect) { visible = Rect(visibleArea); main.post { applyPadding() } }

    /** The page always fills the whole screen, behind the car's own buttons; only our controls stay in the visible area. */
    private fun applyPadding() {
        root?.setPadding(0, 0, 0, 0)
        val c = controls ?: return
        val v = visible ?: return
        (c.layoutParams as? FrameLayout.LayoutParams)?.let { lp -> lp.leftMargin = v.left; lp.topMargin = v.top; lp.height = v.height(); c.layoutParams = lp }
    }

    // Scrolling is sent as a real finger drag. Shorts only change video on a quick swipe, so there a scroll becomes one flick.
    private var dragDown = 0L
    private var dragX = 0f
    private var dragY = 0f
    private var shortsDy = 0f
    private val endDrag = Runnable {
        val w = web ?: return@Runnable
        if (dragDown != 0L) {
            val e = touchEvent(dragDown, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dragX, dragY, 0)
            try { w.dispatchTouchEvent(e) } finally { e.recycle() }
            dragDown = 0L
        }
    }
    private val endShorts = Runnable {
        val w = web ?: return@Runnable
        val dy = shortsDy; shortsDy = 0f
        if (Math.abs(dy) < 25f || w.height <= 0) return@Runnable
        val x = w.width / 2f
        val from = if (dy > 0) w.height * 0.75f else w.height * 0.25f      // dy > 0: the car wants content to move up = next short
        val to = if (dy > 0) w.height * 0.25f else w.height * 0.75f
        val t = SystemClock.uptimeMillis()
        val steps = 8
        for (i in 0..steps + 1) {
            val action = when (i) { 0 -> MotionEvent.ACTION_DOWN; steps + 1 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
            val yy = from + (to - from) * (i.coerceAtMost(steps) / steps.toFloat())
            main.postDelayed({
                val e = touchEvent(t, t + i * 16L, action, x, yy, 0)
                try { w.dispatchTouchEvent(e) } finally { e.recycle() }
            }, i * 16L)
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        main.post {
            val w = web ?: return@post
            if (w.url?.contains("/shorts") == true) {
                shortsDy += distanceY
                main.removeCallbacks(endShorts)
                main.postDelayed(endShorts, 150)
                return@post
            }
            val now = SystemClock.uptimeMillis()
            main.removeCallbacks(endDrag)
            if (dragDown == 0L) {
                dragDown = now; dragX = w.width / 2f; dragY = w.height / 2f
                val d = touchEvent(now, now, MotionEvent.ACTION_DOWN, dragX, dragY, 0)
                try { w.dispatchTouchEvent(d) } finally { d.recycle() }
            }
            // The car reports how far the content should move; the finger moves the opposite way.
            dragX = (dragX - distanceX).coerceIn(1f, w.width - 1f)
            dragY = (dragY - distanceY).coerceIn(1f, w.height - 1f)
            val m = touchEvent(dragDown, now, MotionEvent.ACTION_MOVE, dragX, dragY, 0)
            try { w.dispatchTouchEvent(m) } finally { m.recycle() }
            main.postDelayed(endDrag, 120)
        }
    }

    override fun onClick(x: Float, y: Float) {
        main.post {
            if (hitControl(x, y)) return@post
            val w = web
            val target: View = fullscreen ?: w ?: return@post
            val loc = IntArray(2); target.getLocationInWindow(loc)
            val lx = x - loc[0]; val ly = y - loc[1]
            if (w == null) { touch(target, lx, ly); return@post }
            // A tap on the progress bar seeks with JavaScript (YouTube's bar ignores synthetic touches), fullscreen or not.
            // Anything else is a normal tap.
            val d = w.resources.displayMetrics.density
            w.evaluateJavascript(SEEK_JS.replace("__X__", (lx / d).toString()).replace("__Y__", (ly / d).toString())) { r ->
                if (r?.contains("seek") != true) touch(target, lx, ly)
            }
        }
    }

    /** A tap as a real finger sends it: down, a little movement, up. */
    /** Synthetic touches must say they come from a touchscreen, or WebView may ignore them (Shorts swipes, the seek bar). */
    private fun touchEvent(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float, meta: Int): MotionEvent =
        MotionEvent.obtain(downTime, eventTime, action, x, y, meta).also { it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN }

    private fun touch(target: View, lx: Float, ly: Float) {
        val t = SystemClock.uptimeMillis()
        fun send(action: Int, dt: Long, dx: Float) {
            main.postDelayed({
                val e = touchEvent(t, t + dt, action, lx + dx, ly, 0)
                try { target.dispatchTouchEvent(e) } finally { e.recycle() }
            }, dt)
        }
        send(MotionEvent.ACTION_DOWN, 0, 0f)
        send(MotionEvent.ACTION_MOVE, 40, 1f)
        send(MotionEvent.ACTION_MOVE, 80, 2f)
        send(MotionEvent.ACTION_UP, 120, 2f)
    }

    private var focus: android.media.AudioFocusRequest? = null
    private fun takeAudio() {
        try {
            val am = carContext.getSystemService(android.media.AudioManager::class.java)
            val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setOnAudioFocusChangeListener { }.build()
            am.requestAudioFocus(req); focus = req
        } catch (_: Exception) { }
    }
    private fun dropAudio() {
        try { focus?.let { carContext.getSystemService(android.media.AudioManager::class.java).abandonAudioFocusRequest(it) } } catch (_: Exception) { }
        focus = null
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
            // YouTube treats the default "wv" WebView agent as a stripped-down embed and often won't play; look like Chrome.
            settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex(" Version/[0-9.]+"), "")
            val cm = android.webkit.CookieManager.getInstance()
            cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean = !site.allows(req.url)
                override fun onPageFinished(view: WebView, url: String?) { injectSync(view) }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { injectSync(view) }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: android.webkit.WebResourceError) {
                    if (req.isForMainFrame) CarToast.makeText(carContext, "${site.name}: ${err.description}", CarToast.LENGTH_LONG).show()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreen = view
                    controls?.visibility = View.GONE
                    applyPadding(); invalidate()
                    r.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                override fun onHideCustomView() {
                    fullscreen?.let { r.removeView(it) }
                    fullscreen = null
                    controls?.visibility = View.VISIBLE
                    applyPadding(); invalidate()
                }
            }
        }
        r.addView(w, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        controls = buildControls(p.context)
        r.addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT, android.view.Gravity.START or android.view.Gravity.TOP))
        p.setContentView(r)
        p.show()
        presentation = p; web = w; root = r
        takeAudio()
        r.post { applyPadding() }
        w.loadUrl(pendingUrl)
    }

    private fun tearDown() {
        web?.let { w ->
            pendingUrl = w.url ?: pendingUrl
            try { w.loadUrl("about:blank"); w.stopLoading(); w.destroy() } catch (_: Exception) { }
        }
        web = null; fullscreen = null; root = null; controls = null
        try { presentation?.dismiss() } catch (_: Exception) { }
        presentation = null
        display?.release(); display = null
        dropAudio()
    }

    companion object {
        const val HOME = "https://m.youtube.com/"

        /** Finds YouTube's progress bar under the tap and seeks the video to that point. Prints "seek" if it did. */
        private val SEEK_JS = """
            (function(x, y) {
              var v = document.querySelector('video');
              if (!v || !(v.duration > 0)) return 'none';
              // A tap on a control button (fullscreen, play/pause, next ...) is never a seek, even if it sits next to the bar.
              var hit = document.elementFromPoint(x, y);
              for (var k = 0, h = hit; h && k < 6; k++, h = h.parentElement) {
                var al = ((h.getAttribute && h.getAttribute('aria-label')) || '').toLowerCase();
                var cl = (typeof h.className === 'string' ? h.className : '').toLowerCase();
                if (h.tagName === 'BUTTON' || (h.getAttribute && h.getAttribute('role') === 'button')) return 'none';
                if (/\b(play|pause|full ?screen|exit full|next|previous|captions?|settings|mute|unmute|volume|autoplay)\b/.test(al) || /fullscreen-button|fullscreen_button/.test(cl)) return 'none';
              }
              function shown(e) { for (var i = 0; e && i < 5; i++, e = e.parentElement) { if (parseFloat(getComputedStyle(e).opacity) < 0.1) return false; } return true; }
              var best = null;
              var els = document.querySelectorAll('[role="slider"], [class*="rogress"]');
              for (var i = 0; i < els.length; i++) {
                var r = els[i].getBoundingClientRect();
                if (r.width < 150 || r.width < window.innerWidth * 0.3) continue;
                if (x < r.left || x > r.right) continue;
                var cy = r.top + r.height / 2;
                if (Math.abs(y - cy) > Math.max(20, r.height)) continue;
                if (!shown(els[i])) continue;
                if (!best || r.width > best.width) best = r;
              }
              if (!best) return 'none';
              var f = Math.min(1, Math.max(0, (x - best.left) / best.width));
              v.currentTime = f * v.duration;
              return 'seek';
            })(__X__, __Y__)
        """.trimIndent()
        /**
         * The car plays the phone's sound about 0.4 s late. The video can't be delayed on its own, so its frames are
         * copied to a canvas and shown 0.4 s late instead, while the real video element stays invisible.
         */
        private val SYNC_JS = """
            (function(delay) {
              if (window.__lfSync || !window.createImageBitmap) return;
              window.__lfSync = true;
              var c = document.createElement('canvas');
              c.style.cssText = 'position:absolute;pointer-events:none;display:none;object-fit:contain;background:#000';
              var ctx = c.getContext('2d');
              var q = [], vid = null, busy = false;
              function clear() { while (q.length) q.shift().b.close(); }
              function stop() { c.style.display = 'none'; if (vid) vid.style.opacity = ''; clear(); }
              function frame(v) {
                if (v !== vid) return;
                if (v.paused || v.ended || !v.videoWidth) { stop(); }
                else {
                  var now = performance.now();
                  if (!busy) { busy = true; createImageBitmap(v).then(function(b) { q.push({t: now, b: b}); busy = false; }, function() { busy = false; }); }
                  if (c.parentNode !== v.parentNode) v.parentNode.insertBefore(c, v.nextSibling);
                  if (c.width !== v.videoWidth) { c.width = v.videoWidth; c.height = v.videoHeight; }
                  c.style.left = v.offsetLeft + 'px'; c.style.top = v.offsetTop + 'px';
                  c.style.width = v.offsetWidth + 'px'; c.style.height = v.offsetHeight + 'px';
                  while (q.length > 1 && q[1].t <= now - delay) q.shift().b.close();
                  if (q.length && q[0].t <= now - delay) { ctx.drawImage(q[0].b, 0, 0, c.width, c.height); c.style.display = 'block'; v.style.opacity = '0'; }
                }
                if (v.requestVideoFrameCallback) v.requestVideoFrameCallback(function() { frame(v); });
                else requestAnimationFrame(function() { frame(v); });
              }
              function attach(v) {
                if (vid === v) return;
                if (vid) stop();
                vid = v; v.addEventListener('seeking', clear); v.addEventListener('pause', stop);
                v.addEventListener('playing', function() { frame(v); });
                if (!v.paused) frame(v);
              }
              // The video may sit inside a web component (NRK's player does that): look in shadow roots too.
              function find(root) {
                var v = root.querySelector('video'); if (v) return v;
                var all = root.querySelectorAll('*');
                for (var i = 0; i < all.length; i++) if (all[i].shadowRoot) { v = find(all[i].shadowRoot); if (v) return v; }
                return null;
              }
              setInterval(function() { var v = find(document); if (v) attach(v); }, 1000);
            })(__DELAY__);
        """.trimIndent()

        private val HOSTS = listOf("youtube.com", "youtu.be", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com")

        /** Only YouTube (and the Google pages it needs, like the cookie consent page) may open. */
        fun allowed(u: Uri): Boolean {
            if (u.scheme != "https") return false
            val h = u.host ?: return false
            return HOSTS.any { h == it || h.endsWith(".$it") }
        }
    }
}

class YouTubeSearchScreen(ctx: CarContext, private val siteName: String = "YouTube", private val onSearch: (String) -> Unit) : Screen(ctx) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(searchText: String) {
                if (searchText.isNotBlank()) onSearch(searchText.trim())
                screenManager.pop()
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(tr("Search {0}").replace("{0}", siteName))
            .setShowKeyboardByDefault(true)
            .build()
}
