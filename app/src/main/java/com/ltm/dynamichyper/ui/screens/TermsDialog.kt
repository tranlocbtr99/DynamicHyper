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
                        text = "1. Phạm Vi & Tuyên Bố Miễn Trừ Trách Nhiệm (Disclaimer):",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Dynamic Hyper hoạt động dựa trên giao thức thông báo trọng tâm (Focus Notification) của Xiaomi HyperOS và các quyền do người dùng tự nguyện cấp; ứng dụng KHÔNG can thiệp kernel và KHÔNG yêu cầu root.\n" +
                               "• Phần mềm được cung cấp theo nguyên trạng (\"AS IS\"), không cam kết tương thích tuyệt đối với mọi phiên bản ROM tùy biến hoặc các thay đổi API hệ thống từ Xiaomi trong tương lai.\n" +
                               "• Tác giả được miễn trừ mọi trách nhiệm pháp lý và bồi thường đối với các sự cố xung đột giao diện, hao hụt pin, gián đoạn thông báo hoặc thiệt hại phát sinh từ việc sử dụng sai mục đích hoặc trên ROM không chính thức.",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "2. Quyền Riêng Tư & Xử Lý Dữ Liệu Cục Bộ:",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Cam kết 100% KHÔNG thu thập, lưu trữ máy chủ hay chia sẻ cho bên thứ ba bất kỳ nội dung tin nhắn, cuộc gọi, danh bạ, mã OTP hay dữ liệu cá nhân nào.\n" +
                               "• Mọi xử lý đọc thông báo và hiển thị đảo động diễn ra hoàn toàn cục bộ trên thiết bị. Kết nối mạng chỉ dùng để kiểm tra cập nhật OTA (GitHub) và lấy dữ liệu thời tiết công khai khi bạn bật widget thời tiết.",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "3. Tuyên Bố Bản Quyền & Sở Hữu Trí Tuệ Độc Quyền:",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "• Copyright © 2026 Trần Minh Lộc (tranlocbtr99@gmail.com). Bảo lưu mọi quyền (All Rights Reserved).\n" +
                               "• Toàn bộ mã nguồn, thuật toán điều khiển đảo động, tài nguyên đồ họa và giao diện Kính Lỏng (Liquid Glass UI) là tài sản trí tuệ độc quyền của tác giả.\n" +
                               "• Nghiêm cấm tuyệt đối mọi hành vi sao chép, trích xuất, dịch ngược (reverse engineering / decompile), chỉnh sửa, đóng gói lại, phân phối lại hoặc thương mại hóa dưới bất kỳ hình thức nào nếu không có văn bản chấp thuận từ tác giả.",
                        style = Typography.bodyMedium.copy(fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
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

