package com.photoria.backrooms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 顶部栏（后室主题）。
 *
 * 左侧：App 名称 "Photoria"（奶油黄）
 * 右侧：网格线开关 + 闪光灯开关（后摄可用时显示）+ 前后摄切换按钮
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(BackroomsShadow.copy(alpha = 0.6f))
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

        Spacer(modifier = Modifier.weight(1f))

        // 网格线开关
        IconButton(onClick = onToggleGrid) {
            Icon(
                imageVector = if (showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
                contentDescription = "三分线网格",
                tint = if (showGrid) BackroomsYellow else BackroomsCream.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }

        // 闪光灯开关（仅后摄可用时显示）
        if (torchAvailable) {
            IconButton(onClick = onToggleTorch) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = if (torchEnabled) "关闭闪光灯" else "打开闪光灯",
                    tint = if (torchEnabled) BackroomsYellow else BackroomsCream.copy(alpha = 0.85f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // 专业相机设置（ISO/快门/白平衡/曝光补偿/HDR）
        IconButton(onClick = onToggleCameraSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "专业相机设置",
                tint = if (cameraSettingsActive) BackroomsYellow
                       else BackroomsCream.copy(alpha = 0.85f),
                modifier = Modifier.size(24.dp)
            )
        }

        // 切换摄像头按钮
        IconButton(onClick = onSwitchCamera) {
            Icon(
                imageVector = Icons.Filled.Cameraswitch,
                contentDescription = "切换摄像头",
                tint = BackroomsCream.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
