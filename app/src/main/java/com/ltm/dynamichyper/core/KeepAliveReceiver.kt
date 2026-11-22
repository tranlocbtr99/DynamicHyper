package com.ltm.dynamichyper.core

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.util.Log

class KeepAliveReceiver : BroadcastReceiver() {

    private val TAG = "DynamicHyper_KeepAlive"

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "KeepAliveReceiver triggered by action: $action")

        try {
            // Re-request listener binding if disconnected
            val componentName = ComponentName(context, DynamicNotificationService::class.java)
            NotificationListenerService.requestRebind(componentName)
            Log.d(TAG, "Rebind requested for DynamicNotificationService")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request rebind: ${e.message}")
        }
    }
}

