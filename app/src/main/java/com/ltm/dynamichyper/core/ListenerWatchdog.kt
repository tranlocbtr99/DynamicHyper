package com.ltm.dynamichyper.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import com.ltm.dynamichyper.service.DynamicNotificationService
import kotlinx.coroutines.*

/**
 * Giám sát trạng thái hoạt động của NotificationListenerService (DynamicNotificationService).
 * Tự động hồi sinh và tái kết nối listener ngay cả khi người dùng ĐÃ TẮT Shizuku & Wireless Debugging
 * thông qua requestRebind() và PackageManager toggle; nếu Shizuku đang mở sẽ gọi thêm allow_listener.
 */
object ListenerWatchdog {
    private const val TAG = "DynamicHyper:Watchdog"
    private var watchdogJob: Job? = null
    private const val CHECK_INTERVAL_MS = 15_000L

    @Volatile
    var isListenerConnected: Boolean = false

    fun start(context: Context, scope: CoroutineScope) {
        if (watchdogJob?.isActive == true) return

        watchdogJob = scope.launch(Dispatchers.IO) {
            AppLogger.i(TAG, "Watchdog giám sát dịch vụ thông báo đã khởi chạy (hỗ trợ cả chế độ độc lập không Shizuku)")
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkAndHeal(context)
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun checkAndHeal(context: Context) {
        val isAccessGranted = PermissionHelper.isNotificationAccessGranted(context)
        if (!isAccessGranted) return

        // Nếu quyền đã bật trong hệ thống nhưng dịch vụ bị mất kết nối (dead binding)
        if (!isListenerConnected) {
            val shizukuAlive = ShizukuEngine.hasShizukuPermission()
            AppLogger.w(TAG, "Phát hiện dịch vụ DynamicNotificationService bị ngắt ngầm! Đang tự phục hồi (shizuku=$shizukuAlive)...")
            val component = ComponentName(context, DynamicNotificationService::class.java)

            // 1. Gọi requestRebind chuẩn của Android (hoạt động độc lập KHÔNG cần Shizuku)
            try {
                NotificationListenerService.requestRebind(component)
            } catch (e: Throwable) {
                AppLogger.w(TAG, "requestRebind gặp lỗi: ${e.message}")
            }

            // 2. Nếu Shizuku tình cờ đang bật thì gọi thêm lệnh allow_listener
            if (shizukuAlive) {
                val cls = "${context.packageName}/${DynamicNotificationService::class.java.name}"
                ShizukuEngine.execCommand("cmd notification allow_listener $cls")
            }

            // 3. Toggle Component để ép Android framework bind lại service (KHÔNG cần Shizuku)
            try {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                AppLogger.i(TAG, "✓ Đã kích hoạt tái liên kết dịch vụ thành công (chế độ độc lập)!")
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Không thể re-enable component: ${e.message}")
            }
        }
    }
}
