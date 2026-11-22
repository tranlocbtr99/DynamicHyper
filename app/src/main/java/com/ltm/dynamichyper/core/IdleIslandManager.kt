package com.ltm.dynamichyper.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.R
import com.ltm.dynamichyper.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class IdleIslandManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: PreferencesManager
) {
    private val TAG = "DynamicHyper_Idle"

    companion object {
        const val IDLE_ISLAND_ID = 9999
        private const val DISPATCH_DELAY_MS = 600L
        private const val PIC_IDLE_DOT = "idle_camera_dot"

        fun showIdleIsland(context: Context, width: Int) {
            dispatchDirect(context, width)
        }

        fun hideIdleIsland(context: Context) {
            try {
                NotificationManagerCompat.from(context).cancel(IDLE_ISLAND_ID)
            } catch (e: Exception) {
                Log.e("DynamicHyper_Idle", "Error hiding idle island: ${e.message}")
            }
        }

        fun updateIdleIslandWidth(context: Context, width: Int) {
            dispatchDirect(context, width)
        }

        private fun dispatchDirect(context: Context, width: Int) {
            try {
                val effectiveWidth = if (width > 0) width else 8
                val emptyString = "\u00A0".repeat(effectiveWidth)

                val jsonParam = """
                {
                  "param_v2": {
                    "protocol": 3,
                    "business": "dynamic_hyper_idle",
                    "updatable": true,
                    "ticker": "Dynamic Hyper",
                    "enableFloat": false,
                    "isShowNotification": true,
                    "islandFirstFloat": false,
                    "reopen": true,
                    "param_island": {
                      "islandProperty": 1,
                      "islandPriority": 1,
                      "islandTimeout": 86400000,
                      "islandOrder": false,
                      "dismissIsland": false,
                      "maxSize": false,
                      "needCloseAnimation": true,
                      "highlightColor": "#FFFFFF",
                      "bigIslandArea": {
                        "imageTextInfoLeft": {
                          "type": 1,
                          "textInfo": {
                            "title": "$emptyString",
                            "content": "$emptyString",
                            "showHighlightColor": false
                          }
                        }
                      },
                      "smallIslandArea": {
                        "picInfo": {
                          "type": 1,
                          "pic": "miui.focus.pic_$PIC_IDLE_DOT"
                        }
                      }
                    }
                  },
                  "isShowNotification": true
                }
                """.trimIndent()

                val transparentBmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val pics = mapOf(PIC_IDLE_DOT to transparentBmp)

                val notification = FocusProtocolHelper.buildFocusNotification(
                    context = context,
                    title = "Dynamic Hyper",
                    content = "Idle Island",
                    smallIconRes = R.drawable.ic_launcher_foreground,
                    jsonParam = jsonParam,
                    pics = pics,
                    isOngoing = true
                )

                NotificationManagerCompat.from(context).notify(IDLE_ISLAND_ID, notification)
            } catch (e: Exception) {
                Log.e("DynamicHyper_Idle", "Error direct dispatch: ${e.message}")
            }
        }
    }

    private var isEnabled = true
    private var islandWidth = 8
    private var isShowing = false
    private var activeEventCount = 0
    private var pendingJob: Job? = null

    init {
        scope.launch {
            prefs.isIdleIslandEnabled.collectLatest { enabled ->
                synchronized(this@IdleIslandManager) {
                    if (isEnabled != enabled) {
                        isEnabled = enabled
                        evaluateStateLocked()
                    }
                }
            }
        }
        scope.launch {
            prefs.idleIslandWidth.collectLatest { width ->
                synchronized(this@IdleIslandManager) {
                    if (islandWidth != width) {
                        islandWidth = width
                        if (isShowing) {
                            dispatchIdleIsland()
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun onEventCountChanged(count: Int) {
        activeEventCount = count
        evaluateStateLocked()
    }

    @Synchronized
    fun refresh() {
        if (isEnabled && activeEventCount == 0) {
            dispatchIdleIsland()
            isShowing = true
        }
    }

    private fun shouldShow(): Boolean = isEnabled && activeEventCount == 0

    private fun evaluateStateLocked() {
        if (shouldShow()) {
            if (!isShowing) {
                isShowing = true
                pendingJob?.cancel()
                pendingJob = scope.launch {
                    delay(DISPATCH_DELAY_MS)
                    synchronized(this@IdleIslandManager) {
                        if (shouldShow()) {
                            dispatchIdleIsland()
                        }
                    }
                }
            }
        } else {
            if (isShowing) {
                isShowing = false
                pendingJob?.cancel()
                dismissIdleIsland()
            }
        }
    }

    private fun dispatchIdleIsland() {
        dispatchDirect(context, islandWidth)
    }

    private fun dismissIdleIsland() {
        hideIdleIsland(context)
    }
}
