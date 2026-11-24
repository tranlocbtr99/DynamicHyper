package com.ltm.dynamichyper

import android.app.Application
import android.util.Log
import com.ltm.dynamichyper.core.AppLogger

class DynamicHyperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "DynamicHyperApp initialized successfully")
        AppLogger.i(TAG, "DynamicHyperApp khởi động phiên bản Beta thành công")

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

