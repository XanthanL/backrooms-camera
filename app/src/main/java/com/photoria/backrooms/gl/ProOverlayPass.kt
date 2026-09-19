package com.photoria.backrooms.gl

import android.content.res.AssetManager
import android.opengl.GLES20
import android.util.Log
import com.photoria.backrooms.gl.filter.BaseFilter
import com.photoria.backrooms.util.ShaderHelper

/**
 * 取景辅助叠加 pass：一次绘制同时完成斑马纹与峰值对焦。
 *
 * 两者共用一个 program 而不是各开一个 pass，因为它们是同一类操作
 * （采样滤镜输出 → 判定 → 改写当前像素），拆成两遍会白多一次全屏绘制。
 *
 * 只画到默认帧缓冲，绝不写回纹理 → 辅助标记不会被照片/录像收录。
 * 所有方法必须在 GL 线程调用。
 */
class ProOverlayPass(private val assetManager: AssetManager) {

    companion object {
        private const val TAG = "ProOverlay"
        private const val VERTEX_PATH = "shaders/vertex/default_vertex.glsl"
        private const val FRAGMENT_PATH = "shaders/fragment/pro_overlay.glsl"
    }

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var texelHandle = 0
    private var zebraModeHandle = 0
    private var peakingHandle = 0
    private var peakingThresholdHandle = 0

    private val quadVertices = BaseFilter.createFloatBuffer(floatArrayOf(
        -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    ))
    private val quadTexCoords = BaseFilter.createFloatBuffer(floatArrayOf(
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    ))

    /** 仅在配置变化时打日志，避免 30fps 下刷屏 */
    private var lastLoggedConfig: ProOverlayConfig? = null

    /**
     * 绘制叠加层。
     *
     * @param srcTexId 滤镜链输出的普通 2D 纹理（不可传 OES 纹理）
     * @param textureW 该纹理宽度（用于计算梯度采样步长）
     * @param textureH 该纹理高度
     * @param cfg 叠加配置；[ProOverlayConfig.active] 为 false 时直接跳过
     * @param vpX 屏幕 letterbox 视口左下 x（辅助标记不越界到黑边）
     * @param vpY 屏幕 letterbox 视口左下 y
     * @param vpW 视口宽
     * @param vpH 视口高
     */
    fun draw(
        srcTexId: Int,
        textureW: Int,
        textureH: Int,
        cfg: ProOverlayConfig,
        vpX: Int,
        vpY: Int,
        vpW: Int,
        vpH: Int
    ) {
        if (!cfg.active || srcTexId == 0 || vpW <= 0 || vpH <= 0 || textureW <= 0) return

        ensureProgram()
        if (program == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        // 自设视口：录像 blit 会把视口改成编码器尺寸且不恢复，这里不依赖上游状态
        GLES20.glViewport(vpX, vpY, vpW, vpH)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        GLES20.glUniform1i(textureHandle, 0)
        GLES20.glUniform2f(texelHandle, 1f / textureW, 1f / textureH)
        // ZebraMode.ordinal 与 shader 中的 0/1/2 约定一一对应
        GLES20.glUniform1f(zebraModeHandle, cfg.zebra.ordinal.toFloat())
        GLES20.glUniform1f(peakingHandle, if (cfg.peaking) 1f else 0f)
        GLES20.glUniform1f(
            peakingThresholdHandle,
            cfg.peakingThreshold.coerceIn(0.02f, 0.8f)
        )

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)

        if (cfg != lastLoggedConfig) {
            lastLoggedConfig = cfg
            Log.d(TAG, "draw tex=$srcTexId vp=$vpX,$vpY ${vpW}x$vpH zebra=${cfg.zebra} peaking=${cfg.peaking}@${cfg.peakingThreshold}")
        }
    }

    /**
     * GL 上下文（重新）创建后调用：program 句柄属于已消失的 context，
     * 必须归零以便下次绘制时重新编译，否则拿失效句柄绘制会黑屏或驱动崩溃。
     */
    fun resetForNewContext() {
        program = 0
        positionHandle = 0
        texCoordHandle = 0
        textureHandle = 0
        texelHandle = 0
        zebraModeHandle = 0
        peakingHandle = 0
        peakingThresholdHandle = 0
        lastLoggedConfig = null
    }

    fun release() {
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun ensureProgram() {
        if (program != 0) return
        program = ShaderHelper.buildProgramFromAssets(assetManager, VERTEX_PATH, FRAGMENT_PATH)
        if (program == 0) {
            Log.e(TAG, "pro_overlay program 编译失败")
            return
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        texelHandle = GLES20.glGetUniformLocation(program, "uTexel")
        zebraModeHandle = GLES20.glGetUniformLocation(program, "uZebraMode")
        peakingHandle = GLES20.glGetUniformLocation(program, "uPeaking")
        peakingThresholdHandle = GLES20.glGetUniformLocation(program, "uPeakingThreshold")
    }
}
