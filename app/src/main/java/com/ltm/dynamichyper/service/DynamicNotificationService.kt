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
    private val lastNotificationContent = ConcurrentHashMap<String, String>()
    private val intentionallyRemovedKeys = ConcurrentHashMap<String, Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                    reconcileActiveIslandsAndRestoreIdle()
                }
            }
        }
    }

    private val islandClickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == IslandTapActivity.ACTION_ISLAND_CLICKED) {
                val sbnKey = intent.getStringExtra(IslandTapActivity.EXTRA_SBN_KEY)
                val islandId = intent.getIntExtra(IslandTapActivity.EXTRA_ISLAND_ID, -1)
                Log.i(TAG, "Người dùng đã nhấn xem thông báo trên Đảo (key=$sbnKey, id=$islandId) -> Dọn đảo & khôi phục Đảo Chờ")

                if (sbnKey != null) {
                    activeDynamicIslands.remove(sbnKey)
                    activeDismissJobs.remove(sbnKey)?.cancel()
                    lastNotificationContent.remove(sbnKey)
                    try {
                        cancelNotification(sbnKey)
                    } catch (_: Exception) {}
                }
                if (islandId != -1) {
                    com.ltm.dynamichyper.core.ShizukuEngine.clearPostedActiveId(islandId)
                    try {
                        NotificationManagerCompat.from(this@DynamicNotificationService).cancel(islandId)
                    } catch (_: Exception) {}
                }
                // Khôi phục Đảo Chờ 24/7 sau khi người dùng đã nhấn xem thông báo
                idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
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

        val clickFilter = IntentFilter(IslandTapActivity.ACTION_ISLAND_CLICKED)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            islandClickReceiver,
            clickFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener Connected successfully")
        com.ltm.dynamichyper.core.ListenerWatchdog.isListenerConnected = true
        com.ltm.dynamichyper.core.ListenerWatchdog.start(this, serviceScope)
        idleIslandManager.isShowing = false
        IdleIslandManager.resetCache()
        idleIslandManager.reassert(force = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener Disconnected!")
        com.ltm.dynamichyper.core.ListenerWatchdog.isListenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        com.ltm.dynamichyper.core.AppLogger.d(TAG, "Bắt thông báo từ gói: ${sbn.packageName} (id=${sbn.id})")
        // Không xử lý lại các thông báo đã là Xiaomi Focus Island để tránh đệ quy,
        // nhưng ghi nhận vào NativeIslandTracker để nhường chỗ (Yield Policy)
        val focusParam = sbn.notification.extras?.getString("miui.focus.param")
        if (focusParam != null) {
            val yieldMs = com.ltm.dynamichyper.core.NativeIslandTracker.calculateYieldMs(
                hasFocusParam = true,
                focusParam = focusParam,
                isMediaPlayer = sbn.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            )
            if (yieldMs != null) {
                com.ltm.dynamichyper.core.NativeIslandTracker.note(sbn.key, yieldMs)
                idleIslandManager.onEventCountChanged(activeDynamicIslands.size + 1)
            }
            return
        }

        // 1. Nhận diện Chỉ Đường Bản Đồ (Google Maps, Waze, Here WeGo)
        if (com.ltm.dynamichyper.feature.islands.NavigationIslandManager.isNavNotification(sbn)) {
            val title = sbn.notification.extras?.getString(Notification.EXTRA_TITLE) ?: ""
            val content = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            com.ltm.dynamichyper.feature.islands.NavigationIslandManager.showNavIsland(this, title, content)
            idleIslandManager.onEventCountChanged(activeDynamicIslands.size + 1)
            return
        }

        // 2. Nhận diện Cuộc Gọi Đến (Zalo, Messenger, Telegram, Phone...)
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
                    idleIslandManager.isShowing = false
                    IdleIslandManager.resetCache()
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.reassert(force = true)
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
                VpnIslandManager.VPN_NOTIFICATION_ID,
                com.ltm.dynamichyper.feature.islands.NavigationIslandManager.NAV_ISLAND_ID,
                com.ltm.dynamichyper.feature.islands.DebuggingIslandManager.DEBUGGING_ISLAND_ID -> {
                    Log.i(TAG, "Feature island ${sbn.id} ended. Returning to Idle Island...")
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.reassert(force = true)
                    }
                }
                else -> {
                    // Người dùng vuốt xóa hoặc đóng thông báo Đảo Động của tin nhắn/app
                    val matchingKey = activeDynamicIslands.entries.firstOrNull { it.value == sbn.id }?.key
                    if (matchingKey != null) {
                        activeDynamicIslands.remove(matchingKey)
                        activeDismissJobs.remove(matchingKey)?.cancel()
                        lastNotificationContent.remove(matchingKey)
                        Log.i(TAG, "Dynamic Island notification (id=${sbn.id}) removed by user. Remaining islands: ${activeDynamicIslands.size}")
                        idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
                    }
                }
            }
            return
        }

        if (com.ltm.dynamichyper.core.NativeIslandTracker.remove(sbn.key)) {
            Log.d(TAG, "Native island ${sbn.key} đã kết thúc, kiểm tra khôi phục Đảo Chờ")
            idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
        }

        if (com.ltm.dynamichyper.feature.islands.NavigationIslandManager.isNavNotification(sbn)) {
            com.ltm.dynamichyper.feature.islands.NavigationIslandManager.hideNavIsland(this)
        }

        if (CallIslandManager.isCallNotification(sbn) || callIslandManager.isCallActive) {
            callIslandManager.hideCallIsland()
        }

        if (sbn.packageName == "com.miui.screenrecorder") {
            ScreenRecordingIslandManager.hideRecordingIsland(this)
        }

        // BẢO VỆ ĐẢO KHÔNG BỊ TỰ HỦY:
        // Nếu thông báo này bị Dynamic Hyper cố tình cancelNotification() để tránh trùng lặp trong trung tâm thông báo,
        // thì tuyệt đối KHÔNG được hủy đảo trên thanh trạng thái!
        if (intentionallyRemovedKeys.remove(sbn.key) != null) {
            Log.d(TAG, "Notification ${sbn.key} was intentionally canceled from shade; keeping Dynamic Island alive!")
            return
        }

        val dynamicIslandId = activeDynamicIslands.remove(sbn.key)
        lastNotificationContent.remove(sbn.key)
        if (dynamicIslandId != null) {
            activeDismissJobs.remove(sbn.key)?.cancel()
            com.ltm.dynamichyper.core.ShizukuEngine.clearPostedActiveId(dynamicIslandId)
            try {
                NotificationManagerCompat.from(this).cancel(dynamicIslandId)
            } catch (_: Exception) {}
            // Khôi phục Đảo Chờ khi thông báo Dynamic Hyper thực sự kết thúc
            idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
        }

        // Tự động đồng bộ lại danh sách đảo đang hoạt động (dọn sạch các key mồ côi khi người dùng bấm "Xóa tất cả")
        serviceScope.launch {
            delay(200L)
            reconcileActiveIslandsAndRestoreIdle()
        }
    }

    /**
     * Đồng bộ hóa activeDynamicIslands với danh sách thông báo thực tế đang có trên thanh trạng thái.
     * Nếu người dùng đã xóa hết thông báo hoặc thông báo gốc không còn tồn tại, lập tức dọn sạch và khôi phục Đảo Chờ!
     */
    private fun reconcileActiveIslandsAndRestoreIdle() {
        try {
            val currentNotifs = try { activeNotifications } catch (_: Exception) { null } ?: return
            val liveKeys = currentNotifs.filter { it.packageName != packageName }.map { it.key }.toSet()

            val iterator = activeDynamicIslands.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val key = entry.key
                val islandId = entry.value
                // Nếu thông báo gốc đã biến mất (và không phải do ta chủ động ẩn thông báo gốc)
                if (!liveKeys.contains(key) && !intentionallyRemovedKeys.containsKey(key)) {
                    iterator.remove()
                    activeDismissJobs.remove(key)?.cancel()
                    lastNotificationContent.remove(key)
                    com.ltm.dynamichyper.core.ShizukuEngine.clearPostedActiveId(islandId)
                    try {
                        NotificationManagerCompat.from(this).cancel(islandId)
                    } catch (_: Exception) {}
                }
            }

            if (activeDynamicIslands.isEmpty()) {
                idleIslandManager.onEventCountChanged(0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconcileActiveIslands error: ${e.message}")
        }
    }

    private suspend fun handleIncomingNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return

        // 1. Lọc thông báo Group Summary (tránh bão thông báo kép từ Messenger/Facebook/Zalo)
        val isGroupSummary = (notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) {
            val group = notif.group
            val hasLiveChild = if (!group.isNullOrBlank()) {
                try {
                    activeNotifications?.any {
                        it.packageName == sbn.packageName &&
                                it.key != sbn.key &&
                                (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                                it.notification.group == group
                    } == true
                } catch (_: Exception) { false }
            } else false
            if (hasLiveChild) {
                Log.d(TAG, "Bỏ qua thông báo Group Summary của ${sbn.packageName} vì đã có thông báo con chi tiết")
                return
            }
        }

        val extras = notif.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        if (title.isBlank() && text.isBlank()) return
        if (title.equals(sbn.packageName, ignoreCase = true) || text.equals(sbn.packageName, ignoreCase = true)) return

        val isMessaging = isMessagingApp(sbn)
        val isMedia = isMediaApp(sbn)

        // Lọc bỏ các gói dịch vụ hệ thống chạy ngầm và thông báo ongoing không phải nhạc
        // (ví dụ: android id=0, com.lbe.security.miui, com.huawei.health đếm bước chân, hoặc dịch vụ chat head id=1 của Messenger)
        // để chúng không bao giờ chiếm giữ activeDynamicIslands làm mất Đảo Chờ!
        val sysIgnoredPackages = setOf(
            "android",
            "com.android.systemui",
            "com.lbe.security.miui",
            "com.miui.securitycenter",
            "com.xiaomi.xmsf",
            "com.miui.screenshot"
        )
        if (sbn.packageName in sysIgnoredPackages) return
        if (sbn.isOngoing && !isMedia) {
            Log.d(TAG, "Bỏ qua thông báo chạy ngầm (ongoing non-media) từ ${sbn.packageName} (id=${sbn.id})")
            return
        }

        // Kiểm tra bộ lọc thông báo từng ứng dụng do người dùng cấu hình (Blacklist Model)
        val disabledApps = prefs.disabledPackages.first()
        if (!prefs.isPackageAllowed(sbn.packageName, disabledApps)) {
            Log.d(TAG, "Notification from ${sbn.packageName} ignored by user per-app setting")
            return
        }

        // Tôn trọng thiết lập bật/tắt Đảo Nhạc & Đảo Thông Báo của người dùng
        if (isMedia && !prefs.isMediaIslandEnabled.first()) return
        if (!isMedia && !prefs.isMessageIslandEnabled.first()) return

        // Chống spam bung đảo liên tục: Kiểm tra nếu tiêu đề & nội dung không đổi
        val contentKey = "$title|$text"
        val isSameContent = (lastNotificationContent[sbn.key] == contentKey)
        if (isSameContent && isMedia) {
            // Media progress cập nhật từng giây -> bỏ qua không bung lại
            return
        }
        val isNewMessage = isMessaging && !isSameContent
        lastNotificationContent[sbn.key] = contentKey

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
        val protocolVersion = FocusProtocolHelper.getFocusProtocolVersion(this)
        paramV2.put("protocol", protocolVersion)
        paramV2.put("business", "bridge_${sbn.packageName}")
        paramV2.put("updatable", true)
        paramV2.put("ticker", if (text.isNotBlank()) "$title: $text" else title)
        paramV2.put("timeout", 86400000)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", true)
        // islandFirstFloat: chỉ bung to khi là tin nhắn mới, media/update tuyệt đối không bung
        paramV2.put("islandFirstFloat", isNewMessage)
        paramV2.put("reopen", isNewMessage) // Chuẩn HyperBridge: thông báo mới cần reopen để SystemUI kích hoạt vẽ đảo viên thuốc

        val paramIsland = JSONObject()
        paramIsland.put("islandProperty", 1)
        paramIsland.put("islandPriority", 2)
        paramIsland.put("islandTimeout", 86400000)
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", false)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        // Khi tin nhắn mới đến: bung 3 giây cho người dùng xem rồi tự thu lại về camera
        paramIsland.put("expandedTime", if (isNewMessage) 3 else 0)
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

        // baseInfo (type = 2) + iconTextInfo (kèm animIconInfo) theo đúng chuẩn MessageTranslator của HyperBridge:
        // Đảm bảo FocusPlugin luôn tạo islandExpandedView đầy đủ để khi người dùng chạm lần 1 vào viên thuốc Đảo,
        // Đảo sẽ bung to ra thẻ thông báo chi tiết kèm nút Trả lời nhanh / Sao chép OTP!
        val baseInfo = JSONObject()
        baseInfo.put("type", 2)
        baseInfo.put("title", title)
        baseInfo.put("content", text)
        paramV2.put("baseInfo", baseInfo)

        val iconTextInfo = JSONObject()
        val animIconInfo = JSONObject()
        animIconInfo.put("type", 1)
        animIconInfo.put("src", "miui.focus.pic_$picKey")
        iconTextInfo.put("animIconInfo", animIconInfo)
        iconTextInfo.put("title", title)
        iconTextInfo.put("content", text)
        paramV2.put("iconTextInfo", iconTextInfo)

        // 2. Tương tác trên đảo: Trích xuất Actions & Tạo nút Trả Lời Nhanh / Sao chép OTP
        val actionsMap = mutableMapOf<String, Any>()
        val textButtonsArray = org.json.JSONArray()

        // 2.1 Kiểm tra OTP thông minh (Smart Actions OTP)
        val otpCode = com.ltm.dynamichyper.core.SmartActionsExtractor.findOtpCode("$title $text")
        if (otpCode != null) {
            val copyIntent = Intent(this, com.ltm.dynamichyper.core.OtpCopyReceiver::class.java).apply {
                action = "com.ltm.dynamichyper.ACTION_COPY_OTP"
                putExtra("otp_code", otpCode)
            }
            val copyPendingIntent = android.app.PendingIntent.getBroadcast(
                this,
                otpCode.hashCode(),
                copyIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val actionKey = "miui.focus.action_copy_otp"
            actionsMap[actionKey] = copyPendingIntent

            val btnObj = JSONObject()
            btnObj.put("type", 1)
            btnObj.put("actionTitle", "Sao chép $otpCode")
            btnObj.put("action", actionKey)
            btnObj.put("actionIntent", actionKey)
            btnObj.put("actionIntentType", 1)
            btnObj.put("actionTitleColor", "#30D158")
            textButtonsArray.put(btnObj)
        }

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

        paramV2.put("isShowNotification", true)
        root.put("param_v2", paramV2)
        root.put("isShowNotification", true)

        val jsonParam = root.toString()

        // Bọc contentIntent qua IslandTapActivity (chuẩn HyperBridge):
        // Khi người dùng chạm vào thẻ Đảo đã bung để mở xem thông báo -> Mở app gốc, đồng thời
        // đóng Đảo thông báo đó và tự động khôi phục lại Đảo Chờ 24/7!
        val tapIntent = Intent(this, IslandTapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(IslandTapActivity.EXTRA_SBN_KEY, sbn.key)
            putExtra(IslandTapActivity.EXTRA_ISLAND_ID, dynamicIslandId)
            putExtra(IslandTapActivity.EXTRA_ORIGINAL_INTENT, notif.contentIntent)
            putExtra(IslandTapActivity.EXTRA_TARGET_PKG, sbn.packageName)
        }
        val wrappedContentIntent = android.app.PendingIntent.getActivity(
            this,
            dynamicIslandId,
            tapIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = FocusProtocolHelper.buildFocusNotification(
            context = this,
            title = title,
            content = text,
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = jsonParam,
            pics = pics,
            isOngoing = isMedia,
            // Chỉ alert/bung ra khi là tin nhắn mới nhận diện lần đầu; các bản cập nhật sau chỉ cập nhật âm thầm
            onlyAlertOnce = !isNewMessage,
            contentIntent = if (!isMedia) wrappedContentIntent else notif.contentIntent,
            actionsMap = actionsMap
        )

        FocusProtocolHelper.postNotificationSafely(this, dynamicIslandId, notification, scope = serviceScope)

        // Loại bỏ thông báo gốc của app nhắn tin khỏi Notification Shade CHỈ KHI người dùng chủ động bật tuỳ chọn
        // Mặc định giữ thông báo gốc để đảm bảo người dùng không bao giờ bị lỡ tin nhắn
        val shouldHideOriginal = prefs.isHideOriginalNotification.first()
        if (isNewMessage && shouldHideOriginal) {
            try {
                intentionallyRemovedKeys[sbn.key] = System.currentTimeMillis()
                cancelNotification(sbn.key)
                com.ltm.dynamichyper.core.AppLogger.i("DynamicNotif", "Đã ẩn thông báo gốc của ${sbn.packageName} theo cấu hình người dùng")
            } catch (e: Exception) {
                intentionallyRemovedKeys.remove(sbn.key)
                Log.w("DynamicNotif", "Không thể cancelNotification cho sbn.key: ${e.message}")
            }
        }
        com.ltm.dynamichyper.core.AppLogger.i(TAG, "Đã gửi thông báo Focus cho ${sbn.packageName} (id=$dynamicIslandId, isMedia=$isMedia)")

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
