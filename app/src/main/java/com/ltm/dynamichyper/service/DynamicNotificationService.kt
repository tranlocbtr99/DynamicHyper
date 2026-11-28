package com.ltm.dynamichyper.service

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.core.IslandProbe
import com.ltm.dynamichyper.core.ListenerWatchdog
import com.ltm.dynamichyper.core.NativeIslandTracker
import com.ltm.dynamichyper.core.RemoteViewsExtractor
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.feature.islands.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * DynamicNotificationService - Dịch vụ lắng nghe thông báo lõi của Dynamic Hyper.
 * Phân loại thông báo (Cuộc gọi, Quay màn hình, Dẫn đường, Tin nhắn, Nhạc, Chuông/Rung, Tai nghe Bluetooth),
 * điều phối Đảo Chờ 24/7 trên cả Màn hình khóa & Màn hình chính, và tự động dọn dẹp khi người dùng xem hoặc xóa thông báo.
 */
class DynamicNotificationService : NotificationListenerService() {

    private val TAG = "DynamicHyper_Service"
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: PreferencesManager
    private lateinit var idleIslandManager: IdleIslandManager
    private lateinit var vpnIslandManager: VpnIslandManager
    private lateinit var chargingIslandManager: ChargingIslandManager
    private lateinit var ringerIslandManager: RingerIslandManager
    private lateinit var bluetoothIslandManager: BluetoothIslandManager
    private lateinit var callIslandManager: CallIslandManager

    private val activeDynamicIslands = ConcurrentHashMap<String, Int>()
    private val activeDismissJobs = ConcurrentHashMap<String, Job>()
    private val lastNotificationContent = ConcurrentHashMap<String, String>()
    private val lastPostedTimestamp = ConcurrentHashMap<String, Long>()
    private val intentionallyRemovedKeys = ConcurrentHashMap<String, Long>()
    private val activeNativeMediaKeys = ConcurrentHashMap.newKeySet<String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    idleIslandManager.onScreenLockStateChanged(action)
                    reconcileActiveIslandsAndRestoreIdle(forceReassert = true)
                }
            }
        }
    }

    private val islandClickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == IslandTapActivity.ACTION_ISLAND_CLICKED) {
                val sbnKey = intent.getStringExtra(IslandTapActivity.EXTRA_SBN_KEY)
                val islandId = intent.getIntExtra(IslandTapActivity.EXTRA_ISLAND_ID, -1)
                val isPersistentInteractive = intent.getBooleanExtra(IslandTapActivity.EXTRA_IS_MEDIA, false)
                if (isPersistentInteractive) {
                    Log.d(TAG, "Người dùng chạm mở trình phát nhạc/video/báo thức -> giữ đảo (key=$sbnKey)")
                    return
                }
                Log.i(TAG, "Người dùng đã nhấn xem thông báo trên Đảo (key=$sbnKey, id=$islandId) -> Dọn đảo & khôi phục Đảo Chờ")

                if (sbnKey != null) {
                    activeDynamicIslands.remove(sbnKey)
                    intentionallyRemovedKeys.remove(sbnKey)
                    activeDismissJobs.remove(sbnKey)?.cancel()
                    lastNotificationContent.remove(sbnKey)
                    try {
                        cancelNotification(sbnKey)
                    } catch (_: Exception) {}
                }
                if (islandId != -1) {
                    val siblingKeys = activeDynamicIslands.entries.filter { it.value == islandId }.map { it.key }
                    for (k in siblingKeys) {
                        activeDynamicIslands.remove(k)
                        intentionallyRemovedKeys.remove(k)
                        activeDismissJobs.remove(k)?.cancel()
                        lastNotificationContent.remove(k)
                    }
                    IslandProbe.cancelTrack(islandId)
                    ShizukuEngine.clearPostedActiveId(islandId)
                    try {
                        NotificationManagerCompat.from(this@DynamicNotificationService).cancel(islandId)
                    } catch (_: Exception) {}
                }
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
        ringerIslandManager = RingerIslandManager(this, serviceScope, prefs)
        ringerIslandManager.startListening()
        bluetoothIslandManager = BluetoothIslandManager(this, serviceScope, prefs)
        bluetoothIslandManager.startListening()
        callIslandManager = CallIslandManager(this, serviceScope, prefs)

        serviceScope.launch {
            prefs.isVpnIslandEnabled.collect { enabled ->
                if (enabled) {
                    vpnIslandManager.startListening()
                } else {
                    vpnIslandManager.stopListening()
                }
            }
        }

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)

        ContextCompat.registerReceiver(
            this,
            islandClickReceiver,
            IntentFilter(IslandTapActivity.ACTION_ISLAND_CLICKED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "NotificationListener Connected successfully")
        ListenerWatchdog.isListenerConnected = true
        ListenerWatchdog.start(this, serviceScope)
        idleIslandManager.isShowing = false
        IdleIslandManager.resetCache()
        idleIslandManager.reassert(force = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener Disconnected!")
        ListenerWatchdog.isListenerConnected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        AppLogger.d(TAG, "Bắt thông báo từ gói: ${sbn.packageName} (id=${sbn.id})")

        // Nhường chỗ cho Native Island & Native MediaSession Island của hệ thống Xiaomi (Yield Policy)
        val hasNativeMediaSession = sbn.notification.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        if (hasNativeMediaSession) {
            activeNativeMediaKeys.add(sbn.key)
            IdleIslandManager.setBlockerStatic("native_media", true)
            return
        }

        val focusParam = sbn.notification.extras?.getString("miui.focus.param")
        if (focusParam != null) {
            val yieldMs = NativeIslandTracker.calculateYieldMs(
                hasFocusParam = true,
                focusParam = focusParam,
                isMediaPlayer = false
            )
            if (yieldMs != null) {
                NativeIslandTracker.note(sbn.key, yieldMs)
                IdleIslandManager.setBlockerStatic("native", true)
            }
            return
        }

        if (NavigationIslandManager.isNavNotification(sbn)) {
            val title = sbn.notification.extras?.getString(Notification.EXTRA_TITLE) ?: ""
            val content = sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            IdleIslandManager.setBlockerStatic("nav", true)
            NavigationIslandManager.showNavIsland(this, title, content)
            return
        }

        if (CallIslandManager.isCallNotification(sbn)) {
            Log.i(TAG, "Incoming call detected from ${sbn.packageName}")
            IdleIslandManager.setBlockerStatic("call", true)
            callIslandManager.handleCallNotification(sbn)
            return
        }

        if (ScreenRecordingIslandManager.isScreenRecordingNotification(sbn)) {
            serviceScope.launch {
                if (prefs.isScreenRecordingIslandEnabled.first()) {
                    IdleIslandManager.setBlockerStatic("record", true)
                    ScreenRecordingIslandManager.showRecordingIsland(this@DynamicNotificationService)
                }
            }
            return
        }

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

        if (sbn.packageName == packageName) {
            when (sbn.id) {
                IdleIslandManager.IDLE_ISLAND_ID -> {
                    idleIslandManager.isShowing = false
                    IdleIslandManager.resetCache()
                    serviceScope.launch {
                        if (prefs.isPersistentMode.first()) {
                            Log.i(TAG, "Idle Island (9999) was removed. Auto-restoring immediately...")
                            delay(150L)
                            idleIslandManager.reassert(force = true)
                        }
                    }
                }
                CallIslandManager.CALL_ISLAND_ID -> {
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
                    }
                }
                ChargingIslandManager.CHARGING_NOTIFICATION_ID,
                RingerIslandManager.RINGER_NOTIFICATION_ID,
                BluetoothIslandManager.BLUETOOTH_NOTIFICATION_ID,
                ScreenRecordingIslandManager.RECORDING_NOTIFICATION_ID,
                VpnIslandManager.VPN_NOTIFICATION_ID,
                NavigationIslandManager.NAV_ISLAND_ID,
                DebuggingIslandManager.DEBUGGING_ISLAND_ID -> {
                    serviceScope.launch {
                        delay(150L)
                        idleIslandManager.reassert(force = true)
                    }
                }
                else -> {
                    val matchingKeys = activeDynamicIslands.entries.filter { it.value == sbn.id }.map { it.key }
                    if (matchingKeys.isNotEmpty()) {
                        for (k in matchingKeys) {
                            activeDynamicIslands.remove(k)
                            activeDismissJobs.remove(k)?.cancel()
                            lastNotificationContent.remove(k)
                            intentionallyRemovedKeys.remove(k)
                        }
                        idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
                    }
                }
            }
            return
        }

        if (activeNativeMediaKeys.remove(sbn.key)) {
            IdleIslandManager.setBlockerStatic("native_media", activeNativeMediaKeys.isNotEmpty())
        }

        if (NativeIslandTracker.remove(sbn.key)) {
            IdleIslandManager.setBlockerStatic("native", NativeIslandTracker.hasFresh())
        }

        if (NavigationIslandManager.isNavNotification(sbn)) {
            NavigationIslandManager.hideNavIsland(this)
            IdleIslandManager.setBlockerStatic("nav", false)
        }

        if (sbn.key == callIslandManager.getCurrentCallKey()) {
            callIslandManager.hideCallIsland()
            IdleIslandManager.setBlockerStatic("call", false)
        }

        if (sbn.packageName == "com.miui.screenrecorder") {
            ScreenRecordingIslandManager.hideRecordingIsland(this)
            IdleIslandManager.setBlockerStatic("record", false)
        }

        // QUAN TRỌNG: Không dùng `.remove(sbn.key)` tại đây!
        // Khi Messenger/Zalo bắn thông báo liên hoàn (x2, x4 trong 1 giây) và `isHideOriginalNotification == true`,
        // hệ thống có thể gọi `onNotificationRemoved` nhiều lần cho cùng một `sbn.key`.
        // Nếu xóa khỏi `intentionallyRemovedKeys` ngay lần đầu, lần gọi thứ 2 và hàm `reconcileActiveIslandsAndRestoreIdle`
        // sẽ thấy `intentionallyRemovedKeys[sbn.key] == null` và TỰ TAY HỦY MẤT ĐẢO MESSENGER vừa tạo!
        if (intentionallyRemovedKeys.containsKey(sbn.key)) {
            return
        }

        val dynamicIslandId = activeDynamicIslands.remove(sbn.key)
        lastNotificationContent.remove(sbn.key)
        if (dynamicIslandId != null) {
            activeDismissJobs.remove(sbn.key)?.cancel()
            // Chỉ hủy thông báo Đảo nếu không còn key nào khác đang dùng chung dynamicIslandId này
            val stillReferenced = activeDynamicIslands.values.any { it == dynamicIslandId }
            if (!stillReferenced) {
                IslandProbe.cancelTrack(dynamicIslandId)
                ShizukuEngine.clearPostedActiveId(dynamicIslandId)
                try {
                    NotificationManagerCompat.from(this).cancel(dynamicIslandId)
                } catch (_: Exception) {}
            }
            idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
        }

        serviceScope.launch {
            delay(200L)
            reconcileActiveIslandsAndRestoreIdle()
        }
    }

    private fun reconcileActiveIslandsAndRestoreIdle(forceReassert: Boolean = false) {
        try {
            val currentNotifs = try { activeNotifications } catch (_: Exception) { null } ?: return
            val liveExternalKeys = currentNotifs.filter { it.packageName != packageName }.map { it.key }.toSet()
            val liveOwnIds = currentNotifs.filter { it.packageName == packageName }.map { it.id }.toSet()

            activeNativeMediaKeys.retainAll(liveExternalKeys)
            IdleIslandManager.setBlockerStatic("native_media", activeNativeMediaKeys.isNotEmpty())

            val now = System.currentTimeMillis()
            val iterator = activeDynamicIslands.entries.iterator()
            while (iterator.hasNext()) {
                val (key, islandId) = iterator.next()
                val removedAt = intentionallyRemovedKeys[key]
                val externalAlive = liveExternalKeys.contains(key)
                val ownIslandAlive = liveOwnIds.contains(islandId) || (removedAt != null && now - removedAt < 4000L)
                if ((!externalAlive && removedAt == null) || (removedAt != null && !ownIslandAlive)) {
                    iterator.remove()
                    intentionallyRemovedKeys.remove(key)
                    activeDismissJobs.remove(key)?.cancel()
                    lastNotificationContent.remove(key)
                    val stillReferenced = activeDynamicIslands.values.any { it == islandId }
                    if (!stillReferenced) {
                        IslandProbe.cancelTrack(islandId)
                        ShizukuEngine.clearPostedActiveId(islandId)
                        try {
                            NotificationManagerCompat.from(this).cancel(islandId)
                        } catch (_: Exception) {}
                    }
                }
            }

            if (activeDynamicIslands.isEmpty()) {
                idleIslandManager.onEventCountChanged(0)
            }
            val isLocked = IdleIslandManager.isPhoneLocked || FocusProtocolHelper.isKeyguardLocked(this)
            if (forceReassert && !IdleIslandManager.shouldSuppressIdle(onLockscreen = isLocked)) {
                idleIslandManager.reassert(force = true, delayMs = 150L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "reconcileActiveIslands error: ${e.message}")
        }
    }

    private suspend fun handleIncomingNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return

        // Lọc Group Summary
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
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
            if (hasLiveChild) return
        }

        val extras = notif.extras ?: return
        var title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim() ?: ""
        var text = extractBestNotificationText(extras)

        // Fallback trích xuất văn bản & đồng hồ đếm từ custom RemoteViews nếu EXTRA_TITLE và EXTRA_TEXT bị rỗng
        if (title.isBlank() && text.isBlank()) {
            val extractedRv = RemoteViewsExtractor.extract(this, sbn)
            if (extractedRv != null) {
                title = extractedRv.title
                text = extractedRv.text
            }
        }

        if (title.isBlank() && text.isBlank()) return
        if (title.equals(sbn.packageName, ignoreCase = true) || text.equals(sbn.packageName, ignoreCase = true)) return

        val isMessaging = isMessagingApp(sbn)
        val isMedia = isMediaApp(sbn)
        val isAlarm = isAlarmNotification(sbn)
        val progressPercent = extractProgressPercent(extras)

        // Lọc thông báo hệ thống chạy ngầm & dịch vụ ongoing không phải nhạc/báo thức/tiến trình tải
        if (sbn.packageName in IGNORED_SYSTEM_PACKAGES) return
        if (sbn.isOngoing && !isMedia && !isAlarm && progressPercent == null) return

        val disabledApps = prefs.disabledPackages.first()
        if (!prefs.isPackageAllowed(sbn.packageName, disabledApps)) return
        if (isMedia && !prefs.isMediaIslandEnabled.first()) return
        if (!isMedia && !isAlarm && !prefs.isMessageIslandEnabled.first()) return

        val contentKey = "${sbn.packageName}|$title|$text|${progressPercent ?: -1}"
        val now = System.currentTimeMillis()
        val lastAt = lastPostedTimestamp[contentKey] ?: 0L
        val isSameContent = (lastNotificationContent[sbn.key] == contentKey)
        if (isSameContent && (isMedia || now - lastAt < 800L)) return
        lastPostedTimestamp[contentKey] = now

        val isNewMessage = !isSameContent && !isMedia
        lastNotificationContent[sbn.key] = contentKey

        // Gom chung ID theo gói ứng dụng + người gửi/tiêu đề để cập nhật gọn trên 1 thẻ duy nhất, không đẻ nhiều thẻ trùng
        val dynamicIslandId = "${sbn.packageName}|${title.ifBlank { sbn.key }}".hashCode()
        activeDynamicIslands[sbn.key] = dynamicIslandId
        activeDismissJobs.remove(sbn.key)?.cancel()
        idleIslandManager.onEventCountChanged(activeDynamicIslands.size)

        val notification = NotificationPayloadBuilder.buildAppFocusNotification(
            context = this,
            sbn = sbn,
            title = title.ifBlank { sbn.packageName.substringAfterLast('.') },
            text = text,
            isMedia = isMedia,
            isAlarm = isAlarm,
            progressPercent = progressPercent,
            isNewMessage = isNewMessage,
            isPaletteEnabled = prefs.isPaletteColorEnabled.first(),
            isGradientGlow = prefs.isNotificationGradientGlow.first(),
            dynamicIslandId = dynamicIslandId
        )

        FocusProtocolHelper.postNotificationSafely(this, dynamicIslandId, notification, scope = serviceScope)

        if (!sbn.isOngoing && !isMedia && !isAlarm && progressPercent == null && prefs.isHideOriginalNotification.first()) {
            cancelOriginalAndEmptyGroupSummary(sbn)
        }
        AppLogger.i(TAG, "Đã gửi thông báo Focus cho ${sbn.packageName} (id=$dynamicIslandId, media=$isMedia, alarm=$isAlarm)")

        // Cơ chế collapseToAppropriateState cho thông báo:
        // Sau khi thông báo bung Đảo lớn xong (5.5s), tự động nhả blocker "notif" để Đảo Chờ 24/7 luôn sống;
        // nếu người dùng tắt "Giữ Đảo Thu Nhỏ" (!isPersistent) thì hủy luôn thông báo Focus của app đó.
        val isPersistent = prefs.isPersistentNotificationIsland.first()
        if (!isMedia && !isAlarm && progressPercent == null) {
            val dismissJob = serviceScope.launch {
                delay(5500L)
                activeDynamicIslands.remove(sbn.key)
                activeDismissJobs.remove(sbn.key)
                if (!isPersistent) {
                    intentionallyRemovedKeys.remove(sbn.key)
                    val stillReferenced = activeDynamicIslands.values.any { it == dynamicIslandId }
                    if (!stillReferenced) {
                        IslandProbe.cancelTrack(dynamicIslandId)
                        ShizukuEngine.clearPostedActiveId(dynamicIslandId)
                        try {
                            NotificationManagerCompat.from(this@DynamicNotificationService).cancel(dynamicIslandId)
                        } catch (_: Exception) {}
                    }
                }
                idleIslandManager.onEventCountChanged(activeDynamicIslands.size)
            }
            activeDismissJobs[sbn.key] = dismissJob
        }
    }

    private fun cancelOriginalAndEmptyGroupSummary(sbn: StatusBarNotification) {
        val now = System.currentTimeMillis()
        val lastRemoved = intentionallyRemovedKeys[sbn.key] ?: 0L
        intentionallyRemovedKeys[sbn.key] = now
        // Nếu vừa mới gọi cancelNotification(sbn.key) trong vòng 350ms (do Messenger bắn burst x2-x4), không gọi lặp lại
        if (now - lastRemoved < 350L) return
        try {
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            intentionallyRemovedKeys.remove(sbn.key)
            Log.w("DynamicNotif", "Không thể cancelNotification: ${e.message}")
            return
        }

        // Nếu app gốc (Zalo, Messenger, Telegram...) có thông báo tóm tắt nhóm (FLAG_GROUP_SUMMARY)
        // mà không còn thông báo con nào khác, xóa luôn thẻ summary để không bị trùng trên Trung tâm thông báo
        val group = sbn.notification?.group
        if (!group.isNullOrBlank()) {
            try {
                val current = activeNotifications ?: return
                val sameGroup = current.filter {
                    it.packageName == sbn.packageName &&
                        it.key != sbn.key &&
                        it.notification?.group == group
                }
                val hasOtherChildren = sameGroup.any {
                    (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
                }
                if (!hasOtherChildren) {
                    for (summary in sameGroup) {
                        intentionallyRemovedKeys[summary.key] = now
                        cancelNotification(summary.key)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun extractBestNotificationText(extras: android.os.Bundle): String {
        val baseText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim() ?: ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!msgs.isNullOrEmpty()) {
                val lastBundle = msgs.last() as? android.os.Bundle
                val msgText = lastBundle?.getCharSequence("text")?.toString()?.trim()
                val sender = lastBundle?.getCharSequence("sender")?.toString()?.trim()
                if (!msgText.isNullOrBlank()) {
                    return if (!sender.isNullOrBlank() && !msgText.startsWith(sender)) "$sender: $msgText" else msgText
                }
            }
        }
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            val lastLine = lines.lastOrNull()?.toString()?.trim()
            if (!lastLine.isNullOrBlank() && (baseText.isBlank() || baseText.contains("tin nhắn mới", true))) {
                return lastLine
            }
        }
        return if (bigText.length > baseText.length && baseText.isNotBlank()) bigText else baseText.ifBlank { bigText }
    }

    private fun extractProgressPercent(extras: android.os.Bundle): Int? {
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        if (max > 0 && progress in 0..max && !indeterminate) {
            return ((progress * 100f) / max).toInt().coerceIn(0, 100)
        }
        return null
    }

    private fun isAlarmNotification(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification ?: return false
        if (notif.category == Notification.CATEGORY_ALARM) return true
        val pkg = sbn.packageName.lowercase()
        return pkg in ALARM_PACKAGES && (notif.actions?.isNotEmpty() == true || notif.fullScreenIntent != null)
    }

    private fun isMessagingApp(sbn: StatusBarNotification): Boolean {
        val cat = sbn.notification?.category
        if (cat == Notification.CATEGORY_MESSAGE || cat == Notification.CATEGORY_SOCIAL || cat == Notification.CATEGORY_EMAIL) {
            return true
        }
        val pkg = sbn.packageName.lowercase()
        return MESSAGING_KEYWORDS.any { pkg.contains(it) }
    }

    private fun isMediaApp(sbn: StatusBarNotification): Boolean {
        val notif = sbn.notification ?: return false
        val template = notif.extras?.getString(Notification.EXTRA_TEMPLATE) ?: ""
        if (template.contains("MediaStyle") || notif.category == Notification.CATEGORY_TRANSPORT) {
            return true
        }
        val pkg = sbn.packageName.lowercase()
        return MEDIA_KEYWORDS.any { pkg.contains(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        vpnIslandManager.stopListening()
        chargingIslandManager.stopListening()
        ringerIslandManager.stopListening()
        bluetoothIslandManager.stopListening()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(islandClickReceiver) } catch (_: Exception) {}
    }

    companion object {
        private val IGNORED_SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.lbe.security.miui",
            "com.miui.securitycenter",
            "com.xiaomi.xmsf",
            "com.miui.screenshot"
        )
        private val MESSAGING_KEYWORDS = listOf(
            "zalo", "orca", "messenger", "whatsapp", "telegram", "viber",
            "discord", "instagram", "mms", "messaging", "messages", "wechat", "line", "skype"
        )
        private val ALARM_PACKAGES = setOf(
            "com.android.deskclock",
            "com.google.android.deskclock",
            "com.miui.clock"
        )
        private val MEDIA_KEYWORDS = listOf(
            "spotify", "music", "zing", "youtube", "soundcloud", "apple", "audio", "podcast",
            "tiktok", "netflix", "vlc", "mxtech", "bilibili", "video", "player"
        )
    }
}
