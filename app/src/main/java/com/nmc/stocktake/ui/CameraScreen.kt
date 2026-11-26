package com.nmc.stocktake.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import com.google.mlkit.vision.common.InputImage
import com.nmc.stocktake.ocr.OcrProcessor

/**
 * Point at the label, press Read Label. Asks for the camera the first time.
 * The result goes back to the counting screen to be checked, never saved
 * straight away - a camera can misread.
 */
@Composable
fun CameraScreen(
    itemName: String,
    onResult: (OcrProcessor.LabelResult) -> Unit,
    onCancel: () -> Unit,
) {
    PhotoCamera(itemName, "Fill the frame with the batch and expiry print (or the square 2D code)",
        "Read Label", "Reading the label...", onCancel) { image, done, fail ->
        readImage(image, onDone = { done(); onResult(it) }, onFail = { fail(it) })
    }
}

/**
 * A paper invoice, a photo at a time: hold the phone flat over the page so
 * the rows run straight across. Long invoices take several photos.
 */
@Composable
fun InvoiceCameraScreen(
    title: String,
    onLines: (List<com.nmc.stocktake.core.InvoiceReader.Line>) -> Unit,
    onCancel: () -> Unit,
) {
    PhotoCamera(title, "Hold the phone flat over the invoice so the lines run straight across. " +
            "A long invoice: one part at a time.",
        "Read Invoice", "Reading the invoice...", onCancel) { image, done, fail ->
        readInvoiceImage(image, onDone = { done(); onLines(it) }, onFail = { fail(it) })
    }
}

@Composable
private fun PhotoCamera(
    title: String, hint: String, button: String, busyText: String, onCancel: () -> Unit,
    onPhoto: (ImageProxy, done: () -> Unit, fail: (String) -> Unit) -> Unit,
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
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (asked) "The camera is needed to read labels. Allow it in the phone's Settings > Apps > NMC Stock Take > Permissions, or type the batch and expiry instead."
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
    val controller = remember {
        LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE) }
    }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller } },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Color(0xAA000000)).statusBarsPadding().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(hint, color = Color.White, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xAA000000)).navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            problem?.let { Text(it, color = Color(0xFFFCA5A5)); Spacer(Modifier.height(8.dp)) }
            if (busy) {
                CircularProgressIndicator(color = Color.White)
                Text(busyText, color = Color.White)
            } else {
                Button(onClick = {
                    busy = true
                    problem = null
                    controller.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                onPhoto(image, { busy = false }, { busy = false; problem = it })
                            }

                            override fun onError(exception: ImageCaptureException) {
                                busy = false
                                problem = "The photo did not take: ${exception.message}"
                            }
                        })
                }, modifier = Modifier.fillMaxWidth()) { Text(button) }
                TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
private fun readImage(
    proxy: ImageProxy,
    onDone: (OcrProcessor.LabelResult) -> Unit,
    onFail: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        onFail("The photo was empty - try again")
        return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    OcrProcessor.read(input, onDone, onFail, onFinally = { proxy.close() })
}

@OptIn(ExperimentalGetImage::class)
private fun readInvoiceImage(
    proxy: ImageProxy,
    onDone: (List<com.nmc.stocktake.core.InvoiceReader.Line>) -> Unit,
    onFail: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        onFail("The photo was empty - try again")
        return
    }
    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
    OcrProcessor.readInvoice(input, { lines ->
        if (lines.isEmpty()) onFail("No invoice lines could be read - hold the phone flat and closer, with good light")
        else onDone(lines)
    }, onFail, onFinally = { proxy.close() })
}
