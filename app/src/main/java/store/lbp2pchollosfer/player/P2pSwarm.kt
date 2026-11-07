package store.lbp2pchollosfer.player

import android.content.Context
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P2P entre aparatos: los que ven el mismo canal se pasan los trozos de vídeo.
 * Habla el mismo idioma que la web (mismo punto de encuentro y mismos mensajes),
 * así que la APK y la web comparten entre sí. Todo falla en silencio: si algo
 * no va, el vídeo sigue bajando del nodo como siempre.
 */
object P2p {
    @Volatile var enabled = false
    @Volatile private var configAsked = false
    @Volatile var current: Swarm? = null
    private var factory: PeerConnectionFactory? = null
    private val lock = Any()

    /** Pregunta al servidor si el P2P está encendido para este usuario (una vez por sesión) */
    fun loadConfig() {
        if (configAsked) return
        configAsked = true
        Thread { enabled = Api.p2pEnabled() }.start()
    }

    private fun factory(ctx: Context): PeerConnectionFactory? {
        synchronized(lock) {
            if (factory == null) {
                try {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                            .createInitializationOptions()
                    )
                    factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                } catch (t: Throwable) {
                    Log.w("P2P", "no arranca", t)
                    enabled = false
                }
            }
            return factory
        }
    }

    /** Entra en la sala del canal (sin bloquear la pantalla) */
    fun join(ctx: Context, channelId: String) {
        if (!enabled) return
        val room = "ch-" + channelId
        synchronized(lock) {
            if (current?.room == room) return
            current?.stop()
            current = null
        }
        Thread {
            try {
                val f = factory(ctx) ?: return@Thread
                val s = Swarm(room, f)
                synchronized(lock) {
                    current?.stop()
                    current = s
                }
                s.start()
            } catch (t: Throwable) {
                Log.w("P2P", "join", t)
            }
        }.start()
    }

    fun leave() {
        synchronized(lock) {
            current?.stop()
            current = null
        }
    }
}

private open class Sdp : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

private class Pending {
    val latch = CountDownLatch(1)
    @Volatile var result: ByteArray? = null
    fun finish(b: ByteArray?) {
        result = b
        latch.countDown()
    }
}

private class Asm(val n: Int) {
    val parts = arrayOfNulls<String>(n)
    var got = 0
}

class Swarm(val room: String, private val factory: PeerConnectionFactory) {
    @Volatile private var stopped = false
    @Volatile private var myId = ""
    private val peers = ConcurrentHashMap<String, Peer>()
    private val cache = LinkedHashMap<String, ByteArray>()
    private val cacheLock = Any()
    private val io = Executors.newSingleThreadExecutor()
    private val serve = Executors.newFixedThreadPool(2)
    @Volatile var p2pBytes = 0L
    @Volatile var httpBytes = 0L

    private val ice: List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    // ── Punto de encuentro (mismo que la web, por consultas normales) ─────────
    private fun http(method: String, path: String, body: JSONObject?): JSONObject? {
        var c: HttpURLConnection? = null
        try {
            c = URL(Api.BASE + path).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "P2PTV-Android")
            Api.token?.let { c.setRequestProperty("Authorization", "Bearer " + it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) return null
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            return null
        } finally {
            try { c?.disconnect() } catch (e: Exception) {}
        }
    }

    fun start() {
        Thread { loop() }.apply { isDaemon = true }.start()
    }

    private fun loop() {
        val j = http("POST", "/app-api/sig/join", JSONObject().put("room", room)) ?: return
        myId = j.optString("peerId")
        if (myId.isEmpty()) return
        val arr = j.optJSONArray("peers")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val id = arr.getJSONObject(i).optString("id")
                if (id.isNotEmpty()) initPeer(id, true)
            }
        }
        var after = "0"
        var fastUntil = System.currentTimeMillis() + 30000
        while (!stopped) {
            try {
                Thread.sleep(if (System.currentTimeMillis() < fastUntil) 1000L else 4000L)
            } catch (e: InterruptedException) {
                return
            }
            if (stopped) return
            val r = http("GET", "/app-api/sig/poll?peer=" + myId + "&after=" + after, null) ?: continue
            if (r.optBoolean("gone")) return
            val msgs = r.optJSONArray("messages") ?: continue
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                after = m.optString("id", after)
                try {
                    handleSignal(m.getJSONObject("body"))
                } catch (e: Exception) {
                    Log.w("P2P", "señal", e)
                }
            }
            if (msgs.length() > 0) fastUntil = maxOf(fastUntil, System.currentTimeMillis() + 10000)
        }
    }

    private fun handleSignal(b: JSONObject) {
        when (b.optString("type")) {
            "peer-joined" -> {
                val id = b.optString("peerId")
                if (id.isNotEmpty() && peers.size < 8) initPeer(id, false)
            }
            "peer-left" -> peers.remove(b.optString("peerId"))?.close()
            "offer" -> {
                val from = b.optString("from")
                val sdp = b.optJSONObject("sdp") ?: return
                val p = peers[from] ?: (if (peers.size < 8) initPeer(from, false) else null) ?: return
                p.onOffer(sdp)
            }
            "answer" -> {
                val sdp = b.optJSONObject("sdp") ?: return
                peers[b.optString("from")]?.onAnswer(sdp)
            }
            "ice" -> {
                val cand = b.optJSONObject("candidate") ?: return
                peers[b.optString("from")]?.onRemoteIce(cand)
            }
        }
    }

    private fun sendSignal(to: String, msg: JSONObject) {
        try {
            msg.put("to", to).put("from", myId)
            io.execute {
                http(
                    "POST", "/app-api/sig/send",
                    JSONObject().put("room", room).put("from", myId).put("to", to).put("msg", msg)
                )
            }
        } catch (e: Exception) {
        }
    }

    private fun initPeer(id: String, initiator: Boolean): Peer? {
        if (stopped) return null
        peers[id]?.let { return it }
        val p = Peer(id, initiator)
        peers[id] = p
        try {
            p.start()
        } catch (t: Throwable) {
            Log.w("P2P", "peer", t)
            peers.remove(id)
            return null
        }
        return p
    }

    private fun drop(id: String) {
        peers.remove(id)?.close()
    }

    // ── Caché de trozos ───────────────────────────────────────────────────────
    private fun cached(key: String): ByteArray? = synchronized(cacheLock) { cache[key] }

    fun put(key: String, b: ByteArray, fromPeer: Boolean) {
        synchronized(cacheLock) {
            cache[key] = b
            while (cache.size > 16) {
                val k = cache.keys.first()
                cache.remove(k)
            }
        }
        if (fromPeer) p2pBytes += b.size else httpBytes += b.size
        val msg = JSONObject().put("type", "have").put("segs", JSONArray().put(key))
        for (p in peers.values) if (p.ready) p.sendMsg(msg)
    }

    /** Intenta conseguir el trozo de otro aparato; null = pedirlo al nodo como siempre */
    fun fetch(key: String, budgetMs: Long): ByteArray? {
        cached(key)?.let { return it }
        val deadline = System.currentTimeMillis() + budgetMs
        val ready = peers.values.filter { it.ready }
        val ordered = ready.filter { it.have.contains(key) }.shuffled() +
            ready.filter { !it.have.contains(key) }.take(2)
        for (p in ordered) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val b = p.request(key, left)
            if (b != null && b.isNotEmpty()) {
                put(key, b, true)
                return b
            }
        }
        return null
    }

    fun status(): String {
        val n = peers.values.count { it.ready }
        val total = p2pBytes + httpBytes
        val pct = if (total > 0) (p2pBytes * 100 / total).toInt() else 0
        return "P2P: " + n + " conectados · " + pct + "% compartido"
    }

    fun stop() {
        if (stopped) return
        stopped = true
        for (p in peers.values) p.close()
        peers.clear()
        synchronized(cacheLock) { cache.clear() }
        try {
            val id = myId
            if (id.isNotEmpty()) io.execute { http("POST", "/app-api/sig/leave", JSONObject().put("peer", id)) }
            io.shutdown()
            serve.shutdown()
        } catch (e: Exception) {
        }
    }

    // ── Un aparato conectado ──────────────────────────────────────────────────
    inner class Peer(val id: String, private val initiator: Boolean) {
        @Volatile var ready = false
        val have: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val pending = ConcurrentHashMap<String, Pending>()
        private val asm = ConcurrentHashMap<String, Asm>()
        private var pc: PeerConnection? = null
        private var dc: DataChannel? = null
        private val queued = ArrayList<IceCandidate>()
        private var remoteSet = false
        @Volatile private var closed = false

        fun start() {
            val cfg = PeerConnection.RTCConfiguration(ice)
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            val conn = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                    if (s == PeerConnection.IceConnectionState.FAILED ||
                        s == PeerConnection.IceConnectionState.CLOSED ||
                        s == PeerConnection.IceConnectionState.DISCONNECTED
                    ) drop(id)
                }
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(c: IceCandidate?) {
                    if (c == null) return
                    sendSignal(
                        id,
                        JSONObject().put("type", "ice").put(
                            "candidate",
                            JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid)
                                .put("sdpMLineIndex", c.sdpMLineIndex)
                        )
                    )
                }
                override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
                override fun onAddStream(s: MediaStream?) {}
                override fun onRemoveStream(s: MediaStream?) {}
                override fun onDataChannel(ch: DataChannel?) {
                    if (ch != null) setup(ch)
                }
                override fun onRenegotiationNeeded() {}
            }) ?: throw IllegalStateException("sin conexión")
            pc = conn
            if (initiator) {
                val ch = conn.createDataChannel("p2p", DataChannel.Init())
                setup(ch)
                conn.createOffer(object : Sdp() {
                    override fun onCreateSuccess(d: SessionDescription?) {
                        if (d == null) return
                        conn.setLocalDescription(Sdp(), d)
                        sendSignal(
                            id,
                            JSONObject().put("type", "offer").put(
                                "sdp", JSONObject().put("type", "offer").put("sdp", d.description)
                            )
                        )
                    }
                }, MediaConstraints())
            }
        }

        fun onOffer(j: JSONObject) {
            val conn = pc ?: return
            val desc = SessionDescription(SessionDescription.Type.OFFER, j.optString("sdp"))
            conn.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    flushIce()
                    conn.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d == null) return
                            conn.setLocalDescription(Sdp(), d)
                            sendSignal(
                                id,
                                JSONObject().put("type", "answer").put(
                                    "sdp", JSONObject().put("type", "answer").put("sdp", d.description)
                                )
                            )
                        }
                    }, MediaConstraints())
                }
            }, desc)
        }

        fun onAnswer(j: JSONObject) {
            val conn = pc ?: return
            val desc = SessionDescription(SessionDescription.Type.ANSWER, j.optString("sdp"))
            conn.setRemoteDescription(object : Sdp() {
                override fun onSetSuccess() {
                    flushIce()
                }
            }, desc)
        }

        fun onRemoteIce(j: JSONObject) {
            val ic = IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate"))
            synchronized(queued) {
                if (remoteSet) pc?.addIceCandidate(ic) else queued.add(ic)
            }
        }

        private fun flushIce() {
            synchronized(queued) {
                remoteSet = true
                for (ic in queued) pc?.addIceCandidate(ic)
                queued.clear()
            }
        }

        private fun setup(ch: DataChannel) {
            dc = ch
            ch.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(p: Long) {}
                override fun onStateChange() {
                    val st = ch.state()
                    if (st == DataChannel.State.OPEN) {
                        ready = true
                        val keys = synchronized(cacheLock) { cache.keys.toList() }
                        if (keys.isNotEmpty()) {
                            val arr = JSONArray()
                            for (k in keys) arr.put(k)
                            sendMsg(JSONObject().put("type", "have").put("segs", arr))
                        }
                    } else if (st == DataChannel.State.CLOSED) {
                        drop(id)
                    }
                }
                override fun onMessage(buf: DataChannel.Buffer) {
                    if (buf.binary) return
                    val bytes = ByteArray(buf.data.remaining())
                    buf.data.get(bytes)
                    try {
                        onData(JSONObject(String(bytes, Charsets.UTF_8)))
                    } catch (e: Exception) {
                    }
                }
            })
        }

        fun sendMsg(m: JSONObject) {
            try {
                dc?.send(DataChannel.Buffer(ByteBuffer.wrap(m.toString().toByteArray(Charsets.UTF_8)), false))
            } catch (e: Exception) {
            }
        }

        private fun onData(m: JSONObject) {
            when (m.optString("type")) {
                "have" -> {
                    val a = m.optJSONArray("segs")
                    if (a != null) for (i in 0 until a.length()) have.add(a.getString(i))
                }
                "want" -> {
                    val seg = m.optString("seg")
                    val reqId = m.optString("reqId")
                    try {
                        serve.execute { serveSeg(seg, reqId) }
                    } catch (e: Exception) {
                    }
                }
                "data" -> receive(m)
                "nack" -> pending.remove(m.optString("reqId"))?.finish(null)
                "ping" -> sendMsg(JSONObject().put("type", "pong"))
            }
        }

        private fun serveSeg(seg: String, reqId: String) {
            val b = cached(seg)
            if (b == null) {
                sendMsg(JSONObject().put("type", "nack").put("seg", seg).put("reqId", reqId))
                return
            }
            val b64 = Base64.encodeToString(b, Base64.NO_WRAP)
            val part = 32 * 1024
            val n = maxOf(1, (b64.length + part - 1) / part)
            for (i in 0 until n) {
                sendMsg(
                    JSONObject().put("type", "data").put("seg", seg).put("reqId", reqId)
                        .put("i", i).put("n", n)
                        .put("chunk", b64.substring(i * part, minOf(b64.length, (i + 1) * part)))
                )
            }
        }

        private fun receive(m: JSONObject) {
            val reqId = m.optString("reqId")
            if (!pending.containsKey(reqId)) return
            val n = m.optInt("n", 1)
            val a = asm.getOrPut(reqId) { Asm(n) }
            val i = m.optInt("i", 0)
            if (i < 0 || i >= a.n) return
            synchronized(a) {
                if (a.parts[i] == null) {
                    a.parts[i] = m.optString("chunk")
                    a.got++
                }
                if (a.got < a.n) return
            }
            asm.remove(reqId)
            val bytes = try {
                Base64.decode(a.parts.joinToString(""), Base64.DEFAULT)
            } catch (e: Exception) {
                null
            }
            pending.remove(reqId)?.finish(bytes)
        }

        fun request(seg: String, timeoutMs: Long): ByteArray? {
            if (!ready) return null
            val reqId = java.util.UUID.randomUUID().toString().take(8)
            val pnd = Pending()
            pending[reqId] = pnd
            sendMsg(JSONObject().put("type", "want").put("seg", seg).put("reqId", reqId))
            val ok = try {
                pnd.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }
            pending.remove(reqId)
            asm.remove(reqId)
            return if (ok) pnd.result else null
        }

        fun close() {
            if (closed) return
            closed = true
            ready = false
            for (p in pending.values) p.finish(null)
            pending.clear()
            val c = pc
            val d = dc
            pc = null
            dc = null
            try {
                Thread {
                    try { d?.close() } catch (t: Throwable) {}
                    try { c?.close() } catch (t: Throwable) {}
                    try { c?.dispose() } catch (t: Throwable) {}
                }.start()
            } catch (t: Throwable) {
            }
        }
    }
}
