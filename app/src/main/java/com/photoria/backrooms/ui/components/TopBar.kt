package com.photoria.backrooms.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 顶部栏（后室主题，U1b 玻璃化）。
 *
 * 左侧：App 名称 "Photoria"（奶油黄）
 * 右侧：网格线开关 + 闪光灯开关（后摄可用时显示）+ 专业设置 + 前后摄切换
 *
 * 顶栏压在实时预览上，用"上深下透"的渐变遮罩代替整块死黑底：
 * 既保住图标可读性，又留出向预览渐隐的呼吸感。
 * 图标按钮统一换成 [GlassIconButton]：按压缩放 + 触感；
 * 设置齿轮激活时额外做 90° 弹性旋转（面板开合的方向隐喻）。
 *
 * @param onSwitchCamera 切换摄像头回调
 * @param showGrid 是否显示三分线网格（驱动图标选中态）
 * @param onToggleGrid 切换网格回调
 * @param torchEnabled 闪光灯是否开启
 * @param torchAvailable 当前是否可用闪光灯（前置为 false，隐藏按钮）
 * @param onToggleTorch 切换闪光灯回调
 * @param cameraSettingsActive 专业设置面板是否展开（驱动图标选中态）
 * @param onToggleCameraSettings 切换专业设置面板回调
 */
@Composable
fun TopBar(
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
    showGrid: Boolean = false,
    onToggleGrid: () -> Unit = {},
    torchEnabled: Boolean = false,
    torchAvailable: Boolean = false,
    onToggleTorch: () -> Unit = {},
    cameraSettingsActive: Boolean = false,
    onToggleCameraSettings: () -> Unit = {}
) {
    // 顶栏遮罩：固定 72dp 高度下的纵向渐变（顶部近实、底部全透）
    val scrimBrush = rememberTopScrimBrush()

    // 齿轮旋转：开面板转 90°，spring 收尾带回弹
    val gearRotation by animateFloatAsState(
        targetValue = if (cameraSettingsActive) 90f else 0f,
        animationSpec = PhotoriaGlass.SelectSpring,
        label = "gearRotation"
    )

    // V0：顶栏直接显示构建版本号。
    // 之前 versionName 一直是 1.0，真机装完新包却看不出来装了没有 ——
    // 现在装完打开一眼核对，不再靠玄学
    val context = LocalContext.current
    val appVersion = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(scrimBrush)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧 App 名称
        Text(
            text = "Photoria",
            color = BackroomsCream.copy(alpha = 0.95f),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        // 版本徽标（发丝描边小胶囊）
        if (!appVersion.isNullOrEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, PhotoriaGlass.Hairline, RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = appVersion,
                    color = BackroomsCream.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 网格线开关
        GlassIconButton(
            onClick = onToggleGrid,
            contentDescription = "三分线网格",
            active = showGrid
        ) {
            Icon(
                imageVector = if (showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
                contentDescription = null,
                tint = if (showGrid) BackroomsYellow else BackroomsCream.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }

        // 闪光灯开关（仅后摄可用时显示）
        if (torchAvailable) {
            GlassIconButton(
                onClick = onToggleTorch,
                contentDescription = if (torchEnabled) "关闭闪光灯" else "打开闪光灯",
                active = torchEnabled
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = null,
                    tint = if (torchEnabled) BackroomsYellow else BackroomsCream.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 专业相机设置（ISO/快门/白平衡/曝光补偿/HDR）
        GlassIconButton(
            onClick = onToggleCameraSettings,
            contentDescription = "专业相机设置",
            active = cameraSettingsActive
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = null,
                tint = if (cameraSettingsActive) BackroomsYellow
                       else BackroomsCream.copy(alpha = 0.85f),
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = gearRotation }
            )
        }

        // 切换摄像头按钮
        GlassIconButton(
            onClick = onSwitchCamera,
            contentDescription = "切换摄像头"
        ) {
            Icon(
                imageVector = Icons.Filled.Cameraswitch,
                contentDescription = null,
                tint = BackroomsCream.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** 顶部渐变遮罩：BackroomsShadow 0.92 → 0（透明），固定形状可 remember */
@Composable
private fun rememberTopScrimBrush(): Brush = remember(BackroomsShadow) {
    Brush.verticalGradient(
        0f to BackroomsShadow.copy(alpha = 0.92f),
        0.65f to BackroomsShadow.copy(alpha = 0.55f),
        1f to Color.Transparent
    )
}
