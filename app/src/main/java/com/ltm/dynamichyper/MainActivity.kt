package com.ltm.dynamichyper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.NotificationManagerCompat
import com.ltm.dynamichyper.core.DynamicNotificationService
import com.ltm.dynamichyper.core.IdleIslandManager
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.ui.screens.DashboardScreen
import com.ltm.dynamichyper.ui.theme.DynamicHyperTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (ShizukuEngine.hasShizukuPermission()) {
            ShizukuEngine.applyOneTouchOptimizations(this)
        }
    }

    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuEngine.applyOneTouchOptimizations(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PreferencesManager(this)

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        } catch (_: Throwable) {}

        // Tự động khởi động Đảo Chờ nếu đã bật trong thiết lập
        CoroutineScope(Dispatchers.Main).launch {
            val isIdle = prefs.isIdleIslandEnabled.first()
            val width = prefs.idleIslandWidth.first()
            if (isIdle) {
                IdleIslandManager.showIdleIsland(this@MainActivity, width)
            }
        }

        setContent {
            DynamicHyperTheme {
                DashboardScreen(prefs = prefs)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        } catch (_: Throwable) {}
    }

    companion object {
        fun isNotificationServiceEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            val cn = ComponentName(context, DynamicNotificationService::class.java)
            return flat != null && flat.contains(cn.flattenToString())
        }
    }
}

