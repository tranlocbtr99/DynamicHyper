package com.ltm.dynamichyper.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Trình ghi nhật ký sự kiện & chẩn đoán lỗi (Debug Logger) cho bản Beta.
 * Giúp người dùng và nhà phát triển tra cứu chi tiết:
 * - Trạng thái đảo Focus (Idle, Call, Charging, Notification)
 * - Lỗi hệ thống, crash hoặc từ chối cấp quyền từ HyperOS
 * - Trạng thái Shizuku & thông báo nổi
 */
object AppLogger {

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    ) {
        val formattedTime: String
            get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

        fun toPlainText(): String {
            val levelStr = when (level) {
                LogLevel.DEBUG -> "DEBUG"
                LogLevel.INFO -> "INFO "
                LogLevel.WARN -> "WARN "
                LogLevel.ERROR -> "ERROR"
            }
            val err = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
            return "[$formattedTime] [$levelStr] [$tag] $message$err"
        }
    }

    private const val MAX_LOGS = 300
    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, message, tr)
    fun e(tag: String, message: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, message, tr)

    fun log(level: LogLevel, tag: String, message: String, tr: Throwable? = null) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, tr)
            LogLevel.INFO -> Log.i(tag, message, tr)
            LogLevel.WARN -> Log.w(tag, message, tr)
            LogLevel.ERROR -> Log.e(tag, message, tr)
        }

        val entry = LogEntry(level = level, tag = tag, message = message, throwable = tr)
        buffer.add(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.pollFirst()
        }
        _logsFlow.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
        i("AppLogger", "Đã xóa sạch bộ nhớ đệm nhật ký.")
    }

    fun getAllLogsAsText(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== DYNAMIC HYPER BETA DIAGNOSTIC LOG ===\n")
        sb.append("App Version: 1.5.4-beta (Build 29)\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Brand/Fingerprint: ${Build.FINGERPRINT}\n")
        sb.append("Notification Access: ${PermissionHelper.isNotificationAccessGranted(context)}\n")
        sb.append("Floating Notif (10020): ${PermissionHelper.isFloatingNotificationAllowed(context)}\n")
        sb.append("Lockscreen Op (10008): ${PermissionHelper.isMiuiOpAllowed(context, 10008)}\n")
        sb.append("Battery Optimization Ignored: ${PermissionHelper.isBatteryOptimizationIgnored(context)}\n")
        sb.append("Shizuku Available: ${ShizukuEngine.isShizukuAvailable()} (Granted: ${ShizukuEngine.hasShizukuPermission()})\n")
        sb.append("=========================================\n\n")

        for (entry in buffer) {
            sb.append(entry.toPlainText()).append("\n")
        }
        return sb.toString()
    }

    fun copyToClipboard(context: Context) {
        val text = getAllLogsAsText(context)
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DynamicHyper_Logs", text)
        cm.setPrimaryClip(clip)
    }

    fun exportAndShareLogFile(context: Context) {
        try {
            val text = getAllLogsAsText(context)
            val logDir = File(context.cacheDir, "logs").apply { mkdirs() }
            val logFile = File(logDir, "DynamicHyper_Beta_Log_${System.currentTimeMillis()}.txt")
            logFile.writeText(text)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Nhật ký kiểm lỗi Dynamic Hyper Beta")
                putExtra(Intent.EXTRA_TEXT, "Đính kèm nhật ký kiểm tra lỗi Dynamic Hyper Beta:")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(Intent.createChooser(shareIntent, "Chia sẻ nhật ký lỗi").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Log.e("AppLogger", "Export log error: ${e.message}")
        }
    }
}
