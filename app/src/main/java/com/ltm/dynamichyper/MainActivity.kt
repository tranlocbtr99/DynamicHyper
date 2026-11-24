package com.ltm.dynamichyper

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.*
import com.ltm.dynamichyper.feature.islands.IdleIslandManager
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.ui.screens.DashboardScreen
import com.ltm.dynamichyper.ui.screens.OnboardingTermsScreen
import com.ltm.dynamichyper.ui.screens.SplashScreen
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

        // Tự động yêu cầu quyền gửi thông báo trên Android 13+ (HyperOS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)
        } catch (_: Throwable) {}

        // Kiểm tra và tự động áp dụng quyền Shizuku 10020 & 10008 ngay khi mở app
        if (ShizukuEngine.hasShizukuPermission()) {
            CoroutineScope(Dispatchers.IO).launch {
                ShizukuEngine.applyOneTouchOptimizations(this@MainActivity)
                com.ltm.dynamichyper.core.AppLogger.i("MainActivity", "Tự động áp dụng tối ưu hóa Shizuku & cấp Op 10020 thành công")
            }
        }

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
                var isSplashShowing by remember { mutableStateOf(true) }
                val isTermsAccepted by prefs.isTermsAccepted.collectAsState(initial = false)
                val scope = rememberCoroutineScope()

                Crossfade(targetState = isSplashShowing, label = "splashTransition") { showingSplash ->
                    if (showingSplash) {
                        SplashScreen(
                            onTimeout = { isSplashShowing = false }
                        )
                    } else {
                        // Bắt buộc người dùng tích đồng ý điều khoản & miễn trừ trách nhiệm trước khi vào giao diện app
                        Crossfade(targetState = isTermsAccepted, label = "termsTransition") { accepted ->
                            if (!accepted) {
                                OnboardingTermsScreen(
                                    onAcceptAndStart = {
                                        scope.launch {
                                            prefs.setTermsAccepted(true)
                                        }
                                    }
                                )
                            } else {
                                DashboardScreen(prefs = prefs)
                            }
                        }
                    }
                }
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
}
