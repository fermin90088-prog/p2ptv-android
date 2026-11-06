package store.lbp2pchollosfer.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String,
    val nowTitle: String?,
    val nowStartMs: Long,
    val nowEndMs: Long,
    val nextTitle: String?,
)

data class CatItem(
    val title: String,
    val group: String?,
    val count: Int,
    val header: Boolean,
)

object Api {
    const val BASE = "https://tv.lbp2pchollosfer.store"
    const val PAGE_SIZE = 60

    /** Sesión del cliente (se guarda en el aparato tras entrar con el PIN) */
    @Volatile
    var token: String? = null

    class Unauthorized : RuntimeException("login")

    /** Devuelve (sesión, "") si el PIN vale, o (null, mensaje de error) */
    fun login(pin: String): Pair<String?, String> {
        val conn = URL(BASE + "/app-api/auth/pin-login").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 10000
        conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("User-Agent", "P2PTV-Android")
        try {
            conn.outputStream.use { it.write(("{\"pin\":\"" + pin + "\"}").toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val o = JSONObject(text)
            return if (o.optBoolean("ok")) Pair(o.optString("token"), "")
            else Pair(null, o.optString("error", "No se pudo entrar"))
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 25000
        conn.setRequestProperty("User-Agent", "P2PTV-Android")
        token?.let { conn.setRequestProperty("Authorization", "Bearer " + it) }
        try {
            if (conn.responseCode == 401) throw Unauthorized()
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("^(ES|ESP|EU)\\s*[|:\\-]+\\s*"), "").trim().ifEmpty { raw }

    private fun str(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key, "").ifEmpty { null }

    private fun ms(s: String?): Long {
        if (s == null || s.length < 19) return 0L
        return try {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            f.parse(s.replace(' ', 'T').substring(0, 19))?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    fun categories(): List<CatItem> {
        val arr = JSONArray(get("/app-api/m3u/groups"))
        val out = ArrayList<CatItem>()
        for (i in 0 until arr.length()) {
            val country = arr.getJSONObject(i)
            val flag = country.optString("flag", "")
            val name = country.optString("name", "")
            out.add(CatItem("$flag $name".trim(), null, 0, true))
            val groups = country.optJSONArray("groups") ?: continue
            for (j in 0 until groups.length()) {
                val g = groups.getJSONObject(j)
                val title = g.optString("group_title", "")
                out.add(CatItem(cleanTitle(title), title, g.optInt("count", 0), false))
            }
        }
        return out
    }

    fun channels(group: String?, search: String?, page: Int): List<Channel> {
        val qs = StringBuilder("?page=$page")
        if (!search.isNullOrEmpty()) {
            qs.append("&search=").append(URLEncoder.encode(search, "UTF-8"))
        } else if (!group.isNullOrEmpty()) {
            qs.append("&group=").append(URLEncoder.encode(group, "UTF-8"))
        }
        val arr = JSONArray(get("/app-api/m3u/channels$qs"))
        val out = ArrayList<Channel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val url = o.optString("stream_url", "")
            if (url.isEmpty()) continue
            var logo = str(o, "logo") ?: ""
            if (logo.startsWith("/")) logo = BASE + logo
            out.add(
                Channel(
                    id = o.optString("id", ""),
                    name = o.optString("name", "Canal"),
                    group = cleanTitle(o.optString("group_title", "")),
                    url = url,
                    logo = logo,
                    nowTitle = str(o, "now_title"),
                    nowStartMs = ms(str(o, "now_start")),
                    nowEndMs = ms(str(o, "now_end")),
                    nextTitle = str(o, "next_title"),
                )
            )
        }
        return out
    }

    /** Envía al panel qué canales han reproducido (solo lo acepta el administrador) */
    fun reportHealth(batch: List<Triple<String, Boolean, Int>>): Boolean {
        return try {
            val conn = URL(BASE + "/app-api/health/report").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Content-Type", "application/json")
            token?.let { conn.setRequestProperty("Authorization", "Bearer " + it) }
            val arr = org.json.JSONArray()
            for (t in batch) {
                arr.put(org.json.JSONObject().put("id", t.first).put("ok", t.second).put("ms", t.third))
            }
            val body = org.json.JSONObject().put("results", arr).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
