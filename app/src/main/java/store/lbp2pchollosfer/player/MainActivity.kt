package store.lbp2pchollosfer.player

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class MainActivity : AppCompatActivity() {

    private val io = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var browser: LinearLayout
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var categoriesView: RecyclerView
    private lateinit var channelsView: RecyclerView
    private lateinit var playerLayer: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var overlay: LinearLayout
    private lateinit var playerTitle: TextView
    private lateinit var playerMsg: TextView
    private lateinit var loginLayer: LinearLayout
    private lateinit var pinDots: TextView
    private lateinit var loginMsg: TextView
    private lateinit var keypad: LinearLayout
    private var pin = ""
    private lateinit var webView: android.webkit.WebView
    private var webMode = true

    private val channels = ArrayList<Channel>()
    private lateinit var catAdapter: CatAdapter
    private lateinit var chAdapter: ChAdapter

    private var player: ExoPlayer? = null
    private var currentIndex = -1
    private var currentGroup: String? = null
    private var loadToken = 0
    private var playToken = 0
    private var attempt = 0
    private var checking = false
    private var playedOnce = false
    private var wdRestarts = 0
    private var stuckTicks = 0
    private var lastPos = -1L

    /** Si un canal se queda congelado, se reconecta solo */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && playerLayer.visibility == View.VISIBLE && !isVod() && p.playWhenReady) {
                val st = p.playbackState
                val pos = p.currentPosition
                if ((st == Player.STATE_BUFFERING || st == Player.STATE_READY) && pos == lastPos) stuckTicks++ else stuckTicks = 0
                lastPos = pos
                val limit = if (playedOnce) 3 else 8
                if (stuckTicks >= limit) {
                    stuckTicks = 0
                    lastPos = -1L
                    if (wdRestarts < 4) {
                        wdRestarts++
                        playedOnce = false
                        showMsg("Reconectando… " + wdRestarts + "/4")
                        startPlayback()
                    } else {
                        showMsg("Canal no disponible.\nPulsa arriba o abajo para cambiar de canal.")
                    }
                }
            } else {
                stuckTicks = 0
            }
            ui.postDelayed(this, 2000)
        }
    }
    private var inSeries = false
    private var prevGroup: String? = null
    private var baseTitle = ""

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        browser = findViewById(R.id.browser)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        categoriesView = findViewById(R.id.categories)
        channelsView = findViewById(R.id.channels)
        playerLayer = findViewById(R.id.playerLayer)
        playerView = findViewById(R.id.player)
        overlay = findViewById(R.id.overlay)
        playerTitle = findViewById(R.id.playerTitle)
        playerMsg = findViewById(R.id.playerMsg)
        loginLayer = findViewById(R.id.loginLayer)
        pinDots = findViewById(R.id.pinDots)
        loginMsg = findViewById(R.id.loginMsg)
        keypad = findViewById(R.id.keypad)
        webView = findViewById(R.id.web)
        setupKeypad()
        Api.token = getSharedPreferences("p2ptv", Context.MODE_PRIVATE).getString("token", null)
        findViewById<View>(R.id.btnLogout).setOnClickListener { showLogin() }

        catAdapter = CatAdapter { item ->
            search.setText("")
            inSeries = false
            currentGroup = item.group
            loadChannels(item.group, null, 30)
        }
        categoriesView.layoutManager = LinearLayoutManager(this)
        categoriesView.adapter = catAdapter

        chAdapter = ChAdapter(channels) { index -> play(index) }
        val wDp = resources.configuration.screenWidthDp
        val span = if (wDp >= 1200) 3 else if (wDp >= 800) 2 else 1
        channelsView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, span)
        channelsView.adapter = chAdapter

        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = v.text.toString().trim()
                hideKeyboard()
                if (q == "#probar") runHealthCheck()
                else if (q.length >= 2) loadChannels(null, q, 4)
                else if (q.isEmpty()) loadChannels(currentGroup, null, 30)
                true
            } else false
        }

        status.setOnClickListener { if (catAdapter.items.isEmpty()) loadCategories() }

        findViewById<View>(R.id.btnClose).setOnClickListener { closePlayer() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { zap(-1) }
        findViewById<View>(R.id.btnNext).setOnClickListener { zap(1) }
        playerLayer.setOnClickListener {
            if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webMode && webView.visibility == View.VISIBLE) {
                    webView.evaluateJavascript("(window.__p2pBack && window.__p2pBack()) ? '1' : '0'") { r ->
                        if (r != "\"1\"") {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                    return
                }
                if (playerLayer.visibility == View.VISIBLE) {
                    closePlayer()
                } else if (inSeries) {
                    inSeries = false
                    currentGroup = prevGroup
                    loadChannels(prevGroup, null, 30)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        ui.postDelayed(watchdog, 2000)
        if (webMode) startWeb() else if (Api.token == null) showLogin() else loadCategories()
    }

    // ── La web, exactamente igual que en el navegador ───────────────────────

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun startWeb() {
        browser.visibility = View.GONE
        loginLayer.visibility = View.GONE
        playerLayer.visibility = View.GONE
        webView.visibility = View.VISIBLE
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "2" }
        s.userAgentString = s.userAgentString + " P2PTVApp/" + ver
        webView.setBackgroundColor(0xFF060B14.toInt())
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onReceivedError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) ui.post { fallbackToNative() }
            }

            override fun shouldOverrideUrlLoading(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
            ): Boolean {
                val u = request.url
                val full = u.toString()
                if (u.scheme == "intent" && full.contains("/app-api/vod/play/")) {
                    val target = full.removePrefix("intent:").substringBefore("#Intent")
                    val title = Regex("S\\.title=([^;]*)").find(full)?.groupValues?.get(1)?.let {
                        try { java.net.URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it }
                    } ?: ""
                    val arr = org.json.JSONArray()
                    arr.put(org.json.JSONObject().put("title", title).put("url", target))
                    WebBridge().playVod(arr.toString(), 0)
                    return true
                }
                if (u.scheme == "http" || u.scheme == "https") {
                    if (u.host == "tv.lbp2pchollosfer.store") return false
                }
                try {
                    val i = if (u.scheme == "intent") android.content.Intent.parseUri(u.toString(), android.content.Intent.URI_INTENT_SCHEME)
                    else android.content.Intent(android.content.Intent.ACTION_VIEW, u)
                    startActivity(i)
                } catch (e: Exception) {
                }
                return true
            }
        }
        webView.webChromeClient = android.webkit.WebChromeClient()
        webView.addJavascriptInterface(WebBridge(), "P2PApp")
        val sp = getSharedPreferences("p2ptv", Context.MODE_PRIVATE)
        if (sp.getString("webv", "") != ver) {
            webView.clearCache(true)
            sp.edit().putString("webv", ver).apply()
        }
        webView.loadUrl("https://tv.lbp2pchollosfer.store/")
        webView.requestFocus()
    }

    /** La web llama a esto para reproducir cine y series con el reproductor de la app */
    inner class WebBridge {
        @android.webkit.JavascriptInterface
        fun playVod(json: String, index: Int) {
            ui.post {
                try {
                    val arr = org.json.JSONArray(json)
                    val list = ArrayList<Channel>()
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        list.add(Channel("vod-" + i, o.optString("title"), "", o.optString("url"), "", null, 0L, 0L, null))
                    }
                    if (list.isEmpty()) return@post
                    channels.clear()
                    channels.addAll(list)
                    chAdapter.notifyDataSetChanged()
                    inSeries = list.size > 1
                    play(index.coerceIn(0, list.size - 1))
                } catch (e: Exception) {
                }
            }
        }
    }

    /** Si la web no carga se muestra un aviso con Reintentar (ya no se abre la versión antigua) */
    private fun fallbackToNative() {
        val html = "<html><body style='background:#060B14;color:#F1F5F9;font-family:sans-serif;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;text-align:center'>" +
            "<h2 style='color:#FBBF24'>Sin conexión</h2><p>No se pudo abrir P2P Chollos Fer.</p>" +
            "<a href='https://tv.lbp2pchollosfer.store/' style='background:#FBBF24;color:#000;padding:14px 32px;border-radius:14px;font-weight:bold;text-decoration:none'>Reintentar</a></body></html>"
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    override fun onPause() {
        super.onPause()
        if (webMode) webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (webMode) webView.onResume()
    }

    // ── Acceso con PIN ──────────────────────────────────────────────────────

    private fun showLogin(message: String = "") {
        Api.token = null
        getSharedPreferences("p2ptv", Context.MODE_PRIVATE).edit().remove("token").apply()
        playToken++
        player?.release()
        player = null
        playerView.player = null
        playerLayer.visibility = View.GONE
        browser.visibility = View.GONE
        loginLayer.visibility = View.VISIBLE
        pin = ""
        updatePinDots()
        loginMsg.text = message
        (keypad.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }

    private fun updatePinDots() {
        pinDots.text = (0 until 6).joinToString(" ") { if (it < pin.length) "●" else "○" }
    }

    private fun setupKeypad() {
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("⌫", "0", "OK"),
        )
        val density = resources.displayMetrics.density
        val size = (density * 64).toInt()
        val margin = (density * 4).toInt()
        for (row in keys) {
            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = android.view.Gravity.CENTER
            for (k in row) {
                val tv = TextView(this)
                tv.text = k
                tv.textSize = if (k.length == 1 && k[0].isDigit()) 24f else 18f
                tv.gravity = android.view.Gravity.CENTER
                tv.setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        this,
                        if (k == "OK") R.color.accent else R.color.text,
                    )
                )
                tv.setBackgroundResource(R.drawable.bg_item)
                tv.isFocusable = true
                tv.isClickable = true
                tv.setOnClickListener { pressKey(k) }
                val lp = LinearLayout.LayoutParams(size, size)
                lp.setMargins(margin, margin, margin, margin)
                line.addView(tv, lp)
            }
            keypad.addView(line)
        }
    }

    private fun pressKey(k: String) {
        when (k) {
            "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
            "OK" -> {
                doLogin()
                return
            }
            else -> if (pin.length < 6) pin += k
        }
        loginMsg.text = ""
        updatePinDots()
        if (pin.length == 6 && k != "⌫") doLogin()
    }

    private fun doLogin() {
        if (pin.length != 6) {
            loginMsg.text = "Introduce los 6 dígitos"
            return
        }
        val entered = pin
        loginMsg.text = "Comprobando…"
        io.execute {
            val result = try {
                Api.login(entered)
            } catch (e: Exception) {
                Pair<String?, String>(null, "No se pudo conectar")
            }
            ui.post {
                val token = result.first
                if (token != null) {
                    Api.token = token
                    getSharedPreferences("p2ptv", Context.MODE_PRIVATE).edit().putString("token", token).apply()
                    loginLayer.visibility = View.GONE
                    browser.visibility = View.VISIBLE
                    loadCategories()
                } else {
                    pin = ""
                    updatePinDots()
                    loginMsg.text = result.second
                }
            }
        }
    }

    private fun loadCategories() {
        status.text = "Cargando categorías…"
        io.execute {
            try {
                val cats = Api.categories()
                ui.post {
                    catAdapter.items = cats
                    catAdapter.notifyDataSetChanged()
                    val first = cats.indexOfFirst { !it.header }
                    if (first >= 0) {
                        catAdapter.selected = first
                        catAdapter.notifyItemChanged(first)
                        currentGroup = cats[first].group
                        loadChannels(cats[first].group, null, 30)
                    } else {
                        status.text = "No hay canales."
                    }
                }
            } catch (e: Api.Unauthorized) {
                ui.post { showLogin("Tu sesión ha caducado. Introduce tu PIN.") }
            } catch (e: Exception) {
                ui.post { status.text = "No se pudo conectar. Toca aquí para reintentar." }
            }
        }
    }

    private fun loadChannels(group: String?, query: String?, maxPages: Int) {
        val token = ++loadToken
        channels.clear()
        chAdapter.notifyDataSetChanged()
        status.text = "Cargando canales…"
        io.execute {
            try {
                var page = 1
                while (page <= maxPages) {
                    val batch = Api.channels(group, query, page)
                    if (token != loadToken) return@execute
                    ui.post {
                        if (token == loadToken) {
                            val start = channels.size
                            channels.addAll(batch)
                            chAdapter.notifyItemRangeInserted(start, batch.size)
                            status.text = "${channels.size} canales"
                        }
                    }
                    if (batch.size < Api.PAGE_SIZE) break
                    page++
                }
                ui.post { if (token == loadToken && channels.isEmpty()) status.text = "Sin resultados" }
            } catch (e: Api.Unauthorized) {
                ui.post { showLogin("Tu sesión ha caducado. Introduce tu PIN.") }
            } catch (e: Exception) {
                ui.post { if (token == loadToken) status.text = "No se pudieron cargar los canales." }
            }
        }
    }

    private fun deviceId(): String {
        val sp = getSharedPreferences("p2ptv", Context.MODE_PRIVATE)
        var d = sp.getString("device", null)
        if (d == null) {
            d = "a-" + java.util.UUID.randomUUID().toString().take(10)
            sp.edit().putString("device", d).apply()
        }
        return d
    }

    /** Mientras se ve un canal, avisa al panel cada 30 s para contar la gente viendo a la vez */
    private val pingRunner = object : Runnable {
        override fun run() {
            val ch = channels.getOrNull(currentIndex)
            if (playerLayer.visibility == View.VISIBLE && ch != null && !isVod()) {
                val id = ch.id
                val nm = ch.name
                val dev = deviceId()
                Thread {
                    // 401 = el acceso ha terminado (prueba o cuenta caducada): se corta y vuelve al PIN
                    val code = Api.watchPing(dev, id, nm)
                    if (code == 401) ui.post { showLogin("Tu acceso ha terminado. Introduce un PIN nuevo.") }
                }.start()
                ui.postDelayed(this, 30000)
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)
        val baseFactory = DefaultDataSource.Factory(this, http)
        val p2pFactory = DataSource.Factory { P2pDataSource(baseFactory.createDataSource()) }
        val sourceFactory = DefaultMediaSourceFactory(p2pFactory, extractors)
        // Empieza a reproducir con ~1 s de carga y sigue llenando por detrás
        val lc = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 40000, 1000, 2500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).setLoadControl(lc).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        attempt = 0
                        playedOnce = true
                        playerMsg.visibility = View.GONE
                    }
                    Player.STATE_BUFFERING -> showMsg("Cargando…")
                    Player.STATE_ENDED -> if (isVod()) {
                        if (inSeries && currentIndex + 1 < channels.size) play(currentIndex + 1) else closePlayer()
                    } else retry()
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                retry()
            }
        })
        playerView.player = p
        player = p
        return p
    }

    private fun play(index: Int) {
        if (index !in channels.indices) return
        val u = channels[index].url
        if (u.startsWith("vod://series/")) {
            prevGroup = currentGroup
            inSeries = true
            val g = "series:" + u.removePrefix("vod://series/")
            currentGroup = g
            loadChannels(g, null, 30)
            return
        }
        currentIndex = index
        attempt = 0
        playedOnce = false
        wdRestarts = 0
        stuckTicks = 0
        lastPos = -1L
        playToken++
        browser.visibility = View.GONE
        if (webMode) webView.visibility = View.GONE
        playerLayer.visibility = View.VISIBLE
        playerLayer.requestFocus()
        playerView.keepScreenOn = true
        val chn = channels[index]
        var info = chn.name
        if (chn.nowTitle != null) {
            info += "\n▶ " + chn.nowTitle
            if (chn.nextTitle != null) info += "   ·   Después: " + chn.nextTitle
        }
        baseTitle = info
        playerTitle.text = info
        showOverlay()
        startPlayback()
    }

    private fun startPlayback() {
        val ch = channels.getOrNull(currentIndex) ?: return
        val p = player ?: createPlayer()
        val vod = isVod()
        // Lista nueva: primero por el nodo (1 conexión por canal); si falla 3 veces, directo al proveedor
        val hasDirect = !vod && ch.direct.isNotEmpty()
        val viaNode = hasDirect && attempt < 3
        val asHls = if (viaNode) true else if (hasDirect) attempt % 2 == 0 else (!vod && attempt % 2 == 1)
        if (viaNode) {
            P2p.loadConfig()
            P2p.join(applicationContext, ch.id)
        } else {
            P2p.leave()
        }
        ui.removeCallbacks(pingRunner)
        if (!vod) ui.post(pingRunner)
        val mb = MediaItem.Builder().setUri(if (hasDirect && !viaNode) ch.direct else ch.url)
        if (!vod) mb.setMimeType(if (asHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
        val item = mb.build()
        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
    }

    private fun retry() {
        if (playerLayer.visibility != View.VISIBLE) return
        attempt++
        val token = playToken
        if (attempt <= 5) {
            showMsg("Reconectando…")
            ui.postDelayed({
                if (token == playToken && playerLayer.visibility == View.VISIBLE) startPlayback()
            }, 1200)
        } else {
            showMsg("Canal no disponible.\nPulsa arriba o abajo para cambiar de canal.")
        }
    }

    private fun zap(dir: Int) {
        val n = channels.size
        if (n == 0) return
        play((currentIndex + dir + n) % n)
    }

    private fun closePlayer() {
        P2p.leave()
        ui.removeCallbacks(pingRunner)
        val dev = deviceId()
        Thread { Api.watchStop(dev) }.start()
        playToken++
        ui.removeCallbacks(hideOverlay)
        player?.release()
        player = null
        playerView.player = null
        playerView.keepScreenOn = false
        playerMsg.visibility = View.GONE
        playerLayer.visibility = View.GONE
        if (webMode) {
            inSeries = false
            browser.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.requestFocus()
        } else {
            browser.visibility = View.VISIBLE
            channelsView.scrollToPosition(currentIndex.coerceAtLeast(0))
            channelsView.requestFocus()
        }
    }

    /** Prueba cada canal de la categoría abierta, como lo haría el reproductor, y avisa al panel */
    private fun probeStream(url: String): Boolean {
        var c: java.net.HttpURLConnection? = null
        try {
            c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 6000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            if (c.responseCode !in 200..299) return false
            val buf = ByteArray(2048)
            var got = 0
            val ins = c.inputStream
            while (got < 1200) {
                val r = ins.read(buf, got, buf.size - got)
                if (r <= 0) break
                got += r
            }
            if (got < 400) return false
            val first = buf[0].toInt() and 0xff
            return first == 0x47 || first == 0x23
        } catch (e: Exception) {
            return false
        } finally {
            try { c?.disconnect() } catch (e: Exception) {}
        }
    }

    private fun runHealthCheck() {
        if (checking) return
        val cats = catAdapter.items.filter {
            val g = it.group
            !it.header && g != null && g != "ADULTOS" && !g.startsWith("CINE ·") && !g.startsWith("SERIES ·")
        }
        if (cats.isEmpty()) {
            status.text = "Espera a que carguen las categorías"
            return
        }
        checking = true
        val group = currentGroup
        status.text = "Preparando la comprobación…"
        Thread {
            val seen = HashSet<String>()
            val list = ArrayList<Channel>()
            try {
                for (cat in cats) {
                    var page = 1
                    while (true) {
                        val batch = Api.channels(cat.group, null, page)
                        for (c in batch) if (!c.id.startsWith("vod-") && seen.add(c.id)) list.add(c)
                        if (batch.size < Api.PAGE_SIZE) break
                        page++
                    }
                }
            } catch (e: Exception) {
                ui.post {
                    checking = false
                    status.text = "No se pudo preparar la comprobación"
                }
                return@Thread
            }
            val total = list.size
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val good = java.util.concurrent.atomic.AtomicInteger(0)
            val failedReport = java.util.concurrent.atomic.AtomicInteger(0)
            val results = java.util.Collections.synchronizedList(ArrayList<Triple<String, Boolean, Int>>())
            ui.post { status.text = "Comprobando 0/" + total + "…" }
            val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
            for (ch in list) {
                pool.execute {
                    val t0 = System.currentTimeMillis()
                    val ok = probeStream(ch.direct.ifEmpty { ch.url })
                    val ms = (System.currentTimeMillis() - t0).toInt()
                    if (ok) good.incrementAndGet()
                    results.add(Triple(ch.id, ok, ms))
                    val n = done.incrementAndGet()
                    if (n % 25 == 0 || n == total) {
                        val batch = ArrayList<Triple<String, Boolean, Int>>()
                        synchronized(results) {
                            batch.addAll(results)
                            results.clear()
                        }
                        if (batch.isNotEmpty() && !Api.reportHealth(batch)) failedReport.incrementAndGet()
                        ui.post { status.text = "Comprobando " + n + "/" + total + " · funcionan " + good.get() }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(3, java.util.concurrent.TimeUnit.HOURS)
            ui.post {
                checking = false
                if (failedReport.get() > 0) {
                    status.text = "No se pudo guardar el resultado (solo el administrador puede comprobar)"
                } else {
                    status.text = "Listo: funcionan " + good.get() + " de " + total
                    loadChannels(group, null, 30)
                }
            }
        }.start()
    }

    private fun isVod(): Boolean {
        val u = channels.getOrNull(currentIndex)?.url ?: return false
        return u.contains("/app-api/vod/play/") || (u.contains("/Videos/") && u.contains("static=true"))
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }

    private fun seekBy(ms: Long) {
        val p = player ?: return
        val target = (p.currentPosition + ms).coerceAtLeast(0L)
        val d = p.duration
        p.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
        showOverlay()
    }

    private fun showOverlay() {
        val pl = player
        if (isVod() && pl != null) {
            val d = pl.duration
            playerTitle.text = baseTitle + "\n" + fmt(pl.currentPosition) + (if (d > 0) " / " + fmt(d) else "") + (if (!pl.playWhenReady) "   (pausa)" else "")
        }
        if (!isVod()) P2p.current?.let { playerTitle.text = baseTitle + "\n" + it.status() }
        overlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideOverlay)
        ui.postDelayed(hideOverlay, 3500)
    }

    private fun showMsg(text: String) {
        playerMsg.text = text
        playerMsg.visibility = View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (loginLayer.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            val kc = event.keyCode
            if (kc >= KeyEvent.KEYCODE_0 && kc <= KeyEvent.KEYCODE_9) {
                pressKey((kc - KeyEvent.KEYCODE_0).toString())
                return true
            }
            if (kc == KeyEvent.KEYCODE_DEL) {
                pressKey("⌫")
                return true
            }
        }
        if (playerLayer.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN && isVod()) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    seekBy(-15000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    seekBy(15000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    seekBy(60000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    seekBy(-60000)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    player?.let { it.playWhenReady = !it.playWhenReady }
                    showOverlay()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    player?.playWhenReady = true
                    showOverlay()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    player?.playWhenReady = false
                    showOverlay()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    zap(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    zap(-1)
                    return true
                }
            }
        }
        if (playerLayer.visibility == View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_CHANNEL_UP,
                KeyEvent.KEYCODE_PAGE_UP -> {
                    zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_CHANNEL_DOWN,
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    zap(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    showOverlay()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (playerLayer.visibility == View.VISIBLE) {
            playToken++
            player?.release()
            player = null
            playerView.player = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (playerLayer.visibility == View.VISIBLE && player == null) {
            attempt = 0
            startPlayback()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (webMode) webView.destroy()
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        io.shutdownNow()
    }
}
