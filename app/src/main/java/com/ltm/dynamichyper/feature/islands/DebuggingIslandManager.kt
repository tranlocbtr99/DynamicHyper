package com.ltm.dynamichyper.feature.islands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.FocusProtocolHelper
import org.json.JSONObject

/**
 * Hiển thị Đảo Động khi thiết bị bật USB Debugging hoặc Wireless Debugging
 */
object DebuggingIslandManager {
    const val DEBUGGING_ISLAND_ID = 8886
    private const val PIC_DEBUG_ICON = "debug_bug"
    private const val TAG = "DynamicHyper:Debug"

    fun showDebuggingIsland(context: Context, isWireless: Boolean = false) {
        try {
            val title = if (isWireless) "Gỡ Lỗi Không Dây" else "Gỡ Lỗi USB"
            val content = if (isWireless) "Wireless Debugging đang bật" else "USB Debugging đang kết nối"

            val root = JSONObject()
            val paramV2 = JSONObject()
            val protocolVer = FocusProtocolHelper.getFocusProtocolVersion(context)
            paramV2.put("protocol", protocolVer)
            paramV2.put("business", "debugging")
            paramV2.put("updatable", true)
            paramV2.put("ticker", "$title: $content")
            paramV2.put("timeout", 86400000)
            paramV2.put("enableFloat", false)
            paramV2.put("isShowNotification", true)
            paramV2.put("islandFirstFloat", false)
            paramV2.put("reopen", true)

            val paramIsland = JSONObject()
            paramIsland.put("islandProperty", 1)
            paramIsland.put("islandPriority", 2)
            paramIsland.put("islandTimeout", 86400000)
            paramIsland.put("islandOrder", false)
            paramIsland.put("dismissIsland", false)
            paramIsland.put("maxSize", false)
            paramIsland.put("needCloseAnimation", true)
            paramIsland.put("expandedTime", 0)
            paramIsland.put("highlightColor", "#FF9F0A")

            val bigIslandArea = JSONObject()
            val leftInfo = JSONObject()
            leftInfo.put("type", 1)
            val leftPic = JSONObject()
            leftPic.put("type", 1)
            leftPic.put("pic", "miui.focus.pic_$PIC_DEBUG_ICON")
            leftInfo.put("picInfo", leftPic)
            val leftText = JSONObject()
            leftText.put("title", title)
            leftText.put("content", content)
            leftText.put("showHighlightColor", false)
            leftInfo.put("textInfo", leftText)
            bigIslandArea.put("imageTextInfoLeft", leftInfo)
            paramIsland.put("bigIslandArea", bigIslandArea)

            val smallIslandArea = JSONObject()
            val smallPic = JSONObject()
            smallPic.put("type", 1)
            smallPic.put("pic", "miui.focus.pic_$PIC_DEBUG_ICON")
            smallIslandArea.put("smallPictureInfo", smallPic)
            paramIsland.put("smallIslandArea", smallIslandArea)

            paramV2.put("param_island", paramIsland)
            root.put("param_v2", paramV2)
            root.put("isShowNotification", true)

            val debugBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF9F0A")
                }
                canvas.drawCircle(48f, 48f, 42f, paint)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 42f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("⚡", 48f, 62f, textPaint)
            }

            val pics = mapOf(PIC_DEBUG_ICON to debugBmp)

            val notification = FocusProtocolHelper.buildFocusNotification(
                context = context,
                title = title,
                content = content,
                smallIconRes = R.drawable.ic_launcher_foreground,
                jsonParam = root.toString(),
                pics = pics,
                isOngoing = true,
                priority = NotificationCompat.PRIORITY_DEFAULT
            )

            FocusProtocolHelper.postNotificationSafely(context, DEBUGGING_ISLAND_ID, notification)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Lỗi hiển thị đảo debug: ${e.message}")
        }
    }

    fun hideDebuggingIsland(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(DEBUGGING_ISLAND_ID)
        } catch (_: Exception) {}
        IdleIslandManager.reassertStatic()
    }
}
