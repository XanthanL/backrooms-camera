package com.photoria.backrooms.gl

import android.content.res.AssetManager
import android.opengl.GLES20
import android.util.Log
import com.photoria.backrooms.gl.filter.BaseFilter
import com.photoria.backrooms.util.ShaderHelper
import com.photoria.backrooms.util.TextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 实时直方图取样器：把滤镜输出降采样到小 FBO，回读像素并统计直方图。
 *
 * 只读取、不改写源纹理，且统计发生在 GL 线程之外的小缓冲上，
 * 因此不影响照片/录像内容。
 *
 * 取样频率限制在 [SAMPLE_INTERVAL_MS]：人眼读直方图不需要 30Hz，
 * 而 glReadPixels 会等待 GPU 管线排空（stall），降到 ~6Hz 可以把这个
 * 代价摊薄到几乎不可见。拍照帧与录制帧由调用方跳过。
 *
 * 所有方法必须在 GL 线程调用。
 */
class HistogramProbe(private val assetManager: AssetManager) {

    companion object {
        private const val TAG = "Histogram"
        private const val VERTEX_PATH = "shaders/vertex/default_vertex.glsl"
        private const val FRAGMENT_PATH = "shaders/fragment/histogram_downsample.glsl"
        /** 降采样后的取样尺寸（4:3，与预览构图一致） */
        private const val SAMPLE_W = 128
        private const val SAMPLE_H = 96
        /** 两次取样的最小间隔 */
        private const val SAMPLE_INTERVAL_MS = 150L
    }

    /** 统计结果回调（GL 线程调用，外层负责切主线程） */
    var onBins: ((HistogramBins) -> Unit)? = null

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var textureHandle = 0
    private var stepHandle = 0

    private var fboId = 0
    private var texId = 0

    /** 回读缓冲（跨次复用，避免每次取样分配 48KB） */
    private val readback: ByteBuffer =
        ByteBuffer.allocateDirect(SAMPLE_W * SAMPLE_H * 4).order(ByteOrder.nativeOrder())

    private var lastSampleMs = 0L
    private var lastLoggedMs = 0L

    private val quadVertices = BaseFilter.createFloatBuffer(floatArrayOf(
        -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    ))
    private val quadTexCoords = BaseFilter.createFloatBuffer(floatArrayOf(
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    ))

    /**
     * 到点则取样并回调 [onBins]。
     *
     * @param srcTexId 滤镜链输出的普通 2D 纹理
     * @param srcW 该纹理宽度
     * @param srcH 该纹理高度
     * @return true 表示本次调用确实完成了一次取样
     */
    fun maybeSample(srcTexId: Int, srcW: Int, srcH: Int): Boolean {
        if (srcTexId == 0 || srcW <= 0 || srcH <= 0) return false

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastSampleMs < SAMPLE_INTERVAL_MS) return false

        ensureProgram()
        if (program == 0) return false
        ensureFbo()
        if (fboId == 0) return false
        lastSampleMs = nowMs

        // 记住调用方的视口，取样后原样交还（避免污染后续 pass）
        val prevViewport = IntArray(4)
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, SAMPLE_W, SAMPLE_H)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTexId)
        GLES20.glUniform1i(textureHandle, 0)
        // 取样步长 = 0.75 个输出像素。一个输出像素覆盖 srcW/SAMPLE_W 个源像素，
        // 换成 UV 单位后 srcW 约掉，故只与取样尺寸有关（0.75 / SAMPLE_W）。
        val stepX = 0.75f / SAMPLE_W
        val stepY = 0.75f / SAMPLE_H
        GLES20.glUniform2f(stepHandle, stepX, stepY)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        readback.position(0)
        GLES20.glReadPixels(
            0, 0, SAMPLE_W, SAMPLE_H,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, readback
        )

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3])

        val bins = HistogramBins.from(readback, SAMPLE_W, SAMPLE_H)
        onBins?.invoke(bins)

        // 探针限频（1 秒一条），避免刷屏同时能确认取样在跑
        if (nowMs - lastLoggedMs > 1000L) {
            lastLoggedMs = nowMs
            Log.d(TAG, "sample ${srcW}x$srcH → ${SAMPLE_W}x$SAMPLE_H peak=${bins.peak}")
        }
        return true
    }

    /** GL 上下文重建：句柄作废，下次取样时重建（不调用 glDelete*） */
    fun resetForNewContext() {
        program = 0
        positionHandle = 0
        texCoordHandle = 0
        textureHandle = 0
        stepHandle = 0
        fboId = 0
        texId = 0
    }

    fun release() {
        TextureHelper.deleteFrameBuffer(fboId, texId)
        fboId = 0
        texId = 0
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
    }

    private fun ensureFbo() {
        if (fboId != 0) return
        val (fbo, tex) = TextureHelper.createFrameBuffer(SAMPLE_W, SAMPLE_H)
        fboId = fbo
        texId = tex
    }

    private fun ensureProgram() {
        if (program != 0) return
        program = ShaderHelper.buildProgramFromAssets(assetManager, VERTEX_PATH, FRAGMENT_PATH)
        if (program == 0) {
            Log.e(TAG, "histogram_downsample program 编译失败")
            return
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        stepHandle = GLES20.glGetUniformLocation(program, "uStep")
    }
}
