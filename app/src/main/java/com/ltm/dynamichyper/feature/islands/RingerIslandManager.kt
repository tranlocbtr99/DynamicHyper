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
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
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
 * Quản lý Đảo Báo Chế Độ Âm Thanh (RingerMode Island):
 * Hiển thị hiệu ứng Đảo Động iOS khi người dùng chuyển giữa các chế độ:
 * - Chuông (RINGER_MODE_NORMAL) -> Xanh lá (#30D158)
 * - Rung (RINGER_MODE_VIBRATE) -> Cam hổ phách (#FF9F0A)
 * - Im lặng (RINGER_MODE_SILENT) -> Đỏ san hô (#FF453A)
 * Tự động thu gọn (`collapseToAppropriateState`) sau 3.5 giây để trả về Đảo Chờ 24/7.
 */
class RingerIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager
) {
    private val TAG = "DynamicHyper:Ringer"

    companion object {
        const val RINGER_NOTIFICATION_ID = 6667
        private const val PIC_RINGER_ICON = "ringer_mode_icon"
        private const val AUTO_COLLAPSE_MS = 3500L
    }

    private var lastRingerMode: Int = -1
    private var isInitialized = false
    private var autoCollapseJob: Job? = null

    private val ringerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val mode = intent.getIntExtra(
                AudioManager.EXTRA_RINGER_MODE,
                audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
            )
            // Bỏ qua sticky broadcast lần đầu tiên khi vừa đăng ký receiver
            if (!isInitialized) {
                isInitialized = true
                lastRingerMode = mode
                return
            }
            if (mode == lastRingerMode) return
            lastRingerMode = mode

            scope.launch {
                if (prefs.isRingerIslandEnabled.first()) {
                    showRingerIslandTransient(mode)
                }
            }
        }
    }

    fun startListening() {
        IdleIslandManager.setBlockerStatic("ringer", false)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        lastRingerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        isInitialized = false
        try {
            context.registerReceiver(ringerReceiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
        } catch (e: Exception) {
            Log.e(TAG, "Register ringerReceiver failed: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(ringerReceiver)
        } catch (_: Exception) {}
        collapseToAppropriateState()
    }

    private fun showRingerIslandTransient(mode: Int) {
        val (titleText, contentText, pillLabel, highlightColor, startColor, endColor) = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> Sextet(
                "Chế Độ Im Lặng",
                "Đã tắt toàn bộ chuông & âm báo",
                "Im lặng",
                "#FF453A",
                Color.parseColor("#FF5E3A"),
                Color.parseColor("#FF2A6D")
            )
            AudioManager.RINGER_MODE_VIBRATE -> Sextet(
                "Chế Độ Rung",
                "Chỉ rung khi có cuộc gọi & tin nhắn",
                "Rung",
                "#FF9F0A",
                Color.parseColor("#FF9500"),
                Color.parseColor("#FFB800")
            )
            else -> Sextet(
                "Chế Độ Chuông",
                "Đã bật âm thanh chuông & thông báo",
                "Chuông",
                "#30D158",
                Color.parseColor("#30D158"),
                Color.parseColor("#00C7BE")
            )
        }

        val isLocked = FocusProtocolHelper.isKeyguardLocked(context)
        val root = JSONObject()
        val paramV2 = JSONObject()
        paramV2.put("protocol", 1)
        paramV2.put("business", "taxi")
        paramV2.put("updatable", true)
        paramV2.put("ticker", titleText)
        paramV2.put("timeout", 3500)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", false)
        paramV2.put("islandFirstFloat", true)
        paramV2.put("reopen", true)

        val paramIsland = JSONObject()
        paramIsland.put("islandProperty", if (isLocked) 0 else 1)
        paramIsland.put("islandPriority", 3)
        paramIsland.put("islandTimeout", 4)
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", true)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        paramIsland.put("expandedTime", 3)
        paramIsland.put("highlightColor", highlightColor)

        val bigIslandArea = JSONObject()
        bigIslandArea.put("imageTextInfoLeft", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_$PIC_RINGER_ICON")
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
                put("pic", "miui.focus.pic_$PIC_RINGER_ICON")
            })
        })

        paramV2.put("param_island", paramIsland)
        paramV2.put("baseInfo", JSONObject().apply {
            put("type", 1)
            put("picInfo", JSONObject().apply {
                put("type", 1)
                put("pic", "miui.focus.pic_$PIC_RINGER_ICON")
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

        val ringerBmp = createRingerBitmap(mode, startColor, endColor)
        val pics = mapOf(
            PIC_RINGER_ICON to ringerBmp,
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
            largeIcon = ringerBmp,
            keepIslandOnLockscreen = true,
            notificationId = RINGER_NOTIFICATION_ID
        )

        IdleIslandManager.setBlockerStatic("ringer", true)
        ShizukuEngine.clearPostedActiveId(RINGER_NOTIFICATION_ID)
        FocusProtocolHelper.postNotificationSafely(context, RINGER_NOTIFICATION_ID, notif)
        AppLogger.i(TAG, "Hiển thị Đảo Chế Độ Âm Thanh: $titleText")

        autoCollapseJob?.cancel()
        autoCollapseJob = scope.launch {
            delay(AUTO_COLLAPSE_MS)
            collapseToAppropriateState()
        }
    }

    fun collapseToAppropriateState() {
        autoCollapseJob?.cancel()
        autoCollapseJob = null
        ShizukuEngine.clearPostedActiveId(RINGER_NOTIFICATION_ID)
        try {
            NotificationManagerCompat.from(context).cancel(RINGER_NOTIFICATION_ID)
        } catch (_: Exception) {}
        IdleIslandManager.setBlockerStatic("ringer", false)
    }

    private data class Sextet(
        val title: String,
        val content: String,
        val pillLabel: String,
        val highlightHex: String,
        val startColor: Int,
        val endColor: Int
    )

    private fun createRingerBitmap(mode: Int, startColor: Int, endColor: Int): Bitmap {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val radius = size * 0.44f

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

        // Vẽ quả chuông vector sắc nét ở trung tâm
        val bellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val bellPath = Path().apply {
            moveTo(center, size * 0.25f)
            cubicTo(size * 0.34f, size * 0.25f, size * 0.32f, size * 0.40f, size * 0.32f, size * 0.56f)
            lineTo(size * 0.26f, size * 0.64f)
            lineTo(size * 0.74f, size * 0.64f)
            lineTo(size * 0.68f, size * 0.56f)
            cubicTo(size * 0.68f, size * 0.40f, size * 0.66f, size * 0.25f, center, size * 0.25f)
            close()
        }
        canvas.drawPath(bellPath, bellPaint)
        canvas.drawArc(
            RectF(center - size * 0.08f, size * 0.62f, center + size * 0.08f, size * 0.76f),
            0f, 180f, true, bellPaint
        )

        if (mode == AudioManager.RINGER_MODE_SILENT) {
            // Đường gạch chéo tắt chuông
            val slashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 7f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(size * 0.28f, size * 0.28f, size * 0.72f, size * 0.72f, slashPaint)
        } else if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            // 2 sóng rung hai bên quả chuông
            val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 5f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawArc(RectF(size * 0.18f, size * 0.36f, size * 0.30f, size * 0.58f), 120f, 120f, false, wavePaint)
            canvas.drawArc(RectF(size * 0.70f, size * 0.36f, size * 0.82f, size * 0.58f), -60f, 120f, false, wavePaint)
        }

        return bmp
    }
}
