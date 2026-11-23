package com.ltm.dynamichyper.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ltm.dynamichyper.ui.components.LiquidActionButton
import com.ltm.dynamichyper.ui.theme.*

/**
 * Hộp thoại Điều khoản sử dụng & Tuyên bố miễn trừ trách nhiệm khi mở app lần đầu
 */
@Composable
fun TermsAndDisclaimerDialog(
    onAccept: () -> Unit
) {
    Dialog(
        onDismissRequest = { /* Bắt buộc đồng ý để tiếp tục */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        val scrollState = rememberScrollState()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF232328),
                            Color(0xFF141416)
                        )
                    )
                )
                .border(
                    1.dp,
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.08f))
                    ),
                    RoundedCornerShape(28.dp)
                )
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Icon
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(AppleBlue.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = AppleBlue,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Điều Khoản & Miễn Trừ Trách Nhiệm",
                    style = Typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 18.sp
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Chào mừng bạn đến với Dynamic Hyper",
                    style = Typography.bodyMedium.copy(color = AppleTeal)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable Terms Content
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 280.dp)
                        .verticalScroll(scrollState)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(14.dp)
                ) {
                    Text(
                        text = "1. Tuyên Bố Miễn Trừ Trách Nhiệm (Disclaimer):",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Ứng dụng Dynamic Hyper hoạt động dựa trên cơ chế thông báo trọng tâm (Focus Notification) tích hợp sẵn của Xiaomi HyperOS.\n" +
                               "• Ứng dụng hoàn toàn KHÔNG can thiệp sâu vào kernel hay làm hỏng phần cứng thiết bị của bạn.\n" +
                               "• Tác giả không chịu trách nhiệm trong các trường hợp xung đột giao diện do người dùng sử dụng các bản ROM tùy biến không chính thức.",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "2. Quyền Riêng Tư & Bảo Mật:",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Dynamic Hyper cam kết 100% KHÔNG thu thập, lưu trữ hay tải lên máy chủ bất kỳ nội dung tin nhắn, danh bạ, hay dữ liệu cá nhân nào.\n" +
                               "• Quyền truy cập thông báo chỉ được sử dụng cục bộ trên máy để hiển thị hiệu ứng đảo động cho trình phát nhạc và tin nhắn.",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "3. Bản Quyền & Tác Giả:",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Dự án được phát triển và tối ưu bởi LTM (Trần Lộc).\n" +
                               "• Nghiêm cấm mọi hành vi đóng gói lại để thương mại hóa hoặc lừa đảo người dùng.",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                LiquidActionButton(
                    text = "ĐỒNG Ý VÀ TIẾP TỤC",
                    onClick = onAccept
                )
            }
        }
    }
}

