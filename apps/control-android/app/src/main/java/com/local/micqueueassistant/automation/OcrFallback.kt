package com.local.micqueueassistant.automation

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrLine(
    val text: String,
    val top: Int,
    val bottom: Int,
    val imageHeight: Int,
) {
    val centerFraction: Float
        get() = (top + bottom) / 2f / imageHeight.coerceAtLeast(1)
}

object OcrFallback {
    suspend fun recognize(service: AccessibilityService): List<OcrLine> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val bitmap = takeScreenshot(service) ?: return emptyList()
        return try {
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            result.textBlocks.flatMap(Text.TextBlock::getLines).mapNotNull { line ->
                line.boundingBox?.let { OcrLine(line.text, it.top, it.bottom, bitmap.height) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executor(Runnable::run),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val buffer = result.hardwareBuffer
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        hardware?.recycle()
                        buffer.close()
                        continuation.resume(copy)
                    }

                    override fun onFailure(errorCode: Int) {
                        continuation.resume(null)
                    }
                },
            )
        }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }
}
