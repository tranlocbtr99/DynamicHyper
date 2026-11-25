package com.ltm.dynamichyper.core

import android.os.SystemClock
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Quản lý việc nhường Đảo cho các ứng dụng hệ thống HyperOS bản địa (Đồng hồ đếm ngược,
 * Bấm giờ, Ghi âm Xiaomi, Trình phát nhạc MediaStyle).
 * Ngăn chặn tuyệt đối tình trạng vẽ 2 đảo song song trên HyperOS 3/4.
 */
object NativeIslandTracker {
    private const val DEFAULT_YIELD_MS = 30_000L
    private const val UNBOUNDED_YIELD_MS = Long.MAX_VALUE
    private const val MEDIA_YIELD_MS = 30_000L
    private const val DEFAULT_ISLAND_TIMEOUT_SECONDS = 3_600L
    private const val MAX_YIELD_MS = Long.MAX_VALUE / 2

    private class Sighting(val firstSeen: Long, yieldMs: Long, val live: Boolean = false) {
        val until: Long =
            if (live || yieldMs >= Long.MAX_VALUE - firstSeen) Long.MAX_VALUE else firstSeen + yieldMs
    }

    private val sightings = ConcurrentHashMap<String, Sighting>()

    private fun clock(): Long = SystemClock.elapsedRealtime()

    fun note(key: String, yieldMs: Long = DEFAULT_YIELD_MS, live: Boolean = false): Boolean {
        var changed = false
        sightings.compute(key) { _, current ->
            if (current != null && current.live == live) {
                current
            } else {
                changed = true
                Sighting(clock(), yieldMs.coerceAtLeast(0L), live)
            }
        }
        return changed
    }

    fun remove(key: String): Boolean = sightings.remove(key) != null

    fun clear() {
        sightings.clear()
    }

    fun hasFresh(): Boolean {
        val now = clock()
        return sightings.values.any { now < it.until }
    }

    fun remainingYieldMs(): Long {
        val now = clock()
        val latest = sightings.values.maxOfOrNull { it.until } ?: return 0L
        return if (latest == Long.MAX_VALUE) Long.MAX_VALUE else (latest - now).coerceAtLeast(0L)
    }

    /**
     * Tính toán thời gian nhường đảo từ payload Focus Notification của ứng dụng khác
     */
    fun calculateYieldMs(
        hasFocusParam: Boolean,
        focusParam: String?,
        isMediaPlayer: Boolean,
        isMediaPlaying: Boolean = false
    ): Long? {
        if (isMediaPlayer) return if (isMediaPlaying) UNBOUNDED_YIELD_MS else MEDIA_YIELD_MS
        if (hasFocusParam) return parseFocusYieldMs(focusParam)
        return null
    }

    private fun parseFocusYieldMs(focusParam: String?): Long {
        if (focusParam.isNullOrBlank()) return UNBOUNDED_YIELD_MS
        val root = try {
            JsonParser.parseString(focusParam).takeIf { it.isJsonObject }?.asJsonObject
        } catch (_: Exception) {
            null
        } ?: return UNBOUNDED_YIELD_MS

        val paramV2 = root.get("param_v2")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return UNBOUNDED_YIELD_MS
        val island = paramV2.get("param_island")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return 0L
        val seconds = try {
            island.get("islandTimeout")?.takeIf { it.isJsonPrimitive }?.asLong
        } catch (_: Exception) {
            null
        }
        val effective = if (seconds != null && seconds > 0) seconds else DEFAULT_ISLAND_TIMEOUT_SECONDS
        return effective.coerceAtMost(MAX_YIELD_MS / 1_000L) * 1_000L
    }
}
