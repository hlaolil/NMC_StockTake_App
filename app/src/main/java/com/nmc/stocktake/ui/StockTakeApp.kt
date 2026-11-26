package com.nmc.stocktake.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nmc.stocktake.core.CountExpression
import com.nmc.stocktake.core.Expiry
import com.nmc.stocktake.core.Gs1
import com.nmc.stocktake.core.InvoiceReader
import com.nmc.stocktake.core.StockSheet
import com.nmc.stocktake.data.WebLink
import com.nmc.stocktake.ocr.OcrProcessor
import java.time.LocalDate

/** What is typed for the item on screen, kept while the camera is open. */
private data class Draft(
    val count: String = "", val expiry: String = "", val batch: String = "",
    val location: String = "", val note: String = "", val qty: String = "",
) {
    companion object {
        fun of(item: StockSheet.Item?) = if (item == null) Draft() else
            Draft(item.countText, item.expiry, item.batch, item.location, item.note, item.qty)
    }
}

private enum class CameraFor { LABEL, MATCH, BARCODE, LINK }

@Composable
fun StockTakeApp(vm: StockTakeViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    var camera by remember { mutableStateOf<CameraFor?>(null) }
    var showList by remember { mutableStateOf(false) }
    var pickOurs by remember { mutableStateOf(false) }   // putting an invoice line on our item
    var invoiceCamera by remember { mutableStateOf(false) }   // photographing a paper invoice
    var lastLabel by remember { mutableStateOf<OcrProcessor.LabelResult?>(null) }
    var confirmMatch by remember { mutableStateOf(false) }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }   // scanned, on no item yet
    var linkingBarcode by remember { mutableStateOf<String?>(null) }   // choosing its item from the list
    var scanFill by remember { mutableStateOf<Gs1?>(null) }            // a 2D code's batch and expiry, to fill in
    var boxOfOurs by remember { mutableStateOf<StockSheet.OurItem?>(null) }  // scanned: ours, on no line yet
    var draft by remember(state.index, state.sheet) { mutableStateOf(Draft.of(state.item)) }
    LaunchedEffect(state.index, state.sheet) { lastLabel = null }
    // After a scan has moved to its item: the 2D code's batch and expiry go in the boxes to check.
    LaunchedEffect(state.index, state.sheet, scanFill) {
        scanFill?.let { g ->
            draft = draft.copy(batch = g.batch.ifBlank { draft.batch }, expiry = g.expiry.ifBlank { draft.expiry })
            if (g.batch.isNotBlank() || g.expiry.isNotBlank()) vm.say("Batch and expiry read from the 2D code - check them against the box")
            scanFill = null
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.say(null) }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? -> uri?.let { vm.exportTo(it) } }

    fun share() {
        val intent = vm.shareIntent() ?: return
        try { context.startActivity(intent) } catch (e: ActivityNotFoundException) { vm.say("No app to share with") }
    }

    // ---- Camera screens: label photo, barcode, link QR -------------------
    camera?.let { purpose ->
        BackHandler { camera = null }
        when (purpose) {
            CameraFor.LABEL, CameraFor.MATCH -> CameraScreen(
                itemName = state.item?.name ?: "",
                onResult = { r ->
                    // Fill only what was found; keep anything already typed otherwise.
                    draft = draft.copy(
                        batch = r.batch.ifBlank { draft.batch },
                        expiry = r.expiry.ifBlank { draft.expiry },
                    )
                    lastLabel = r
                    // The photo had the box's 2D code: its product number becomes the
                    // item's barcode, so the next box is found by scanning.
                    val cur = state.item
                    if (r.barcode.isNotBlank() && cur != null && cur.barcode.isBlank()
                        && vm.findBarcode(r.barcode) < 0 && !cur.needsOurItem()) {
                        vm.linkBarcode(state.index, r.barcode)
                    }
                    camera = null
                    if (purpose == CameraFor.MATCH) confirmMatch = true
                },
                onCancel = { camera = null },
            )
            CameraFor.BARCODE -> ScanScreen(
                title = "Scan the barcode on the box",
                hint = "It finds the item. A barcode not known yet can be put on an item once.",
                qrOnly = false,
                onCode = { code ->
                    camera = null
                    val g = Gs1.parse(code)
                    val i = vm.findBarcode(code)
                    val ours = if (i < 0 && state.sheet?.mode == StockSheet.Mode.RECEIVING) vm.ourItemFor(code) else null
                    when {
                        i >= 0 -> { vm.goTo(i); scanFill = g }
                        ours != null -> {
                            // One of our items, on no line by its barcode: its line, or a line
                            // the invoice named its own way.
                            val line = state.sheet?.findName(ours.name) ?: -1
                            if (line >= 0) { vm.goTo(line); scanFill = g }
                            else { boxOfOurs = ours; scanFill = g }
                        }
                        else -> { unknownBarcode = g?.gtin ?: code; scanFill = g }
                    }
                },
                onCancel = { camera = null },
            )
            CameraFor.LINK -> ScanScreen(
                title = "Scan the code on the web app",
                hint = "Web app > Stock Take > link the phone app > Show Link Code",
                qrOnly = true,
                onCode = { code -> camera = null; vm.linkWeb(code)?.let(vm::say) },
                onCancel = { camera = null },
            )
        }
        return
    }

    // ---- A paper invoice: photograph, check, send ------------------------
    state.invoiceFor?.let { d ->
        if (invoiceCamera) {
            BackHandler { invoiceCamera = false }
            InvoiceCameraScreen("Invoice for ${d.reference}",
                onLines = { vm.addInvoiceLines(it); invoiceCamera = false },
                onCancel = { invoiceCamera = false })
        } else {
            BackHandler { vm.cancelInvoice() }
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                InvoiceReview(Modifier.padding(pad), d, state,
                    onPhoto = { invoiceCamera = true },
                    onPdf = { uri -> vm.readInvoicePdf(uri) },
                    onEdit = vm::editInvoiceLine, onRemove = vm::removeInvoiceLine,
                    onUnits = vm::setInvoiceUnits,
                    onSend = { vm.sendInvoice() }, onCancel = { vm.cancelInvoice() })
            }
            state.invoiceAskReplace?.let { why ->
                AlertDialog(
                    onDismissRequest = { vm.keepWaitingLines() },
                    title = { Text("Replace the waiting lines?") },
                    text = { Text("$why\nReplacing drops what phones already checked on them.") },
                    confirmButton = { Button(onClick = { vm.sendInvoice(replace = true) }) { Text("Replace") } },
                    dismissButton = { TextButton(onClick = { vm.keepWaitingLines() }) { Text("Keep them") } },
                )
            }
        }
        return
    }

    val sheet = state.sheet
    if (sheet == null) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            StartScreen(Modifier.padding(pad), state,
                onImport = { uri, mode -> vm.import(uri, mode) },
                onScanLink = { camera = CameraFor.LINK },
                onPasteLink = { code -> vm.linkWeb(code) },
                onGetSheet = { g, label -> vm.getSheetFromWeb(g, label) },
                onGetDelivery = { d -> vm.getDeliveryFromWeb(d) },
                onPhotoInvoice = { d -> vm.startInvoice(d) },
                onRefresh = { vm.refreshWeb() },
                onUnlink = { vm.unlinkWeb() })
        }
        return
    }

    if (pickOurs) {
        BackHandler { pickOurs = false }
        OurItemPicker(state.item, state.ourItems, state.busy,
            onPick = { o -> vm.putOnOurItem(o); pickOurs = false },
            onRetry = { vm.loadOurItems() },
            onClose = { pickOurs = false })
        return
    }

    if (showList) {
        BackHandler { showList = false; linkingBarcode = null }
        ItemList(sheet, state.rev, linkingBarcode,
            onPick = { i ->
                linkingBarcode?.let { code -> vm.linkBarcode(i, code)?.let(vm::say) }
                linkingBarcode = null
                vm.goTo(i); showList = false
            },
            onClose = { showList = false; linkingBarcode = null })
        return
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        val item = state.item ?: return@Scaffold
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(sheet, state.index, state.rev, onScan = { camera = CameraFor.BARCODE })
            ItemCard(sheet, item)
            if (sheet.mode == StockSheet.Mode.RECEIVING) {
                InvoiceName(item, canPick = state.webDelivery.isNotBlank() && state.linked,
                    onPick = { vm.loadOurItems(); pickOurs = true })
            }
            ExpiryWarning(draft.expiry)
            lastLabel?.let { LabelNote(it) }

            if (sheet.mode == StockSheet.Mode.STOCK_TAKE) {
                CountFields(
                    draft = draft, onDraft = { draft = it }, item = item,
                    onSave = { next ->
                        vm.saveCount(draft.count, draft.expiry, draft.batch, draft.location, draft.note, next)
                            ?.let(vm::say)
                    },
                    onMatch = { vm.matchesSystem(draft.expiry, draft.batch, draft.location, draft.note)?.let(vm::say) },
                    onMatchWithLabel = { camera = CameraFor.MATCH },
                    onLabel = { camera = CameraFor.LABEL },
                )
            } else {
                ReceiveFields(
                    draft = draft, onDraft = { draft = it }, item = item,
                    onSave = { checked -> vm.saveReceived(draft.qty, draft.expiry, draft.batch, draft.note, checked)?.let(vm::say) },
                    onMatch = { vm.matchesInvoice(draft.expiry, draft.batch, draft.note)?.let(vm::say) },
                    onLabel = { camera = CameraFor.LABEL },
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { vm.previous() }, enabled = state.index > 0) { Text("< Previous") }
                TextButton(onClick = { vm.nextOpen() }) {
                    Text(if (sheet.mode == StockSheet.Mode.STOCK_TAKE) "Next not counted" else "Next not checked")
                }
                TextButton(onClick = { vm.next() }, enabled = state.index < sheet.items.size - 1) { Text("Skip >") }
            }
            OutlinedButton(onClick = { showList = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Find an item / see the list")
            }
            HorizontalDivider()
            ExportArea(sheet, state.rev,
                state.linked && (sheet.mode == StockSheet.Mode.STOCK_TAKE || state.webDelivery.isNotBlank()), state.busy,
                onSend = { vm.sendToWeb() },
                onSave = { saveLauncher.launch(vm.exportName()) },
                onShare = { share() },
                onClear = { vm.clear() })
        }
    }

    if (confirmMatch) {
        val item = state.item
        AlertDialog(
            onDismissRequest = { confirmMatch = false },
            title = { Text("Matches the system?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${item?.name}\nCount: ${item?.onSystem ?: "-"} (as on the system)")
                    OutlinedTextField(draft.expiry, { draft = draft.copy(expiry = it) },
                        label = { Text("Expiry") }, singleLine = true)
                    OutlinedTextField(draft.batch, { draft = draft.copy(batch = it) },
                        label = { Text("Batch") }, singleLine = true)
                    if (lastLabel?.batch.isNullOrBlank() || lastLabel?.expiry.isNullOrBlank())
                        Text("Not everything could be read - check against the box.",
                            color = MaterialTheme.colorScheme.tertiary)
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmMatch = false
                    vm.matchesSystem(draft.expiry, draft.batch, draft.location, draft.note)?.let(vm::say)
                }) { Text("Save & Next") }
            },
            dismissButton = { TextButton(onClick = { confirmMatch = false }) { Text("Back") } },
        )
    }

    boxOfOurs?.let { o ->
        val lines = remember(state.rev) { state.sheet?.linesNotOurs() ?: emptyList() }
        AlertDialog(
            onDismissRequest = { boxOfOurs = null; scanFill = null },
            title = { Text("Which invoice line is this?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("The box is our ${o.name}. No line has that name yet.")
                    if (lines.isEmpty()) Text("Every line is already on one of our items - it may not be on this invoice.")
                    for (i in lines) {
                        val it = state.sheet?.items?.getOrNull(i) ?: continue
                        OutlinedButton(onClick = { boxOfOurs = null; vm.putOnOurItem(i, o) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${it.invoiceName.ifBlank { it.name }}${if (it.invoiceQty.isNotBlank()) " (${it.invoiceQty})" else ""}",
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { boxOfOurs = null; scanFill = null }) { Text("None of these") } },
        )
    }

    unknownBarcode?.let { code ->
        AlertDialog(
            onDismissRequest = { unknownBarcode = null },
            title = { Text("New barcode") },
            text = {
                Text("$code is not on any item yet. Put it on the item it belongs to, and from then on " +
                    "scanning this box finds it.")
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    state.item?.let { cur ->
                        Button(onClick = {
                            unknownBarcode = null
                            vm.linkBarcode(state.index, code)?.let(vm::say)
                        }) { Text("It is ${cur.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                    TextButton(onClick = {
                        unknownBarcode = null
                        linkingBarcode = code
                        showList = true
                    }) { Text("Choose the item from the list") }
                }
            },
            dismissButton = { TextButton(onClick = { unknownBarcode = null }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun StartScreen(
    modifier: Modifier, state: StockTakeViewModel.UiState,
    onImport: (Uri, StockSheet.Mode) -> Unit,
    onScanLink: () -> Unit, onPasteLink: (String) -> String?,
    onGetSheet: (String, String) -> Unit, onGetDelivery: (WebLink.Delivery) -> Unit,
    onPhotoInvoice: (WebLink.Delivery) -> Unit,
    onRefresh: () -> Unit, onUnlink: () -> Unit,
) {
    var mode by remember { mutableStateOf(StockSheet.Mode.STOCK_TAKE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { onImport(it, mode) }
    }
    val types = arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "*/*")
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("NMC Pharmacy", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        state.busy?.let { Busy(it) }

        if (state.linked) {
            WebStart(state.webOpen, state.busy != null, onGetSheet, onRefresh, onUnlink)
            WebDeliveries(state.deliveries, state.busy != null, onGetDelivery, onPhotoInvoice)
        } else {
            LinkCard(onScanLink, onPasteLink)
        }

        HorizontalDivider()
        Text("Stock take from a file", style = MaterialTheme.typography.titleMedium)
        Text("On the web app's Stock Take page, tap a CSV link to download the count sheet, then open it here. " +
            "When done, export and upload it there with Read Spreadsheet.")
        OutlinedButton(onClick = { mode = StockSheet.Mode.STOCK_TAKE; picker.launch(types) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open Count Sheet (CSV)")
        }
        HorizontalDivider()
        Text("Receiving", style = MaterialTheme.typography.titleMedium)
        Text("Open a list of what arrived (Medication and Quantity columns, e.g. the invoice template saved as CSV). " +
            "Read the batch and expiry off each box, then export it for the delivery's upload.")
        OutlinedButton(onClick = { mode = StockSheet.Mode.RECEIVING; picker.launch(types) }, modifier = Modifier.fillMaxWidth()) {
            Text("Open Receiving List (CSV)")
        }
    }
}

@Composable
private fun LinkCard(onScanLink: () -> Unit, onPasteLink: (String) -> String?) {
    var paste by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Link to the web app", style = MaterialTheme.typography.titleMedium)
            Text("Once linked, the phone gets the count sheet and sends the counts by itself - no files. " +
                "On the web app: Stock Take > link the phone app > Show Link Code.")
            Button(onClick = onScanLink, modifier = Modifier.fillMaxWidth()) { Text("Scan the Link Code") }
            if (!paste) {
                TextButton(onClick = { paste = true }) { Text("No camera? Paste the code instead") }
            } else {
                OutlinedTextField(code, { code = it; problem = null }, label = { Text("Link code") },
                    isError = problem != null, supportingText = { problem?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { problem = onPasteLink(code) }, enabled = code.isNotBlank()) { Text("Link") }
            }
        }
    }
}

@Composable
private fun WebStart(
    open: WebLink.Open?, busy: Boolean,
    onGetSheet: (String, String) -> Unit, onRefresh: () -> Unit, onUnlink: () -> Unit,
) {
    var askUnlink by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stock take", style = MaterialTheme.typography.titleMedium)
            if (open == null) {
                Text(if (busy) "Asking the web app what is open..." else
                    "No stock take is open on the web app. Open one there (Stock Take page), then tap Refresh.")
            } else {
                Text("${open.reference}: ${open.groups}. ${open.toCount} item(s) still to count.")
                Text("Get a sheet to count:", fontWeight = FontWeight.Bold)
                for (g in open.byGroup) {
                    Button(onClick = { onGetSheet(g.group, g.label) }, enabled = !busy && g.toCount > 0,
                        modifier = Modifier.fillMaxWidth()) { Text("${g.label} (${g.toCount})") }
                }
                if (open.byGroup.size > 1) {
                    OutlinedButton(onClick = { onGetSheet("", "everything") }, enabled = !busy && open.toCount > 0,
                        modifier = Modifier.fillMaxWidth()) { Text("Everything (${open.toCount})") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
                TextButton(onClick = { askUnlink = true }) { Text("Unlink this phone") }
            }
        }
    }
    if (askUnlink) {
        AlertDialog(
            onDismissRequest = { askUnlink = false },
            title = { Text("Unlink from the web app?") },
            text = { Text("The phone will need the link code again to get sheets or send counts.") },
            confirmButton = { Button(onClick = { askUnlink = false; onUnlink() }) { Text("Unlink") } },
            dismissButton = { TextButton(onClick = { askUnlink = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun WebDeliveries(deliveries: List<WebLink.Delivery>, busy: Boolean, onGet: (WebLink.Delivery) -> Unit,
                          onPhoto: (WebLink.Delivery) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Receiving", style = MaterialTheme.typography.titleMedium)
            if (deliveries.isEmpty()) {
                Text("No delivery is open on the web app. Open one on the Receive page, then tap Refresh.")
            }
            for (d in deliveries) {
                val what = listOf(d.supplier, d.invoices.takeIf { it.isNotBlank() }?.let { "invoice $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
                if (d.lines > 0) {
                    Button(onClick = { onGet(d) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("${d.reference}${if (what.isNotEmpty()) " - $what" else ""}: ${d.lines} line(s)" +
                            if (d.checked > 0) ", ${d.checked} checked" else "")
                    }
                    TextButton(onClick = { onPhoto(d) }, enabled = !busy) { Text("Read the invoice again (paper or PDF)") }
                } else {
                    Text("${d.reference}${if (what.isNotEmpty()) " - $what" else ""}: no invoice lines yet.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onPhoto(d) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Paper or PDF invoice: read it on the phone")
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Header(sheet: StockSheet, index: Int, rev: Long, onScan: () -> Unit) {
    val done = remember(rev) { sheet.countDone() }
    val total = sheet.items.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(if (sheet.mode == StockSheet.Mode.STOCK_TAKE) "Stock take" else "Receiving",
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("Item ${index + 1} of $total", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = onScan) { Text("Scan Barcode") }
    }
    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
    Text("$done of $total ${if (sheet.mode == StockSheet.Mode.STOCK_TAKE) "counted" else "checked"}",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ItemCard(sheet: StockSheet, item: StockSheet.Item) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.location.isNotBlank()) {
                Text("Shelf ${item.location}", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text(item.name, style = MaterialTheme.typography.headlineSmall)
            if (item.countedBy.isNotBlank()) {
                Text(if (sheet.mode == StockSheet.Mode.STOCK_TAKE)
                        "Already counted on ${item.countedBy}. Count it again only to check - the web app compares the two."
                    else "Already checked on ${item.countedBy}. Check it again only if needed - the web app compares the two.",
                    Modifier.fillMaxWidth().background(Color(0xFFE0ECFF)).padding(8.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
            val sub = listOf(item.code, item.group).filter { it.isNotBlank() }.joinToString("  ·  ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
            if (sheet.mode == StockSheet.Mode.STOCK_TAKE) {
                Text("On system: ${item.onSystem ?: "-"}", style = MaterialTheme.typography.titleMedium)
                item.packUnits()?.let { Text("Pack of $it - type 12p+5 for 12 packs and 5 loose",
                    style = MaterialTheme.typography.bodySmall) }
                if (item.fileExpiry.isNotBlank() || item.fileBatch.isNotBlank())
                    Text("On file: expiry ${item.fileExpiry.ifBlank { "-" }}, batch ${item.fileBatch.ifBlank { "-" }}",
                        style = MaterialTheme.typography.bodySmall)
                item.counted?.let { Text("Counted: $it", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
            } else {
                val pack = listOf(
                    item.packSize.takeIf { it.isNotBlank() }?.let { "pack of $it" },
                    item.packPrice.takeIf { it.isNotBlank() }?.let { "M$it a pack" },
                ).filterNotNull().joinToString(", ")
                if (pack.isNotEmpty()) Text(pack, style = MaterialTheme.typography.bodySmall)
                if (item.invoiceQty.isNotBlank()) Text("Invoice: ${item.invoiceQty} units", style = MaterialTheme.typography.titleMedium)
                if (item.done) Text("Checked", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Receiving: the invoice names the item its own way. A line not on our list
 * must be put on our item before it is checked; a line the web app matched
 * by a guess is shown to be confirmed.
 */
@Composable
private fun InvoiceName(item: StockSheet.Item, canPick: Boolean, onPick: () -> Unit) {
    val differs = item.invoiceName.isNotBlank() && !item.invoiceName.equals(item.name, ignoreCase = true)
    when {
        item.needsOurItem() -> Column(Modifier.fillMaxWidth().background(Color(0xFFFDE2E2)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The invoice calls it \"${item.invoiceName.ifBlank { item.name }}\" - that is not one of our items.",
                color = Color(0xFF9B1C1C), fontWeight = FontWeight.Bold)
            if (canPick) {
                Text("Pick our item for it. The web app remembers this name for the next invoice.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Pick our item") }
            } else {
                Text("Correct the name on the delivery page, or add the item under Add Medication.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item.guessed() -> Column(Modifier.fillMaxWidth().background(Color(0xFFFFF1D6)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("The invoice calls it \"${item.invoiceName}\". The web app took it to be this item - check the box.",
                color = Color(0xFF7A4B00))
            if (canPick) OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Text("Not this - pick our item")
            }
        }
        differs -> Text("On the invoice: ${item.invoiceName}", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The lines read off a paper invoice, to check against the paper and
 * correct before they go to the delivery. The figures add up line by line
 * (quantity x price = total), so a misread figure shows.
 */
@Composable
private fun InvoiceReview(
    modifier: Modifier, d: WebLink.Delivery, state: StockTakeViewModel.UiState,
    onPhoto: () -> Unit, onPdf: (Uri) -> Unit, onEdit: (Int, InvoiceReader.Line) -> Unit, onRemove: (Int) -> Unit,
    onUnits: (Boolean) -> Unit, onSend: () -> Unit, onCancel: () -> Unit,
) {
    val lines = state.invoiceLines
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(onPdf)
    }
    val sum = lines.sumOf { (it.qty.toDoubleOrNull() ?: 0.0) * (it.price.replace(',', '.').toDoubleOrNull() ?: 0.0) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("Paper invoice for ${d.reference}", style = MaterialTheme.typography.titleLarge)
            Text(if (lines.isEmpty()) "Photograph the invoice (a long one a part at a time - the lines are added together), " +
                    "or open a PDF of it: scanned on the printer, or sent by the supplier."
                 else "${lines.size} line(s) read. Check each against the paper and correct anything misread.",
                style = MaterialTheme.typography.bodyMedium)
            state.busy?.let { Busy(it) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!state.invoiceUnits, { onUnits(false) }); Text("Quantities are packs")
                Spacer(Modifier.width(12.dp))
                RadioButton(state.invoiceUnits, { onUnits(true) }); Text("Single units")
            }
            OutlinedButton(onClick = onPhoto, modifier = Modifier.fillMaxWidth()) {
                Text(if (lines.isEmpty()) "Photograph the invoice" else "Photograph more of the invoice")
            }
            OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                enabled = state.busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Open a PDF of the invoice (scanned or emailed)")
            }
        }
        items(lines.size) { i -> InvoiceLineCard(i, lines[i], onEdit, onRemove) }
        item {
            TextButton(onClick = { onEdit(lines.size, InvoiceReader.Line("", "", "", "", "")) }) { Text("+ Add a line by hand") }
            if (lines.isNotEmpty()) {
                Text("Lines add up to M${"%.2f".format(sum)} - compare with the invoice's total before VAT.",
                    fontWeight = FontWeight.Bold)
            }
            Button(onClick = onSend, enabled = lines.isNotEmpty() && state.busy == null, modifier = Modifier.fillMaxWidth()) {
                Text("Send to ${d.reference}")
            }
            Text("The lines then come back to check box by box, as if the invoice were uploaded. " +
                "Names the invoice gives its own way are matched to our items as before.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InvoiceLineCard(i: Int, l: InvoiceReader.Line, onEdit: (Int, InvoiceReader.Line) -> Unit, onRemove: (Int) -> Unit) {
    val q = l.qty.toDoubleOrNull()
    val p = l.price.replace(',', '.').toDoubleOrNull()
    val t = l.total.replace(',', '.').toDoubleOrNull()
    // The printed total no longer matches quantity x price: something was misread or changed.
    val off = q != null && p != null && t != null && kotlin.math.abs(q * p - t) > 0.02
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(l.name, { onEdit(i, l.with(l.code, it, l.qty, l.price)) }, label = { Text("Item") },
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(i) }) { Text("Remove") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(l.qty, { onEdit(i, l.with(l.code, l.name, it, l.price)) }, label = { Text("Qty") },
                    singleLine = true, isError = q == null || q <= 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(l.price, { onEdit(i, l.with(l.code, l.name, l.qty, it)) }, label = { Text("Price") },
                    singleLine = true, isError = off,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(l.code, { onEdit(i, l.with(it, l.name, l.qty, l.price)) }, label = { Text("Code") },
                    singleLine = true, modifier = Modifier.weight(1f))
            }
            if (off) Text("On the invoice the line total is ${l.total} - check the quantity and price.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Our medication list, to put an invoice line on one of our items. */
@Composable
private fun OurItemPicker(
    item: StockSheet.Item?, ours: List<StockSheet.OurItem>, busy: String?,
    onPick: (StockSheet.OurItem) -> Unit, onRetry: () -> Unit, onClose: () -> Unit,
) {
    val invoiceName = item?.let { i -> i.invoiceName.ifBlank { i.name } } ?: ""
    var query by remember { mutableStateOf("") }
    val likely = remember(ours, invoiceName) { StockSheet.likely(ours, invoiceName, 5) }
    val rows = remember(ours, query) {
        if (query.isBlank()) emptyList()
        else ours.filter { it.name.contains(query.trim(), true) || it.code.equals(query.trim(), true) }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Which of our items is it?", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        Text("On the invoice: $invoiceName", fontWeight = FontWeight.Bold)
        busy?.let { Busy(it) }
        if (ours.isEmpty() && busy == null) {
            Text("Our medication list is not on the phone yet.")
            OutlinedButton(onClick = onRetry) { Text("Get it from the web app") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Search our list: name or code") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        val show = if (query.isBlank()) likely else rows
        Text(if (query.isBlank()) (if (likely.isEmpty()) "Type to search" else "Most like the invoice's name:")
             else "${rows.size} found", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize()) {
            items(show, key = { it.name }) { o ->
                Column(Modifier.fillMaxWidth().clickable { onPick(o) }.padding(vertical = 10.dp)) {
                    Text(o.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = listOfNotNull(o.code.takeIf { it.isNotBlank() },
                        o.packSize.takeIf { it.isNotBlank() && it != "1" }?.let { "pack of $it" }).joinToString("  ·  ")
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

/** Expired or within three months: said while the pack is in your hand. */
@Composable
private fun ExpiryWarning(expiry: String) {
    val warning = remember(expiry) { Expiry.warning(expiry, LocalDate.now()) } ?: return
    val expired = warning.startsWith("EXPIRED")
    Text(warning, Modifier.fillMaxWidth()
        .background(if (expired) Color(0xFFFDE2E2) else Color(0xFFFFF1D6)).padding(10.dp),
        color = if (expired) Color(0xFF9B1C1C) else Color(0xFF7A4B00), fontWeight = FontWeight.Bold)
}

@Composable
private fun LabelNote(r: OcrProcessor.LabelResult) {
    val found = listOfNotNull(
        r.batch.takeIf { it.isNotBlank() }?.let { "batch $it" },
        r.expiry.takeIf { it.isNotBlank() }?.let { "expiry $it" },
    )
    var text = if (found.isEmpty()) "Nothing readable as a batch or expiry - type them from the box."
               else "Read from the label: ${found.joinToString(", ")}. Check them against the box."
    // When something was missed, show what the camera saw, so the date can be typed
    // from it or the photo retaken closer.
    if (r.batch.isBlank() || r.expiry.isBlank()) {
        val seen = r.rawText.replace('\n', ' ').trim()
        if (seen.isNotEmpty()) text += "\nThe camera read: \"${seen.take(160)}${if (seen.length > 160) "..." else ""}\""
    }
    Text(text, Modifier.fillMaxWidth().background(Color(0xFFFFF7E0)).padding(10.dp),
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CountFields(
    draft: Draft, onDraft: (Draft) -> Unit, item: StockSheet.Item,
    onSave: (Boolean) -> Unit, onMatch: () -> Unit, onMatchWithLabel: () -> Unit, onLabel: () -> Unit,
) {
    val packs = item.packUnits()
    // Show what a sum comes to as it is typed: "3x10+4" -> "= 34", "12p+5" -> "= 341".
    val preview: Pair<String, Boolean> = remember(draft.count, packs) {
        try {
            val v = CountExpression.evaluate(draft.count, packs)
            when {
                v == null -> "Blank = not counted yet" to false
                CountExpression.isSum(draft.count) -> "= $v" to false
                else -> "" to false
            }
        } catch (e: IllegalArgumentException) {
            (e.message ?: "Cannot read this") to true
        }
    }
    OutlinedTextField(
        value = draft.count, onValueChange = { onDraft(draft.copy(count = it)) },
        label = { Text(if (packs != null) "Physical count  (e.g. 12p+5)" else "Physical count  (e.g. 3x10+4)") },
        singleLine = true, isError = preview.second,
        supportingText = { if (preview.first.isNotEmpty()) Text(preview.first) },
        // The full keyboard when packs are possible: the number pad has no "p".
        keyboardOptions = KeyboardOptions(
            keyboardType = if (packs != null) KeyboardType.Text else KeyboardType.Phone,
            imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSave(true) }),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
    )
    if (item.onSystem != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMatch, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
                Text("Matches (${item.onSystem}) & Next", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onMatchWithLabel, modifier = Modifier.weight(1f)) {
                Text("Matches + Read Label", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    DetailFields(draft, onDraft, onLabel, showLocation = true)
    Button(onClick = { onSave(true) }, modifier = Modifier.fillMaxWidth()) { Text("Save & Next") }
}

@Composable
private fun ReceiveFields(
    draft: Draft, onDraft: (Draft) -> Unit, item: StockSheet.Item,
    onSave: (Boolean) -> Unit, onMatch: () -> Unit, onLabel: () -> Unit,
) {
    val packs = item.packUnits()
    if (item.invoiceQty.isNotBlank() && !item.needsOurItem()) {
        // The usual case: the box agrees with the invoice. One tap, once batch and expiry are in.
        Button(onClick = onMatch, modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
            Text("Matches Invoice (${item.invoiceQty}) & Next")
        }
        Text("Or type what came if it differs:", style = MaterialTheme.typography.bodySmall)
    }
    // What the quantity comes to, and how it compares with the invoice.
    val (said, wrong) = remember(draft.qty, packs, item.invoiceQty) {
        try {
            val v = CountExpression.evaluate(draft.qty, packs)
            if (v == null) "" to false else {
                val probe = StockSheet.Item().apply { invoiceQty = item.invoiceQty; qty = v.toString() }
                val diff = StockSheet.invoiceDifference(probe)
                listOfNotNull(if (CountExpression.isSum(draft.qty)) "= $v units" else null, diff)
                    .joinToString(" - ") to (diff != null)
            }
        } catch (e: IllegalArgumentException) {
            (e.message ?: "Cannot read this") to true
        }
    }
    OutlinedTextField(
        value = draft.qty, onValueChange = { onDraft(draft.copy(qty = it)) },
        label = { Text(if (packs != null) "Units received  (or packs: 2p)" else "Units received") }, singleLine = true,
        isError = wrong, supportingText = { if (said.isNotEmpty()) Text(said) },
        keyboardOptions = KeyboardOptions(keyboardType = if (packs != null) KeyboardType.Text else KeyboardType.Number),
        textStyle = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth(),
    )
    DetailFields(draft, onDraft, onLabel, showLocation = false)
    Button(onClick = { onSave(true) }, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)) {
        Text(if (item.done) "Save & Next" else "Checked & Next")
    }
}

@Composable
private fun DetailFields(draft: Draft, onDraft: (Draft) -> Unit, onLabel: () -> Unit, showLocation: Boolean) {
    OutlinedButton(onClick = onLabel, modifier = Modifier.fillMaxWidth()) { Text("Read Label (batch and expiry)") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft.expiry, { onDraft(draft.copy(expiry = it)) }, label = { Text("Expiry") },
            placeholder = { Text("03/2027") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedTextField(draft.batch, { onDraft(draft.copy(batch = it)) }, label = { Text("Batch") },
            singleLine = true, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showLocation) {
            OutlinedTextField(draft.location, { onDraft(draft.copy(location = it)) }, label = { Text("Shelf") },
                placeholder = { Text("B3") }, singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.weight(1f))
        }
        OutlinedTextField(draft.note, { onDraft(draft.copy(note = it)) }, label = { Text("Note (optional)") },
            singleLine = true, modifier = Modifier.weight(2f))
    }
}

@Composable
private fun ExportArea(
    sheet: StockSheet, rev: Long, canSend: Boolean, busy: String?,
    onSend: () -> Unit, onSave: () -> Unit, onShare: () -> Unit, onClear: () -> Unit,
) {
    var askClear by remember { mutableStateOf(false) }
    val done = remember(rev) { sheet.countDone() }
    busy?.let { Busy(it) }
    if (canSend) {
        Text(if (sheet.mode == StockSheet.Mode.RECEIVING)
                "Send the checked lines to the web app. They fill the delivery's lines; nothing is received until you press Add These Lines there."
            else "Send what is counted so far to the web app. It waits on the Stock Take page until you post it there.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = onSend, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Send to Web App") }
        Text("Or as a file:", style = MaterialTheme.typography.bodySmall)
    } else {
        Text("When done (or part done), export and upload on the web app.", style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canSend) OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save File") }
        else Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text("Save File") }
        OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
    }
    TextButton(onClick = { askClear = true }, modifier = Modifier.fillMaxWidth()) {
        Text("Finish & clear this list", color = MaterialTheme.colorScheme.error)
    }
    if (askClear) {
        AlertDialog(
            onDismissRequest = { askClear = false },
            title = { Text("Clear this list?") },
            text = { Text("$done item(s) are recorded on this phone. Send or export them first - clearing deletes them here. " +
                "Anything already on the web app is safe.") },
            confirmButton = { Button(onClick = { askClear = false; onClear() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { askClear = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun ItemList(sheet: StockSheet, rev: Long, linkingBarcode: String?, onPick: (Int) -> Unit, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var onlyOpen by remember { mutableStateOf(false) }
    val rows = remember(rev, query, onlyOpen) {
        sheet.items.withIndex().filter { (_, row) ->
            (!onlyOpen || !sheet.isDone(row)) &&
                (query.isBlank() || row.name.contains(query, true) || row.code.contains(query, true)
                    || row.location.equals(query.trim(), true))
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (linkingBarcode != null) "Which item is barcode $linkingBarcode?" else "Find an item",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Close") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("Name, code or shelf") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(onlyOpen, { onlyOpen = it })
            Text(if (sheet.mode == StockSheet.Mode.STOCK_TAKE) "Only items not counted yet" else "Only items not checked yet")
        }
        Text("${rows.size} shown", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.index }) { (i, row) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sub = listOf(row.location.takeIf { it.isNotBlank() }?.let { "Shelf $it" }, row.code.takeIf { it.isNotBlank() })
                            .filterNotNull().joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
                    val right = if (row.countedBy.isNotBlank() && row.counted == null && !StockSheet.mine(row))
                        row.countedBy
                    else if (sheet.mode == StockSheet.Mode.STOCK_TAKE)
                        (row.counted?.toString() ?: "-") + " / ${row.onSystem ?: "-"}"
                    else if (row.needsOurItem()) "pick our item"
                    else if (row.done) "checked" else "-"
                    Text(right, color = if (sheet.isDone(row)) MaterialTheme.colorScheme.secondary else Color.Gray)
                }
                HorizontalDivider()
            }
        }
    }
}
