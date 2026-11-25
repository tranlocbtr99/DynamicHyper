package com.ltm.dynamichyper.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltm.dynamichyper.ui.components.LiquidActionButton
import com.ltm.dynamichyper.ui.theme.*

/**
 * Màn hình Chào Mừng & Bắt Buộc Đồng Ý Điều Khoản Khi Mở App Lần Đầu
 * Người dùng bắt buộc phải tích chọn xác nhận mới mở khóa nút "BẮT ĐẦU SỬ DỤNG"
 */
@Composable
fun OnboardingTermsScreen(
    onAcceptAndStart: () -> Unit
) {
    val scrollState = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    var isConfirmed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(10.dp))

            // Header Icon
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .shadow(16.dp, RoundedCornerShape(20.dp), spotColor = AppleBlue.copy(alpha = 0.5f))
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(listOf(AppleBlue, ApplePurple))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Dynamic Hyper",
                style = Typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 26.sp
                )
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Điều Khoản Sử Dụng & Miễn Trừ Trách Nhiệm",
                style = Typography.bodyMedium.copy(color = AppleTeal, fontWeight = FontWeight.SemiBold)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Scrollable Terms Box
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF17171A))
                    .border(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.2f), Color.White.copy(alpha = 0.05f))
                        ),
                        RoundedCornerShape(22.dp)
                    )
                    .padding(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = "1. Phạm Vi & Tuyên Bố Miễn Trừ Trách Nhiệm (Disclaimer):",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Ứng dụng hoạt động dựa trên giao thức Focus Notification của Xiaomi HyperOS và quyền do bạn tự nguyện cấp; KHÔNG can thiệp kernel hay root.\n" +
                               "• Phần mềm được cung cấp theo nguyên trạng (\"AS IS\"). Tác giả được miễn trừ mọi trách nhiệm đối với xung đột hệ thống do ROM tùy biến hoặc thay đổi API từ nhà sản xuất.",
                        style = Typography.bodyMedium.copy(fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "2. Quyền Riêng Tư & Xử Lý Cục Bộ:",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Cam kết 100% KHÔNG thu thập, lưu trữ máy chủ hay chia sẻ bất kỳ nội dung tin nhắn, cuộc gọi, OTP hay danh bạ nào của bạn.\n" +
                               "• Quyền truy cập thông báo chỉ được xử lý cục bộ trên thiết bị để hiển thị đảo động.",
                        style = Typography.bodyMedium.copy(fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "3. Tuyên Bố Bản Quyền & Sở Hữu Trí Tuệ:",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Copyright © 2026 Trần Minh Lộc (tranlocbtr99@gmail.com). Bảo lưu mọi quyền (All Rights Reserved).\n" +
                               "• Nghiêm cấm tuyệt đối mọi hành vi sao chép, trích xuất mã nguồn, dịch ngược (decompile), đóng gói lại hoặc thương mại hóa trái phép.",
                        style = Typography.bodyMedium.copy(fontSize = 13.sp, color = TextSecondary, lineHeight = 20.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Checkbox Xác Nhận Chuẩn iOS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isConfirmed = !isConfirmed
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val boxBg by animateColorAsState(
                    targetValue = if (isConfirmed) AppleTeal else Color(0xFF333336),
                    animationSpec = spring(),
                    label = "checkBg"
                )

                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(boxBg)
                        .border(1.dp, if (isConfirmed) AppleTeal else Color.Gray, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isConfirmed) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Tôi đã đọc và đồng ý với Điều khoản sử dụng & Tuyên bố miễn trừ trách nhiệm",
                    style = Typography.bodyMedium.copy(
                        color = if (isConfirmed) Color.White else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isConfirmed) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Nút BẮT ĐẦU SỬ DỤNG (Chỉ bấm được khi đã xác nhận)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (isConfirmed) LiquidGlowGradient
                        else Brush.horizontalGradient(listOf(Color(0xFF333336), Color(0xFF262629)))
                    )
                    .clickable(enabled = isConfirmed) {
                        if (isConfirmed) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onAcceptAndStart()
                        }
                    }
                    .border(
                        1.dp,
                        if (isConfirmed) Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.4f), Color.White.copy(alpha = 0.1f))
                        )
                        else Brush.verticalGradient(
                            listOf(Color.White.copy(alpha = 0.1f), Color.Transparent)
                        ),
                        RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "BẮT ĐẦU SỬ DỤNG",
                    color = if (isConfirmed) Color.White else TextTertiary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

