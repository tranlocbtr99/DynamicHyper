package com.ltm.dynamichyper.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.core.PaletteHelper
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.feature.islands.IdleIslandManager
import com.ltm.dynamichyper.feature.islands.ScreenRecordingIslandManager
import com.ltm.dynamichyper.feature.islands.VpnIslandManager
import com.ltm.dynamichyper.feature.islands.CallIslandManager
import com.ltm.dynamichyper.feature.islands.ChargingIslandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * DynamicNotificationService - Dịch vụ lõi của Dynamic Hyper
 * Lắng nghe toàn bộ thông báo (Nhạc, Tin nhắn, Quay màn hình, VPN) và chuyển thành Dynamic Island
 */
class DynamicNotificationService : NotificationListenerService() {

    private val TAG = "DynamicHyper_Service"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PreferencesManager
    private lateinit var idleIslandManager: IdleIslandManager
    private lateinit var vpnIslandManager: VpnIslandManager
    private lateinit var chargingIslandManager: ChargingIslandManager
    private lateinit var callIslandManager: CallIslandManager

    private val activeDynamicIslands = ConcurrentHashMap<String, Int>()
    private val activeDismissJobs = ConcurrentHashMap<String, Job>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
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
        vpnIslandManager = VpnIslandManager(this)
        chargingIslandManager = ChargingIslandManager(this, serviceScope, prefs)
        chargingIslandManager.startListening()
        callIslandManager = CallIslandManager(this, serviceScope, prefs)

        serviceScope.launch {
            val isVpnEnabled = prefs.isVpnIslandEnabled.first()
            if (isVpnEnabled) {
                vpnIslandManager.startListening()
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        com.ltm.dynamichyper.core.AppLogger.d(TAG, "Bắt thông báo từ gói: ${sbn.packageName} (id=${sbn.id})")
        // Không xử lý lại các thông báo đã là Xiaomi Focus Island để tránh đệ quy
        if (sbn.notification.extras?.containsKey("miui.focus.param") == true) return

        // 1. Nhận diện Cuộc Gọi Đến (Zalo, Messenger, Telegram, Phone...)
        if (CallIslandManager.isCallNotification(sbn)) {
            Log.i(TAG, "Incoming call detected from ${sbn.packageName}")
            callIslandManager.handleCallNotification(sbn)
            idleIslandManager.onEventCountChanged(activeDynamicIslands.size + 1)
            return
        }

        // 2. Nhận diện Quay Màn Hình của Xiaomi
        if (ScreenRecordingIslandManager.isScreenRecordingNotification(sbn)) {
            serviceScope.launch {
                val isRecordEnabled = prefs.isScreenRecordingIslandEnabled.first()
                if (isRecordEnabled) {
                    ScreenRecordingIslandManager.showRecordingIsland(this@DynamicNotificationService)
                }
            }
            return
        }

        // 3. Nhận diện Nhạc và Tin Nhắn
        serviceScope.launch {
            handleIncomingNotification(sbn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        handleNotificationRemoved(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        handleNotificationRemoved(sbn)
    }

    private fun handleNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return

        // TỰ ĐỘNG KHÔI PHỤC ĐẢO CHỜ NGAY LẬP TỨC:
        // Nếu người dùng bấm "Xóa tất cả thông báo" (Clear All) hoặc hệ thống gỡ bỏ id 9999,
        // Dịch vụ sẽ lập tức phát hiện và hồi sinh Đảo Chờ 24/7 ngay mà không cần người dùng thao tác.
        if (sbn.packageName == packageName) {
            when (sbn.id) {
                IdleIslandManager.IDLE_ISLAND_ID -> {
                    Log.i(TAG, "Idle Island (9999) was removed. Auto-restoring immediately...")
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.reassert()
                    }
                }
                CallIslandManager.CALL_ISLAND_ID -> {
                    Log.i(TAG, "Call Island ended. Returning to Idle Island...")
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
                    }
                }
                ChargingIslandManager.CHARGING_NOTIFICATION_ID,
                ScreenRecordingIslandManager.RECORDING_NOTIFICATION_ID,
                VpnIslandManager.VPN_NOTIFICATION_ID -> {
                    Log.i(TAG, "Feature island ${sbn.id} ended. Returning to Idle Island...")
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.reassert()
                    }
                }
            }
            return
        }

        if (CallIslandManager.isCallNotification(sbn) || callIslandManager.isCallActive) {
            callIslandManager.hideCallIsland()
        }

        if (sbn.packageName == "com.miui.screenrecorder") {
            ScreenRecordingIslandManager.hideRecordingIsland(this)
        }

        val dynamicIslandId = activeDynamicIslands.remove(sbn.key)
        if (dynamicIslandId != null) {
            activeDismissJobs.remove(sbn.key)?.cancel()
            try {
                NotificationManagerCompat.from(this).cancel(dynamicIslandId)
            } catch (_: Exception) {}
            // Khôi phục Đảo Chờ khi thông báo Dynamic Hyper thực sự kết thúc
            idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
        }

        // Tự động kiểm tra đồng bộ: nếu người dùng bấm "Xóa tất cả" trên thanh thông báo
        serviceScope.launch {
            delay(180L)
            val currentNotifs = try { activeNotifications } catch (_: Exception) { null }
            val otherAppNotifsCount = currentNotifs?.count { it.packageName != packageName } ?: 0
            if (otherAppNotifsCount == 0 || activeDynamicIslands.isEmpty()) {
                activeDynamicIslands.values.forEach { id ->
                    try {
                        NotificationManagerCompat.from(this@DynamicNotificationService).cancel(id)
                    } catch (_: Exception) {}
                }
                activeDynamicIslands.clear()
                activeDismissJobs.values.forEach { it.cancel() }
                activeDismissJobs.clear()
                idleIslandManager.onEventCountChanged(0)
            }
        }
    }

    private suspend fun handleIncomingNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return

        val isMessaging = isMessagingApp(sbn)
        val isMedia = isMediaApp(sbn)

        if (!isMessaging && !isMedia) return

        if (isMedia && !prefs.isMediaIslandEnabled.first()) return
        if (isMessaging && !prefs.isMessageIslandEnabled.first()) return

        // 1. Kiểm tra bộ lọc thông báo từng ứng dụng do người dùng cấu hình (Blacklist Model)
        val disabledApps = prefs.disabledPackages.first()
        if (!prefs.isPackageAllowed(sbn.packageName, disabledApps)) {
            Log.d(TAG, "Notification from ${sbn.packageName} ignored by user per-app setting")
            return
        }

        val dynamicIslandId = sbn.key.hashCode()
        activeDynamicIslands[sbn.key] = dynamicIslandId
        activeDismissJobs.remove(sbn.key)?.cancel()
        idleIslandManager.onEventCountChanged(activeDynamicIslands.size)

        val rawAvatarBmp: Bitmap? = try {
            val largeIconObj = extras.get(Notification.EXTRA_LARGE_ICON)
            when (largeIconObj) {
                is Bitmap -> largeIconObj
                is android.graphics.drawable.Icon -> {
                    val drawable = largeIconObj.loadDrawable(this)
                    if (drawable is android.graphics.drawable.BitmapDrawable) {
                        drawable.bitmap
                    } else if (drawable != null) {
                        val bitmap = Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(96),
                            drawable.intrinsicHeight.coerceAtLeast(96),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bitmap
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        val avatarBmp: Bitmap = if (rawAvatarBmp != null) {
            createCircularBitmap(rawAvatarBmp)
        } else {
            try {
                val appIconDrawable = packageManager.getApplicationIcon(sbn.packageName)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                appIconDrawable.setBounds(0, 0, 96, 96)
                appIconDrawable.draw(canvas)
                createCircularBitmap(bmp)
            } catch (_: Exception) {
                Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            }
        }

        val picKey = "notif_avatar"
        val pics = mutableMapOf<String, Bitmap>()
        pics[picKey] = avatarBmp
        // Thêm hidden_pixel để Xiaomi Focus Engine render văn bản bên phải chuẩn xác
        pics["hidden_pixel"] = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        val isPaletteEnabled = prefs.isPaletteColorEnabled.first()
        val isGradientGlow = prefs.isNotificationGradientGlow.first()
        val highlightColor = when {
            isMedia && isPaletteEnabled -> PaletteHelper.extractDominantColorHex(avatarBmp)
            isGradientGlow -> {
                when (sbn.packageName) {
                    "com.zing.zalo" -> "#0088FF"
                    "com.facebook.orca" -> "#00C6FF"
                    "org.telegram.messenger" -> "#2AABEE"
                    "com.whatsapp" -> "#25D366"
                    "com.google.android.apps.messaging", "com.android.mms" -> "#FF2D55"
                    else -> if (isMedia) "#BF5AF2" else "#5E5CE6" // Neon Indigo Hologram
                }
            }
            else -> {
                if (isMedia) "#BF5AF2" else "#0A84FF"
            }
        }

        // Tạo cấu trúc JSON FocusParams chuẩn xác 100% bằng JSONObject (tránh lỗi cú pháp ký tự đặc biệt)
        val root = JSONObject()
        val paramV2 = JSONObject()
        paramV2.put("protocol", 3)
        paramV2.put("business", "dynamic_hyper_${sbn.packageName}")
        paramV2.put("updatable", true)
        paramV2.put("ticker", if (text.isNotBlank()) "$title: $text" else title)
        paramV2.put("timeout", 0)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", true)
        paramV2.put("islandFirstFloat", true)
        paramV2.put("reopen", true)

        val paramIsland = JSONObject()
        paramIsland.put("islandProperty", 1)
        paramIsland.put("islandPriority", 2)
        paramIsland.put("islandTimeout", 86400)
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", false)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        // Khi tin nhắn mới đến: expandedTime = 4s (tự động bung to 4 giây cho người dùng xem rồi tự thu lại về camera)
        paramIsland.put("expandedTime", if (isMedia) 0 else 4)
        paramIsland.put("highlightColor", highlightColor)

        // bigIslandArea: Left chứa Avatar, Right chứa Tiêu đề & Nội dung tin nhắn
        val bigIslandArea = JSONObject()
        val leftInfo = JSONObject()
        leftInfo.put("type", 1)
        val leftPic = JSONObject()
        leftPic.put("type", 1)
        leftPic.put("pic", "miui.focus.pic_$picKey")
        leftInfo.put("picInfo", leftPic)
        val leftText = JSONObject()
        leftText.put("title", "")
        leftText.put("content", "")
        leftText.put("showHighlightColor", false)
        leftInfo.put("textInfo", leftText)
        bigIslandArea.put("imageTextInfoLeft", leftInfo)

        val rightInfo = JSONObject()
        rightInfo.put("type", 1)
        val rightPic = JSONObject()
        rightPic.put("type", 1)
        rightPic.put("pic", "miui.focus.pic_hidden_pixel")
        rightInfo.put("picInfo", rightPic)
        val rightText = JSONObject()
        rightText.put("title", title)
        rightText.put("content", text)
        rightText.put("showHighlightColor", false)
        rightInfo.put("textInfo", rightText)
        bigIslandArea.put("imageTextInfoRight", rightInfo)

        paramIsland.put("bigIslandArea", bigIslandArea)

        // smallIslandArea: Hiển thị icon nhỏ của app/avatar
        val smallIslandArea = JSONObject()
        val smallPic = JSONObject()
        smallPic.put("type", 1)
        smallPic.put("pic", "miui.focus.pic_$picKey")
        smallIslandArea.put("picInfo", smallPic)
        paramIsland.put("smallIslandArea", smallIslandArea)

        paramV2.put("param_island", paramIsland)

        // iconTextInfo
        val iconTextInfo = JSONObject()
        iconTextInfo.put("title", title)
        iconTextInfo.put("content", text)
        paramV2.put("iconTextInfo", iconTextInfo)

        // 2. Tương tác trên đảo: Trích xuất Actions & Tạo nút Trả Lời Nhanh (Inline Quick Reply)
        val actionsMap = mutableMapOf<String, Any>()
        val textButtonsArray = org.json.JSONArray()

        val rawActions = notif.actions
        if (rawActions != null && rawActions.isNotEmpty()) {
            for ((index, act) in rawActions.withIndex()) {
                val actionTitle = act.title?.toString() ?: ""
                if (actionTitle.isBlank()) continue

                val remoteInputs = act.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    // Action có RemoteInput -> Kích hoạt khung kính lỏng InlineReplyService
                    val resultKey = remoteInputs[0].resultKey
                    val replyIntent = Intent(this, com.ltm.dynamichyper.feature.reply.InlineReplyReceiver::class.java).apply {
                        putExtra("pending_intent", act.actionIntent)
                        putExtra("result_key", resultKey)
                        putExtra("package_name", sbn.packageName)
                        putExtra("sender_title", title)
                    }
                    val replyPendingIntent = android.app.PendingIntent.getBroadcast(
                        this,
                        (sbn.key + "_reply").hashCode(),
                        replyIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
                    )

                    val actionKey = "miui.focus.action_reply"
                    actionsMap[actionKey] = replyPendingIntent

                    val btnObj = JSONObject()
                    btnObj.put("type", 1)
                    btnObj.put("actionTitle", if (actionTitle.isNotEmpty()) actionTitle else "Trả lời")
                    btnObj.put("action", actionKey)
                    btnObj.put("actionIntent", actionKey)
                    btnObj.put("actionIntentType", 1)
                    btnObj.put("actionTitleColor", "#FFFFFF")
                    textButtonsArray.put(btnObj)
                } else if (act.actionIntent != null) {
                    // Action thường (Đánh dấu đã đọc, Thích, v.v.)
                    val actionKey = "miui.focus.action_$index"
                    actionsMap[actionKey] = act.actionIntent

                    val btnObj = JSONObject()
                    btnObj.put("type", 1)
                    btnObj.put("actionTitle", actionTitle)
                    btnObj.put("action", actionKey)
                    btnObj.put("actionIntent", actionKey)
                    btnObj.put("actionIntentType", 1)
                    btnObj.put("actionTitleColor", "#FFFFFF")
                    textButtonsArray.put(btnObj)
                }
            }
        }

        if (textButtonsArray.length() > 0) {
            paramV2.put("textButton", textButtonsArray)
        }

        root.put("param_v2", paramV2)
        root.put("isShowNotification", true)

        val jsonParam = root.toString()

        val notification = FocusProtocolHelper.buildFocusNotification(
            context = this,
            title = title,
            content = text,
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = jsonParam,
            pics = pics,
            isOngoing = isMedia,
            // Cho tin nhắn mới: onlyAlertOnce = false để kích hoạt Heads-up Float và bung to Dynamic Island
            onlyAlertOnce = isMedia,
            contentIntent = notif.contentIntent,
            actionsMap = actionsMap
        )

        NotificationManagerCompat.from(this).notify(dynamicIslandId, notification)

        val isPersistent = prefs.isPersistentNotificationIsland.first()
        if (!isMedia && !isPersistent) {
            // Nếu người dùng tắt chế độ giữ đảo thu nhỏ, tự động thu hồi sau 5.5s
            val dismissJob = serviceScope.launch {
                delay(5500L)
                activeDynamicIslands.remove(sbn.key)
                activeDismissJobs.remove(sbn.key)
                try {
                    NotificationManagerCompat.from(this@DynamicNotificationService).cancel(dynamicIslandId)
                } catch (_: Exception) {}
                idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
            }
            activeDismissJobs[sbn.key] = dismissJob
        }
        // Nếu isPersistent == true: Đảo bung ra 4s rồi tự động thu nhỏ về camera, giữ nguyên hiển thị tại nốt ruồi camera cho tới khi người dùng xem/xóa thông báo!
    }

    private fun isMessagingApp(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification ?: return false
        val category = notif.category
        if (category == Notification.CATEGORY_MESSAGE ||
            category == Notification.CATEGORY_SOCIAL ||
            category == Notification.CATEGORY_EMAIL) {
            return true
        }
        val pkg = sbn.packageName.lowercase()
        return pkg.contains("zalo") ||
                pkg.contains("orca") ||
                pkg.contains("messenger") ||
                pkg.contains("whatsapp") ||
                pkg.contains("telegram") ||
                pkg.contains("viber") ||
                pkg.contains("discord") ||
                pkg.contains("instagram") ||
                pkg.contains("mms") ||
                pkg.contains("messaging") ||
                pkg.contains("messages") ||
                pkg.contains("wechat") ||
                pkg.contains("line") ||
                pkg.contains("skype")
    }

    private fun isMediaApp(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification ?: return false
        val template = notif.extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("MediaStyle") || notif.category == Notification.CATEGORY_TRANSPORT) {
            return true
        }
        val pkg = sbn.packageName.lowercase()
        return pkg.contains("spotify") ||
                pkg.contains("music") ||
                pkg.contains("zing") ||
                pkg.contains("youtube") ||
                pkg.contains("soundcloud") ||
                pkg.contains("apple") ||
                pkg.contains("audio") ||
                pkg.contains("podcast")
    }

    private fun createCircularBitmap(src: Bitmap): Bitmap {
        val size = Math.min(src.width, src.height).coerceAtLeast(96)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)
        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, null, rect, paint)
        return output
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnIslandManager.stopListening()
        chargingIslandManager.stopListening()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
    }
}
