package com.ltm.dynamichyper.feature.islands

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.BatteryManager
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.feature.weather.WeatherManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Bộ vẽ đồ họa (Bitmap Renderer) và đọc thông số hệ thống cho Đảo Chờ 24/7.
 * Toàn bộ bitmap đều dùng nền trong suốt (ARGB_8888) theo phong cách iOS Liquid Glass,
 * tuyệt đối không vẽ nền vuông đen đặc.
 */
object IdleIslandRenderer {

    @Volatile
    private var cachedTopRamText: String? = null

    @Volatile
    private var lastTopRamFetchMs: Long = 0L

    /**
     * Vẽ icon 96x96 cho vùng bên trái Đảo Chờ (smallIslandArea và bigIslandArea.imageTextInfoLeft).
     */
    fun renderIdleDotBitmap(context: Context, style: String): Bitmap {
        val bmpSize = 96
        val bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)
        if (style == "minimal") return bmp

        val canvas = Canvas(bmp)
        val cx = bmpSize / 2f
        val cy = bmpSize / 2f

        when (style) {
            "green_dot" -> {
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        cx, cy, 18f,
                        Color.parseColor("#5530D158"),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(cx, cy, 18f, glowPaint)
                canvas.drawCircle(cx, cy, 9f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#30D158")
                })
            }
            "weather" -> {
                drawWeatherVector(canvas, cx, cy, 1.0f, WeatherManager.currentData.weatherCode)
            }
            "ram_usage" -> {
                drawRamChipVector(canvas, cx, cy, 1.0f, Color.parseColor("#00F2FE"))
            }
            "battery_temp" -> {
                val temp = getBatteryTemperature(context)
                val color = batteryTempColor(temp)
                drawThermometerVector(canvas, cx, cy, 1.0f, color)
            }
        }
        return bmp
    }

    /**
     * Vẽ icon 96x96 bo tròn Liquid Glass cho thẻ bung mở rộng (Expanded Card) và largeIcon.
     */
    fun renderBigWidgetIcon(context: Context, style: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f

        when (style) {
            "weather" -> {
                drawLiquidGlassBadge(canvas, center, "#1E3A8A", "#0EA5E9", "#7DD3FC")
                drawWeatherVector(canvas, center, center, 0.85f, WeatherManager.currentData.weatherCode)
            }
            "battery_temp" -> {
                val temp = getBatteryTemperature(context)
                val accent = batteryTempColor(temp)
                val darkStart = when {
                    temp < 38 -> "#064E3B"
                    temp <= 42 -> "#78350F"
                    else -> "#7F1D1D"
                }
                val darkEnd = when {
                    temp < 38 -> "#10B981"
                    temp <= 42 -> "#F59E0B"
                    else -> "#EF4444"
                }
                drawLiquidGlassBadge(canvas, center, darkStart, darkEnd, String.format("#%06X", 0xFFFFFF and accent))
                drawThermometerVector(canvas, center, center, 0.85f, Color.WHITE)
            }
            "ram_usage" -> {
                drawLiquidGlassBadge(canvas, center, "#0F172A", "#0284C7", "#38BDF8")
                drawRamChipVector(canvas, center, center, 0.85f, Color.WHITE)
            }
            "green_dot" -> {
                drawLiquidGlassBadge(canvas, center, "#064E3B", "#163322", "#30D158")
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        center, center, 24f,
                        Color.parseColor("#8030D158"),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(center, center, 24f, glowPaint)
                canvas.drawCircle(center, center, 13f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#30D158")
                })
            }
            else -> {
                drawLiquidGlassBadge(canvas, center, "#1E293B", "#0F172A", "#38BDF8")
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E2E8F0") }
                canvas.drawRoundRect(RectF(center - 20f, center - 8f, center + 20f, center + 8f), 8f, 8f, pillPaint)
                canvas.drawCircle(center + 11f, center, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#38BDF8")
                })
            }
        }
        return bmp
    }

    private fun drawLiquidGlassBadge(
        canvas: Canvas,
        center: Float,
        startHex: String,
        endHex: String,
        borderHex: String
    ) {
        val radius = 42f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, center * 2f, center * 2f,
                Color.parseColor(startHex),
                Color.parseColor(endHex),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(center, center, radius, bgPaint)

        // Specular highlight ở nửa trên tạo cảm giác kính lỏng iOS
        val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                center, center - radius, center, center,
                Color.parseColor("#4DFFFFFF"),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(center, center - 4f, radius - 4f, glossPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.parseColor(borderHex)
            alpha = 170
        }
        canvas.drawCircle(center, center, radius - 1.5f, ringPaint)
    }

    private fun drawWeatherVector(canvas: Canvas, cx: Float, cy: Float, scale: Float, weatherCode: Int) {
        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD60A") }
        val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F1F5F9") }
        val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#38BDF8")
            strokeWidth = 4f * scale
            strokeCap = Paint.Cap.ROUND
        }

        when {
            weatherCode == 0 -> {
                canvas.drawCircle(cx, cy, 16f * scale, sunPaint)
                val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FFD60A")
                    strokeWidth = 3.5f * scale
                    strokeCap = Paint.Cap.ROUND
                }
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val r1 = 21f * scale
                    val r2 = 27f * scale
                    canvas.drawLine(
                        cx + (r1 * kotlin.math.cos(angle)).toFloat(),
                        cy + (r1 * kotlin.math.sin(angle)).toFloat(),
                        cx + (r2 * kotlin.math.cos(angle)).toFloat(),
                        cy + (r2 * kotlin.math.sin(angle)).toFloat(),
                        rayPaint
                    )
                }
            }
            weatherCode in 1..2 -> {
                canvas.drawCircle(cx + 8f * scale, cy - 8f * scale, 12f * scale, sunPaint)
                drawCloudShape(canvas, cx - 3f * scale, cy + 5f * scale, scale * 0.9f, cloudPaint)
            }
            weatherCode in 51..67 || weatherCode in 80..82 -> {
                drawCloudShape(canvas, cx, cy - 5f * scale, scale * 0.9f, cloudPaint)
                canvas.drawLine(cx - 10f * scale, cy + 12f * scale, cx - 13f * scale, cy + 22f * scale, rainPaint)
                canvas.drawLine(cx, cy + 12f * scale, cx - 3f * scale, cy + 22f * scale, rainPaint)
                canvas.drawLine(cx + 10f * scale, cy + 12f * scale, cx + 7f * scale, cy + 22f * scale, rainPaint)
            }
            weatherCode >= 95 -> {
                drawCloudShape(canvas, cx, cy - 6f * scale, scale * 0.9f, cloudPaint)
                val boltPath = Path().apply {
                    moveTo(cx + 2f * scale, cy + 6f * scale)
                    lineTo(cx - 6f * scale, cy + 16f * scale)
                    lineTo(cx, cy + 16f * scale)
                    lineTo(cx - 4f * scale, cy + 26f * scale)
                    lineTo(cx + 8f * scale, cy + 13f * scale)
                    lineTo(cx + 2f * scale, cy + 13f * scale)
                    close()
                }
                canvas.drawPath(boltPath, sunPaint)
            }
            else -> {
                drawCloudShape(canvas, cx, cy + 2f * scale, scale, cloudPaint)
            }
        }
    }

    private fun drawCloudShape(canvas: Canvas, cx: Float, cy: Float, scale: Float, paint: Paint) {
        canvas.drawCircle(cx - 10f * scale, cy + 2f * scale, 10f * scale, paint)
        canvas.drawCircle(cx, cy - 5f * scale, 13f * scale, paint)
        canvas.drawCircle(cx + 11f * scale, cy + 2f * scale, 9f * scale, paint)
        canvas.drawRoundRect(
            RectF(cx - 18f * scale, cy + 1f * scale, cx + 19f * scale, cy + 12f * scale),
            6f * scale, 6f * scale, paint
        )
    }

    private fun drawRamChipVector(canvas: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * scale
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = 16f * scale
        canvas.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), 5f * scale, 5f * scale, strokePaint)
        canvas.drawRoundRect(RectF(cx - 7f * scale, cy - 7f * scale, cx + 7f * scale, cy + 7f * scale), 2f * scale, 2f * scale, fillPaint)
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            strokeWidth = 3f * scale
            strokeCap = Paint.Cap.ROUND
        }
        for (offset in listOf(-8f, 0f, 8f)) {
            val o = offset * scale
            canvas.drawLine(cx + o, cy - r - 6f * scale, cx + o, cy - r, pinPaint)
            canvas.drawLine(cx + o, cy + r, cx + o, cy + r + 6f * scale, pinPaint)
            canvas.drawLine(cx - r - 6f * scale, cy + o, cx - r, cy + o, pinPaint)
            canvas.drawLine(cx + r, cy + o, cx + r + 6f * scale, cy + o, pinPaint)
        }
    }

    private fun drawThermometerVector(canvas: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * scale
            strokeCap = Paint.Cap.ROUND
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(
            RectF(cx - 6f * scale, cy - 22f * scale, cx + 6f * scale, cy + 8f * scale),
            6f * scale, 6f * scale, strokePaint
        )
        canvas.drawCircle(cx, cy + 14f * scale, 10f * scale, fillPaint)
        canvas.drawLine(cx, cy - 10f * scale, cx, cy + 12f * scale, strokePaint)
    }

    private fun batteryTempColor(temp: Int): Int = when {
        temp < 38 -> Color.parseColor("#30D158")
        temp <= 42 -> Color.parseColor("#FF9F0A")
        else -> Color.parseColor("#FF453A")
    }

    fun getBatteryTemperature(context: Context): Int {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val tempTenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
            tempTenths / 10
        } catch (_: Exception) {
            32
        }
    }

    fun getBatteryPowerSummary(context: Context): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3900) ?: 3900
            val volts = if (voltageMv > 100) voltageMv / 1000.0 else voltageMv.toDouble()

            val rawCurrentUa = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
            val currentMa = if (abs(rawCurrentUa) > 25000) abs(rawCurrentUa) / 1000.0 else abs(rawCurrentUa).toDouble()
            val watts = (currentMa / 1000.0) * volts

            val stateLabel = if (isCharging) "Đang sạc" else "Đang tiêu thụ"
            if (watts > 0.1) {
                String.format(Locale.US, "%s %.1fW (%dmA)", stateLabel, watts, currentMa.roundToInt())
            } else {
                "$stateLabel ổn định"
            }
        } catch (_: Exception) {
            "Công suất ổn định"
        }
    }

    fun getUsedRamGb(context: Context): String {
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

    fun getRamDetailSummary(context: Context): String {
        val baseSummary = try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalGb = mi.totalMem / (1024f * 1024 * 1024)
            val availGb = mi.availMem / (1024f * 1024 * 1024)
            val usedGb = totalGb - availGb
            String.format(Locale.US, "Đã dùng %.1f/%.1f GB (Trống %.1f GB)", usedGb, totalGb, availGb)
        } catch (_: Exception) {
            "RAM ổn định"
        }

        val now = System.currentTimeMillis()
        if (cachedTopRamText != null && now - lastTopRamFetchMs < 25_000L) {
            return "$baseSummary • $cachedTopRamText"
        }

        if (ShizukuEngine.hasShizukuPermission()) {
            try {
                val (code, out) = ShizukuEngine.execCommand("dumpsys meminfo -s | grep -E 'K: ' | head -n 12")
                if (code == 0 && out.isNotBlank()) {
                    val pm = context.packageManager
                    val entries = mutableListOf<String>()
                    val regex = Regex("([0-9,]+)K:\\s+([a-zA-Z0-9._]+)")
                    for (line in out.lines()) {
                        val m = regex.find(line) ?: continue
                        val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                        val pkg = m.groupValues[2]
                        if (pkg.startsWith("com.android.systemui") || pkg == "system" || pkg == context.packageName) continue
                        val label = try {
                            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                        } catch (_: Exception) {
                            pkg.substringAfterLast('.')
                        }
                        val mb = (kb / 1024f).roundToInt()
                        if (mb >= 30) {
                            entries.add("$label ${mb}MB")
                        }
                        if (entries.size >= 2) break
                    }
                    if (entries.isNotEmpty()) {
                        cachedTopRamText = "Top: " + entries.joinToString(", ")
                        lastTopRamFetchMs = now
                        return "$baseSummary • $cachedTopRamText"
                    }
                }
            } catch (_: Exception) {}
        }

        return "$baseSummary • Bật Shizuku để xem Top app"
    }
}
