package com.ltm.dynamichyper.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.service.notification.StatusBarNotification
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.core.OtpCopyReceiver
import com.ltm.dynamichyper.core.PaletteHelper
import com.ltm.dynamichyper.core.SmartActionsExtractor
import com.ltm.dynamichyper.feature.reply.InlineReplyReceiver
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bộ dựng Notification & JSON Focus Protocol cho các thông báo ứng dụng (Tin nhắn, Nhạc, OTP).
 * Tách riêng khỏi DynamicNotificationService để mã nguồn gọn gàng, dễ bảo trì.
 */
object NotificationPayloadBuilder {

    fun buildAppFocusNotification(
        context: Context,
        sbn: StatusBarNotification,
        title: String,
        text: String,
        isMedia: Boolean,
        isNewMessage: Boolean,
        isPaletteEnabled: Boolean,
        isGradientGlow: Boolean,
        dynamicIslandId: Int
    ): Notification {
        val notif = sbn.notification
        val rawAvatarBmp = extractCircularAvatar(context, sbn)
        val highlightColor = resolveHighlightColor(
            packageName = sbn.packageName,
            avatarBmp = rawAvatarBmp,
            isMedia = isMedia,
            isPaletteEnabled = isPaletteEnabled,
            isGradientGlow = isGradientGlow
        )
        val avatarBmp = if (isGradientGlow) {
            applyGradientGlowRing(rawAvatarBmp, highlightColor)
        } else {
            rawAvatarBmp
        }
        val picKey = "notif_avatar"
        val pics = mapOf(
            picKey to avatarBmp,
            "hidden_pixel" to Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        )

        val actionsMap = mutableMapOf<String, Any>()
        val textButtonsArray = JSONArray()
        extractSmartAndReplyActions(context, sbn, title, text, actionsMap, textButtonsArray)

        val paramIsland = JSONObject().apply {
            put("islandProperty", 1)
            put("islandPriority", 2)
            put("islandTimeout", 86400000)
            put("islandOrder", false)
            put("dismissIsland", false)
            put("maxSize", false)
            put("needCloseAnimation", true)
            put("expandedTime", if (isNewMessage) 3 else 0)
            put("highlightColor", highlightColor)
            put("bigIslandArea", JSONObject().apply {
                put("imageTextInfoLeft", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_$picKey")
                    })
                    put("textInfo", JSONObject().apply {
                        put("title", "")
                        put("content", "")
                        put("showHighlightColor", false)
                    })
                })
                put("imageTextInfoRight", JSONObject().apply {
                    put("type", 1)
                    put("picInfo", JSONObject().apply {
                        put("type", 1)
                        put("pic", "miui.focus.pic_hidden_pixel")
                    })
                    put("textInfo", JSONObject().apply {
                        put("title", title)
                        put("content", text)
                        put("showHighlightColor", isGradientGlow || (isMedia && isPaletteEnabled))
                    })
                })
            })
            put("smallIslandArea", JSONObject().apply {
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_$picKey")
                })
            })
        }

        val paramV2 = JSONObject().apply {
            put("protocol", 1)
            put("business", "bridge_${sbn.packageName}")
            put("updatable", true)
            put("ticker", if (text.isNotBlank()) "$title: $text" else title)
            put("timeout", 86400000)
            put("enableFloat", true)
            put("isShowNotification", true)
            put("islandFirstFloat", isNewMessage)
            put("reopen", isNewMessage)
            put("param_island", paramIsland)
            put("baseInfo", JSONObject().apply {
                put("type", 1)
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_$picKey")
                })
                put("title", title)
                put("content", text)
            })
            put("iconTextInfo", JSONObject().apply {
                put("title", title)
                put("content", text)
            })
            if (textButtonsArray.length() > 0) {
                put("textButton", textButtonsArray)
            }
        }

        val jsonParam = JSONObject().apply {
            put("param_v2", paramV2)
            put("isShowNotification", true)
        }.toString()

        val tapIntent = Intent(context, IslandTapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(IslandTapActivity.EXTRA_SBN_KEY, sbn.key)
            putExtra(IslandTapActivity.EXTRA_ISLAND_ID, dynamicIslandId)
            putExtra(IslandTapActivity.EXTRA_ORIGINAL_INTENT, notif.contentIntent)
            putExtra(IslandTapActivity.EXTRA_TARGET_PKG, sbn.packageName)
        }
        val wrappedContentIntent = PendingIntent.getActivity(
            context,
            dynamicIslandId,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return FocusProtocolHelper.buildFocusNotification(
            context = context,
            title = title,
            content = text,
            smallIconRes = R.drawable.ic_launcher_foreground,
            jsonParam = jsonParam,
            pics = pics,
            isOngoing = isMedia,
            onlyAlertOnce = !isNewMessage,
            contentIntent = if (!isMedia) wrappedContentIntent else notif.contentIntent,
            actionsMap = actionsMap,
            largeIcon = avatarBmp
        )
    }

    private fun extractSmartAndReplyActions(
        context: Context,
        sbn: StatusBarNotification,
        title: String,
        text: String,
        actionsMap: MutableMap<String, Any>,
        textButtonsArray: JSONArray
    ) {
        val otpCode = SmartActionsExtractor.findOtpCode("$title $text")
        if (otpCode != null) {
            val copyIntent = Intent(context, OtpCopyReceiver::class.java).apply {
                action = "com.ltm.dynamichyper.ACTION_COPY_OTP"
                putExtra("otp_code", otpCode)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(
                context,
                otpCode.hashCode(),
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val actionKey = "miui.focus.action_copy_otp"
            actionsMap[actionKey] = copyPendingIntent
            textButtonsArray.put(JSONObject().apply {
                put("type", 1)
                put("actionTitle", "Sao chép $otpCode")
                put("action", actionKey)
                put("actionIntent", actionKey)
                put("actionIntentType", 1)
                put("actionTitleColor", "#30D158")
            })
        }

        val rawActions = sbn.notification.actions ?: return
        for ((index, act) in rawActions.withIndex()) {
            val actionTitle = act.title?.toString() ?: ""
            if (actionTitle.isBlank()) continue

            val remoteInputs = act.remoteInputs
            if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                val resultKey = remoteInputs[0].resultKey
                val replyIntent = Intent(context, InlineReplyReceiver::class.java).apply {
                    putExtra("pending_intent", act.actionIntent)
                    putExtra("result_key", resultKey)
                    putExtra("package_name", sbn.packageName)
                    putExtra("sender_title", title)
                }
                val replyPendingIntent = PendingIntent.getBroadcast(
                    context,
                    (sbn.key + "_reply").hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                val actionKey = "miui.focus.action_reply"
                actionsMap[actionKey] = replyPendingIntent
                textButtonsArray.put(JSONObject().apply {
                    put("type", 1)
                    put("actionTitle", actionTitle.ifEmpty { "Trả lời" })
                    put("action", actionKey)
                    put("actionIntent", actionKey)
                    put("actionIntentType", 1)
                    put("actionTitleColor", "#FFFFFF")
                })
            } else if (act.actionIntent != null) {
                val actionKey = "miui.focus.action_$index"
                actionsMap[actionKey] = act.actionIntent
                textButtonsArray.put(JSONObject().apply {
                    put("type", 1)
                    put("actionTitle", actionTitle)
                    put("action", actionKey)
                    put("actionIntent", actionKey)
                    put("actionIntentType", 1)
                    put("actionTitleColor", "#FFFFFF")
                })
            }
        }
    }

    private fun resolveHighlightColor(
        packageName: String,
        avatarBmp: Bitmap,
        isMedia: Boolean,
        isPaletteEnabled: Boolean,
        isGradientGlow: Boolean
    ): String = when {
        isMedia && isPaletteEnabled -> PaletteHelper.extractDominantColorHex(avatarBmp)
        isGradientGlow -> when (packageName) {
            "com.zing.zalo" -> "#0088FF"
            "com.facebook.orca" -> "#00C6FF"
            "org.telegram.messenger" -> "#2AABEE"
            "com.whatsapp" -> "#25D366"
            "com.google.android.apps.messaging", "com.android.mms" -> "#FF2D55"
            else -> if (isMedia) "#BF5AF2" else "#5E5CE6"
        }
        else -> if (isMedia) "#BF5AF2" else "#0A84FF"
    }

    private fun extractCircularAvatar(context: Context, sbn: StatusBarNotification): Bitmap {
        val extras = sbn.notification.extras
        @Suppress("DEPRECATION")
        val rawAvatarBmp: Bitmap? = try {
            when (val largeIconObj = extras?.get(Notification.EXTRA_LARGE_ICON)) {
                is Bitmap -> largeIconObj
                is Icon -> {
                    val drawable = largeIconObj.loadDrawable(context)
                    if (drawable is BitmapDrawable) {
                        drawable.bitmap
                    } else if (drawable != null) {
                        val bmp = Bitmap.createBitmap(
                            drawable.intrinsicWidth.coerceAtLeast(96),
                            drawable.intrinsicHeight.coerceAtLeast(96),
                            Bitmap.Config.ARGB_8888
                        )
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    } else null
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        if (rawAvatarBmp != null) return createCircularBitmap(rawAvatarBmp)

        return try {
            val appIconDrawable = context.packageManager.getApplicationIcon(sbn.packageName)
            val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            appIconDrawable.setBounds(0, 0, 96, 96)
            appIconDrawable.draw(canvas)
            createCircularBitmap(bmp)
        } catch (_: Exception) {
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        }
    }

    private fun createCircularBitmap(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height).coerceAtLeast(96)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawOval(RectF(rect), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, null, rect, paint)
        return output
    }

    private fun applyGradientGlowRing(src: Bitmap, accentHex: String): Bitmap {
        val size = 108
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val center = size / 2f

        val primaryColor = try {
            Color.parseColor(accentHex)
        } catch (_: Exception) {
            Color.parseColor("#5E5CE6")
        }
        val secondaryColor = Color.parseColor("#00F2FE")

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                center, center, center,
                intArrayOf(primaryColor, Color.TRANSPARENT),
                floatArrayOf(0.75f, 1.0f),
                Shader.TileMode.CLAMP
            )
            alpha = 180
        }
        canvas.drawCircle(center, center, center, glowPaint)

        val inset = 8f
        canvas.drawBitmap(src, null, RectF(inset, inset, size - inset, size - inset), Paint(Paint.ANTI_ALIAS_FLAG))

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            shader = SweepGradient(
                center, center,
                intArrayOf(primaryColor, secondaryColor, Color.parseColor("#BF5AF2"), primaryColor),
                null
            )
        }
        canvas.drawCircle(center, center, center - 7f, ringPaint)
        return out
    }
}
