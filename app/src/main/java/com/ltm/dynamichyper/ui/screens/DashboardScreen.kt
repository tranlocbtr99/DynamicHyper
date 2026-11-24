package com.ltm.dynamichyper.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ltm.dynamichyper.feature.islands.IdleIslandManager
import com.ltm.dynamichyper.core.OtaUpdateManager
import com.ltm.dynamichyper.core.PermissionHelper
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.core.UpdateInfo
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.ui.components.*
import com.ltm.dynamichyper.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(
    prefs: PreferencesManager,
    hazeState: HazeState = remember { HazeState() }
) {
    var currentTab by remember { mutableStateOf(DashboardTab.OVERVIEW) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .haze(hazeState)
    ) {
        // Nội dung của 3 màn hình chuyển đổi mượt mà với hiệu ứng bóng nước (Water Bubble Physics)
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                (fadeIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) +
                        scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(120)) +
                                scaleOut(targetScale = 1.04f, animationSpec = tween(120))
                    )
            },
            label = "waterBubbleTabTransition",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                DashboardTab.OVERVIEW -> OverviewTabScreen(prefs = prefs, hazeState = hazeState)
                DashboardTab.FEATURES -> FeaturesTabScreen(prefs = prefs, hazeState = hazeState)
                DashboardTab.APPS -> AppsTabScreen(prefs = prefs, hazeState = hazeState)
            }
        }

        // Thanh điều hướng đáy nổi kiểu iOS Liquid Glass (Floating Island Taskbar)
        FloatingGlassBottomBar(
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}

// =================================================================================
// 1. TRANG TỔNG QUAN (OVERVIEW TAB)
// =================================================================================
@Composable
private fun OverviewTabScreen(
    prefs: PreferencesManager,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isIdleIslandEnabled by prefs.isIdleIslandEnabled.collectAsState(initial = true)
    val idleIslandWidth by prefs.idleIslandWidth.collectAsState(initial = 10)
    val idleIslandStyle by prefs.idleIslandStyle.collectAsState(initial = "minimal")
    val idleIslandMode by prefs.idleIslandMode.collectAsState(initial = "compact")
    val isNotificationGradientGlow by prefs.isNotificationGradientGlow.collectAsState(initial = true)
    val isPersistentNotificationIsland by prefs.isPersistentNotificationIsland.collectAsState(initial = true)
    val devicePreset by prefs.devicePreset.collectAsState(initial = "redmi_k")
    val isAutoStartMarked by prefs.isAutoStartMarked.collectAsState(initial = false)

    val bankName by prefs.bankName.collectAsState(initial = "Techcombank")
    val bankAccount by prefs.bankAccount.collectAsState(initial = "4444444790")
    val bankOwner by prefs.bankOwner.collectAsState(initial = "Tran Minh Loc")

    var isNotificationAccessGranted by remember { mutableStateOf(PermissionHelper.isNotificationAccessGranted(context)) }
    var isPostNotificationGranted by remember { mutableStateOf(PermissionHelper.isPostNotificationGranted(context)) }
    var isFloatingNotificationGranted by remember { mutableStateOf(PermissionHelper.isFloatingNotificationAllowed(context)) }
    var isFeaturedNotificationGranted by remember { mutableStateOf(com.ltm.dynamichyper.core.FocusProtocolHelper.canShowFocus(context) != false) }
    var isRestrictedAllowed by remember { mutableStateOf(PermissionHelper.isRestrictedSettingsAllowed(context)) }
    var showRestrictedDialog by remember { mutableStateOf(false) }
    var isBatteryOptimizationIgnored by remember { mutableStateOf(PermissionHelper.isBatteryOptimizationIgnored(context)) }
    var isShizukuReady by remember { mutableStateOf(ShizukuEngine.hasShizukuPermission()) }
    var isLockscreenOpGranted by remember { mutableStateOf(PermissionHelper.isMiuiOpAllowed(context, 10008)) }
    var isAutoStartGranted by remember {
        mutableStateOf(PermissionHelper.isAutoStartOrLockscreenGranted(context, isAutoStartMarked))
    }

    val postNotificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isPostNotificationGranted = isGranted
        if (!isGranted) {
            Toast.makeText(context, "Cần cấp quyền để Dynamic Hyper hiển thị thông báo đảo", Toast.LENGTH_LONG).show()
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isPostNotificationGranted = PermissionHelper.isPostNotificationGranted(context)
                isNotificationAccessGranted = PermissionHelper.isNotificationAccessGranted(context)
                isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
                isFeaturedNotificationGranted = com.ltm.dynamichyper.core.FocusProtocolHelper.canShowFocus(context) != false
                isRestrictedAllowed = PermissionHelper.isRestrictedSettingsAllowed(context)
                isBatteryOptimizationIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
                isLockscreenOpGranted = PermissionHelper.isMiuiOpAllowed(context, 10008)
                isAutoStartGranted = PermissionHelper.isAutoStartOrLockscreenGranted(context, isAutoStartMarked)
                isShizukuReady = ShizukuEngine.hasShizukuPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var showTermsDialog by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }
    var showDiagnosticDialog by remember { mutableStateOf(false) }
    var updateInfoState by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    LaunchedEffect(isAutoStartMarked) {
        isPostNotificationGranted = PermissionHelper.isPostNotificationGranted(context)
        isNotificationAccessGranted = PermissionHelper.isNotificationAccessGranted(context)
        isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
        isFeaturedNotificationGranted = com.ltm.dynamichyper.core.FocusProtocolHelper.canShowFocus(context) != false
        isRestrictedAllowed = PermissionHelper.isRestrictedSettingsAllowed(context)
        isBatteryOptimizationIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
        isLockscreenOpGranted = PermissionHelper.isMiuiOpAllowed(context, 10008)
        isAutoStartGranted = PermissionHelper.isAutoStartOrLockscreenGranted(context, isAutoStartMarked)
        isShizukuReady = ShizukuEngine.hasShizukuPermission()
    }

    val isInstallGranted = PermissionHelper.canRequestPackageInstalls(context)
    val grantedCount = listOf(
        isPostNotificationGranted,
        isNotificationAccessGranted,
        isFloatingNotificationGranted,
        isFeaturedNotificationGranted,
        isLockscreenOpGranted,
        isBatteryOptimizationIgnored,
        isAutoStartGranted,
        isInstallGranted
    ).count { it }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .padding(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Live Island Header
        LiveIslandHeader(
            isIdleEnabled = isIdleIslandEnabled,
            islandWidth = idleIslandWidth,
            islandStyle = idleIslandStyle,
            islandMode = idleIslandMode
        )

        // Hàng tinh gọn OTA & Ủng Hộ Tác Giả & Kiểm Lỗi Beta (Compact Multi-Bubble Row)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Nút Kiểm Lỗi Beta (Nhật ký sự kiện)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleOrange.copy(alpha = 0.12f))
                    .border(1.dp, AppleOrange.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable { showDiagnosticDialog = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = null,
                        tint = AppleOrange,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Nhật Ký Beta",
                            style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppleOrange)
                        )
                        Text(
                            text = "Kiểm lỗi thời gian thực",
                            style = Typography.bodySmall.copy(fontSize = 10.sp, color = TextSecondary)
                        )
                    }
                }
            }

            // Nút Cập nhật OTA
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleBlue.copy(alpha = 0.12f))
                    .border(1.dp, AppleBlue.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable {
                        scope.launch {
                            isCheckingUpdate = true
                            val update = OtaUpdateManager.checkUpdate(context)
                            isCheckingUpdate = false
                            if (update != null && update.hasUpdate) {
                                updateInfoState = update
                            } else {
                                Toast.makeText(context, "Bạn đang dùng bản mới nhất (${OtaUpdateManager.getCurrentVersion(context)})!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = AppleBlue
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = AppleBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = "Cập Nhật OTA",
                            style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppleBlue)
                        )
                        Text(
                            text = "v2.0.4-beta",
                            style = Typography.bodySmall.copy(fontSize = 10.sp, color = TextSecondary)
                        )
                    }
                }
            }

            // Nút Ủng hộ (Donate)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(AppleTeal.copy(alpha = 0.12f))
                    .border(1.dp, AppleTeal.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable { showDonateDialog = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = AppleTeal,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Ủng Hộ",
                        style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AppleTeal)
                    )
                }
            }
        }

        // Cảnh báo & Nút Cấp Quyền Nhanh khi thiếu quyền Thông Báo hoặc Thông Báo Nổi
        if (!isPostNotificationGranted || !isNotificationAccessGranted || !isFloatingNotificationGranted || !isFeaturedNotificationGranted) {
            LiquidGlassCard(
                hazeState = hazeState,
                modifier = Modifier
                    .border(1.dp, AppleOrange.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
                    .background(AppleOrange.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(AppleOrange.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = AppleOrange,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                !isPostNotificationGranted -> "Chưa Cấp Quyền Thông Báo"
                                !isNotificationAccessGranted && !isRestrictedAllowed -> "Android Đang Hạn Chế Cài Đặt"
                                !isFeaturedNotificationGranted -> "Chưa Bật Thông Báo Tiêu Điểm"
                                !isFloatingNotificationGranted -> "Chưa Bật Thông Báo Nổi (HyperOS)"
                                else -> "Chưa Bật Truy Cập Thông Báo"
                            },
                            style = Typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = AppleOrange)
                        )
                        Text(
                            text = when {
                                !isPostNotificationGranted -> "Bắt buộc cấp quyền để Dynamic Hyper hiển thị thông báo."
                                !isNotificationAccessGranted && !isRestrictedAllowed -> "Cần mở khóa 3 chấm trong Thông tin ứng dụng để cho phép cấp quyền đọc thông báo!"
                                !isFeaturedNotificationGranted -> "HyperOS cần bạn BẬT 'Thông báo nổi bật' trong Cài đặt thông báo để Đảo hiển thị!"
                                !isFloatingNotificationGranted -> "Cần bật 'Thông báo nổi' để HyperOS cho phép gắn Đảo lên nốt ruồi camera!"
                                else -> "Cần cấp quyền để ứng dụng đọc tin nhắn và đưa lên Đảo Động."
                            },
                            style = Typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppleOrange)
                            .clickable {
                                when {
                                    !isPostNotificationGranted -> {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            PermissionHelper.openAppNotificationSettings(context)
                                        }
                                    }
                                    !isFeaturedNotificationGranted -> {
                                        PermissionHelper.openAppNotificationSettings(context)
                                    }
                                    !isFloatingNotificationGranted -> {
                                        if (isShizukuReady) {
                                            val ok = PermissionHelper.setMiuiFloatingNotificationEnabled(context.packageName, true)
                                            isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
                                            Toast.makeText(
                                                context,
                                                when {
                                                    isFloatingNotificationGranted -> "Đã bật Thông Báo Nổi qua Shizuku!"
                                                    ok -> "Đã gửi lệnh nhưng HyperOS chưa xác nhận. Kiểm tra lại trong Cài đặt."
                                                    else -> "Shizuku chưa cấp quyền. Mở Cài đặt để bật thủ công."
                                                },
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            PermissionHelper.openMiuiOtherPermissions(context)
                                        }
                                    }
                                    else -> {
                                        if (!isRestrictedAllowed && !isNotificationAccessGranted) {
                                            showRestrictedDialog = true
                                        } else {
                                            PermissionHelper.openNotificationAccessSettings(context)
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "Bật Ngay",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Nút kích hoạt nhanh tất cả
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isShizukuReady || isNotificationAccessGranted) AppleTeal.copy(alpha = 0.2f) else AppleBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isShizukuReady || isNotificationAccessGranted) Icons.Default.CheckCircle else Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isShizukuReady || isNotificationAccessGranted) AppleTeal else AppleBlue
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Kích Hoạt Tự Động 1-Chạm",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = if (isNotificationAccessGranted && isPostNotificationGranted) "Hệ thống đang hoạt động ổn định" else "Tự động cấp toàn bộ quyền và tối ưu Xiaomi",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LiquidActionButton(
                text = if (isShizukuReady) "TỰ ĐỘNG TỐI ƯU HÓA TẤT CẢ (SHIZUKU)" else "KÍCH HOẠT NHANH TẤT CẢ",
                onClick = {
                    if (ShizukuEngine.isShizukuAvailable()) {
                        if (!ShizukuEngine.hasShizukuPermission()) {
                            ShizukuEngine.requestShizukuPermission()
                        } else {
                            val success = ShizukuEngine.applyOneTouchOptimizations(context)
                            PermissionHelper.setMiuiFloatingNotificationEnabled(context.packageName, true)
                            isShizukuReady = true
                            // Đọc lại trạng thái THẬT sau khi Shizuku áp dụng, không ép cứng true
                            isNotificationAccessGranted = PermissionHelper.isNotificationAccessGranted(context)
                            isPostNotificationGranted = PermissionHelper.isPostNotificationGranted(context)
                            isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
                            isBatteryOptimizationIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
                            isAutoStartGranted = PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_BG_START)
                            isLockscreenOpGranted = PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_SHOW_WHEN_LOCKED)
                            scope.launch { prefs.setAutoStartMarked(true) }
                            Toast.makeText(
                                context,
                                if (success) "Đã tối ưu hóa thành công 100%! Đảo đã kích hoạt." else "Đã áp dụng tối ưu hóa hệ thống.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } else {
                        if (!isPostNotificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            PermissionHelper.openNotificationAccessSettings(context)
                        }
                    }
                }
            )
        }

        // Quyền hệ thống (Compact)
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trạng Thái Quyền Hệ Thống",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleBlue)
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (grantedCount == 7) AppleTeal.copy(alpha = 0.2f) else AppleOrange.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (grantedCount == 7) "Hoàn hảo 7/7" else "$grantedCount/7 Đã cấp",
                        style = Typography.labelSmall.copy(
                            color = if (grantedCount == 7) AppleTeal else AppleOrange,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Cho Phép Gửi Thông Báo (App)",
                icon = Icons.Default.NotificationsActive,
                isGranted = isPostNotificationGranted,
                onActionClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isPostNotificationGranted) {
                        postNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        PermissionHelper.openAppNotificationSettings(context)
                    }
                }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Thông Báo Tiêu Điểm (Featured Notifications)",
                icon = Icons.Default.Star,
                isGranted = isFeaturedNotificationGranted,
                onActionClick = {
                    PermissionHelper.openAppNotificationSettings(context)
                }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Thông Báo Nổi (HyperOS Island)",
                icon = Icons.Default.Fullscreen,
                isGranted = isFloatingNotificationGranted,
                onActionClick = {
                    if (isShizukuReady) {
                        val ok = PermissionHelper.setMiuiFloatingNotificationEnabled(context.packageName, true)
                        isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
                        Toast.makeText(
                            context,
                            when {
                                isFloatingNotificationGranted -> "Đã bật Thông Báo Nổi qua Shizuku!"
                                ok -> "Đã gửi lệnh nhưng HyperOS chưa xác nhận. Kiểm tra lại trong Cài đặt."
                                else -> "Shizuku chưa cấp quyền. Mở Cài đặt để bật thủ công."
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        PermissionHelper.openAppNotificationSettings(context)
                    }
                }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Truy Cập Đọc Thông Báo",
                icon = Icons.Default.Notifications,
                isGranted = isNotificationAccessGranted,
                onActionClick = {
                    if (!isRestrictedAllowed && !isNotificationAccessGranted) {
                        showRestrictedDialog = true
                    } else {
                        PermissionHelper.openNotificationAccessSettings(context)
                    }
                }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Hiển Thị Trên Màn Hình Khóa",
                icon = Icons.Default.Lock,
                isGranted = isLockscreenOpGranted,
                onActionClick = { PermissionHelper.openMiuiOtherPermissions(context) }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Không Giới Hạn Pin (Chạy ngầm)",
                icon = Icons.Default.BatteryChargingFull,
                isGranted = isBatteryOptimizationIgnored,
                onActionClick = { PermissionHelper.requestIgnoreBatteryOptimizations(context) }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Tự Khởi Chạy (AutoStart)",
                icon = Icons.Default.Refresh,
                isGranted = isAutoStartGranted,
                onActionClick = {
                    scope.launch { prefs.setAutoStartMarked(true) }
                    // Không ép isAutoStartGranted = true: người dùng vẫn phải vào Cài đặt và bật tay
                    PermissionHelper.openMiuiAutoStartSettings(context)
                }
            )

            HorizontalDivider(color = GlassBorderSubtle)

            CompactPermissionItem(
                title = "Cài Đặt Cập Nhật (OTA)",
                icon = Icons.Default.SystemUpdate,
                isGranted = isInstallGranted,
                onActionClick = { PermissionHelper.openInstallPermissionSettings(context) }
            )
        }

        // Đảo Chờ 24/7 & Căn Chỉnh Nốt Ruồi Camera
        LiquidGlassCard(hazeState = hazeState) {
            GlassSettingItem(
                title = "Bật Đảo Chờ 24/7 (Idle Island)",
                subtitle = "Luôn hiển thị viên thuốc camera HyperOS ngay cả khi không có thông báo.",
                checked = isIdleIslandEnabled,
                onCheckedChange = { checked ->
                    scope.launch {
                        prefs.setIdleIslandEnabled(checked)
                        if (checked) {
                            IdleIslandManager.showIdleIsland(context, idleIslandWidth)
                        } else {
                            IdleIslandManager.hideIdleIsland(context)
                        }
                    }
                }
            )

            if (isIdleIslandEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = GlassBorderSubtle)
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Tối Ưu Theo Dòng Máy Xiaomi",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = ApplePurple, fontSize = 14.sp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DevicePresetChip(
                        name = "Redmi K / POCO",
                        selected = devicePreset == "redmi_k",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setDevicePreset("redmi_k")
                                prefs.setIdleIslandWidth(10)
                                IdleIslandManager.updateIdleIslandWidth(context, 10)
                            }
                        }
                    )
                    DevicePresetChip(
                        name = "Xiaomi Số",
                        selected = devicePreset == "xiaomi_flagship",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setDevicePreset("xiaomi_flagship")
                                prefs.setIdleIslandWidth(8)
                                IdleIslandManager.updateIdleIslandWidth(context, 8)
                            }
                        }
                    )
                    DevicePresetChip(
                        name = "Redmi Note",
                        selected = devicePreset == "redmi_note",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setDevicePreset("redmi_note")
                                prefs.setIdleIslandWidth(12)
                                IdleIslandManager.updateIdleIslandWidth(context, 12)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Độ rộng tinh chỉnh: $idleIslandWidth",
                        style = Typography.bodyLarge.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    )
                    Text(
                        text = "4 (Nhỏ) - 20 (Rộng)",
                        style = Typography.bodyMedium.copy(color = AppleTeal, fontSize = 12.sp)
                    )
                }

                Slider(
                    value = idleIslandWidth.toFloat(),
                    onValueChange = { value ->
                        scope.launch {
                            val newW = value.toInt()
                            prefs.setIdleIslandWidth(newW)
                            prefs.setDevicePreset("custom")
                            IdleIslandManager.updateIdleIslandWidth(context, newW)
                        }
                    },
                    valueRange = 4f..20f,
                    steps = 16,
                    colors = SliderDefaults.colors(
                        thumbColor = ApplePurple,
                        activeTrackColor = ApplePurple,
                        inactiveTrackColor = Color(0xFF3A3A3C)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = GlassBorderSubtle)
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Giao Diện Đảo Chờ (Hiệu Ứng Động)",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleTeal, fontSize = 14.sp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Hàng 1: Tối Giản, Chấm Xanh, Thời Tiết
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DevicePresetChip(
                        name = "Tối Giản",
                        selected = idleIslandStyle == "minimal",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setIdleIslandStyle("minimal")
                                IdleIslandManager.updateStyle(context, "minimal")
                            }
                        }
                    )
                    DevicePresetChip(
                        name = "Chấm Xanh",
                        selected = idleIslandStyle == "green_dot",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setIdleIslandStyle("green_dot")
                                IdleIslandManager.updateStyle(context, "green_dot")
                            }
                        }
                    )
                    DevicePresetChip(
                        name = "Thời Tiết",
                        selected = idleIslandStyle == "weather",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setIdleIslandStyle("weather")
                                IdleIslandManager.updateStyle(context, "weather")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Hàng 2: Nhiệt Pin, RAM Dùng
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DevicePresetChip(
                        name = "Nhiệt Độ Pin",
                        selected = idleIslandStyle == "battery_temp",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setIdleIslandStyle("battery_temp")
                                IdleIslandManager.updateStyle(context, "battery_temp")
                            }
                        }
                    )
                    DevicePresetChip(
                        name = "RAM Đang Dùng",
                        selected = idleIslandStyle == "ram_usage",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                prefs.setIdleIslandStyle("ram_usage")
                                IdleIslandManager.updateStyle(context, "ram_usage")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = GlassBorderSubtle)

                GlassSettingItem(
                    title = "Hiệu Ứng Gradient Thông Báo",
                    subtitle = "Phát quang viền gradient neon hologram khi có thông báo mới bung ra.",
                    checked = isNotificationGradientGlow,
                    onCheckedChange = { checked ->
                        scope.launch {
                            prefs.setNotificationGradientGlow(checked)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = GlassBorderSubtle)

                GlassSettingItem(
                    title = "Giữ Đảo Thu Nhỏ Tại Camera",
                    subtitle = "Bung to thông báo 4s rồi tự động thu nhỏ về camera, giữ hiển thị cho đến khi bạn xem hoặc xóa thông báo.",
                    checked = isPersistentNotificationIsland,
                    onCheckedChange = { checked ->
                        scope.launch {
                            prefs.setPersistentNotificationIsland(checked)
                        }
                    }
                )
            }
        }

        // Bản quyền
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dynamic Hyper",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Phiên bản v1.5.0 • Liquid Glass Edition",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = AppleTeal)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = GlassBorderSubtle)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "• Thiết kế & Phát triển: Tran Minh Loc (LTM)\n" +
                        "• Kiến trúc: HyperIsland Core Protocol & LTMori Multi-Task Shield\n" +
                        "• Nền tảng: Xiaomi HyperOS 3 & 4 / Android 15 - 17\n" +
                        "• Tương thích: Toàn bộ thiết bị Xiaomi, Redmi, POCO",
                style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary, lineHeight = 20.sp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Xem lại Điều khoản sử dụng & Miễn trừ trách nhiệm",
                color = AppleBlue,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { showTermsDialog = true }
            )
        }
    }

    if (showRestrictedDialog) {
        AlertDialog(
            onDismissRequest = { showRestrictedDialog = false },
            containerColor = Color(0xFF1E1E22),
            icon = {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = AppleOrange,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Mở Khóa Cài Đặt Bị Hạn Chế",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                )
            },
            text = {
                Column {
                    Text(
                        text = "Android 13/14+ mặc định hạn chế quyền đọc thông báo đối với ứng dụng cài ngoài CH Play.",
                        style = Typography.bodyMedium.copy(color = Color.White.copy(alpha = 0.9f))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Cách mở khóa trong 3 bước:\n1. Bấm nút \"Mở Thông Tin Ứng Dụng\" bên dưới.\n2. Bấm vào biểu tượng 3 chấm (⋮) ở góc trên bên phải màn hình.\n3. Chọn \"Cho phép cài đặt bị hạn chế\".\n4. Quay lại Dynamic Hyper để cấp quyền đọc thông báo bình thường.",
                        style = Typography.bodySmall.copy(color = Color.White.copy(alpha = 0.75f), lineHeight = 18.sp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestrictedDialog = false
                        PermissionHelper.openAppDetailsSettings(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppleOrange),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Mở Thông Tin Ứng Dụng", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestrictedDialog = false }) {
                    Text("Để Sau", color = TextSecondary)
                }
            }
        )
    }

    if (showDiagnosticDialog) {
        DiagnosticLogDialog(
            onDismissRequest = { showDiagnosticDialog = false }
        )
    }

    if (showTermsDialog) {
        TermsAndDisclaimerDialog(onAccept = { showTermsDialog = false })
    }

    if (showDonateDialog) {
        DonateBankDialog(
            bankName = bankName,
            accountNumber = bankAccount,
            accountOwner = bankOwner,
            onDismiss = { showDonateDialog = false }
        )
    }

    updateInfoState?.let { update ->
        AlertDialog(
            onDismissRequest = { updateInfoState = null },
            containerColor = Color(0xFF1E1E22),
            title = {
                Text(
                    text = "Có Bản Cập Nhật Mới (${update.latestVersion})",
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            text = {
                Column {
                    Text(text = update.changelog, color = TextSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        updateInfoState = null
                        OtaUpdateManager.openInBrowser(context, update.downloadUrl)
                    }) {
                        Text("Trình Duyệt ↗", color = AppleTeal)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = {
                        updateInfoState = null
                        OtaUpdateManager.startDownloadAndInstall(context, update.downloadUrl)
                        Toast.makeText(context, "Đang tải bản cập nhật...", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Cập Nhật Ngay", color = AppleBlue, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { updateInfoState = null }) {
                    Text("Để Sau", color = TextTertiary)
                }
            }
        )
    }
}

// =================================================================================
// 2. TRANG TÍNH NĂNG (FEATURES TAB)
// =================================================================================
@Composable
private fun FeaturesTabScreen(
    prefs: PreferencesManager,
    hazeState: HazeState
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isScreenRecordingEnabled by prefs.isScreenRecordingIslandEnabled.collectAsState(initial = true)
    val isVpnEnabled by prefs.isVpnIslandEnabled.collectAsState(initial = true)
    val isPaletteColorEnabled by prefs.isPaletteColorEnabled.collectAsState(initial = true)
    val isChargingEnabled by prefs.isChargingEnabled.collectAsState(initial = true)
    val isChargingWattageEnabled by prefs.isChargingWattageEnabled.collectAsState(initial = true)
    val isChargingTimeToFullEnabled by prefs.isChargingTimeToFullEnabled.collectAsState(initial = true)

    val isCallIslandEnabled by prefs.isCallIslandEnabled.collectAsState(initial = true)
    val isBlockFullscreenCall by prefs.isBlockFullscreenCall.collectAsState(initial = true)

    val isMusicEnabled by prefs.isMusicEnabled.collectAsState(initial = true)
    val isChatEnabled by prefs.isChatEnabled.collectAsState(initial = true)
    val isPersistentMode by prefs.isPersistentMode.collectAsState(initial = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .padding(bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Tiêu đề đầu trang
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ApplePink.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = ApplePink,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Tính Năng Dynamic Pro",
                    style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
                )
                Text(
                    text = "Hiệu ứng đảo mở rộng & thông báo thông minh",
                    style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary)
                )
            }
        }

        // ================= KHUNG TÍNH NĂNG NÂNG CAO (GOM VÀO 1 KHUNG DUY NHẤT) =================
        LiquidGlassCard(hazeState = hazeState) {
            Text(
                text = "Đảo Mở Rộng Hệ Thống",
                style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = ApplePink)
            )
            Spacer(modifier = Modifier.height(10.dp))

            // 1. Đảo Quay Màn Hình
            GlassSettingItem(
                title = "Đảo Quay Màn Hình",
                subtitle = "Hiển thị chấm đỏ nhấp nháy HyperOS & thời gian quay thực tế",
                checked = isScreenRecordingEnabled,
                onCheckedChange = { scope.launch { prefs.setScreenRecordingIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            // 2. Đảo Trạng Thái VPN
            GlassSettingItem(
                title = "Đảo Trạng Thái VPN",
                subtitle = "Báo kết nối bảo mật khi mở 1.1.1.1, WireGuard, v2ray...",
                checked = isVpnEnabled,
                onCheckedChange = { scope.launch { prefs.setVpnIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            // 3. Màu Đảo Động Theo Bài Hát (Palette)
            GlassSettingItem(
                title = "Màu Đảo Động Theo Bài Hát",
                subtitle = "Tự động đổi màu viền phát sáng theo bìa album đang nghe",
                checked = isPaletteColorEnabled,
                onCheckedChange = { scope.launch { prefs.setPaletteColorEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            // 4. Hiệu Ứng Sạc Pin & Cáp USB
            GlassSettingItem(
                title = "Hiệu Ứng Sạc Pin & Cáp USB",
                subtitle = "Tự động bung đảo khi cắm sạc hiển thị phần trăm pin",
                checked = isChargingEnabled,
                onCheckedChange = { scope.launch { prefs.setChargingEnabled(it) } }
            )

            if (isChargingEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .border(0.5.dp, GlassBorderSubtle, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    GlassSettingItem(
                        title = "⚡ Công Suất Sạc (Watt)",
                        subtitle = "Đo công suất W thời gian thực & cấp độ Turbo/HyperCharge",
                        checked = isChargingWattageEnabled,
                        onCheckedChange = { scope.launch { prefs.setChargingWattageEnabled(it) } }
                    )

                    HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

                    GlassSettingItem(
                        title = "⏱️ Thời Gian Sạc Đầy Pin",
                        subtitle = "Ước tính thông minh thời gian nạp pin còn lại đến 100%",
                        checked = isChargingTimeToFullEnabled,
                        onCheckedChange = { scope.launch { prefs.setChargingTimeToFullEnabled(it) } }
                    )
                }
            }
        }

        // ================= KHUNG ĐẢO CUỘC GỌI ĐẾN (CALL ISLAND & CHẶN BUNG MÀN HÌNH) =================
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppleGreen.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = AppleGreen,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Đảo Cuộc Gọi Đến Thông Minh",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleGreen)
                    )
                    Text(
                        text = "Zalo, Messenger, Telegram, Cuộc gọi SIM",
                        style = Typography.bodyMedium.copy(fontSize = 11.sp, color = TextSecondary)
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            GlassSettingItem(
                title = "Hiển Thị Cuộc Gọi Trên Đảo",
                subtitle = "Hiện tên, avatar, nút Nhận cuộc gọi hoặc Từ chối trực tiếp trên đảo.",
                checked = isCallIslandEnabled,
                onCheckedChange = { scope.launch { prefs.setCallIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Chặn Bung Toàn Màn Hình Khi Gọi Đến",
                subtitle = "Ngăn Zalo/Messenger cướp trọn màn hình đang dùng, đẩy cuộc gọi lên đảo (Yêu cầu Shizuku).",
                checked = isBlockFullscreenCall,
                onCheckedChange = { checked ->
                    scope.launch {
                        prefs.setBlockFullscreenCall(checked)
                        withContext(Dispatchers.IO) {
                            ShizukuEngine.blockAllFullScreenCalls(checked)
                        }
                    }
                }
            )
        }

        // ================= KHUNG SỰ KIỆN THÔNG BÁO CHÍNH =================
        LiquidGlassCard(hazeState = hazeState) {
            Text(
                text = "Sự Kiện Thông Báo & Chạy Nền",
                style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleTeal)
            )
            Spacer(modifier = Modifier.height(10.dp))

            GlassSettingItem(
                title = "Trình Phát Nhạc Động",
                subtitle = "Spotify, Apple Music, Zing MP3, YouTube Music...",
                checked = isMusicEnabled,
                onCheckedChange = { scope.launch { prefs.setMusicEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Tin Nhắn Zalo & Messenger",
                subtitle = "Hiển thị đảo tức thì khi có thông báo tin nhắn mới",
                checked = isChatEnabled,
                onCheckedChange = { scope.launch { prefs.setChatEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Bất Tử Khi Xóa Đa Nhiệm",
                subtitle = "Không bị tắt đảo khi bấm nút xóa tất cả đa nhiệm",
                checked = isPersistentMode,
                onCheckedChange = { scope.launch { prefs.setPersistentMode(it) } }
            )
        }

        // Minh họa 4 cấp độ sạc phần cứng
        LiquidGlassCard(hazeState = hazeState) {
            Text(
                text = "Cấp Độ Sạc Neon Tự Động",
                style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChargingTierBadge(
                    name = "HyperCharge",
                    wattage = ">65W",
                    color = Color(0xFFFF3B30),
                    modifier = Modifier.weight(1f)
                )
                ChargingTierBadge(
                    name = "Turbo Charge",
                    wattage = "30-65W",
                    color = Color(0xFFFF9F0A),
                    modifier = Modifier.weight(1f)
                )
                ChargingTierBadge(
                    name = "Fast Charge",
                    wattage = "15-30W",
                    color = Color(0xFF30D158),
                    modifier = Modifier.weight(1f)
                )
                ChargingTierBadge(
                    name = "Standard",
                    wattage = "<15W",
                    color = Color(0xFF34C759),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // ================= DIALOGS =================
    }
}

@Composable
private fun ChargingTierBadge(
    name: String,
    wattage: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(0.5.dp, color.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = wattage,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// =================================================================================
// 3. TRANG QUẢN LÝ ỨNG DỤNG (APPS TAB)
// =================================================================================
@Composable
private fun AppsTabScreen(
    prefs: PreferencesManager,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val disabledPackages by prefs.disabledPackages.collectAsState(initial = emptySet())
    val disabledFloatingPackages by prefs.disabledFloatingPackages.collectAsState(initial = emptySet())

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(AppFilterTab.ALL) }
    var allApps by remember { mutableStateOf<List<ManagedAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val installed = try {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            } catch (_: Exception) {
                emptyList<ApplicationInfo>()
            }

            val mapped = installed.map { info ->
                val name = try {
                    info.loadLabel(pm).toString()
                } catch (_: Exception) {
                    info.packageName
                }
                val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                ManagedAppItem(
                    packageName = info.packageName,
                    name = name.ifBlank { info.packageName },
                    isSystemApp = isSystem
                )
            }.sortedWith(
                compareBy<ManagedAppItem> { it.isSystemApp }
                    .thenBy { it.name.lowercase() }
            )

            withContext(Dispatchers.Main) {
                allApps = mapped
                isLoading = false
            }
        }
    }

    val filteredApps = remember(searchQuery, selectedTab, allApps) {
        allApps.filter { app ->
            val matchTab = when (selectedTab) {
                AppFilterTab.ALL -> true
                AppFilterTab.USER -> !app.isSystemApp
                AppFilterTab.SYSTEM -> app.isSystemApp
            }
            val matchQuery = if (searchQuery.isBlank()) {
                true
            } else {
                app.name.contains(searchQuery, ignoreCase = true) ||
                        app.packageName.contains(searchQuery, ignoreCase = true)
            }
            matchTab && matchQuery
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(AppleOrange.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    tint = AppleOrange,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Quản Lý Ứng Dụng",
                    style = Typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 20.sp)
                )
                Text(
                    text = "Bật/tắt Đảo & Chặn thông báo nổi gốc HyperOS",
                    style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary)
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppleOrange.copy(alpha = 0.15f))
                    .border(0.5.dp, AppleOrange.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${filteredApps.size}",
                    color = AppleOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        // Thanh tìm kiếm Liquid Glass
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(0.5.dp, GlassBorderSubtle, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    singleLine = true,
                    cursorBrush = SolidColor(AppleOrange),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = "Tìm kiếm theo tên app hoặc package name...",
                                style = TextStyle(color = TextSecondary.copy(alpha = 0.6f), fontSize = 14.sp)
                            )
                        }
                        innerTextField()
                    }
                )
                if (searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Xóa",
                        tint = TextSecondary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { searchQuery = "" }
                    )
                }
            }
        }

        // 3 Tab Lọc (Tất cả, Cài đặt, Hệ thống)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppFilterTab.values().forEach { tab ->
                val isSelected = selectedTab == tab
                val tabCount = when (tab) {
                    AppFilterTab.ALL -> allApps.size
                    AppFilterTab.USER -> allApps.count { !it.isSystemApp }
                    AppFilterTab.SYSTEM -> allApps.count { it.isSystemApp }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) AppleOrange else Color.White.copy(alpha = 0.05f))
                        .border(
                            0.5.dp,
                            if (isSelected) AppleOrange else GlassBorderSubtle,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { selectedTab = tab }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${tab.label} ($tabCount)",
                        fontSize = 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) Color.White else TextSecondary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // Danh sách ứng dụng
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = AppleOrange)
            }
        } else if (filteredApps.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Không tìm thấy ứng dụng",
                        style = Typography.bodyMedium.copy(color = TextSecondary)
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 108.dp)
            ) {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    val isAppAllowed = prefs.isPackageAllowed(app.packageName, disabledPackages)
                    val isFloatingDisabled = disabledFloatingPackages.contains(app.packageName)

                    AppManagementItemCard(
                        app = app,
                        isAppAllowed = isAppAllowed,
                        isFloatingDisabled = isFloatingDisabled,
                        onToggleAppAllowed = { checked ->
                            scope.launch {
                                prefs.setAppAllowed(app.packageName, checked)
                            }
                        },
                        onToggleFloatingNotification = {
                            val willDisable = !isFloatingDisabled
                            val success = PermissionHelper.setMiuiFloatingNotificationEnabled(app.packageName, !willDisable)
                            scope.launch {
                                prefs.setAppFloatingDisabled(app.packageName, willDisable)
                            }
                            if (success) {
                                Toast.makeText(
                                    context,
                                    "Đã ${if (willDisable) "tắt" else "bật lại"} thông báo nổi của ${app.name} qua Shizuku",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                PermissionHelper.openAppNotificationSettings(context, app.packageName)
                                Toast.makeText(
                                    context,
                                    "Đã mở cài đặt: Vui lòng tắt mục 'Thông báo nổi' cho ${app.name}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

// =================================================================================
// HELPER COMPONENTS
// =================================================================================
@Composable
fun CompactPermissionItem(
    title: String,
    icon: ImageVector,
    isGranted: Boolean,
    onActionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (isGranted) AppleTeal.copy(alpha = 0.2f) else AppleOrange.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) AppleTeal else AppleOrange,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = title,
            style = Typography.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (isGranted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AppleTeal,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = "Đã cấp",
                    color = AppleTeal,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppleBlue.copy(alpha = 0.25f))
                    .border(0.5.dp, AppleBlue.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable { onActionClick() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Cấp quyền",
                    color = AppleBlue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun DevicePresetChip(
    name: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ApplePurple.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.08f))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .border(
                1.dp,
                if (selected) ApplePurple else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
fun LiveIslandHeader(
    isIdleEnabled: Boolean,
    islandWidth: Int,
    islandStyle: String = "minimal",
    islandMode: String = "compact"
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        if (islandMode == "widget" && isIdleEnabled) {
            // Preview: Widget Đảo Động Live (Expanded Dynamic Widget)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .height(58.dp)
                    .shadow(20.dp, RoundedCornerShape(29.dp), spotColor = Color.Black)
                    .clip(RoundedCornerShape(29.dp))
                    .background(Color(0xFF111113))
                    .border(1.2.dp, Color(0xFF333336), RoundedCornerShape(29.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Biểu tượng tròn lớn bên trái
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                when (islandStyle) {
                                    "weather" -> Color(0xFFFF9F0A).copy(alpha = 0.25f)
                                    "battery_temp" -> AppleGreen.copy(alpha = 0.25f)
                                    "ram_usage" -> Color(0xFF00F2FE).copy(alpha = 0.25f)
                                    else -> AppleTeal.copy(alpha = 0.25f)
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        when (islandStyle) {
                            "weather" -> Text(text = "⛅", fontSize = 20.sp)
                            "battery_temp" -> Icon(Icons.Default.Thermostat, null, tint = AppleGreen, modifier = Modifier.size(22.dp))
                            "ram_usage" -> Icon(Icons.Default.Memory, null, tint = Color(0xFF00F2FE), modifier = Modifier.size(22.dp))
                            else -> Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(AppleGreen))
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Tiêu đề & phụ đề bên phải
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (islandStyle) {
                                "weather" -> "⛅ 28°C • Nhiều mây"
                                "battery_temp" -> "🔋 34°C • Pin Mát Mẻ"
                                "ram_usage" -> "⚡ RAM Dùng: 5.4 GB"
                                "green_dot" -> "🟢 Trạng Thái Hệ Thống"
                                else -> "Dynamic Hyper Đang Chạy"
                            },
                            color = when (islandStyle) {
                                "weather" -> Color(0xFFFFD60A)
                                "battery_temp" -> AppleGreen
                                "ram_usage" -> Color(0xFF00F2FE)
                                else -> Color.White
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = when (islandStyle) {
                                "weather" -> "TP. Hồ Chí Minh • Độ ẩm 75%"
                                "battery_temp" -> "Nhiệt độ pin hệ thống • Tình trạng tốt"
                                "ram_usage" -> "Đa nhiệm HyperOS mượt mà"
                                else -> "Đảo Động Live 24/7"
                            },
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    // Nhãn Live nhấp nháy nhỏ
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "LIVE",
                            color = AppleTeal,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            // Preview: Đảo Thu Nhỏ Nốt Ruồi (Compact Pill)
            val calculatedWidth = (155 + islandWidth * 3).dp.coerceIn(165.dp, 260.dp)

            Box(
                modifier = Modifier
                    .width(if (isIdleEnabled) calculatedWidth else 90.dp)
                    .height(38.dp)
                    .shadow(18.dp, RoundedCornerShape(22.dp), spotColor = Color.Black)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black)
                    .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isIdleEnabled) AppleTeal else Color(0xFF555555))
                    )
                    Text(
                        text = if (isIdleEnabled) "Đang chạy" else "Tắt",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (isIdleEnabled) {
                        when (islandStyle) {
                            "green_dot" -> {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .shadow(3.dp, CircleShape, spotColor = Color(0xFF30D158))
                                        .clip(CircleShape)
                                        .background(Color(0xFF30D158))
                                )
                            }
                            "battery_temp" -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Thermostat,
                                        contentDescription = null,
                                        tint = AppleTeal,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "34°C",
                                        color = AppleTeal,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            "ram_usage" -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = Color(0xFF00F2FE),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "5.4G",
                                        color = Color(0xFF00F2FE),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            "weather" -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "⛅",
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "28°C",
                                        color = Color(0xFFFFD60A),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = AppleBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
