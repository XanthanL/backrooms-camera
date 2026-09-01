package com.photoria.backrooms.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStream

/**
 * 图片保存工具。
 * 使用 MediaStore API 将 Bitmap 或已编码字节保存到设备相册。
 */
object ImageSaver {

    private const val TAG = "ImageSaver"
    private const val GALLERY_DIR = "Photoria"

    /**
     * 将 Bitmap 以 JPEG 保存到 DCIM/Photoria 目录，并注册到系统相册。
     *
     * EXIF 方向通过框架 android.media.ExifInterface 写入：bitmap.compress
     * 只能吐到流里，而 ExifInterface 需要真实文件路径，因此先压到缓存临时文件、
     * 写好标签再把字节搬进 MediaStore。EXIF 写失败只记日志——元数据问题绝不丢照片。
     *
     * @param exifOrientation ExifInterface.ORIENTATION_* 之一，默认正立（1）
     * @return 保存成功后的 Uri，失败返回 null
     */
    fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        exifOrientation: Int = ExifInterface.ORIENTATION_NORMAL
    ): Uri? {
        val tmp: File = try {
            File.createTempFile("photoria_", ".jpg", context.cacheDir)
        } catch (e: Exception) {
            Log.e(TAG, "临时文件创建失败", e)
            return null
        }
        return try {
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            runCatching {
                ExifInterface(tmp.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
                    saveAttributes()
                }
            }.onFailure { Log.w(TAG, "EXIF 写入失败（照片仍会保存）", it) }
            saveImage(context, "jpg", "image/jpeg") { out ->
                tmp.inputStream().use { it.copyTo(out) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败", e)
            null
        } finally {
            tmp.delete()
        }
    }

    /**
     * 保存一段已经编码好的图片字节（动图用）。
     *
     * 不能走 bitmap.compress：Android 的 CompressFormat 根本不支持 GIF，
     * 而 GIF 已由 gif/GifEncoder 自己编好，这里只负责把它原样写进相册。
     *
     * @param mimeType 如 image/gif
     * @param extension 文件名后缀（不含点）
     */
    fun saveBytesToGallery(
        context: Context,
        bytes: ByteArray,
        mimeType: String,
        extension: String
    ): Uri? = saveImage(context, extension, mimeType) { out -> out.write(bytes) }

    /**
     * 三者共用的落盘骨架：文件名 / MIME / 写入动作是唯一差别。
     * 拆开写会让 GIF 分支漏掉 IS_PENDING 收尾，相册里就多出一个"不可见"的文件。
     */
    private fun saveImage(
        context: Context,
        extension: String,
        mimeType: String,
        write: (OutputStream) -> Unit
    ): Uri? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "PHOTORIA_${System.currentTimeMillis()}.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // _relative_path 在 Android 10 之前根本不存在这列，无条件塞进去
                    // 会让整条 insert 失败 —— 落到默认相册目录远好过丢照片
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$GALLERY_DIR")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: return null

            resolver.openOutputStream(uri)?.use(write)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            Log.d(TAG, "已保存[$mimeType]: $uri")
            uri
        } catch (e: Exception) {
            Log.e(TAG, "保存图片失败", e)
            null
        }
    }
}
