package com.ltm.dynamichyper.ui.screens

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ltm.dynamichyper.core.PermissionHelper
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.ui.components.CupertinoSwitch
import com.ltm.dynamichyper.ui.components.GlassCompactButton
import com.ltm.dynamichyper.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ManagedAppItem(
    val packageName: String,
    val name: String,
    val isSystemApp: Boolean
)

enum class AppFilterTab(val label: String) {
    ALL("Tất cả"),
    USER("Đã cài đặt"),
    SYSTEM("Hệ thống")
}

@Composable
fun AppManagementScreen(
    prefs: PreferencesManager,
    onBack: () -> Unit,
    hazeState: HazeState = remember { HazeState() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    BackHandler {
        onBack()
    }

    // Lấy dữ liệu Preferences
    val disabledPackages by prefs.disabledPackages.collectAsState(initial = emptySet())
    val disabledFloatingPackages by prefs.disabledFloatingPackages.collectAsState(initial = emptySet())

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(AppFilterTab.ALL) }
    var allApps by remember { mutableStateOf<List<ManagedAppItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    // Tải toàn bộ danh sách ứng dụng trong background
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

    // Lọc danh sách app theo Search Query và Tab được chọn
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .haze(hazeState)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // ================= TOP BAR =================
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
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(0.5.dp, GlassBorderSubtle, CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

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
                        .background(AppleBlue.copy(alpha = 0.15f))
                        .border(0.5.dp, AppleBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${filteredApps.size}",
                        color = AppleBlue,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            // ================= THANH TÌM KIẾM LIQUID GLASS =================
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
                        textStyle = TextStyle(
                            color = TextPrimary,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(AppleBlue),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Tìm theo tên app hoặc package name...",
                                    style = TextStyle(color = TextSecondary.copy(alpha = 0.6f), fontSize = 14.sp)
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Xóa tìm kiếm",
                            tint = TextSecondary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { searchQuery = "" }
                        )
                    }
                }
            }

            // ================= BỘ LỌC TAB (TẤT CẢ / CÀI ĐẶT / HỆ THỐNG) =================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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
                            .background(if (isSelected) AppleBlue else Color.White.copy(alpha = 0.05f))
                            .border(
                                0.5.dp,
                                if (isSelected) AppleBlue else GlassBorderSubtle,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { selectedTab = tab }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${tab.label} ($tabCount)",
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else TextSecondary
                        )
                    }
                }
            }

            // ================= DANH SÁCH ỨNG DỤNG (LAZY COLUMN) =================
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = AppleBlue)
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
                            text = "Không tìm thấy ứng dụng phù hợp",
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
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
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
}

/**
 * Thẻ hiển thị từng ứng dụng chuẩn iOS Liquid Glass
 */
@Composable
fun AppManagementItemCard(
    app: ManagedAppItem,
    isAppAllowed: Boolean,
    isFloatingDisabled: Boolean,
    onToggleAppAllowed: (Boolean) -> Unit,
    onToggleFloatingNotification: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(0.5.dp, GlassBorderSubtle, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            AppIconRenderer(
                packageName = app.packageName,
                pm = pm,
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = app.name,
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (app.isSystemApp) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(ApplePurple.copy(alpha = 0.2f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Hệ thống",
                                color = ApplePurple,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = Typography.bodyMedium.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.4f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Switch Bật/Tắt Đảo (chuẩn iOS Liquid Glass, tinh gọn)
            CupertinoSwitch(
                checked = isAppAllowed,
                onCheckedChange = onToggleAppAllowed
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = GlassBorderSubtle)
        Spacer(modifier = Modifier.height(8.dp))

        // Dòng quản lý thông báo nổi gốc HyperOS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(end = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isFloatingDisabled) AppleGreen else AppleYellow)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFloatingDisabled) "Đã chặn nổi gốc HyperOS" else "Nổi gốc: Đang bật",
                    style = Typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        color = if (isFloatingDisabled) AppleGreen else AppleYellow
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            GlassCompactButton(
                text = if (isFloatingDisabled) "Bật Lại Nổi Gốc" else "Tắt Nổi Gốc",
                isPrimary = !isFloatingDisabled,
                color = if (isFloatingDisabled) AppleTeal else AppleOrange,
                onClick = onToggleFloatingNotification
            )
        }
    }
}

/**
 * Tải icon bất đồng bộ cực mượt mà
 */
@Composable
fun AppIconRenderer(
    packageName: String,
    pm: PackageManager,
    modifier: Modifier = Modifier
) {
    var iconBitmap by remember(packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                val drawable = pm.getApplicationIcon(packageName)
                val bmp = drawable.toBitmap(width = 84, height = 84, config = Bitmap.Config.ARGB_8888)
                val imgBmp = bmp.asImageBitmap()
                withContext(Dispatchers.Main) {
                    iconBitmap = imgBmp
                }
            } catch (_: Exception) {}
        }
    }

    if (iconBitmap != null) {
        Image(
            bitmap = iconBitmap!!,
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Android,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.4f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
