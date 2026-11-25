package com.ltm.dynamichyper.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object XmsfNetworkHelper {

    private const val TAG = "XmsfNetworkHelper"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 200L
    private const val CALL_TIMEOUT_MS = 1500L

    /**
     * Đánh dấu XMSF đã bị khóa trong phiên chạy hiện tại để các lần gửi thông báo sau
     * có thể bắn thẳng 0ms mà không cần gọi lại IPC hay giữ khóa chờ.
     */
    @Volatile
    var isBlockedInCurrentSession: Boolean = false
        private set

    fun markBlockedInSession() {
        isBlockedInCurrentSession = true
    }

    /**
     * Đảm bảo XMSF đã bị khóa mạng vĩnh viễn.
     * Nếu trong phiên này đã khóa thành công rồi thì trả về true ngay lập tức (0ms).
     */
    suspend fun ensureXmsfBlocked(context: Context): Boolean {
        if (isBlockedInCurrentSession) return true
        val ok = setXmsfNetworkingEnabled(context, false)
        if (ok) {
            isBlockedInCurrentSession = true
        }
        return ok
    }

    suspend fun setXmsfNetworkingEnabled(context: Context, enabled: Boolean): Boolean {
        try {
            val pm = context.packageManager
            val uid = try {
                pm.getPackageUid(XMSF_PACKAGE, 0)
            } catch (e: Exception) {
                Log.w(TAG, "XMSF package not found (UID lookup failed)")
                return false
            }

            if (!ShizukuEngine.hasShizukuPermission()) {
                Log.w(TAG, "Shizuku permission not granted")
                return false
            }

            // 1. Thử khóa nhanh qua ShizukuHook IPC trực tiếp (không cần chờ UserService bind)
            try {
                if (ShizukuHook.setPackageNetworkingEnabled(uid, enabled)) {
                    if (!enabled) isBlockedInCurrentSession = true
                    Log.d(TAG, "✓ Đã đặt mạng XMSF = $enabled qua ShizukuHook IPC (uid=$uid)")
                    return true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "ShizukuHook direct IPC failed: ${e.message}")
            }

            // 2. Thử qua Privileged UserService với timeout ngắn để không bao giờ treo khi tắt nóng Wireless Debugging
            val userServiceResult = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                for (attempt in 0 until MAX_RETRIES) {
                    try {
                        val service = ShizukuUserServiceRecycler.getPrivilegedService()
                        val success = service.setPackageNetworkingEnabled(uid, enabled)
                        if (success) {
                            if (!enabled) isBlockedInCurrentSession = true
                            Log.d(TAG, "✓ Đã đặt mạng XMSF = $enabled qua Shizuku UserService (uid=$uid)")
                            return@withTimeoutOrNull true
                        }
                    } catch (e: CancellationException) {
                        return@withTimeoutOrNull false
                    } catch (e: Throwable) {
                        Log.w(TAG, "Privileged service path failed (attempt ${attempt + 1}): ${e.message}")
                        if (attempt + 1 < MAX_RETRIES) {
                            delay(RETRY_DELAY_MS)
                        }
                    }
                }
                false
            }
            if (userServiceResult == true) return true

            // 3. Fallback sang lệnh Shell
            val rule = if (enabled) "allow" else "false"
            val (c1, _) = ShizukuEngine.execCommand("cmd connectivity set-chain3-enabled true")
            val (c2, _) = ShizukuEngine.execCommand("cmd connectivity set-package-networking-enabled $rule $XMSF_PACKAGE")
            val ok = c1 == 0 || c2 == 0
            if (ok && !enabled) isBlockedInCurrentSession = true
            return ok

        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi trong setXmsfNetworkingEnabled: ${e.message}", e)
            return false
        }
    }
}
