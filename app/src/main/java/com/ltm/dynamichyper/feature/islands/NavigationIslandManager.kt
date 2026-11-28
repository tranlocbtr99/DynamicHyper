package com.ltm.dynamichyper.feature.islands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.FocusProtocolHelper
import org.json.JSONObject

/**
 * Hiển thị Đảo Động chỉ đường khi người dùng sử dụng Google Maps hoặc ứng dụng dẫn đường.
 */
object NavigationIslandManager {
    const val NAV_ISLAND_ID = 8887
    private const val PIC_NAV_ICON = "nav_arrow"
    private const val TAG = "DynamicHyper:Nav"

    private val NAV_PACKAGES = setOf(
        "com.google.android.apps.maps",
        "com.here.app.maps",
        "com.waze"
    )

    fun isNavNotification(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in NAV_PACKAGES) return false
        val extras = sbn.notification.extras
        val template = extras.getString("android.template") ?: ""
        val category = sbn.notification.category ?: ""
        return category == "navigation" || template.contains("BigPicture") || sbn.isOngoing
    }

    fun showNavIsland(context: Context, title: String, content: String) {
        try {
            val root = JSONObject()
            val paramV2 = JSONObject()
            val protocolVer = FocusProtocolHelper.getFocusProtocolVersion(context)
            paramV2.put("protocol", protocolVer)
            paramV2.put("business", "navigation")
            paramV2.put("updatable", true)
            paramV2.put("ticker", "$title: $content")
            paramV2.put("timeout", 86400000)
            paramV2.put("enableFloat", false)
            paramV2.put("isShowNotification", false)
            paramV2.put("islandFirstFloat", false)
            paramV2.put("reopen", true)

            val paramIsland = JSONObject()
            paramIsland.put("islandProperty", 1)
            paramIsland.put("islandPriority", 3)
            paramIsland.put("islandTimeout", 86400000)
            paramIsland.put("islandOrder", false)
            paramIsland.put("dismissIsland", false)
            paramIsland.put("maxSize", false)
            paramIsland.put("needCloseAnimation", true)
            paramIsland.put("expandedTime", 0)
            paramIsland.put("highlightColor", "#30D158")

            val bigIslandArea = JSONObject()
            val leftInfo = JSONObject()
            leftInfo.put("type", 1)
            val leftPic = JSONObject()
            leftPic.put("type", 1)
            leftPic.put("pic", "miui.focus.pic_$PIC_NAV_ICON")
            leftInfo.put("picInfo", leftPic)
            val leftText = JSONObject()
            leftText.put("title", title.ifBlank { "Chỉ Đường" })
            leftText.put("content", content.ifBlank { "Đang dẫn đường..." })
            leftText.put("showHighlightColor", false)
            leftInfo.put("textInfo", leftText)
            bigIslandArea.put("imageTextInfoLeft", leftInfo)
            paramIsland.put("bigIslandArea", bigIslandArea)

            val smallIslandArea = JSONObject()
            val smallPic = JSONObject()
            smallPic.put("type", 1)
            smallPic.put("pic", "miui.focus.pic_$PIC_NAV_ICON")
            smallIslandArea.put("picInfo", smallPic)
            paramIsland.put("smallIslandArea", smallIslandArea)

            paramV2.put("param_island", paramIsland)
            paramV2.put("baseInfo", JSONObject().apply {
                put("type", 1)
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_$PIC_NAV_ICON")
                })
                put("title", title.ifBlank { "Chỉ Đường" })
                put("content", content.ifBlank { "Đang dẫn đường..." })
            })
            paramV2.put("iconTextInfo", JSONObject().apply {
                put("title", title.ifBlank { "Chỉ Đường" })
                put("content", content.ifBlank { "Đang dẫn đường..." })
            })
            root.put("param_v2", paramV2)
            root.put("isShowNotification", false)

            val navBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#30D158")
                }
                canvas.drawCircle(48f, 48f, 42f, paint)
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = 40f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("➔", 48f, 62f, textPaint)
            }

            val pics = mapOf(PIC_NAV_ICON to navBmp)

            val notification = FocusProtocolHelper.buildFocusNotification(
                context = context,
                title = title.ifBlank { "Chỉ Đường" },
                content = content,
                smallIconRes = R.drawable.ic_launcher_foreground,
                jsonParam = root.toString(),
                pics = pics,
                isOngoing = true,
                priority = NotificationCompat.PRIORITY_HIGH,
                largeIcon = navBmp,
                notificationId = NAV_ISLAND_ID
            )

            IdleIslandManager.setBlockerStatic("nav", true)
            FocusProtocolHelper.postNotificationSafely(context, NAV_ISLAND_ID, notification)
            AppLogger.d(TAG, "Đã gửi Đảo Dẫn Đường: $title - $content")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Lỗi hiển thị đảo dẫn đường: ${e.message}")
        }
    }

    fun hideNavIsland(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NAV_ISLAND_ID)
        } catch (_: Exception) {}
        IdleIslandManager.reassertStatic()
    }
}
