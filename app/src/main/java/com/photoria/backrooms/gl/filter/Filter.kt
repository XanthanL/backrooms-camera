package com.photoria.backrooms.gl.filter

/**
 * 滤镜接口。
 * 所有滤镜（包括 Passthrough）均需实现此接口。
 *
 * 生命周期：
 *   setup() → 每帧 apply() → destroy()
 *
 * 注意：所有方法必须在 GL 线程调用。
 */
interface Filter {

    /**
     * 初始化滤镜资源（编译 shader、获取 uniform 位置等）。
     * 在 GL 上下文就绪后调用一次。
     */
    fun setup()

    /**
     * 将滤镜应用到输入纹理，渲染到指定 FBO。
     *
     * @param inputTextureId  输入纹理 ID（GL_TEXTURE_2D 类型）
     * @param outputFrameBuffer  输出 FBO ID（0 表示直接渲染到屏幕）
     * @param width  渲染目标宽度
     * @param height 渲染目标高度
     */
    fun apply(inputTextureId: Int, outputFrameBuffer: Int, width: Int, height: Int)

    /**
     * 设置滤镜参数。
     *
     * @param key   参数名（如 "uTime", "uIntensity" 等）
     * @param value 参数值（Float, Int, FloatArray 等，具体类型由滤镜决定）
     */
    fun setParameter(key: String, value: Any)

    /**
     * 释放滤镜占用的 GL 资源。
     */
    fun destroy()

    /**
     * 返回滤镜名称（用于调试和 UI 显示）。
     */
    fun getName(): String
}
