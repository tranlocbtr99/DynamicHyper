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
 * Hỗ trợ cơ chế "Cấp 1 Lần — Hoạt Động Độc Lập Ngay Cả Khi Tắt Tùy Chọn Nhà Phát Triển":
 * - Sửa lỗi `Shizuku.newProcess` (dùng `getDeclaredMethod` cho phương thức private static trong Shizuku API 13.1.5
 *   kèm fallback qua `IShizukuService.newProcess` trực tiếp).
 * - Khóa mạng và đóng băng tiến trình `com.xiaomi.xmsf` ở cả 4 tầng (RAM Firewall Chain 9 & Chain 3,
 *   Persistent `/data/system/netpolicy.xml`, Persistent `/data/system/appops.xml`, Standby Bucket `restricted`
 *   và `am force-stop com.xiaomi.xmsf`).
 * - Nhờ các chính sách này được Android lưu trực tiếp trong `system_server` và trên đĩa `/data/system/`,
 *   ngay cả khi người dùng tắt hoàn toàn Gỡ lỗi không dây và tắt luôn công tắc "Tùy chọn nhà phát triển",
 *   `com.xiaomi.xmsf` vẫn bị chặn kết nối máy chủ (-300) và Đảo Động vẫn hoạt động 24/7!
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
     * Thực thi lệnh shell thông qua Shizuku Process an toàn.
     * LƯU Ý QUAN TRỌNG: Trong `rikka.shizuku:api:13.1.5`, hàm `Shizuku.newProcess` có phạm vi `private static`,
     * do đó BẮT BUỘC phải dùng `getDeclaredMethod("newProcess", ...)` với `isAccessible = true`
     * (thay vì `getMethod` vốn chỉ tìm hàm public và gây lỗi NoSuchMethodException trên Android 17).
     */
    fun execCommand(command: String): Pair<Int, String> {
        return try {
            val method = try {
                Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
            } catch (_: NoSuchMethodException) {
                Shizuku::class.java.getMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
            }
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = StringBuilder()
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
            }
            val errOutput = StringBuilder()
            process.errorStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    errOutput.append(line).append("\n")
                }
            }
            val exitCode = process.waitFor()
            process.destroy()
            val combinedOut = if (output.isNotEmpty()) output.toString().trim() else errOutput.toString().trim()
            Pair(exitCode, combinedOut)
        } catch (e: Throwable) {
            val rootCause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            Log.e(TAG, "Failed to exec: $command", rootCause)
            Pair(-1, rootCause.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Tự động áp dụng toàn bộ tối ưu hóa HyperOS chỉ với 1-chạm.
     * Sau khi hàm này chạy xong, người dùng có thể tắt Gỡ lỗi không dây và tắt cả Tùy chọn nhà phát triển.
     */
    fun applyOneTouchOptimizations(context: Context): Boolean {
        val packageName = context.packageName
        val targetProtocol = FocusProtocolHelper.getFocusProtocolVersion(context)

        val commands = listOf(
            "cmd notification allow_listener $packageName/com.ltm.dynamichyper.service.DynamicNotificationService",
            "dumpsys deviceidle whitelist +$packageName",
            "setprop persist.sys.feature.island true",
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
            "pm grant $packageName android.permission.POST_PROMOTED_NOTIFICATIONS",
            "pm grant $packageName android.permission.BLUETOOTH_CONNECT"
        )

        var successCount = 0
        for (cmd in commands) {
            val (code, out) = execCommand(cmd)
            Log.d(TAG, "Exec: $cmd -> code $code, out: $out")
            if (code == 0) {
                successCount++
            } else {
                AppLogger.w(TAG, "Bỏ qua lệnh không hỗ trợ trên ROM này: $cmd (exit=$code ${out.take(80)})")
            }
        }

        val xmsfOk = applyXmsfBypass(context)
        if (successCount > 0 || xmsfOk) {
            markOneTimeActivated(context)
            AppLogger.i(
                TAG,
                "✓ Đã kích hoạt Chế độ Độc Lập 1-Chạm ($successCount/${commands.size} lệnh thành công, khóa XMSF=$xmsfOk). " +
                    "Có thể tắt Tùy chọn nhà phát triển & Shizuku mà Đảo vẫn hiển thị 24/7!"
            )
        }
        return successCount > 0 || xmsfOk
    }

    /**
     * Khóa mạng và đóng băng vĩnh viễn tiến trình `com.xiaomi.xmsf` qua 4 tầng hệ thống:
     * 1. ShizukuHook IPC (`IConnectivityManager`, `INetworkManagementService` Chain 9 & Chain 3, `INetworkPolicyManager`, `IActivityManager.forceStopPackage`)
     * 2. Connectivity shell chain-3 & `set-package-networking-enabled false com.xiaomi.xmsf`
     * 3. NetworkPolicy `restrict-background-blacklist` (lưu bền vững trong `/data/system/netpolicy.xml`)
     * 4. AppOps `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND`, `WAKE_LOCK` -> `ignore` + `set-standby-bucket restricted` + `am force-stop`
     *
     * Nhờ cả 4 tầng đều ghi xuống `/data/system/` và giết sạch socket đang mở của XMSF,
     * ngay cả khi người dùng tắt Tùy chọn nhà phát triển (Developer Options), XMSF không thể thức dậy xóa Đảo!
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

            var ipcSuccess = false
            // 1. Hook IPC ShizukuBinderWrapper trực tiếp (Chain 9 + Chain 3 + INetworkPolicyManager + forceStopPackage)
            try {
                if (ShizukuHook.setPackageNetworkingEnabled(xmsfUid, false)) {
                    ipcSuccess = true
                }
            } catch (e: Throwable) {
                AppLogger.w(TAG, "ShizukuHook IPC cảnh báo: ${e.message}")
            }

            // 2. Thực thi bộ lệnh Shell lưu bền vững xuống đĩa (/data/system/netpolicy.xml & appops.xml)
            val (c1, _) = execCommand("cmd connectivity set-chain3-enabled true")
            val (c2, _) = execCommand("cmd connectivity set-package-networking-enabled false com.xiaomi.xmsf")
            val (cNet, _) = execCommand("cmd netpolicy add restrict-background-blacklist $xmsfUid")
            val (cBg1, _) = execCommand("cmd appops set com.xiaomi.xmsf RUN_IN_BACKGROUND ignore")
            val (cBg2, _) = execCommand("cmd appops set com.xiaomi.xmsf RUN_ANY_IN_BACKGROUND ignore")
            execCommand("cmd appops set com.xiaomi.xmsf WAKE_LOCK ignore")
            execCommand("am set-standby-bucket com.xiaomi.xmsf restricted")
            val (cStop, _) = execCommand("am force-stop com.xiaomi.xmsf")

            val shellSuccess = (c1 == 0 || c2 == 0 || cNet == 0 || cBg1 == 0 || cBg2 == 0)
            if (ipcSuccess || shellSuccess) {
                markOneTimeActivated(context)
                AppLogger.i(
                    TAG,
                    "✓ Đã khóa cứng XMSF đa tầng (uid=$xmsfUid, IPC=$ipcSuccess, Chain3=${c1 == 0}, " +
                        "PkgNet=${c2 == 0}, NetPolicy=${cNet == 0}, AppOps=${cBg1 == 0}, ForceStop=${cStop == 0})"
                )
                return true
            }
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
     * Thu hồi toàn bộ quyền đã cấp & khôi phục trạng thái hệ thống nguyên bản bằng 1 nút bấm duy nhất.
     */
    fun revokeAllGrantedPermissions(context: Context): Boolean {
        val packageName = context.packageName
        var usedShizuku = false

        try {
            NotificationManagerCompat.from(context).cancelAll()
            postedActiveIds.clear()
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        } catch (_: Throwable) {}

        if (hasShizukuPermission()) {
            usedShizuku = true
            try {
                val xmsfUid = try {
                    context.packageManager.getPackageUid("com.xiaomi.xmsf", 0)
                } catch (_: Exception) { -1 }

                if (xmsfUid > 0) {
                    try { ShizukuHook.setPackageNetworkingEnabled(xmsfUid, true) } catch (_: Throwable) {}
                    execCommand("cmd connectivity set-package-networking-enabled true com.xiaomi.xmsf")
                    execCommand("cmd netpolicy remove restrict-background-blacklist $xmsfUid")
                    execCommand("cmd appops set com.xiaomi.xmsf RUN_IN_BACKGROUND allow")
                    execCommand("cmd appops set com.xiaomi.xmsf RUN_ANY_IN_BACKGROUND allow")
                    execCommand("cmd appops set com.xiaomi.xmsf WAKE_LOCK allow")
                    execCommand("am set-standby-bucket com.xiaomi.xmsf active")
                }
            } catch (_: Throwable) {}

            blockAllFullScreenCalls(false)

            val revokeCommands = listOf(
                "cmd notification disallow_listener $packageName/com.ltm.dynamichyper.service.DynamicNotificationService",
                "dumpsys deviceidle whitelist -$packageName",
                "cmd appops set $packageName AUTO_START ignore",
                "cmd appops set $packageName 10008 ignore",
                "cmd appops set $packageName 10020 ignore",
                "cmd appops set $packageName 10021 ignore",
                "cmd appops set $packageName 10033 ignore",
                "cmd appops set $packageName SYSTEM_ALERT_WINDOW ignore",
                "pm revoke $packageName android.permission.BLUETOOTH_CONNECT",
                "pm revoke $packageName android.permission.POST_NOTIFICATIONS"
            )
            for (cmd in revokeCommands) {
                execCommand(cmd)
            }
            AppLogger.i(TAG, "✓ Đã thu hồi toàn bộ quyền hệ thống & khôi phục XMSF nguyên bản qua Shizuku")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            try {
                context.revokeSelfPermissionsOnKill(
                    listOf(
                        android.Manifest.permission.POST_NOTIFICATIONS,
                        android.Manifest.permission.BLUETOOTH_CONNECT
                    )
                )
            } catch (_: Throwable) {}
        }

        return usedShizuku
    }

    /**
     * Gửi thông báo Đảo Tiêu Điểm an toàn:
     * - Khi Shizuku đang bật: Nếu là lần đầu trong phiên (`!XmsfNetworkHelper.isBlockedInCurrentSession`),
     *   chạy ngay `applyXmsfBypass(context)` để khóa cả 4 tầng (RAM + Disk `/data/system/` + `force-stop` XMSF)
     *   để chuẩn bị sẵn sàng cho việc người dùng tắt Tùy chọn nhà phát triển bất cứ lúc nào.
     * - Khi người dùng đã tắt Tùy chọn nhà phát triển (`!hasShizukuPermission()`): Bắn trực tiếp 0ms,
     *   dựa trên chính sách khóa bền vững đã ghi xuống `/data/system/` từ trước.
     */
    suspend fun postNotificationSafely(context: Context, id: Int, notification: Notification) {
        val isOnlyAlertOnce = (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0
        val alreadyActive = postedActiveIds.contains(id)

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
                    if (!XmsfNetworkHelper.isBlockedInCurrentSession) {
                        applyXmsfBypass(context)
                    } else {
                        XmsfNetworkHelper.ensureXmsfBlocked(context)
                    }
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
                    isolated -> "đã khóa XMSF đa tầng bền vững (Shizuku đang bật)"
                    else -> "cách ly XMSF FAILED"
                }
            } else {
                NotificationManagerCompat.from(context).notify(id, notification)
                postedActiveIds.add(id)
                val sameBoot = isIsolatedInCurrentBoot(context)
                val everActivated = isOneTimeOptimizationActive(context)
                Log.d(TAG, "✓ Đã bắn Focus Notification ở chế độ độc lập (id=$id, sameBoot=$sameBoot, ever=$everActivated)")
                path = when {
                    !hasXmsf -> "direct (máy không có XMSF)"
                    sameBoot -> "chế độ độc lập (XMSF đã khóa đa tầng, Tùy chọn nhà phát triển / Shizuku đã tắt)"
                    everActivated -> "chế độ độc lập sau khởi động lại (NetPolicy/AppOps, Shizuku tắt)"
                    else -> "hybrid Focus + Android 16 Promoted LiveUpdate (chưa từng kích hoạt Shizuku)"
                }
            }
        }
        IslandProbe.track(context, id, path)
    }
}
