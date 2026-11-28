package com.ltm.dynamichyper.feature.islands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper

/**
 * Quản lý Đảo Trạng Thái VPN (Báo kết nối VPN bảo mật) của Dynamic Hyper
 */
class VpnIslandManager(private val context: Context) {

    private val TAG = "DynamicHyper:VPN"
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isVpnActive = false

    companion object {
        const val VPN_NOTIFICATION_ID = 7777
        private const val PIC_VPN_LOCK = "vpn_lock_icon"
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
                if (!isVpnActive) {
                    isVpnActive = true
                    showVpnIsland()
                }
            }
        }

        override fun onLost(network: Network) {
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true || !hasActiveVpn()) {
                if (isVpnActive) {
                    isVpnActive = false
                    hideVpnIsland()
                }
            }
        }
    }

    fun startListening() {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)

            if (hasActiveVpn()) {
                isVpnActive = true
                showVpnIsland()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering VPN callback: ${e.message}")
        }
    }

    fun stopListening() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        hideVpnIsland()
    }

    private fun hasActiveVpn(): Boolean {
        return try {
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    private fun showVpnIsland() {
        try {
            val jsonParam = """
            {
              "param_v2": {
                "protocol": 1,
                "business": "taxi",
                "updatable": true,
                "ticker": "VPN Đang Hoạt Động",
                "timeout": 86400000,
                "enableFloat": false,
                "isShowNotification": false,
                "islandFirstFloat": true,
                "reopen": true,
                "param_island": {
                  "islandProperty": 1,
                  "islandPriority": 2,
                  "islandTimeout": 86400,
                  "islandOrder": false,
                  "dismissIsland": false,
                  "maxSize": false,
                  "needCloseAnimation": true,
                  "highlightColor": "#30D158",
                  "bigIslandArea": {
                    "imageTextInfoLeft": {
                      "type": 1,
                      "picInfo": {
                        "type": 1,
                        "pic": "miui.focus.pic_$PIC_VPN_LOCK"
                      },
                      "textInfo": {
                        "title": "VPN Bảo Mật",
                        "content": "Kết nối an toàn đang bật",
                        "showHighlightColor": true
                      }
                    }
                  },
                  "smallIslandArea": {
                    "picInfo": {
                      "type": 1,
                      "pic": "miui.focus.pic_$PIC_VPN_LOCK"
                    }
                  }
                }
              },
              "isShowNotification": false
            }
            """.trimIndent()

            val vpnDotBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#30D158")
                }
                canvas.drawCircle(48f, 48f, 38f, paint)
            }

            val pics = mapOf(PIC_VPN_LOCK to vpnDotBmp)

            val notification = FocusProtocolHelper.buildFocusNotification(
                context = context,
                title = "VPN Bảo Mật",
                content = "Đang bảo vệ lưu lượng mạng",
                smallIconRes = R.drawable.ic_launcher_foreground,
                jsonParam = jsonParam,
                pics = pics,
                isOngoing = true,
                largeIcon = vpnDotBmp,
                notificationId = VPN_NOTIFICATION_ID
            )

            IdleIslandManager.setBlockerStatic("vpn", true)
            com.ltm.dynamichyper.core.FocusProtocolHelper.postNotificationSafely(context, VPN_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show VPN island: ${e.message}")
        }
    }

    private fun hideVpnIsland() {
        try {
            NotificationManagerCompat.from(context).cancel(VPN_NOTIFICATION_ID)
        } catch (_: Exception) {}
        IdleIslandManager.setBlockerStatic("vpn", false)
    }
}
