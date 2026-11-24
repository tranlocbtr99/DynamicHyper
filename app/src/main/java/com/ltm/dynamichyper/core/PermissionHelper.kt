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

    /**
     * Kiểm tra trạng thái "Cài đặt bị hạn chế" (Restricted Settings) của Android 13/14+.
     * Với file APK cài ngoài, Android sẽ chặn quyền đọc thông báo trừ khi người dùng
     * mở khóa qua menu 3 chấm trong Thông tin ứng dụng.
     */
    fun isRestrictedSettingsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val mode = method.invoke(
                appOps,
                "android:access_restricted_settings",
                Process.myUid(),
                context.packageName
            ) as Int
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            true
        }
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
     * Đọc thẳng MODE của app-op MIUI/HyperOS qua Reflection.
     * Trả về -1 khi ROM không hỗ trợ op đó (không được coi là "bị từ chối").
     */
    fun getMiuiOpMode(context: Context, opCode: Int): Int {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            method.invoke(appOps, opCode, Process.myUid(), context.packageName) as Int
        } catch (_: Throwable) {
            -1
        }
    }

    /** true nếu ROM thật sự định nghĩa op này (đọc được mode). */
    fun isMiuiOpKnown(context: Context, opCode: Int): Boolean = getMiuiOpMode(context, opCode) >= 0

    /**
     * Nhận diện quyền Tự khởi chạy và Quyền hiển thị màn hình khóa của Xiaomi HyperOS.
     * KHÔNG còn cộng quyền đọc thông báo vào đây: đó là lỗi false-positive khiến UI
     * báo "đã cấp" trong khi công tắc thật vẫn tắt.
     */
    fun isAutoStartOrLockscreenGranted(context: Context, isManuallyMarked: Boolean = false): Boolean {
        if (isManuallyMarked) return true
        return isMiuiOpAllowed(context, OP_MIUI_SHOW_WHEN_LOCKED) ||
               isMiuiOpAllowed(context, OP_MIUI_BG_START) ||
               isMiuiOpAllowed(context, OP_MIUI_POPUP_WINDOW)
    }

    /**
     * Kiểm tra quyền Thông báo nổi (Floating Notifications / Heads-up) của Xiaomi HyperOS.
     * HyperOS 4 dùng op 10033; các bản cũ dùng 10020. Nếu ROM không đọc được op nào
     * thì trả true để không chặn oan người dùng (trạng thái = không xác định).
     */
    fun isFloatingNotificationAllowed(context: Context): Boolean {
        val op = when {
            isMiuiOpKnown(context, OP_MIUI_FLOAT_NOTIFICATION) -> OP_MIUI_FLOAT_NOTIFICATION
            isMiuiOpKnown(context, OP_MIUI_BG_START) -> OP_MIUI_BG_START
            else -> return true
        }
        return isMiuiOpAllowed(context, op)
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
     * Tắt hoặc Bật thông báo nổi (Floating notification / Heads-up) của app qua Shizuku.
     * HyperOS 4 dùng op 10033, bản cũ dùng 10020 → set cả hai, thành công nếu ÍT NHẤT một op tồn tại.
     */
    fun setMiuiFloatingNotificationEnabled(packageName: String, enabled: Boolean): Boolean {
        if (!ShizukuEngine.hasShizukuPermission()) return false
        val mode = if (enabled) "allow" else "ignore"
        val results = listOf(OP_MIUI_FLOAT_NOTIFICATION, OP_MIUI_BG_START).map { op ->
            val (exit, _) = ShizukuEngine.execCommand("cmd appops set $packageName $op $mode")
            exit == 0
        }
        return results.any { it }
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

    /**
     * Mở trực tiếp trang Tiết kiệm pin của MIUI/HyperOS (HiddenAppsConfigActivity)
     * để người dùng chọn "Không hạn chế" (No restrictions).
     */
    fun openMiuiBatterySaver(context: Context) {
        val intents = listOf(
            Intent().apply {
                component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                putExtra("package_name", context.packageName)
                putExtra("package_label", context.getString(com.ltm.dynamichyper.R.string.app_name))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
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
