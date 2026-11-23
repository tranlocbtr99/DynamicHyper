package com.ltm.dynamichyper.feature.weather

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class LiveWeatherData(
    val temp: Int = 28,
    val condition: String = "Nhiều mây",
    val iconEmoji: String = "⛅",
    val city: String = "TP. Hồ Chí Minh",
    val humidity: Int = 75,
    val weatherCode: Int = 3
)

object WeatherManager {
    private const val TAG = "DynamicHyper_Weather"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var currentData = LiveWeatherData()
        private set

    @Volatile
    private var lastFetchTime = 0L
    private const val CACHE_DURATION_MS = 25 * 60 * 1000L // 25 phút

    fun getWeatherDisplay(): String {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime > CACHE_DURATION_MS || lastFetchTime == 0L) {
            triggerRefresh()
        }
        return "${currentData.iconEmoji} ${currentData.temp}°C"
    }

    fun getTemperatureOnly(): String = "${currentData.temp}°"

    fun triggerRefresh() {
        scope.launch {
            fetchWeatherDirect()
        }
    }

    suspend fun fetchWeatherDirect(): LiveWeatherData = withContext(Dispatchers.IO) {
        try {
            var lat = 10.822
            var lon = 106.6257
            var detectedCity = "TP. Hồ Chí Minh"

            // 1. Lấy tọa độ tương đối qua IP
            try {
                val ipConn = (URL("http://ip-api.com/json").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                }
                if (ipConn.responseCode == 200) {
                    val body = ipConn.inputStream.bufferedReader().use { it.readText() }
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("lat") && json.has("lon")) {
                        lat = json.get("lat").asDouble
                        lon = json.get("lon").asDouble
                    }
                    if (json.has("city")) {
                        detectedCity = json.get("city").asString
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "IP location fetch skipped: ${e.message}")
            }

            // 2. Lấy thời tiết từ Open-Meteo
            val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,weather_code"
            val conn = (URL(weatherUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JsonParser.parseString(body).asJsonObject
                val current = root.getAsJsonObject("current")
                val temp = current.get("temperature_2m").asDouble.roundToInt()
                val humidity = current.get("relative_humidity_2m")?.asDouble?.roundToInt() ?: 75
                val code = current.get("weather_code").asInt

                val (icon, condition) = when (code) {
                    0 -> "☀️" to "Trời nắng"
                    1, 2 -> "🌤️" to "Ít mây"
                    3 -> "⛅" to "Nhiều mây"
                    45, 48 -> "🌫️" to "Sương mù"
                    51, 53, 55 -> "🌦️" to "Mưa phùn"
                    61, 63, 65 -> "🌧️" to "Mưa rào"
                    80, 81, 82 -> "🌧️" to "Mưa to"
                    71, 73, 75, 85, 86 -> "❄️" to "Tuyết rơi"
                    95, 96, 99 -> "⛈️" to "Dông sét"
                    else -> "⛅" to "Mát mẻ"
                }

                currentData = LiveWeatherData(
                    temp = temp,
                    condition = condition,
                    iconEmoji = icon,
                    city = detectedCity,
                    humidity = humidity,
                    weatherCode = code
                )
                lastFetchTime = System.currentTimeMillis()
                Log.d(TAG, "Weather updated: ${currentData.temp}°C, ${currentData.condition}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching weather: ${e.message}")
        }
        currentData
    }
}
