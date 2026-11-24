package com.ltm.dynamichyper.feature.islands

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.BatteryManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quản lý Đảo Sạc Pin & Cáp USB (Charging Island) của Dynamic Hyper
 * Tự động bung to đảo khi cắm sạc hiển thị phần trăm pin, công suất sạc thời gian thực (W)
 * và thời gian ước tính sạc đầy, tự động đổi màu neon theo cấp độ sạc.
 */
class ChargingIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager
) {
    private val TAG = "DynamicHyper:Charging"

    companion object {
        const val CHARGING_NOTIFICATION_ID = 6666
        private const val PIC_CHARGING_LIGHTNING = "charging_lightning_icon"
    }

    private var isChargingActive = false
    private var lastReportedPercent = -1
    private var lastReportedWattageRounded = -1

    enum class ChargingTier(
        val tierName: String,
        val highlightColor: String,
        val startColor: Int,
        val endColor: Int,
        val isMultiBolt: Boolean
    ) {
        HYPER_CHARGE(
            tierName = "Xiaomi HyperCharge",
            highlightColor = "#FF3B30", // Neon Flame Red
            startColor = Color.parseColor("#FF5E3A"),
            endColor = Color.parseColor("#FF2A6D"),
            isMultiBolt = true
        ),
        TURBO_CHARGE(
            tierName = "Mi Turbo Charge",
            highlightColor = "#FF9F0A", // Neon Amber Gold
            startColor = Color.parseColor("#FF9500"),
            endColor = Color.parseColor("#FFB800"),
            isMultiBolt = true
        ),
        FAST_CHARGE(
            tierName = "Sạc Nhanh QC/PD",
            highlightColor = "#30D158", // iOS Electric Mint Green
            startColor = Color.parseColor("#30D158"),
            endColor = Color.parseColor("#34C759"),
            isMultiBolt = false
        ),
        STANDARD_CHARGE(
            tierName = "Sạc Tiêu Chuẩn",
            highlightColor = "#34C759", // Apple Emerald Green
            startColor = Color.parseColor("#28CD41"),
            endColor = Color.parseColor("#00C7BE"),
            isMultiBolt = false
        )
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    scope.launch {
                        if (prefs.isChargingIslandEnabled.first()) {
                            showChargingIsland(getBatteryPercentage(), intent)
                        }
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    hideChargingIsland()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    if (isCharging) {
                        scope.launch {
                            if (prefs.isChargingIslandEnabled.first()) {
                                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                                val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else getBatteryPercentage()
                                showChargingIsland(percent, intent)
                            }
                        }
                    } else if (isChargingActive) {
                        hideChargingIsland()
                    }
                }
            }
        }
    }

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            context.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Register battery receiver failed: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        hideChargingIsland()
    }

    private fun getBatteryPercentage(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80
    }

    /**
     * Đo lường công suất sạc tức thời (Watt)
     */
    private fun getChargingWattage(intent: Intent?): Double {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        var currentAmps = 0.0
        var voltageVolts = 0.0

        // 1. Thử lấy dòng điện tức thời (µA hoặc mA) từ BatteryManager
        val currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val absCurrent = abs(currentNow)
        if (absCurrent > 0) {
            currentAmps = if (absCurrent > 10000) {
                absCurrent / 1_000_000.0 // µA -> A
            } else {
                absCurrent / 1_000.0 // mA -> A
            }
        }

        // 2. Lấy điện áp (mV)
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (voltageMv > 0) {
            voltageVolts = voltageMv / 1_000.0
        }

        var watts = currentAmps * voltageVolts

        // 3. Fallback: Nếu watts <= 0.5W hoặc currentNow không đọc được,
        // thử lấy max_charging_current & max_charging_voltage
        if (watts <= 0.5) {
            val maxCurrent = intent?.getIntExtra("max_charging_current", -1) ?: -1
            val maxVoltage = intent?.getIntExtra("max_charging_voltage", -1) ?: -1
            if (maxCurrent > 0 && maxVoltage > 0) {
                val maxAmps = if (maxCurrent > 10000) maxCurrent / 1_000_000.0 else maxCurrent / 1_000.0
                val maxVolts = if (maxVoltage > 100000) maxVoltage / 1_000_000.0 else maxVoltage / 1_000.0
                watts = maxAmps * maxVolts
            }
        }

        // 4. Fallback mặc định theo nguồn cấp cắm
        if (watts <= 0.5) {
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            watts = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> 25.0
                BatteryManager.BATTERY_PLUGGED_USB -> 5.0
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> 15.0
                else -> 18.0
            }
        }

        return watts
    }

    /**
     * Ước tính thời gian sạc đầy pin
     */
    private fun getTimeToFull(percent: Int, watts: Double): String {
        if (percent >= 100) return "Đã đầy pin"

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        var remMs: Long = -1L
        try {
            remMs = bm?.computeChargeTimeRemaining() ?: -1L
        } catch (_: Exception) {}

        val minutes: Int
        if (remMs > 0) {
            minutes = (remMs / 60000L).toInt()
        } else {
            // Redmi K80 có dung lượng pin ~6550 mAh, xấp xỉ ~25.5 Wh
            val remainingRatio = (100 - percent).coerceIn(1, 100) / 100.0
            val remainingWh = 25.5 * remainingRatio
            val effectiveWatts = watts.coerceAtLeast(4.0) * 0.85
            val hours = remainingWh / effectiveWatts
            minutes = (hours * 60).roundToInt().coerceIn(1, 360)
        }

        return if (minutes < 60) {
            "~${minutes}p"
        } else {
            val h = minutes / 60
            val m = minutes % 60
            if (m > 0) "~${h}h ${m}p" else "~${h}h"
        }
    }

    private fun determineTier(watts: Double): ChargingTier {
        return when {
            watts >= 65.0 -> ChargingTier.HYPER_CHARGE
            watts >= 30.0 -> ChargingTier.TURBO_CHARGE
            watts >= 15.0 -> ChargingTier.FAST_CHARGE
            else -> ChargingTier.STANDARD_CHARGE
        }
    }

    private suspend fun showChargingIsland(percent: Int, intent: Intent? = null) {
        val showWattage = prefs.isChargingWattageEnabled.first()
        val showTimeToFull = prefs.isChargingTimeToFullEnabled.first()

        val watts = getChargingWattage(intent)
        val tier = determineTier(watts)
        val timeStr = getTimeToFull(percent, watts)

        val wattRounded = watts.roundToInt()
        // Tránh flood notification nếu phần trăm và công suất không đổi
        if (isChargingActive && percent == lastReportedPercent && wattRounded == lastReportedWattageRounded) {
            return
        }

        isChargingActive = true
        lastReportedPercent = percent
        lastReportedWattageRounded = wattRounded

        val wattStr = if (watts >= 10.0) "${wattRounded}W" else "%.1fW".format(Locale.US, watts)

        // Tiêu đề đảo: Đang sạc 78% • 33W
        val titleText = if (showWattage) {
            "Đang sạc $percent% • $wattStr"
        } else {
            "Đang sạc $percent%"
        }

        // Nội dung phụ: Còn ~25p đầy • Mi Turbo Charge
        val contentText = if (showTimeToFull && percent < 100) {
            "Còn $timeStr đầy • ${tier.tierName}"
        } else {
            "Nguồn điện kết nối • ${tier.tierName}"
        }

        val root = JSONObject()
        val paramV2 = JSONObject()
        paramV2.put("protocol", 1)
        paramV2.put("business", "taxi")
        paramV2.put("updatable", true)
        paramV2.put("ticker", titleText)
        paramV2.put("timeout", 1440)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", true)
        paramV2.put("islandFirstFloat", true)
        paramV2.put("reopen", true)

        val paramIsland = JSONObject()
        paramIsland.put("islandProperty", 1)
        paramIsland.put("islandPriority", 3)
        paramIsland.put("islandTimeout", 86400) // 24 giờ (đơn vị giây)
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", false)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        paramIsland.put("expandedTime", 4)
        paramIsland.put("highlightColor", tier.highlightColor)

        // bigIslandArea: Left = Glowing Tier Lightning Badge, Right = Text Info
        val bigIslandArea = JSONObject()
        val leftInfo = JSONObject()
        leftInfo.put("type", 1)
        val leftPic = JSONObject()
        leftPic.put("type", 1)
        leftPic.put("pic", "miui.focus.pic_$PIC_CHARGING_LIGHTNING")
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
        rightText.put("title", titleText)
        rightText.put("content", contentText)
        rightText.put("showHighlightColor", true)
        rightInfo.put("textInfo", rightText)
        bigIslandArea.put("imageTextInfoRight", rightInfo)

        paramIsland.put("bigIslandArea", bigIslandArea)

        // smallIslandArea: Icon tia sét neon bóng mờ trên đảo nhỏ
        val smallIslandArea = JSONObject()
        val smallPic = JSONObject()
        smallPic.put("type", 1)
        smallPic.put("pic", "miui.focus.pic_$PIC_CHARGING_LIGHTNING")
        smallIslandArea.put("picInfo", smallPic)
        paramIsland.put("smallIslandArea", smallIslandArea)

        paramV2.put("param_island", paramIsland)

        val iconTextInfo = JSONObject()
        iconTextInfo.put("title", titleText)
        iconTextInfo.put("content", contentText)
        paramV2.put("iconTextInfo", iconTextInfo)

        root.put("param_v2", paramV2)
        root.put("isShowNotification", true)

        val jsonParam = root.toString()

        val lightningBmp = createLiquidGlassLightningBitmap(tier)
        val pics = mapOf(
            PIC_CHARGING_LIGHTNING to lightningBmp,
            "hidden_pixel" to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        )

        val notif = FocusProtocolHelper.buildFocusNotification(
            context = context,
            title = titleText,
            content = contentText,
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = jsonParam,
            pics = pics,
            isOngoing = true,
            onlyAlertOnce = false
        )

        FocusProtocolHelper.postNotificationSafely(context, CHARGING_NOTIFICATION_ID, notif)
    }

    private fun hideChargingIsland() {
        isChargingActive = false
        lastReportedPercent = -1
        lastReportedWattageRounded = -1
        try {
            NotificationManagerCompat.from(context).cancel(CHARGING_NOTIFICATION_ID)
        } catch (_: Exception) {}
        // Tự động khôi phục Đảo Chờ ngay sau khi rút sạc
        IdleIslandManager.reassertStatic()
    }

    /**
     * Tạo biểu tượng sạc hiệu ứng iOS Liquid Glass cao cấp:
     * - Vòng hào quang phát sáng neon tinh tế
     * - Thể tích hình cầu gradient bóng mờ theo cấp độ sạc
     * - Điểm phản chiếu ánh sáng trắng (Specular gloss)
     * - Tia sét vector sắc nét (đơn hoặc kép theo Tier)
     */
    private fun createLiquidGlassLightningBitmap(tier: ChargingTier): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = size / 2f
        val radius = size * 0.44f

        // 1. Vòng hào quang phát sáng ngoài (Ambient Glow Ring)
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tier.startColor
            alpha = 60
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(center, center, radius + 4f, glowPaint)

        // 2. Nền cầu bóng kính Gradient (Liquid Glass Disc)
        val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                size * 0.15f, size * 0.15f,
                size * 0.85f, size * 0.85f,
                tier.startColor,
                tier.endColor,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, gradientPaint)

        // 3. Lớp phản chiếu ánh sáng mặt kính cong (Glass Specular Highlight)
        val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                center, size * 0.1f,
                center, size * 0.5f,
                Color.argb(130, 255, 255, 255),
                Color.argb(0, 255, 255, 255),
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawOval(
            size * 0.22f, size * 0.12f,
            size * 0.78f, size * 0.44f,
            highlightPaint
        )

        // 4. Vẽ tia sét sắc nét (Vector Lightning Bolt)
        val whiteBoltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        if (tier.isMultiBolt) {
            // Tia sét kép cho chế độ Turbo và HyperCharge
            val bolt1 = Path().apply {
                moveTo(size * 0.44f, size * 0.24f)
                lineTo(size * 0.26f, size * 0.52f)
                lineTo(size * 0.40f, size * 0.52f)
                lineTo(size * 0.36f, size * 0.78f)
                lineTo(size * 0.58f, size * 0.46f)
                lineTo(size * 0.44f, size * 0.46f)
                close()
            }
            val bolt2 = Path().apply {
                moveTo(size * 0.64f, size * 0.24f)
                lineTo(size * 0.46f, size * 0.52f)
                lineTo(size * 0.60f, size * 0.52f)
                lineTo(size * 0.56f, size * 0.78f)
                lineTo(size * 0.78f, size * 0.46f)
                lineTo(size * 0.64f, size * 0.46f)
                close()
            }
            canvas.drawPath(bolt1, whiteBoltPaint)
            canvas.drawPath(bolt2, whiteBoltPaint)
        } else {
            // Tia sét đơn thanh lịch cho sạc thường & sạc nhanh
            val bolt = Path().apply {
                moveTo(size * 0.55f, size * 0.20f)
                lineTo(size * 0.33f, size * 0.52f)
                lineTo(size * 0.49f, size * 0.52f)
                lineTo(size * 0.45f, size * 0.80f)
                lineTo(size * 0.71f, size * 0.46f)
                lineTo(size * 0.54f, size * 0.46f)
                close()
            }
            canvas.drawPath(bolt, whiteBoltPaint)
        }

        return bitmap
    }
}
