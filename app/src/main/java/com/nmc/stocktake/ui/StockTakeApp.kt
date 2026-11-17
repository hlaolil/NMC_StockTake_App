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
    var lastLabel by remember { mutableStateOf<OcrProcessor.LabelResult?>(null) }
    var confirmMatch by remember { mutableStateOf(false) }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }   // scanned, on no item yet
    var linkingBarcode by remember { mutableStateOf<String?>(null) }   // choosing its item from the list
    var draft by remember(state.index, state.sheet) { mutableStateOf(Draft.of(state.item)) }
    LaunchedEffect(state.index, state.sheet) { lastLabel = null }

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
                    val i = vm.findBarcode(code)
                    if (i >= 0) vm.goTo(i) else unknownBarcode = code
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

    val sheet = state.sheet
    if (sheet == null) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
            StartScreen(Modifier.padding(pad), state,
                onImport = { uri, mode -> vm.import(uri, mode) },
                onScanLink = { camera = CameraFor.LINK },
                onPasteLink = { code -> vm.linkWeb(code) },
                onGetSheet = { g, label -> vm.getSheetFromWeb(g, label) },
                onGetDelivery = { d -> vm.getDeliveryFromWeb(d) },
                onRefresh = { vm.refreshWeb() },
                onUnlink = { vm.unlinkWeb() })
        }
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
            WebDeliveries(state.deliveries, state.busy != null, onGetDelivery)
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
private fun WebDeliveries(deliveries: List<WebLink.Delivery>, busy: Boolean, onGet: (WebLink.Delivery) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Receiving", style = MaterialTheme.typography.titleMedium)
            if (deliveries.isEmpty()) {
                Text("No delivery is open on the web app. Open one on the Receive page and upload its invoice, then tap Refresh.")
            }
            for (d in deliveries) {
                val what = listOf(d.supplier, d.invoices.takeIf { it.isNotBlank() }?.let { "invoice $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
                if (d.lines > 0) {
                    Button(onClick = { onGet(d) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("${d.reference}${if (what.isNotEmpty()) " - $what" else ""}: ${d.lines} line(s)" +
                            if (d.checked > 0) ", ${d.checked} checked" else "")
                    }
                } else {
                    Text("${d.reference}${if (what.isNotEmpty()) " - $what" else ""}: upload the invoice on the delivery page first.",
                        style = MaterialTheme.typography.bodySmall)
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
    val text = if (found.isEmpty()) "Nothing readable as a batch or expiry - type them from the box."
               else "Read from the label: ${found.joinToString(", ")}. Check them against the box."
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
    onSave: (Boolean) -> Unit, onLabel: () -> Unit,
) {
    val packs = item.packUnits()
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
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    val right = if (sheet.mode == StockSheet.Mode.STOCK_TAKE)
                        (row.counted?.toString() ?: "-") + " / ${row.onSystem ?: "-"}"
                    else if (row.done) "checked" else "-"
                    Text(right, color = if (sheet.isDone(row)) MaterialTheme.colorScheme.secondary else Color.Gray)
                }
                HorizontalDivider()
            }
        }
    }
}
