package com.photoria.backrooms.util

/**
 * 全分辨率出片的几何计算：给定传感器帧尺寸、旋转角、是否镜像与目标画幅比，
 * 推导输出分辨率（保持画幅比、≤ maxTextureSize、偶数化）以及四角纹理坐标。
 *
 * 纹理坐标定义在**传感器原始 UV 空间**（0..1），采样时通过旋转/镜像矩阵把
 * "显示朝向"映射回传感器像素。这样 YUV→RGB shader 只需一套代码，旋转/裁切/
 * 镜像全部由 UV 变换完成 —— 单 pass，不产生中间全分辨率 RGBA 纹理。
 *
 * 纯函数，无 GL 依赖；SmokeMain 有全断言覆盖。
 */
object FullResMath {

    /**
     * 计算结果：输出宽高 + 四角纹理坐标（按 GL 三角形带顺序：左下、右下、左上、右上）。
     */
    data class Result(
        val outW: Int,
        val outH: Int,
        /** [leftBottomU, leftBottomV, rightBottomU, rightBottomV, ...] 共 8 个值 */
        val texCoords: FloatArray
    )

    /**
     * 推导全分辨率出片的输出尺寸与纹理坐标。
     *
     * @param sensorW   传感器帧宽（像素，未旋转前）
     * @param sensorH   传感器帧高（像素，未旋转前）
     * @param rotationDegrees 传感器旋转角（0/90/180/270，顺时针）
     * @param mirrored  是否前置摄像头（水平镜像）
     * @param contentAspect 内容画幅比 = contentW/contentH（预览 letterbox 视口的比例）
     * @param maxTextureSize GL_MAX_TEXTURE_SIZE 上限
     * @return 输出尺寸与纹理坐标；非法输入返回 null
     */
    fun computeOutput(
        sensorW: Int,
        sensorH: Int,
        rotationDegrees: Int,
        mirrored: Boolean,
        contentAspect: Float,
        maxTextureSize: Int
    ): Result? {
        if (sensorW <= 0 || sensorH <= 0 || maxTextureSize <= 0) return null
        if (!contentAspect.isFinite() || contentAspect <= 0f) return null

        // 1. 旋转后的传感器边界框
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val rotatedW: Int
        val rotatedH: Int
        if (normalizedRotation == 90 || normalizedRotation == 270) {
            rotatedW = sensorH
            rotatedH = sensorW
        } else {
            rotatedW = sensorW
            rotatedH = sensorH
        }

        // 2. 在旋转后的空间里做中心裁切，保持内容画幅比
        val targetW: Int
        val targetH: Int
        val rotatedAspect = rotatedW.toFloat() / rotatedH
        if (rotatedAspect > contentAspect) {
            // 旋转后更宽 → 以高为基准，裁宽
            targetH = rotatedH
            targetW = (rotatedH * contentAspect).toInt()
        } else {
            // 旋转后更高 → 以宽为基准，裁高
            targetW = rotatedW
            targetH = (rotatedW / contentAspect).toInt()
        }

        // 3. 限制到 maxTextureSize 并偶数化
        val clampedLong = minOf(maxOf(targetW, targetH), maxTextureSize) and 0x7FFFFFFE
        val finalW: Int
        val finalH: Int
        if (targetW >= targetH) {
            finalW = clampedLong
            finalH = ((clampedLong.toFloat() / targetW) * targetH).toInt() and 0x7FFFFFFE
        } else {
            finalH = clampedLong
            finalW = ((clampedLong.toFloat() / targetH) * targetW).toInt() and 0x7FFFFFFE
        }
        if (finalW <= 0 || finalH <= 0) return null

        // 4. 计算裁剪区域在旋转后空间里的归一化 UV（0..1）
        val cropLeft = (rotatedW - targetW) / 2f / rotatedW
        val cropTop = (rotatedH - targetH) / 2f / rotatedH
        val cropRight = cropLeft + targetW.toFloat() / rotatedW
        val cropBottom = cropTop + targetH.toFloat() / rotatedH

        // 5. 构建四角纹理坐标（GL 坐标系：原点在左下角，V 向上增长）
        //    图像坐标系的 cropTop/cropBottom 需转换为 GL V 坐标：
        //    GL_V_bottom = 1 - cropBottom_image, GL_V_top = 1 - cropTop_image
        val glVBottom = 1f - cropBottom
        val glVTop = 1f - cropTop
        
        // 纹理坐标直接对应裁剪区域，不做旋转/镜像变换
        // （旋转由 EXIF 方向标签处理，镜像由预览渲染管线处理）
        val texCoords = floatArrayOf(
            cropLeft, glVBottom,   // 左下
            cropRight, glVBottom,  // 右下
            cropLeft, glVTop,      // 左上
            cropRight, glVTop      // 右上
        )

        return Result(finalW, finalH, texCoords)
    }
}
