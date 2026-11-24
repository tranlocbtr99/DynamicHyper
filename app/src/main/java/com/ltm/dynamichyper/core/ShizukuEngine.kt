package com.ltm.dynamichyper.core

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Điều phối quyền hạn cấp cao qua Shizuku
 */
object ShizukuEngine {
    private const val TAG = "DynamicHyper:Shizuku"
    private val notifyMutex = Mutex()

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            if (Shizuku.isPreV11()) return false
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int = 1001) {
        try {
            if (isShizukuAvailable() && !hasShizukuPermission()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Request Shizuku permission failed", e)
        }
    }

    /**
     * Thực thi lệnh shell thông qua Shizuku Process an toàn
     */
    fun execCommand(command: String): Pair<Int, String> {
        return try {
            val method = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            val exitCode = process.waitFor()
            process.destroy()
            Pair(exitCode, output.toString().trim())
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to exec: $command", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Tự động áp dụng toàn bộ tối ưu hóa HyperOS chỉ với 1-chạm
     */
    fun applyOneTouchOptimizations(context: Context): Boolean {
        val packageName = context.packageName
        val commands = listOf(
            "cmd notification allow_listener $packageName/com.ltm.dynamichyper.service.DynamicNotificationService",
            "dumpsys deviceidle whitelist +$packageName",
            "setprop persist.sys.feature.island true",
            "resetprop persist.sys.feature.island true",
            "settings put system notification_focus_protocol 2",
            "cmd appops set $packageName AUTO_START allow",
            "cmd appops set $packageName 10008 allow",
            "cmd appops set $packageName 10020 allow",
            "cmd appops set $packageName 10021 allow",
            "cmd appops set $packageName 10033 allow",
            "cmd appops set $packageName 10042 allow",
            "cmd appops set $packageName SYSTEM_ALERT_WINDOW allow",
            "cmd appops set $packageName POST_PROMOTED_NOTIFICATIONS allow",
            "pm grant $packageName android.permission.POST_NOTIFICATIONS",
            "pm grant $packageName android.permission.POST_PROMOTED_NOTIFICATIONS"
        )

        var allSuccess = true
        for (cmd in commands) {
            val (code, out) = execCommand(cmd)
            Log.d(TAG, "Exec: $cmd -> code $code, out: $out")
            if (code != 0) {
                // Một số ROM không định nghĩa op appops đó -> không coi là thất bại toàn bộ
                AppLogger.w(TAG, "Bỏ qua lệnh không chạy được trên ROM này: $cmd (exit=$code ${out.take(80)})")
            }
        }

        applyXmsfBypass(context)
        return allSuccess
    }

    /**
     * Chặn mạng com.xiaomi.xmsf qua ShizukuHook (chuẩn HyperBridge IPC) để HyperOS AuthManager bỏ qua kiểm duyệt online.
     */
    fun applyXmsfBypass(context: Context): Boolean {
        try {
            val pm = context.packageManager
            val xmsfUid = try {
                pm.getPackageUid("com.xiaomi.xmsf", 0)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Không tìm thấy com.xiaomi.xmsf trên máy này: ${e.message}")
                return false
            }

            // 1. Thử qua Hook IPC ShizukuBinderWrapper trực tiếp (Chuẩn HyperBridge)
            val hookSuccess = try {
                ShizukuHook.setPackageNetworkingEnabled(xmsfUid, false)
            } catch (e: Throwable) {
                AppLogger.w(TAG, "ShizukuHook IPC thất bại, thử lệnh shell fallback: ${e.message}")
                false
            }

            if (hookSuccess) {
                AppLogger.i(TAG, "✓ Đã áp dụng XMSF Bypass qua ShizukuHook IPC thành công (uid=$xmsfUid)")
                return true
            }

            // 2. Fallback sang lệnh netpolicy / connectivity
            val (cNet, outNet) = execCommand("cmd netpolicy add restrict-background-blacklist $xmsfUid")
            if (cNet == 0) {
                AppLogger.i(TAG, "✓ Đã áp dụng XMSF Bypass qua cmd netpolicy blacklist (uid=$xmsfUid)")
                return true
            }

            val (c1, out1) = execCommand("cmd connectivity set-chain3-enabled true")
            if (c1 == 0) {
                val (c2, out2) = execCommand("cmd connectivity set-package-networking-enabled false com.xiaomi.xmsf")
                if (c2 == 0) {
                    AppLogger.i(TAG, "✓ Đã áp dụng XMSF Bypass qua cmd connectivity chain3")
                    return true
                }
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Lỗi khi chạy applyXmsfBypass: ${e.message}", e)
        }
        return false
    }

    /**
     * Bật / Tắt chặn màn hình bung toàn màn hình khi cuộc gọi đến
     * Giúp giữ cuộc gọi gọn gàng trên Đảo Động, không gián đoạn app đang dùng
     */
    fun setFullScreenCallBlocked(packageName: String, blocked: Boolean): Boolean {
        val mode = if (blocked) "ignore" else "allow"
        val (code, _) = execCommand("cmd appops set $packageName USE_FULL_SCREEN_INTENT $mode")
        return code == 0
    }

    fun blockAllFullScreenCalls(blocked: Boolean): Boolean {
        val callApps = listOf(
            "com.zing.zalo",
            "com.facebook.orca",
            "org.telegram.messenger",
            "com.whatsapp"
        )
        var allSuccess = true
        for (pkg in callApps) {
            val s = setFullScreenCallBlocked(pkg, blocked)
            if (!s) allSuccess = false
        }
        Log.i(TAG, "Block all full screen calls ($blocked): $allSuccess")
        return allSuccess
    }

    /**
     * Gửi thông báo an toàn bằng cách tạm thời cô lập mạng của com.xiaomi.xmsf (50ms)
     * để AuthManager trên HyperOS China ROM bỏ qua bước kiểm duyệt Cloud (-300 error),
     * sau đó mở lại mạng bình thường sau 100ms. Chuẩn 100% HyperBridge.
     */
    suspend fun postNotificationSafely(context: Context, id: Int, notification: Notification) {
        notifyMutex.withLock {
            val pm = context.packageManager
            val xmsfUid = try {
                pm.getPackageUid("com.xiaomi.xmsf", 0)
            } catch (_: Exception) {
                null
            }

            if (hasShizukuPermission() && xmsfUid != null) {
                try {
                    ShizukuHook.setPackageNetworkingEnabled(xmsfUid, false)
                    delay(50L)
                    NotificationManagerCompat.from(context).notify(id, notification)
                    Log.d(TAG, "✓ Đã bắn Focus Notification với XMSF bypass (id=$id)")
                } catch (e: Throwable) {
                    Log.w(TAG, "Lỗi bắn thông báo XMSF bypass: ${e.message}")
                    NotificationManagerCompat.from(context).notify(id, notification)
                } finally {
                    delay(100L)
                    try {
                        ShizukuHook.setPackageNetworkingEnabled(xmsfUid, true)
                    } catch (_: Throwable) {}
                }
            } else {
                NotificationManagerCompat.from(context).notify(id, notification)
            }
        }
    }
}
