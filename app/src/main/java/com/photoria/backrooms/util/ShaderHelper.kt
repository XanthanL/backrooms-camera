package com.photoria.backrooms.util

import android.content.res.AssetManager
import android.opengl.GLES20
import android.util.Log

/**
 * Shader 编译与链接工具类。
 * 提供 shader 源码加载、编译、程序链接等功能。
 */
object ShaderHelper {

    private const val TAG = "ShaderHelper"

    /** #include 指令正则：匹配 #include "path" 形式 */
    private val includeRegex = Regex("""#include\s+"([^"]+)"""")

    /** 已解析路径集合，防止递归包含导致死循环 */
    private fun resolveIncludes(
        source: String,
        assetManager: AssetManager,
        visited: MutableSet<String>
    ): String {
        return includeRegex.replace(source) { match ->
            val includePath = match.groupValues[1]
            if (includePath in visited) {
                // 已包含过，跳过（避免重复定义/死循环）
                "// (已包含) $includePath"
            } else {
                visited.add(includePath)
                val included = assetManager.open(includePath).bufferedReader().use { it.readText() }
                resolveIncludes(included, assetManager, visited)
            }
        }
    }

    /**
     * 从 assets 目录加载 shader 源码文本。
     * 支持 #include "path" 指令预处理：将被包含文件内容内联展开，
     * 同一文件多次包含只展开一次。
     */
    fun loadShaderFromAssets(assetManager: AssetManager, path: String): String {
        val raw = assetManager.open(path).bufferedReader().use { it.readText() }
        return resolveIncludes(raw, assetManager, mutableSetOf(path))
    }

    /**
     * 编译单个 shader。
     * @param type GLES20.GL_VERTEX_SHADER 或 GLES20.GL_FRAGMENT_SHADER
     * @param source shader 源码字符串
     * @return 编译后的 shader id，失败返回 0
     */
    fun compileShader(type: Int, source: String): Int {
        val shaderId = GLES20.glCreateShader(type)
        if (shaderId == 0) {
            Log.e(TAG, "glCreateShader 失败: type=$type")
            return 0
        }

        GLES20.glShaderSource(shaderId, source)
        GLES20.glCompileShader(shaderId)

        // 检查编译状态
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] == 0) {
            val infoLog = GLES20.glGetShaderInfoLog(shaderId)
            Log.e(TAG, "Shader 编译失败 (type=${typeStr(type)}):\n$infoLog")
            Log.e(TAG, "Shader source:\n$source")
            GLES20.glDeleteShader(shaderId)
            return 0
        }

        return shaderId
    }

    /**
     * 链接 vertex shader 和 fragment shader 为一个 program。
     * @return program id，失败返回 0
     */
    fun linkProgram(vertexShaderId: Int, fragmentShaderId: Int): Int {
        val programId = GLES20.glCreateProgram()
        if (programId == 0) {
            Log.e(TAG, "glCreateProgram 失败")
            return 0
        }

        GLES20.glAttachShader(programId, vertexShaderId)
        GLES20.glAttachShader(programId, fragmentShaderId)
        GLES20.glLinkProgram(programId)

        // 检查链接状态
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] == 0) {
            val infoLog = GLES20.glGetProgramInfoLog(programId)
            Log.e(TAG, "Program 链接失败:\n$infoLog")
            GLES20.glDeleteProgram(programId)
            return 0
        }

        // 链接成功后可以 detach shader（释放引用）
        GLES20.glDetachShader(programId, vertexShaderId)
        GLES20.glDetachShader(programId, fragmentShaderId)

        return programId
    }

    /**
     * 便捷方法：从 assets 加载 shader 文件并编译链接为完整 program。
     * @param assetManager AssetManager 实例
     * @param vertexShaderPath 顶点着色器 assets 路径
     * @param fragmentShaderPath 片段着色器 assets 路径
     * @return program id，失败返回 0
     */
    fun buildProgramFromAssets(assetManager: AssetManager, vertexShaderPath: String, fragmentShaderPath: String): Int {
        val vertexSource = loadShaderFromAssets(assetManager, vertexShaderPath)
        val fragmentSource = loadShaderFromAssets(assetManager, fragmentShaderPath)

        val vertexId = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexId == 0) return 0

        val fragmentId = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentId == 0) {
            GLES20.glDeleteShader(vertexId)
            return 0
        }

        return linkProgram(vertexId, fragmentId).also {
            // program 已链接，shader 对象可以删除
            GLES20.glDeleteShader(vertexId)
            GLES20.glDeleteShader(fragmentId)
        }
    }

    /**
     * 从内联字符串编译链接 program。
     */
    fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexId = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexId == 0) return 0

        val fragmentId = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentId == 0) {
            GLES20.glDeleteShader(vertexId)
            return 0
        }

        return linkProgram(vertexId, fragmentId).also {
            GLES20.glDeleteShader(vertexId)
            GLES20.glDeleteShader(fragmentId)
        }
    }

    private fun typeStr(type: Int): String = when (type) {
        GLES20.GL_VERTEX_SHADER -> "VERTEX"
        GLES20.GL_FRAGMENT_SHADER -> "FRAGMENT"
        else -> "UNKNOWN($type)"
    }
}
