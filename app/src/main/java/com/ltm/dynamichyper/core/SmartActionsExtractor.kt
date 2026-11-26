package com.ltm.dynamichyper.core

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.util.regex.Pattern

/**
 * Trích xuất các hành động thông minh (Smart Actions) và mã xác thực OTP từ thông báo,
 * gắn trực tiếp vào các nút bấm của Đảo Động bung to.
 */
object SmartActionsExtractor {

    private val OTP_CONTEXT_KEYWORD = Regex("(?i)(otp|mã\\s*xác\\s*thực|ma\\s*xac\\s*thuc|verification|mã\\s*gd|ma\\s*gd|mã\\s*bảo\\s*mật|passcode|mã\\s*kích\\s*hoạt|code\\s*(?:is|là|:))")

    private val OTP_PATTERNS = listOf(
        Pattern.compile("(?i)(?:otp|mã\\s*xác\\s*thực|ma\\s*xac\\s*thuc|verification\\s*code|mã\\s*gd|ma\\s*gd|mã\\s*bảo\\s*mật|passcode|mã\\s*kích\\s*hoạt|code)\\s*(?:là|của\\s*bạn\\s*là|is|:|-)?\\s*([0-9]{4,8})\\b"),
        Pattern.compile("(?i)\\b([0-9]{4,8})\\s*(?:là\\s*mã\\s*xác\\s*thực|là\\s*mã\\s*otp|is\\s*your\\s*(?:verification\\s*)?code)\\b")
    )

    data class ExtractedAction(
        val title: String,
        val pendingIntent: PendingIntent,
        val isOtp: Boolean = false,
        val otpCode: String? = null
    )

    /**
     * Tìm mã OTP theo ngữ cảnh chặt chẽ (tránh bắt nhầm số tài khoản/năm)
     */
    fun findOtpCode(text: String): String? {
        if (text.isBlank()) return null
        if (!OTP_CONTEXT_KEYWORD.containsMatchIn(text)) return null
        for (pattern in OTP_PATTERNS) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                val candidate = matcher.group(1) ?: matcher.group(0)
                if (candidate.length in 4..8) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * Trích xuất danh sách hành động từ Notification.actions
     */
    fun extractActions(context: Context, notification: Notification, text: String): List<ExtractedAction> {
        val result = mutableListOf<ExtractedAction>()

        // 1. Kiểm tra OTP để tạo hành động "Sao Chép OTP" ưu tiên
        val otp = findOtpCode(text)
        if (otp != null) {
            val copyIntent = createCopyOtpIntent(context, otp)
            result.add(ExtractedAction("Sao chép $otp", copyIntent, isOtp = true, otpCode = otp))
        }

        // 2. Trích xuất các nút bấm gốc của thông báo
        val actions = notification.actions
        if (actions != null) {
            for (action in actions) {
                if (action.actionIntent != null && !action.title.isNullOrBlank()) {
                    // Nếu đã có nút sao chép OTP thì tránh trùng lặp
                    if (otp != null && action.title.contains("copy", ignoreCase = true)) continue
                    result.add(ExtractedAction(action.title.toString(), action.actionIntent))
                    if (result.size >= 2) break // Giới hạn 2 nút để hiển thị đẹp mắt trên Đảo
                }
            }
        }

        return result
    }

    private fun createCopyOtpIntent(context: Context, otp: String): PendingIntent {
        val intent = Intent(context, OtpCopyReceiver::class.java).apply {
            action = "com.ltm.dynamichyper.ACTION_COPY_OTP"
            putExtra("otp_code", otp)
        }
        return PendingIntent.getBroadcast(
            context,
            otp.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
