package com.nixai.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import org.json.JSONObject
import java.io.File

/** Modelo open source que usa Nix Ai (Qwen2.5 1.5B Instruct, licencia Apache-2.0, formato LiteRT-LM). */
object ModelSpec {
    const val FILE_NAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
    const val URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/$FILE_NAME"

    /** Tamaño mínimo para dar la descarga por buena (el archivo real pesa ~1.6 GB). */
    const val MIN_BYTES = 1_400_000_000L
    const val NEEDED_FREE_BYTES = 2_300_000_000L
}

/** Descarga el modelo con el DownloadManager del sistema: reanuda solo, sobrevive a cortes y muestra notificación. */
class ModelManager(private val ctx: Context) {

    private val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = ctx.getSharedPreferences("nixai", Context.MODE_PRIVATE)

    private val dir: File
        get() = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    val modelFile: File get() = File(dir, ModelSpec.FILE_NAME)

    fun isReady(): Boolean =
        prefs.getBoolean("model_ok", false) && modelFile.exists() && modelFile.length() >= ModelSpec.MIN_BYTES

    fun freeBytes(): Long = try {
        StatFs(dir.path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    fun startDownload(wifiOnly: Boolean) {
        if (isReady()) return
        val current = prefs.getLong("dl_id", -1L)
        if (current != -1L) {
            val st = query(current)
            if (st != null && (st.status == DownloadManager.STATUS_RUNNING ||
                        st.status == DownloadManager.STATUS_PENDING ||
                        st.status == DownloadManager.STATUS_PAUSED)
            ) return
            dm.remove(current)
        }
        modelFile.delete()

        val req = DownloadManager.Request(Uri.parse(ModelSpec.URL))
            .setTitle("Nix Ai")
            .setDescription("Descargando el modelo de IA (una sola vez)")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setAllowedOverRoaming(false)
            .setAllowedOverMetered(!wifiOnly)
            .setDestinationUri(Uri.fromFile(modelFile))
        prefs.edit().putLong("dl_id", dm.enqueue(req)).putBoolean("model_ok", false).apply()
    }

    fun cancelDownload() {
        val id = prefs.getLong("dl_id", -1L)
        if (id != -1L) dm.remove(id)
        prefs.edit().remove("dl_id").putBoolean("model_ok", false).apply()
        modelFile.delete()
    }

    private class Q(val status: Int, val done: Long, val total: Long, val reason: Int)

    private fun query(id: Long): Q? {
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            return Q(
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            )
        }
        return null
    }

    /** Estado de la descarga: none | downloading | waiting | failed | ready */
    fun downloadState(): JSONObject {
        val o = JSONObject()
        if (isReady()) return o.put("state", "ready").put("done", modelFile.length()).put("total", modelFile.length())

        val id = prefs.getLong("dl_id", -1L)
        val q = if (id == -1L) null else query(id)
        if (q == null) return o.put("state", "none").put("done", 0).put("total", 0)

        o.put("done", q.done).put("total", q.total)
        when (q.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                if (modelFile.exists() && modelFile.length() >= ModelSpec.MIN_BYTES) {
                    prefs.edit().putBoolean("model_ok", true).apply()
                    o.put("state", "ready")
                } else {
                    prefs.edit().remove("dl_id").apply()
                    o.put("state", "failed").put("message", "El archivo descargado está incompleto. Intenta de nuevo.")
                }
            }
            DownloadManager.STATUS_FAILED -> {
                prefs.edit().remove("dl_id").apply()
                o.put("state", "failed").put("message", "La descarga falló (código ${q.reason}). Revisa tu conexión e intenta de nuevo.")
            }
            DownloadManager.STATUS_PAUSED -> o.put("state", "waiting")
            else -> o.put("state", "downloading")
        }
        return o
    }
}
