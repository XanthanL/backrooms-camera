package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager
import android.opengl.GLES20
import android.util.Log
import com.photoria.backrooms.util.ShaderHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 滤镜抽象基类。
 * 封装 shader program 管理、uniform 变量查询、全屏四边形绘制等通用逻辑。
 * 子类只需实现 shader 路径指定和特定参数设置。
 *
 * 使用方式：
 *   1. 构造时传入 AssetManager
 *   2. setup() 编译链接 shader、初始化 buffer
 *   3. 每帧 apply() → 绑定 FBO → 绘制 → 解绑
 *   4. destroy() 释放资源
 */
abstract class BaseFilter(protected val assetManager: AssetManager) : Filter {

    companion object {
        private const val TAG = "BaseFilter"

        // 通用顶点着色器路径
        const val DEFAULT_VERTEX_SHADER_PATH = "shaders/vertex/default_vertex.glsl"

        // 兜底 fragment shader：当滤镜 shader 编译/链接失败时退化为直通，
        // 避免黑屏（shader 文件缺失、语法错误等场景）
        private const val PASSTHROUGH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        // 全屏四边形顶点坐标 (x, y)，两个三角形组成一个矩形
        private val VERTICES = floatArrayOf(
            -1f, -1f,   // 左下
             1f, -1f,   // 右下
            -1f,  1f,   // 左上
             1f,  1f,   // 右上
        )

        // 纹理坐标 (u, v)
        private val TEX_COORDS = floatArrayOf(
            0f, 0f,   // 左下
            1f, 0f,   // 右下
            0f, 1f,   // 左上
            1f, 1f,   // 右上
        )

        /** 创建 native 顺序的 FloatBuffer */
        fun createFloatBuffer(data: FloatArray): FloatBuffer {
            val buffer = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buffer.put(data).position(0)
            return buffer
        }
    }

    /** GL program id */
    protected var programId = 0

    // ── Uniform 句柄 ───────────────────────────────────────────────
    protected var textureHandle = 0       // uTexture
    protected var timeHandle = 0          // uTime
    protected var resolutionHandle = 0    // uResolution

    // ── Attribute 句柄 ─────────────────────────────────────────────
    protected var positionHandle = 0      // aPosition
    protected var texCoordHandle = 0      // aTexCoord

    // ── 顶点数据 buffer ────────────────────────────────────────────
    protected val vertexBuffer: FloatBuffer = createFloatBuffer(VERTICES)
    protected val texCoordBuffer: FloatBuffer = createFloatBuffer(TEX_COORDS)

    // ── 运行时参数（可通过 setParameter 更新）──────────────────────
    protected var timeValue = 0f
    protected var resolutionValue = floatArrayOf(0f, 0f)

    // ── 用户可调参数管理 ─────────────────────────────────────────
    protected val adjustableParams = mutableMapOf<String, Float>()
    /**
     * 可调参数的 uniform location 缓存。
     * 在 onSetup() 后首次 apply 时按需填入，避免每帧对每个参数都调用
     * glGetUniformLocation（字符串查表 + GL 调用，较重）。
     */
    private val adjustableParamLocations = mutableMapOf<String, Int>()

    /** 设置可调参数（由 UI 层调用，在 GL 线程通过 onApply 生效） */
    fun setAdjustableParam(name: String, value: Float) {
        adjustableParams[name] = value
    }

    /** 获取当前可调参数值（默认 1.0） */
    fun getAdjustableParam(name: String): Float = adjustableParams[name] ?: 1.0f

    /** 在 onApply 中统一将 adjustableParams 中的 uniform 传给 shader */
    protected fun applyAdjustableParams() {
        for ((name, value) in adjustableParams) {
            // 缓存 location：首次查询后复用，避免每帧字符串查表
            val location = adjustableParamLocations.getOrPut(name) {
                GLES20.glGetUniformLocation(programId, name)
            }
            if (location >= 0) {
                GLES20.glUniform1f(location, value)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 子类必须实现的抽象方法
    // ──────────────────────────────────────────────────────────────

    /** 返回 fragment shader 在 assets 中的路径 */
    abstract fun getFragmentShaderPath(): String

    /** shader 链接完成后，子类可在此获取自定义 uniform 句柄 */
    abstract fun onSetup()

    /** 每帧渲染前，子类可在此设置自定义 uniform */
    abstract fun onApply(inputTextureId: Int, width: Int, height: Int)

    // ──────────────────────────────────────────────────────────────
    // Filter 接口实现
    // ──────────────────────────────────────────────────────────────

    override fun setup() {
        // 编译链接 shader program
        programId = ShaderHelper.buildProgramFromAssets(
            assetManager,
            DEFAULT_VERTEX_SHADER_PATH,
            getFragmentShaderPath()
        )
        if (programId == 0) {
            Log.e(TAG, "[${getName()}] shader program 编译失败！回退到内联 Passthrough")
            programId = try {
                val vertexSource = assetManager.open(DEFAULT_VERTEX_SHADER_PATH)
                    .bufferedReader().use { it.readText() }
                ShaderHelper.buildProgram(vertexSource, PASSTHROUGH_FRAGMENT_SHADER)
            } catch (e: Exception) {
                Log.e(TAG, "[${getName()}] 回退 shader 加载失败", e)
                0
            }
            if (programId == 0) return
        }

        // 新 program 的 uniform location 可能不同，清空缓存（上下文重建场景）
        adjustableParamLocations.clear()

        // 获取通用 attribute 句柄
        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")

        // 获取通用 uniform 句柄（可能不存在，-1 为正常）
        textureHandle = GLES20.glGetUniformLocation(programId, "uTexture")
        timeHandle = GLES20.glGetUniformLocation(programId, "uTime")
        resolutionHandle = GLES20.glGetUniformLocation(programId, "uResolution")

        // 子类初始化
        onSetup()

        Log.d(TAG, "[${getName()}] setup 完成, programId=$programId")
    }

    override fun apply(inputTextureId: Int, outputFrameBuffer: Int, width: Int, height: Int) {
        if (programId == 0) {
            Log.w(TAG, "[${getName()}] programId=0，跳过渲染")
            return
        }

        // 绑定输出 FBO（0 = 屏幕）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFrameBuffer)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glUseProgram(programId)

        // ── 设置 uniform ──────────────────────────────────────────
        // uTexture → texture unit 0
        if (textureHandle >= 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
            GLES20.glUniform1i(textureHandle, 0)
        }

        // uTime
        if (timeHandle >= 0) {
            GLES20.glUniform1f(timeHandle, timeValue)
        }

        // uResolution
        if (resolutionHandle >= 0) {
            GLES20.glUniform2f(resolutionHandle, width.toFloat(), height.toFloat())
        }

        // 子类自定义 uniform
        onApply(inputTextureId, width, height)

        // ── 绘制全屏四边形 ─────────────────────────────────────────
        drawQuad()

        // ── 清理状态 ───────────────────────────────────────────────
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    override fun setParameter(key: String, value: Any) {
        when (key) {
            "uTime" -> timeValue = (value as? Float) ?: 0f
            "uResolution" -> {
                @Suppress("UNCHECKED_CAST")
                resolutionValue = value as? FloatArray ?: floatArrayOf(0f, 0f)
            }
        }
    }

    override fun destroy() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            programId = 0
        }
        Log.d(TAG, "[${getName()}] 资源已释放")
    }

    // ──────────────────────────────────────────────────────────────
    // 内部工具方法
    // ──────────────────────────────────────────────────────────────

    /** 绘制全屏四边形（两个三角形，4 个顶点） */
    protected fun drawQuad() {
        // 顶点坐标
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // 纹理坐标
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        // 绘制三角形条带
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 禁用 vertex attribute
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    // ──────────────────────────────────────────────────────────────
    // Uniform 设置辅助方法（供子类在 onApply 中使用）
    // ──────────────────────────────────────────────────────────────

    protected fun setFloat(location: Int, value: Float) {
        if (location >= 0) GLES20.glUniform1f(location, value)
    }

    protected fun setInt(location: Int, value: Int) {
        if (location >= 0) GLES20.glUniform1i(location, value)
    }

    protected fun setVec2(location: Int, x: Float, y: Float) {
        if (location >= 0) GLES20.glUniform2f(location, x, y)
    }

    protected fun setVec3(location: Int, x: Float, y: Float, z: Float) {
        if (location >= 0) GLES20.glUniform3f(location, x, y, z)
    }

    protected fun setVec4(location: Int, x: Float, y: Float, z: Float, w: Float) {
        if (location >= 0) GLES20.glUniform4f(location, x, y, z, w)
    }

    /** 获取自定义 uniform 位置 */
    protected fun getUniformLocation(name: String): Int {
        return GLES20.glGetUniformLocation(programId, name)
    }

    // ──────────────────────────────────────────────────────────────
    // 静态工具
    // ──────────────────────────────────────────────────────────────

}
