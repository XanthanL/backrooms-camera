package com.photoria.backrooms.ui.components

import android.util.Range
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.camera.VoiceShutter
import com.photoria.backrooms.camera.WbPreset
import com.photoria.backrooms.gl.ZebraMode
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.BackroomsYellowOnDark
import com.photoria.backrooms.ui.theme.NumberFont
import com.photoria.backrooms.ui.viewmodel.BurstCount
import com.photoria.backrooms.ui.viewmodel.CountdownSec
import com.photoria.backrooms.util.VolumeKeyShutter
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 专业相机设置面板（右侧抽屉式）。
 *
 * 内容（自上而下）：
 *   1. 滤镜强度：滑块 + 复位（作用于滤镜输出，预览/录像/拍照一致）
 *   2. 白平衡：自动 / 暖色 / 冷色 分段选择 + 强度滑块（手动时显示）
 *   3. 曝光补偿：滑块（自动曝光时可用，手动曝光时置灰）
 *   4. 手动曝光：开关 + ISO 滑块 + 快门滑块（对数刻度）
 *   5. HDR+：开关（启用多帧包围曝光前处理，非滤镜模拟）
 *   6. 夜景：开关（启用多帧对齐时域降噪前处理，与 HDR+ 互斥）
 *   7. 取景辅助：直方图 / 斑马纹 / 峰值对焦 / 水平仪（只影响取景器，不进照片与录像）
 *   8. 快门与出片：音量键快门三态（默认关）、倒计时自拍档位（默认关）、声控快门（默认关 + 电平条）、连拍帧数档位（+ 动图开关）
 *   9. 全部重置
 *
 * 面板只做展示与回调转发，状态由调用方（CameraScreen）持有并下发 CameraManager。
 * 「全部重置」只管相机成像参数；滤镜强度属于滤镜输出偏好，单独复位。
 */
@Composable
fun CameraSettingsPanel(
    modifier: Modifier = Modifier,
    filterStrength: Float,
    filterApplied: Boolean,
    onFilterStrength: (Float) -> Unit,
    onResetFilterStrength: () -> Unit,
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
    histogramEnabled: Boolean,
    onHistogramToggle: (Boolean) -> Unit,
    zebraMode: ZebraMode,
    onZebraMode: (ZebraMode) -> Unit,
    peakingEnabled: Boolean,
    onPeakingToggle: (Boolean) -> Unit,
    peakingSensitivity: Float,
    onPeakingSensitivity: (Float) -> Unit,
    levelEnabled: Boolean,
    onLevelToggle: (Boolean) -> Unit,
    volumeKeyShutter: VolumeKeyShutter,
    onVolumeKeyShutter: (VolumeKeyShutter) -> Unit,
    countdownSec: CountdownSec,
    onCountdownSec: (CountdownSec) -> Unit,
    voiceEnabled: Boolean,
    onVoiceToggle: (Boolean) -> Unit,
    voicePickup: Float,
    onVoicePickup: (Float) -> Unit,
    voiceMinLevel: Float,
    onVoiceMinLevel: (Float) -> Unit,
    voiceMeter: StateFlow<VoiceShutter.Meter>,
    burstCount: BurstCount,
    onBurstCount: (BurstCount) -> Unit,
    burstGif: Boolean,
    onBurstGifToggle: (Boolean) -> Unit,
    onResetAll: () -> Unit
) {
    // U1b：根容器换柔光玻璃；V3b：升为抽屉档阴影（三档最高），
    // 配合 CameraScreen 的背景压暗，"抽屉在最前"一目了然
    GlassSurface(
        modifier = modifier,
        elevation = PhotoriaGlass.Elevations.Drawer,
        glow = true
    ) {
    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionTitle("滤镜")
            if (filterApplied) {
                OptionChip(text = "强度复位", selected = false, onClick = onResetFilterStrength)
            }
        }
        SettingsSliderRow(
            label = "强度",
            valueText = "${(filterStrength * 100).roundToInt()}%",
            value = filterStrength,
            valueRange = 0f..1f,
            steps = 0,
            enabled = filterApplied,
            onValueChange = onFilterStrength
        )

        Spacer(modifier = Modifier.height(8.dp))

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
        SectionExpand(visible = wbPreset != WbPreset.AUTO) {
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

        // ISO 滑块（V3c：只在 M 档亮起时展开，退出自动收拢）
        val isoDelta = isoRange.upper - isoRange.lower
        if (isoDelta > 0) {
            SectionExpand(visible = manualExposure) {
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
        }

        // 快门滑块（对数刻度）
        val sMin = shutterRange.lower.coerceAtLeast(1L)
        val sMax = shutterRange.upper.coerceAtLeast(sMin + 1)
        if (sMax > sMin) {
            SectionExpand(visible = manualExposure) {
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

        SectionTitle("取景辅助")
        SwitchSettingRow(
            title = "直方图",
            subtitle = "实时曝光分布（取景器左上浮层）",
            checked = histogramEnabled,
            onCheckedChange = onHistogramToggle
        )

        Spacer(modifier = Modifier.height(6.dp))
        SectionTitle("斑马纹")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZebraMode.entries.forEach { mode ->
                OptionChip(
                    text = mode.display,
                    selected = zebraMode == mode,
                    onClick = { onZebraMode(mode) }
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        SwitchSettingRow(
            title = "峰值对焦",
            subtitle = "合焦边缘染荧光黄",
            checked = peakingEnabled,
            onCheckedChange = onPeakingToggle
        )
        SectionExpand(visible = peakingEnabled) {
            SettingsSliderRow(
                label = "灵敏度",
                valueText = "${(peakingSensitivity * 100).roundToInt()}%",
                value = peakingSensitivity,
                valueRange = 0.2f..0.98f,
                steps = 0,
                enabled = true,
                onValueChange = onPeakingSensitivity
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        SwitchSettingRow(
            title = "水平仪",
            subtitle = "气泡水平 + 归零震动",
            checked = levelEnabled,
            onCheckedChange = onLevelToggle
        )

        Spacer(modifier = Modifier.height(10.dp))

        SectionTitle("快门与出片")
        SectionTitle("音量键")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VolumeKeyShutter.entries.forEach { mode ->
                OptionChip(
                    text = mode.display,
                    selected = volumeKeyShutter == mode,
                    onClick = { onVolumeKeyShutter(mode) }
                )
            }
        }
        Text(
            text = "开启后可用音量键出片；被吞掉的按键不会再弹音量条",
            color = BackroomsCream.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))
        SectionTitle("倒计时自拍")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CountdownSec.entries.forEach { mode ->
                OptionChip(
                    text = mode.display,
                    selected = countdownSec == mode,
                    onClick = { onCountdownSec(mode) }
                )
            }
        }
        Text(
            text = "倒数中再按一次快门即取消（仅拍照模式）",
            color = BackroomsCream.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(6.dp))
        SwitchSettingRow(
            title = "声控快门",
            subtitle = "拍手或说「拍」即出片（自适应底噪）",
            checked = voiceEnabled,
            onCheckedChange = onVoiceToggle
        )
        SectionExpand(visible = voiceEnabled) {
            VoiceLevelMeter(meter = voiceMeter)
            SettingsSliderRow(
                label = "灵敏度",
                valueText = "×${voicePickup.roundToInt()}",
                value = voicePickup,
                valueRange = 2f..20f,
                steps = 0,
                enabled = true,
                onValueChange = onVoicePickup
            )
            SettingsSliderRow(
                label = "触发下限",
                valueText = "${(voiceMinLevel * 100).roundToInt()}",
                value = voiceMinLevel,
                valueRange = 0.01f..0.3f,
                steps = 0,
                enabled = true,
                onValueChange = onVoiceMinLevel
            )
            Text(
                text = "灵敏度=高于底噪多少倍才算；下限挡住极安静环境下的呼吸声",
                color = BackroomsCream.copy(alpha = 0.5f),
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        SectionTitle("连拍")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BurstCount.entries.forEach { mode ->
                OptionChip(
                    text = mode.display,
                    selected = burstCount == mode,
                    onClick = { onBurstCount(mode) }
                )
            }
        }
        Text(
            text = "整批只存一张九宫格拼图，帧不单独入相册；连拍走单帧管线，与 HDR/夜景不同时生效",
            color = BackroomsCream.copy(alpha = 0.5f),
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        SectionExpand(visible = burstCount.frames > 1) {
            SwitchSettingRow(
                title = "同时出动图",
                subtitle = "把整批帧另存一张循环 GIF",
                checked = burstGif,
                onCheckedChange = onBurstGifToggle
            )
        }

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

/** 电平条满量程（归一化 RMS）：与「触发下限」滑块的上界一致，好对着调 */
private const val METER_FULL_SCALE = 0.3f

/**
 * 声控电平稳条：亮条是当前帧 RMS，竖线是此刻的触发线（随环境底噪自动移动）。
 *
 * 没有这根线，那两个滑块只能盲调 —— 看不见线画在哪里，就无从判断
 * 「为什么安静处不触发」或「为什么有点声音就出片」。
 *
 * 在本叶子组件内收集 StateFlow：10Hz 的更新不该让整块面板跟着重组。
 */
@Composable
private fun VoiceLevelMeter(meter: StateFlow<VoiceShutter.Meter>) {
    val value by meter.collectAsState()
    val levelFraction = (value.level / METER_FULL_SCALE).coerceIn(0f, 1f)
    val thresholdFraction = (value.threshold / METER_FULL_SCALE).coerceIn(0f, 1f)
    val over = value.level >= value.threshold
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(BackroomsCream.copy(alpha = 0.12f))
        ) {
            drawRect(
                color = if (over) BackroomsYellow else BackroomsCream.copy(alpha = 0.55f),
                size = Size(size.width * levelFraction, size.height)
            )
            drawLine(
                color = BackroomsYellow.copy(alpha = 0.9f),
                start = Offset(size.width * thresholdFraction, 0f),
                end = Offset(size.width * thresholdFraction, size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
        Text(
            text = String.format("%.3f", value.level),
            color = BackroomsCream.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.width(40.dp)
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
            // V0b：面板里密集的选项 chip 上下留白 5→8dp，触控更从容
            .padding(horizontal = 14.dp, vertical = 8.dp)
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
                fontSize = 12.sp,
                fontFamily = NumberFont
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

/**
 * 面板内"联动控件"的统一展开容器（V3c）。
 *
 * 白平衡强度、ISO/快门、峰值灵敏度、声控细调、连拍 GIF 这些条件行
 * 以前是 `if` 闪现闪没 —— "出现"本身是重要信息，应该被看见。
 * 与滤镜栏折叠共用同一组弹簧参数（DESIGN.md §5）。
 */
@Composable
private fun SectionExpand(
    visible: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)
        ) + fadeIn(tween(160)),
        exit = shrinkVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 420f)
        ) + fadeOut(tween(120)),
        content = content
    )
}
