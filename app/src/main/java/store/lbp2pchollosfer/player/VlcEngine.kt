package store.lbp2pchollosfer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Reproductor VLC (libVLC) para películas y series: reproduce casi cualquier audio y vídeo sin convertir. */
class VlcEngine(ctx: Context, private val parent: FrameLayout, private val software: Boolean = false) {

    companion object {
        val available: Boolean by lazy {
            try {
                Class.forName("org.videolan.libvlc.LibVLC")
                true
            } catch (e: Throwable) {
                false
            }
        }
    }

    var onPlaying: (() -> Unit)? = null
    var onBuffering: ((Float) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onError: (() -> Unit)? = null
    var onNoVideo: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val layout = VLCVideoLayout(ctx)
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var started = false
    private var released = false
    private var voutShown = false
    private val noVideoCheck = Runnable { if (!released && !voutShown) onNoVideo?.invoke() }
    private val startTimeout = Runnable { if (!started && !released) onError?.invoke() }

    init {
        parent.addView(layout, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        layout.visibility = View.VISIBLE
        val opts = ArrayList<String>()
        opts.add("--network-caching=2000")
        opts.add("--http-reconnect")
        val lv = LibVLC(ctx, opts)
        libVlc = lv
        mp = MediaPlayer(lv)
    }

    fun play(url: String) {
        val p = mp ?: return
        val lv = libVlc ?: return
        p.attachViews(layout, null, false, false)
        p.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    started = true
                    handler.removeCallbacks(startTimeout)
                    if (!voutShown) {
                        handler.removeCallbacks(noVideoCheck)
                        handler.postDelayed(noVideoCheck, 10000)
                    }
                    onPlaying?.invoke()
                }
                MediaPlayer.Event.Vout -> {
                    if (ev.voutCount > 0) {
                        voutShown = true
                        handler.removeCallbacks(noVideoCheck)
                    }
                }
                MediaPlayer.Event.Buffering -> onBuffering?.invoke(ev.buffering)
                MediaPlayer.Event.EndReached -> onEnded?.invoke()
                MediaPlayer.Event.EncounteredError -> onError?.invoke()
            }
        }
        val m = Media(lv, Uri.parse(url))
        m.setHWDecoderEnabled(!software, false)
        if (software) m.addOption(":avcodec-hw=none")
        m.addOption(":http-user-agent=P2PTVApp VLC/3.0.18 LibVLC/3.0.18")
        p.setMedia(m)
        m.release()
        p.play()
        handler.postDelayed(startTimeout, 25000)
    }

    fun position(): Long = mp?.time ?: 0L
    fun duration(): Long = mp?.length ?: 0L
    fun isPlaying(): Boolean = mp?.isPlaying ?: false

    fun togglePause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun setPaused(paused: Boolean) {
        val p = mp ?: return
        if (paused) {
            if (p.isPlaying) p.pause()
        } else if (!p.isPlaying) {
            p.play()
        }
    }

    fun seekBy(ms: Long) {
        val p = mp ?: return
        val d = p.length
        val target = (p.time + ms).coerceAtLeast(0L)
        p.setTime(if (d > 0) target.coerceAtMost(d - 1000) else target)
    }

    fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(startTimeout)
        handler.removeCallbacks(noVideoCheck)
        val p = mp
        val lv = libVlc
        mp = null
        libVlc = null
        parent.removeView(layout)
        Thread {
            try {
                p?.setEventListener(null)
                p?.stop()
                p?.detachViews()
                p?.release()
                lv?.release()
            } catch (e: Throwable) {
            }
        }.start()
    }
}
