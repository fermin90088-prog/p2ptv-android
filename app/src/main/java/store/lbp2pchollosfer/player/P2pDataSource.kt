package store.lbp2pchollosfer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Envuelve la descarga normal: los trozos de vídeo (.ts) se piden primero a otros
 * aparatos que ven el mismo canal; si nadie los tiene a tiempo, bajan del nodo.
 * Lo que baja del nodo se guarda para pasárselo a los demás.
 */
@UnstableApi
class P2pDataSource(private val upstream: DataSource) : DataSource {
    private var mem: ByteArrayInputStream? = null
    private var capture: ByteArrayOutputStream? = null
    private var captureOk = false
    private var key: String? = null
    private var swarm: Swarm? = null
    private var uri: Uri? = null

    override fun addTransferListener(l: TransferListener) {
        upstream.addTransferListener(l)
    }

    override fun open(spec: DataSpec): Long {
        uri = spec.uri
        mem = null
        capture = null
        captureOk = false
        key = null
        val sw = P2p.current
        val path = spec.uri.path ?: ""
        if (sw != null && path.endsWith(".ts") && spec.position == 0L &&
            spec.length == C.LENGTH_UNSET.toLong()
        ) {
            val k = path.split("/").takeLast(2).joinToString("/")
            swarm = sw
            key = k
            val bytes = try { sw.fetch(k, 1200) } catch (t: Throwable) { null }
            if (bytes != null) {
                mem = ByteArrayInputStream(bytes)
                return bytes.size.toLong()
            }
            capture = ByteArrayOutputStream()
        }
        return upstream.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val m = mem
        if (m != null) {
            val n = m.read(buffer, offset, length)
            return if (n < 0) C.RESULT_END_OF_INPUT else n
        }
        val n = upstream.read(buffer, offset, length)
        if (n == C.RESULT_END_OF_INPUT) {
            captureOk = true
        } else if (n > 0) {
            val cap = capture
            if (cap != null) {
                if (cap.size() + n > 6 * 1024 * 1024) capture = null else cap.write(buffer, offset, n)
            }
        }
        return n
    }

    override fun getUri(): Uri? = if (mem != null) uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (mem != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        val cap = capture
        val k = key
        val sw = swarm
        if (captureOk && cap != null && k != null && sw != null && cap.size() > 0) {
            try { sw.put(k, cap.toByteArray(), false) } catch (t: Throwable) {}
        }
        mem = null
        capture = null
        captureOk = false
        try { upstream.close() } catch (t: Throwable) {}
    }
}
