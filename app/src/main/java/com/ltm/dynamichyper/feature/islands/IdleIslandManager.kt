package com.ltm.dynamichyper.feature.islands

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.feature.weather.WeatherManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Quản lý Đảo Chờ 24/7 (Idle Island) cho Dynamic Hyper.
 * Hỗ trợ 2 chế độ hiển thị:
 *  - "compact": Đảo nốt ruồi thu nhỏ ôm sát camera với biểu tượng to rõ.
 *  - "widget": Widget Đảo Động Live bung to sang trọng hiển thị đầy đủ thông tin thời tiết/pin/RAM.
 * Tự động tắt nhường chỗ 100% khi có thông báo mới và tự động khôi phục khi hết thông báo.
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
        private const val TAG = "DynamicHyper_Idle"

        @Volatile
        private var instance: IdleIslandManager? = null

        @Volatile
        private var lastDispatchedWidgetText: String = ""

        fun getInstance(): IdleIslandManager? = instance

        fun reassertStatic() {
            instance?.reassert()
        }

        fun showIdleIsland(context: Context, width: Int, style: String = "minimal", force: Boolean = true) {
            instance?.reassert(force = force) ?: dispatchDirect(context, width, style, force = force)
        }

        fun hideIdleIsland(context: Context) {
            try {
                com.ltm.dynamichyper.core.ShizukuEngine.clearPostedActiveId(IDLE_ISLAND_ID)
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
                // Kiểm tra xem có ứng dụng hệ thống nào đang phát native island không (Yield Check)
                if (com.ltm.dynamichyper.core.NativeIslandTracker.hasFresh()) {
                    val remaining = com.ltm.dynamichyper.core.NativeIslandTracker.remainingYieldMs()
                    Log.d(TAG, "Tạm hoãn Đảo Chờ: Đang nhường chỗ cho native island hệ thống (${remaining}ms)")
                    return
                }

                val effectiveWidth = if (width > 0) width else 8
                val emptyString = "\u00A0".repeat(effectiveWidth)

                val root = org.json.JSONObject()
                val paramV2 = org.json.JSONObject()
                val protocolVer = FocusProtocolHelper.getFocusProtocolVersion(context)
                paramV2.put("protocol", protocolVer)
                paramV2.put("business", "permanent_island")
                paramV2.put("updatable", true)
                paramV2.put("ticker", "Permanent Island")
                paramV2.put("timeout", 86400000)
                // enableFloat: false để không bung heads-up banner
                paramV2.put("enableFloat", false)
                // isShowNotification: true bắt buộc để SystemUI chấp nhận hiển thị trên status bar
                paramV2.put("isShowNotification", true)
                // islandFirstFloat: false
                paramV2.put("islandFirstFloat", false)
                // reopen: true chuẩn HyperBridge để đảo luôn được vẽ lại tại nốt ruồi
                paramV2.put("reopen", true)

                val paramIsland = org.json.JSONObject()
                paramIsland.put("islandProperty", 1)
                paramIsland.put("islandPriority", 2)
                paramIsland.put("islandTimeout", 86400000)
                paramIsland.put("islandOrder", false)
                paramIsland.put("dismissIsland", false)
                paramIsland.put("maxSize", false)
                paramIsland.put("needCloseAnimation", true)
                paramIsland.put("expandedTime", 0)

                // Tính toán nội dung gọn gàng và màu sắc (TUYỆT ĐỐI KHÔNG dùng chữ "🟢 Trạng Thái Hệ Thống" gây tràn lan)
                val weather = WeatherManager.currentData
                val temp = getBatteryTemperature(context)
                val ramGb = getUsedRamGb(context)

                val (notifTitle, notifContent, highlightColor) = when (style) {
                    "weather" -> Triple(
                        "${weather.temp}°C • ${weather.condition}",
                        "${weather.city} • Độ ẩm ${weather.humidity}% • Pin ${temp}°C",
                        "#FFD60A"
                    )
                    "battery_temp" -> Triple(
                        "Pin ${temp}°C • ${if (temp < 38) "Mát mẻ" else if (temp <= 42) "Ấm" else "Nóng"}",
                        "RAM $ramGb GB • Thời tiết ${weather.temp}°C",
                        if (temp < 38) "#30D158" else if (temp <= 42) "#FF9F0A" else "#FF453A"
                    )
                    "ram_usage" -> Triple(
                        "RAM $ramGb GB • Đa nhiệm mượt",
                        "Pin ${temp}°C • Thời tiết ${weather.temp}°C",
                        "#00F2FE"
                    )
                    "green_dot" -> Triple(
                        "Dynamic Hyper • Đảo Chờ",
                        "Pin ${temp}°C • RAM $ramGb GB • ${weather.temp}°C",
                        "#30D158"
                    )
                    else -> Triple(
                        "Dynamic Hyper • Đảo Chờ",
                        "Pin ${temp}°C • RAM $ramGb GB • ${weather.temp}°C",
                        "#FFFFFF"
                    )
                }

                paramIsland.put("highlightColor", highlightColor)

                // bigIslandArea: Giữ viên thuốc thu gọn ôm sát nốt ruồi camera
                val bigIslandArea = org.json.JSONObject()
                val leftInfo = org.json.JSONObject()
                leftInfo.put("type", 1)
                val leftText = org.json.JSONObject()
                leftText.put("title", emptyString)
                leftText.put("content", emptyString)
                leftText.put("showHighlightColor", false)
                leftInfo.put("textInfo", leftText)
                bigIslandArea.put("imageTextInfoLeft", leftInfo)
                paramIsland.put("bigIslandArea", bigIslandArea)

                // smallIslandArea: Hiển thị viên thuốc camera nằm im quanh nốt ruồi
                val smallIslandArea = org.json.JSONObject()
                val smallPic = org.json.JSONObject()
                smallPic.put("type", 1)
                smallPic.put("pic", "miui.focus.pic_$PIC_IDLE_DOT")
                smallIslandArea.put("picInfo", smallPic)
                paramIsland.put("smallIslandArea", smallIslandArea)

                paramV2.put("param_island", paramIsland)

                // baseInfo + iconTextInfo (areaAModule): Bắt buộc phải có để FocusPlugin dựng islandExpandedView != null
                // giúp người dùng chạm vào Đảo Chờ sẽ bung ra thẻ xem nhanh thông số máy (Pin, RAM, Thời tiết).
                val baseInfo = org.json.JSONObject()
                baseInfo.put("type", 2)
                baseInfo.put("title", notifTitle)
                baseInfo.put("content", notifContent)
                paramV2.put("baseInfo", baseInfo)

                val iconTextInfo = org.json.JSONObject()
                val animIconInfo = org.json.JSONObject()
                animIconInfo.put("type", 1)
                animIconInfo.put("src", "miui.focus.pic_$PIC_IDLE_LEFT")
                iconTextInfo.put("animIconInfo", animIconInfo)
                iconTextInfo.put("title", notifTitle)
                iconTextInfo.put("content", notifContent)
                paramV2.put("iconTextInfo", iconTextInfo)

                root.put("param_v2", paramV2)
                root.put("isShowNotification", true)

                val dotBitmap = renderIdleDotBitmap(context, style)
                val leftBitmap = renderBigWidgetIcon(context, style)
                val pics = mapOf(
                    PIC_IDLE_DOT to dotBitmap,
                    PIC_IDLE_LEFT to leftBitmap
                )

                val contentIntent = android.app.PendingIntent.getActivity(
                    context,
                    IDLE_ISLAND_ID,
                    android.content.Intent(context, com.ltm.dynamichyper.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val currentText = "$style|$notifTitle|$notifContent|$effectiveWidth"
                if (!force && lastDispatchedWidgetText == currentText && instance?.isShowing == true) {
                    return
                }
                lastDispatchedWidgetText = currentText

                if (force) {
                    com.ltm.dynamichyper.core.ShizukuEngine.clearPostedActiveId(IDLE_ISLAND_ID)
                }

                val notification = FocusProtocolHelper.buildFocusNotification(
                    context = context,
                    title = notifTitle,
                    content = notifContent,
                    smallIconRes = R.drawable.ic_launcher_foreground,
                    jsonParam = root.toString(),
                    pics = pics,
                    isOngoing = true,
                    onlyAlertOnce = true,
                    isSilent = true,
                    priority = androidx.core.app.NotificationCompat.PRIORITY_HIGH,
                    contentIntent = contentIntent,
                    enablePromotedLiveUpdate = false // Tắt PromotedOngoing trên Đảo Chờ để không hiện chip chữ tràn lan
                )

                FocusProtocolHelper.postNotificationSafely(context, IDLE_ISLAND_ID, notification)
                com.ltm.dynamichyper.core.AppLogger.i(TAG, "Đã gửi thông báo Đảo Chờ (style=$style, width=$effectiveWidth, clean=true)")
            } catch (e: Exception) {
                Log.e(TAG, "Error direct dispatch: ${e.message}")
                com.ltm.dynamichyper.core.AppLogger.e(TAG, "Lỗi gửi thông báo Đảo Chờ: ${e.message}", e)
            }
        }

        /**
         * Vẽ bitmap 96x96 cho slot smallIslandArea của Xiaomi SystemUI.
         * QUAN TRỌNG: Nền phải TRONG SUỐT 100% (Color.TRANSPARENT) vì SystemUI đã tự vẽ sẵn viên thuốc
         * màu đen tuyền ôm quanh camera. Tuyệt đối không vẽ khung hộp đè lên để tránh hiệu ứng "viên thuốc lồng viên thuốc".
         */
        private fun renderIdleDotBitmap(context: Context, style: String): Bitmap {
            val bmpSize = 96
            val bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)
            if (style == "minimal") {
                // Giống HyperBridge: Bitmap trong suốt hoàn toàn để SystemUI vẽ viên thuốc đen ôm sát nốt ruồi tự nhiên
                return bmp
            }
            val canvas = Canvas(bmp)
            val cx = bmpSize / 2f
            val cy = bmpSize / 2f

            when (style) {
                "green_dot" -> {
                    // Chấm tròn chỉ báo phong cách iOS Camera/Mic Indicator: nhỏ gọn, sắc nét trên nền trong suốt
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            cx, cy, 18f,
                            Color.parseColor("#5530D158"),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawCircle(cx, cy, 18f, glowPaint)

                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#30D158")
                    }
                    canvas.drawCircle(cx, cy, 9f, dotPaint)
                }
                "battery_temp" -> {
                    val temp = getBatteryTemperature(context)
                    val textColor = when {
                        temp < 38 -> Color.parseColor("#30D158")
                        temp <= 42 -> Color.parseColor("#FF9F0A")
                        else -> Color.parseColor("#FF453A")
                    }
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 38f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        color = textColor
                    }
                    val y = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText("${temp}°", cx, y, textPaint)
                }
                "ram_usage" -> {
                    val ramText = getUsedRamGb(context)
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 36f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        color = Color.parseColor("#00F2FE")
                    }
                    val y = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText(ramText, cx, y, textPaint)
                }
                "weather" -> {
                    val weather = WeatherManager.currentData
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 38f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        color = Color.parseColor("#FFD60A")
                    }
                    val y = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText("${weather.temp}°", cx, y, textPaint)
                }
            }
            return bmp
        }

        /**
         * Vẽ icon 96x96 độ nét cao cho chế độ Widget Đảo Động Live
         */
        private fun renderBigWidgetIcon(context: Context, style: String): Bitmap {
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val center = size / 2f

            when (style) {
                "weather" -> {
                    // Nền tròn vàng cam chuyển sắc
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            0f, 0f, size.toFloat(), size.toFloat(),
                            Color.parseColor("#FF9F0A"),
                            Color.parseColor("#FFD60A"),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawCircle(center, center, 42f, bgPaint)

                    // Mặt trời trung tâm
                    val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                    }
                    canvas.drawCircle(center, center, 22f, sunPaint)
                }
                "battery_temp" -> {
                    val temp = getBatteryTemperature(context)
                    val color = if (temp < 38) Color.parseColor("#30D158") else Color.parseColor("#FF9F0A")
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color
                    }
                    canvas.drawCircle(center, center, 42f, bgPaint)

                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 36f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        this.color = Color.WHITE
                    }
                    val y = center - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText("${temp}°", center, y, textPaint)
                }
                "ram_usage" -> {
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = LinearGradient(
                            0f, 0f, size.toFloat(), size.toFloat(),
                            Color.parseColor("#007AFF"),
                            Color.parseColor("#00F2FE"),
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawCircle(center, center, 42f, bgPaint)

                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 30f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        this.color = Color.WHITE
                    }
                    val y = center - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText("RAM", center, y, textPaint)
                }
                "green_dot" -> {
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            center, center, 24f,
                            Color.parseColor("#4D30D158"),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawCircle(center, center, 24f, glowPaint)

                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = Color.parseColor("#30D158")
                    }
                    canvas.drawCircle(center, center, 10f, dotPaint)
                }
                else -> {
                    // "minimal" -> Đen thui
                    canvas.drawColor(Color.BLACK)
                }
            }
            return bmp
        }

        private fun getBatteryTemperature(context: Context): Int {
            return try {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
                tempTenths / 10
            } catch (_: Exception) {
                32
            }
        }

        private fun getUsedRamGb(context: Context): String {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                val usedBytes = mi.totalMem - mi.availMem
                val usedGb = usedBytes / (1024f * 1024 * 1024)
                String.format(Locale.US, "%.1f", usedGb)
            } catch (_: Exception) {
                "4.5"
            }
        }
    }

    private var isEnabled = true
    private var islandWidth = 10
    private var islandStyle = "minimal"
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
                    if (isShowing && activeEventCount <= 0 && (style == "battery_temp" || style == "ram_usage" || style == "weather")) {
                        val width = prefs.idleIslandWidth.first()
                        dispatchDirect(context, width, style)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Dynamic refresh error: ${e.message}")
                }
            }
        }

        // Heartbeat 24/7: Định kỳ 15 phút làm mới Đảo Chờ để HyperOS không bao giờ xóa đảo do timeout ngầm
        scope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                try {
                    if (prefs.isIdleIslandEnabled.first() && activeEventCount <= 0) {
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
        activeEventCount = count.coerceAtLeast(0)
        Log.d(TAG, "onEventCountChanged: count=$activeEventCount, isEnabled=$isEnabled")
        if (activeEventCount > 0) {
            // Khi có thông báo mới => Đảo chờ TẮT nhường chỗ hoàn toàn cho thông báo mới bung ra!
            hideIdleIsland(context)
            isShowing = false
            pendingJob?.cancel()
        } else if (isEnabled) {
            // Khi hết thông báo hoặc người dùng đã xem/xóa => Tự động khôi phục Đảo Chờ ngay lập tức!
            reassert()
        }
    }

    fun updateStyleInternal(style: String) {
        islandStyle = style
        pendingJob?.cancel()
        pendingJob = scope.launch {
            val width = prefs.idleIslandWidth.first()
            dispatchDirect(context, width, style)
            isShowing = true
            Log.d(TAG, "Idle Island immediately updated to style: $style")
        }
    }

    /**
     * Khôi phục Đảo Chờ 24/7 êm ái.
     * Chuẩn PermanentIslandManager của HyperBridge (DISPATCH_DELAY_MS = 700L):
     * Khi thông báo trước vừa bị đóng/xóa, phải đợi 700ms để HyperOS hoàn tất animation thu đóng đảo cũ,
     * nếu bắn lại 9999 quá sớm (150ms) HyperOS sẽ nuốt mất hiển thị của 9999!
     */
    fun reassert(force: Boolean = true, delayMs: Long = 700L) {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(delayMs)
            if (prefs.isIdleIslandEnabled.first() && activeEventCount <= 0) {
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

    private fun shouldShow(): Boolean = isEnabled

    private fun evaluateStateLocked() {
        if (shouldShow()) {
            reassert()
        } else {
            if (isShowing) {
                isShowing = false
                pendingJob?.cancel()
                dismissIdleIsland()
            }
        }
    }

    private fun dismissIdleIsland() {
        hideIdleIsland(context)
    }
}
