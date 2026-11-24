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
        AppLogger.i(TAG, "DynamicHyperApp khởi động phiên bản v2.0.3-beta thành công")

        // Chú ý Android 12+: Tuyệt đối không gọi startForegroundService trong Application.onCreate()
        // vì sẽ gây ra ForegroundServiceStartNotAllowedException và khiến app bị crash/buộc dừng khi mở lần đầu.
        // KeepAliveService sẽ được khởi chạy an toàn từ MainActivity.onResume() khi cửa sổ app đã sẵn sàng.

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

