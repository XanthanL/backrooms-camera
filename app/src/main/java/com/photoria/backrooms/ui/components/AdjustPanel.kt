package com.photoria.backrooms.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.gl.AdjustmentEngine
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.BackroomsYellowOnDark
import com.photoria.backrooms.ui.theme.NumberFont
import kotlin.math.roundToInt

/**
 * 实时调色面板（W2）—— PS/Lightroom 式的影调/色彩/色域三组滑杆。
 *
 * 与 FilterParamsPanel 同族：同一套玻璃底、发丝描边、展开动画与
 * 黄色滑块语言；差别在于一次呈现整组参数（调色是"看着调"的，
 * 参数藏在 pager 后面就无法比较相邻滑杆的观感了）。
 *
 * 三页：
 *   - 影调：曝光/对比度/高光/阴影/白色/黑色
 *   - 色彩：色温/色调/自然饱和度/饱和度
 *   - 色域：6 色相带（红黄绿青蓝洋红）× 色相/饱和度/明度
 *
 * 值域统一 -100..100（VM 负责取整持久化），曝光显示为 EV、色相显示为度。
 */
@Composable
fun AdjustPanel(
    visible: Boolean,
    values: Map<String, Float>,
    onParamChange: (String, Float) -> Unit,
    onReset: () -> Unit,
    onPresetSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    curves: Map<String, List<Float>> = emptyMap(),
    onCurveChange: (String, List<Float>) -> Unit = { _, _ -> },
    onCurveReset: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(0) }
    var band by remember { mutableStateOf(0) }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = tween(280, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing))
            + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(280, easing = FastOutSlowInEasing)
        ),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(280, easing = FastOutSlowInEasing)
        ) + fadeOut(animationSpec = tween(280, easing = FastOutSlowInEasing))
            + slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = tween(280, easing = FastOutSlowInEasing)
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.verticalGradient(
                        0f to PhotoriaGlass.FillTop,
                        0.55f to PhotoriaGlass.FillBottom
                    )
                )
                .border(1.dp, PhotoriaGlass.Hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // ── 预设 + 重置 ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(AdjustmentEngine.PRESETS.keys.toList()) { name ->
                        AdjustPresetChip(name = name, onClick = { onPresetSelected(name) })
                    }
                }
                Text(
                    text = "全部还原",
                    color = BackroomsCream.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onReset() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            // ── 分组页签 ───────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("影调", "色彩", "色域", "曲线").forEachIndexed { i, label ->
                    AdjustTabChip(
                        text = label,
                        selected = tab == i,
                        onClick = { tab = i }
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))

            // ── 滑杆区（限高可滚动，保住取景面积；曲线页不吃这块）───
            if (tab != 3) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(184.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (tab) {
                    0 -> AdjustmentEngine.TONE_KEYS.forEach { key ->
                        SliderRow(
                            label = AdjustmentEngine.LABELS[key] ?: key,
                            value = values[key] ?: 0f,
                            valueText = formatAdjustValue(key, values[key] ?: 0f),
                            onChange = { onParamChange(key, it) }
                        )
                    }
                    1 -> AdjustmentEngine.COLOR_KEYS.forEach { key ->
                        SliderRow(
                            label = AdjustmentEngine.LABELS[key] ?: key,
                            value = values[key] ?: 0f,
                            valueText = formatAdjustValue(key, values[key] ?: 0f),
                            onChange = { onParamChange(key, it) }
                        )
                    }
                    else -> {
                        // 色相带选择：6 颗色点，选中带黄色描边
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                        ) {
                            AdjustmentEngine.BANDS.forEachIndexed { i, name ->
                                BandDot(
                                    label = name,
                                    color = bandColor(i),
                                    selected = band == i,
                                    onClick = { band = i }
                                )
                            }
                        }
                        val suffix = AdjustmentEngine.BAND_SUFFIX[band]
                        listOf("hue" to "色相", "sat" to "饱和度", "lum" to "明度").forEach { (prefix, label) ->
                            val key = "${prefix}_$suffix"
                            SliderRow(
                                label = label,
                                value = values[key] ?: 0f,
                                valueText = formatAdjustValue(key, values[key] ?: 0f),
                                onChange = { onParamChange(key, it) }
                            )
                        }
                        Text(
                            text = "只作用于所选色相附近的颜色，肤色/天空互不牵连",
                            color = BackroomsCream.copy(alpha = 0.5f),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
            }

            // ── 曲线页（X 批：样条曲线不吃限高滑杆区，单独占一块）──
            if (tab == 3) {
                CurveEditor(
                    curves = curves,
                    onPointsChange = onCurveChange,
                    onReset = onCurveReset,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

/** UI 值 → 面板读数（曝光给 EV、色相给角度，其余给整数） */
private fun formatAdjustValue(key: String, v: Float): String = when {
    key == "exposure" -> String.format("%+.2f", v / 100f * 1.5f)
    key.startsWith("hue_") -> "${(v / 100f * 50f).roundToInt()}°"
    else -> v.roundToInt().toString()
}

/** 色相带代表色：红黄绿青蓝洋红（HSV 均分色环） */
private fun bandColor(index: Int): Color = Color.hsv(index * 60f / 360f, 0.85f, 1f)

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    onChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = BackroomsCream,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(52.dp)
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = -100f..100f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = BackroomsYellow,
                activeTrackColor = BackroomsYellow,
                inactiveTrackColor = BackroomsCream.copy(alpha = 0.2f)
            )
        )
        // 等宽数字：拖动时数值不抖动（DESIGN.md NumberFont 规则）
        Text(
            text = valueText,
            color = BackroomsCream.copy(alpha = 0.7f),
            fontSize = 11.sp,
            fontFamily = NumberFont,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun BandDot(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    // 触控下限 40dp（DESIGN.md），色点本身 22dp
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) BackroomsYellow else BackroomsShadow.copy(alpha = 0.6f),
                    shape = CircleShape
                )
        )
    }
}

@Composable
private fun AdjustTabChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (selected) BackroomsYellowOnDark else BackroomsCream.copy(alpha = 0.8f),
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) BackroomsYellow else BackroomsCream.copy(alpha = 0.12f))
            .border(
                1.dp,
                if (selected) BackroomsYellow else BackroomsCream.copy(alpha = 0.25f),
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    )
}

@Composable
private fun AdjustPresetChip(
    name: String,
    onClick: () -> Unit
) {
    Text(
        text = name,
        color = BackroomsCream,
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(BackroomsCream.copy(alpha = 0.12f))
            .border(1.dp, BackroomsCream.copy(alpha = 0.25f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}
