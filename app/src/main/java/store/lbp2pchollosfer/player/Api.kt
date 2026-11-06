package store.lbp2pchollosfer.player

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
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

    private fun get(path: String): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 25000
        conn.setRequestProperty("User-Agent", "P2PTV-Android")
        try {
            if (conn.responseCode !in 200..299) throw RuntimeException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("^(ES|ESP|EU)\\s*[|:\\-]+\\s*"), "").trim().ifEmpty { raw }

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
            out.add(
                Channel(
                    id = o.optString("id", ""),
                    name = o.optString("name", "Canal"),
                    group = cleanTitle(o.optString("group_title", "")),
                    url = url,
                )
            )
        }
        return out
    }
}
