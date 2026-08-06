package com.photoria.backrooms.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ── 后室配色（Backrooms）──
// 灵感来自后室 Level 0：荧光灯下的病态黄墙、潮湿沉闷的黄色调。
// 深色基调保证取景器可视性，文字/图标用奶油黄模拟荧光照明。
val BackroomsYellow = Color(0xFFD1BC55)      // 荧光黄：主强调（快门、焦点、指示点）
val BackroomsMoss = Color(0xFFA8A36D)        // 苔藓黄：次级强调
val BackroomsCream = Color(0xFFF0E6B8)       // 奶油黄：文字/图标（替代纯白）
val BackroomsWall = Color(0xFF2B2512)        // 墙面黄褐：面板/滑块
val BackroomsShadow = Color(0xFF100D06)      // 暗黄棕：背景/遮罩（替代纯黑）
val BackroomsYellowOnDark = Color(0xFF1C1705) // 荧光黄底上的深色图标/文字

private val BackroomsDarkColorScheme = darkColorScheme(
    primary = BackroomsYellow,
    onPrimary = Color(0xFF1C1705),
    primaryContainer = Color(0xFF8A7B2E),
    onPrimaryContainer = BackroomsCream,
    secondary = BackroomsMoss,
    onSecondary = Color(0xFF1C1705),
    secondaryContainer = BackroomsWall,
    onSecondaryContainer = BackroomsCream,
    background = BackroomsShadow,
    onBackground = BackroomsCream,
    surface = BackroomsWall,
    onSurface = BackroomsCream,
)

@Composable
fun PhotoriaTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }
    }
    MaterialTheme(
        colorScheme = BackroomsDarkColorScheme,
        content = content
    )
}
