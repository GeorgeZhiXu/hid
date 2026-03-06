package com.hid.tabletpen

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API =
            "https://api.github.com/repos/GeorgeZhiXu/hid/releases/latest"
    }

    data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val notes: String
    )

    private val handler = Handler(Looper.getMainLooper())

    fun checkInBackground(onUpdate: (UpdateInfo) -> Unit) {
        Thread({
            try {
                val info = check()
                if (info != null) {
                    handler.post { onUpdate(info) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Update check failed: ${e.message}")
            }
        }, "UpdateChecker").start()
    }

    private fun check(): UpdateInfo? {
        val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000

        try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.getString("tag_name")
            val remoteVersion = tag.removePrefix("v")

            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

            if (!isNewer(remoteVersion, currentVersion)) return null

            val assets = json.getJSONArray("assets")
            var downloadUrl = ""
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
            if (downloadUrl.isEmpty()) return null

            return UpdateInfo(
                version = remoteVersion,
                downloadUrl = downloadUrl,
                notes = json.optString("body", "")
            )
        } finally {
            conn.disconnect()
        }
    }

    internal fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    fun showUpdateDialog(info: UpdateInfo) {
        AlertDialog.Builder(context)
            .setTitle("Update available: v${info.version}")
            .setMessage(info.notes.ifEmpty { "A new version is available." })
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateInfo) {
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val label = TextView(context).apply {
            text = "Downloading..."
            setPadding(48, 16, 48, 0)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
            addView(label)
            addView(progressBar)
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Downloading update")
            .setView(layout)
            .setCancelable(false)
            .show()

        Thread({
            try {
                val conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.instanceFollowRedirects = true
                val totalSize = conn.contentLength
                val apkFile = File(context.cacheDir, "update-${info.version}.apk")

                conn.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalSize > 0) {
                                val pct = downloaded * 100 / totalSize
                                handler.post {
                                    progressBar.progress = pct
                                    label.text = "Downloading... ${pct}%"
                                }
                            }
                        }
                    }
                }

                handler.post {
                    dialog.dismiss()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                handler.post {
                    dialog.dismiss()
                    AlertDialog.Builder(context)
                        .setTitle("Update failed")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }, "UpdateDownloader").start()
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
