package com.ltm.dynamichyper.service

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * Activity trong suốt xử lý sự kiện khi người dùng chạm vào thẻ Đảo Động đã bung để mở xem thông báo.
 * Giống cơ chế IslandTapActivity của HyperBridge:
 * 1. Mở ngay PendingIntent gốc của ứng dụng (Zalo, Messenger, Telegram...) từ Foreground để không bị Android chặn Background Activity Launch.
 * 2. Gửi broadcast nội bộ ACTION_ISLAND_CLICKED về DynamicNotificationService để đóng Đảo thông báo vừa xem
 *    và tự động khôi phục lại Đảo Chờ 24/7.
 */
class IslandTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val originalIntent: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ORIGINAL_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ORIGINAL_INTENT)
        }

        try {
            if (originalIntent != null) {
                if (Build.VERSION.SDK_INT >= 34) {
                    val options = ActivityOptions.makeBasic().apply {
                        setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    }
                    originalIntent.send(this, 0, null, null, null, null, options.toBundle())
                } else {
                    originalIntent.send()
                }
            }
        } catch (e: Throwable) {
            Log.w("IslandTapActivity", "Không thể mở PendingIntent gốc: ${e.message}")
            // Fallback: Mở ứng dụng theo packageName nếu PendingIntent đã bị hủy
            val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
            if (!targetPkg.isNullOrBlank()) {
                try {
                    packageManager.getLaunchIntentForPackage(targetPkg)?.let { launchIntent ->
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                } catch (_: Throwable) {}
            }
        }

        sendBroadcast(Intent(ACTION_ISLAND_CLICKED).apply {
            setPackage(packageName)
            putExtra(EXTRA_SBN_KEY, intent.getStringExtra(EXTRA_SBN_KEY))
            putExtra(EXTRA_ISLAND_ID, intent.getIntExtra(EXTRA_ISLAND_ID, -1))
        })

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val ACTION_ISLAND_CLICKED = "com.ltm.dynamichyper.ACTION_ISLAND_CLICKED"
        const val EXTRA_SBN_KEY = "sbn_key"
        const val EXTRA_ISLAND_ID = "island_id"
        const val EXTRA_ORIGINAL_INTENT = "original_intent"
        const val EXTRA_TARGET_PKG = "target_pkg"
    }
}
