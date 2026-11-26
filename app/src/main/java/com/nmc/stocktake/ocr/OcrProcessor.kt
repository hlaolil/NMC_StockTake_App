package com.nmc.stocktake.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    /** Read at most this many pages of a PDF: an invoice, not a catalogue. */
    private const val PDF_PAGES = 10

    /**
     * A PDF invoice - a paper invoice scanned on a printer, or one the
     * supplier sent: each page is drawn as a picture and read like a photo,
     * on the phone. Throws IllegalArgumentException with a plain reason.
     */
    suspend fun readInvoicePdf(context: Context, uri: Uri): List<InvoiceReader.Line> {
        val pages = withContext(Dispatchers.IO) {
            val fd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) { null } ?: throw IllegalArgumentException("The PDF could not be opened")
            fd.use { pfd ->
                val renderer = try { PdfRenderer(pfd) } catch (e: Exception) {
                    throw IllegalArgumentException("That file is not a PDF the phone can read (is it password-protected?)")
                }
                renderer.use { r ->
                    (0 until minOf(r.pageCount, PDF_PAGES)).map { i ->
                        r.openPage(i).use { page ->
                            // About 200 dots per inch: small print stays readable, the picture stays small.
                            val scale = minOf(2.8f, 2400f / maxOf(page.width, page.height))
                            val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)          // a page is drawn on clear, not on paper
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            }
        }
        if (pages.isEmpty()) throw IllegalArgumentException("The PDF has no pages")
        val out = ArrayList<InvoiceReader.Line>()
        for (bmp in pages) {
            out += readInvoicePage(InputImage.fromBitmap(bmp, 0))
            bmp.recycle()
        }
        return out
    }

    private suspend fun readInvoicePage(image: InputImage): List<InvoiceReader.Line> =
        suspendCancellableCoroutine { cont ->
            readInvoice(image, { cont.resume(it) },
                { cont.resumeWithException(IllegalArgumentException(it)) }, onFinally = {})
        }
}
