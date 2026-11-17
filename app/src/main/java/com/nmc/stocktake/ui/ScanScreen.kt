package com.nmc.stocktake.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * Scans continuously and returns the first code it sees: a barcode on a box
 * (to find the item) or the QR code on the web app's Phone App page (to
 * link the phone). No button to press - just point.
 */
@Composable
fun ScanScreen(
    title: String,
    hint: String,
    qrOnly: Boolean,
    onCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var allowed by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!allowed) askPermission.launch(Manifest.permission.CAMERA)
    }
    if (!allowed) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (asked) "The camera is needed to scan. Allow it in the phone's Settings > Apps > NMC Stock Take > Permissions."
                else "Asking for the camera...",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            if (asked) Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Ask Again") }
            TextButton(onClick = onCancel) { Text("Back") }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(if (qrOnly) Barcode.FORMAT_QR_CODE else Barcode.FORMAT_ALL_FORMATS)
                .build()
        )
    }
    val controller = remember {
        LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.IMAGE_ANALYSIS) }
    }
    var found by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val main = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(
            main,
            MlKitAnalyzer(listOf(scanner), ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED, main) { result ->
                val value = result.getValue(scanner)?.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
                if (value != null && !found) {
                    found = true
                    onCode(value)
                }
            },
        )
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.clearImageAnalysisAnalyzer()
            controller.unbind()
            scanner.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller } },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0xAA000000)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(hint, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xAA000000)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
        }
    }
}
