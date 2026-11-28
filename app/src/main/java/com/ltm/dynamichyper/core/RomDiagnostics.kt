package com.ltm.dynamichyper.core

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Thu thập thông tin ROM / môi trường để đưa vào đầu nhật ký chẩn đoán.
 * Mọi trường đều đọc an toàn: lỗi một trường không làm hỏng cả báo cáo.
 */
object RomDiagnostics {

    private const val XMSF_PACKAGE = "com.xiaomi.xmsf"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun prop(key: String): String {
        try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getDeclaredMethod("get", String::class.java, String::class.java)
            get.isAccessible = true
            val v = (get.invoke(null, key, "") as? String).orEmpty()
            if (v.isNotBlank()) return v
        } catch (_: Throwable) {
        }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", key))
            p.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
        } catch (_: Throwable) {
            ""
        }
    }

    fun isChinaRom(): Boolean {
        if (prop("ro.miui.region").equals("CN", ignoreCase = true)) return true
        return Build.DISPLAY.contains("CNXM", ignoreCase = true) ||
            Build.FINGERPRINT.contains("CNXM", ignoreCase = true)
    }

    fun isXmsfInstalled(context: Context): Boolean = isInstalled(context, XMSF_PACKAGE)

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageUid(pkg, 0)
        true
    } catch (_: Throwable) {
        false
    }

    private inline fun <T> safe(block: () -> T): String = try {
        block().toString()
    } catch (e: Throwable) {
        "không đọc được (${e.javaClass.simpleName})"
    }

    /** Đường gửi mà app sẽ dùng với cấu hình hiện tại. */
    fun predictedPath(context: Context): String = when {
        !isXmsfInstalled(context) -> "trực tiếp (máy không có XMSF)"
        ShizukuEngine.hasShizukuPermission() -> "khóa XMSF vĩnh viễn (Shizuku đang bật)"
        ShizukuEngine.isIsolatedInCurrentBoot(context) -> "chế độ độc lập (XMSF đã khóa trong phiên boot này, Shizuku tắt)"
        ShizukuEngine.isOneTimeOptimizationActive(context) -> "chế độ độc lập sau khởi động lại (NetPolicy/AppOps, Shizuku tắt)"
        else -> "trực tiếp (chưa từng kích hoạt Shizuku)"
    }

    fun snapshot(context: Context): String {
        val sb = StringBuilder()
        val cn = isChinaRom()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        sb.append("--- ROM ---\n")
        sb.append("Loại ROM (suy ra từ region/CNXM): ${if (cn) "China ROM" else "Global/khác"}\n")
        sb.append("ro.miui.region: ${prop("ro.miui.region").ifBlank { "(trống)" }}\n")
        sb.append("ro.mi.os.version.name: ${prop("ro.mi.os.version.name").ifBlank { "(trống)" }}\n")
        sb.append("ro.miui.ui.version.name: ${prop("ro.miui.ui.version.name").ifBlank { "(trống)" }}\n")
        sb.append("Tên thương mại: ${prop("ro.product.marketname").ifBlank { Build.MODEL }}\n")
        sb.append("persist.sys.feature.island (thô): ${prop("persist.sys.feature.island").ifBlank { "(trống)" }}\n")
        val rawProtocol = safe { Settings.System.getInt(context.contentResolver, "notification_focus_protocol", -1) }
        sb.append("notification_focus_protocol (Settings.System, -1 = chưa có): $rawProtocol\n")
        sb.append("Protocol app sẽ dùng: ${safe { FocusProtocolHelper.getFocusProtocolVersion(context) }}\n")

        sb.append("--- XMSF / Shizuku ---\n")
        sb.append("XMSF cài: ${isXmsfInstalled(context)}")
        sb.append(" • bật: ${safe { context.packageManager.getApplicationInfo(XMSF_PACKAGE, 0).enabled }}\n")
        sb.append("Ứng dụng Shizuku cài: ${isInstalled(context, SHIZUKU_PACKAGE)}")
        sb.append(" • đang chạy: ${ShizukuEngine.isShizukuAvailable()}")
        sb.append(" • đã cấp quyền: ${ShizukuEngine.hasShizukuPermission()}\n")
        sb.append("Đã kích hoạt 1-chạm (chế độ độc lập): ${ShizukuEngine.isOneTimeOptimizationActive(context)}")
        sb.append(" • cùng chu kỳ boot: ${ShizukuEngine.isIsolatedInCurrentBoot(context)}")
        sb.append(" • bootCount hiện tại: ${ShizukuEngine.getCurrentBootCount(context)}\n")
        sb.append("Đường gửi dự kiến: ${predictedPath(context)}\n")

        sb.append("--- Thông báo ---\n")
        sb.append("Thông báo ứng dụng được bật: ${safe { nm.areNotificationsEnabled() }}\n")
        val channel = try {
            nm.getNotificationChannel(FocusProtocolHelper.CHANNEL_ID)
        } catch (_: Throwable) {
            null
        }
        sb.append(
            "Kênh Focus: " + if (channel == null) "chưa tạo" else
                "importance=${channel.importance} (4 = HIGH), lockscreen=${channel.lockscreenVisibility}"
        ).append("\n")
        sb.append("Không làm phiền (1=tắt, 2=ưu tiên, 3=chặn hết, 4=báo thức): ${safe { nm.currentInterruptionFilter }}\n")
        sb.append(
            "Standby bucket (10=active, 20=working, 30=frequent, 40=rare, 50=restricted): " +
                safe {
                    (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket
                } + "\n"
        )

        val s = IslandProbe.stats.value
        sb.append("--- Dò đảo (đảo có sống sót sau khi gửi không) ---\n")
        sb.append("${s.summary()}\n")
        sb.append("Gợi ý (suy đoán từ dữ kiện trên, KHÔNG phải kết luận): ${hint(context, cn, s, channel?.importance)}\n")
        return sb.toString()
    }

    private fun hint(context: Context, cn: Boolean, s: IslandProbe.Stats, channelImportance: Int?): String {
        val shizuku = ShizukuEngine.hasShizukuPermission()
        val isolatedSameBoot = ShizukuEngine.isIsolatedInCurrentBoot(context)
        return when {
            channelImportance != null && channelImportance < NotificationManager.IMPORTANCE_HIGH ->
                "Kênh thông báo bị hạ mức quan trọng, đảo có thể không bung. Đặt lại kênh về mức cao."
            s.probed == 0 ->
                "Chưa có phép dò nào. Bấm Test đảo hoặc nhận một thông báo rồi lấy lại nhật ký."
            s.lost > 0 && cn && !shizuku && !isolatedSameBoot ->
                "Đảo bị hệ thống xóa/gỡ trên China ROM khi chưa khóa XMSF trong phiên boot hiện tại. Hãy bật Shizuku kích hoạt 1 lần."
            s.lost > 0 && (shizuku || isolatedSameBoot) ->
                "Có ${s.lost}/${s.probed} thông báo bị đóng sớm trong khi ${s.alive} thông báo vẫn sống tốt. Kiểm tra xem thông báo gốc có bị thu hồi nhanh hay không."
            s.lost > 0 ->
                "Đảo bị hệ thống loại bỏ trên ROM không phải China. Cần thêm dữ liệu."
            else ->
                "100% thông báo đảo (${s.alive}/${s.probed}) đều tồn tại hoàn hảo ở phía hệ thống!"
        }
    }
}
