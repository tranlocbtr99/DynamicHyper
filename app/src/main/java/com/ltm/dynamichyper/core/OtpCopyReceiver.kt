package com.ltm.dynamichyper.core

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Receiver nhận sự kiện bấm nút sao chép OTP từ Đảo Động
 */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val otp = intent.getStringExtra("otp_code") ?: return
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OTP Code", otp)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Đã sao chép mã OTP: $otp", Toast.LENGTH_SHORT).show()
            AppLogger.i("DynamicHyper:OTP", "✓ Đã sao chép mã OTP thành công: $otp")
        } catch (e: Throwable) {
            AppLogger.e("DynamicHyper:OTP", "Không thể sao chép OTP: ${e.message}")
        }
    }
}
