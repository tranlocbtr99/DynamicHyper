package com.ltm.dynamichyper.core

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ltm.dynamichyper.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FocusProtocolHelper {

    // v2: kênh cũ (v1.5.x) được tạo thiếu lockscreenVisibility/bypassDnd trên một số ROM.
    // Thuộc tính NotificationChannel là BẤT BIẾN sau khi tạo, nên buộc phải dùng ID mới
    // để cài đặt mới có hiệu lực ngay cả trên máy đã cài bản cũ.
    const val CHANNEL_ID = "dynamic_hyper_focus_channel_v2"
    private const val LEGACY_CHANNEL_ID = "dynamic_hyper_focus_channel"
    const val DEFAULT_ISLAND_PROPERTY = 1
    const val TEST_ISLAND_ID = 9998

    fun setupChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Xóa kênh cũ để không còn notification nào bị kẹt ở cấu hình sai.
        try {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        } catch (_: Throwable) {}
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dynamic Hyper Focus Channel",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            description = "Notification channel powering Xiaomi HyperOS Focus Islands"
        }
        manager.createNotificationChannel(channel)
    }

    // ================= CỔNG KIỂM TRA CỦA HYPEROS =================

    /** Kết quả chẩn đoán cổng Focus Island của HyperOS. */
    data class FocusGate(
        val isXiaomi: Boolean,
        val islandFeature: Boolean?,
        val canShowFocus: Boolean?,
        val summary: String
    )

    fun isXiaomiDevice(): Boolean {
        val m = Build.MANUFACTURER ?: ""
        return m.equals("Xiaomi", true) || m.equals("Redmi", true) || m.equals("POCO", true)
    }

    fun isIslandFeatureAvailable(): Boolean? = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val getBoolean = clazz.getDeclaredMethod(
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        getBoolean.isAccessible = true
        (getBoolean.invoke(null, "persist.sys.feature.island", false) as? Boolean) ?: false
    } catch (_: Throwable) {
        null
    }

    /**
     * Xác định phiên bản giao thức Focus Protocol của thiết bị:
     * 1: HyperOS 1
     * 2: HyperOS 2
     * 3: HyperOS 3 / 4
     */
    fun getFocusProtocolVersion(context: Context): Int {
        val sysProtocol = try {
            android.provider.Settings.System.getInt(
                context.contentResolver,
                "notification_focus_protocol",
                0
            )
        } catch (_: Throwable) {
            0
        }
        if (sysProtocol in 1..3) return sysProtocol

        // Nếu chưa set trong Settings.System, đọc từ ro.mi.os.version.name hoặc phiên bản Android SDK
        val osVer = RomDiagnostics.prop("ro.mi.os.version.name")
        return when {
            osVer.startsWith("OS3", true) || osVer.startsWith("OS4", true) -> 3
            osVer.startsWith("OS2", true) -> 2
            osVer.startsWith("OS1", true) -> 1
            Build.VERSION.SDK_INT >= 36 -> 3 // Android 16/17 (HyperOS 3/4)
            Build.VERSION.SDK_INT >= 35 -> 2 // Android 15 (HyperOS 2)
            Build.VERSION.SDK_INT >= 34 -> 1 // Android 14 (HyperOS 1)
            else -> 1
        }
    }

    /**
     * Hỏi thẳng SystemUI: package này có được phép hiện Focus Island không.
     * Đây chính là cổng mà app-op "Thông báo nổi" (10033/10020) điều khiển.
     */
    fun canShowFocus(context: Context): Boolean? = try {
        val bundle = context.contentResolver.call(
            Uri.parse("content://miui.statusbar.notification.public"),
            "canShowFocus",
            null,
            Bundle().apply { putString("package", context.packageName) }
        )
        bundle?.getBoolean("canShowFocus")
    } catch (_: Throwable) {
        null
    }

    private var gateLogged = false

    fun focusGate(context: Context): FocusGate {
        val xiaomi = isXiaomiDevice()
        val feature = isIslandFeatureAvailable()
        val focus = canShowFocus(context)
        val summary = buildString {
            append("manufacturer=").append(Build.MANUFACTURER)
            append(", islandFeature=").append(feature?.toString() ?: "unknown")
            append(", canShowFocus=").append(focus?.toString() ?: "unknown")
            append(", sdk=").append(Build.VERSION.SDK_INT)
        }
        if (!gateLogged) {
            gateLogged = true
            AppLogger.i("DynamicHyper:Focus", "Cổng Focus Island: $summary")
        }
        return FocusGate(xiaomi, feature, focus, summary)
    }

    /**
     * Kiểm tra xem thiết bị có hỗ trợ đảo động hay không.
     * Lưu ý quan trọng: KHÔNG chặn cứng khi islandFeature == false hoặc canShowFocus == false,
     * vì nhiều ROM Global trả về false hoặc chưa bật cờ nhưng SystemUI vẫn hỗ trợ render
     * hoặc Shizuku có thể kích hoạt lúc runtime. Chỉ trả về false nếu thiết bị chắc chắn không phải Xiaomi.
     */
    fun isFocusIslandSupported(context: Context): Boolean {
        val gate = focusGate(context)
        return gate.isXiaomi
    }

    fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isKeyguardLocked == true
    }

    /**
     * Builds a Xiaomi Focus Notification directly with custom extras.
     */
    fun buildFocusNotification(
        context: Context,
        title: String,
        content: String,
        smallIconRes: Int,
        jsonParam: String,
        pics: Map<String, Bitmap> = emptyMap(),
        isOngoing: Boolean = true,
        onlyAlertOnce: Boolean = true,
        isSilent: Boolean = false,
        priority: Int = NotificationCompat.PRIORITY_HIGH,
        contentIntent: android.app.PendingIntent? = null,
        actionsMap: Map<String, Any> = emptyMap(),
        enablePromotedLiveUpdate: Boolean = true,
        largeIcon: Bitmap? = null
    ): Notification {
        // Luôn đảm bảo Channel được khởi tạo với IMPORTANCE_HIGH trước khi build
        setupChannel(context)
        // Chẩn đoán cổng Focus Island (ghi log một lần)
        focusGate(context)

        // Kiểm tra xem thiết bị có đang ở trạng thái China ROM chưa từng kích hoạt Shizuku hay không.
        // Lưu ý: Đảo Chờ (enablePromotedLiveUpdate = false) TUYỆT ĐỐI KHÔNG bật PromotedOngoing hay shortCriticalText
        // để tránh hiện chip chữ tràn lan trên thanh trạng thái.
        val needsNativeLiveUpdateFallback = enablePromotedLiveUpdate &&
            RomDiagnostics.isChinaRom() &&
            !ShizukuEngine.hasShizukuPermission() &&
            !ShizukuEngine.isOneTimeOptimizationActive(context)
        val effectiveOngoing = isOngoing || needsNativeLiveUpdateFallback

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(effectiveOngoing)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setSilent(isSilent)

        if (enablePromotedLiveUpdate) {
            val shortCritical = buildShortCriticalText(title, content)
            builder.setRequestPromotedOngoing(true)
            builder.setShortCriticalText(shortCritical)
        }

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
        }
        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        // Build miui.focus.pics bundle
        if (pics.isNotEmpty()) {
            val picsBundle = Bundle()
            for ((key, bmp) in pics) {
                picsBundle.putParcelable("miui.focus.pic_$key", Icon.createWithBitmap(bmp))
            }
            val resourceBundle = Bundle()
            resourceBundle.putBundle("miui.focus.pics", picsBundle)
            builder.addExtras(resourceBundle)
        }

        // Build miui.focus.actions bundle for Action Buttons & Quick Reply
        if (actionsMap.isNotEmpty()) {
            val actionsBundle = Bundle()
            for ((key, act) in actionsMap) {
                when (act) {
                    is Notification.Action -> actionsBundle.putParcelable(key, act)
                    is android.app.PendingIntent -> actionsBundle.putParcelable(key, act)
                }
            }
            val actResourceBundle = Bundle()
            actResourceBundle.putBundle("miui.focus.actions", actionsBundle)
            builder.addExtras(actResourceBundle)
        }

        // Xiaomi HyperOS quy định islandProperty là 1 (Hiển thị) hoặc 2 (Thao tác), trên Lockscreen là 0
        val isLocked = isKeyguardLocked(context)
        val targetIslandProperty = if (isLocked) 0 else DEFAULT_ISLAND_PROPERTY
        val targetProtocol = getFocusProtocolVersion(context)
        val patchedJson = patchIslandJson(jsonParam, targetIslandProperty, targetProtocol)

        val notif = builder.build()
        if (effectiveOngoing) {
            notif.flags = notif.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
        notif.extras.putString("miui.focus.param", patchedJson)
        notif.extras.putString("miui.focus.ticker", title)

        // Đồng bộ miui.focus.param.custom theo chuẩn HyperBridge (ParamCustom) để SystemUI HyperOS 3 và HyperOS 4 luôn nhận diện được
        try {
            val rootObj = JsonParser.parseString(patchedJson).asJsonObject
            val paramV2 = rootObj.getAsJsonObject("param_v2")
            if (paramV2 != null) {
                val customObj = JsonObject()
                if (paramV2.has("ticker")) customObj.add("ticker", paramV2.get("ticker"))
                if (paramV2.has("enableFloat")) customObj.add("enableFloat", paramV2.get("enableFloat"))
                if (paramV2.has("updatable")) customObj.add("updatable", paramV2.get("updatable"))
                if (paramV2.has("isShowNotification")) customObj.add("isShowNotification", paramV2.get("isShowNotification"))
                if (paramV2.has("islandFirstFloat")) customObj.add("islandFirstFloat", paramV2.get("islandFirstFloat"))
                if (paramV2.has("timeout")) customObj.add("timeout", paramV2.get("timeout"))
                if (paramV2.has("reopen")) customObj.add("reopen", paramV2.get("reopen"))
                if (paramV2.has("outEffectSrc")) customObj.add("outEffectSrc", paramV2.get("outEffectSrc"))
                if (paramV2.has("aodTitle")) customObj.add("aodTitle", paramV2.get("aodTitle"))
                if (paramV2.has("param_island")) customObj.add("param_island", paramV2.get("param_island"))
                notif.extras.putString("miui.focus.param.custom", Gson().toJson(customObj))
            } else {
                notif.extras.putString("miui.focus.param.custom", patchedJson)
            }
        } catch (_: Exception) {}
        return notif
    }

    private fun buildShortCriticalText(title: String, content: String): String {
        // Trích xuất % pin / tiến trình hoặc thời gian nếu có (chuẩn LiveUpdateTranslator của HyperBridge)
        val combined = "$title $content"
        val percentMatch = Regex("(\\d{1,3})%").find(combined)
        if (percentMatch != null) return percentMatch.value

        val wattMatch = Regex("(\\d+(?:[.,]\\d+)?)\\s*W", RegexOption.IGNORE_CASE).find(combined)
        if (wattMatch != null) return wattMatch.value

        val tempMatch = Regex("(\\d{1,2})°C").find(combined)
        if (tempMatch != null) return tempMatch.value

        val cleanTitle = title.trim()
        if (cleanTitle.isNotEmpty()) {
            return if (cleanTitle.length <= 14) cleanTitle else cleanTitle.take(13) + "…"
        }
        val cleanContent = content.trim()
        return if (cleanContent.length <= 14) cleanContent else cleanContent.take(13) + "…"
    }

    private fun patchIslandJson(json: String, property: Int, protocol: Int): String {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val paramV2 = root.getAsJsonObject("param_v2") ?: root
            val island = paramV2.getAsJsonObject("param_island") ?: root
            island.addProperty("islandProperty", property)
            // Chuẩn hyperisland_kit của HyperBridge: trường "protocol" trong JSON param_v2 luôn là 1
            // để FocusPlugin.createStandardTemplate dựng đầy đủ islandExpandedView khi người dùng chạm vào đảo.
            paramV2.addProperty("protocol", 1)
            Gson().toJson(root)
        } catch (_: Exception) {
            json
        }
    }

    /**
     * Bắn thông báo Đảo Tiêu Điểm có bảo vệ XMSF bypass và đồng bộ Shizuku
     */
    fun postNotificationSafely(
        context: Context,
        id: Int,
        notification: Notification,
        scope: CoroutineScope? = null
    ) {
        val s = scope ?: CoroutineScope(Dispatchers.IO)
        s.launch {
            ShizukuEngine.postNotificationSafely(context, id, notification)
        }
    }

    /**
     * Bắn thông báo Đảo Động Test trực quan:
     * Bung to 4 giây quanh camera nốt ruồi với hiệu ứng bung to + màu cam nổi bật + âm thầm thu nhỏ về camera.
     * Giúp người dùng kiểm tra ngay lập tức xem hệ thống HyperOS đã chấp nhận vẽ Đảo hay chưa.
     */
    fun sendVisualTestIsland(context: Context) {
        val root = org.json.JSONObject()
        val paramV2 = org.json.JSONObject()
        paramV2.put("protocol", 1)
        paramV2.put("business", "test_island")
        paramV2.put("updatable", true)
        paramV2.put("ticker", "Dynamic Hyper • Test Đảo")
        paramV2.put("timeout", 60000)
        paramV2.put("enableFloat", true)
        paramV2.put("isShowNotification", true)
        paramV2.put("islandFirstFloat", true)
        paramV2.put("reopen", true)

        val paramIsland = org.json.JSONObject()
        paramIsland.put("islandProperty", 1)
        paramIsland.put("islandPriority", 2)
        paramIsland.put("islandTimeout", 60000)
        paramIsland.put("islandOrder", false)
        paramIsland.put("dismissIsland", false)
        paramIsland.put("maxSize", false)
        paramIsland.put("needCloseAnimation", true)
        paramIsland.put("expandedTime", 4) // Bung to 4 giây rồi tự động thu về nốt ruồi camera
        paramIsland.put("highlightColor", "#FF9F0A")

        val bigIslandArea = org.json.JSONObject()
        val leftInfo = org.json.JSONObject()
        leftInfo.put("type", 1)
        val leftPic = org.json.JSONObject()
        leftPic.put("type", 1)
        leftPic.put("pic", "miui.focus.pic_test_icon")
        leftInfo.put("picInfo", leftPic)
        leftInfo.put("textInfo", org.json.JSONObject().apply {
            put("title", "")
            put("content", "")
            put("showHighlightColor", false)
        })
        bigIslandArea.put("imageTextInfoLeft", leftInfo)

        val rightInfo = org.json.JSONObject()
        rightInfo.put("type", 1)
        val rightPic = org.json.JSONObject()
        rightPic.put("type", 1)
        rightPic.put("pic", "miui.focus.pic_hidden_pixel")
        rightInfo.put("picInfo", rightPic)
        rightInfo.put("textInfo", org.json.JSONObject().apply {
            put("title", "⚡ Dynamic Hyper")
            put("content", "Đảo Động bung to thành công!")
            put("showHighlightColor", false)
        })
        bigIslandArea.put("imageTextInfoRight", rightInfo)
        paramIsland.put("bigIslandArea", bigIslandArea)

        val smallIslandArea = org.json.JSONObject()
        val smallPic = org.json.JSONObject()
        smallPic.put("type", 1)
        smallPic.put("pic", "miui.focus.pic_test_icon")
        smallIslandArea.put("smallPictureInfo", smallPic)
        paramIsland.put("smallIslandArea", smallIslandArea)

        paramV2.put("param_island", paramIsland)

        val baseInfo = org.json.JSONObject()
        baseInfo.put("type", 1)
        baseInfo.put("title", "Dynamic Hyper • Test Đảo")
        baseInfo.put("content", "Đảo Động HyperOS đã kích hoạt thành công!")
        paramV2.put("baseInfo", baseInfo)

        val iconTextInfo = org.json.JSONObject()
        iconTextInfo.put("title", "Dynamic Hyper • Test Đảo")
        iconTextInfo.put("content", "Đảo Động HyperOS đã kích hoạt thành công!")
        paramV2.put("iconTextInfo", iconTextInfo)

        root.put("param_v2", paramV2)
        root.put("isShowNotification", true)

        val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            val canvas = android.graphics.Canvas(this)
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#FF9F0A")
            }
            canvas.drawCircle(48f, 48f, 44f, paint)
        }

        val pics = mapOf(
            "test_icon" to bmp,
            "hidden_pixel" to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        )

        val notification = buildFocusNotification(
            context = context,
            title = "Dynamic Hyper • Test Đảo",
            content = "Đảo Động HyperOS đã kích hoạt thành công!",
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = root.toString(),
            pics = pics,
            isOngoing = false,
            onlyAlertOnce = false,
            isSilent = false,
            priority = NotificationCompat.PRIORITY_HIGH
        )

        postNotificationSafely(context, TEST_ISLAND_ID, notification)
        AppLogger.i("DynamicHyper:Test", "Đã bắn thông báo Test Đảo trực quan (id=$TEST_ISLAND_ID, float=true, expanded=4s)")
    }
}
