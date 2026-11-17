package com.nmc.stocktake.ocr

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nmc.stocktake.core.LabelReader

/**
 * Reads the text on a label photo with ML Kit (on the phone, no signal
 * needed), then hands it to LabelReader to find the batch and expiry.
 */
object OcrProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class LabelResult(val batch: String, val expiry: String, val rawText: String)

    fun read(image: InputImage, onDone: (LabelResult) -> Unit, onFail: (String) -> Unit, onFinally: () -> Unit) {
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text
                onDone(LabelResult(LabelReader.batch(text), LabelReader.expiry(text), text))
            }
            .addOnFailureListener { e -> onFail(e.message ?: "The label could not be read") }
            .addOnCompleteListener { onFinally() }
    }
}
