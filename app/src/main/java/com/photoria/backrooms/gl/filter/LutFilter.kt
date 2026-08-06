package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * LUT 颜色分级滤镜。
 *
 * 使用运行时生成的 2D LUT atlas（模拟 3D LUT）替代 shader 内联调色计算。
 * 优势：
 *   - 调色逻辑预计算到查找表，运行时只需一次纹理采样，比复杂 shader 计算更便宜
 *   - 颜色映射一致性更高（同一输入颜色永远映射到同一输出）
 *
 * LUT atlas 格式（OpenGL ES 2.0 无 3D 纹理，用 2D atlas 模拟）：
 *   - LUT_SIZE = 32（每维 32 级）
 *   - atlas 宽 = 32 * 32 = 1024（32 个 slice 横向排列，每 slice 32 像素宽）
 *   - atlas 高 = 32
 *   - 像素(x, y) 对应原始颜色：
 *       slice = x / 32,  xInSlice = x % 32
 *       r = y / 31,  g = xInSlice / 31,  b = slice / 31
 *     atlas[y][x] = colorGrade(r, g, b)
 *
 * 调色函数（电影青橙色调 + 对比度 + 轻微褪色）：
 *   - 色温偏移：阴影偏青，高光偏暖橙
 *   - 青橙色调：提升 R/B 对比（肤色橙、阴影青）
 *   - 对比度 S 曲线
 *   - 轻微褪色（黑色提升）
 */
class LutFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    companion object {
        private const val TAG = "LutFilter"
        private const val LUT_SIZE = 32
        private const val ATLAS_WIDTH = LUT_SIZE * LUT_SIZE  // 1024
        private const val ATLAS_HEIGHT = LUT_SIZE            // 32
    }

    /** LUT atlas 纹理 ID */
    private var lutTextureId = 0
    /** uLutTexture uniform 句柄 */
    private var lutTextureHandle = 0

    override fun getFragmentShaderPath(): String = "shaders/fragment/lut.glsl"

    override fun onSetup() {
        lutTextureHandle = getUniformLocation("uLutTexture")
        // 生成 LUT atlas 纹理
        generateLutAtlas()
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 绑定 LUT atlas 到 texture unit 1
        if (lutTextureId != 0 && lutTextureHandle >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
            GLES20.glUniform1i(lutTextureHandle, 1)
        }
        // 应用用户可调参数（uLutIntensity）
        applyAdjustableParams()
    }

    /**
     * 生成 LUT atlas 纹理。
     * 对每个像素计算对应的原始 RGB，应用调色函数，写入纹理。
     */
    private fun generateLutAtlas() {
        // RGBA 数据，每像素 4 字节
        val pixelData = ByteBuffer
            .allocateDirect(ATLAS_WIDTH * ATLAS_HEIGHT * 4)
            .order(ByteOrder.nativeOrder())

        // 复用输出数组，避免每像素分配（32K 像素 × 3 = 96K 对象）
        val graded = FloatArray(3)

        for (y in 0 until ATLAS_HEIGHT) {
            for (x in 0 until ATLAS_WIDTH) {
                val slice = x / LUT_SIZE
                val xInSlice = x % LUT_SIZE
                // 归一化原始颜色 [0,1]
                val r = y.toFloat() / (LUT_SIZE - 1)
                val g = xInSlice.toFloat() / (LUT_SIZE - 1)
                val b = slice.toFloat() / (LUT_SIZE - 1)

                // 应用调色函数
                colorGrade(r, g, b, graded)

                pixelData.put((clamp01(graded[0]) * 255).toInt().toByte())
                pixelData.put((clamp01(graded[1]) * 255).toInt().toByte())
                pixelData.put((clamp01(graded[2]) * 255).toInt().toByte())
                pixelData.put(255.toByte()) // alpha
            }
        }
        pixelData.rewind()

        // 创建纹理
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        lutTextureId = textures[0]
        if (lutTextureId == 0) {
            Log.e(TAG, "LUT atlas 纹理创建失败")
            return
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            ATLAS_WIDTH, ATLAS_HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelData
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        Log.d(TAG, "LUT atlas 已生成: ${ATLAS_WIDTH}x${ATLAS_HEIGHT}")
    }

    /**
     * 调色函数（电影青橙色调 + 对比度 + 轻微褪色）。
     * 输入为 [0,1] 的 r/g/b，结果写入 out（避免每像素分配数组）。
     */
    private fun colorGrade(r: Float, g: Float, b: Float, out: FloatArray) {
        var nr = r
        var ng = g
        var nb = b

        // ── 1. 轻微褪色（黑色提升 ~0.04，降低对比度基底）──
        nr = nr * 0.96f + 0.04f
        ng = ng * 0.96f + 0.04f
        nb = nb * 0.96f + 0.04f

        // ── 2. 色温偏移：阴影偏青（R- B+），高光偏暖橙（R+ B-）──
        val luma = 0.299f * nr + 0.587f * ng + 0.114f * nb
        val warmAmount = (luma - 0.5f) * 0.15f  // 高光正向，阴影负向
        nr += warmAmount
        nb -= warmAmount

        // ── 3. 青橙色调：提升 R/B 对比（肤色偏橙，阴影偏青）──
        // 阴影区域：减红加青（蓝绿）
        val shadowWeight = 1f - smoothstep(0f, 0.5f, luma)
        nr -= shadowWeight * 0.06f
        ng += shadowWeight * 0.03f
        nb += shadowWeight * 0.05f
        // 高光区域：加红减蓝（暖橙）
        val highlightWeight = smoothstep(0.5f, 1f, luma)
        nr += highlightWeight * 0.05f
        nb -= highlightWeight * 0.04f

        // ── 4. 对比度 S 曲线（幂函数近似）──
        val contrast = 1.12f
        nr = applyContrast(nr, contrast)
        ng = applyContrast(ng, contrast)
        nb = applyContrast(nb, contrast)

        // ── 5. 饱和度微增（增强青橙分离感）──
        val newLuma = 0.299f * nr + 0.587f * ng + 0.114f * nb
        val sat = 1.1f
        out[0] = newLuma + (nr - newLuma) * sat
        out[1] = newLuma + (ng - newLuma) * sat
        out[2] = newLuma + (nb - newLuma) * sat
    }

    /** 对比度 S 曲线：以 0.5 为中心拉伸 */
    private fun applyContrast(v: Float, contrast: Float): Float {
        return (v - 0.5f) * contrast + 0.5f
    }

    /** 平滑阶跃（GLSL smoothstep 近似） */
    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    override fun destroy() {
        super.destroy()
        if (lutTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
            lutTextureId = 0
        }
    }

    override fun getName(): String = "LUT"
}
