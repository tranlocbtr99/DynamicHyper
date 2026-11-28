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
// 2. TRANG TÍNH NĂNG (FEATURES TAB)
// =================================================================================
@Composable
internal fun FeaturesTabScreen(
    prefs: PreferencesManager,
    hazeState: HazeState
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val isScreenRecordingEnabled by prefs.isScreenRecordingIslandEnabled.collectAsState(initial = true)
    val isVpnEnabled by prefs.isVpnIslandEnabled.collectAsState(initial = true)
    val isRingerEnabled by prefs.isRingerIslandEnabled.collectAsState(initial = true)
    val isBluetoothEnabled by prefs.isBluetoothIslandEnabled.collectAsState(initial = true)
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

        // ================= 1. ĐẢO CUỘC GỌI & CHỐNG CƯỚP MÀN HÌNH =================
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
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Cuộc Gọi Đến",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleGreen, fontSize = 15.sp)
                    )
                    Text(
                        text = "Zalo, Messenger, Telegram, SIM",
                        style = Typography.bodySmall.copy(fontSize = 11.sp, color = TextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            GlassSettingItem(
                title = "Hiển thị cuộc gọi trên đảo",
                subtitle = "Trả lời hoặc từ chối trực tiếp từ đảo nốt ruồi",
                checked = isCallIslandEnabled,
                onCheckedChange = { scope.launch { prefs.setCallIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Chặn bung chiếm màn hình",
                subtitle = "Đưa cuộc gọi lên đảo, không cướp full màn hình đang dùng (Shizuku)",
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

        // ================= 2. ĐẢO HỆ THỐNG & TRẠNG THÁI =================
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(ApplePink.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Widgets,
                        contentDescription = null,
                        tint = ApplePink,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Đảo Mở Rộng Hệ Thống",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = ApplePink, fontSize = 15.sp)
                    )
                    Text(
                        text = "Quay màn hình, VPN, hiệu ứng sạc pin",
                        style = Typography.bodySmall.copy(fontSize = 11.sp, color = TextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            GlassSettingItem(
                title = "Đảo Quay Màn Hình",
                subtitle = "Chấm đỏ nhấp nháy & thời gian quay thực tế của HyperOS",
                checked = isScreenRecordingEnabled,
                onCheckedChange = { scope.launch { prefs.setScreenRecordingIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Đảo Trạng Thái VPN",
                subtitle = "Biểu tượng ổ khóa bảo mật khi kết nối 1.1.1.1, VPN",
                checked = isVpnEnabled,
                onCheckedChange = { scope.launch { prefs.setVpnIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Đảo Chế Độ Chuông / Rung / Im Lặng",
                subtitle = "Bung đảo báo trạng thái âm thanh kiểu iOS khi chuyển chế độ chuông, rung, im lặng",
                checked = isRingerEnabled,
                onCheckedChange = { scope.launch { prefs.setRingerIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Đảo Kết Nối Tai Nghe Bluetooth & % Pin",
                subtitle = "Bung đảo hiển thị tên tai nghe và phần trăm dung lượng pin khi kết nối Bluetooth",
                checked = isBluetoothEnabled,
                onCheckedChange = { scope.launch { prefs.setBluetoothIslandEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Hiệu Ứng Cắm Sạc Pin (Tự ẩn sau 6s)",
                subtitle = "Bung đảo hiển thị phần trăm pin và công suất sạc trong 6 giây rồi tự trả về Đảo Chờ 24/7",
                checked = isChargingEnabled,
                onCheckedChange = { scope.launch { prefs.setChargingEnabled(it) } }
            )

            if (isChargingEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.03f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    GlassSettingItem(
                        title = "⚡ Công Suất Sạc (Watt)",
                        subtitle = "Đo công suất W & cấp độ sạc nhanh thời gian thực",
                        checked = isChargingWattageEnabled,
                        onCheckedChange = { scope.launch { prefs.setChargingWattageEnabled(it) } }
                    )

                    HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

                    GlassSettingItem(
                        title = "⏱️ Ước Tính Thời Gian Sạc Đầy",
                        subtitle = "Hiển thị số phút nạp pin còn lại đến 100%",
                        checked = isChargingTimeToFullEnabled,
                        onCheckedChange = { scope.launch { prefs.setChargingTimeToFullEnabled(it) } }
                    )
                }
            }
        }

        // ================= 3. TRÌNH PHÁT NHẠC & CHẠY NỀN =================
        LiquidGlassCard(hazeState = hazeState) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AppleTeal.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = AppleTeal,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Âm Nhạc & Chạy Nền",
                        style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleTeal, fontSize = 15.sp)
                    )
                    Text(
                        text = "Spotify, Apple Music, Zing MP3, duy trì dịch vụ",
                        style = Typography.bodySmall.copy(fontSize = 11.sp, color = TextSecondary)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            GlassSettingItem(
                title = "Trình Phát Nhạc Động",
                subtitle = "Hiển thị đảo đĩa xoay và thanh sóng âm nhạc",
                checked = isMusicEnabled,
                onCheckedChange = { scope.launch { prefs.setMusicEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Màu Đảo Theo Bìa Nhạc (Palette)",
                subtitle = "Tự động đổi màu viền phát sáng neon theo bài hát đang nghe",
                checked = isPaletteColorEnabled,
                onCheckedChange = { scope.launch { prefs.setPaletteColorEnabled(it) } }
            )

            HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

            GlassSettingItem(
                title = "Bất Tử Khi Xóa Đa Nhiệm",
                subtitle = "Bảo vệ Đảo Chờ không bị tắt khi bấm nút xóa tất cả đa nhiệm",
                checked = isPersistentMode,
                onCheckedChange = { scope.launch { prefs.setPersistentMode(it) } }
            )
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

