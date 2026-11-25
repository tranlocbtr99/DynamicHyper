package com.ltm.dynamichyper.feature.islands

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.os.BatteryManager
import com.ltm.dynamichyper.feature.weather.WeatherManager
import java.util.Locale

/**
 * Bộ vẽ đồ họa (Bitmap Renderer) và đọc thông số hệ thống cho Đảo Chờ 24/7.
 * Mọi bitmap đều dùng nền trong suốt (ARGB_8888) với hình tròn/viên thuốc bo mượt,
 * tuyệt đối không vẽ nền vuông đen đặc.
 */
object IdleIslandRenderer {

    /**
     * Vẽ bitmap 96x96 cho slot smallIslandArea (viên thuốc quanh nốt ruồi camera).
     * Nền trong suốt 100% để hòa vào viên thuốc đen nguyên bản của Xiaomi SystemUI.
     */
    fun renderIdleDotBitmap(context: Context, style: String): Bitmap {
        val bmpSize = 96
        val bmp = Bitmap.createBitmap(bmpSize, bmpSize, Bitmap.Config.ARGB_8888)
        if (style == "minimal") {
            return bmp
        }
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
                drawCenteredText(canvas, "${temp}°", cx, cy, 38f, textColor)
            }
            "ram_usage" -> {
                val ramText = getUsedRamGb(context)
                drawCenteredText(canvas, ramText, cx, cy, 36f, Color.parseColor("#00F2FE"))
            }
            "weather" -> {
                val weather = WeatherManager.currentData
                drawCenteredText(canvas, "${weather.temp}°", cx, cy, 38f, Color.parseColor("#FFD60A"))
            }
        }
        return bmp
    }

    /**
     * Vẽ icon 96x96 bo tròn sắc nét cho thẻ bung mở rộng (Expanded Card) khi người dùng chạm vào Đảo Chờ.
     */
    fun renderBigWidgetIcon(context: Context, style: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f

        when (style) {
            "weather" -> {
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, size.toFloat(), size.toFloat(),
                        Color.parseColor("#FF9F0A"),
                        Color.parseColor("#FFD60A"),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(center, center, 42f, bgPaint)
                val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                canvas.drawCircle(center, center, 20f, sunPaint)
            }
            "battery_temp" -> {
                val temp = getBatteryTemperature(context)
                val bgColor = if (temp < 38) Color.parseColor("#30D158") else Color.parseColor("#FF9F0A")
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
                canvas.drawCircle(center, center, 42f, bgPaint)
                drawCenteredText(canvas, "${temp}°", center, center, 34f, Color.WHITE)
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
                drawCenteredText(canvas, "RAM", center, center, 28f, Color.WHITE)
            }
            "green_dot" -> {
                // Huy hiệu tròn xanh ngọc tối có chấm sáng iOS ở giữa (không dùng nền vuông đen)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#163322")
                }
                canvas.drawCircle(center, center, 42f, bgPaint)

                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.parseColor("#6630D158")
                }
                canvas.drawCircle(center, center, 40f, ringPaint)

                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        center, center, 24f,
                        Color.parseColor("#8030D158"),
                        Color.TRANSPARENT,
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(center, center, 24f, glowPaint)

                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#30D158")
                }
                canvas.drawCircle(center, center, 13f, dotPaint)
            }
            else -> {
                // "minimal" -> Huy hiệu tròn Liquid Glass tinh tế (tuyệt đối không vẽ ô vuông đen)
                val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, 0f, size.toFloat(), size.toFloat(),
                        Color.parseColor("#1E293B"),
                        Color.parseColor("#0F172A"),
                        Shader.TileMode.CLAMP
                    )
                }
                canvas.drawCircle(center, center, 42f, bgPaint)

                val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.parseColor("#4D38BDF8")
                }
                canvas.drawCircle(center, center, 40f, ringPaint)

                // Biểu tượng viên thuốc Dynamic Island nhỏ gọn màu trắng ngọc bên trong
                val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#E2E8F0")
                }
                val pillRect = RectF(center - 20f, center - 8f, center + 20f, center + 8f)
                canvas.drawRoundRect(pillRect, 8f, 8f, pillPaint)

                val dotInPill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#38BDF8")
                }
                canvas.drawCircle(center + 11f, center, 4f, dotInPill)
            }
        }
        return bmp
    }

    private fun drawCenteredText(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            this.color = color
        }
        val y = cy - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(text, cx, y, paint)
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
}
