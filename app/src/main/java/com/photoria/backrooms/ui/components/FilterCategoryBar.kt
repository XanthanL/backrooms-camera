package com.photoria.backrooms.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.catalog.FilterCategory
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 滤镜分类标签栏（极简风格）。
 *
 * 横向滚动的分类芯片，点击切换当前筛选分类。
 * 选中态：荧光黄文字 + 中等字重；未选中态：半透明奶油黄 + 常规字重。
 * 无背景填充，仅以颜色区分，保持取景器可视面积最大化。
 *
 * @param categories 分类列表（显示顺序）
 * @param selected 当前选中的分类
 * @param onSelect 选择分类回调
 */
@Composable
fun FilterCategoryBar(
    categories: List<FilterCategory>,
    selected: FilterCategory,
    onSelect: (FilterCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(categories) { category ->
            CategoryChip(
                name = category.displayName,
                isSelected = category == selected,
                onClick = { onSelect(category) }
            )
        }
    }
}

@Composable
private fun CategoryChip(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 颜色过渡：选中 → 荧光黄；未选中 → 半透明奶油黄
    val color by animateColorAsState(
        targetValue = if (isSelected) BackroomsYellow else BackroomsCream.copy(alpha = 0.45f),
        animationSpec = tween(180),
        label = "categoryColor"
    )
    // U1b：选中项获得玻璃药丸底，切换时有"落到标签上"的实感
    val chipBg by animateColorAsState(
        targetValue = if (isSelected) PhotoriaGlass.Glow else Color.Transparent,
        animationSpec = tween(180),
        label = "categoryChipBg"
    )
    val haptics = LocalHapticFeedback.current
    Text(
        text = name,
        color = color,
        fontSize = 12.sp,
        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(chipBg, RoundedCornerShape(50))
            .clickable {
                if (!isSelected) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}
