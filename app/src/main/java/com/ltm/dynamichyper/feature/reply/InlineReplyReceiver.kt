package com.ltm.dynamichyper.feature.reply

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

/**
 * Receiver nhận sự kiện khi người dùng nhấn nút "Trả Lời" trên Dynamic Island
 */
class InlineReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác' để dùng tính năng Trả Lời Nhanh",
                Toast.LENGTH_LONG
            ).show()
            val settingsIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(settingsIntent)
            return
        }

        val serviceIntent = Intent(context, InlineReplyService::class.java).apply {
            putExtras(intent)
        }

        try {
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Toast.makeText(context, "Không thể mở khung trả lời: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

