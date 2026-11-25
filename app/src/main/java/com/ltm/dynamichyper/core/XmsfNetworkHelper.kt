package com.ltm.dynamichyper.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

object XmsfNetworkHelper {

    private const val TAG = "XmsfNetworkHelper"
    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val MAX_RETRIES = 2
    private const val RETRY_DELAY_MS = 300L

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

            for (attempt in 0 until MAX_RETRIES) {
                try {
                    val service = ShizukuUserServiceRecycler.getPrivilegedService()
                    val success = service.setPackageNetworkingEnabled(uid, enabled)
                    if (success) {
                        Log.d(TAG, "✓ Đã đặt mạng XMSF = $enabled qua Shizuku UserService (uid=$uid)")
                        return true
                    }
                } catch (e: CancellationException) {
                    return false
                } catch (e: Throwable) {
                    Log.w(TAG, "Privileged service path failed (attempt ${attempt + 1}): ${e.message}")
                    if (attempt + 1 < MAX_RETRIES) {
                        delay(RETRY_DELAY_MS)
                    }
                }
            }

            // Fallback sang lệnh Shell
            val rule = if (enabled) "allow" else "false"
            val (c1, _) = ShizukuEngine.execCommand("cmd connectivity set-chain3-enabled true")
            val (c2, _) = ShizukuEngine.execCommand("cmd connectivity set-package-networking-enabled $rule $XMSF_PACKAGE")
            return c1 == 0 && c2 == 0

        } catch (e: Throwable) {
            Log.e(TAG, "Lỗi trong setXmsfNetworkingEnabled: ${e.message}", e)
            return false
        }
    }
}
