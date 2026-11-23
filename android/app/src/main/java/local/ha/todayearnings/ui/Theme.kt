package local.ha.todayearnings.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 克制的极简风格：跟随系统浅色/深色，只用一个强调色；绿 = 正向金额，红 = 负向影响。 */
object DS {
    val accent = Color(0xFF0A84FF)
    val positive = Color(0xFF34C759)
    val negative = Color(0xFFFF3B30)
}

private val LightColors = lightColorScheme(
    primary = DS.accent,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF2F2F5),
    outlineVariant = Color(0xFFE0E0E6),
)

private val DarkColors = darkColorScheme(
    primary = DS.accent,
    background = Color(0xFF111114),
    surface = Color(0xFF111114),
    surfaceVariant = Color(0xFF1E1E22),
    outlineVariant = Color(0xFF38383D),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
