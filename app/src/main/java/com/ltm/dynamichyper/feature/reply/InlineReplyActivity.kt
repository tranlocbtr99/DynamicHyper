package com.ltm.dynamichyper.feature.reply

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ltm.dynamichyper.ui.theme.AppleBlue

/**
 * Activity kính lỏng trong suốt cho phép Trả Lời Nhanh trực tiếp từ Đảo Động
 * mà không bị Android 14–17 chặn startService nền và không bắt buộc quyền Overlay.
 */
class InlineReplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val pendingIntent: PendingIntent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("pending_intent", PendingIntent::class.java)
        } else {
            intent?.getParcelableExtra("pending_intent")
        }
        val resultKey = intent?.getStringExtra("result_key")
        val senderTitle = intent?.getStringExtra("sender_title") ?: "Tin nhắn"

        if (pendingIntent == null || resultKey.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            var messageText by remember { mutableStateOf("") }
            val focusRequester = remember { FocusRequester() }

            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { finishAndNoAnim() },
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(top = 18.dp, start = 16.dp, end = 16.dp)
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                        .shadow(24.dp, RoundedCornerShape(26.dp), spotColor = Color.Black.copy(alpha = 0.5f))
                        .clip(RoundedCornerShape(26.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xEB1C1C1E), Color(0xDD0B0C10))
                            )
                        )
                        .border(
                            1.dp,
                            Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0.06f))
                            ),
                            RoundedCornerShape(26.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(AppleBlue, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Trả lời: $senderTitle",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { finishAndNoAnim() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Đóng",
                                    tint = Color.White.copy(alpha = 0.75f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BasicTextField(
                                value = messageText,
                                onValueChange = { messageText = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(focusRequester),
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                cursorBrush = SolidColor(AppleBlue),
                                singleLine = false,
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = {
                                    sendReply(pendingIntent, resultKey, messageText)
                                }),
                                decorationBox = { innerTextField ->
                                    if (messageText.isEmpty()) {
                                        Text(
                                            text = "Nhập tin nhắn trả lời...",
                                            color = Color.White.copy(alpha = 0.45f),
                                            fontSize = 15.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (messageText.isNotBlank()) AppleBlue else Color.White.copy(alpha = 0.1f)
                                    )
                                    .clickable(enabled = messageText.isNotBlank()) {
                                        sendReply(pendingIntent, resultKey, messageText)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Gửi",
                                    tint = if (messageText.isNotBlank()) Color.White else Color.White.copy(alpha = 0.35f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendReply(pendingIntent: PendingIntent, resultKey: String, message: String) {
        if (message.isBlank()) return
        try {
            val replyIntent = Intent()
            val bundle = Bundle().apply { putCharSequence(resultKey, message) }
            val remoteInput = RemoteInput.Builder(resultKey).build()
            RemoteInput.addResultsToIntent(arrayOf(remoteInput), replyIntent, bundle)
            pendingIntent.send(this, 0, replyIntent)
            Toast.makeText(this, "✓ Đã gửi tin nhắn!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("InlineReplyActivity", "Gửi phản hồi thất bại", e)
            Toast.makeText(this, "Không thể gửi tin: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finishAndNoAnim()
    }

    private fun finishAndNoAnim() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
