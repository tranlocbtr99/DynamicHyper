package com.ltm.dynamichyper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ltm.dynamichyper.MainActivity
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.FocusProtocolHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/**
 * KeepAliveService – Dịch vụ foreground (specialUse) giữ NotificationListenerService và
 * Đảo Chờ hoạt động liên tục trên HyperOS 4.
 * Yêu cầu : targetSdk 37, permission FOREGROUND_SERVICE_SPECIAL_USE và property special-use.
 */
class KeepAliveService : Service() {
    private val TAG = "DynamicHyper:KeepAlive"
    private val NOTIFICATION_ID = 1001
    private var rebindJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        createKeepAliveChannel()
        AppLogger.d(TAG, "KeepAliveService khởi tạo")
    }

    private fun createKeepAliveChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            try { manager.deleteNotificationChannel("dynamic_hyper_keep_alive_channel") } catch (_: Throwable) {}
            val channel = NotificationChannel(
                KEEP_ALIVE_CHANNEL_ID,
                "Dịch vụ duy trì nền",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                description = "Duy trì kết nối lắng nghe thông báo nền"
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (ACTION_STOP == action) {
            stopForeground(true)
            stopSelf()
            AppLogger.i(TAG, "KeepAliveService dừng theo yêu cầu")
            return START_NOT_STICKY
        }

        startForegroundCompat()
        rebindListener()
        schedulePeriodicRebind()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        rebindJob?.cancel()
        Log.d(TAG, "onDestroy")
        AppLogger.i(TAG, "KeepAliveService bị hủy")
    }

    private var foregroundStarted = false

    private fun startForegroundCompat() {
        if (foregroundStarted) return
        foregroundStarted = true
        createKeepAliveChannel()
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            AppLogger.e(TAG, "Lỗi khi gọi startForeground trong KeepAliveService: ${e.message}", e)
        }
    }

    private fun buildNotification(): Notification {
        createKeepAliveChannel()
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, KEEP_ALIVE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Dịch vụ nền đang hoạt động")
            .setContentText("Giữ kết nối lắng nghe thông báo 24/7")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    private fun rebindListener() {
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, DynamicNotificationService::class.java)
            )
        } catch (e: Exception) {
            Log.e(TAG, "requestRebind failed: ${e.message}")
        }
    }

    private fun schedulePeriodicRebind() {
        rebindJob?.cancel()
        rebindJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(60 * 1000L)
                if (!com.ltm.dynamichyper.core.ListenerWatchdog.isListenerConnected) {
                    rebindListener()
                }
                if (!com.ltm.dynamichyper.feature.islands.IdleIslandManager.shouldSuppressIdle()) {
                    val mgr = com.ltm.dynamichyper.feature.islands.IdleIslandManager.getInstance()
                    if (mgr != null && !mgr.isShowing) {
                        mgr.reassert(force = true, delayMs = 200L)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "com.ltm.dynamichyper.action.STOP_KEEP_ALIVE"
        const val KEEP_ALIVE_CHANNEL_ID = "dynamic_hyper_keep_alive_v2"
    }
}
