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
        const val IDLE_ISLAND_ID = 6666
        private const val PIC_IDLE_DOT = "idle_dot"
        private const val PIC_IDLE_LEFT = "idle_left"
        private const val TAG = "DynamicHyper_Idle"

        @Volatile
        private var instance: IdleIslandManager? = null

        fun getInstance(): IdleIslandManager? = instance

        fun reassertStatic() {
            instance?.reassert()
        }

        fun showIdleIsland(context: Context, width: Int, style: String = "minimal") {
            instance?.reassert() ?: dispatchDirect(context, width, style)
        }

        fun hideIdleIsland(context: Context) {
            try {
                NotificationManagerCompat.from(context).cancel(IDLE_ISLAND_ID)
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding idle island: ${e.message}")
            }
        }

        fun updateIdleIslandWidth(context: Context, width: Int) {
            instance?.reassert() ?: dispatchDirect(context, width, "minimal")
        }

        fun updateStyle(context: Context, style: String) {
            instance?.updateStyleInternal(style) ?: dispatchDirect(context, 10, style)
        }

        fun dispatchDirect(context: Context, width: Int, style: String = "minimal") {
            try {
                val effectiveWidth = if (width > 0) width else 8
                val emptyString = "\u00A0".repeat(effectiveWidth)

                val root = org.json.JSONObject()
                val paramV2 = org.json.JSONObject()
                paramV2.put("protocol", 1)
                paramV2.put("business", "taxi")
                paramV2.put("updatable", true)
                paramV2.put("ticker", "Dynamic Hyper")
                paramV2.put("timeout", 1440)
                // enableFloat: BẮT BUỘC TRUE ĐỂ HYPEROS ATTACH ĐẢO VÀO CAMERA NOTCH!
                paramV2.put("enableFloat", true)
                paramV2.put("isShowNotification", true)
                // islandFirstFloat: BẮT BUỘC TRUE ĐỂ HYPEROS SYSTEMUI KHỞI TẠO NEO VỊ TRÍ NỔI BAN ĐẦU!
                paramV2.put("islandFirstFloat", true)
                paramV2.put("reopen", true)

                val paramIsland = org.json.JSONObject()
                paramIsland.put("islandProperty", 1)
                paramIsland.put("islandPriority", 1) // Ưu tiên thấp nhất để tự động nhường 100% cho thông báo
                paramIsland.put("islandTimeout", 86400)
                paramIsland.put("islandOrder", false)
                paramIsland.put("dismissIsland", false)
                paramIsland.put("maxSize", false)
                paramIsland.put("needCloseAnimation", false)
                paramIsland.put("expandedTime", 2)

                // Tính toán nội dung tiêu đề và màu sắc
                val weather = WeatherManager.currentData
                val temp = getBatteryTemperature(context)
                val ramGb = getUsedRamGb(context)

                val (widgetTitle, widgetContent, highlightColor) = when (style) {
                    "weather" -> Triple(
                        "${weather.iconEmoji} ${weather.temp}°C • ${weather.condition}",
                        "${weather.city} • Độ ẩm ${weather.humidity}%",
                        "#FFD60A"
                    )
                    "battery_temp" -> Triple(
                        "🔋 ${temp}°C • ${if (temp < 38) "Pin Mát Mẻ" else if (temp <= 42) "Pin Ấm Áp" else "Pin Đang Nóng!"}",
                        "Nhiệt độ pin hệ thống • Tình trạng tốt",
                        if (temp < 38) "#30D158" else if (temp <= 42) "#FF9F0A" else "#FF453A"
                    )
                    "ram_usage" -> Triple(
                        "⚡ RAM Đang Dùng: $ramGb GB",
                        "Đa nhiệm HyperOS mượt mà",
                        "#00F2FE"
                    )
                    "green_dot" -> Triple(
                        "🟢 Trạng Thái Hệ Thống",
                        "Dynamic Hyper đang hoạt động 24/7",
                        "#30D158"
                    )
                    else -> Triple(
                        "Dynamic Hyper",
                        "Đảo Chờ Đang Hoạt Động",
                        "#000000"
                    )
                }

                paramIsland.put("highlightColor", highlightColor)

                // bigIslandArea
                val bigIslandArea = org.json.JSONObject()
                val leftInfo = org.json.JSONObject()
                leftInfo.put("type", 1)
                val leftPic = org.json.JSONObject()
                leftPic.put("type", 1)
                leftPic.put("pic", "miui.focus.pic_$PIC_IDLE_LEFT")
                leftInfo.put("picInfo", leftPic)
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

                val iconTextInfo = org.json.JSONObject()
                iconTextInfo.put("title", widgetTitle)
                iconTextInfo.put("content", widgetContent)
                paramV2.put("iconTextInfo", iconTextInfo)

                root.put("param_v2", paramV2)
                root.put("isShowNotification", true)

                val dotBitmap = renderIdleDotBitmap(context, style)
                val leftBitmap = renderBigWidgetIcon(context, style)

                val pics = mapOf(
                    PIC_IDLE_DOT to dotBitmap,
                    PIC_IDLE_LEFT to leftBitmap,
                    "hidden_pixel" to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                )

                val contentIntent = android.app.PendingIntent.getActivity(
                    context,
                    IDLE_ISLAND_ID,
                    android.content.Intent(context, com.ltm.dynamichyper.MainActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val notification = FocusProtocolHelper.buildFocusNotification(
                    context = context,
                    title = widgetTitle,
                    content = widgetContent,
                    smallIconRes = R.drawable.ic_launcher_foreground,
                    jsonParam = root.toString(),
                    pics = pics,
                    isOngoing = true,
                    onlyAlertOnce = false,
                    priority = androidx.core.app.NotificationCompat.PRIORITY_HIGH,
                    contentIntent = contentIntent
                )

                NotificationManagerCompat.from(context).notify(IDLE_ISLAND_ID, notification)
                com.ltm.dynamichyper.core.AppLogger.i(TAG, "Đã gửi thông báo Đảo Chờ (style=$style, width=$effectiveWidth, pri=HIGH, enableFloat=true)")
            } catch (e: Exception) {
                Log.e(TAG, "Error direct dispatch: ${e.message}")
                com.ltm.dynamichyper.core.AppLogger.e(TAG, "Lỗi gửi thông báo Đảo Chờ: ${e.message}", e)
            }
        }

        /**
         * Vẽ bitmap chuẩn slot viên thuốc của Xiaomi SystemUI.
         * Nền Dark Capsule #E6000000 bo tròn có viền kính mờ để hiển thị sắc nét trên cả Theme Sáng lẫn Theme Tối.
         */
        private fun renderIdleDotBitmap(context: Context, style: String): Bitmap {
            val bmpWidth = 100
            val bmpHeight = 72
            val bmp = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Vẽ nền viên thuốc Dark Capsule bo tròn tương phản cao
            val capsuleRect = RectF(2f, 2f, bmpWidth - 2f, bmpHeight - 2f)
            val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E6000000") // 90% đen nhung
            }
            canvas.drawRoundRect(capsuleRect, 32f, 32f, capsulePaint)

            val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.style = Paint.Style.STROKE
                strokeWidth = 2f
                color = Color.parseColor("#33FFFFFF") // Viền kính mờ tinh tế
            }
            canvas.drawRoundRect(capsuleRect, 32f, 32f, borderPaint)

            val cx = bmpWidth / 2f
            val cy = bmpHeight / 2f

            when (style) {
                "green_dot" -> {
                    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(
                            cx, cy, 18f,
                            Color.parseColor("#8030D158"),
                            Color.TRANSPARENT,
                            Shader.TileMode.CLAMP
                        )
                    }
                    canvas.drawCircle(cx, cy, 18f, glowPaint)

                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#30D158")
                    }
                    canvas.drawCircle(cx, cy, 8f, dotPaint)
                }
                "battery_temp" -> {
                    val temp = getBatteryTemperature(context)
                    val textColor = when {
                        temp < 38 -> Color.parseColor("#30D158") // Xanh mát
                        temp <= 42 -> Color.parseColor("#FF9F0A") // Ấm
                        else -> Color.parseColor("#FF453A") // Nóng
                    }
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 42f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        color = textColor
                        setShadowLayer(8f, 0f, 0f, textColor)
                    }
                    val y = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText("${temp}°", cx, y, textPaint)
                }
                "ram_usage" -> {
                    val ramText = getUsedRamGb(context)
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 40f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        color = Color.parseColor("#00F2FE")
                        setShadowLayer(8f, 0f, 0f, Color.parseColor("#00F2FE"))
                    }
                    val y = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText(ramText, cx, y, textPaint)
                }
                "weather" -> {
                    val weather = WeatherManager.currentData
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 42f
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                        color = Color.parseColor("#FFD60A")
                        setShadowLayer(8f, 0f, 0f, Color.parseColor("#FFD60A"))
                    }
                    val y = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
                    canvas.drawText("${weather.temp}°", cx, y, textPaint)
                }
                else -> {
                    // "minimal" -> Viên thuốc đen nhung có viền sáng viền camera
                    val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.style = Paint.Style.STROKE
                        strokeWidth = 3f
                        color = Color.parseColor("#4D30D158") // Vòng sáng Neon xanh nhẹ
                    }
                    canvas.drawCircle(cx, cy, 14f, ringPaint)
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
    private var isShowing = false
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
     * Khôi phục Đảo Chờ 24/7 êm ái, có debounce 150ms chống xung đột sự kiện dồn dập
     */
    fun reassert() {
        pendingJob?.cancel()
        pendingJob = scope.launch {
            delay(150L)
            if (prefs.isIdleIslandEnabled.first() && activeEventCount <= 0) {
                val width = prefs.idleIslandWidth.first()
                val style = prefs.idleIslandStyle.first()
                dispatchDirect(context, width, style)
                isShowing = true
                Log.d(TAG, "Idle Island reasserted successfully with style: $style, width: $width")
            }
        }
    }

    @Synchronized
    fun refresh() {
        reassert()
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
