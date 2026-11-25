package com.ltm.dynamichyper.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ltm.dynamichyper.core.AppLogger
import com.ltm.dynamichyper.core.IslandProbe
import com.ltm.dynamichyper.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Hộp thoại Tra Cứu & Kiểm Tra Nhật Ký Beta (Beta Diagnostic Log Viewer)
 * Cung cấp khả năng theo dõi thời gian thực, lọc theo mức độ lỗi, sao chép và xuất file chia sẻ.
 */
@Composable
fun DiagnosticLogDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs by AppLogger.logsFlow.collectAsState()
    val probeStats by IslandProbe.stats.collectAsState()
    val listState = rememberLazyListState()

    var selectedFilter by remember { mutableStateOf<AppLogger.LogLevel?>(null) }
    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == null) logs else logs.filter { it.level == selectedFilter }
    }

    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF141416))
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(AppleOrange.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = AppleOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Nhật Ký Kiểm Lỗi",
                                    style = Typography.titleMedium.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AppleOrange.copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "BETA",
                                        style = Typography.labelSmall.copy(color = AppleOrange, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                    )
                                }
                            }
                            Text(
                                text = "Ghi lại mọi sự kiện Đảo & Quyền HyperOS (${filteredLogs.size}/${logs.size})",
                                style = Typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismissRequest,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Đóng", tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bộ lọc Log Level
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        label = { Text("Tất cả", fontSize = 11.sp) }
                    )
                    FilterChip(
                        selected = selectedFilter == AppLogger.LogLevel.ERROR,
                        onClick = { selectedFilter = if (selectedFilter == AppLogger.LogLevel.ERROR) null else AppLogger.LogLevel.ERROR },
                        label = { Text("Lỗi (Error)", fontSize = 11.sp, color = if (selectedFilter == AppLogger.LogLevel.ERROR) Color.White else ApplePink) }
                    )
                    FilterChip(
                        selected = selectedFilter == AppLogger.LogLevel.WARN,
                        onClick = { selectedFilter = if (selectedFilter == AppLogger.LogLevel.WARN) null else AppLogger.LogLevel.WARN },
                        label = { Text("Cảnh báo", fontSize = 11.sp, color = if (selectedFilter == AppLogger.LogLevel.WARN) Color.White else AppleOrange) }
                    )
                    FilterChip(
                        selected = selectedFilter == AppLogger.LogLevel.INFO,
                        onClick = { selectedFilter = if (selectedFilter == AppLogger.LogLevel.INFO) null else AppLogger.LogLevel.INFO },
                        label = { Text("Thông tin", fontSize = 11.sp, color = if (selectedFilter == AppLogger.LogLevel.INFO) Color.White else AppleBlue) }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Kết quả dò đảo: đảo có sống sót sau khi app gửi không
                Text(
                    text = "Dò đảo: ${probeStats.summary()}",
                    color = if (probeStats.lost > 0) AppleOrange else TextSecondary,
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Vùng hiển thị Log Console
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black)
                        .border(1.0.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
                        .padding(8.dp)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Chưa có bản ghi nhật ký nào phù hợp.",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredLogs) { entry ->
                                val color = when (entry.level) {
                                    AppLogger.LogLevel.DEBUG -> TextTertiary
                                    AppLogger.LogLevel.INFO -> AppleBlue
                                    AppLogger.LogLevel.WARN -> AppleOrange
                                    AppLogger.LogLevel.ERROR -> ApplePink
                                }
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.White.copy(alpha = 0.03f))
                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = entry.formattedTime,
                                            color = TextTertiary,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "[${entry.tag}]",
                                            color = color,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = entry.message,
                                        color = TextPrimary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (entry.throwable != null) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val stackTrace = android.util.Log.getStackTraceString(entry.throwable)
                                        Text(
                                            text = stackTrace,
                                            color = ApplePink.copy(alpha = 0.8f),
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Thanh hành động (Sao chép, Xuất file chia sẻ, Xóa)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            AppLogger.copyToClipboard(context)
                            Toast.makeText(context, "Đã sao chép toàn bộ nhật ký kiểm lỗi!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleBlue),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sao Chép", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            AppLogger.exportAndShareLogFile(context)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AppleTeal),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Chia Sẻ File", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                        onClick = {
                            AppLogger.clear()
                            Toast.makeText(context, "Đã xóa nhật ký", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Xóa", tint = ApplePink)
                    }
                }
            }
        }
    }
}
