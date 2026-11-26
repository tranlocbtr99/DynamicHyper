package com.ltm.dynamichyper.feature.islands

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
 * Tự động tắt nhường chỗ 100% khi có thông báo mới / native island hệ thống
 * và tự động khôi phục sau 700ms khi hết thông báo.
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
        private const val REASSERT_DELAY_MS = 700L
        private const val TAG = "DynamicHyper_Idle"

        @Volatile
        private var instance: IdleIslandManager? = null

        @Volatile
        private var lastDispatchedWidgetText: String = ""

        private val activeBlockers = ConcurrentHashMap.newKeySet<String>()

        fun getInstance(): IdleIslandManager? = instance

        fun setBlockerStatic(tag: String, active: Boolean) {
            if (active) activeBlockers.add(tag) else activeBlockers.remove(tag)
            instance?.onBlockersChanged()
        }

        fun shouldSuppressIdle(): Boolean = activeBlockers.isNotEmpty() || NativeIslandTracker.hasFresh()

        fun reassertStatic() {
            instance?.reassert()
        }

        fun showIdleIsland(context: Context, width: Int, style: String = "minimal", force: Boolean = true) {
            instance?.reassert(force = force) ?: dispatchDirect(context, width, style, force = force)
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
            instance?.reassert() ?: dispatchDirect(context, width, "minimal")
        }

        fun updateStyle(context: Context, style: String) {
            instance?.updateStyleInternal(style) ?: dispatchDirect(context, 10, style, force = true)
        }

        fun resetCache() {
            lastDispatchedWidgetText = ""
            instance?.isShowing = false
        }

        fun dispatchDirect(context: Context, width: Int, style: String = "minimal", force: Boolean = false) {
            try {
                if (shouldSuppressIdle()) {
                    val remaining = NativeIslandTracker.remainingYieldMs()
                    Log.d(TAG, "Tạm hoãn Đảo Chờ: blockers=$activeBlockers, nativeYield=${remaining}ms")
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
                    highlightColor = highlightColor
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

                val isLocked = FocusProtocolHelper.isKeyguardLocked(context)
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
                    keepIslandOnLockscreen = true
                )

                FocusProtocolHelper.postNotificationSafely(
                    context = context,
                    id = IDLE_ISLAND_ID,
                    notification = notification,
                    shouldCancel = { shouldSuppressIdle() }
                )
                AppLogger.i(TAG, "Đã gửi thông báo Đảo Chờ (style=$style, width=$effectiveWidth, clean=true)")
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
            highlightColor: String
        ): String {
            val leftInfo = JSONObject().apply {
                put("type", 1)
                // Khi là minimal thì không gắn icon ở bigIslandArea để viên thuốc đen tuyền tối giản.
                // Với green_dot, weather, battery_temp, ram_usage: gắn icon ở bên trái camera để luôn hiện rõ!
                if (style != "minimal") {
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_$PIC_IDLE_DOT")
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
                put("islandProperty", 1)
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
                put("ticker", "Permanent Island")
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
    private var activeEventCount = 0
    private var pendingJob: Job? = null

    init {
        instance = this

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

        // Cập nhật động nhiệt độ pin / RAM / Thời tiết mỗi 30 giây nếu người dùng chọn style động
        scope.launch {
            while (true) {
                delay(30 * 1000L)
                try {
                    val style = prefs.idleIslandStyle.first()
                    if (isShowing && !shouldSuppressIdle() && (style == "battery_temp" || style == "ram_usage" || style == "weather")) {
                        val width = prefs.idleIslandWidth.first()
                        dispatchDirect(context, width, style)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dynamic refresh error: ${e.message}")
                }
            }
        }

        // Heartbeat 24/7: Định kỳ 15 phút làm mới Đảo Chờ để HyperOS không xóa đảo do timeout ngầm
        scope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                try {
                    if (prefs.isIdleIslandEnabled.first() && !shouldSuppressIdle()) {
                        val width = prefs.idleIslandWidth.first()
                        val style = prefs.idleIslandStyle.first()
                        dispatchDirect(context, width, style)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Heartbeat refresh error: ${e.message}")
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
        val suppressed = shouldSuppressIdle()
        Log.d(TAG, "onBlockersChanged: blockers=$activeBlockers, suppressed=$suppressed, isEnabled=$isEnabled")
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
        if (shouldSuppressIdle()) return
        pendingJob = scope.launch {
            val width = prefs.idleIslandWidth.first()
            dispatchDirect(context, width, style, force = true)
            isShowing = !shouldSuppressIdle()
            Log.d(TAG, "Idle Island immediately updated to style: $style")
        }
    }

    fun reassert(force: Boolean = true, delayMs: Long = REASSERT_DELAY_MS) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            if (prefs.isIdleIslandEnabled.first() && !shouldSuppressIdle()) {
                val width = prefs.idleIslandWidth.first()
                val style = prefs.idleIslandStyle.first()
                dispatchDirect(context, width, style, force = force)
                isShowing = true
                Log.d(TAG, "Idle Island reasserted successfully with style: $style, width: $width (delay=${delayMs}ms)")
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
