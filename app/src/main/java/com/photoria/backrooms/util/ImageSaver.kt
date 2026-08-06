package com.photoria.backrooms.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log

/**
 * 图片保存工具。
 * 使用 MediaStore API 将 Bitmap 保存到设备相册。
 */
object ImageSaver {

    private const val TAG = "ImageSaver"
    private const val GALLERY_DIR = "Photoria"

    /**
     * 将 Bitmap 保存到 DCIM/Photoria 目录，并注册到系统相册。
     *
     * @param context 上下文
     * @param bitmap  要保存的图片
     * @return 保存成功后的 Uri，失败返回 null
     */
    fun saveToGallery(context: Context, bitmap: Bitmap): Uri? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PHOTORIA_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$GALLERY_DIR")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null

            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "图片已保存: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败", e)
            null
        }
    }
}
