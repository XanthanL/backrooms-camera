package com.photoria.backrooms.encoder

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * EGL 核心封装，参考 Grafika EglCore。
 *
 * 管理 EGL 显示、上下文和窗口 Surface，用于将 GL 渲染输出到 MediaCodec 的 InputSurface。
 * 通过共享 GLSurfaceView 的 EGL 上下文，可以在同一线程中访问相同的 GL 纹理资源。
 *
 * 所有方法必须在 GL 线程调用。
 */
class EglCore {

    companion object {
        private const val TAG = "EglCore"
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    /**
     * 初始化 EGL 显示和上下文。
     *
     * @param sharedContext 共享的 EGL 上下文（来自 GLSurfaceView），
     *                      使编码器 Surface 能访问相同的 GL 纹理资源。
     */
    fun init(sharedContext: EGLContext) {
        // 获取默认 EGL 显示
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay 失败")
        }

        // 初始化 EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize 失败")
        }

        // 配置 EGL：OpenGL ES 3.0，可录制（recordable）
        // E1：GLSurfaceView 已升级到 ES 3.0，编码器共享 context 必须匹配，
        // 否则在某些驱动上 eglCreateContext 共享 ES 2.0 + ES 3.0 会失败。
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("eglChooseConfig 失败")
        }
        eglConfig = configs[0]

        // 创建共享上下文（ES 3.0，与 GLSurfaceView 一致）
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext 失败，error=0x${Integer.toHexString(EGL14.eglGetError())}")
        }

        Log.d(TAG, "EglCore 初始化完成，共享上下文已建立 (ES 3.0)")
    }

    /**
     * 为给定的 Android Surface（来自 MediaCodec InputSurface）创建 EGL 窗口 Surface。
     *
     * @param surface MediaCodec 编码器提供的输入 Surface
     * @return 创建的 EGLSurface，用于后续 makeCurrent 和 swapBuffers
     */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val config = eglConfig ?: throw IllegalStateException("EglCore 未初始化")
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, config, surface, surfaceAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface 失败，error=${EGL14.eglGetError()}")
        }
        Log.d(TAG, "EGL 窗口 Surface 已创建")
        return eglSurface
    }

    /**
     * 将指定 EGLSurface 设为当前渲染目标。
     * 后续所有 GL 操作都将渲染到此 Surface。
     */
    fun makeCurrent(eglSurface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw RuntimeException("eglMakeCurrent 失败，error=${EGL14.eglGetError()}")
        }
    }

    /**
     * 提交当前帧到编码器。
     * 将渲染的帧内容提交给 MediaCodec 的 InputSurface。
     */
    fun swapBuffers(eglSurface: EGLSurface) {
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            throw RuntimeException("eglSwapBuffers 失败，error=${EGL14.eglGetError()}")
        }
    }

    /**
     * 设置帧的呈现时间戳（纳秒）。
     * MediaCodec 使用此时间戳作为输出视频帧的 PTS。
     * 必须在 swapBuffers 之前调用。
     *
     * @param eglSurface 目标 EGLSurface
     * @param nsecs      时间戳（纳秒），必须单调递增
     */
    fun setPresentationTime(eglSurface: EGLSurface, nsecs: Long) {
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, nsecs)
    }

    /**
     * 释放 EGL 资源。
     *
     * 仅销毁本 EglCore 创建的 context 和 surface，**不** 调用
     * eglTerminate / eglReleaseThread。
     *
     * 关键修复：eglDisplay 是与 GLSurfaceView 共享的默认 display，
     * eglTerminate 会销毁整个 display 连接（包括 GLSurfaceView 的
     * context 和 surface），导致录制停止后 GLSurfaceView 继续
     * onDrawFrame → eglSwapBuffers 在已终止的 display 上崩溃（闪退）。
     * eglReleaseThread 同样会剥离线程上所有 EGL 状态。
     *
     * 仅 eglDestroyContext + eglMakeCurrent(NO_CONTEXT) 即可安全释放
     * 本 EglCore 的共享 context，GLSurfaceView 的 display 保持有效。
     */
    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            // 先取消绑定当前线程的 context（避免 destroy 时仍在使用）
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            // 注意：不调用 eglTerminate / eglReleaseThread，
            // 否则会连带销毁 GLSurfaceView 的 EGL 资源 → 闪退
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglConfig = null
        Log.d(TAG, "EglCore 资源已释放")
    }

    /**
     * 销毁指定的 EGLSurface。
     */
    fun destroySurface(eglSurface: EGLSurface) {
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
    }

    /**
     * 取消绑定当前线程的 EGLSurface 和 context（makeCurrent 到 NO_SURFACE/NO_CONTEXT）。
     *
     * 用于销毁编码器 EGLSurface 前的安全解绑：在仍被 context 绑定时直接
     * eglDestroySurface 在部分驱动上会触发 GL 错误。
     */
    fun unbindCurrent() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
            )
        }
    }
}
