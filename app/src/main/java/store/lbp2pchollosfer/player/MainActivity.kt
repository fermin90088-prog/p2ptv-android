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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    private val channels = ArrayList<Channel>()
    private lateinit var catAdapter: CatAdapter
    private lateinit var chAdapter: ChAdapter

    private var player: ExoPlayer? = null
    private var currentIndex = -1
    private var currentGroup: String? = null
    private var loadToken = 0
    private var playToken = 0
    private var attempt = 0

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

        catAdapter = CatAdapter { item ->
            search.setText("")
            currentGroup = item.group
            loadChannels(item.group, null, 30)
        }
        categoriesView.layoutManager = LinearLayoutManager(this)
        categoriesView.adapter = catAdapter

        chAdapter = ChAdapter(channels) { index -> play(index) }
        channelsView.layoutManager = LinearLayoutManager(this)
        channelsView.adapter = chAdapter

        search.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val q = v.text.toString().trim()
                hideKeyboard()
                if (q.length >= 2) loadChannels(null, q, 4)
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
                if (playerLayer.visibility == View.VISIBLE) {
                    closePlayer()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        loadCategories()
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
            } catch (e: Exception) {
                ui.post { if (token == loadToken) status.text = "No se pudieron cargar los canales." }
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(sourceFactory).build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        attempt = 0
                        playerMsg.visibility = View.GONE
                    }
                    Player.STATE_BUFFERING -> showMsg("Cargando…")
                    Player.STATE_ENDED -> retry()
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
        currentIndex = index
        attempt = 0
        playToken++
        browser.visibility = View.GONE
        playerLayer.visibility = View.VISIBLE
        playerLayer.requestFocus()
        playerView.keepScreenOn = true
        val chn = channels[index]
        var info = chn.name
        if (chn.nowTitle != null) {
            info += "\n▶ " + chn.nowTitle
            if (chn.nextTitle != null) info += "   ·   Después: " + chn.nextTitle
        }
        playerTitle.text = info
        showOverlay()
        startPlayback()
    }

    private fun startPlayback() {
        val ch = channels.getOrNull(currentIndex) ?: return
        val p = player ?: createPlayer()
        val asHls = attempt % 2 == 1
        val item = MediaItem.Builder()
            .setUri(ch.url)
            .setMimeType(if (asHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .build()
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
        playToken++
        ui.removeCallbacks(hideOverlay)
        player?.release()
        player = null
        playerView.player = null
        playerView.keepScreenOn = false
        playerMsg.visibility = View.GONE
        playerLayer.visibility = View.GONE
        browser.visibility = View.VISIBLE
        channelsView.scrollToPosition(currentIndex.coerceAtLeast(0))
        channelsView.requestFocus()
    }

    private fun showOverlay() {
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
        ui.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        io.shutdownNow()
    }
}
