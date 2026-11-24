package com.ltm.dynamichyper.core

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Điều phối quyền hạn cấp cao qua Shizuku
 */
object ShizukuEngine {
    private const val TAG = "DynamicHyper:Shizuku"

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
            "cmd appops set $packageName AUTO_START allow",
            "cmd appops set $packageName 10008 allow",
            "cmd appops set $packageName 10020 allow",
            "cmd appops set $packageName 10021 allow",
            "cmd appops set $packageName 10033 allow",
            "cmd appops set $packageName SYSTEM_ALERT_WINDOW allow",
            "pm grant $packageName android.permission.POST_NOTIFICATIONS"
        )

        var allSuccess = true
        for (cmd in commands) {
            val (code, out) = execCommand(cmd)
            Log.d(TAG, "Exec: $cmd -> code $code, out: $out")
            if (code != 0) allSuccess = false
        }

        applyXmsfBypass()
        return allSuccess
    }

    /**
     * Chặn mạng com.xiaomi.xmsf qua chuẩn Connectivity Chain 3 để HyperOS cho phép Focus Island
     */
    fun applyXmsfBypass(): Boolean {
        val (c1, _) = execCommand("cmd connectivity set-chain3-enabled true")
        val (c2, _) = execCommand("cmd connectivity set-package-networking-enabled false com.xiaomi.xmsf")
        val success = c1 == 0 && c2 == 0
        Log.i(TAG, "Applied HyperOS XMSF Bypass via Connectivity chain3: $success")
        return success
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
}
