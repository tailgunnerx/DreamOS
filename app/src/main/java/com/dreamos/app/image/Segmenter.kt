package com.dreamos.app.image

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One-tap "isolate the subject" using ML Kit Subject Segmentation.
 * Inference runs fully on-device; the image is never uploaded. (Google Play services may
 * download the model itself the first time it's used.)
 */
object Segmenter {

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    /** Returns an ARGB mask (alpha = subject confidence) the same size as [bitmap]. */
    suspend fun segment(context: Context, bitmap: Bitmap): Bitmap {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
        )
        try {
            val modules = ModuleInstall.getClient(context)
            val available = runCatching { modules.areModulesAvailable(segmenter).awaitTask().areModulesAvailable() }.getOrDefault(true)
            if (!available) {
                modules.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).awaitTask()
            }
            val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).awaitTask()
            val buf = result.foregroundConfidenceMask ?: error("No subject found")
            buf.rewind()
            val w = bitmap.width
            val h = bitmap.height
            val px = IntArray(w * h)
            for (i in px.indices) {
                val a = (buf.get() * 255f).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or 0x00FFFFFF
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } finally {
            segmenter.close()
        }
    }
}
