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
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors

/**
 * Trình ghi nhật ký sự kiện & chẩn đoán lỗi (Debug Logger) cho bản Beta.
 *
 * Cải tiến so với bản cũ:
 *  - Lưu xuống file: nhật ký sống sót khi tiến trình bị hệ thống kill (trước đây mất sạch).
 *  - Gộp dòng lặp liên tiếp thành "(×N)" để không bị ngập.
 *  - Khi đầy bộ đệm, xóa dòng DEBUG cũ trước; dòng INFO/WARN/ERROR được giữ lâu hơn.
 *  - Đầu báo cáo có thông tin ROM, XMSF, Shizuku, kênh thông báo và kết quả dò đảo.
 *
 * Quyền riêng tư: nhật ký chỉ ghi tên gói và trạng thái, không ghi nội dung tin nhắn.
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
        val throwable: Throwable? = null,
        val repeat: Int = 1,
        val lastTimestamp: Long = timestamp
    ) {
        val formattedTime: String
            get() = formatTime(timestamp)

        fun toPlainText(): String {
            val levelStr = when (level) {
                LogLevel.DEBUG -> "DEBUG"
                LogLevel.INFO -> "INFO "
                LogLevel.WARN -> "WARN "
                LogLevel.ERROR -> "ERROR"
            }
            val rep = if (repeat > 1) " (×$repeat, đến ${formatTime(lastTimestamp)})" else ""
            val err = throwable?.let { "\n" + Log.getStackTraceString(it) } ?: ""
            return "[$formattedTime] [$levelStr] [$tag] $message$rep$err"
        }
    }

    private const val MAX_LOGS = 800
    private const val MAX_FILE_BYTES = 400_000L
    private const val KEEP_FILE_BYTES = 200_000L
    private const val PREVIOUS_BYTES = 60_000L

    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private var writer: BufferedWriter? = null
    private var historyFile: File? = null
    @Volatile
    private var previousSessions: String = ""
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AppLogger-io").apply { isDaemon = true }
    }

    private fun formatTime(ts: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(ts))

    /** Gọi một lần từ Application.onCreate(). Trước đó nhật ký chỉ nằm trong bộ nhớ. */
    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val file = File(dir, "history.log")
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                file.writeText(readTail(file, KEEP_FILE_BYTES))
            }
            previousSessions = if (file.exists()) readTail(file, PREVIOUS_BYTES) else ""
            historyFile = file
            writer = BufferedWriter(FileWriter(file, true))
            persist(
                "===== TIẾN TRÌNH MỚI ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} " +
                    "pid=${android.os.Process.myPid()} ====="
            )
        } catch (e: Throwable) {
            Log.e("AppLogger", "Không mở được file nhật ký: ${e.message}")
        }
    }

    private fun readTail(file: File, maxBytes: Long): String = try {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            val start = (len - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((len - start).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            // Bỏ dòng đầu nếu bị cắt giữa chừng.
            if (start > 0) text.substringAfter('\n', text) else text
        }
    } catch (_: Throwable) {
        ""
    }

    private fun persist(line: String) {
        val w = writer ?: return
        io.execute {
            try {
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (_: Throwable) {
            }
        }
    }

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, tr: Throwable? = null) = log(LogLevel.WARN, tag, message, tr)
    fun e(tag: String, message: String, tr: Throwable? = null) = log(LogLevel.ERROR, tag, message, tr)

    @Synchronized
    fun log(level: LogLevel, tag: String, message: String, tr: Throwable? = null) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, tr)
            LogLevel.INFO -> Log.i(tag, message, tr)
            LogLevel.WARN -> Log.w(tag, message, tr)
            LogLevel.ERROR -> Log.e(tag, message, tr)
        }

        val last = buffer.peekLast()
        if (last != null && tr == null && last.throwable == null &&
            last.level == level && last.tag == tag && last.message == message
        ) {
            buffer.pollLast()
            val merged = last.copy(repeat = last.repeat + 1, lastTimestamp = System.currentTimeMillis())
            buffer.add(merged)
            // Chỉ ghi file ở các mốc để không phình file: lặp lần 2, 10, 100, 1000.
            if (merged.repeat == 2 || merged.repeat == 10 || merged.repeat == 100 || merged.repeat == 1000) {
                persist(merged.toPlainText())
            }
        } else {
            val entry = LogEntry(level = level, tag = tag, message = message, throwable = tr)
            buffer.add(entry)
            persist(entry.toPlainText())
            trim()
        }
        _logsFlow.value = buffer.toList()
    }

    /** Khi đầy, bỏ dòng DEBUG cũ nhất trước; chỉ khi không còn DEBUG mới bỏ dòng cũ nhất bất kỳ. */
    private fun trim() {
        while (buffer.size > MAX_LOGS) {
            var removed = false
            val it = buffer.iterator()
            while (it.hasNext()) {
                if (it.next().level == LogLevel.DEBUG) {
                    it.remove()
                    removed = true
                    break
                }
            }
            if (!removed) buffer.pollFirst()
        }
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        previousSessions = ""
        _logsFlow.value = emptyList()
        val file = historyFile
        if (file != null) io.execute { try { file.writeText("") } catch (_: Throwable) {} }
        i("AppLogger", "Đã xóa sạch bộ nhớ đệm nhật ký.")
    }

    fun getAllLogsAsText(context: Context): String {
        val sb = StringBuilder()
        val pkgInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
        } catch (_: Exception) {
            null
        }
        val vName = pkgInfo?.versionName ?: "Unknown"
        val vCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo?.longVersionCode ?: 0
        } else {
            @Suppress("DEPRECATION")
            pkgInfo?.versionCode ?: 0
        }

        sb.append("=== DYNAMIC HYPER BETA DIAGNOSTIC LOG ===\n")
        sb.append("App Version: $vName (Build $vCode)\n")
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("OS: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("Brand/Fingerprint: ${Build.FINGERPRINT}\n")
        sb.append("Notification Access: ${PermissionHelper.isNotificationAccessGranted(context)}\n")
        sb.append("Floating Notif (10033): ${PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_FLOAT_NOTIFICATION)}\n")
        sb.append("Bg Popup (10020): ${PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_BG_START)}\n")
        sb.append("Lockscreen Op (10008): ${PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_SHOW_WHEN_LOCKED)}\n")
        sb.append("Battery Optimization Ignored: ${PermissionHelper.isBatteryOptimizationIgnored(context)}\n")
        sb.append("Focus Gate canShowFocus: ${FocusProtocolHelper.canShowFocus(context)}\n")
        sb.append("Focus Gate isIslandFeature: ${FocusProtocolHelper.isIslandFeatureAvailable()}\n")
        sb.append("Shizuku Available: ${ShizukuEngine.isShizukuAvailable()} (Granted: ${ShizukuEngine.hasShizukuPermission()})\n")
        try {
            sb.append(RomDiagnostics.snapshot(context))
        } catch (e: Throwable) {
            sb.append("(Không thu thập được thông tin ROM: ${e.message})\n")
        }
        sb.append("=========================================\n\n")

        val prev = previousSessions
        if (prev.isNotBlank()) {
            sb.append("--- LỊCH SỬ CÁC PHIÊN TRƯỚC (đã lưu trên máy) ---\n")
            sb.append(prev.trimEnd()).append("\n")
            sb.append("--- HẾT LỊCH SỬ ---\n\n")
        }
        sb.append("--- PHIÊN HIỆN TẠI ---\n")
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
