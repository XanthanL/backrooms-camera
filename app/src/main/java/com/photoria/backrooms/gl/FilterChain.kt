package com.photoria.backrooms.gl

import android.opengl.GLES20
import android.util.Log
import com.photoria.backrooms.gl.filter.BaseFilter
import com.photoria.backrooms.gl.filter.Filter
import com.photoria.backrooms.gl.filter.PassthroughFilter
import com.photoria.backrooms.util.TextureHelper

/**
 * 滤镜链管理器。
 *
 * 负责：
 *   - 持有当前活跃滤镜
 *   - 管理 FBO 资源（ping-pong 双缓冲）
 *   - 驱动滤镜的 apply 流程：输入纹理 → 滤镜处理 → FBO → 屏幕
 *
 * 当前 MVP 仅支持单个活跃滤镜，但 FBO 设计已预留链式扩展能力。
 *
 * 使用流程（每帧）：
 *   filterChain.apply(inputTextureId, width, height)
 *
 * 注意：所有方法必须在 GL 线程调用。
 */
class FilterChain {

    companion object {
        private const val TAG = "FilterChain"
    }

    /** 当前活跃滤镜 */
    private var currentFilter: Filter? = null

    /** 待切换的新滤镜（延迟到下一帧生效，避免帧内切换造成 GL 状态问题） */
    private var pendingFilter: Filter? = null

    /** 待切换滤镜是否需要 setup（缓存切换时为 false，避免重编译 shader） */
    private var pendingNeedsSetup: Boolean = false

    // ── FBO 资源 ─────────────────────────────────────────
    // FBO A：滤镜渲染目标。当前 MVP 单滤镜链仅需一个 FBO。
    // 原预留的 FBO B（链式第二 pass）从未被读写，移除以节省 GPU 显存。
    private var fboA = 0
    private var fboTextureA = 0

    /** 当前 FBO 尺寸（用于按需重建） */
    private var fboWidth = 0
    private var fboHeight = 0

    /**
     * 最近一次滤镜处理输出的 2D 纹理 ID（E2 单 pass 录制用）。
     *
     * - 无滤镜：等于输入纹理 ID（透传）
     * - 有滤镜且渲染到屏幕（outputFrameBuffer=0）：等于 fboTextureA
     *   （滤镜处理后的 FBO 颜色附件）
     * - 离屏渲染（outputFrameBuffer!=0，如拍照）：保持上一帧值
     *   （此路径不用于录制，避免覆盖）
     *
     * 由 apply() 在每帧渲染后更新。录制器可直接 blit 此纹理到编码器 Surface，
     * 无需重新执行滤镜，避免双重渲染（E2 优化）。
     */
    var lastOutputTextureId: Int = 0
        private set

    /** 屏幕 letterbox 视口（仅在 outputFrameBuffer=0 时使用） */
    private var screenVpX = 0
    private var screenVpY = 0
    private var screenVpW = 0
    private var screenVpH = 0

    // ──────────────────────────────────────────────────────────────
    // 公开 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 设置屏幕 letterbox 视口。FilterChain 渲染到屏幕（outputFrameBuffer=0）时
     * 使用此视口，外部区域保持黑色（由调用方 glClear 清屏）。
     */
    fun setScreenViewport(x: Int, y: Int, w: Int, h: Int) {
        screenVpX = x; screenVpY = y; screenVpW = w; screenVpH = h
    }

    /**
     * 设置当前滤镜（外部传入实例，未 setup）。
     * 旧滤镜会被 destroy，新滤镜会在下一帧 apply 前 setup。
     * 传入 null 表示移除所有滤镜（直接透传）。
     */
    fun setFilter(filter: Filter?) {
        pendingFilter = filter
        pendingNeedsSetup = true
    }

    /**
     * 切换到已缓存且已 setup 的滤镜实例。
     * 不 destroy 旧实例（仍在缓存中），不 setup 新实例（已 setup），避免重编译 shader。
     */
    fun activateFilter(filter: Filter) {
        pendingFilter = filter
        pendingNeedsSetup = false
    }

    /**
     * 获取当前活跃滤镜。
     */
    fun getCurrentFilter(): Filter? = currentFilter

    /**
     * 获取即将生效或当前生效的滤镜（pendingFilter 优先）。
     *
     * 滤镜切换（activateFilter/setFilter）只在下一帧 apply() 的
     * swapFilterIfNeeded() 中真正生效；若此时外部（如 setFilterParam）
     * 查询 getCurrentFilter()，拿到的仍是上一帧的旧滤镜，导致切换瞬间
     * 推送的参数丢失（LUT 等纯参数驱动的滤镜会表现为"无效"）。
     */
    fun getActiveOrPendingFilter(): Filter? = pendingFilter ?: currentFilter

    /**
     * 每帧开始：处理待切换的滤镜并返回当前生效的滤镜。
     *
     * 供 GLRenderer 在决定渲染路径前调用（例如原画直通 OES 渲染时跳过
     * apply()，但滤镜切换仍需生效）；apply() 内部也会调用，未切换时无开销。
     */
    fun beginFrame(): Filter? {
        swapFilterIfNeeded()
        return currentFilter
    }

    /**
     * 滤镜强度（1.0 = 完全滤镜效果，0 = 完全原图）。仅 GL 线程读写。
     *
     * 混合实现不额外申请 FBO：滤镜结果已写在目标颜色附件里，混合的
     * read-modify-write 由固定功能混合单元完成（只有「用 sampler 采样正在写入的
     * 附件」才是非法 feedback），因此把原图以 alpha = 1-strength 叠回目标即可，
     * 结果 = strength·滤镜 + (1-strength)·原图，与 shader 内 mix() 等价。
     */
    var strength: Float = 1f
        set(value) {
            val coerced = value.coerceIn(0f, 1f)
            if (coerced == field) return
            field = coerced
            Log.d(TAG, "strength=$coerced blend=${coerced < 0.999f}")
        }

    /**
     * 每帧调用：将输入纹理通过滤镜链处理后渲染到屏幕。
     *
     * @param inputTextureId 输入纹理（GL_TEXTURE_2D）
     * @param width  视口宽度
     * @param height 视口高度
     */
    fun apply(inputTextureId: Int, width: Int, height: Int) {
        apply(inputTextureId, 0, width, height)
    }

    /**
     * 将输入纹理通过滤镜链处理后渲染到指定 FBO。
     * 用于拍照等需要离屏渲染的场景。
     *
     * @param inputTextureId  输入纹理（GL_TEXTURE_2D）
     * @param outputFrameBuffer  输出 FBO ID（0 表示渲染到屏幕）
     * @param width  渲染宽度
     * @param height 渲染高度
     */
    fun apply(inputTextureId: Int, outputFrameBuffer: Int, width: Int, height: Int) {
        // ── 1. 处理待切换的滤镜 ────────────────────────────────────
        swapFilterIfNeeded()

        // ── 2. 确保屏幕 FBO 尺寸匹配 ──────────────────────────────
        // 离屏渲染（拍照）直接写调用方 FBO，从不碰 fboA；若也走 ensureFBO，
        // 全分辨率拍照会白白分配再丢弃一个等大的 FBO A（8192×6144 ≈ 201MB）
        if (outputFrameBuffer == 0) {
            ensureFBO(width, height)
        }

        // ── 3. 应用滤镜 ───────────────────────────────────────────
        val filter = currentFilter
        // 原画（Passthrough）输出即原图，混合它没有视觉差异 → 省掉整趟 pass
        val needBlend = filter != null && filter !is PassthroughFilter && strength < 0.999f
        if (filter == null) {
            // 无滤镜：直接将输入纹理渲染到目标（强度对原画无意义）
            drawTextureToScreen(inputTextureId, outputFrameBuffer, width, height)
            // 仅在渲染到屏幕路径更新（离屏渲染不覆盖录制纹理）
            if (outputFrameBuffer == 0) {
                lastOutputTextureId = inputTextureId
            }
        } else if (outputFrameBuffer != 0) {
            // 离屏渲染：直接让滤镜渲染到目标 FBO（跳过中间 FBO A）
            filter.apply(inputTextureId, outputFrameBuffer, width, height)
            if (needBlend) blendOriginal(inputTextureId, 1f - strength, outputFrameBuffer, width, height)
            // 离屏路径不更新 lastOutputTextureId（避免覆盖屏幕渲染的结果）
        } else {
            // 正常渲染到屏幕：滤镜 → FBO A →（强度混合）→ 屏幕
            filter.apply(inputTextureId, fboA, width, height)
            if (needBlend) blendOriginal(inputTextureId, 1f - strength, fboA, width, height)
            drawTextureToScreen(fboTextureA, 0, width, height)
            // 滤镜处理后的 FBO 纹理即为本帧录制输出（已含强度混合 → 录像与预览一致）
            lastOutputTextureId = fboTextureA
        }
    }

    /**
     * 将未处理的原图按 alpha 叠加回已写入 [targetFrameBuffer] 的滤镜结果。
     *
     * 只采样 [inputTextureId]（原图），绝不采样目标自身的颜色附件。
     */
    private fun blendOriginal(
        inputTextureId: Int,
        alpha: Float,
        targetFrameBuffer: Int,
        width: Int,
        height: Int
    ) {
        ensureBlendProgram()
        if (blendProgram == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targetFrameBuffer)
        // 目标为屏幕时用 letterbox 视口，离屏时按目标尺寸
        if (targetFrameBuffer == 0 && screenVpW > 0 && screenVpH > 0) {
            GLES20.glViewport(screenVpX, screenVpY, screenVpW, screenVpH)
        } else {
            GLES20.glViewport(0, 0, width, height)
        }
        GLES20.glUseProgram(blendProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GLES20.glUniform1i(blendTextureHandle, 0)
        GLES20.glUniform1f(blendAlphaHandle, alpha)

        // 混合开关严格成对：泄漏到后续 pass 会造成全局半透明叠加，极难排查
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawQuadWith(blendPositionHandle, blendTexCoordHandle)
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glDisableVertexAttribArray(blendPositionHandle)
        GLES20.glDisableVertexAttribArray(blendTexCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * 释放所有 GL 资源。
     */
    fun release() {
        currentFilter?.destroy()
        currentFilter = null
        pendingFilter?.destroy()
        pendingFilter = null
        pendingNeedsSetup = false
        destroyFBOs()

        // 释放 passthrough shader program
        if (passthroughProgram != 0) {
            GLES20.glDeleteProgram(passthroughProgram)
            passthroughProgram = 0
        }
        passthroughPositionHandle = 0
        passthroughTexCoordHandle = 0
        passthroughTextureHandle = 0

        // 释放强度混合 program
        if (blendProgram != 0) {
            GLES20.glDeleteProgram(blendProgram)
            blendProgram = 0
        }
        blendPositionHandle = 0
        blendTexCoordHandle = 0
        blendTextureHandle = 0
        blendAlphaHandle = 0

        Log.d(TAG, "FilterChain 资源已释放")
    }

    /**
     * GL 上下文（重新）创建后调用：旧 context 下的 FBO / program 句柄全部失效。
     *
     * CameraGLSurfaceView 设了 setPreserveEGLContextOnPause(true)，多数前后台切换
     * 不会走这里；但上下文真被重建时，若不清零这些句柄，
     * ensureFBO() / ensurePassthroughProgram() / ensureBlendProgram() 会因句柄非 0
     * 而早退，拿着失效对象绘制 → 黑屏或 GL 错误。
     *
     * 只归零句柄、不调用 glDelete*（那些对象属于已消失的 context）；
     * [strength] 是用户偏好而非 GL 资源，保持不变。
     */
    fun resetForNewContext() {
        fboA = 0; fboTextureA = 0
        fboWidth = 0; fboHeight = 0
        passthroughProgram = 0
        passthroughPositionHandle = 0
        passthroughTexCoordHandle = 0
        passthroughTextureHandle = 0
        blendProgram = 0
        blendPositionHandle = 0
        blendTexCoordHandle = 0
        blendTextureHandle = 0
        blendAlphaHandle = 0
        lastOutputTextureId = 0
        Log.d(TAG, "FilterChain 句柄已随新上下文重置")
    }

    // ──────────────────────────────────────────────────────────────
    // 内部方法
    // ──────────────────────────────────────────────────────────────

    /**
     * 在帧开始时切换滤镜（避免帧中切换 GL 状态）。
     */
    private fun swapFilterIfNeeded() {
        val pending = pendingFilter ?: return
        pendingFilter = null

        if (pendingNeedsSetup) {
            // 外部传入实例：销毁旧滤镜 + setup 新滤镜
            currentFilter?.destroy()
            currentFilter = pending
            pending.setup()
        } else {
            // 缓存切换：不 destroy 旧实例（仍在缓存中），不 setup 新实例（已 setup）
            currentFilter = pending
        }

        Log.d(TAG, "滤镜切换为: ${pending.getName()}")
    }

    /**
     * 确保 FBO 尺寸与视口一致，不一致则重建。
     */
    private fun ensureFBO(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (fboWidth == width && fboHeight == height && fboA != 0) return

        // 尺寸变化，释放旧 FBO 并重建
        destroyFBOs()
        fboWidth = width
        fboHeight = height

        val (fboIdA, texIdA) = TextureHelper.createFrameBuffer(width, height)
        fboA = fboIdA
        fboTextureA = texIdA

        Log.d(TAG, "FBO 已创建: ${width}x${height}")
    }

    /**
     * 销毁所有 FBO 资源。
     */
    private fun destroyFBOs() {
        TextureHelper.deleteFrameBuffer(fboA, fboTextureA)
        fboA = 0; fboTextureA = 0
    }

    /**
     * 将 2D 纹理直接渲染到屏幕（无滤镜处理）。
     * 使用一个简单的 passthrough shader。
     */
    private var passthroughProgram = 0
    private var passthroughPositionHandle = 0
    private var passthroughTexCoordHandle = 0
    private var passthroughTextureHandle = 0

    private val quadVertices = BaseFilter.createFloatBuffer(floatArrayOf(
        -1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f
    ))
    private val quadTexCoords = BaseFilter.createFloatBuffer(floatArrayOf(
        0f, 0f,  1f, 0f,  0f, 1f,  1f, 1f
    ))

    private fun ensurePassthroughProgram() {
        if (passthroughProgram != 0) return

        val vertexSource = """
            precision mediump float;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        val fragmentSource = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        passthroughProgram = com.photoria.backrooms.util.ShaderHelper.buildProgram(vertexSource, fragmentSource)
        if (passthroughProgram == 0) return

        passthroughPositionHandle = GLES20.glGetAttribLocation(passthroughProgram, "aPosition")
        passthroughTexCoordHandle = GLES20.glGetAttribLocation(passthroughProgram, "aTexCoord")
        passthroughTextureHandle = GLES20.glGetUniformLocation(passthroughProgram, "uTexture")
    }

    /**
     * 将指定 2D 纹理渲染到屏幕（0 号 FBO）。
     */
    private fun drawTextureToScreen(textureId: Int, outputFrameBuffer: Int, width: Int, height: Int) {
        ensurePassthroughProgram()
        if (passthroughProgram == 0) return

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFrameBuffer)
        // 渲染到屏幕（outputFrameBuffer=0）时使用 letterbox 视口；离屏则按目标尺寸
        if (outputFrameBuffer == 0 && screenVpW > 0 && screenVpH > 0) {
            GLES20.glViewport(screenVpX, screenVpY, screenVpW, screenVpH)
        } else {
            GLES20.glViewport(0, 0, width, height)
        }
        GLES20.glUseProgram(passthroughProgram)

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(passthroughTextureHandle, 0)

        // 顶点 + 纹理坐标 + 绘制
        drawQuadWith(passthroughPositionHandle, passthroughTexCoordHandle)

        // 清理
        GLES20.glDisableVertexAttribArray(passthroughPositionHandle)
        GLES20.glDisableVertexAttribArray(passthroughTexCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
    }

    // ── 滤镜强度混合 program ────────────────────────────────
    private var blendProgram = 0
    private var blendPositionHandle = 0
    private var blendTexCoordHandle = 0
    private var blendTextureHandle = 0
    private var blendAlphaHandle = 0

    private fun ensureBlendProgram() {
        if (blendProgram != 0) return

        val vertexSource = """
            precision mediump float;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()
        // 输出常量 alpha，交由 GL 混合单元做 (1-a)·原图 + a·目标（目标已是滤镜结果）
        val fragmentSource = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform float uAlpha;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = vec4(texture2D(uTexture, vTexCoord).rgb, uAlpha);
            }
        """.trimIndent()

        blendProgram = com.photoria.backrooms.util.ShaderHelper.buildProgram(vertexSource, fragmentSource)
        if (blendProgram == 0) return

        blendPositionHandle = GLES20.glGetAttribLocation(blendProgram, "aPosition")
        blendTexCoordHandle = GLES20.glGetAttribLocation(blendProgram, "aTexCoord")
        blendTextureHandle = GLES20.glGetUniformLocation(blendProgram, "uTexture")
        blendAlphaHandle = GLES20.glGetUniformLocation(blendProgram, "uAlpha")
    }

    /** 以全屏四边形绘制当前 program（TRIANGLE_STRIP，4 顶点） */
    private fun drawQuadWith(positionHandle: Int, texCoordHandle: Int) {
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
}
