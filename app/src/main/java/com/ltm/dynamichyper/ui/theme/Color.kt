package com.ltm.dynamichyper.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// iOS Dark Palette & Liquid Glass Colors
val DeepBlack = Color(0xFF000000)
val DarkBackground = Color(0xFF08080C)
val GlassSurfaceDark = Color(0x331C1C1E)
val GlassSurfaceLight = Color(0x552C2C2E)
val GlassBorder = Color(0x33FFFFFF)
val GlassBorderSubtle = Color(0x1AFFFFFF)

// Apple Accent Colors
val AppleBlue = Color(0xFF0A84FF)
val AppleTeal = Color(0xFF30D158)
val AppleGreen = Color(0xFF34C759)
val AppleIndigo = Color(0xFF5E5CE6)
val ApplePurple = Color(0xFFBF5AF2)
val ApplePink = Color(0xFFFF375F)
val AppleOrange = Color(0xFFFF9F0A)
val AppleYellow = Color(0xFFFFD60A)

// Text Colors
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0x99EBEBF5)
val TextTertiary = Color(0x4DEBEBF5)

// Gradients
val LiquidGlassGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x33FFFFFF),
        Color(0x0DFFFFFF),
        Color(0x05000000)
    )
)

val LiquidGlowGradient = Brush.horizontalGradient(
    colors = listOf(
        Color(0xFF0A84FF),
        Color(0xFF5E5CE6),
        Color(0xFFBF5AF2)
    )
)

