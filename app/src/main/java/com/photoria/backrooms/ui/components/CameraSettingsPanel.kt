package com.photoria.backrooms.ui.components

import android.util.Range
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.camera.WbPreset
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.BackroomsYellowOnDark
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 专业相机设置面板（右侧抽屉式）。
 *
 * 内容（自上而下）：
 *   1. 白平衡：自动 / 暖色 / 冷色 分段选择 + 强度滑块（手动时显示）
 *   2. 曝光补偿：滑块（自动曝光时可用，手动曝光时置灰）
 *   3. 手动曝光：开关 + ISO 滑块 + 快门滑块（对数刻度）
 *   4. HDR+：开关（启用多帧包围曝光前处理，非滤镜模拟）
 *   5. 夜景：开关（启用多帧对齐时域降噪前处理，与 HDR+ 互斥）
 *   6. 全部重置
 *
 * 面板只做展示与回调转发，状态由调用方（CameraScreen）持有并下发 CameraManager。
 */
@Composable
fun CameraSettingsPanel(
    modifier: Modifier = Modifier,
    wbPreset: WbPreset,
    wbIntensity: Float,
    onWbPreset: (WbPreset) -> Unit,
    onWbIntensity: (Float) -> Unit,
    evIndex: Int,
    evRange: Range<Int>,
    onEvChange: (Int) -> Unit,
    manualExposure: Boolean,
    onManualExposureToggle: (Boolean) -> Unit,
    iso: Int,
    isoRange: Range<Int>,
    onIsoChange: (Int) -> Unit,
    shutterNs: Long,
    shutterRange: Range<Long>,
    onShutterChange: (Long) -> Unit,
    hdrEnabled: Boolean,
    onHdrToggle: (Boolean) -> Unit,
    nightEnabled: Boolean,
    onNightToggle: (Boolean) -> Unit,
    onResetAll: () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BackroomsShadow.copy(alpha = 0.88f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        SectionTitle("白平衡")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WbPreset.entries.forEach { preset ->
                OptionChip(
                    text = preset.display,
                    selected = wbPreset == preset,
                    onClick = { onWbPreset(preset) }
                )
            }
        }
        if (wbPreset != WbPreset.AUTO) {
            Slider(
                value = wbIntensity,
                onValueChange = onWbIntensity,
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = BackroomsYellow,
                    activeTrackColor = BackroomsYellow,
                    inactiveTrackColor = BackroomsCream.copy(alpha = 0.2f)
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        SectionTitle("曝光补偿")
        val evEnabled = !manualExposure
        val evMin = evRange.lower
        val evMax = evRange.upper
        if (evMax > evMin) {
            SettingsSliderRow(
                label = "EV",
                valueText = if (evIndex > 0) "+$evIndex" else "$evIndex",
                value = evIndex.toFloat(),
                valueRange = evMin.toFloat()..evMax.toFloat(),
                steps = (evMax - evMin) - 1,
                enabled = evEnabled,
                onValueChange = { onEvChange(it.roundToInt()) }
            )
        } else {
            Text(
                text = "设备不支持",
                color = BackroomsCream.copy(alpha = 0.5f),
                fontSize = 12.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 手动曝光行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "手动曝光（ISO/快门）",
                color = BackroomsCream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = manualExposure,
                onCheckedChange = onManualExposureToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = BackroomsYellowOnDark,
                    checkedTrackColor = BackroomsYellow,
                    uncheckedThumbColor = BackroomsCream,
                    uncheckedTrackColor = BackroomsCream.copy(alpha = 0.2f)
                )
            )
        }

        // ISO 滑块
        val isoDelta = isoRange.upper - isoRange.lower
        if (isoDelta > 0) {
            val isoStep = if (isoDelta <= 500) 50 else 100
            val snap = { v: Float ->
                ((v / isoStep).roundToInt() * isoStep)
                    .coerceIn(isoRange.lower, isoRange.upper)
            }
            SettingsSliderRow(
                label = "ISO",
                valueText = snap(iso.toFloat()).toString(),
                value = iso.toFloat(),
                valueRange = isoRange.lower.toFloat()..isoRange.upper.toFloat(),
                steps = if (isoDelta / isoStep - 1 > 0) isoDelta / isoStep - 1 else 0,
                enabled = manualExposure,
                onValueChange = { onIsoChange(snap(it)) }
            )
        }

        // 快门滑块（对数刻度）
        val sMin = shutterRange.lower.coerceAtLeast(1L)
        val sMax = shutterRange.upper.coerceAtLeast(sMin + 1)
        if (sMax > sMin) {
            val logMin = ln(sMin.toDouble())
            val logMax = ln(sMax.toDouble())
            val position = ((ln(shutterNs.toDouble()) - logMin) / (logMax - logMin)).toFloat()
            SettingsSliderRow(
                label = "快门",
                valueText = formatShutter(shutterNs),
                value = position.coerceIn(0f, 1f),
                valueRange = 0f..1f,
                steps = 0,
                enabled = manualExposure,
                onValueChange = { t ->
                    onShutterChange(
                        exp(logMin + (logMax - logMin) * t.toDouble()).toLong()
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // HDR 行
        SwitchSettingRow(
            title = "HDR+",
            subtitle = "多帧包围曝光合成（前处理）",
            checked = hdrEnabled,
            onCheckedChange = onHdrToggle
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 夜景行（与 HDR+ 互斥，开启夜景时 HDR+ 自动关闭）
        SwitchSettingRow(
            title = "夜景",
            subtitle = "多帧对齐时域降噪（前处理）",
            checked = nightEnabled,
            onCheckedChange = onNightToggle
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "全部重置",
            color = BackroomsYellow,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(50))
                .background(BackroomsCream.copy(alpha = 0.1f))
                .clickable { onResetAll() }
                .padding(horizontal = 16.dp, vertical = 5.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}

/** 段落小标题 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = BackroomsCream.copy(alpha = 0.6f),
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

/**
 * 通用开关行：左侧标题 + 副标题，右侧 Switch。
 *
 * 用于 HDR+ / 夜景等多帧前处理开关，统一视觉风格。
 */
@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = BackroomsCream,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                color = BackroomsCream.copy(alpha = 0.55f),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = BackroomsYellowOnDark,
                checkedTrackColor = BackroomsYellow,
                uncheckedThumbColor = BackroomsCream,
                uncheckedTrackColor = BackroomsCream.copy(alpha = 0.2f)
            )
        )
    }
}

/** 分段选择芯片（后室主题） */
@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        targetValue = if (selected) BackroomsYellow else BackroomsCream.copy(alpha = 0.1f),
        label = "chipBg"
    )
    val fg by animateColorAsState(
        targetValue = if (selected) BackroomsYellowOnDark else BackroomsCream.copy(alpha = 0.85f),
        label = "chipFg"
    )
    Text(
        text = text,
        color = fg,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 5.dp)
    )
}

/** 滑块行：左侧标签 + 数值，下方滑块 */
@Composable
private fun SettingsSliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: kotlin.ranges.ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = BackroomsCream,
                fontSize = 13.sp
            )
            Text(
                text = valueText,
                color = BackroomsCream.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = { if (enabled) onValueChange(it) },
            valueRange = valueRange,
            steps = if (steps > 0) steps else 0,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = BackroomsYellow,
                activeTrackColor = BackroomsYellow,
                inactiveTrackColor = BackroomsCream.copy(alpha = 0.2f),
                disabledThumbColor = BackroomsCream.copy(alpha = 0.4f),
                disabledActiveTrackColor = BackroomsCream.copy(alpha = 0.25f),
                disabledInactiveTrackColor = BackroomsCream.copy(alpha = 0.15f)
            )
        )
    }
}

/** 快门时间格式化：>=1s 显示 1.0s 形式，否则 1/125 形式 */
private fun formatShutter(ns: Long): String {
    if (ns >= 1_000_000_000L) {
        return String.format("%.1fs", ns / 1_000_000_000.0)
    }
    val denominator = (1_000_000_000.0 / ns).roundToInt().coerceAtLeast(1)
    return "1/${denominator}s"
}
