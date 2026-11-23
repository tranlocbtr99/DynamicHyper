package com.ltm.dynamichyper.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Quản lý kích hoạt qua Gỡ lỗi không dây nội bộ (Local Wireless ADB Pairing & Activation)
 * Giúp người dùng kích hoạt app trực tiếp mà KHÔNG CẦN tải app Shizuku bên thứ ba.
 */
object LocalAdbEngine {
    private const val TAG = "DynamicHyper:LocalAdb"

    /**
     * Kiểm tra xem cổng ADB nội bộ có đang mở hay không (e.g. 5555 hoặc cổng ngẫu nhiên)
     */
    suspend fun checkLocalAdbPort(port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 1000)
                true
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Thực thi kịch bản tối ưu hóa trực tiếp từ ADB shell hoặc hướng dẫn người dùng
     */
    fun getAdbCommandScript(context: Context): String {
        val pkg = context.packageName
        return """
            cmd notification allow_listener $pkg/com.ltm.dynamichyper.service.DynamicNotificationService
            dumpsys deviceidle whitelist +$pkg
            cmd appops set $pkg AUTO_START allow
            cmd appops set $pkg 10008 allow
            cmd appops set $pkg 10020 allow
            cmd appops set $pkg 10021 allow
            pm grant $pkg android.permission.POST_NOTIFICATIONS
        """.trimIndent()
    }
}

