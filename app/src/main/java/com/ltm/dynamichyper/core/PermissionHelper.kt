package com.ltm.dynamichyper.core

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.ltm.dynamichyper.service.DynamicNotificationService

object PermissionHelper {

    fun isNotificationAccessGranted(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val cn = ComponentName(context, DynamicNotificationService::class.java)
        return flat != null && flat.contains(cn.flattenToString())
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }

    fun isPostNotificationGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isOverlayGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    const val OP_MIUI_SHOW_WHEN_LOCKED = 10008
    const val OP_MIUI_BG_START = 10020
    const val OP_MIUI_POPUP_WINDOW = 10021
    const val OP_MIUI_FLOAT_NOTIFICATION = 10033

    /**
     * Kiểm tra trạng thái cấp quyền chuyên sâu cho các mã Op MIUI/HyperOS qua Reflection
     * Ví dụ: 10008 (Show on Lockscreen), 10020 (Background Popups), 10021 (Popup Window), 10033 (Floating Notification)
     */
    fun isMiuiOpAllowed(context: Context, opCode: Int): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(appOps, opCode, Process.myUid(), context.packageName) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Nhận diện quyền Tự khởi chạy và Quyền hiển thị màn hình khóa của Xiaomi HyperOS
     */
    fun isAutoStartOrLockscreenGranted(context: Context, isManuallyMarked: Boolean = false): Boolean {
        if (isManuallyMarked) return true
        // Trên Xiaomi HyperOS, khi người dùng cấp quyền Tự khởi chạy hoặc Quyền khác,
        // các mã op 10008, 10020, 10021 sẽ được set sang MODE_ALLOWED (0)
        return isMiuiOpAllowed(context, OP_MIUI_SHOW_WHEN_LOCKED) ||
               isMiuiOpAllowed(context, OP_MIUI_BG_START) ||
               isMiuiOpAllowed(context, OP_MIUI_POPUP_WINDOW) ||
               isNotificationAccessGranted(context)
    }

    /**
     * Kiểm tra quyền Thông báo nổi (Floating Notifications / Heads-up) của Xiaomi HyperOS
     * Trên HyperOS (Android 17), công tắc Thông báo nổi chuẩn là mã Op 10033 (kết hợp 10020 làm fallback)
     */
    fun isFloatingNotificationAllowed(context: Context): Boolean {
        return isMiuiOpAllowed(context, OP_MIUI_FLOAT_NOTIFICATION) || isMiuiOpAllowed(context, OP_MIUI_BG_START)
    }

    // ================= INTENTS =================

    fun openNotificationAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun openMiuiOtherPermissions(context: Context) {
        val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            putExtra("extra_pkgname", context.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(miuiIntent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    fun openMiuiAutoStartSettings(context: Context) {
        val autoStartIntent = Intent().apply {
            component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            context.startActivity(autoStartIntent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    /**
     * Tắt hoặc Bật thông báo nổi (Floating notification / Heads-up) của app qua Shizuku
     * Cấp đồng bộ cả Op 10033 (công tắc chính trong cài đặt thông báo HyperOS) và Op 10020
     */
    fun setMiuiFloatingNotificationEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!ShizukuEngine.hasShizukuPermission()) return false
        val mode = if (enabled) "allow" else "ignore"
        val cmd1 = "cmd appops set $packageName 10033 $mode"
        val cmd2 = "cmd appops set $packageName 10020 $mode"
        val (exit1, _) = ShizukuEngine.execCommand(cmd1)
        val (exit2, _) = ShizukuEngine.execCommand(cmd2)
        return exit1 == 0 && exit2 == 0
    }

    /**
     * Mở trực tiếp trang cài đặt thông báo của ứng dụng để bật/tắt thủ công
     */
    fun openAppNotificationSettings(context: Context, packageName: String = context.packageName) {
        val intents = listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }
}
