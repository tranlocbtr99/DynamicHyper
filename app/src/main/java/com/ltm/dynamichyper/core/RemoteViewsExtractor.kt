package com.ltm.dynamichyper.core

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

/**
 * Trích xuất văn bản (title / content) và đồng hồ đếm (Chronometer) từ các thông báo
 * dùng custom RemoteViews (như các app giao đồ ăn, đồng hồ bấm giờ, ghi âm, trình phát nhạc Trung Quốc)
 * khi `EXTRA_TITLE` và `EXTRA_TEXT` chuẩn bị bỏ trống.
 */
object RemoteViewsExtractor {

    data class ExtractedViewData(
        val title: String,
        val text: String,
        val chronometerBaseMs: Long? = null,
        val isChronometerCountDown: Boolean = false
    )

    @Suppress("DEPRECATION")
    fun extract(context: Context, sbn: StatusBarNotification): ExtractedViewData? {
        val notif = sbn.notification ?: return null
        val remoteViews: RemoteViews = notif.bigContentView
            ?: notif.contentView
            ?: notif.headsUpContentView
            ?: return null

        return try {
            val pkgContext = try {
                context.createPackageContext(sbn.packageName, Context.CONTEXT_RESTRICTED)
            } catch (_: Throwable) {
                context
            }
            val container = FrameLayout(pkgContext)
            val rootView = remoteViews.apply(pkgContext, container)
            val texts = mutableListOf<String>()
            var chronoBase: Long? = null
            var chronoCountDown = false

            fun traverse(view: View?) {
                if (view == null || view.visibility == View.GONE) return
                when (view) {
                    is Chronometer -> {
                        if (chronoBase == null && view.base > 0L) {
                            chronoBase = view.base
                            chronoCountDown = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                                view.isCountDown
                            } else false
                        }
                        val t = view.text?.toString()?.trim()
                        if (!t.isNullOrBlank() && t !in texts) {
                            texts.add(t)
                        }
                    }
                    is TextView -> {
                        val t = view.text?.toString()?.trim()
                        if (!t.isNullOrBlank() && t.length <= 200 && t !in texts) {
                            texts.add(t)
                        }
                    }
                    is ViewGroup -> {
                        for (i in 0 until view.childCount) {
                            traverse(view.getChildAt(i))
                        }
                    }
                }
            }

            traverse(rootView)

            if (texts.isEmpty() && chronoBase == null) return null
            val extractedTitle = texts.firstOrNull() ?: ""
            val extractedText = if (texts.size > 1) {
                texts.drop(1).take(3).joinToString(" • ")
            } else {
                ""
            }
            ExtractedViewData(
                title = extractedTitle,
                text = extractedText,
                chronometerBaseMs = chronoBase,
                isChronometerCountDown = chronoCountDown
            )
        } catch (_: Throwable) {
            null
        }
    }
}
