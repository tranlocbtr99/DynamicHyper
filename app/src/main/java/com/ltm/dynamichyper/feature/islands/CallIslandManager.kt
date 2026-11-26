package com.ltm.dynamichyper.feature.islands

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.*
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper
import com.ltm.dynamichyper.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Quản lý Đảo Cuộc Gọi Đến (Call Island) cho Dynamic Hyper
 * Hỗ trợ cuộc gọi đến từ Zalo, Messenger, Telegram, WhatsApp và Cuộc gọi hệ thống.
 * Cho phép Từ chối hoặc Nhận cuộc gọi ngay trên Đảo, chặn bung toàn màn hình.
 */
class CallIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager
) {
    companion object {
        const val CALL_ISLAND_ID = 8888
        private const val PIC_CALL_AVATAR = "call_avatar"
        private const val PIC_CALL_ICON = "call_icon"
        private const val TAG = "DynamicHyper_Call"

        fun isCallNotification(sbn: StatusBarNotification): Boolean {
            val notif = sbn.notification ?: return false

            // 1. Nếu rõ ràng là tin nhắn chat hoặc có cấu trúc tin nhắn -> 100% KHÔNG PHẢI cuộc gọi!
            if (notif.category == Notification.CATEGORY_MESSAGE ||
                notif.extras.containsKey(Notification.EXTRA_MESSAGES) ||
                notif.extras.containsKey(Notification.EXTRA_MESSAGING_PERSON)
            ) {
                return false
            }

            // Nếu bất kỳ nút action nào chứa RemoteInput (ô nhập tin nhắn trả lời nhanh) -> Là tin nhắn!
            val actions = notif.actions ?: emptyArray()
            val hasQuickReply = actions.any { it.remoteInputs != null && it.remoteInputs.isNotEmpty() }
            if (hasQuickReply) {
                return false
            }

            // 2. Android 12+ CallStyle notification
            val template = notif.extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
            if (template.contains("CallStyle")) return true

            if (notif.category == Notification.CATEGORY_CALL) return true

            val pkg = sbn.packageName
            val isTelecomApp = pkg == "com.android.phone" ||
                    pkg == "com.android.incallui" ||
                    pkg == "com.google.android.dialer"

            if (isTelecomApp) {
                return notif.fullScreenIntent != null || actions.isNotEmpty()
            }

            val isVoipApp = pkg == "com.zing.zalo" ||
                    pkg == "com.facebook.orca" ||
                    pkg == "org.telegram.messenger" ||
                    pkg == "com.whatsapp"

            if (isVoipApp) {
                val title = (notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "").lowercase()
                val text = (notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "").lowercase()
                val subText = (notif.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "").lowercase()
                val combined = "$title $text $subText"

                val isCallKeywords = combined.contains("cuộc gọi") ||
                        combined.contains("incoming call") ||
                        combined.contains("calling") ||
                        combined.contains("đang gọi") ||
                        combined.contains("gọi video") ||
                        combined.contains("gọi thoại") ||
                        combined.contains("cuộc gọi thoại đến") ||
                        combined.contains("cuộc gọi video đến")

                val hasCallAction = actions.any { act ->
                    val actTitle = act.title?.toString()?.lowercase() ?: ""
                    actTitle.contains("từ chối") || actTitle.contains("decline") ||
                            actTitle.contains("reject") || actTitle.contains("nghe") ||
                            actTitle.contains("ngắt") || actTitle.contains("tắt máy") ||
                            (actTitle.contains("trả lời") && isCallKeywords)
                }

                if (isCallKeywords && hasCallAction) {
                    return true
                }
                if (notif.fullScreenIntent != null && isCallKeywords) {
                    return true
                }
            }
            return false
        }
    }

    var isCallActive = false
        private set

    private var currentCallKey: String? = null

    fun getCurrentCallKey(): String? = currentCallKey

    fun handleCallNotification(sbn: StatusBarNotification) {
        scope.launch {
            if (!prefs.isCallIslandEnabled.first()) return@launch

            val notif = sbn.notification ?: return@launch
            val extras = notif.extras ?: return@launch

            val callerName = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                ?: "Cuộc gọi đến"

            val appLabel = when (sbn.packageName) {
                "com.zing.zalo" -> "Zalo"
                "com.facebook.orca" -> "Messenger"
                "org.telegram.messenger" -> "Telegram"
                "com.whatsapp" -> "WhatsApp"
                else -> "Điện thoại"
            }

            val callStatusText = "Cuộc gọi đến $appLabel..."

            // Trích xuất avatar người gọi
            val avatarBmp: Bitmap? = try {
                val largeIconObj = extras.get(Notification.EXTRA_LARGE_ICON)
                when (largeIconObj) {
                    is Bitmap -> largeIconObj
                    is android.graphics.drawable.Icon -> {
                        val d = largeIconObj.loadDrawable(context)
                        if (d is android.graphics.drawable.BitmapDrawable) d.bitmap
                        else if (d != null) {
                            val b = Bitmap.createBitmap(
                                d.intrinsicWidth.coerceAtLeast(96),
                                d.intrinsicHeight.coerceAtLeast(96),
                                Bitmap.Config.ARGB_8888
                            )
                            val c = Canvas(b)
                            d.setBounds(0, 0, c.width, c.height)
                            d.draw(c)
                            b
                        } else null
                    }
                    else -> {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            val person = extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
                            person?.icon?.loadDrawable(context)?.let { d ->
                                if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
                            }
                        } else null
                    }
                }
            } catch (_: Exception) { null }

            // Trích xuất action Từ chối và Nghe máy
            var declineAction: Notification.Action? = null
            var answerAction: Notification.Action? = null

            notif.actions?.forEach { act ->
                val title = act.title?.toString()?.lowercase() ?: ""
                if (title.contains("từ chối") || title.contains("decline") || title.contains("reject") || title.contains("tắt máy")) {
                    declineAction = act
                } else if (title.contains("trả lời") || title.contains("answer") || title.contains("nghe")) {
                    answerAction = act
                }
            }

            showCallIsland(
                key = sbn.key,
                callerName = callerName,
                callStatus = callStatusText,
                avatar = avatarBmp,
                contentIntent = notif.contentIntent,
                declineAction = declineAction,
                answerAction = answerAction
            )
        }
    }

    fun hideCallIsland() {
        if (!isCallActive) return
        isCallActive = false
        currentCallKey = null
        try {
            NotificationManagerCompat.from(context).cancel(CALL_ISLAND_ID)
        } catch (_: Exception) {}
        IdleIslandManager.setBlockerStatic("call", false)
        Log.i(TAG, "Call Island hidden")
    }

    private fun showCallIsland(
        key: String,
        callerName: String,
        callStatus: String,
        avatar: Bitmap?,
        contentIntent: PendingIntent?,
        declineAction: Notification.Action?,
        answerAction: Notification.Action?
    ) {
        try {
            isCallActive = true
            currentCallKey = key

            val root = JSONObject()
            val paramV2 = JSONObject()
            paramV2.put("protocol", FocusProtocolHelper.getFocusProtocolVersion(context))
            paramV2.put("business", "taxi")
            paramV2.put("updatable", true)
            paramV2.put("ticker", "$callerName đang gọi...")
            paramV2.put("timeout", 1440)
            paramV2.put("enableFloat", true)
            paramV2.put("isShowNotification", true)
            paramV2.put("islandFirstFloat", true)
            paramV2.put("reopen", true)

            val paramIsland = JSONObject()
            paramIsland.put("islandProperty", 2)
            paramIsland.put("islandPriority", 10) // Ưu tiên tuyệt đối cao nhất
            paramIsland.put("islandTimeout", 45) // 45 giây chuông
            paramIsland.put("islandOrder", true)
            paramIsland.put("dismissIsland", false)
            paramIsland.put("maxSize", true)
            paramIsland.put("needCloseAnimation", true)
            paramIsland.put("expandedTime", 30000) // Bung to 30 giây trong khi có chuông
            paramIsland.put("highlightColor", "#30D158") // Viền phát quang Neon Green cuộc gọi

            // bigIslandArea: Left (Avatar người gọi)
            val bigIslandArea = JSONObject()
            val leftInfo = JSONObject()
            leftInfo.put("type", 1)
            val leftPic = JSONObject()
            leftPic.put("type", 1)
            leftPic.put("pic", "miui.focus.pic_$PIC_CALL_AVATAR")
            leftInfo.put("picInfo", leftPic)
            bigIslandArea.put("imageTextInfoLeft", leftInfo)

            // bigIslandArea: Right (Tên người gọi & Trạng thái cuộc gọi)
            val rightInfo = JSONObject()
            rightInfo.put("type", 1)
            val rightPic = JSONObject()
            rightPic.put("type", 1)
            rightPic.put("pic", "miui.focus.pic_$PIC_CALL_ICON")
            rightInfo.put("picInfo", rightPic)
            val rightText = JSONObject()
            rightText.put("title", callerName)
            rightText.put("content", callStatus)
            rightText.put("showHighlightColor", true)
            rightInfo.put("textInfo", rightText)
            bigIslandArea.put("imageTextInfoRight", rightInfo)

            paramIsland.put("bigIslandArea", bigIslandArea)

            // smallIslandArea: Icon điện thoại xanh lá nhấp nháy trên đảo nhỏ
            val smallIslandArea = JSONObject()
            val smallPic = JSONObject()
            smallPic.put("type", 1)
            smallPic.put("pic", "miui.focus.pic_$PIC_CALL_ICON")
            smallIslandArea.put("picInfo", smallPic)
            paramIsland.put("smallIslandArea", smallIslandArea)

            paramV2.put("param_island", paramIsland)

            val baseInfo = JSONObject().apply {
                put("type", 1)
                put("picInfo", JSONObject().apply {
                    put("type", 1)
                    put("pic", "miui.focus.pic_$PIC_CALL_AVATAR")
                })
                put("title", callerName)
                put("content", callStatus)
            }
            paramV2.put("baseInfo", baseInfo)

            val iconTextInfo = JSONObject()
            iconTextInfo.put("title", callerName)
            iconTextInfo.put("content", callStatus)
            paramV2.put("iconTextInfo", iconTextInfo)

            val textButtons = org.json.JSONArray()
            val actionsMap = mutableMapOf<String, Any>()
            if (declineAction != null) {
                actionsMap["miui.focus.action_decline"] = declineAction
                textButtons.put(JSONObject().apply {
                    put("type", 1)
                    put("actionTitle", "Từ chối")
                    put("action", "miui.focus.action_decline")
                    put("actionIntent", "miui.focus.action_decline")
                    put("actionIntentType", 1)
                    put("actionTitleColor", "#FF453A")
                })
            }
            if (answerAction != null) {
                actionsMap["miui.focus.action_answer"] = answerAction
                textButtons.put(JSONObject().apply {
                    put("type", 1)
                    put("actionTitle", "Nghe máy")
                    put("action", "miui.focus.action_answer")
                    put("actionIntent", "miui.focus.action_answer")
                    put("actionIntentType", 1)
                    put("actionTitleColor", "#30D158")
                })
            }
            if (textButtons.length() > 0) {
                paramV2.put("textButton", textButtons)
            }

            root.put("param_v2", paramV2)
            root.put("isShowNotification", true)

            val circularAvatar = createCircularBitmap(avatar ?: createDefaultCallAvatar(callerName))
            val callIcon = createCallGlowingIcon()

            val pics = mapOf(
                PIC_CALL_AVATAR to circularAvatar,
                PIC_CALL_ICON to callIcon
            )

            val notificationBuilder = NotificationCompat.Builder(context, FocusProtocolHelper.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(callerName)
                .setContentText(callStatus)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .setSilent(false)

            if (contentIntent != null) {
                notificationBuilder.setContentIntent(contentIntent)
            }

            if (declineAction != null) {
                notificationBuilder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Từ chối",
                    declineAction.actionIntent
                )
            }

            if (answerAction != null) {
                notificationBuilder.addAction(
                    android.R.drawable.ic_menu_call,
                    "Nghe máy",
                    answerAction.actionIntent
                )
            }

            val notification = FocusProtocolHelper.buildFocusNotification(
                context = context,
                title = callerName,
                content = callStatus,
                smallIconRes = R.drawable.ic_launcher_foreground,
                jsonParam = root.toString(),
                pics = pics,
                isOngoing = true,
                onlyAlertOnce = false,
                priority = NotificationCompat.PRIORITY_MAX,
                contentIntent = contentIntent,
                actionsMap = actionsMap,
                largeIcon = circularAvatar,
                keepIslandOnLockscreen = true
            )

            // Đưa actions vào notification object
            val callActions = listOfNotNull(declineAction, answerAction)
            if (callActions.isNotEmpty()) {
                notification.actions = callActions.toTypedArray()
            }

            FocusProtocolHelper.postNotificationSafely(context, CALL_ISLAND_ID, notification)
            Log.i(TAG, "Call Island successfully posted for: $callerName")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting call island: ${e.message}", e)
        }
    }

    private fun createCircularBitmap(src: Bitmap): Bitmap {
        val size = Math.min(src.width, src.height).coerceAtLeast(96)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, null, rect, paint)
        return output
    }

    private fun createDefaultCallAvatar(name: String): Bitmap {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.color = Color.parseColor("#30D158")
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        paint.color = Color.WHITE
        paint.textSize = 42f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        val firstLetter = name.trim().firstOrNull()?.uppercase() ?: "C"
        val yPos = (canvas.height / 2f) - ((paint.descent() + paint.ascent()) / 2f)
        canvas.drawText(firstLetter, size / 2f, yPos, paint)

        return bmp
    }

    private fun createCallGlowingIcon(): Bitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Radial glow
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            Color.parseColor("#6630D158"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = shader
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, glowPaint)

        // Inner solid circle
        paint.color = Color.parseColor("#30D158")
        canvas.drawCircle(size / 2f, size / 2f, 22f, paint)

        // Telephone handset icon
        paint.color = Color.WHITE
        paint.strokeWidth = 4f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        val path = Path()
        val cx = size / 2f
        val cy = size / 2f
        path.moveTo(cx - 10f, cy + 6f)
        path.cubicTo(cx - 12f, cy - 6f, cx + 6f, cy - 12f, cx + 10f, cy - 4f)
        canvas.drawPath(path, paint)

        return bmp
    }
}
