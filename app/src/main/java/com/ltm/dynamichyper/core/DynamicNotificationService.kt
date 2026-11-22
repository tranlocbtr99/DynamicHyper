package com.ltm.dynamichyper.core

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class DynamicNotificationService : NotificationListenerService() {

    private val TAG = "DynamicHyper_Service"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PreferencesManager
    private lateinit var idleIslandManager: IdleIslandManager

    private val activeBridgeIslands = ConcurrentHashMap<String, Int>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Device unlocked (USER_PRESENT) -> refreshing islands")
                    idleIslandManager.refresh()
                }
                Intent.ACTION_SCREEN_ON -> {
                    idleIslandManager.refresh()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DynamicNotificationService onCreate")
        prefs = PreferencesManager(this)
        FocusProtocolHelper.setupChannel(this)
        idleIslandManager = IdleIslandManager(this, serviceScope, prefs)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY for LTMori keep-alive
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener Connected successfully")
        idleIslandManager.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return

        // Process message / media / call events
        serviceScope.launch {
            handleIncomingNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return

        val bridgedId = activeBridgeIslands.remove(sbn.key)
        if (bridgedId != null) {
            NotificationManagerCompat.from(this).cancel(bridgedId)
            idleIslandManager.onEventCountChanged(activeBridgeIslands.size)
        }
    }

    private fun handleIncomingNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val isMessaging = isMessagingApp(sbn.packageName)
        val isMedia = isMediaApp(sbn.packageName)

        if (!isMessaging && !isMedia) return

        val bridgeId = sbn.key.hashCode()
        activeBridgeIslands[sbn.key] = bridgeId
        idleIslandManager.onEventCountChanged(activeBridgeIslands.size)

        // Extract icon or largeIcon avatar
        val avatarBmp: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_LARGE_ICON, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_LARGE_ICON)
        }

        val picKey = "notif_avatar"
        val pics = mutableMapOf<String, Bitmap>()
        if (avatarBmp != null) {
            pics[picKey] = avatarBmp
        } else {
            pics[picKey] = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        }

        val jsonParam = """
        {
          "param_v2": {
            "protocol": 3,
            "business": "bridge_${sbn.packageName}",
            "updatable": true,
            "ticker": "$title",
            "enableFloat": true,
            "isShowNotification": true,
            "islandFirstFloat": true,
            "reopen": true,
            "param_island": {
              "islandProperty": 1,
              "islandPriority": 2,
              "islandTimeout": 5000,
              "islandOrder": false,
              "dismissIsland": false,
              "maxSize": false,
              "needCloseAnimation": true,
              "expandedTime": 3,
              "highlightColor": "#0A84FF",
              "bigIslandArea": {
                "imageTextInfoLeft": {
                  "type": 1,
                  "picInfo": {
                    "type": 1,
                    "pic": "miui.focus.pic_$picKey"
                  },
                  "textInfo": {
                    "title": "$title",
                    "content": "$text",
                    "showHighlightColor": false
                  }
                }
              },
              "smallIslandArea": {
                "picInfo": {
                  "type": 1,
                  "pic": "miui.focus.pic_$picKey"
                }
              }
            }
          },
          "isShowNotification": true
        }
        """.trimIndent()

        val notification = FocusProtocolHelper.buildFocusNotification(
            context = this,
            title = title,
            content = text,
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = jsonParam,
            pics = pics,
            isOngoing = false
        )

        NotificationManagerCompat.from(this).notify(bridgeId, notification)
    }

    private fun isMessagingApp(pkg: String): Boolean =
        pkg.contains("zalo") || pkg.contains("orca") || pkg.contains("telegram") ||
        pkg.contains("mms") || pkg.contains("message") || pkg.contains("whatsapp")

    private fun isMediaApp(pkg: String): Boolean =
        pkg.contains("spotify") || pkg.contains("youtube") || pkg.contains("music") ||
        pkg.contains("mp3") || pkg.contains("audio")

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        serviceScope.cancel()
    }
}

