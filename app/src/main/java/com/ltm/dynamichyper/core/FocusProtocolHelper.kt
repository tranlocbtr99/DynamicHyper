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

object FocusProtocolHelper {

    // v2: kênh cũ (v1.5.x) được tạo thiếu lockscreenVisibility/bypassDnd trên một số ROM.
    // Thuộc tính NotificationChannel là BẤT BIẾN sau khi tạo, nên buộc phải dùng ID mới
    // để cài đặt mới có hiệu lực ngay cả trên máy đã cài bản cũ.
    const val CHANNEL_ID = "dynamic_hyper_focus_channel_v2"
    private const val LEGACY_CHANNEL_ID = "dynamic_hyper_focus_channel"
    const val DEFAULT_ISLAND_PROPERTY = 1

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
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
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
     * true = máy có khả năng vẽ đảo; false = chắc chắn không.
     * Khi không đọc được thông tin (null) thì trả true để không chặn oan người dùng.
     */
    fun isFocusIslandSupported(context: Context): Boolean {
        val gate = focusGate(context)
        return when {
            !gate.isXiaomi -> false
            gate.islandFeature == false -> false
            gate.canShowFocus == false -> false
            else -> true
        }
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
        actionsMap: Map<String, Any> = emptyMap()
    ): Notification {
        // Luôn đảm bảo Channel được khởi tạo với IMPORTANCE_HIGH trước khi build
        setupChannel(context)
        // Chẩn đoán cổng Focus Island (ghi log một lần)
        focusGate(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(priority)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setSilent(isSilent)

        if (contentIntent != null) {
            builder.setContentIntent(contentIntent)
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

        // Xiaomi HyperOS quy định islandProperty là 1 (Hiển thị) hoặc 2 (Thao tác), tuyệt đối không để 0
        val targetIslandProperty = DEFAULT_ISLAND_PROPERTY
        val patchedJson = patchIslandProperty(jsonParam, targetIslandProperty)

        val notif = builder.build()
        if (isOngoing) {
            notif.flags = notif.flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }
        notif.extras.putString("miui.focus.param", patchedJson)
        return notif
    }

    private fun patchIslandProperty(json: String, property: Int): String {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val paramV2 = root.getAsJsonObject("param_v2") ?: root
            val island = paramV2.getAsJsonObject("param_island") ?: root
            island.addProperty("islandProperty", property)
            Gson().toJson(root)
        } catch (_: Exception) {
            json
        }
    }
}
