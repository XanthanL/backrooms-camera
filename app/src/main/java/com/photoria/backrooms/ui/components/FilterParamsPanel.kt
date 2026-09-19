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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.catalog.FilterParamDef
import com.photoria.backrooms.catalog.FilterPreset
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 滤镜参数调节面板（单条滑动版）。
 *
 * 设计思路（参考主流相机 App 的参数轮盘）：
 *   - 一次只显示一个参数：名称 + 数值，左右滑动切换参数项
 *   - 下方独立滑块区域调整当前参数值（与 pager 手势隔离，避免冲突）
 *   - 预设芯片行 + 重置按钮
 *   - 半透明背景 + 紧凑高度，最大化保留预览可视面积
 *
 * 展开动画：expandVertically（高度从底部增长，驱动上方滤镜行平滑上移）
 *           + slideInVertically（内容轻微上滑）+ fadeIn，统一 280ms FastOutSlowIn 缓动，
 *           避免瞬移带来的突兀感。
 *
 * 交互：
 *   1. 左右滑动参数名区域 → 切换当前编辑的参数
 *   2. 拖动底部滑块 → 修改当前参数值
 *   3. 点击预设芯片 → 应用预设
 *   4. 点击重置 → 所有参数恢复默认
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterParamsPanel(
    visible: Boolean,
    paramDefs: List<FilterParamDef>,
    currentValues: Map<String, Float>,
    onParamChange: (String, Float) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    presets: List<FilterPreset> = emptyList(),
    onPresetSelected: (FilterPreset) -> Unit = {}
) {
    // 丝滑缓动：标准减速曲线，进出一致，避免速度突变
    val panelDurationMs = 280
    val easing: Easing = FastOutSlowInEasing

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(
            expandFrom = Alignment.Bottom,
            animationSpec = tween(panelDurationMs, easing = easing)
        ) + fadeIn(
            animationSpec = tween(panelDurationMs, easing = easing)
        ) + slideInVertically(
            initialOffsetY = { it / 4 },
            animationSpec = tween(panelDurationMs, easing = easing)
        ),
        exit = shrinkVertically(
            shrinkTowards = Alignment.Bottom,
            animationSpec = tween(panelDurationMs, easing = easing)
        ) + fadeOut(
            animationSpec = tween(panelDurationMs, easing = easing)
        ) + slideOutVertically(
            targetOffsetY = { it / 4 },
            animationSpec = tween(panelDurationMs, easing = easing)
        ),
        modifier = modifier
    ) {
        if (paramDefs.isEmpty()) return@AnimatedVisibility

        // pager 状态：当前显示第几个参数
        val pagerState = rememberPagerState(pageCount = { paramDefs.size })

        // 当前活跃参数定义（跟随 pager 滑动）
        val currentDef = paramDefs[pagerState.currentPage]
        val currentValue = currentValues[currentDef.uniformName] ?: currentDef.defaultValue

        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                // U1b：玻璃分层底（纵向渐变 + 发丝描边），与设置面板同族
                .background(
                    Brush.verticalGradient(
                        0f to PhotoriaGlass.FillTop,
                        0.55f to PhotoriaGlass.FillBottom
                    )
                )
                .border(1.dp, PhotoriaGlass.Hairline, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // ── 预设行 + 重置按钮（始终显示重置）──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (presets.isNotEmpty()) {
                    LazyRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(presets) { preset ->
                            PresetChip(
                                name = preset.name,
                                onClick = { onPresetSelected(preset) }
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Text(
                    text = "重置",
                    color = BackroomsCream.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable { onReset() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            // ── 参数名 + 数值（HorizontalPager，左右滑动切换）──
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                pageSpacing = 16.dp
            ) { page ->
                val def = paramDefs[page]
                val value = currentValues[def.uniformName] ?: def.defaultValue
                val isInteger = def.step >= 1f
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = def.displayName,
                        color = BackroomsCream,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = if (isInteger) value.toInt().toString()
                               else String.format("%.2f", value),
                        color = BackroomsCream.copy(alpha = 0.7f),
                        fontSize = 13.sp
                    )
                }
            }

            // ── 页面指示点 ──
            if (paramDefs.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    paramDefs.forEachIndexed { index, _ ->
                        val isActive = index == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 2.dp)
                                .size(if (isActive) 6.dp else 4.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isActive) BackroomsYellow
                                    else BackroomsCream.copy(alpha = 0.3f)
                                )
                        )
                    }
                }
            }

            // ── 滑块（独立区域，调整当前参数，实时更新 GL）──
            val steps = if (currentDef.step <= 0f) 0
                else ((currentDef.max - currentDef.min) / currentDef.step).toInt() - 1
            Slider(
                value = currentValue,
                onValueChange = { newValue ->
                    onParamChange(currentDef.uniformName, newValue)
                },
                valueRange = currentDef.min..currentDef.max,
                steps = if (steps > 0) steps else 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BackroomsYellow,
                    activeTrackColor = BackroomsYellow,
                    inactiveTrackColor = BackroomsCream.copy(alpha = 0.2f)
                )
            )
        }
    }
}

/**
 * 预设选择芯片（紧凑版）。
 */
@Composable
private fun PresetChip(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = name,
        color = BackroomsCream,
        fontSize = 11.sp,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(BackroomsCream.copy(alpha = 0.12f))
            .border(1.dp, BackroomsCream.copy(alpha = 0.25f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
