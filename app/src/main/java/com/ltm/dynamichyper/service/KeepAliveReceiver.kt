package com.ltm.dynamichyper.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.feature.islands.IdleIslandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * KeepAliveReceiver - Tự động kích hoạt lại dịch vụ khi mở khóa hoặc cắm sạc
 */
class KeepAliveReceiver : BroadcastReceiver() {
    private val TAG = "DynamicHyper:KeepAlive"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "KeepAliveReceiver triggered: $action")

        val prefs = PreferencesManager(context)
        CoroutineScope(Dispatchers.Main).launch {
            val isIdle = prefs.isIdleIslandEnabled.first()
            val width = prefs.idleIslandWidth.first()

            if (isIdle) {
                IdleIslandManager.showIdleIsland(context, width)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationListenerService.requestRebind(
                    ComponentName(context, DynamicNotificationService::class.java)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "KeepAlive requestRebind failed: ${e.message}")
        }
    }
}
