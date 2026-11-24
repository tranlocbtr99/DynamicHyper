package com.ltm.dynamichyper

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.service.KeepAliveService

class DynamicHyperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "DynamicHyperApp initialized successfully")
        AppLogger.i(TAG, "DynamicHyperApp khởi động phiên bản Beta thành công")

        // Khởi động KeepAliveService (foreground special-use) để giữ NotificationListenerService
        // và Đảo Chờ không bị hệ thống thu hồi trong nền.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                startForegroundService(Intent(this, KeepAliveService::class.java))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Không thể khởi động KeepAliveService: ${e.message}")
            }
        } else {
            startService(Intent(this, KeepAliveService::class.java))
        }

        // Bắt lỗi Crash toàn cục để lưu vào nhật ký chẩn đoán
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLogger.e("FATAL_CRASH", "Crash trên luồng ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val TAG = "DynamicHyper"
        lateinit var instance: DynamicHyperApp
            private set
    }
}

