package com.photoria.backrooms.gl.filter

import android.content.res.AssetManager
import android.opengl.GLES20
import com.photoria.backrooms.util.ShaderHelper

/**
 * Passthrough 滤镜 —— 直接传递纹理，不做任何处理。
 * 用于验证渲染管线和作为"无滤镜"选项。
 *
 * fragment shader 内联在代码中，无需单独 glsl 文件。
 */
class PassthroughFilter(assetManager: AssetManager) : BaseFilter(assetManager) {

    companion object {
        /**
         * 内联的 passthrough fragment shader。
         * 仅采样纹理并直接输出，无任何后处理。
         */
        private const val PASSTHROUGH_FRAGMENT_SHADER = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }

    override fun getFragmentShaderPath(): String {
        // Passthrough 使用内联 shader，此方法不会被调用
        return ""
    }

    override fun onSetup() {
        // 无需额外初始化
    }

    override fun onApply(inputTextureId: Int, width: Int, height: Int) {
        // 无需额外 uniform
    }

    /**
     * 覆盖 setup()：使用内联 shader 而非从 assets 加载。
     */
    override fun setup() {
        val vertexSource = assetManager.open(DEFAULT_VERTEX_SHADER_PATH).bufferedReader().use { it.readText() }
        programId = ShaderHelper.buildProgram(vertexSource, PASSTHROUGH_FRAGMENT_SHADER)
        if (programId == 0) return

        positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
        textureHandle = GLES20.glGetUniformLocation(programId, "uTexture")
        timeHandle = -1
        resolutionHandle = -1

        onSetup()
    }

    override fun getName(): String = "Passthrough"
}
