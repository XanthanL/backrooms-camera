package com.photoria.backrooms.util

import android.opengl.GLES20
import android.util.Log

/**
 * 纹理与 FBO 工具类。
 * 提供 Framebuffer Object 的创建、销毁等操作。
 */
object TextureHelper {

    private const val TAG = "TextureHelper"

    /**
     * 创建一个 FBO 及其配套纹理。
     * 纹理格式为 RGBA + UNSIGNED_BYTE，使用 LINEAR 滤波。
     *
     * @param width  纹理宽度
     * @param height 纹理高度
     * @return Pair(fboId, textureId)，失败时对应值为 0
     */
    fun createFrameBuffer(width: Int, height: Int): Pair<Int, Int> {
        // 1. 创建纹理
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        val textureId = textureIds[0]
        if (textureId == 0) {
            Log.e(TAG, "glGenTextures 失败")
            return Pair(0, 0)
        }

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        // 2. 创建 FBO
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        val fboId = fboIds[0]
        if (fboId == 0) {
            Log.e(TAG, "glGenFramebuffers 失败")
            GLES20.glDeleteTextures(1, textureIds, 0)
            return Pair(0, 0)
        }

        // 3. 将纹理附加到 FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textureId, 0
        )

        // 4. 检查 FBO 完整性
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "FBO 不完整: status=$status")
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            deleteFrameBuffer(fboId, textureId)
            return Pair(0, 0)
        }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        return Pair(fboId, textureId)
    }

    /**
     * 删除 FBO 及其配套纹理。
     */
    fun deleteFrameBuffer(fboId: Int, textureId: Int) {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        }
        if (textureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
        }
    }
}
