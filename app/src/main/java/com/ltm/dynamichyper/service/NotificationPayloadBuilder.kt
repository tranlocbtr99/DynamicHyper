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
import com.ltm.dynamichyper.feature.reply.InlineReplyActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bộ dựng Notification & JSON Focus Protocol cho Tin nhắn, Nhạc/Video, Báo thức, Tiến trình & OTP.
 */
object NotificationPayloadBuilder {

    fun buildAppFocusNotification(
        context: Context,
        sbn: StatusBarNotification,
        title: String,
        text: String,
        isMedia: Boolean,
        isAlarm: Boolean = false,
        progressPercent: Int? = null,
        isNewMessage: Boolean,
        isPaletteEnabled: Boolean,
        isGradientGlow: Boolean,
        dynamicIslandId: Int,
        showInNotificationShade: Boolean = true
    ): Notification {
        val notif = sbn.notification
        val rawAvatarBmp = extractCircularAvatar(context, sbn)
        val highlightColor = resolveHighlightColor(
            packageName = sbn.packageName,
            avatarBmp = rawAvatarBmp,
            isMedia = isMedia,
            isAlarm = isAlarm,
            isPaletteEnabled = isPaletteEnabled,
            isGradientGlow = isGradientGlow
        )
        val avatarBmp = if (isGradientGlow || isAlarm) {
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
        extractSmartAndReplyActions(
            context = context,
            sbn = sbn,
            title = title,
            text = text,
            isMedia = isMedia,
            isAlarm = isAlarm,
            actionsMap = actionsMap,
            textButtonsArray = textButtonsArray
        )

        // Rút gọn nhãn ở viên thuốc thu gọn (bigIslandArea.imageTextInfoRight) để KHÔNG kéo dài đảo
        val pillLabel = when {
            isAlarm -> "⏰ Báo thức"
            progressPercent != null -> "$progressPercent%"
            else -> compactLabel(title, if (isMedia) 12 else 10)
        }

        val shouldFloatOnArrival = (isNewMessage || isAlarm) && !isMedia && progressPercent == null
        val expandedSec = when {
            isAlarm -> 6
            isNewMessage -> 3
            else -> 0
        }

        val isLocked = FocusProtocolHelper.isKeyguardLocked(context)
        val paramIsland = JSONObject().apply {
            put("islandProperty", if (isLocked) 0 else 1)
            put("islandPriority", if (isAlarm) 5 else 3)
            put("islandTimeout", 86400000)
            put("islandOrder", false)
            put("dismissIsland", false)
            put("maxSize", false)
            put("needCloseAnimation", true)
            put("expandedTime", expandedSec)
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
                        put("title", pillLabel)
                        put("content", "")
                        put("showHighlightColor", isGradientGlow || isAlarm || (isMedia && isPaletteEnabled))
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
            put("enableFloat", shouldFloatOnArrival)
            put("isShowNotification", showInNotificationShade)
            put("islandFirstFloat", shouldFloatOnArrival)
            put("reopen", isAlarm || isNewMessage)
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
            put("isShowNotification", showInNotificationShade)
        }.toString()

        val tapIntent = Intent(context, IslandTapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(IslandTapActivity.EXTRA_SBN_KEY, sbn.key)
            putExtra(IslandTapActivity.EXTRA_ISLAND_ID, dynamicIslandId)
            putExtra(IslandTapActivity.EXTRA_ORIGINAL_INTENT, notif.contentIntent)
            putExtra(IslandTapActivity.EXTRA_TARGET_PKG, sbn.packageName)
            putExtra(IslandTapActivity.EXTRA_IS_MEDIA, isMedia || isAlarm || progressPercent != null)
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
            isOngoing = isMedia || isAlarm || progressPercent != null,
            onlyAlertOnce = !isNewMessage && !isAlarm,
            contentIntent = wrappedContentIntent,
            actionsMap = actionsMap,
            largeIcon = avatarBmp,
            keepIslandOnLockscreen = isAlarm || isNewMessage,
            notificationId = dynamicIslandId
        )
    }

    private fun compactLabel(raw: String, maxLen: Int): String {
        val clean = raw.trim().replace("\n", " ")
        if (clean.length <= maxLen) return clean
        return clean.take(maxLen - 1) + "…"
    }

    private fun extractSmartAndReplyActions(
        context: Context,
        sbn: StatusBarNotification,
        title: String,
        text: String,
        isMedia: Boolean,
        isAlarm: Boolean,
        actionsMap: MutableMap<String, Any>,
        textButtonsArray: JSONArray
    ) {
        val maxButtons = if (isMedia || isAlarm) 3 else 2

        if (!isMedia && !isAlarm) {
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
                val nativeAction = Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                    "Sao chép $otpCode",
                    copyPendingIntent
                ).build()
                actionsMap[actionKey] = nativeAction
                textButtonsArray.put(JSONObject().apply {
                    put("type", 1)
                    put("actionTitle", "Sao chép $otpCode")
                    put("action", actionKey)
                    put("actionIntent", actionKey)
                    put("actionIntentType", 1)
                    put("actionTitleColor", "#30D158")
                })
            }
        }

        val rawActions = sbn.notification.actions ?: return
        for ((index, act) in rawActions.withIndex()) {
            if (textButtonsArray.length() >= maxButtons) break
            val actionTitle = act.title?.toString()?.trim() ?: ""
            if (actionTitle.isBlank()) continue

            val remoteInputs = act.remoteInputs
            if (!isMedia && !isAlarm && remoteInputs != null && remoteInputs.isNotEmpty() && act.actionIntent != null) {
                val resultKey = remoteInputs[0].resultKey
                val replyIntent = Intent(context, InlineReplyActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    putExtra("pending_intent", act.actionIntent)
                    putExtra("result_key", resultKey)
                    putExtra("package_name", sbn.packageName)
                    putExtra("sender_title", title)
                }
                val replyPendingIntent = PendingIntent.getActivity(
                    context,
                    (sbn.key + "_reply").hashCode(),
                    replyIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                val actionKey = "miui.focus.action_reply"
                val nativeAction = Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                    actionTitle.ifEmpty { "Trả lời" },
                    replyPendingIntent
                ).build()
                actionsMap[actionKey] = nativeAction
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
                val nativeAction = Notification.Action.Builder(
                    act.getIcon() ?: Icon.createWithResource(context, R.drawable.ic_launcher_foreground),
                    actionTitle,
                    act.actionIntent
                ).build()
                actionsMap[actionKey] = nativeAction
                val colorHex = when {
                    isAlarm && (actionTitle.contains("tắt", true) || actionTitle.contains("dismiss", true) || actionTitle.contains("stop", true)) -> "#FF453A"
                    isAlarm -> "#FFD60A"
                    else -> "#FFFFFF"
                }
                textButtonsArray.put(JSONObject().apply {
                    put("type", 1)
                    put("actionTitle", actionTitle)
                    put("action", actionKey)
                    put("actionIntent", actionKey)
                    put("actionIntentType", 1)
                    put("actionTitleColor", colorHex)
                })
            }
        }
    }

    private fun resolveHighlightColor(
        packageName: String,
        avatarBmp: Bitmap,
        isMedia: Boolean,
        isAlarm: Boolean,
        isPaletteEnabled: Boolean,
        isGradientGlow: Boolean
    ): String = when {
        isAlarm -> "#FF453A"
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
            when (val largeIconObj = extras?.get(Notification.EXTRA_LARGE_ICON)
                ?: extras?.get(Notification.EXTRA_LARGE_ICON_BIG)) {
                is Bitmap -> largeIconObj
                is Icon -> loadIconBitmap(context, largeIconObj)
                else -> extractMessagingStyleOrPersonAvatar(context, extras)
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

    /**
     * Trích xuất Avatar của người gửi từ MessagingStyle (`EXTRA_MESSAGES` -> `sender_person`,
     * `EXTRA_HISTORIC_MESSAGES` -> `sender_person`, `EXTRA_CALL_PERSON`, `EXTRA_MESSAGING_PERSON`)
     * khi thông báo không gắn `largeIcon` ở cấp độ root (như Zalo, Telegram, Google Messages, WhatsApp).
     */
    @Suppress("DEPRECATION")
    private fun extractMessagingStyleOrPersonAvatar(context: Context, extras: android.os.Bundle?): Bitmap? {
        extras ?: return null
        try {
            // 1. Kiểm tra mảng tin nhắn EXTRA_MESSAGES và EXTRA_HISTORIC_MESSAGES từ cuối lên đầu
            val messageArrays = listOfNotNull(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES),
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
                } else null
            )
            for (arr in messageArrays) {
                for (i in arr.indices.reversed()) {
                    val msgBundle = arr[i] as? android.os.Bundle ?: continue
                    val senderPerson = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        msgBundle.getParcelable("sender_person", android.app.Person::class.java)
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        msgBundle.getParcelable("sender_person") as? android.app.Person
                    } else null
                    val iconBmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        senderPerson?.icon?.let { loadIconBitmap(context, it) }
                    } else null
                    if (iconBmp != null) return iconBmp
                }
            }

            // 2. Kiểm tra EXTRA_CALL_PERSON & EXTRA_MESSAGING_PERSON (messagingUser)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
                    ?: extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, android.app.Person::class.java)
                person?.icon?.let { loadIconBitmap(context, it) }?.let { return it }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val person = (extras.getParcelable(Notification.EXTRA_CALL_PERSON) as? android.app.Person)
                    ?: (extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON) as? android.app.Person)
                person?.icon?.let { loadIconBitmap(context, it) }?.let { return it }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun loadIconBitmap(context: Context, icon: Icon): Bitmap? {
        val drawable = icon.loadDrawable(context) ?: return null
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bmp = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(96),
            drawable.intrinsicHeight.coerceAtLeast(96),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
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
