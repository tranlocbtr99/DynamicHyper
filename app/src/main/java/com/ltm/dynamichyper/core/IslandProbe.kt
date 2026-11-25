package com.ltm.dynamichyper.core

import android.app.NotificationManager
import android.content.Context
import com.ltm.dynamichyper.feature.islands.CallIslandManager
import com.ltm.dynamichyper.feature.islands.ChargingIslandManager
import com.ltm.dynamichyper.feature.islands.DebuggingIslandManager
import com.ltm.dynamichyper.feature.islands.IdleIslandManager
import com.ltm.dynamichyper.feature.islands.NavigationIslandManager
import com.ltm.dynamichyper.feature.islands.ScreenRecordingIslandManager
import com.ltm.dynamichyper.feature.islands.VpnIslandManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Bộ dò "đảo có sống sót không".
 *
 * Sau mỗi lần gửi thông báo Focus, đợi một lúc rồi hỏi NotificationManager xem thông báo đó còn không
 * và còn mang dữ liệu đảo (`miui.focus.param`) không. Mục đích: ghi lại bằng chứng khi HyperOS
 * (XMSF/AuthManager/SystemUI) âm thầm xóa đảo sau khi app đã gửi thành công.
 *
 * Giới hạn (quan trọng khi đọc kết quả):
 *  - ALIVE chỉ nghĩa là thông báo còn tồn tại kèm dữ liệu đảo ở phía hệ thống. KHÔNG đảm bảo đảo đã được vẽ.
 *  - GONE / STRIPPED là bằng chứng trực tiếp rằng hệ thống đã loại bỏ thông báo hoặc phần đảo.
 *  - Không thể biết lý do hệ thống xóa; phần "nghi do..." trong log chỉ là suy đoán.
 */
object IslandProbe {

    private const val TAG = "DynamicHyper:Probe"
    private const val FOCUS_EXTRA = "miui.focus.param"
    private const val CHECK_EARLY_MS = 1_500L
    private const val CHECK_FINAL_MS = 5_000L

    enum class State { ALIVE, STRIPPED, GONE }

    data class Stats(
        val alive: Int = 0,
        val stripped: Int = 0,
        val gone: Int = 0,
        val superseded: Int = 0
    ) {
        val probed: Int get() = alive + stripped + gone
        val lost: Int get() = stripped + gone

        fun summary(): String =
            "$alive còn tồn tại • $stripped mất dữ liệu đảo • $gone bị xóa • $superseded bị thay thế (tổng $probed lần dò)"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Job>()
    private val lastState = ConcurrentHashMap<Int, State>()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun labelFor(id: Int): String = when (id) {
        IdleIslandManager.IDLE_ISLAND_ID -> "đảo chờ"
        FocusProtocolHelper.TEST_ISLAND_ID -> "đảo test"
        CallIslandManager.CALL_ISLAND_ID -> "cuộc gọi/quay màn hình"
        ChargingIslandManager.CHARGING_NOTIFICATION_ID -> "sạc pin"
        VpnIslandManager.VPN_NOTIFICATION_ID -> "VPN"
        NavigationIslandManager.NAV_ISLAND_ID -> "dẫn đường"
        DebuggingIslandManager.DEBUGGING_ISLAND_ID -> "gỡ lỗi"
        else -> "thông báo ứng dụng"
    }

    /**
     * Gọi ngay sau khi `notify()`. [path] mô tả đường gửi đã dùng để log gắn nguyên nhân khả dĩ.
     * Chỉ phép dò mới nhất của mỗi id được tính; phép dò cũ bị thay thế.
     */
    fun track(context: Context, id: Int, path: String) {
        val app = context.applicationContext
        jobs.put(id, scope.launch {
            val label = labelFor(id)
            try {
                delay(CHECK_EARLY_MS)
                val early = inspect(app, id) ?: return@launch
                if (early != State.ALIVE) {
                    report(id, label, early, CHECK_EARLY_MS, path)
                    return@launch
                }
                delay(CHECK_FINAL_MS - CHECK_EARLY_MS)
                val final = inspect(app, id) ?: return@launch
                report(id, label, final, CHECK_FINAL_MS, path)
            } catch (_: kotlinx.coroutines.CancellationException) {
                _stats.update { it.copy(superseded = it.superseded + 1) }
            }
        })?.cancel()
    }

    private fun inspect(context: Context, id: Int): State? = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sbn = nm.activeNotifications.firstOrNull { it.id == id }
        when {
            sbn == null -> State.GONE
            sbn.notification.extras?.containsKey(FOCUS_EXTRA) == true -> State.ALIVE
            else -> State.STRIPPED
        }
    } catch (e: Throwable) {
        AppLogger.w(TAG, "Không đọc được danh sách thông báo đang hoạt động: ${e.message}")
        null
    }

    private fun report(id: Int, label: String, state: State, afterMs: Long, path: String) {
        val previous = lastState.put(id, state)
        val secs = String.format("%.1fs", afterMs / 1000f)
        _stats.update {
            when (state) {
                State.ALIVE -> it.copy(alive = it.alive + 1)
                State.STRIPPED -> it.copy(stripped = it.stripped + 1)
                State.GONE -> it.copy(gone = it.gone + 1)
            }
        }
        when (state) {
            State.ALIVE -> {
                // INFO lần đầu hoặc khi vừa hồi phục; còn lại chỉ DEBUG để không làm ngập nhật ký.
                val msg = "[$label] id=$id sau $secs: thông báo còn tồn tại kèm dữ liệu đảo " +
                    "(chưa chắc đã được vẽ) • đường gửi: $path"
                if (previous == null || previous != State.ALIVE) AppLogger.i(TAG, msg) else AppLogger.d(TAG, msg)
            }
            State.STRIPPED -> AppLogger.w(
                TAG,
                "[$label] id=$id sau $secs: thông báo còn nhưng MẤT dữ liệu đảo ($FOCUS_EXTRA bị gỡ) — " +
                    "hệ thống đã loại phần đảo • đường gửi: $path. " + suspectedCause(path)
            )
            State.GONE -> AppLogger.w(
                TAG,
                "[$label] id=$id sau $secs: thông báo BỊ XÓA khỏi danh sách đang hoạt động • " +
                    "đường gửi: $path. " + suspectedCause(path)
            )
        }
    }

    private fun suspectedCause(path: String): String = when {
        path.startsWith("direct") ->
            "Nghi do XMSF/AuthManager trên China ROM khi mạng XMSF không được cách ly (đường gửi trực tiếp). Đây là suy đoán, chưa được xác nhận."
        path.contains("FAILED") ->
            "Cách ly XMSF đã thất bại nên đảo có thể bị XMSF xóa. Xem dòng cảnh báo cách ly ngay trước đó."
        else ->
            "Đã cách ly XMSF nhưng đảo vẫn bị loại: nguyên nhân khác (cổng quyền, protocol, nội dung đảo). Cần thêm dữ liệu."
    }
}
