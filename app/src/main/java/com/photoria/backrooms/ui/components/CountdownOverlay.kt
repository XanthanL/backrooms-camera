package com.photoria.backrooms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoria.backrooms.ui.theme.BackroomsCream
import com.photoria.backrooms.ui.theme.BackroomsShadow
import com.photoria.backrooms.ui.theme.BackroomsYellow
import com.photoria.backrooms.ui.theme.NumberFont

/**
 * 倒计时自拍的取景器浮层：居中大数字 + 「再点一次取消」提示。
 *
 * 刻意不加 clickable / pointerInput：它只是一层显示，手势必须继续穿透到取景器
 * （点击对焦、捏合缩放），取消走快门本身 —— 多一个可点区域只会多一种误触。
 *
 * @param seconds 剩余秒数；null 表示未在倒数，此时不绘制任何东西
 */
@Composable
fun CountdownOverlay(
    seconds: Int?,
    modifier: Modifier = Modifier
) {
    val left = seconds ?: return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 深色圆底：亮天空下纯黄字会糊成一片
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(CircleShape)
                .background(BackroomsShadow.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = left.toString(),
                color = BackroomsYellow,
                fontSize = 84.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = NumberFont
            )
        }
        Text(
            text = "再点一次取消",
            color = BackroomsCream,
            fontSize = 12.sp,
            modifier = Modifier
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(50))
                .background(BackroomsShadow.copy(alpha = 0.7f))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        )
    }
}
