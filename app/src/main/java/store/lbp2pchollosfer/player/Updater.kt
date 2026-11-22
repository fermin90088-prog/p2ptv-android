package store.lbp2pchollosfer.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Avisa de que hay una versión nueva de la APK, la descarga y abre el instalador */
object Updater {
    private const val BASE = "https://github.com/fermin90088-prog/p2ptv-android/releases/download/latest/"
    private const val EVERY_MS = 6L * 60 * 60 * 1000
    private const val PREFS = "p2ptv"

    @Volatile
    private var busy = false

    @Suppress("DEPRECATION")
    private fun installedCode(act: AppCompatActivity): Long {
        val info = act.packageManager.getPackageInfo(act.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    private fun apkFile(act: AppCompatActivity): File = File(File(act.cacheDir, "updates").apply { mkdirs() }, "p2ptv-update.apk")

    /** Mira si hay versión nueva, como mucho una vez cada 6 horas */
    fun checkIfDue(act: AppCompatActivity) {
        try {
            val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (busy || System.currentTimeMillis() - prefs.getLong("upd_last", 0L) < EVERY_MS) return
            val mine = installedCode(act)
            busy = true
            Thread {
                try {
                    val c = URL(BASE + "version.json").openConnection() as HttpURLConnection
                    c.connectTimeout = 6000
                    c.readTimeout = 6000
                    if (c.responseCode == 200) {
                        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        c.disconnect()
                        prefs.edit().putLong("upd_last", System.currentTimeMillis()).apply()
                        val code = j.optLong("versionCode", 0L)
                        val url = j.optString("url", "")
                        if (code > mine && url.startsWith(BASE)) {
                            val name = j.optString("versionName", code.toString())
                            act.runOnUiThread { offer(act, name, url) }
                        }
                    } else {
                        c.disconnect()
                    }
                } catch (e: Exception) {
                } finally {
                    busy = false
                }
            }.start()
        } catch (e: Exception) {
            busy = false
        }
    }

    private fun offer(act: AppCompatActivity, name: String, url: String) {
        if (act.isFinishing || act.isDestroyed) return
        AlertDialog.Builder(act)
            .setTitle("Hay una versión nueva")
            .setMessage("Versión " + name + ". Se descarga e instala en un minuto. Tu cuenta y tus ajustes se mantienen.")
            .setPositiveButton("Actualizar ahora") { _, _ -> download(act, url) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun download(act: AppCompatActivity, url: String) {
        val dlg = AlertDialog.Builder(act).setTitle("Descargando actualización").setMessage("0 %").setCancelable(false).create()
        dlg.show()
        val file = apkFile(act)
        Thread {
            var ok = false
            try {
                file.delete()
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                if (c.responseCode == 200) {
                    val total = c.contentLengthLong
                    var done = 0L
                    var lastPct = -1
                    c.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        act.runOnUiThread { dlg.setMessage(pct.toString() + " %") }
                                    }
                                }
                            }
                        }
                    }
                    ok = file.length() > 1_000_000L && (total <= 0L || file.length() == total)
                }
                c.disconnect()
            } catch (e: Exception) {
                ok = false
            }
            act.runOnUiThread {
                try {
                    dlg.dismiss()
                } catch (e: Exception) {
                }
                if (ok) install(act, file)
                else Toast.makeText(act, "No se pudo descargar la actualización. Inténtalo más tarde.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun install(act: AppCompatActivity, file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            act.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("upd_pending", true).apply()
            AlertDialog.Builder(act)
                .setTitle("Un último paso")
                .setMessage("Para instalar la actualización, activa «Permitir de esta fuente» para esta app y vuelve aquí.")
                .setPositiveButton("Abrir ajustes") { _, _ -> openSettings(act) }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        launchInstaller(act, file)
    }

    private fun openSettings(act: AppCompatActivity) {
        try {
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName)))
        } catch (e: Exception) {
            try {
                act.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(act, "Ve a Ajustes y permite instalar apps desconocidas para P2P Chollos Fer.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchInstaller(act: AppCompatActivity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".updates", file)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            act.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(act, "No se pudo abrir el instalador.", Toast.LENGTH_LONG).show()
        }
    }

    /** Si el usuario fue a activar el permiso y vuelve, sigue con la instalación */
    fun resumePending(act: AppCompatActivity) {
        try {
            val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("upd_pending", false)) return
            if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) return
            prefs.edit().remove("upd_pending").apply()
            val file = apkFile(act)
            if (file.exists() && file.length() > 1_000_000L) launchInstaller(act, file)
        } catch (e: Exception) {
        }
    }
}
