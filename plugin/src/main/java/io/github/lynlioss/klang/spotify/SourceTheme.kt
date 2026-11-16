@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package io.github.lynlioss.klang.spotify

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Reuses Klang's neutral shell palette and expressive shape/motion scale. */
@Composable
internal fun SourceTheme(context: Context, theme: String, dynamic: Boolean, content: @Composable () -> Unit) {
    val dark = theme == "dark" || theme == "system" && isSystemInDarkTheme()
    val neutral = rememberDynamicColorScheme(seedColor = Color(0xFF787878), isDark = dark, style = PaletteStyle.Monochrome)
    val palette = if (dynamic) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) } else neutral
    val colors = if (!dark) palette else palette.copy(
        surface = Color(0xFF090909), background = Color(0xFF090909), surfaceDim = Color(0xFF090909), surfaceBright = Color(0xFF343434),
        surfaceContainerLowest = Color(0xFF050505), surfaceContainerLow = Color(0xFF121212), surfaceContainer = Color(0xFF191919),
        surfaceContainerHigh = Color(0xFF222222), surfaceContainerHighest = Color(0xFF2C2C2C), surfaceVariant = Color(0xFF2C2C2C),
        onSurface = Color(0xFFF2F2F2), onBackground = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFFC6C6C6),
        outline = Color(0xFF909090), outlineVariant = Color(0xFF454545))
    MaterialTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp), largeIncreased = RoundedCornerShape(20.dp),
        extraLargeIncreased = RoundedCornerShape(32.dp), extraExtraLarge = RoundedCornerShape(48.dp)), content = content)
}
