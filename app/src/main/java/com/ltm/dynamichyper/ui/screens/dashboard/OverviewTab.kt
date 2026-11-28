package com.ltm.dynamichyper.ui.screens.dashboard

import com.ltm.dynamichyper.ui.screens.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import com.ltm.dynamichyper.core.FocusProtocolHelper
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



// =================================================================================
// 1. TRANG TỔNG QUAN (OVERVIEW TAB)
// =================================================================================
@Composable
internal fun OverviewTabScreen(
    prefs: PreferencesManager,
    hazeState: HazeState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val currentVersion = remember { "v${OtaUpdateManager.getCurrentVersion(context).removePrefix("v")}" }

    val isIdleIslandEnabled by prefs.isIdleIslandEnabled.collectAsState(initial = true)
    val idleIslandWidth by prefs.idleIslandWidth.collectAsState(initial = 10)
    val idleIslandStyle by prefs.idleIslandStyle.collectAsState(initial = "minimal")
    val idleIslandMode by prefs.idleIslandMode.collectAsState(initial = "compact")
    val isNotificationGradientGlow by prefs.isNotificationGradientGlow.collectAsState(initial = true)
    val isPersistentNotificationIsland by prefs.isPersistentNotificationIsland.collectAsState(initial = true)
    val isHideOriginalNotification by prefs.isHideOriginalNotification.collectAsState(initial = true)
    val isSuppressChatBubble by prefs.isSuppressChatBubble.collectAsState(initial = true)
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
    var isOneTimeActivated by remember { mutableStateOf(ShizukuEngine.isOneTimeOptimizationActive(context)) }
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
                isOneTimeActivated = ShizukuEngine.isOneTimeOptimizationActive(context)
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
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }
    var updateInfoState by remember { mutableStateOf<UpdateInfo?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isPermissionsExpanded by remember { mutableStateOf(false) }

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
        isOneTimeActivated = ShizukuEngine.isOneTimeOptimizationActive(context)
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

        // Hàng điều khiển nhanh: Nhật Ký • Cập Nhật OTA • Ủng Hộ (Clean Unified Glass Bar)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .border(0.5.dp, GlassBorderSubtle, RoundedCornerShape(18.dp))
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Nút Kiểm Lỗi Beta (Nhật ký)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showDiagnosticDialog = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = AppleOrange,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Nhật Ký",
                    style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextPrimary)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(GlassBorderSubtle)
            )

            // Nút Cập nhật OTA
            Row(
                modifier = Modifier
                    .weight(1.2f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        scope.launch {
                            isCheckingUpdate = true
                            val update = OtaUpdateManager.checkUpdate(context)
                            isCheckingUpdate = false
                            if (update != null && update.hasUpdate) {
                                updateInfoState = update
                            } else {
                                Toast.makeText(context, "Đang dùng bản mới nhất ($currentVersion)!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = AppleBlue
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "OTA $currentVersion",
                    style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppleBlue)
                )
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(20.dp)
                    .background(GlassBorderSubtle)
            )

            // Nút Ủng hộ
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { showDonateDialog = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = ApplePink,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Ủng Hộ",
                    style = Typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ApplePink)
                )
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

        // Hero Status & Điều Khiển 1-Chạm (Apple Liquid Glass Clean Design)
        val isShizukuOrStandaloneReady = isShizukuReady || isOneTimeActivated
        val isAllReady = isShizukuOrStandaloneReady && isNotificationAccessGranted && isPostNotificationGranted && isFloatingNotificationGranted
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (isAllReady) AppleTeal.copy(alpha = 0.2f) else AppleBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isAllReady) Icons.Default.CheckCircle else Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isAllReady) AppleTeal else AppleBlue,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isAllReady && !isShizukuReady && isOneTimeActivated -> "Đảo Hoạt Động Độc Lập 24/7"
                            isAllReady -> "Đảo Đang Hoạt Động"
                            else -> "Kích Hoạt Tự Động"
                        },
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    )
                    Text(
                        text = when {
                            isAllReady && !isShizukuReady && isOneTimeActivated ->
                                "XMSF đã khóa • Tự chạy khi tắt Shizuku"
                            isAllReady ->
                                "XMSF đã khóa vĩnh viễn • Có thể tắt Shizuku"
                            else ->
                                "Cần cấp Shizuku 1 lần để khóa XMSF"
                        },
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = if (isAllReady) AppleTeal else TextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Nút Bật Đảo Chính
            LiquidActionButton(
                text = "⚡ BẬT ĐẢO",
                onClick = {
                    if (ShizukuEngine.isShizukuAvailable() && ShizukuEngine.hasShizukuPermission()) {
                        ShizukuEngine.applyXmsfBypass(context)
                        isOneTimeActivated = true
                    }
                    IdleIslandManager.resetCache()
                    IdleIslandManager.showIdleIsland(context, idleIslandWidth, force = true)
                    FocusProtocolHelper.sendVisualTestIsland(context)
                    Toast.makeText(context, "Đang bật Đảo Động...", Toast.LENGTH_SHORT).show()
                }
            )

            // Nút phụ Kích hoạt / Tối ưu hóa (Ẩn khi đã kích hoạt 1 lần và đủ quyền để giao diện sạch sẽ)
            if (!isAllReady) {
                Spacer(modifier = Modifier.height(8.dp))
                LiquidActionButton(
                    text = if (isShizukuReady) "KÍCH HOẠT 1 LẦN (TẮT SHIZUKU VẪN CHẠY)" else "KÍCH HOẠT NHANH TẤT CẢ QUYỀN",
                    onClick = {
                        if (ShizukuEngine.isShizukuAvailable()) {
                            if (!ShizukuEngine.hasShizukuPermission()) {
                                ShizukuEngine.requestShizukuPermission()
                            } else {
                                val success = ShizukuEngine.applyOneTouchOptimizations(context)
                                PermissionHelper.setMiuiFloatingNotificationEnabled(context.packageName, true)
                                isShizukuReady = true
                                isOneTimeActivated = ShizukuEngine.isOneTimeOptimizationActive(context)
                                isNotificationAccessGranted = PermissionHelper.isNotificationAccessGranted(context)
                                isPostNotificationGranted = PermissionHelper.isPostNotificationGranted(context)
                                isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
                                isBatteryOptimizationIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
                                isAutoStartGranted = PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_BG_START)
                                isLockscreenOpGranted = PermissionHelper.isMiuiOpAllowed(context, PermissionHelper.OP_MIUI_SHOW_WHEN_LOCKED)
                                scope.launch { prefs.setAutoStartMarked(true) }
                                IdleIslandManager.resetCache()
                                IdleIslandManager.showIdleIsland(context, idleIslandWidth)
                                Toast.makeText(
                                    context,
                                    if (success) "Đã kích hoạt 1 lần thành công! Bây giờ bạn có thể tắt Wireless Debugging & Shizuku." else "Đã áp dụng tối ưu hóa hệ thống.",
                                    Toast.LENGTH_LONG
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
        }

        // Quyền hệ thống (Sleek Collapsible Panel)
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isPermissionsExpanded = !isPermissionsExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (grantedCount >= 7) Icons.Default.CheckCircle else Icons.Default.Security,
                        contentDescription = null,
                        tint = if (grantedCount >= 7) AppleTeal else AppleOrange,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Quyền Hệ Thống",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (grantedCount >= 7) AppleTeal.copy(alpha = 0.2f) else AppleOrange.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = if (grantedCount >= 7) "Hoàn hảo $grantedCount/8" else "$grantedCount/8 Đã cấp",
                            style = Typography.labelSmall.copy(
                                color = if (grantedCount >= 7) AppleTeal else AppleOrange,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isPermissionsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Mở rộng quyền",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = isPermissionsExpanded || grantedCount < 7,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(10.dp))
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

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = GlassBorderSubtle)
                    Spacer(modifier = Modifier.height(10.dp))

                    // Nút bấm duy nhất: Thu hồi toàn bộ quyền đã cấp & Khôi phục nguyên bản
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFFF453A).copy(alpha = 0.14f))
                            .border(0.8.dp, Color(0xFFFF453A).copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                            .clickable { showRevokeConfirmDialog = true }
                            .padding(vertical = 11.dp, horizontal = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.RemoveModerator,
                                contentDescription = null,
                                tint = Color(0xFFFF453A),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "THU HỒI TOÀN BỘ QUYỀN ĐÃ CẤP (1-CHẠM)",
                                style = Typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFFFF453A)
                                )
                            )
                        }
                    }
                }
            }
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
                    text = "Kiểu Hiển Thị Đảo Chờ",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleTeal, fontSize = 14.sp)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Hàng 1: Đen Tối Giản, Chấm Xanh iOS, Thời Tiết
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DevicePresetChip(
                        name = "Đen Tối Giản",
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
                        name = "Chấm Xanh iOS",
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

                // Hàng 2: Nhiệt Độ Pin, Bộ Nhớ RAM
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
                        name = "Bộ Nhớ RAM",
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
                    subtitle = "Vòng phát quang gradient neon & màu nhấn theo từng ứng dụng khi thông báo bung ra.",
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

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = GlassBorderSubtle)

                GlassSettingItem(
                    title = "Ẩn Thông Báo Gốc Khi Lên Đảo",
                    subtitle = "Tự động ẩn thông báo tin nhắn gốc trên trung tâm thông báo sau khi đã đưa lên Đảo Động để tránh bị trùng lặp 2 thông báo.",
                    checked = isHideOriginalNotification,
                    onCheckedChange = { checked ->
                        scope.launch {
                            prefs.setHideOriginalNotification(checked)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = GlassBorderSubtle)

                GlassSettingItem(
                    title = "Tắt Thông Báo Bong Bóng Chat Lặp Lại",
                    subtitle = "Chặn bong bóng chat (Messenger, Zalo) tự phát lại thông báo liên tục khi đang mở hoặc kéo bong bóng trên màn hình.",
                    checked = isSuppressChatBubble,
                    onCheckedChange = { checked ->
                        scope.launch {
                            prefs.setSuppressChatBubble(checked)
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
                        text = "Phiên bản $currentVersion • Liquid Glass",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = AppleTeal)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = GlassBorderSubtle)
            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Copyright © 2026 Trần Minh Lộc (tranlocbtr99@gmail.com). Bảo lưu mọi quyền (All Rights Reserved).\n" +
                        "• Phần mềm độc quyền — Nghiêm cấm sao chép, trích xuất, dịch ngược hoặc thương mại hóa trái phép.\n" +
                        "• Tương thích: Xiaomi, Redmi, POCO (HyperOS 1, 2, 3, 4)",
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

    if (showRevokeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeConfirmDialog = false },
            containerColor = Color(0xFF1E1E22),
            icon = {
                Icon(
                    imageVector = Icons.Default.RemoveModerator,
                    contentDescription = null,
                    tint = Color(0xFFFF453A),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Thu Hồi Toàn Bộ Quyền Đã Cấp?",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = Color.White)
                )
            },
            text = {
                Text(
                    text = "Thao tác này sẽ bằng 1 nút bấm duy nhất:\n" +
                        "• Tắt toàn bộ Đảo Động & dịch vụ nền đang chạy.\n" +
                        "• Thu hồi quyền Đọc Thông Báo, quyền Gửi Thông Báo, quyền Bluetooth, Tự khởi chạy, Hiển thị trên màn hình khóa & Thông báo nổi.\n" +
                        "• Mở lại mạng nguyên bản cho com.xiaomi.xmsf và khôi phục quyền cuộc gọi toàn màn hình cho Zalo/Messenger.",
                    style = Typography.bodySmall.copy(color = Color.White.copy(alpha = 0.85f), lineHeight = 19.sp)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRevokeConfirmDialog = false
                        scope.launch {
                            prefs.setAutoStartMarked(false)
                            prefs.setIdleIslandEnabled(false)
                            IdleIslandManager.hideIdleIsland(context)
                            try {
                                context.startService(
                                    Intent(context, com.ltm.dynamichyper.service.KeepAliveService::class.java).apply {
                                        action = com.ltm.dynamichyper.service.KeepAliveService.ACTION_STOP
                                    }
                                )
                            } catch (_: Throwable) {}

                            val revokedViaShizuku = withContext(Dispatchers.IO) {
                                ShizukuEngine.revokeAllGrantedPermissions(context)
                            }
                            isOneTimeActivated = false
                            isPostNotificationGranted = PermissionHelper.isPostNotificationGranted(context)
                            isNotificationAccessGranted = PermissionHelper.isNotificationAccessGranted(context)
                            isFloatingNotificationGranted = PermissionHelper.isFloatingNotificationAllowed(context)
                            isBatteryOptimizationIgnored = PermissionHelper.isBatteryOptimizationIgnored(context)
                            isLockscreenOpGranted = PermissionHelper.isMiuiOpAllowed(context, 10008)
                            isAutoStartGranted = false

                            if (revokedViaShizuku) {
                                Toast.makeText(
                                    context,
                                    "Đã thu hồi toàn bộ quyền đã cấp & khôi phục hệ thống nguyên bản!",
                                    Toast.LENGTH_LONG
                                ).show()
                            } else {
                                Toast.makeText(
                                    context,
                                    "Đã tắt Đảo & thu hồi quyền ứng dụng. Đang mở trang Quyền để tắt nốt quyền hệ thống...",
                                    Toast.LENGTH_LONG
                                ).show()
                                PermissionHelper.openNotificationAccessSettings(context)
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF453A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Thu Hồi Ngay", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeConfirmDialog = false }) {
                    Text("Hủy", color = TextSecondary)
                }
            }
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
