package com.nmc.stocktake.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nmc.stocktake.core.CountExpression
import com.nmc.stocktake.core.StockSheet
import com.nmc.stocktake.data.SessionStore
import com.nmc.stocktake.data.WebLink
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The list being worked through, where we are in it, and every change saved
 * to the phone as it happens.
 *
 * StockSheet (in core/) is plain Java so its rules - sums, blank-not-zero,
 * what goes in the export - are tested without a phone. `rev` goes up on
 * every change so the screen knows to redraw.
 */
class StockTakeViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val sheet: StockSheet? = null,
        val index: Int = 0,
        val source: String = "",
        val rev: Long = 0,
        val message: String? = null,
        val linked: Boolean = false,          // to the web app
        val webOpen: WebLink.Open? = null,    // what is open to count there
        val busy: String? = null,             // "Sending...", while the web app is asked
        val deliveries: List<WebLink.Delivery> = emptyList(),  // open on the web app
        val webDelivery: String = "",         // the delivery this receiving list came from
        val ourItems: List<StockSheet.OurItem> = emptyList(),  // our medication list, for invoice names not ours
    ) {
        val item: StockSheet.Item? get() = sheet?.items?.getOrNull(index)
    }

    private val store = SessionStore(app)
    private val web = WebLink(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        store.load()?.let { s -> _state.value = UiState(s.sheet, s.index, s.source, webDelivery = s.webDelivery) }
        _state.value = _state.value.copy(linked = web.isLinked)
        if (web.isLinked && _state.value.sheet == null) refreshWeb()
    }

    private fun changed(index: Int = _state.value.index, message: String? = null) {
        val s = _state.value
        val sheet = s.sheet ?: return
        _state.value = s.copy(index = index, rev = s.rev + 1, message = message)
        store.save(sheet, index, s.source, s.webDelivery)
    }

    fun say(message: String?) {
        _state.value = _state.value.copy(message = message)
    }

    // ---- Starting --------------------------------------------------------

    fun import(uri: Uri, mode: StockSheet.Mode) {
        val ctx = getApplication<Application>()
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalArgumentException("The file could not be opened")
            if (text.startsWith("PK")) {
                throw IllegalArgumentException("That is an Excel file. On the web app's Stock Take page use the CSV link instead.")
            }
            load(text, mode, fileName(uri) ?: "sheet.csv")
        } catch (e: IllegalArgumentException) {
            say(e.message)
        } catch (e: Exception) {
            say("The file could not be read: ${e.message}")
        }
    }

    private fun load(text: String, mode: StockSheet.Mode, name: String, webDelivery: String = "") {
        val sheet = StockSheet.fromCsv(text, mode)
        _state.value = _state.value.copy(sheet = sheet, index = 0, source = name, webDelivery = webDelivery,
            rev = _state.value.rev + 1, message = "${sheet.items.size} ${if (mode == StockSheet.Mode.RECEIVING) "lines" else "items"} read from $name")
        store.save(sheet, 0, name, webDelivery)
    }

    private fun fileName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col >= 0 && c.moveToFirst()) c.getString(col) else null
        }

    fun clear() {
        store.clear()
        _state.value = UiState(rev = _state.value.rev + 1, linked = web.isLinked)
        if (web.isLinked) refreshWeb()
    }

    // ---- Moving about ----------------------------------------------------

    fun goTo(i: Int) {
        val n = _state.value.sheet?.items?.size ?: return
        if (i in 0 until n) changed(i)
    }

    fun next() = goTo(_state.value.index + 1)
    fun previous() = goTo(_state.value.index - 1)

    fun nextOpen() {
        val s = _state.value
        val i = s.sheet?.nextOpen(s.index) ?: return
        if (i < 0) say("Everything on the list is done") else changed(i)
    }

    // ---- Recording -------------------------------------------------------

    /**
     * Save what is on the screen for the current item. Returns a problem to
     * show, or null when saved. A blank count is allowed: it means "not
     * counted" and is exported blank, so the web app leaves the item alone.
     */
    fun saveCount(countText: String, expiry: String, batch: String, location: String, note: String,
                  thenNext: Boolean): String? {
        val s = _state.value
        val it = s.item ?: return null
        val counted = try {
            CountExpression.evaluate(countText, it.packUnits())
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        it.countText = countText.trim()
        it.counted = counted
        if (counted != null) it.countedBy = ""   // counted here too: the web compares the two
        it.expiry = expiry.trim(); it.batch = batch.trim(); it.note = note.trim()
        it.location = location.trim().uppercase()
        if (thenNext) moveOn(s) else changed()
        return null
    }

    /** The shelf agrees with the system: the count is the system figure. */
    fun matchesSystem(expiry: String, batch: String, location: String, note: String): String? {
        val s = _state.value
        val it = s.item ?: return null
        val sys = it.onSystem ?: return "This sheet has no system figure for the item - type the count"
        it.countText = sys.toString()
        it.counted = sys
        it.countedBy = ""
        it.expiry = expiry.trim(); it.batch = batch.trim(); it.note = note.trim()
        it.location = location.trim().uppercase()
        moveOn(s)
        return null
    }

    /** Receiving: this line is checked against the box. */
    fun saveReceived(qty: String, expiry: String, batch: String, note: String, checked: Boolean): String? {
        val s = _state.value
        val it = s.item ?: return null
        if (checked && it.needsOurItem()) {
            return "The invoice calls this \"${it.name}\", which is not one of our items - tap Pick our item first"
        }
        // Packs work here too: "2p" with packs of 28 is 56 units.
        val units = try {
            CountExpression.evaluate(qty, it.packUnits())
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        it.qty = units?.toString() ?: ""
        it.expiry = expiry.trim(); it.batch = batch.trim(); it.note = note.trim()
        it.done = checked
        if (checked) it.countedBy = ""
        if (checked) moveOn(s) else changed()
        return null
    }

    // ---- Barcodes ---------------------------------------------------------

    /** The item with this barcode, or -1. */
    fun findBarcode(code: String): Int = _state.value.sheet?.findBarcode(code) ?: -1

    /** Put a scanned barcode on an item, so the next scan finds it. */
    fun linkBarcode(index: Int, code: String): String? {
        val sheet = _state.value.sheet ?: return null
        val clean = StockSheet.cleanBarcode(code)
        if (clean.isEmpty()) return "That barcode could not be read"
        val other = sheet.findBarcode(clean)
        if (other >= 0 && other != index) return "That barcode is already on ${sheet.items[other].name}"
        sheet.items.getOrNull(index)?.barcode = clean
        changed(index, "Barcode saved - it goes to the web app with the counts")
        return null
    }

    // ---- The web app -----------------------------------------------------

    fun linkWeb(code: String): String? {
        val problem = web.link(code)
        if (problem != null) return problem
        _state.value = _state.value.copy(linked = true, message = "Linked to ${web.address}")
        refreshWeb()
        return null
    }

    fun unlinkWeb() {
        web.unlink()
        _state.value = _state.value.copy(linked = false, webOpen = null, message = "Unlinked from the web app")
    }

    fun refreshWeb() {
        if (!web.isLinked) return
        ask("Asking the web app...") {
            val open = web.hello()
            val dels = web.deliveries()
            _state.value = _state.value.copy(webOpen = open, deliveries = dels)
        }
    }

    fun getSheetFromWeb(group: String, label: String) {
        val ref = _state.value.webOpen?.reference ?: "stock-take"
        ask("Getting the sheet...") {
            val text = web.sheet(group)
            load(text, StockSheet.Mode.STOCK_TAKE, "$ref-${label.lowercase().replace(' ', '-')}.csv")
        }
    }

    fun getDeliveryFromWeb(d: WebLink.Delivery) {
        ask("Getting the invoice lines...") {
            val text = web.deliveryLines(d.id)
            load(text, StockSheet.Mode.RECEIVING, "${d.reference}.csv", d.id)
            val sheet = _state.value.sheet
            if (sheet != null && sheet.items.any { it.needsOurItem() || it.guessed() }) {
                try {
                    _state.value = _state.value.copy(ourItems = StockSheet.ourItems(web.medications()))
                } catch (e: Exception) { /* fetched again when Pick our item is tapped */ }
            }
        }
    }

    /** Get our medication list from the web app, for Pick our item. */
    fun loadOurItems() {
        if (!web.isLinked || _state.value.ourItems.isNotEmpty()) return
        ask("Getting our medication list...") {
            val list = StockSheet.ourItems(web.medications())
            _state.value = _state.value.copy(ourItems = list)
        }
    }

    /** Put the line on screen on our item: the invoice named it its own way. */
    fun putOnOurItem(o: StockSheet.OurItem) {
        val s = _state.value
        val it = s.item ?: return
        StockSheet.putOnOurItem(it, o.code, o.name)
        if (it.packSize.isBlank() || it.packSize == "1") {
            if (o.packSize.isNotBlank()) it.packSize = o.packSize
        }
        if (it.barcode.isBlank() && o.barcode.isNotBlank()) { it.barcode = o.barcode; it.fileBarcode = o.barcode }
        changed(message = "Put on ${o.name}. The web app remembers the invoice's name for next time once the line is added.")
    }

    fun sendToWeb() {
        val s = _state.value
        val sheet = s.sheet ?: return
        if (sheet.mode == StockSheet.Mode.RECEIVING) {
            if (s.webDelivery.isBlank()) { say("This list did not come from the web app - save it as a file instead"); return }
            if (sheet.items.none { it.done }) { say("No line is checked yet"); return }
            ask("Sending...") { say(web.sendDelivery(s.webDelivery, sheet.toCsv())) }
            return
        }
        if (sheet.items.none { it.touched() }) { say("Nothing counted yet to send"); return }
        ask("Sending...") {
            say(web.send(sheet.toCsv()))
        }
    }

    private fun ask(busy: String, work: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = busy)
        viewModelScope.launch {
            try {
                work()
            } catch (e: WebLink.Refused) {
                say(e.message)
            } catch (e: IllegalArgumentException) {
                say(e.message)
            } catch (e: Exception) {
                say("Something went wrong: ${e.message}")
            } finally {
                _state.value = _state.value.copy(busy = null)
            }
        }
    }

    /** After saving: the next item no other phone has done. */
    private fun moveOn(s: UiState) {
        val next = s.sheet?.nextFree(s.index) ?: -1
        val skipped = if (next > s.index + 1) next - s.index - 1 else 0
        if (next < 0) {
            changed(s.index, "That was the last item - send or export when ready")
        } else {
            changed(next, if (skipped > 0) "Passed over $skipped item(s) another phone has done" else null)
        }
    }

    // ---- Exporting -------------------------------------------------------

    fun exportName(): String {
        val s = _state.value
        val base = s.source.substringBeforeLast('.').ifBlank { "stock-take" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        val what = if (s.sheet?.mode == StockSheet.Mode.RECEIVING) "received" else "counted"
        return "$base-$what-$stamp.csv"
    }

    fun exportTo(uri: Uri) {
        val sheet = _state.value.sheet ?: return
        try {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                it.write(sheet.toCsv().toByteArray(Charsets.UTF_8))
            }
            say("Saved. Upload it on the web app's ${if (sheet.mode == StockSheet.Mode.RECEIVING) "delivery page" else "Stock Take page (Read Spreadsheet)"}.")
        } catch (e: Exception) {
            say("Could not save: ${e.message}")
        }
    }

    /** A share sheet (WhatsApp, email, Drive...) with the export attached. */
    fun shareIntent(): Intent? {
        val sheet = _state.value.sheet ?: return null
        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, exportName())
        file.writeText(sheet.toCsv(), Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Send the sheet")
    }
}
