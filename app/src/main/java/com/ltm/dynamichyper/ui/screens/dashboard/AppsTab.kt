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
// 3. TRANG QUẢN LÝ ỨNG DỤNG (APPS TAB)
// =================================================================================
@Composable
internal fun AppsTabScreen(
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

