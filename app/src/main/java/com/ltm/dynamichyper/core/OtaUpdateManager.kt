package com.ltm.dynamichyper.core

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val downloadUrl: String,
    val changelog: String
)

object OtaUpdateManager {
    private const val TAG = "DynamicHyper:OTA"
    private const val GITHUB_REPO = "tranlocbtr99/DynamicHyper"
    private const val CURRENT_VERSION = "1.5.1"

    suspend fun checkUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DynamicHyper-App")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (connection.responseCode == 200) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(json).asJsonObject
                val tagName = root.get("tag_name")?.asString?.removePrefix("v") ?: ""
                val body = root.get("body")?.asString ?: "Cập nhật hiệu năng và sửa lỗi."

                var downloadUrl = ""
                val assets = root.getAsJsonArray("assets")
                if (assets != null && assets.size() > 0) {
                    for (asset in assets) {
                        val assetObj = asset.asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.endsWith(".apk")) {
                            downloadUrl = assetObj.get("browser_download_url")?.asString ?: ""
                            break
                        }
                    }
                }

                val hasNewer = isNewerVersion(CURRENT_VERSION, tagName)
                UpdateInfo(
                    hasUpdate = hasNewer,
                    latestVersion = tagName,
                    downloadUrl = downloadUrl,
                    changelog = body
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update: ${e.message}")
            null
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (latest.isBlank()) return false
        val curParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latParts = latest.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(curParts.size, latParts.size)
        for (i in 0 until size) {
            val c = curParts.getOrElse(i) { 0 }
            val l = latParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun startDownloadAndInstall(context: Context, downloadUrl: String) {
        try {
            val uri = Uri.parse(downloadUrl)
            val request = DownloadManager.Request(uri).apply {
                setTitle("Dynamic Hyper Cập Nhật")
                setDescription("Đang tải bản cập nhật mới nhất...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "DynamicHyper-Update.apk")
                setMimeType("application/vnd.android.package-archive")
            }

            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = manager.enqueue(request)

            val onComplete = object : BroadcastReceiver() {
                override fun onReceive(ctxt: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        try {
                            context.unregisterReceiver(this)
                        } catch (_: Exception) {}

                        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DynamicHyper-Update.apk")
                        if (file.exists()) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                Toast.makeText(context, "Vui lòng cấp quyền 'Cài đặt ứng dụng không rõ nguồn' cho Dynamic Hyper để cập nhật!", Toast.LENGTH_LONG).show()
                                val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(permissionIntent)
                                return
                            }
                            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(contentUri, "application/vnd.android.package-archive")
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }
                            context.startActivity(installIntent)
                        }
                    }
                }
            }
            context.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting download: ${e.message}")
        }
    }
}

