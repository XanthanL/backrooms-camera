package com.photoria.backrooms.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow

/**
 * 底部横向滚动的滤镜选择器（后室主题）。
 *
 * 设计原则：
 *   - 后室配色：奶油黄文字 + 暗黄棕底，荧光黄作选中强调
 *   - 圆形缩略图显示当前帧经该滤镜处理的效果（GL 实时渲染）；
 *     缩略图尚未生成时回退为滤镜名称首个汉字
 *   - 选中：荧光黄边框 + 不透明 + 粗体名称
 *   - 未选中：无边框 + 半透明 + 常规名称
 *   - 自定义参数指示点：荧光黄小点
 *
 * @param filters 滤镜名称列表（完整列表，索引 = FilterCatalog 索引）
 * @param selectedIndex 当前选中的滤镜索引（原始索引）
 * @param customizedIndices 已被自定义参数的滤镜索引集合（显示指示点）
 * @param onFilterSelected 滤镜选中回调（回传原始索引）
 * @param displayIndices 当前显示的滤镜索引子集（null = 全部）。用于分类筛选。
 */
@Composable
fun FilterSelector(
    filters: List<String>,
    selectedIndex: Int,
    onFilterSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    customizedIndices: Set<Int> = emptySet(),
    thumbnails: List<ImageBitmap?> = emptyList(),
    displayIndices: List<Int>? = null
) {
    // displayIndices 为 null 时显示全部；否则只显示子集
    val indices = displayIndices ?: filters.indices.toList()
    val listState = rememberLazyListState()
    // 冷启动回读到非 0 滤镜时让选中项进入可视区。只做一次，避免与用户手动滑动竞争。
    var scrolledToInitialSelection by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex, indices.size) {
        if (scrolledToInitialSelection || indices.isEmpty()) return@LaunchedEffect
        scrolledToInitialSelection = true
        val position = indices.indexOf(selectedIndex)
        if (position > 0) listState.scrollToItem(position)
    }
    LazyRow(
        modifier = modifier,
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(indices) { index ->
            FilterItem(
                name = filters[index],
                isSelected = index == selectedIndex,
                isCustomized = index in customizedIndices,
                thumbnail = thumbnails.getOrNull(index),
                onClick = { onFilterSelected(index) }
            )
        }
    }
}

@Composable
private fun FilterItem(
    name: String,
    isSelected: Boolean,
    isCustomized: Boolean,
    thumbnail: ImageBitmap?,
    onClick: () -> Unit
) {
    // 后室风：用透明度区分选中状态
    val itemAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.45f,
        label = "itemAlpha"
    )
    // U1b：选中项弹性放大 + 边框宽度动画，切换滤镜有"吸附"手感
    val itemScale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.88f,
        animationSpec = PhotoriaGlass.SelectSpring,
        label = "filterItemScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (isSelected) 2.dp else 0.dp,
        animationSpec = PhotoriaGlass.SelectSpringDp,
        label = "filterBorder"
    )
    val haptics = LocalHapticFeedback.current

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable {
                if (!isSelected) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                onClick()
            }
            .padding(vertical = 4.dp)
    ) {
        // 滤镜圆形缩略图 + 自定义指示点（整体走 graphicsLayer 缩放，不触发重组）
        Box(
            modifier = Modifier
                .size(52.dp)
                .graphicsLayer {
                    scaleX = itemScale
                    scaleY = itemScale
                }
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(BackroomsShadow.copy(alpha = 0.5f * itemAlpha))
                    .border(
                        width = borderWidth,
                        color = if (isSelected) BackroomsYellow else BackroomsCream,
                        shape = CircleShape
                    )
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 缩略图未就绪：用首个汉字占位
                    Text(
                        text = name.take(1),
                        color = BackroomsCream.copy(alpha = itemAlpha),
                        fontSize = 18.sp,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
            // 自定义参数指示点（右上角荧光黄点）
            if (isCustomized) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(BackroomsYellow)
                )
            }
        }

        // 滤镜名称
        Text(
            text = name,
            color = BackroomsCream.copy(alpha = itemAlpha),
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
