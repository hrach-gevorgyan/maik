package com.maik.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID

/** Photos attached to messages: copied in, shrunk, kept private, removed with their chat. */
class Photos(context: Context) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "images").apply { mkdirs() }

    /** Where the next import will land, known before the copying starts. */
    fun reserve(): File = File(dir, "${UUID.randomUUID()}.jpg")

    /**
     * A private, shrunk JPEG copy of [uri] at [out], or null if it couldn't be read.
     *
     * The destination is chosen by the caller so it can be protected from [keepOnly]
     * for as long as the copy takes.
     */
    fun importFrom(uri: Uri, out: File): File? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = app.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scaled = scaleDown(rotateUpright(decoded, uri))
        out.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out
    }.getOrNull()

    /** Removes the full-size camera capture once a shrunk copy has been made. */
    fun clearCameraCapture() {
        runCatching { cameraTarget().delete() }
    }

    /** Removes photos no message refers to any more. */
    fun keepOnly(paths: Set<String>) {
        dir.listFiles()?.forEach { if (it.absolutePath !in paths) it.delete() }
    }

    private fun rotateUpright(bitmap: Bitmap, uri: Uri): Bitmap {
        val degrees = runCatching {
            app.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val edge = maxOf(bitmap.width, bitmap.height)
        if (edge <= MAX_EDGE) return bitmap
        val ratio = MAX_EDGE.toFloat() / edge
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }

    /** A place the camera can write a full-size photo before it is imported. */
    fun cameraTarget(): File = File(app.cacheDir, "camera").apply { mkdirs() }.let { File(it, "capture.jpg") }

    private companion object {
        /** Plenty for signs and menus; the encoder resizes to far smaller. */
        const val MAX_EDGE = 1024
    }
}
