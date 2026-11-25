package com.ltm.dynamichyper.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.ltm.dynamichyper.service.DynamicNotificationService
import kotlinx.coroutines.*

/**
 * Giám sát trạng thái hoạt động của NotificationListenerService (DynamicNotificationService).
 * Tự động hồi sinh và tái kết nối listener thông qua Shizuku khi MIUI/HyperOS vô tình kill ngầm.
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
            AppLogger.i(TAG, "Watchdog giám sát dịch vụ thông báo đã khởi chạy")
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
        if (!isListenerConnected && ShizukuEngine.hasShizukuPermission()) {
            AppLogger.w(TAG, "Phát hiện dịch vụ DynamicNotificationService bị ngắt ngầm! Đang tự phục hồi...")
            val pkg = context.packageName
            val cls = "$pkg/${DynamicNotificationService::class.java.name}"
            
            // Re-toggle listener via Shizuku
            ShizukuEngine.execCommand("cmd notification allow_listener $cls")
            
            // Toggle Component để ép Android framework bind lại service
            try {
                val component = ComponentName(context, DynamicNotificationService::class.java)
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
                AppLogger.i(TAG, "✓ Đã kích hoạt tái liên kết dịch vụ thành công!")
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Không thể re-enable component: ${e.message}")
            }
        }
    }
}
