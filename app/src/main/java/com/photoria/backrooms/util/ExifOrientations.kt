package com.photoria.backrooms.util

import android.media.ExifInterface

/**
 * EXIF 方向标签推导。
 *
 * 传感器坐标系与显示坐标系的换算只看两个输入：
 *   - rotationDegrees：ImageProxy.imageInfo.rotationDegrees
 *     （把传感器像素顺时针转多少度得到"目标朝向"）
 *   - mirrored：是否前置摄像头（预览做了水平镜像，出片保持同样镜像）
 *
 * 返回 android.media.ExifInterface 的 ORIENTATION_* 常量，可直接写入
 * TAG_ORIENTATION。纯函数，SmokeMain 有全真值表断言。
 */
object ExifOrientations {

    /**
     * 由旋转角与是否镜像推导 EXIF 方向。
     *
     * 后摄（mirrored = false）：0→NORMAL(1)，90→6，180→3，270→8
     * 前摄（mirrored = true）：0→2，90→7，180→4，270→5
     * 非法角度一律 NORMAL(1)，绝不抛错（写标签失败只是元数据问题，不能影响出片）。
     */
    fun forCamera(rotationDegrees: Int, mirrored: Boolean): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return if (!mirrored) {
            when (normalized) {
                0 -> ExifInterface.ORIENTATION_NORMAL
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        } else {
            when (normalized) {
                0 -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
                90 -> ExifInterface.ORIENTATION_TRANSVERSE
                180 -> ExifInterface.ORIENTATION_FLIP_VERTICAL
                270 -> ExifInterface.ORIENTATION_TRANSPOSE
                else -> ExifInterface.ORIENTATION_NORMAL
            }
        }
    }
}
