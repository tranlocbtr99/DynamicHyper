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
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            val exitCode = process.waitFor()
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
            "cmd notification allow_listener $packageName/com.ltm.dynamichyper.core.DynamicNotificationService",
            "dumpsys deviceidle whitelist +$packageName",
            "cmd appops set $packageName AUTO_START allow",
            "cmd appops set $packageName 10008 allow",
            "cmd appops set $packageName 10020 allow",
            "cmd appops set $packageName 10021 allow",
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
     * Chặn mạng UID com.xiaomi.xmsf để Focus Island không bao giờ bị thu hồi
     */
    fun applyXmsfBypass(): Boolean {
        val getUidCmd = "pm list packages -U com.xiaomi.xmsf"
        val (_, out) = execCommand(getUidCmd)
        val uidRegex = Regex("uid:(\\d+)")
        val match = uidRegex.find(out)
        val uid = match?.groupValues?.get(1)?.toIntOrNull()

        return if (uid != null && uid > 0) {
            val blockCmd = "cmd netpolicy add restrict-background-whitelist $uid; cmd connectivity set-uid-firewall-rule 9 $uid 2; cmd connectivity set-uid-firewall-rule 1 $uid 2"
            val (code, _) = execCommand(blockCmd)
            Log.i(TAG, "XMSF UID $uid blocked successfully: ${code == 0}")
            code == 0
        } else {
            Log.w(TAG, "Could not find UID for com.xiaomi.xmsf")
            false
        }
    }
}
