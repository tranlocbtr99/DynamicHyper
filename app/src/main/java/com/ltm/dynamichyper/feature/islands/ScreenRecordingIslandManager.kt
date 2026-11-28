package com.ltm.dynamichyper.feature.islands

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.core.FocusProtocolHelper

/**
 * Quản lý Đảo Quay Màn Hình cho Xiaomi HyperOS (com.miui.screenrecorder)
 */
object ScreenRecordingIslandManager {
    private const val TAG = "DynamicHyper:Record"
    const val RECORDING_NOTIFICATION_ID = 8886
    private const val PIC_RED_DOT = "screen_recording_dot"

    fun isScreenRecordingNotification(sbn: StatusBarNotification): Boolean {
        return sbn.packageName == "com.miui.screenrecorder" &&
                (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                sbn.isOngoing
    }

    fun showRecordingIsland(context: Context) {
        try {
            val jsonParam = """
            {
              "param_v2": {
                "protocol": 1,
                "business": "taxi",
                "updatable": true,
                "ticker": "Đang quay màn hình",
                "timeout": 1440,
                "enableFloat": true,
                "isShowNotification": false,
                "islandFirstFloat": true,
                "reopen": true,
                "param_island": {
                  "islandProperty": 1,
                  "islandPriority": 3,
                  "islandTimeout": 86400,
                  "islandOrder": false,
                  "dismissIsland": false,
                  "maxSize": false,
                  "needCloseAnimation": true,
                  "highlightColor": "#FF3B30",
                  "bigIslandArea": {
                    "imageTextInfoLeft": {
                      "type": 1,
                      "picInfo": {
                        "type": 1,
                        "pic": "miui.focus.pic_$PIC_RED_DOT"
                      },
                      "textInfo": {
                        "title": "Quay màn hình",
                        "content": "Đang ghi hình...",
                        "showHighlightColor": true
                      }
                    }
                  },
                  "smallIslandArea": {
                    "picInfo": {
                      "type": 1,
                      "pic": "miui.focus.pic_$PIC_RED_DOT"
                    }
                  }
                },
                "baseInfo": {
                  "type": 1,
                  "picInfo": {
                    "type": 1,
                    "pic": "miui.focus.pic_$PIC_RED_DOT"
                  },
                  "title": "Đang quay màn hình",
                  "content": "Nhấn để dừng hoặc xem chi tiết"
                },
                "iconTextInfo": {
                  "title": "Đang quay màn hình",
                  "content": "Nhấn để dừng hoặc xem chi tiết"
                }
              },
              "isShowNotification": false
            }
            """.trimIndent()

            val redDotBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
                val canvas = Canvas(this)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#FF3B30")
                }
                canvas.drawCircle(48f, 48f, 40f, paint)
            }

            val pics = mapOf(PIC_RED_DOT to redDotBmp)

            val notification = FocusProtocolHelper.buildFocusNotification(
                context = context,
                title = "Đang quay màn hình",
                content = "Nhấn để dừng hoặc xem chi tiết",
                smallIconRes = R.drawable.ic_launcher_foreground,
                jsonParam = jsonParam,
                pics = pics,
                isOngoing = true,
                largeIcon = redDotBmp,
                notificationId = RECORDING_NOTIFICATION_ID
            )

            IdleIslandManager.setBlockerStatic("record", true)
            com.ltm.dynamichyper.core.FocusProtocolHelper.postNotificationSafely(context, RECORDING_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show recording island: ${e.message}")
        }
    }

    fun hideRecordingIsland(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(RECORDING_NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide recording island: ${e.message}")
        }
        IdleIslandManager.setBlockerStatic("record", false)
    }
}
