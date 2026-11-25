package com.ltm.dynamichyper.ui.screens


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

import com.ltm.dynamichyper.ui.screens.dashboard.OverviewTabScreen
import com.ltm.dynamichyper.ui.screens.dashboard.FeaturesTabScreen
import com.ltm.dynamichyper.ui.screens.dashboard.AppsTabScreen

@Composable
fun DashboardScreen(
    prefs: PreferencesManager,
    hazeState: HazeState = remember { HazeState() }
) {
    var currentTab by remember { mutableStateOf(DashboardTab.OVERVIEW) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .haze(hazeState)
    ) {
        // Nội dung của 3 màn hình chuyển đổi mượt mà với hiệu ứng bóng nước (Water Bubble Physics)
        AnimatedContent(
            targetState = currentTab,
            transitionSpec = {
                (fadeIn(animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f)) +
                        scaleIn(initialScale = 0.94f, animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f)))
                    .togetherWith(
                        fadeOut(animationSpec = tween(120)) +
                                scaleOut(targetScale = 1.04f, animationSpec = tween(120))
                    )
            },
            label = "waterBubbleTabTransition",
            modifier = Modifier.fillMaxSize()
        ) { tab ->
            when (tab) {
                DashboardTab.OVERVIEW -> OverviewTabScreen(prefs = prefs, hazeState = hazeState)
                DashboardTab.FEATURES -> FeaturesTabScreen(prefs = prefs, hazeState = hazeState)
                DashboardTab.APPS -> AppsTabScreen(prefs = prefs, hazeState = hazeState)
            }
        }

        // Thanh điều hướng đáy nổi kiểu iOS Liquid Glass (Floating Island Taskbar)
        FloatingGlassBottomBar(
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }
}
