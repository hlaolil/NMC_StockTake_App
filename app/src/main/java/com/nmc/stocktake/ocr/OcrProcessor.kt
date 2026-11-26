package com.nmc.stocktake.ocr

import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.nmc.stocktake.core.Gs1
import com.nmc.stocktake.core.InvoiceReader
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nmc.stocktake.core.LabelReader

/**
 * Reads the text on a label photo with ML Kit (on the phone, no signal
 * needed), then hands it to LabelReader to find the batch and expiry.
 *
 * The same photo is also searched for the square 2D code many boxes carry:
 * when one is there, its batch and expiry are exact and are taken over the
 * print, and its product number comes back as the box's barcode.
 */
object OcrProcessor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val codes = BarcodeScanning.getClient(BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_QR_CODE, Barcode.FORMAT_CODE_128).build())

    /** fromCode: the batch and expiry came from the 2D code, not the print. */
    data class LabelResult(val batch: String, val expiry: String, val rawText: String,
                           val barcode: String = "", val fromCode: Boolean = false)

    fun read(image: InputImage, onDone: (LabelResult) -> Unit, onFail: (String) -> Unit, onFinally: () -> Unit) {
        codes.process(image)
            .addOnCompleteListener { found ->
                val gs1 = if (found.isSuccessful)
                    found.result.firstNotNullOfOrNull { b -> b.rawValue?.let { Gs1.parse(it) } } else null
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val text = result.text
                        onDone(LabelResult(
                            batch = gs1?.batch?.takeIf { it.isNotBlank() } ?: LabelReader.batch(text),
                            expiry = gs1?.expiry?.takeIf { it.isNotBlank() } ?: LabelReader.expiry(text),
                            rawText = text,
                            barcode = gs1?.gtin ?: "",
                            fromCode = gs1 != null && (gs1.batch.isNotBlank() || gs1.expiry.isNotBlank())))
                    }
                    .addOnFailureListener { e ->
                        if (gs1 != null) onDone(LabelResult(gs1.batch, gs1.expiry, "", gs1.gtin, true))
                        else onFail(e.message ?: "The label could not be read")
                    }
                    .addOnCompleteListener { onFinally() }
            }
    }

    /** A photo of a paper invoice: its text, row by row, read into invoice lines. */
    fun readInvoice(image: InputImage, onDone: (List<InvoiceReader.Line>) -> Unit, onFail: (String) -> Unit,
                    onFinally: () -> Unit) {
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val pieces = ArrayList<InvoiceReader.Piece>()
                for (block in result.textBlocks) for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    pieces.add(InvoiceReader.Piece(line.text, box.left.toFloat(), box.top.toFloat(),
                        box.right.toFloat(), box.bottom.toFloat()))
                }
                onDone(InvoiceReader.read(pieces))
            }
            .addOnFailureListener { e -> onFail(e.message ?: "The invoice could not be read") }
            .addOnCompleteListener { onFinally() }
    }
}
