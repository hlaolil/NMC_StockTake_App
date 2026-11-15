package com.nmc.stocktake.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun StockTakeApp(viewModel: StockTakeViewModel = viewModel()) {
    val meds by viewModel.medications.collectAsState()
    val index by viewModel.currentIndex.collectAsState()
    var showCamera by remember { mutableStateOf(false) }
    var autoMatchMode by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("stocktake") }
    val context = LocalContext.current

    val csvLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importFromCsv(context, it) }
    }

    if (showCamera) {
        CameraScreen(
            onResult = { batch, expiry ->
                if (autoMatchMode) {
                    // Auto-fill quantity with system value and save
                    val current = meds.getOrNull(index)
                    current?.let {
                        viewModel.saveCurrent(it.onSystem, expiry, batch)
                        viewModel.next()
                    }
                    autoMatchMode = false
                }
                showCamera = false
            },
            onCancel = {
                autoMatchMode = false
                showCamera = false
            }
        )
        return
    }

    if (meds.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("NMC Pharmacy", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(24.dp))
            Button(onClick = {
                viewModel.loadDefaultList()
                mode = "stocktake"
            }) { Text("Start Stock Take (NMC List)") }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                csvLauncher.launch("text/*")
                mode = "receiving"
            }) { Text("Start Receiving (Import List)") }
        }
        return
    }

    val current = meds.getOrNull(index) ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (mode == "receiving") "Receiving" else "Stock Take")
            Text("Item ${index + 1} of ${meds.size}")
        }

        Text(current.name, style = MaterialTheme.typography.headlineSmall)
        Text("On System: ${current.onSystem}")

        var countText by remember(current) { mutableStateOf(current.physicalCount.toString()) }
        var expiry by remember(current) { mutableStateOf(current.expiry) }
        var batch by remember(current) { mutableStateOf(current.batch) }

        if (mode == "stocktake") {
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it },
                label = { Text("Physical Count") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        OutlinedTextField(value = expiry, onValueChange = { expiry = it }, label = { Text("Expiry") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = batch, onValueChange = { batch = it }, label = { Text("Batch No.") }, modifier = Modifier.fillMaxWidth())

        // Quick match button
        if (mode == "stocktake") {
            Button(
                onClick = {
                    autoMatchMode = true
                    showCamera = true
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Matches System – Snap & Next")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showCamera = true }) { Text("Snap Label") }
            Button(onClick = {
                val count = if (mode == "stocktake") evaluateExpression(countText) else current.onSystem
                viewModel.saveCurrent(count, expiry, batch)
                viewModel.next()
            }) { Text("Save & Next") }
        }

        Row {
            TextButton(onClick = { viewModel.previous() }, enabled = index > 0) { Text("Previous") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.next() }, enabled = index < meds.lastIndex) { Text("Skip") }
        }

        Spacer(Modifier.weight(1f))

        Button(onClick = { viewModel.exportToCsv() }, modifier = Modifier.fillMaxWidth()) {
            Text("Export CSV / Excel")
        }

        TextButton(onClick = { viewModel.clearList() }) {
            Text("Clear & Start Over")
        }
    }
}

private fun evaluateExpression(expr: String): Int {
    return try {
        val clean = expr.replace("×", "*").replace("x", "*").replace("X", "*")
        if (!clean.matches(Regex("[0-9+\\-*()\\s]+"))) return expr.toIntOrNull() ?: 0
        expr.filter { it.isDigit() }.toIntOrNull() ?: 0
    } catch (e: Exception) {
        expr.toIntOrNull() ?: 0
    }
}
