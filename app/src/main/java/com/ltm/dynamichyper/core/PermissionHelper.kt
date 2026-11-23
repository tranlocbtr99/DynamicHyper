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

    /**
     * Kiểm tra trạng thái cấp quyền chuyên sâu cho các mã Op MIUI/HyperOS qua Reflection
     * Ví dụ: 10008 (Show on Lockscreen), 10020 (Background Popups), 10021 (Popup Window)
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
        return isMiuiOpAllowed(context, 10008) ||
               isMiuiOpAllowed(context, 10020) ||
               isMiuiOpAllowed(context, 10021) ||
               isNotificationAccessGranted(context)
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
     */
    fun setMiuiFloatingNotificationEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!ShizukuEngine.hasShizukuPermission()) return false
        val mode = if (enabled) "allow" else "ignore"
        val cmd = "cmd appops set $packageName 10020 $mode"
        val (exitCode, _) = ShizukuEngine.execCommand(cmd)
        return exitCode == 0
    }

    /**
     * Mở trực tiếp trang cài đặt thông báo của ứng dụng để tắt thủ công
     */
    fun openAppNotificationSettings(context: Context, packageName: String) {
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
