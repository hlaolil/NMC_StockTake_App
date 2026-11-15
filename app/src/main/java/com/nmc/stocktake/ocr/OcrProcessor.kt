package com.nmc.stocktake.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

object OcrProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractBatchAndExpiry(bitmap: Bitmap): Pair<String, String> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            val text = result.text.uppercase()

            val batch = extractBatch(text)
            val expiry = extractExpiry(text)

            batch to expiry
        } catch (e: Exception) {
            "" to ""
        }
    }

    private fun extractBatch(text: String): String {
        val patterns = listOf(
            Regex("""(?:BATCH|LOT|B/N|BN|BNO)[:\s]*([A-Z0-9\-]+)"""),
            Regex("""LOT[:\s]*([A-Z0-9]+)""")
        )
        for (p in patterns) {
            p.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return ""
    }

    private fun extractExpiry(text: String): String {
        val patterns = listOf(
            Regex("""(?:EXP|EXPIRY|EXP\.|E\.D|VERV)[:\s]*(\d{1,2}[/\-]\d{2,4}|\d{1,2}/\d{2})"""),
            Regex("""(\d{1,2}/\d{2,4})""")
        )
        for (p in patterns) {
            p.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return ""
    }
}
