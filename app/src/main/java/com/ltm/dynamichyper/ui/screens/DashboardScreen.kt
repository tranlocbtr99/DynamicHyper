package com.ltm.dynamichyper.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltm.dynamichyper.core.IdleIslandManager
import com.ltm.dynamichyper.core.ShizukuEngine
import com.ltm.dynamichyper.data.PreferencesManager
import com.ltm.dynamichyper.ui.components.*
import com.ltm.dynamichyper.ui.theme.*
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    prefs: PreferencesManager,
    hazeState: HazeState = remember { HazeState() }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    // Preferences states
    val isIdleIslandEnabled by prefs.isIdleIslandEnabled.collectAsState(initial = true)
    val idleIslandWidth by prefs.idleIslandWidth.collectAsState(initial = 8)
    val isMusicEnabled by prefs.isMusicEnabled.collectAsState(initial = true)
    val isChatEnabled by prefs.isChatEnabled.collectAsState(initial = true)
    val isChargingEnabled by prefs.isChargingEnabled.collectAsState(initial = true)
    val isPersistentMode by prefs.isPersistentMode.collectAsState(initial = true)

    // System status
    var isShizukuReady by remember { mutableStateOf(ShizukuEngine.hasShizukuPermission()) }
    var isOptimized by remember { mutableStateOf(false) }
    var showAdbDialog by remember { mutableStateOf(false) }

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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ================= 1. LIVE DYNAMIC ISLAND PREVIEW =================
            LiveIslandHeader(
                isIdleEnabled = isIdleIslandEnabled,
                islandWidth = idleIslandWidth
            )

            // ================= 2. ONE-TOUCH MASTER ACTIVATION =================
            LiquidGlassCard(hazeState = hazeState) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(if (isOptimized || isShizukuReady) AppleTeal.copy(alpha = 0.2f) else AppleBlue.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isOptimized || isShizukuReady) Icons.Default.CheckCircle else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isOptimized || isShizukuReady) AppleTeal else AppleBlue
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Kích Hoạt Tự Động 1-Chạm",
                            style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (isShizukuReady) "Đã kết nối Shizuku - Sẵn sàng 100%" else "Bấm để tối ưu hóa toàn bộ HyperOS",
                            style = Typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LiquidActionButton(
                    text = if (isShizukuReady) "ÁP DỤNG TẤT CẢ TỐI ƯU HÓA" else "KÍCH HOẠT 1-CHẠM NGAY",
                    onClick = {
                        if (ShizukuEngine.isShizukuAvailable()) {
                            if (!ShizukuEngine.hasShizukuPermission()) {
                                ShizukuEngine.requestShizukuPermission()
                            } else {
                                val success = ShizukuEngine.applyOneTouchOptimizations(context)
                                isOptimized = success
                                Toast.makeText(
                                    context,
                                    if (success) "Đã tối ưu hóa thành công! Đảo đã kích hoạt." else "Đã chạy tối ưu hóa.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            showAdbDialog = true
                        }
                    }
                )

                if (!isShizukuReady) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Không cần cài Shizuku! Có thể dùng Gỡ lỗi không dây nội bộ.",
                        style = Typography.labelSmall.copy(color = AppleYellow),
                        modifier = Modifier.clickable { showAdbDialog = true }
                    )
                }
            }

            // ================= 3. ĐẢO CHỜ 24/7 (IDLE ISLAND) =================
            LiquidGlassCard(hazeState = hazeState) {
                Text(
                    text = "Đảo Chờ Liên Tục (Idle Island 24/7)",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleBlue)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Duy trì đảo đen ôm sát camera trước cả khi không có thông báo.",
                    style = Typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = GlassBorderSubtle)
                Spacer(modifier = Modifier.height(10.dp))

                GlassSettingItem(
                    title = "Bật Đảo Chờ 24/7",
                    subtitle = "Luôn hiển thị viên thuốc camera HyperOS",
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

                AnimatedVisibility(visible = isIdleIslandEnabled) {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Độ rộng đảo chờ: ${idleIslandWidth}",
                                style = Typography.bodyLarge.copy(color = TextPrimary)
                            )
                            Text(
                                text = "Chuẩn Redmi/Xiaomi: 6 - 12",
                                style = Typography.bodyMedium.copy(color = AppleTeal)
                            )
                        }

                        Slider(
                            value = idleIslandWidth.toFloat(),
                            onValueChange = { value ->
                                scope.launch {
                                    val newW = value.toInt()
                                    prefs.setIdleIslandWidth(newW)
                                    IdleIslandManager.updateIdleIslandWidth(context, newW)
                                }
                            },
                            valueRange = 4f..20f,
                            steps = 16,
                            colors = SliderDefaults.colors(
                                thumbColor = AppleBlue,
                                activeTrackColor = AppleBlue,
                                inactiveTrackColor = Color(0xFF3A3A3C)
                            )
                        )
                    }
                }
            }

            // ================= 4. TÍNH NĂNG ỨNG DỤNG =================
            LiquidGlassCard(hazeState = hazeState) {
                Text(
                    text = "Ứng Dụng & Sự Kiện Hỗ Trợ",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = ApplePurple)
                )
                Spacer(modifier = Modifier.height(12.dp))

                GlassSettingItem(
                    title = "Trình Phát Nhạc Động",
                    subtitle = "Spotify, Apple Music, Zing MP3, YouTube...",
                    checked = isMusicEnabled,
                    onCheckedChange = { scope.launch { prefs.setMusicEnabled(it) } }
                )

                HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

                GlassSettingItem(
                    title = "Tin Nhắn Zalo & Messenger",
                    subtitle = "Hiển thị đảo tức thì khi có tin nhắn mới",
                    checked = isChatEnabled,
                    onCheckedChange = { scope.launch { prefs.setChatEnabled(it) } }
                )

                HorizontalDivider(color = GlassBorderSubtle, modifier = Modifier.padding(vertical = 4.dp))

                GlassSettingItem(
                    title = "Hiệu Ứng Sạc Pin & USB",
                    subtitle = "Báo sạc nhanh, cắm cáp kết nối",
                    checked = isChargingEnabled,
                    onCheckedChange = { scope.launch { prefs.setChargingEnabled(it) } }
                )
            }

            // ================= 5. BẤT TỬ KHI XÓA ĐA NHIỆM =================
            LiquidGlassCard(hazeState = hazeState) {
                Text(
                    text = "Cơ Chế Bất Tử (Anti-Task Clear)",
                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = AppleTeal)
                )
                Spacer(modifier = Modifier.height(8.dp))
                GlassSettingItem(
                    title = "Chạy Ngầm Liên Tục",
                    subtitle = "Tự hồi sinh ngay cả khi bấm nút xóa tất cả đa nhiệm",
                    checked = isPersistentMode,
                    onCheckedChange = { scope.launch { prefs.setPersistentMode(it) } }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Dialog hướng dẫn nếu chưa kích hoạt
        if (showAdbDialog) {
            AlertDialog(
                onDismissRequest = { showAdbDialog = false },
                containerColor = Color(0xFF1E1E20),
                title = {
                    Text(text = "Kích Hoạt Không Cần Shizuku", fontWeight = FontWeight.Bold, color = Color.White)
                },
                text = {
                    Column {
                        Text("Bạn có thể kích hoạt bằng 2 cách rất nhanh:")
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("1. Shizuku: Mở Shizuku và quay lại đây bấm 1 chạm.")
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("2. Mở Quyền Truy Cập Thông Báo: Cấp quyền thủ công cho Dynamic Hyper trong Cài đặt hệ thống.")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showAdbDialog = false
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) {
                        Text("Mở Cài Đặt Quyền", color = AppleBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAdbDialog = false }) {
                        Text("Đóng", color = TextTertiary)
                    }
                }
            )
        }
    }
}

/**
 * Mô phỏng trực quan Đảo Động trên đỉnh màn hình
 */
@Composable
fun LiveIslandHeader(
    isIdleEnabled: Boolean,
    islandWidth: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        val calculatedWidth = (80 + islandWidth * 6).dp

        Box(
            modifier = Modifier
                .width(if (isIdleEnabled) calculatedWidth.coerceIn(90.dp, 180.dp) else 80.dp)
                .height(34.dp)
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isIdleEnabled) AppleTeal else Color(0xFF555555))
                )
                Text(
                    text = if (isIdleEnabled) "Đang hoạt động" else "Tắt",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = if (isIdleEnabled) AppleBlue else Color.Gray,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
