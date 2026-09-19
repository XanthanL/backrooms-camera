package com.photoria.backrooms.camera

import android.content.Context
import android.net.Uri
import android.util.Log
import com.photoria.backrooms.encoder.GLVideoRecorder
import com.photoria.backrooms.gl.CameraGLSurfaceView

/**
 * 视频录像管理器（重构版）。
 * 封装 GLVideoRecorder，提供录制控制逻辑。
 *
 * 录像流程：
 *   1. 用户点击录制 → startRecording()
 *   2. GLVideoRecorder 在 GL 线程中初始化编码器并开始录制
 *   3. 每帧渲染后自动将滤镜帧写入编码器
 *   4. 用户再次点击 → stopRecording()
 *   5. 视频保存到 DCIM/Photoria 目录
 */
class VideoRecorder(private val context: Context) {

    companion object {
        private const val TAG = "VideoRecorder"
    }

    /** GL 视频录制器实例 */
    val glRecorder = GLVideoRecorder(context)

    /** 当前是否正在录制 */
    @Volatile
    var isRecording = false
        private set

    /** 录制开始时间戳（毫秒） */
    private var recordingStartTime = 0L

    /** 视频保存成功回调（Uri 字符串） */
    var onVideoSaved: ((Uri?) -> Unit)? = null

    /** 录制失败回调 */
    var onRecordingError: ((Throwable) -> Unit)? = null

    init {
        // 设置 GLVideoRecorder 的回调
        glRecorder.onVideoSaved = { path ->
            isRecording = false
            onVideoSaved?.invoke(Uri.parse(path))
        }
        glRecorder.onRecordingError = { e ->
            isRecording = false
            onRecordingError?.invoke(e)
        }
    }

    /**
     * 开始录制。
     * 通过 CameraGLSurfaceView 在 GL 线程中启动录制。
     *
     * @param glSurfaceView CameraGLSurfaceView 实例
     * @return 是否成功发起录制（实际启动是异步的）
     */
    fun startRecording(glSurfaceView: CameraGLSurfaceView): Boolean {
        if (isRecording) {
            Log.w(TAG, "已在录制中")
            return false
        }

        isRecording = true
        recordingStartTime = System.currentTimeMillis()

        glSurfaceView.startVideoRecording(glRecorder) { success ->
            if (!success) {
                isRecording = false
                onRecordingError?.invoke(RuntimeException("录制启动失败"))
            } else {
                Log.d(TAG, "录制已开始")
            }
        }

        return true
    }

    /**
     * 停止录制。
     * 通过 CameraGLSurfaceView 在 GL 线程中停止编码器并完成视频文件。
     */
    fun stopRecording(glSurfaceView: CameraGLSurfaceView) {
        if (!isRecording) return
        // 本地状态立即复位：GL 线程的停止与相册迁移都是异步的，
        // 不能等 onVideoSaved 回调才复位，否则期间可重复进入停止路径
        isRecording = false
        glSurfaceView.stopVideoRecording()
        Log.d(TAG, "停止录制")
    }

    /**
     * 获取录制时长（秒）。
     */
    fun getRecordingDurationSec(): Int {
        return if (isRecording) {
            ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
        } else {
            0
        }
    }

    /**
     * 释放资源。
     */
    fun release() {
        if (isRecording) {
            isRecording = false
        }
        glRecorder.release()
    }
}
