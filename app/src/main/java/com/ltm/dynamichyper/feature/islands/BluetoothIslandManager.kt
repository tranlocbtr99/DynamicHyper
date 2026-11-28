package com.ltm.dynamichyper.feature.islands

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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

/**
 * Quản lý Đảo Kết Nối Tai Nghe Bluetooth & Thiết Bị Âm Thanh kèm % Pin (`BluetoothIslandManager`).
 * - Lắng nghe `ACL_CONNECTED`, `ACTION_CONNECTION_STATE_CHANGED` và `ACTION_BATTERY_LEVEL_CHANGED`
 *   (`android.bluetooth.device.action.BATTERY_LEVEL_CHANGED`).
 * - Tự động đọc `% pin` của tai nghe qua reflection `BluetoothDevice.getBatteryLevel()` hoặc
 *   extra `android.bluetooth.device.extra.BATTERY_LEVEL`.
 * - Hiển thị Đảo Động trong 5 giây rồi tự động thu gọn (`collapseToAppropriateState`) trả về Đảo Chờ 24/7.
 */
class BluetoothIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager
) {
    private val TAG = "DynamicHyper:Bluetooth"

    companion object {
        const val BLUETOOTH_NOTIFICATION_ID = 6668
        private const val PIC_BT_ICON = "bt_headset_icon"
        private const val ACTION_BT_BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val EXTRA_BT_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private const val ACTION_A2DP_CONNECTION_CHANGED = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED"
        private const val ACTION_HEADSET_CONNECTION_CHANGED = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED"
        private const val AUTO_COLLAPSE_MS = 5000L
    }

    private var lastShownDeviceKey = ""
    private var lastShownTimestamp = 0L
    private var autoCollapseJob: Job? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    triggerBluetoothIsland(device, intent, isDisconnected = false)
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    triggerBluetoothIsland(device, intent, isDisconnected = true)
                }
                ACTION_A2DP_CONNECTION_CHANGED, ACTION_HEADSET_CONNECTION_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                    if (state == BluetoothAdapter.STATE_CONNECTED) {
                        triggerBluetoothIsland(device, intent, isDisconnected = false)
                    } else if (state == BluetoothAdapter.STATE_DISCONNECTED) {
                        triggerBluetoothIsland(device, intent, isDisconnected = true)
                    }
                }
                ACTION_BT_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1)
                    if (level in 0..100 && System.currentTimeMillis() - lastShownTimestamp < 8000L) {
                        triggerBluetoothIsland(device, intent, isDisconnected = false, explicitBattery = level, forceUpdate = true)
                    }
                }
            }
        }
    }

    fun startListening() {
        IdleIslandManager.setBlockerStatic("bluetooth", false)
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(ACTION_A2DP_CONNECTION_CHANGED)
            addAction(ACTION_HEADSET_CONNECTION_CHANGED)
            addAction(ACTION_BT_BATTERY_CHANGED)
        }
        try {
            context.registerReceiver(btReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Register btReceiver failed: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(btReceiver)
        } catch (_: Exception) {}
        collapseToAppropriateState()
    }

    private fun triggerBluetoothIsland(
        device: BluetoothDevice?,
        intent: Intent,
        isDisconnected: Boolean,
        explicitBattery: Int? = null,
        forceUpdate: Boolean = false
    ) {
        scope.launch {
            if (!prefs.isBluetoothIslandEnabled.first()) return@launch
            val deviceName = getSafeDeviceName(device)
            val batteryLevel = explicitBattery ?: extractBatteryLevel(device, intent)
            val now = System.currentTimeMillis()
            val key = "$deviceName|$isDisconnected|$batteryLevel"
            if (!forceUpdate && key == lastShownDeviceKey && now - lastShownTimestamp < 4000L) {
                return@launch
            }
            lastShownDeviceKey = key
            lastShownTimestamp = now

            showBluetoothIslandTransient(deviceName, batteryLevel, isDisconnected)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSafeDeviceName(device: BluetoothDevice?): String {
        if (device == null) return "Tai nghe Bluetooth"
        return try {
            val alias = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                device.alias
            } else null
            val name = alias ?: device.name
            if (!name.isNullOrBlank()) name else "Tai nghe Bluetooth"
        } catch (_: SecurityException) {
            // Thử đọc qua Shizuku nếu chưa cấp BLUETOOTH_CONNECT runtime
            readBluetoothDeviceNameViaShizuku(device.address) ?: "Tai nghe Bluetooth"
        } catch (_: Throwable) {
            "Tai nghe Bluetooth"
        }
    }

    private fun readBluetoothDeviceNameViaShizuku(address: String?): String? {
        if (address.isNullOrBlank() || !ShizukuEngine.hasShizukuPermission()) return null
        return try {
            val (code, out) = ShizukuEngine.execCommand("dumpsys bluetooth_manager | grep -i -A 2 '$address'")
            if (code == 0 && out.isNotBlank()) {
                Regex("name\\s*[:=]\\s*([^,\\n]+)", RegexOption.IGNORE_CASE).find(out)?.groupValues?.getOrNull(1)?.trim()
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractBatteryLevel(device: BluetoothDevice?, intent: Intent?): Int {
        val fromIntent = intent?.getIntExtra(EXTRA_BT_BATTERY_LEVEL, -1) ?: -1
        if (fromIntent in 0..100) return fromIntent

        if (device != null) {
            try {
                val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
                val level = method.invoke(device) as? Int ?: -1
                if (level in 0..100) return level
            } catch (_: Throwable) {}
        }
        return -1
    }

    private fun showBluetoothIslandTransient(
        deviceName: String,
        batteryPercent: Int,
        isDisconnected: Boolean
    ) {
        val hasBattery = !isDisconnected && batteryPercent in 0..100
        val titleText = when {
            isDisconnected -> "Đã Ngắt Kết Nối"
            hasBattery -> "$deviceName • 🔋 $batteryPercent%"
            else -> "Đã Kết Nối $deviceName"
        }
        val contentText = when {
            isDisconnected -> "$deviceName đã ngắt kết nối âm thanh"
            hasBattery -> "Tai nghe Bluetooth sẵn sàng • Pin còn $batteryPercent%"
            else -> "Thiết bị âm thanh Bluetooth đã kết nối"
        }
        val pillLabel = when {
            isDisconnected -> "Đã ngắt"
            hasBattery -> "🎧 $batteryPercent%"
            else -> "🎧 Đã nối"
        }
        val highlightColor = when {
            isDisconnected -> "#8E8E93"
            hasBattery && batteryPercent <= 20 -> "#FF453A"
            hasBattery -> "#30D158"
            else -> "#0A84FF"
        }

        val isLocked = FocusProtocolHelper.isKeyguardLocked(context)
        val root = JSONObject()
        val paramV2 = JSONObject()
        paramV2.put("protocol", 1)
        paramV2.put("business", "taxi")
        paramV2.put("updatable", true)
        paramV2.put("ticker", titleText)
        paramV2.put("timeout", 5000)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", false)
        paramV2.put("islandFirstFloat", true)
        paramV2.put("reopen", true)

        val paramIsland = JSONObject()
        paramIsland.put("islandProperty", if (isLocked) 0 else 1)
        paramIsland.put("islandPriority", 3)
        paramIsland.put("islandTimeout", 5)
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", true)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        paramIsland.put("expandedTime", 4)
        paramIsland.put("highlightColor", highlightColor)

        val bigIslandArea = JSONObject()
        bigIslandArea.put("imageTextInfoLeft", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_$PIC_BT_ICON")
            })
            put("textInfo", JSONObject().apply {
                put("title", "")
                put("content", "")
                put("showHighlightColor", false)
            })
        })
        bigIslandArea.put("imageTextInfoRight", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_hidden_pixel")
            })
            put("textInfo", JSONObject().apply {
                put("title", pillLabel)
                put("content", "")
                put("showHighlightColor", true)
            })
        })
        paramIsland.put("bigIslandArea", bigIslandArea)
        paramIsland.put("smallIslandArea", JSONObject().apply {
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_$PIC_BT_ICON")
            })
        })

        paramV2.put("param_island", paramIsland)
        paramV2.put("baseInfo", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_$PIC_BT_ICON")
            })
            put("title", titleText)
            put("content", contentText)
        })
        paramV2.put("iconTextInfo", JSONObject().apply {
            put("title", titleText)
            put("content", contentText)
        })

        root.put("param_v2", paramV2)
        root.put("isShowNotification", false)

        val btBmp = createHeadphonesBitmap(isDisconnected, batteryPercent)
        val pics = mapOf(
            PIC_BT_ICON to btBmp,
            "hidden_pixel" to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        )

        val notif = FocusProtocolHelper.buildFocusNotification(
            context = context,
            title = titleText,
            content = contentText,
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = root.toString(),
            pics = pics,
            isOngoing = false,
            onlyAlertOnce = false,
            largeIcon = btBmp,
            keepIslandOnLockscreen = true,
            notificationId = BLUETOOTH_NOTIFICATION_ID
        )

        IdleIslandManager.setBlockerStatic("bluetooth", true)
        ShizukuEngine.clearPostedActiveId(BLUETOOTH_NOTIFICATION_ID)
        FocusProtocolHelper.postNotificationSafely(context, BLUETOOTH_NOTIFICATION_ID, notif)
        AppLogger.i(TAG, "Hiển thị Đảo Bluetooth: $titleText")

        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_MS)
            collapseToAppropriateState()
        }
    }

    fun collapseToAppropriateState() {
        autoCollapseJob?.cancel()
        autoCollapseJob = null
        ShizukuEngine.clearPostedActiveId(BLUETOOTH_NOTIFICATION_ID)
        try {
            NotificationManagerCompat.from(context).cancel(BLUETOOTH_NOTIFICATION_ID)
        } catch (_: Exception) {}
        IdleIslandManager.setBlockerStatic("bluetooth", false)
    }

    private fun createHeadphonesBitmap(isDisconnected: Boolean, batteryPercent: Int): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = size * 0.44f

        val (startColor, endColor) = when {
            isDisconnected -> Color.parseColor("#636366") to Color.parseColor("#48484A")
            batteryPercent in 0..20 -> Color.parseColor("#FF5E3A") to Color.parseColor("#FF2A6D")
            else -> Color.parseColor("#0A84FF") to Color.parseColor("#00F2FE")
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                size * 0.15f, size * 0.15f,
                size * 0.85f, size * 0.85f,
                startColor, endColor,
                Shader.TileMode.CLAMP
            )
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, bgPaint)

        // Vòng cung phần trăm pin bao quanh nếu có % pin
        if (!isDisconnected && batteryPercent in 0..100) {
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (batteryPercent <= 20) Color.parseColor("#FF453A") else Color.parseColor("#30D158")
                style = Paint.Style.STROKE
                strokeWidth = 6f
                strokeCap = Paint.Cap.ROUND
            }
            val sweep = (batteryPercent / 100f) * 360f
            val arcRect = RectF(center - radius - 4f, center - radius - 4f, center + radius + 4f, center + radius + 4f)
            canvas.drawArc(arcRect, -90f, sweep, false, ringPaint)
        }

        // Vẽ biểu tượng tai nghe (Headphones) ở trung tâm
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(
            RectF(size * 0.30f, size * 0.28f, size * 0.70f, size * 0.68f),
            180f, 180f, false, iconPaint
        )
        val cupPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(size * 0.25f, size * 0.48f, size * 0.36f, size * 0.72f), 8f, 8f, cupPaint)
        canvas.drawRoundRect(RectF(size * 0.64f, size * 0.48f, size * 0.75f, size * 0.72f), 8f, 8f, cupPaint)

        return bmp
    }
}
