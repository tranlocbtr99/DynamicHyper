package com.ltm.dynamichyper.feature.islands

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.MainActivity
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.core.NativeIslandTracker
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.feature.weather.WeatherManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Quản lý Đảo Chờ 24/7 (Idle Island) cho Dynamic Hyper.
 * - Hoạt động 24/7 trên cả Màn hình chính (`islandProperty = 1`) lẫn Màn hình khóa (`islandProperty = 0`).
 * - Áp dụng bộ máy trạng thái `handleScreenStateChanged()` & `collapseToAppropriateState()`:
 *   Khi khóa màn hình (`isPhoneLocked == true`), ưu tiên thu gọn về Đảo Chờ (`id=9999`, `islandProperty = 0`)
 *   và vượt qua các blocker phụ (`notif`, `charging`, `ringer`, `bluetooth`).
 * - Có cơ chế Self-Healing (tự phục hồi mỗi 10 giây nếu bị SystemUI ẩn do kéo thanh thông báo `TempHidden`).
 */
class IdleIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager
) {
    companion object {
        const val IDLE_ISLAND_ID = 9999
        private const val PIC_IDLE_DOT = "idle_dot"
        private const val PIC_IDLE_LEFT = "idle_left"
        private const val PIC_HIDDEN = "hidden_pixel"
        private const val REASSERT_DELAY_MS = 450L
        private const val TAG = "DynamicHyper_Idle"

        // Chỉ các blocker khẩn cấp thực sự đang diễn ra (cuộc gọi, quay màn hình, dẫn đường) mới chặn Đảo Chờ trên Màn hình khóa
        private val CRITICAL_LOCKSCREEN_BLOCKERS = setOf("call", "record", "nav")

        @Volatile
        private var instance: IdleIslandManager? = null

        @Volatile
        private var lastDispatchedWidgetText: String = ""

        @Volatile
        var isPhoneLocked: Boolean = false
            private set

        private val activeBlockers = ConcurrentHashMap.newKeySet<String>()

        fun getInstance(): IdleIslandManager? = instance

        fun setBlockerStatic(tag: String, active: Boolean) {
            if (active) activeBlockers.add(tag) else activeBlockers.remove(tag)
            instance?.onBlockersChanged()
        }

        fun getActiveBlockersSnapshot(): Set<String> = activeBlockers.toSet()

        /**
         * Trên màn hình khóa (`isPhoneLocked == true`), học theo `DynamicBar.handleScreenStateChanged()`:
         * Luôn ưu tiên hiển thị Đảo Chờ (Minimal) trừ khi đang có cuộc gọi / quay màn hình / dẫn đường.
         */
        fun shouldSuppressIdle(onLockscreen: Boolean = isPhoneLocked): Boolean {
            if (onLockscreen) {
                return activeBlockers.any { it in CRITICAL_LOCKSCREEN_BLOCKERS }
            }
            return activeBlockers.isNotEmpty() || NativeIslandTracker.hasFresh()
        }

        fun reassertStatic(force: Boolean = true, delayMs: Long = REASSERT_DELAY_MS) {
            instance?.reassert(force = force, delayMs = delayMs)
        }

        fun showIdleIsland(context: Context, width: Int, style: String = "minimal", force: Boolean = true) {
            instance?.reassert(force = force, delayMs = 100L) ?: dispatchDirect(context, width, style, force = force)
        }

        fun hideIdleIsland(context: Context) {
            try {
                ShizukuEngine.clearPostedActiveId(IDLE_ISLAND_ID)
                NotificationManagerCompat.from(context).cancel(IDLE_ISLAND_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding idle island: ${e.message}")
            }
        }

        fun updateIdleIslandWidth(context: Context, width: Int) {
            instance?.reassert(force = true, delayMs = 100L) ?: dispatchDirect(context, width, "minimal", force = true)
        }

        fun updateStyle(context: Context, style: String) {
            instance?.updateStyleInternal(style) ?: dispatchDirect(context, 10, style, force = true)
        }

        fun resetCache() {
            lastDispatchedWidgetText = ""
            instance?.isShowing = false
        }

        fun dispatchDirect(
            context: Context,
            width: Int,
            style: String = "minimal",
            force: Boolean = false,
            forceLockscreenState: Boolean? = null
        ) {
            try {
                val isLocked = forceLockscreenState ?: (isPhoneLocked || FocusProtocolHelper.isKeyguardLocked(context))
                isPhoneLocked = isLocked

                if (shouldSuppressIdle(onLockscreen = isLocked)) {
                    val remaining = NativeIslandTracker.remainingYieldMs()
                    Log.d(TAG, "Tạm hoãn Đảo Chờ: blockers=$activeBlockers, isLocked=$isLocked, nativeYield=${remaining}ms")
                    return
                }

                val effectiveWidth = if (width > 0) width else 8
                val emptyString = "\u00A0".repeat(effectiveWidth)

                val weather = WeatherManager.currentData
                val temp = IdleIslandRenderer.getBatteryTemperature(context)
                val ramGb = IdleIslandRenderer.getUsedRamGb(context)

                // Thông tin hiển thị trên thẻ bung mở rộng (Expanded Card) và thông số ngắn bên phải viên thuốc
                val (notifTitle, notifContent, pillRightText, highlightColor) = when (style) {
                    "weather" -> Quad(
                        "${weather.iconEmoji} ${weather.temp}°C • ${weather.condition} (Cảm giác ${weather.apparentTemp}°C)",
                        "${weather.city} • Ẩm ${weather.humidity}% • Gió ${weather.windSpeed} km/h • ${weather.tempMin}°–${weather.tempMax}°C",
                        "${weather.temp}°C",
                        "#FFD60A"
                    )
                    "battery_temp" -> Quad(
                        "Pin ${temp}°C • ${if (temp < 38) "Mát mẻ" else if (temp <= 42) "Ấm" else "Nóng"}",
                        "${IdleIslandRenderer.getBatteryPowerSummary(context)} • Thời tiết ${weather.temp}°C",
                        "${temp}°C",
                        if (temp < 38) "#30D158" else if (temp <= 42) "#FF9F0A" else "#FF453A"
                    )
                    "ram_usage" -> Quad(
                        "RAM đang dùng $ramGb GB",
                        IdleIslandRenderer.getRamDetailSummary(context),
                        "${ramGb}G",
                        "#00F2FE"
                    )
                    "green_dot" -> Quad(
                        "Dynamic Hyper • Đảo Chờ",
                        "Pin ${temp}°C • RAM $ramGb GB • ${weather.temp}°C",
                        "",
                        "#30D158"
                    )
                    else -> Quad(
                        "Dynamic Hyper • Đảo Chờ",
                        "Pin ${temp}°C • RAM $ramGb GB • ${weather.temp}°C",
                        "",
                        "#38BDF8"
                    )
                }

                val jsonParam = buildIdleJson(
                    style = style,
                    emptyString = emptyString,
                    pillRightText = pillRightText,
                    notifTitle = notifTitle,
                    notifContent = notifContent,
                    highlightColor = highlightColor,
                    isLocked = isLocked
                )

                val bigWidgetBmp = IdleIslandRenderer.renderBigWidgetIcon(context, style)
                val pics = mapOf(
                    PIC_IDLE_DOT to IdleIslandRenderer.renderIdleDotBitmap(context, style),
                    PIC_IDLE_LEFT to bigWidgetBmp,
                    PIC_HIDDEN to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                )

                val contentIntent = PendingIntent.getActivity(
                    context,
                    IDLE_ISLAND_ID,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val currentText = "$style|$notifTitle|$notifContent|$effectiveWidth|$isLocked"
                if (!force && lastDispatchedWidgetText == currentText && instance?.isShowing == true) {
                    return
                }
                lastDispatchedWidgetText = currentText

                if (force) {
                    ShizukuEngine.clearPostedActiveId(IDLE_ISLAND_ID)
                }

                val notification = FocusProtocolHelper.buildFocusNotification(
                    context = context,
                    title = notifTitle,
                    content = notifContent,
                    smallIconRes = R.drawable.ic_launcher_foreground,
                    jsonParam = jsonParam,
                    pics = pics,
                    isOngoing = true,
                    onlyAlertOnce = true,
                    isSilent = true,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    contentIntent = contentIntent,
                    enablePromotedLiveUpdate = false,
                    largeIcon = bigWidgetBmp,
                    keepIslandOnLockscreen = true,
                    notificationId = IDLE_ISLAND_ID,
                    forceLockscreenProperty = isLocked
                )

                FocusProtocolHelper.postNotificationSafely(
                    context = context,
                    id = IDLE_ISLAND_ID,
                    notification = notification,
                    shouldCancel = { shouldSuppressIdle(onLockscreen = isLocked) }
                )
                instance?.isShowing = true
                AppLogger.i(TAG, "Đã gửi Đảo Chờ 24/7 (style=$style, width=$effectiveWidth, isLocked=$isLocked, property=${if (isLocked) 0 else 1})")
            } catch (e: Exception) {
                Log.e(TAG, "Error direct dispatch: ${e.message}")
                AppLogger.e(TAG, "Lỗi gửi thông báo Đảo Chờ: ${e.message}", e)
            }
        }

        private data class Quad(
            val title: String,
            val content: String,
            val pillRightText: String,
            val highlightColor: String
        )

        private fun buildIdleJson(
            style: String,
            emptyString: String,
            pillRightText: String,
            notifTitle: String,
            notifContent: String,
            highlightColor: String,
            isLocked: Boolean
        ): String {
            val leftInfo = JSONObject().apply {
                put("type", 1)
                // Trên màn hình khóa, HyperOS FocusPlugin yêu cầu picInfo hợp lệ để không lọc bỏ viên thuốc
                if (style != "minimal") {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_$PIC_IDLE_DOT")
                    })
                } else if (isLocked) {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_$PIC_HIDDEN")
                    })
                }
                put("textInfo", JSONObject().apply {
                    put("title", if (style == "minimal" || style == "green_dot") emptyString else "")
                    put("content", if (style == "minimal" || style == "green_dot") emptyString else "")
                    put("showHighlightColor", false)
                })
            }

            val bigIslandArea = JSONObject().apply {
                put("imageTextInfoLeft", leftInfo)
                if (pillRightText.isNotBlank()) {
                    put("imageTextInfoRight", JSONObject().apply {
                        put("type", 1)
                        put("picInfo", JSONObject().apply {
                            put("type", 1)
                            put("pic", "miui.focus.pic_$PIC_HIDDEN")
                        })
                        put("textInfo", JSONObject().apply {
                            put("title", pillRightText)
                            put("content", "")
                            put("showHighlightColor", true)
                        })
                    })
                }
            }

            val paramIsland = JSONObject().apply {
                put("islandProperty", if (isLocked) 0 else 1)
                put("islandPriority", 2)
                put("islandTimeout", 86400000)
                put("islandOrder", false)
                put("dismissIsland", false)
                put("maxSize", false)
                put("needCloseAnimation", true)
                put("expandedTime", 0)
                put("highlightColor", highlightColor)
                put("bigIslandArea", bigIslandArea)
                put("smallIslandArea", JSONObject().apply {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_$PIC_IDLE_DOT")
                    })
                })
            }

            val paramV2 = JSONObject().apply {
                put("protocol", 1)
                put("business", "permanent_island")
                put("updatable", true)
                put("ticker", notifTitle)
                put("aodTitle", notifTitle)
                put("aodPic", "miui.focus.pic_$PIC_IDLE_DOT")
                put("timeout", 86400000)
                put("enableFloat", false)
                put("isShowNotification", true)
                put("islandFirstFloat", false)
                put("reopen", true)
                put("param_island", paramIsland)
                put("baseInfo", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_$PIC_IDLE_LEFT")
                    })
                    put("title", notifTitle)
                    put("content", notifContent)
                })
                put("iconTextInfo", JSONObject().apply {
                    put("title", notifTitle)
                    put("content", notifContent)
                })
            }

            return JSONObject().apply {
                put("param_v2", paramV2)
                put("isShowNotification", true)
            }.toString()
        }
    }

    private var isEnabled = true
    var isShowing = false
        internal set
    private var pendingJob: Job? = null
    private var lockTransitionJob: Job? = null

    init {
        instance = this
        isPhoneLocked = FocusProtocolHelper.isKeyguardLocked(context)

        scope.launch {
            prefs.isIdleIslandEnabled.collectLatest { enabled ->
                synchronized(this@IdleIslandManager) {
                    if (isEnabled != enabled) {
                        isEnabled = enabled
                        evaluateStateLocked()
                    }
                }
            }
        }

        // Vòng lặp Self-Healing & Dynamic Refresh mỗi 12 giây:
        // 1. Tự động kiểm tra nếu IDLE_ISLAND_ID (9999) bị mất khỏi NotificationManager.activeNotifications
        //    (hoặc bị SystemUI ẩn sau khi kéo Trung tâm thông báo) -> tự động khôi phục ngay.
        // 2. Đồng bộ trạng thái khóa màn hình (isKeyguardLocked) nếu có thay đổi ngầm.
        // 3. Cập nhật động nhiệt độ pin / RAM / Thời tiết.
        scope.launch {
            var tickCount = 0
            while (true) {
                delay(12 * 1000L)
                tickCount++
                try {
                    if (!prefs.isIdleIslandEnabled.first()) continue
                    val currentLocked = FocusProtocolHelper.isKeyguardLocked(context)
                    val lockChanged = (currentLocked != isPhoneLocked)
                    isPhoneLocked = currentLocked

                    if (shouldSuppressIdle(onLockscreen = currentLocked)) continue

                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    val isAliveInStatusBar = try {
                        nm?.activeNotifications?.any { it.id == IDLE_ISLAND_ID } ?: true
                    } catch (_: Throwable) {
                        true
                    }

                    val style = prefs.idleIslandStyle.first()
                    val isDynamicStyle = (style == "battery_temp" || style == "ram_usage" || style == "weather")

                    if (!isAliveInStatusBar || !isShowing || lockChanged || (isDynamicStyle && tickCount % 2 == 0)) {
                        val width = prefs.idleIslandWidth.first()
                        dispatchDirect(
                            context = context,
                            width = width,
                            style = style,
                            force = !isAliveInStatusBar || lockChanged,
                            forceLockscreenState = currentLocked
                        )
                        isShowing = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Self-healing loop error: ${e.message}")
                }
            }
        }

        // Heartbeat 24/7: Định kỳ 15 phút làm mới Đảo Chờ với force=true để HyperOS không xóa đảo do timeout ngầm
        scope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                try {
                    val currentLocked = FocusProtocolHelper.isKeyguardLocked(context)
                    if (prefs.isIdleIslandEnabled.first() && !shouldSuppressIdle(onLockscreen = currentLocked)) {
                        val width = prefs.idleIslandWidth.first()
                        val style = prefs.idleIslandStyle.first()
                        dispatchDirect(context, width, style, force = true, forceLockscreenState = currentLocked)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat refresh error: ${e.message}")
                }
            }
        }
    }

    /**
     * Học theo `DynamicBar.handleScreenStateChanged()`:
     * - Khi màn hình tắt (`ACTION_SCREEN_OFF`) hoặc bật sáng trên màn hình khóa (`ACTION_SCREEN_ON` + locked):
     *   Ép `isPhoneLocked = true` và phát lại Đảo Chờ với `islandProperty = 0` ở cả 100ms và 850ms
     *   (để vượt qua độ trễ 500ms của KeyguardManager trên HyperOS).
     * - Khi mở khóa thành công (`ACTION_USER_PRESENT`):
     *   Đặt `isPhoneLocked = false` và gọi `collapseToAppropriateState` (nếu không có blocker thì phát với `islandProperty = 1`).
     */
    fun onScreenLockStateChanged(action: String) {
        lockTransitionJob?.cancel()
        lockTransitionJob = scope.launch {
            if (!prefs.isIdleIslandEnabled.first()) return@launch
            val width = prefs.idleIslandWidth.first()
            val style = prefs.idleIslandStyle.first()

            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isPhoneLocked = true
                    delay(120L)
                    dispatchDirect(context, width, style, force = true, forceLockscreenState = true)
                    isShowing = true
                    // Phát bồi lần 2 sau 850ms khi Keyguard của HyperOS đã khóa hoàn toàn
                    delay(750L)
                    if (isPhoneLocked) {
                        dispatchDirect(context, width, style, force = true, forceLockscreenState = true)
                        isShowing = true
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    val lockedNow = FocusProtocolHelper.isKeyguardLocked(context)
                    isPhoneLocked = lockedNow
                    delay(100L)
                    dispatchDirect(context, width, style, force = true, forceLockscreenState = lockedNow)
                    isShowing = !shouldSuppressIdle(onLockscreen = lockedNow)
                }
                Intent.ACTION_USER_PRESENT -> {
                    isPhoneLocked = false
                    delay(150L)
                    if (!shouldSuppressIdle(onLockscreen = false)) {
                        dispatchDirect(context, width, style, force = true, forceLockscreenState = false)
                        isShowing = true
                    } else {
                        hideIdleIsland(context)
                        isShowing = false
                    }
                }
            }
        }
    }

    @Synchronized
    fun onEventCountChanged(count: Int) {
        val active = count > 0
        if (active) activeBlockers.add("notif") else activeBlockers.remove("notif")
        onBlockersChanged()
    }

    @Synchronized
    fun onBlockersChanged() {
        val suppressed = shouldSuppressIdle(onLockscreen = isPhoneLocked)
        Log.d(TAG, "onBlockersChanged: blockers=$activeBlockers, isLocked=$isPhoneLocked, suppressed=$suppressed, isEnabled=$isEnabled")
        if (suppressed) {
            pendingJob?.cancel()
            hideIdleIsland(context)
            isShowing = false
        } else if (isEnabled) {
            reassert()
        }
    }

    fun updateStyleInternal(style: String) {
        pendingJob?.cancel()
        if (shouldSuppressIdle(onLockscreen = isPhoneLocked)) return
        pendingJob = scope.launch {
            val width = prefs.idleIslandWidth.first()
            dispatchDirect(context, width, style, force = true)
            isShowing = !shouldSuppressIdle(onLockscreen = isPhoneLocked)
            Log.d(TAG, "Idle Island immediately updated to style: $style")
        }
    }

    fun reassert(force: Boolean = true, delayMs: Long = REASSERT_DELAY_MS) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            val currentLocked = isPhoneLocked || FocusProtocolHelper.isKeyguardLocked(context)
            if (prefs.isIdleIslandEnabled.first() && !shouldSuppressIdle(onLockscreen = currentLocked)) {
                val width = prefs.idleIslandWidth.first()
                val style = prefs.idleIslandStyle.first()
                dispatchDirect(context, width, style, force = force, forceLockscreenState = currentLocked)
                isShowing = true
                Log.d(TAG, "Idle Island reasserted (style=$style, width=$width, isLocked=$currentLocked, delay=${delayMs}ms)")
            }
        }
    }

    @Synchronized
    fun refresh() {
        reassert(force = true)
    }

    private fun evaluateStateLocked() {
        if (isEnabled) {
            reassert()
        } else if (isShowing) {
            isShowing = false
            pendingJob?.cancel()
            hideIdleIsland(context)
        }
    }
}
