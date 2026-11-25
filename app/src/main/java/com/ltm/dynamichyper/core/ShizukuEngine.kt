package com.ltm.dynamichyper.core

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku

/**
 * Điều phối quyền hạn cấp cao qua Shizuku.
 * Hỗ trợ cơ chế "Cấp 1 Lần — Hoạt Động Độc Lập": Sau khi đã chạy tối ưu hóa & khóa mạng XMSF 1 lần,
 * các luật tường lửa (OEM_DENY_CHAIN, netpolicy restrict-background-blacklist, AppOps) được lưu trong
 * system_server của Android nên ứng dụng vẫn hiển thị Đảo Động 24/7 kể cả khi người dùng tắt hoàn toàn
 * Gỡ lỗi không dây (Wireless Debugging) và dừng Shizuku.
 */
object ShizukuEngine {
    private const val TAG = "DynamicHyper:Shizuku"
    private const val PREFS_NAME = "shizuku_one_time_state"
    private const val KEY_ACTIVATED_EVER = "one_time_activated_ever"
    private const val KEY_ACTIVATED_BOOT_COUNT = "one_time_boot_count"
    private const val KEY_ACTIVATED_TIMESTAMP = "one_time_timestamp"

    private val notifyMutex = Mutex()
    private val postedActiveIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun clearPostedActiveId(id: Int) {
        postedActiveIds.remove(id)
    }

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

    fun getCurrentBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Throwable) {
        -1
    }

    /**
     * Ghi nhận đã kích hoạt Shizuku 1 lần thành công (kèm BOOT_COUNT hiện tại).
     */
    fun markOneTimeActivated(context: Context) {
        try {
            val bootCount = getCurrentBootCount(context)
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ACTIVATED_EVER, true)
                .putInt(KEY_ACTIVATED_BOOT_COUNT, bootCount)
                .putLong(KEY_ACTIVATED_TIMESTAMP, System.currentTimeMillis())
                .apply()
            XmsfNetworkHelper.markBlockedInSession()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to persist one-time activation state: ${e.message}")
        }
    }

    /**
     * Kiểm tra xem máy đã từng được kích hoạt tối ưu hóa 1-chạm qua Shizuku hay chưa.
     * Lưu ý: Các quyền AppOps (10008, 10020, 10033), POST_PROMOTED_NOTIFICATIONS, deviceidle whitelist,
     * notification_focus_protocol và netpolicy restrict-background-blacklist đều được Android lưu
     * bền vững trong /data/system/ ngay cả khi tắt Shizuku.
     */
    fun isOneTimeOptimizationActive(context: Context): Boolean {
        if (hasShizukuPermission()) return true
        return try {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.getBoolean(KEY_ACTIVATED_EVER, false)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Kiểm tra xem lần kích hoạt Shizuku gần nhất có nằm trong cùng chu kỳ khởi động máy (cùng BOOT_COUNT) hay không.
     * Trong cùng chu kỳ boot, cả rule OEM_DENY_CHAIN trong RAM của ConnectivityService lẫn netpolicy trên đĩa đều còn nguyên.
     */
    fun isIsolatedInCurrentBoot(context: Context): Boolean {
        return try {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!sp.getBoolean(KEY_ACTIVATED_EVER, false)) return false
            val savedBoot = sp.getInt(KEY_ACTIVATED_BOOT_COUNT, -2)
            val currentBoot = getCurrentBootCount(context)
            savedBoot != -2 && savedBoot == currentBoot
        } catch (_: Throwable) {
            false
        }
    }

    fun getLastActivatedTimestamp(context: Context): Long = try {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_ACTIVATED_TIMESTAMP, 0L)
    } catch (_: Throwable) {
        0L
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
     * Tự động áp dụng toàn bộ tối ưu hóa HyperOS chỉ với 1-chạm.
     * Sau khi hàm này chạy xong, người dùng có thể tắt Gỡ lỗi không dây (Wireless Debugging) và tắt Shizuku.
     */
    fun applyOneTouchOptimizations(context: Context): Boolean {
        val packageName = context.packageName
        val targetProtocol = FocusProtocolHelper.getFocusProtocolVersion(context)

        val commands = listOf(
            "cmd notification allow_listener $packageName/com.ltm.dynamichyper.service.DynamicNotificationService",
            "dumpsys deviceidle whitelist +$packageName",
            "setprop persist.sys.feature.island true",
            "resetprop persist.sys.feature.island true",
            "settings put system notification_focus_protocol $targetProtocol",
            "cmd appops set $packageName AUTO_START allow",
            "cmd appops set $packageName RUN_IN_BACKGROUND allow",
            "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow",
            "cmd appops set $packageName START_FOREGROUND allow",
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

        var successCount = 0
        for (cmd in commands) {
            val (code, out) = execCommand(cmd)
            Log.d(TAG, "Exec: $cmd -> code $code, out: $out")
            if (code == 0) {
                successCount++
            } else {
                AppLogger.w(TAG, "Bỏ qua lệnh không chạy được trên ROM này: $cmd (exit=$code ${out.take(80)})")
            }
        }

        val xmsfOk = applyXmsfBypass(context)
        if (successCount > 0 || xmsfOk) {
            markOneTimeActivated(context)
            AppLogger.i(
                TAG,
                "✓ Đã kích hoạt Chế độ Độc Lập 1-Chạm ($successCount lệnh, khóa XMSF=$xmsfOk). " +
                    "Có thể tắt Wireless Debugging & Shizuku mà Đảo vẫn hiển thị 24/7!"
            )
        }
        return successCount > 0 || xmsfOk
    }

    /**
     * Khóa mạng vĩnh viễn cho com.xiaomi.xmsf qua 4 tầng hệ thống:
     * 1. ShizukuHook IPC (ConnectivityManager / NetworkManagementService OEM_DENY_CHAIN)
     * 2. Connectivity shell chain-3 & package-networking-enabled false
     * 3. NetworkPolicy restrict-background-blacklist (lưu bền vững trong /data/system/netpolicy.xml)
     * 4. AppOps RUN_IN_BACKGROUND & RUN_ANY_IN_BACKGROUND ignore + force-stop
     *
     * TUYỆT ĐỐI KHÔNG mở lại mạng cho XMSF sau khi gửi thông báo, giúp hiệu lực khóa duy trì
     * ngay cả khi người dùng đã tắt hoàn toàn Shizuku và Wireless Debugging.
     */
    fun applyXmsfBypass(context: Context): Boolean {
        try {
            val pm = context.packageManager
            val xmsfUid = try {
                pm.getPackageUid("com.xiaomi.xmsf", 0)
            } catch (e: Exception) {
                AppLogger.i(TAG, "Máy không có com.xiaomi.xmsf (Global/Custom ROM) -> không cần khóa XMSF")
                markOneTimeActivated(context)
                return true
            }

            var anySuccess = false

            // 1. Hook IPC ShizukuBinderWrapper trực tiếp
            try {
                if (ShizukuHook.setPackageNetworkingEnabled(xmsfUid, false)) {
                    AppLogger.i(TAG, "✓ Đã khóa mạng XMSF vĩnh viễn qua ShizukuHook IPC (uid=$xmsfUid)")
                    anySuccess = true
                }
            } catch (e: Throwable) {
                AppLogger.w(TAG, "ShizukuHook IPC thất bại, tiếp tục shell fallback: ${e.message}")
            }

            // 2. Shell fallback đa tầng bền vững: connectivity chain3 + netpolicy blacklist + appops + force-stop
            val (c1, _) = execCommand("cmd connectivity set-chain3-enabled true")
            val (c2, _) = execCommand("cmd connectivity set-package-networking-enabled false com.xiaomi.xmsf")
            val (cNet, _) = execCommand("cmd netpolicy add restrict-background-blacklist $xmsfUid")
            execCommand("cmd appops set com.xiaomi.xmsf RUN_IN_BACKGROUND ignore")
            execCommand("cmd appops set com.xiaomi.xmsf RUN_ANY_IN_BACKGROUND ignore")
            execCommand("am force-stop com.xiaomi.xmsf")

            if (c1 == 0 || c2 == 0 || cNet == 0) {
                AppLogger.i(TAG, "✓ Đã khóa mạng XMSF vĩnh viễn qua Shell & NetPolicy (uid=$xmsfUid)")
                anySuccess = true
            }

            if (anySuccess) {
                markOneTimeActivated(context)
            }
            return anySuccess
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Lỗi khi chạy applyXmsfBypass: ${e.message}", e)
        }
        return false
    }

    /**
     * Bật / Tắt chặn màn hình bung toàn màn hình khi cuộc gọi đến
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
     * Gửi thông báo Đảo Tiêu Điểm an toàn:
     * - Nếu Shizuku đang bật: Đảm bảo XMSF đã bị khóa vĩnh viễn (ensureXmsfBlocked) và TUYỆT ĐỐI KHÔNG mở lại mạng,
     *   sau đó bắn thông báo ngay lập tức.
     * - Nếu Shizuku đã tắt (người dùng đã tắt Wireless Debugging): Bắn thẳng thông báo với độ trễ 0ms.
     *   Nhờ luật khóa XMSF không bị mở lại ở lần gửi trước, system_server vẫn giữ trạng thái khóa XMSF
     *   và cho phép Đảo hiển thị bình thường!
     */
    suspend fun postNotificationSafely(context: Context, id: Int, notification: Notification) {
        val isOnlyAlertOnce = (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        val alreadyActive = postedActiveIds.contains(id)

        // Cơ chế In-Place Update của HyperBridge:
        // Khi một notificationId đã được đăng lên thanh trạng thái rồi và lần gọi này chỉ là cập nhật tại chỗ
        // (onlyAlertOnce = true), SystemUI cập nhật thẳng nội dung Đảo mà không gọi lại AuthManager/XMSF.
        if (alreadyActive && isOnlyAlertOnce && XmsfNetworkHelper.isBlockedInCurrentSession) {
            NotificationManagerCompat.from(context).notify(id, notification)
            IslandProbe.track(context, id, "in-place update (0ms, XMSF đã khóa)")
            return
        }

        var path: String
        notifyMutex.withLock {
            val hasXmsf = RomDiagnostics.isXmsfInstalled(context)
            if (hasShizukuPermission()) {
                val isolated = if (hasXmsf) {
                    XmsfNetworkHelper.ensureXmsfBlocked(context)
                } else {
                    true
                }
                if (isolated) {
                    markOneTimeActivated(context)
                } else if (hasXmsf) {
                    AppLogger.w(TAG, "Khóa mạng XMSF thất bại (id=$id): đảo có thể bị XMSF xóa")
                }
                NotificationManagerCompat.from(context).notify(id, notification)
                postedActiveIds.add(id)
                Log.d(TAG, "✓ Đã bắn Focus Notification thành công (id=$id, keepBlocked=true)")
                path = when {
                    !hasXmsf -> "direct (máy không có XMSF)"
                    isolated -> "đã khóa XMSF vĩnh viễn (Shizuku đang bật)"
                    else -> "cách ly XMSF FAILED"
                }
            } else {
                // Shizuku đã tắt (người dùng đã tắt Wireless Debugging) hoặc máy chưa cài Shizuku -> Bắn trực tiếp 0ms
                NotificationManagerCompat.from(context).notify(id, notification)
                postedActiveIds.add(id)
                val sameBoot = isIsolatedInCurrentBoot(context)
                val everActivated = isOneTimeOptimizationActive(context)
                Log.d(TAG, "✓ Đã bắn Focus Notification ở chế độ độc lập / Hybrid LiveUpdate (id=$id, sameBoot=$sameBoot, ever=$everActivated)")
                path = when {
                    !hasXmsf -> "direct (máy không có XMSF)"
                    sameBoot -> "chế độ độc lập (XMSF đã khóa từ trước, Shizuku tắt)"
                    everActivated -> "chế độ độc lập sau khởi động lại (NetPolicy/AppOps, Shizuku tắt)"
                    else -> "hybrid Focus + Android 16 Promoted LiveUpdate (chưa từng kích hoạt Shizuku)"
                }
            }
        }
        // Dò xem đảo có sống sót sau khi gửi không
        IslandProbe.track(context, id, path)
    }
}
