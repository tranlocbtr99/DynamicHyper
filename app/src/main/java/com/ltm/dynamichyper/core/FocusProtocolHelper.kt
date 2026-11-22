package com.ltm.dynamichyper.core

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ltm.dynamichyper.R

object FocusProtocolHelper {

    const val CHANNEL_ID = "dynamic_hyper_focus_channel"
    const val KEYGUARD_ISLAND_PROPERTY = 0
    const val UNLOCKED_ISLAND_PROPERTY = 1

    fun setupChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
        isOngoing: Boolean = true
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(isOngoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)

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

        // Dynamically adjust islandProperty for Lockscreen / Unlocked
        val targetIslandProperty = if (isKeyguardLocked(context)) KEYGUARD_ISLAND_PROPERTY else UNLOCKED_ISLAND_PROPERTY
        val patchedJson = patchIslandProperty(jsonParam, targetIslandProperty)

        val notif = builder.build()
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

