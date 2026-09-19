package com.photoria.backrooms.ui.components

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * 柔光玻璃（frosted glass）设计令牌与动效规格。
 *
 * 为什么不用 Modifier.blur 做"真背景模糊"：
 *   相机预览是 SurfaceView 在窗口上打的洞，Compose 层拿不到它身后的像素，
 *   RenderEffect 又只能作用于节点自身内容 —— 平台没有 backdrop blur API。
 *   硬做（每帧把预览读回 GPU 纹理再模糊）会直接吃掉预览帧率预算。
 *
 * 所以玻璃感走分层绘制，全部是单次 drawRect/Brush，无离屏 pass：
 *   半透明底色（纵向双色渐变） + 顶部高光带（specular） +
 *   发丝描边 + 程序生成的微噪点 + 软阴影。
 * 这套组合在任意 API 等级上表现一致，成本约等于一块纯色面板。
 */
object PhotoriaGlass {

    // ── 分层参数（后室配色，单一真相源）──────────────────────────
    /** 玻璃底色·上（墙面黄褐提亮，模拟受光面） */
    val FillTop = Color(0xCC3E3519)
    /** 玻璃底色·下（暗黄棕，与遮罩同族） */
    val FillBottom = Color(0xCC100D06)
    /** 顶部高光带（奶油黄低透明度，渐隐到透明） */
    val SpecularTop = Color(0x1AF0E6B8)
    /** 发丝描边 */
    val Hairline = Color(0x26F0E6B8)
    /** 激活态描边（荧光黄） */
    val HairlineActive = Color(0x80D1BC55)
    /** 装饰性柔光斑（API 31+ 才做模糊，低版本直接画淡色块） */
    val Glow = Color(0x2ED1BC55)
    /** 噪点整体透明度：只负责打破纯色带的"塑料感"，不该被注意到 */
    const val NOISE_ALPHA = 0.045f

    val PanelShape = RoundedCornerShape(22.dp)
    val ChipShape = RoundedCornerShape(14.dp)

    /** 按压缩放：快而脆，收尾带一点点回弹（相机按键的手感记忆点） */
    val PressScale: SpringSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 640f)
    /** 弹层进出：临界阻尼，滑入不晃 */
    val PanelSlide: SpringSpec<Float> = spring(dampingRatio = 0.82f, stiffness = 380f)
    /** 选中态缩放（滤镜圈等）：略软，有"吸附"感 */
    val SelectSpring: SpringSpec<Float> = spring(dampingRatio = 0.68f, stiffness = 460f)

    /** [SelectSpring] 的 Dp 版本（animateDpAsState 需要 SpringSpec<Dp>） */
    val SelectSpringDp: SpringSpec<Dp> = spring(dampingRatio = 0.68f, stiffness = 460f)
}

/** 纵向双色的玻璃底渐变（顶亮底暗，单一 Brush 一次 drawRect） */
private fun glassBaseBrush() = Brush.verticalGradient(
    0f to PhotoriaGlass.FillTop,
    1f to PhotoriaGlass.FillBottom
)

/**
 * 程序生成 64×64 白噪点并平铺 —— 柔光玻璃的"磨砂"颗粒。
 * 固定种子：每次安装长得一样，也避免热重载闪烁。
 * remember 保证整个 Composition 只生成一次（≈16KB，常驻无压力）。
 * 像素写成 premultiplied 白（rgb == a），任何后端解释都是纯亮度颗粒。
 */
@Composable
fun rememberNoiseBrush(): Brush {
    val tile = remember {
        val rnd = Random(0x51EED)
        val size = 64
        val bitmap = android.graphics.Bitmap.createBitmap(
            size, size, android.graphics.Bitmap.Config.ARGB_8888
        )
        val pixels = IntArray(size * size) {
            val v = rnd.nextInt(96)
            (v shl 24) or (v shl 16) or (v shl 8) or v
        }
        bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
        bitmap.asImageBitmap()
    }
    return remember(tile) {
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

/**
 * 柔光玻璃面板容器：分层背景 + 描边 + 阴影 + 噪点，内容照常往里放。
 *
 * @param elevated 是否带投影（贴边通栏可以关掉，省一次阴影缓存）
 * @param glow 是否加左上柔光斑（API 31+ 走 blur，低版本退化为淡色块）
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = PhotoriaGlass.PanelShape,
    elevated: Boolean = true,
    glow: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val noiseBrush = rememberNoiseBrush()
    Box(
        modifier
            .then(
                if (elevated) {
                    Modifier.shadow(20.dp, shape, ambientColor = Color.Black, spotColor = Color(0x99000000))
                } else Modifier
            )
            .clip(shape)
            .background(glassBaseBrush(), shape)
            .border(1.dp, PhotoriaGlass.Hairline, shape)
    ) {
        // 高光带 + 噪点：同一次 drawBehind 完成，不多占渲染层
        Box(
            Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to PhotoriaGlass.SpecularTop,
                            0.42f to Color.Transparent
                        )
                    )
                    drawRect(brush = noiseBrush, alpha = PhotoriaGlass.NOISE_ALPHA)
                }
        )
        if (glow) {
            // 装饰柔光斑：静态色块 + blur（Android 12+）。
            // 内容不随帧变化，blur 缓存一次即可，不进每帧开销。
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(72.dp)
                    .offset(x = (-10).dp, y = (-16).dp)
                    .blur(28.dp)
                    .background(PhotoriaGlass.Glow, CircleShape)
            )
        }
        content()
    }
}

/**
 * 按压回弹 + 触感 的通用图标按钮（玻璃圆底）。
 *
 * 只驱动 graphicsLayer 的 scale —— 不触发重组，动画在渲染层完成；
 * indication 关掉水波纹，玻璃面板上按压缩放 + 震动比涟漪更像"相机按键"。
 */
@Composable
fun GlassIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = 44.dp,
    icon: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = PhotoriaGlass.PressScale,
        label = "glassIconScale"
    )
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (active) PhotoriaGlass.Glow else Color.Transparent,
                CircleShape
            )
            .border(
                width = 1.dp,
                color = if (active) PhotoriaGlass.HairlineActive else PhotoriaGlass.Hairline,
                shape = CircleShape
            )
            .clickable(
                interactionSource = interaction,
                indication = null
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

/**
 * 玻璃小胶囊（缩放倍率 / 原图提示 / 夜景建议这类浮动小条）。
 */
@Composable
fun GlassPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.94f else 1f,
        animationSpec = PhotoriaGlass.PressScale,
        label = "glassPillScale"
    )
    val haptics = LocalHapticFeedback.current
    val tap: (() -> Unit)? = if (onClick != null) {
        rememberTapWithHaptic(onClick)
    } else null
    GlassSurface(
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
        shape = CircleShape,
        elevated = false,
        content = {
            if (tap != null) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = tap
                        )
                )
            }
            content()
        }
    )
}

/** 供各组件复用的"点击+触感"包装：toggle 类控件统一震感 */
@Composable
fun rememberTapWithHaptic(onClick: () -> Unit): () -> Unit {
    val haptics = LocalHapticFeedback.current
    val latest by rememberUpdatedState(onClick)
    return remember(haptics) {
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            latest()
        }
    }
}
