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
