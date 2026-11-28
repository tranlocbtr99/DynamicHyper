package com.ltm.dynamichyper.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private const val CHANNEL_ID = "ota_update_channel"
    private const val NOTIFICATION_ID = 2026

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.5.3"
        } catch (_: Exception) {
            "1.5.3"
        }
    }

    suspend fun checkUpdate(context: Context? = null): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val curVer = if (context != null) getCurrentVersion(context) else "1.5.4"
            val isBetaChannel = curVer.contains("beta", ignoreCase = true)

            // Nếu đang dùng bản beta -> gọi endpoint danh sách releases để lấy bản beta mới nhất
            // Nếu bản chính thức -> gọi latest release
            val apiUrl = if (isBetaChannel) {
                "https://api.github.com/repos/$GITHUB_REPO/releases"
            } else {
                "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
            }

            val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DynamicHyper-App")
                connectTimeout = 8000
                readTimeout = 8000
            }

            if (connection.responseCode == 200) {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                val rootObj = if (isBetaChannel) {
                    val releases = JsonParser.parseString(json).asJsonArray
                    // Tìm release beta mới nhất hoặc release đầu tiên
                    var chosen = releases[0].asJsonObject
                    for (rel in releases) {
                        val relObj = rel.asJsonObject
                        val t = relObj.get("tag_name")?.asString ?: ""
                        if (t.contains("beta", ignoreCase = true)) {
                            chosen = relObj
                            break
                        }
                    }
                    chosen
                } else {
                    JsonParser.parseString(json).asJsonObject
                }

                val tagName = rootObj.get("tag_name")?.asString?.removePrefix("v") ?: ""
                val body = rootObj.get("body")?.asString ?: "Cập nhật hiệu năng và sửa lỗi."

                var downloadUrl = ""
                val assets = rootObj.getAsJsonArray("assets")
                if (assets != null && assets.size() > 0) {
                    for (asset in assets) {
                        val assetObj = asset.asJsonObject
                        val name = assetObj.get("name")?.asString ?: ""
                        if (name.endsWith(".apk")) {
                            downloadUrl = assetObj.get("browser_download_url")?.asString ?: ""
                            break
                        }
                    }
                    if (downloadUrl.isBlank()) {
                        downloadUrl = "https://raw.githubusercontent.com/$GITHUB_REPO/main/release/DynamicHyper-v${tagName.removePrefix("v")}.apk"
                    }
                }

                val hasNewer = isNewerVersion(curVer, tagName)
                UpdateInfo(
                    hasUpdate = hasNewer,
                    latestVersion = tagName,
                    downloadUrl = downloadUrl,
                    changelog = body
                )
            } else {
                checkRawVersionFallback(curVer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update: ${e.message}")
            null
        }
    }

    private fun checkRawVersionFallback(curVer: String): UpdateInfo? {
        return try {
            val url = URL("https://raw.githubusercontent.com/$GITHUB_REPO/main/version.json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
            }
            if (conn.responseCode == 200) {
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JsonParser.parseString(json).asJsonObject
                val latest = obj.get("version")?.asString?.removePrefix("v") ?: return null
                val dl = obj.get("downloadUrl")?.asString
                    ?: "https://raw.githubusercontent.com/$GITHUB_REPO/main/release/DynamicHyper-v$latest.apk"
                val notes = obj.get("changelog")?.asString ?: "Cập nhật hiệu năng và tính năng mới."
                UpdateInfo(
                    hasUpdate = isNewerVersion(curVer, latest),
                    latestVersion = latest,
                    downloadUrl = dl,
                    changelog = notes
                )
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        if (latest.isBlank()) return false
        if (current == latest) return false

        // Chuẩn hóa dạng số (loại bỏ -beta hoặc v)
        val curClean = current.substringBefore("-").removePrefix("v")
        val latClean = latest.substringBefore("-").removePrefix("v")

        val curParts = curClean.split(".").mapNotNull { it.toIntOrNull() }
        val latParts = latClean.split(".").mapNotNull { it.toIntOrNull() }

        val size = maxOf(curParts.size, latParts.size)
        for (i in 0 until size) {
            val c = curParts.getOrElse(i) { 0 }
            val l = latParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }

        // Nếu phần số bằng nhau (ví dụ 1.5.4 và 1.5.4-beta), so sánh hậu tố
        val curBetaNum = current.substringAfter("beta", "").filter { it.isDigit() }.toIntOrNull() ?: 0
        val latBetaNum = latest.substringAfter("beta", "").filter { it.isDigit() }.toIntOrNull() ?: 0
        if (latBetaNum > curBetaNum) return true

        return false
    }

    /**
     * Mở trình duyệt ngoài để người dùng tải APK trực tiếp làm phương án dự phòng
     */
    fun openInBrowser(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open browser: ${e.message}")
            Toast.makeText(context, "Không thể mở trình duyệt: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Tải tệp APK trực tiếp qua Coroutine an toàn trong thư mục app-specific
     * Tránh hoàn toàn lỗi Scoped Storage EACCES (Permission Denied) và lỗi parse package
     */
    fun startDownloadAndInstall(context: Context, downloadUrl: String) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            val notifManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Dynamic Hyper Cập Nhật",
                    NotificationManager.IMPORTANCE_LOW
                )
                notifManager.createNotificationChannel(channel)
            }

            val notifBuilder = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Dynamic Hyper Đang Tải Cập Nhật")
                .setContentText("Đang kết nối máy chủ...")
                .setProgress(100, 0, true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            try {
                notifManager.notify(NOTIFICATION_ID, notifBuilder.build())
            } catch (_: Exception) {}

            try {
                // Theo dõi HTTP Redirect (GitHub Releases luôn redirect 302 sang AWS S3 / objects.githubusercontent)
                var currentUrl = downloadUrl
                var connection: HttpURLConnection
                var redirectCount = 0

                while (true) {
                    val url = URL(currentUrl)
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "DynamicHyper-App")
                        connectTimeout = 15000
                        readTimeout = 30000
                    }

                    val code = connection.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 || code == 308
                    ) {
                        val location = connection.getHeaderField("Location")
                        connection.disconnect()
                        if (location != null && redirectCount < 5) {
                            currentUrl = location
                            redirectCount++
                            continue
                        }
                    }
                    break
                }

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Không thể tải bản cập nhật (Mã lỗi ${connection.responseCode}). Đang mở trình duyệt...", Toast.LENGTH_LONG).show()
                        openInBrowser(appContext, downloadUrl)
                    }
                    notifManager.cancel(NOTIFICATION_ID)
                    return@launch
                }

                // Dùng thư mục app-specific ngoài (không bị giới hạn Scoped Storage EACCES trên Android 11+)
                val storageDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.cacheDir
                if (!storageDir.exists()) storageDir.mkdirs()

                val apkFile = File(storageDir, "DynamicHyper-Update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                val contentLength = connection.contentLength
                var totalBytesRead = 0L
                var lastProgress = 0

                connection.inputStream.use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (contentLength > 0) {
                                val progress = ((totalBytesRead * 100) / contentLength).toInt()
                                if (progress >= lastProgress + 5) {
                                    lastProgress = progress
                                    notifBuilder.setProgress(100, progress, false)
                                        .setContentText("Đã tải $progress%")
                                    try {
                                        notifManager.notify(NOTIFICATION_ID, notifBuilder.build())
                                    } catch (_: Exception) {}
                                }
                            }
                        }
                        output.flush()
                    }
                }

                notifManager.cancel(NOTIFICATION_ID)

                // Kiểm tra kích thước và tính toàn vẹn của tệp APK
                if (!apkFile.exists() || apkFile.length() < 1_000_000) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Tệp tải về chưa hoàn tất. Đang chuyển sang trình duyệt...", Toast.LENGTH_LONG).show()
                        openInBrowser(appContext, downloadUrl)
                    }
                    return@launch
                }

                // Kiểm tra giải mã tệp APK bằng Android PackageManager để tránh lỗi "There was a problem parsing the package"
                val archiveInfo = appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
                if (archiveInfo == null) {
                    Log.e(TAG, "Corrupted APK file downloaded")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appContext, "Tệp APK bị hỏng hoặc không tương thích. Đang mở trình duyệt tải trực tiếp...", Toast.LENGTH_LONG).show()
                        openInBrowser(appContext, downloadUrl)
                    }
                    return@launch
                }

                // Tiến hành cài đặt
                withContext(Dispatchers.Main) {
                    installApk(appContext, apkFile)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error in OTA download", e)
                notifManager.cancel(NOTIFICATION_ID)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Lỗi tải cập nhật: ${e.message}. Đang mở trình duyệt...", Toast.LENGTH_LONG).show()
                    openInBrowser(appContext, downloadUrl)
                }
            }
        }
    }

    /**
     * Khởi chạy Intent cài đặt hoặc dùng Shizuku nếu đã cấp quyền
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            // 1. Thử cài đặt trực tiếp qua Shizuku nếu đang kích hoạt (không cần bấm xác nhận)
            if (ShizukuEngine.isShizukuAvailable() && ShizukuEngine.hasShizukuPermission()) {
                val (exitCode, output) = ShizukuEngine.execCommand("pm install -r \"${apkFile.absolutePath}\"")
                if (exitCode == 0) {
                    Toast.makeText(context, "Đã cập nhật thành công qua Shizuku!", Toast.LENGTH_SHORT).show()
                    return
                } else {
                    Log.w(TAG, "Shizuku install failed: $output, falling back to standard installer")
                }
            }

            // 2. Kiểm tra quyền 'Cài đặt ứng dụng không rõ nguồn' trên Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                Toast.makeText(context, "Vui lòng cho phép 'Cài đặt ứng dụng không rõ nguồn' cho Dynamic Hyper để cập nhật!", Toast.LENGTH_LONG).show()
                val permIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(permIntent)
                return
            }

            // 3. Sử dụng FileProvider an toàn
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }

            // Cấp quyền đọc rõ ràng cho toàn bộ package installer của Xiaomi/Google
            val resolveInfos = context.packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in resolveInfos) {
                context.grantUriPermission(info.activityInfo.packageName, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)

        } catch (e: Exception) {
            Log.e(TAG, "Cannot launch installer", e)
            Toast.makeText(context, "Không thể mở trình cài đặt: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
