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
    // FBO B：调色 pass 目标，原为链式第二 pass 预留、后被移除，
    // W1 起仅在调色激活时按需分配（零调色 = 零显存开销）。
    private var fboA = 0
    private var fboTextureA = 0

    /** 当前 FBO 尺寸（用于按需重建） */
    private var fboWidth = 0
    private var fboHeight = 0

    /** FBO B：调色 pass 目标，仅在调色激活时按需分配 */
    private var fboB = 0
    private var fboTextureB = 0
    private var fboBWidth = 0
    private var fboBHeight = 0

    /** 调色参数（[AdjustmentEngine.pack] 输出，仅 GL 线程读写） */
    private var adjustments: FloatArray? = null

    /** 影调/色彩/色域参数偏离 identity（滑杆有非零值）；与 [curveActive] 共同决定是否走调色 pass */
    private var toneActive = false

    /** 曲线 LUT（256×1 RGBA 字节，[CurveEngine.buildRgbaBytes]），null=未设置 */
    private var curveBytes: ByteArray? = null
    private var curveTextureId = 0
    private var curveDirty = false

    /** 曲线（四通道任一）偏离恒等斜坡 */
    private var curveActive = false

    /** 调色 pass 是否需要执行 = 影调非零 || 曲线非恒等 */
    private var adjustmentsActive = false

    /**
     * 最近一次滤镜处理输出的 2D 纹理 ID（E2 单 pass 录制用）。
     *
     * - 无滤镜：等于输入纹理 ID（透传）
     * - 有滤镜且渲染到屏幕（outputFrameBuffer=0）：等于 fboTextureA
     *   （滤镜处理后的 FBO 颜色附件）
     * - 调色激活（W1）：等于 fboTextureB（滤镜+调色后的最终纹理）
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
        // 调色 program 提前编译（早退式：已编译则只是一次整数比较）。
        // 惰性编译会把 shader 错误拖到首次拖滑杆那一刻，logcat 启动自证链看不到。
        ensureAdjustProgram()
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
     * 设置调色参数（[AdjustmentEngine.pack] 输出，36 float）。仅 GL 线程调用。
     *
     * 全零（identity）且曲线恒等时 [apply] 跳过调色 pass 且不分配 fboB ——
     * 不调色的用户零成本。
     */
    fun setAdjustments(packed: FloatArray) {
        adjustments = packed
        refreshActive()
    }

    /**
     * 设置曲线 LUT（[CurveEngine.buildRgbaBytes]，256×1 RGBA 字节）。仅 GL 线程调用。
     *
     * 像素上传走惰性路径（curveDirty）：上下文重建后纹理句柄失效也能自动补传。
     */
    fun setCurveLut(rgbaBytes: ByteArray) {
        curveBytes = rgbaBytes
        curveDirty = true
        refreshActive()
    }

    private fun refreshActive() {
        val tone = adjustments != null && !AdjustmentEngine.isIdentity(adjustments!!)
        val curve = curveBytes != null && !CurveEngine.isIdentity(curveBytes!!)
        val active = tone || curve
        if (active != adjustmentsActive) {
            adjustmentsActive = active
            Log.d(TAG, "调色激活=$active（影调=$tone 曲线=$curve）")
        }
        toneActive = tone
        curveActive = curve
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
     * 将输入纹理通过滤镜链（+ 调色 pass）处理后渲染到屏幕或指定 FBO。
     * 用于拍照等需要离屏渲染的场景。
     *
     * 处理顺序：滤镜 → 强度混合 → 调色（对齐 Lightroom 的
     * 「滤镜叠层在基础调色之上」心智模型；录像 blit lastOutputTextureId、
     * 拍照写调用方 FBO，两条输出都吃到调色）。
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

        // ── 3. 应用滤镜 + 调色 ────────────────────────────────────
        val filter = currentFilter
        // 原画（Passthrough）输出即原图，混合它没有视觉差异 → 省掉整趟 pass
        val needBlend = filter != null && filter !is PassthroughFilter && strength < 0.999f
        // Passthrough 视作无滤镜：调色直接采样原纹理，省一趟复制
        val realFilter = if (filter !is PassthroughFilter) filter else null

        if (!adjustmentsActive) {
            // ── 无调色：与 W1 之前的路径完全一致 ──
            if (realFilter == null) {
                drawTextureToScreen(inputTextureId, outputFrameBuffer, width, height)
                if (outputFrameBuffer == 0) lastOutputTextureId = inputTextureId
            } else if (outputFrameBuffer != 0) {
                realFilter.apply(inputTextureId, outputFrameBuffer, width, height)
                if (needBlend) blendOriginal(inputTextureId, 1f - strength, outputFrameBuffer, width, height)
            } else {
                realFilter.apply(inputTextureId, fboA, width, height)
                if (needBlend) blendOriginal(inputTextureId, 1f - strength, fboA, width, height)
                drawTextureToScreen(fboTextureA, 0, width, height)
                lastOutputTextureId = fboTextureA
            }
            return
        }

        // ── 有调色 ──
        if (outputFrameBuffer != 0) {
            // 离屏（拍照）
            if (realFilter != null) {
                // 滤镜 + 调色双 pass：fboB 作拍照等大的中间缓冲（仅这一张，
                // 拍完后的下一帧预览会把它缩回内容尺寸，不长期占显存）
                ensureFBOB(width, height)
                if (fboB == 0) {
                    // 中间缓冲分配失败：退化为"只滤镜不调色"，保出片
                    Log.e(TAG, "fboB 分配失败，本张跳过调色")
                    realFilter.apply(inputTextureId, outputFrameBuffer, width, height)
                    if (needBlend) blendOriginal(inputTextureId, 1f - strength, outputFrameBuffer, width, height)
                    return
                }
                realFilter.apply(inputTextureId, fboB, width, height)
                if (needBlend) blendOriginal(inputTextureId, 1f - strength, fboB, width, height)
                applyAdjustments(fboTextureB, outputFrameBuffer, width, height)
            } else {
                // 单调色：一趟直写调用方 FBO，零中间分配
                applyAdjustments(inputTextureId, outputFrameBuffer, width, height)
            }
            return
        }

        // 屏幕渲染：调色结果先落 fboB（录像 blit lastOutputTextureId 需要纹理）
        ensureFBOB(width, height)
        if (fboB == 0) {
            // 分配失败退回无调色屏幕路径
            Log.e(TAG, "fboB 分配失败，本帧跳过调色")
            if (realFilter != null) {
                realFilter.apply(inputTextureId, fboA, width, height)
                if (needBlend) blendOriginal(inputTextureId, 1f - strength, fboA, width, height)
                drawTextureToScreen(fboTextureA, 0, width, height)
                lastOutputTextureId = fboTextureA
            } else {
                drawTextureToScreen(inputTextureId, 0, width, height)
                lastOutputTextureId = inputTextureId
            }
            return
        }
        if (realFilter != null) {
            realFilter.apply(inputTextureId, fboA, width, height)
            if (needBlend) blendOriginal(inputTextureId, 1f - strength, fboA, width, height)
            applyAdjustments(fboTextureA, fboB, width, height)
        } else {
            applyAdjustments(inputTextureId, fboB, width, height)
        }
        drawTextureToScreen(fboTextureB, 0, width, height)
        // 调色后的纹理即本帧录制输出（录像与预览一致）
        lastOutputTextureId = fboTextureB
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

        // 释放调色 program + 曲线 LUT 纹理
        if (adjustProgram != 0) {
            GLES20.glDeleteProgram(adjustProgram)
            adjustProgram = 0
        }
        if (curveTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(curveTextureId), 0)
            curveTextureId = 0
        }
        adjustments = null
        curveBytes = null
        toneActive = false
        curveActive = false
        adjustmentsActive = false

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
        fboB = 0; fboTextureB = 0
        fboBWidth = 0; fboBHeight = 0
        passthroughProgram = 0
        passthroughPositionHandle = 0
        passthroughTexCoordHandle = 0
        passthroughTextureHandle = 0
        blendProgram = 0
        blendPositionHandle = 0
        blendTexCoordHandle = 0
        blendTextureHandle = 0
        blendAlphaHandle = 0
        adjustProgram = 0
        adjustPositionHandle = 0
        adjustTexCoordHandle = 0
        adjustTextureHandle = 0
        adjustCurveHandle = 0
        adjustCurveOnHandle = 0
        adjustA0Handle = 0
        adjustA1Handle = 0
        adjustA2Handle = 0
        adjustBandHandle = 0
        curveTextureId = 0
        curveDirty = curveBytes != null  // 句柄作废：下次使用时重传像素
        lastOutputTextureId = 0
        // adjustments / curveBytes / 激活标志是用户偏好，跨上下文保留（同 strength）
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
        TextureHelper.deleteFrameBuffer(fboB, fboTextureB)
        fboB = 0; fboTextureB = 0
        fboBWidth = 0; fboBHeight = 0
    }

    /**
     * 确保调色目标 FBO B 尺寸匹配（仅在调色激活时被调用，零调色零分配）。
     */
    private fun ensureFBOB(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (fboBWidth == width && fboBHeight == height && fboB != 0) return

        TextureHelper.deleteFrameBuffer(fboB, fboTextureB)
        fboBWidth = width
        fboBHeight = height
        val (fboId, texId) = TextureHelper.createFrameBuffer(width, height)
        fboB = fboId
        fboTextureB = texId
        if (fboId == 0) {
            Log.e(TAG, "fboB 创建失败: ${width}x${height}")
        } else {
            Log.d(TAG, "fboB 已创建: ${width}x${height}")
        }
    }

    // ── 调色 pass ─────────────────────────────────────────
    private var adjustProgram = 0
    private var adjustPositionHandle = 0
    private var adjustTexCoordHandle = 0
    private var adjustTextureHandle = 0
    private var adjustCurveHandle = 0
    private var adjustCurveOnHandle = 0
    private var adjustA0Handle = 0
    private var adjustA1Handle = 0
    private var adjustA2Handle = 0
    private var adjustBandHandle = 0

    /** 曲线单开（影调全零未设置）时的恒等影调参数 */
    private val zeroAdjustments = FloatArray(AdjustmentEngine.PACK_SIZE)

    /**
     * 创建/更新曲线 LUT 纹理并上传像素。
     *
     * **前置条件：调用方已 glActiveTexture(GL_TEXTURE1)** ——
     * 若在 0 号单元上 glBindTexture，会把调色 pass 正在采样的输入纹理
     * 顶掉/解绑 → uTexture 采到空 → 整帧黑屏（真机复现过的坑）。
     *
     * 256×1 RGBA 只有 1KB，每次都走 glTexImage2D 整传：
     * 免去 首建/增量 两条路径的分歧，上下文重建后句柄归零也自动走重建。
     */
    private fun uploadCurveLut(bytes: ByteArray) {
        if (curveTextureId == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            curveTextureId = ids[0]
            if (curveTextureId == 0) {
                Log.e(TAG, "曲线 LUT 纹理创建失败")
                return
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
            // LINEAR：LUT 格点间线性插值，256 级无条带
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
        }
        val buf = java.nio.ByteBuffer
            .allocateDirect(bytes.size)
            .order(java.nio.ByteOrder.nativeOrder())
            .put(bytes)
        buf.position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            CurveEngine.LUT_SIZE, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
    }

    private fun ensureAdjustProgram() {
        if (adjustProgram != 0) return

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

        adjustProgram = com.photoria.backrooms.util.ShaderHelper.buildProgram(
            vertexSource, AdjustmentShaders.FRAGMENT
        )
        if (adjustProgram == 0) {
            Log.e(TAG, "调色 shader 编译失败，调色将退化为直通")
            return
        }
        adjustPositionHandle = GLES20.glGetAttribLocation(adjustProgram, "aPosition")
        adjustTexCoordHandle = GLES20.glGetAttribLocation(adjustProgram, "aTexCoord")
        adjustTextureHandle = GLES20.glGetUniformLocation(adjustProgram, "uTexture")
        adjustCurveHandle = GLES20.glGetUniformLocation(adjustProgram, "uCurve")
        adjustCurveOnHandle = GLES20.glGetUniformLocation(adjustProgram, "uCurveOn")
        adjustA0Handle = GLES20.glGetUniformLocation(adjustProgram, "uA0")
        adjustA1Handle = GLES20.glGetUniformLocation(adjustProgram, "uA1")
        adjustA2Handle = GLES20.glGetUniformLocation(adjustProgram, "uA2")
        adjustBandHandle = GLES20.glGetUniformLocation(adjustProgram, "uBand")
    }

    /**
     * 将 [inputTextureId] 按当前曲线 + 调色参数渲染到 [outputFrameBuffer]。
     *
     * shader 编译失败时退化为 passthrough（宁可无调色，不可黑屏/漏输出）。
     */
    private fun applyAdjustments(inputTextureId: Int, outputFrameBuffer: Int, width: Int, height: Int) {
        ensureAdjustProgram()
        if (adjustProgram == 0) {
            drawTextureToScreen(inputTextureId, outputFrameBuffer, width, height)
            return
        }
        val p = adjustments ?: zeroAdjustments

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFrameBuffer)
        // 调色目标永远是 FBO（fboB 或拍照 FBO），按目标全尺寸视口
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(adjustProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GLES20.glUniform1i(adjustTextureHandle, 0)
        GLES20.glUniform4f(adjustA0Handle, p[0], p[1], p[2], p[3])
        GLES20.glUniform4f(adjustA1Handle, p[4], p[5], p[6], p[7])
        GLES20.glUniform4f(adjustA2Handle, p[8], p[9], p[10], p[11])
        GLES20.glUniform4fv(adjustBandHandle, 6, p, 12)

        // 曲线 LUT 走 texture unit 1：先切单元再上传/绑定 —— 在 unit 0 上
        // bind 会顶掉刚绑定的输入纹理采样源（真机黑屏根因）
        var curveOn = 0f
        val cb = curveBytes
        if (curveActive && cb != null) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            if (curveDirty) {
                uploadCurveLut(cb)
                curveDirty = false
            }
            if (curveTextureId != 0) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, curveTextureId)
                GLES20.glUniform1i(adjustCurveHandle, 1)
                curveOn = 1f
            }
        }
        GLES20.glUniform1f(adjustCurveOnHandle, curveOn)

        drawQuadWith(adjustPositionHandle, adjustTexCoordHandle)

        GLES20.glDisableVertexAttribArray(adjustPositionHandle)
        GLES20.glDisableVertexAttribArray(adjustTexCoordHandle)
        // 收尾：先把 active 拨回 0，再解绑 —— 只脱输入纹理，曲线留在 unit 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glUseProgram(0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
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
