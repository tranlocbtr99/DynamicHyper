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
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Quản lý Đảo Sạc Pin & Cáp USB (Charging Island) của Dynamic Hyper.
 * Áp dụng cơ chế `collapseToAppropriateState()` từ Dynamic Notch Bar:
 * - Khi vừa cắm sạc (`ACTION_POWER_CONNECTED`) hoặc khi pin đầy 100%, bung Đảo Sạc hiển thị
 *   phần trăm pin, công suất sạc (W) và thời gian ước tính sạc đầy trong đúng 6 giây (`AUTO_COLLAPSE_MS = 6000L`),
 *   sau đó tự động ẩn Đảo Sạc (`id=6666`) và nhả `blockers=[charging]` để trả lại Đảo Chờ 24/7 / Đảo Thông báo.
 * - Tuyệt đối không giữ `blockers=[charging]` suốt 24/24 khi đang cắm cáp USB.
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
        private const val AUTO_COLLAPSE_MS = 6000L
    }

    private var isPluggedIn = false
    private var isChargingIslandShowing = false
    private var lastMilestonePercent = -1
    private var autoCollapseJob: Job? = null

    enum class ChargingTier(
        val tierName: String,
        val highlightColor: String,
        val startColor: Int,
        val endColor: Int,
        val isMultiBolt: Boolean
    ) {
        HYPER_CHARGE(
            tierName = "Xiaomi HyperCharge",
            highlightColor = "#FF3B30",
            startColor = Color.parseColor("#FF5E3A"),
            endColor = Color.parseColor("#FF2A6D"),
            isMultiBolt = true
        ),
        TURBO_CHARGE(
            tierName = "Mi Turbo Charge",
            highlightColor = "#FF9F0A",
            startColor = Color.parseColor("#FF9500"),
            endColor = Color.parseColor("#FFB800"),
            isMultiBolt = true
        ),
        FAST_CHARGE(
            tierName = "Sạc Nhanh QC/PD",
            highlightColor = "#30D158",
            startColor = Color.parseColor("#30D158"),
            endColor = Color.parseColor("#34C759"),
            isMultiBolt = false
        ),
        STANDARD_CHARGE(
            tierName = "Sạc Tiêu Chuẩn",
            highlightColor = "#34C759",
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
                    isPluggedIn = true
                    lastMilestonePercent = getBatteryPercentage()
                    scope.launch {
                        if (prefs.isChargingIslandEnabled.first()) {
                            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                            showChargingIslandTransient(getBatteryPercentage(), sticky ?: intent)
                        }
                    }
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    isPluggedIn = false
                    lastMilestonePercent = -1
                    collapseToAppropriateState()
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    val isChargingNow = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL || plugged > 0

                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else getBatteryPercentage()

                    if (isChargingNow) {
                        val justConnected = !isPluggedIn
                        val justReachedFull = (percent == 100 && lastMilestonePercent != 100)
                        isPluggedIn = true
                        if (justConnected || justReachedFull) {
                            lastMilestonePercent = percent
                            scope.launch {
                                if (prefs.isChargingIslandEnabled.first()) {
                                    showChargingIslandTransient(percent, intent)
                                }
                            }
                        }
                    } else if (isPluggedIn || isChargingIslandShowing) {
                        isPluggedIn = false
                        lastMilestonePercent = -1
                        collapseToAppropriateState()
                    }
                }
            }
        }
    }

    fun startListening() {
        // Đảm bảo không còn kẹt blocker "charging" từ phiên cũ
        IdleIslandManager.setBlockerStatic("charging", false)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        try {
            val sticky = context.registerReceiver(batteryReceiver, filter)
            val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            isPluggedIn = plugged > 0
            if (isPluggedIn) {
                lastMilestonePercent = getBatteryPercentage()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Register battery receiver failed: ${e.message}")
        }

        scope.launch {
            prefs.isChargingIslandEnabled.collect { enabled ->
                if (!enabled && isChargingIslandShowing) {
                    collapseToAppropriateState()
                }
            }
        }
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        collapseToAppropriateState()
    }

    private fun getBatteryPercentage(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 80
    }

    private fun getChargingWattage(intent: Intent?): Double {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        var currentAmps = 0.0
        var voltageVolts = 0.0

        val currentNow = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val absCurrent = abs(currentNow)
        if (absCurrent > 0) {
            currentAmps = if (absCurrent > 10000) {
                absCurrent / 1_000_000.0
            } else {
                absCurrent / 1_000.0
            }
        }

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        if (voltageMv > 0) {
            voltageVolts = voltageMv / 1_000.0
        }

        var watts = currentAmps * voltageVolts

        if (watts <= 0.5) {
            val maxCurrent = intent?.getIntExtra("max_charging_current", -1) ?: -1
            val maxVoltage = intent?.getIntExtra("max_charging_voltage", -1) ?: -1
            if (maxCurrent > 0 && maxVoltage > 0) {
                val maxAmps = if (maxCurrent > 10000) maxCurrent / 1_000_000.0 else maxCurrent / 1_000.0
                val maxVolts = if (maxVoltage > 100000) maxVoltage / 1_000_000.0 else maxVoltage / 1_000.0
                watts = maxAmps * maxVolts
            }
        }

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

    /**
     * Hiển thị Đảo Sạc trong 6 giây rồi tự động gọi `collapseToAppropriateState()`
     * để trả về Đảo Chờ 24/7 / Đảo Nhạc / Đảo Thông báo.
     */
    private suspend fun showChargingIslandTransient(percent: Int, intent: Intent? = null) {
        val showWattage = prefs.isChargingWattageEnabled.first()
        val showTimeToFull = prefs.isChargingTimeToFullEnabled.first()

        val watts = getChargingWattage(intent)
        val tier = determineTier(watts)
        val timeStr = getTimeToFull(percent, watts)
        val wattRounded = watts.roundToInt()
        val wattStr = if (watts >= 10.0) "${wattRounded}W" else "%.1fW".format(Locale.US, watts)

        val titleText = if (percent >= 100) {
            "Đã Sạc Đầy 100%"
        } else if (showWattage) {
            "Đang sạc $percent% • $wattStr"
        } else {
            "Đang sạc $percent%"
        }

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
        paramV2.put("timeout", 6000)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", false)
        paramV2.put("islandFirstFloat", true)
        paramV2.put("reopen", true)

        val paramIsland = JSONObject()
        paramIsland.put("islandProperty", if (FocusProtocolHelper.isKeyguardLocked(context)) 0 else 1)
        paramIsland.put("islandPriority", 3)
        paramIsland.put("islandTimeout", 6) // Tự đóng sau 6 giây
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", true)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        paramIsland.put("expandedTime", 4)
        paramIsland.put("highlightColor", tier.highlightColor)

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

        val smallIslandArea = JSONObject()
        val smallPic = JSONObject()
        smallPic.put("type", 1)
        smallPic.put("pic", "miui.focus.pic_$PIC_CHARGING_LIGHTNING")
        smallIslandArea.put("picInfo", smallPic)
        paramIsland.put("smallIslandArea", smallIslandArea)

        paramV2.put("param_island", paramIsland)
        paramV2.put("baseInfo", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_$PIC_CHARGING_LIGHTNING")
            })
            put("title", titleText)
            put("content", contentText)
        })

        val iconTextInfo = JSONObject()
        iconTextInfo.put("title", titleText)
        iconTextInfo.put("content", contentText)
        paramV2.put("iconTextInfo", iconTextInfo)

        root.put("param_v2", paramV2)
        root.put("isShowNotification", false)

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
            isOngoing = false,
            onlyAlertOnce = false,
            largeIcon = lightningBmp,
            keepIslandOnLockscreen = true,
            notificationId = CHARGING_NOTIFICATION_ID
        )

        isChargingIslandShowing = true
        IdleIslandManager.setBlockerStatic("charging", true)
        ShizukuEngine.clearPostedActiveId(CHARGING_NOTIFICATION_ID)
        FocusProtocolHelper.postNotificationSafely(context, CHARGING_NOTIFICATION_ID, notif)
        AppLogger.i(TAG, "Hiển thị Đảo Sạc tạm thời ($titleText) -> tự thu về Đảo Chờ sau ${AUTO_COLLAPSE_MS / 1000}s")

        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_MS)
            collapseToAppropriateState()
        }
    }

    /**
     * Thu gọn Đảo Sạc sau khi hiển thị xong để trả về trạng thái phù hợp (Đảo Chờ 24/7 / Đảo Nhạc / Thông báo).
     */
    fun collapseToAppropriateState() {
        autoCollapseJob?.cancel()
        autoCollapseJob = null
        isChargingIslandShowing = false
        com.ltm.dynamichyper.core.IslandProbe.cancelTrack(CHARGING_NOTIFICATION_ID)
        ShizukuEngine.clearPostedActiveId(CHARGING_NOTIFICATION_ID)
        try {
            NotificationManagerCompat.from(context).cancel(CHARGING_NOTIFICATION_ID)
        } catch (_: Exception) {}
        IdleIslandManager.setBlockerStatic("charging", false)
    }

    private fun createLiquidGlassLightningBitmap(tier: ChargingTier): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = size / 2f
        val radius = size * 0.44f

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = tier.startColor
            alpha = 60
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        canvas.drawCircle(center, center, radius + 4f, glowPaint)

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

        val whiteBoltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        if (tier.isMultiBolt) {
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
