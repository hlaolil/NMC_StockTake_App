package com.nmc.stocktake.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nmc.stocktake.ocr.OcrProcessor
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    onResult: (batch: String, expiry: String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controller = remember { LifecycleCameraController(context) }
    var isProcessing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        controller.bindToLifecycle(lifecycleOwner)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { PreviewView(it).apply { this.controller = controller } },
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isProcessing) {
                CircularProgressIndicator()
                Text("Reading label...")
            } else {
                Button(
                    onClick = {
                        isProcessing = true
                        val file = File(context.cacheDir, "label.jpg")
                        val output = ImageCapture.OutputFileOptions.Builder(file).build()
                        controller.takePicture(
                            output,
                            Executors.newSingleThreadExecutor(),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                    scope.launch {
                                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                        val (batch, expiry) = OcrProcessor.extractBatchAndExpiry(bitmap)
                                        onResult(batch, expiry)
                                    }
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    isProcessing = false
                                }
                            }
                        )
                    }
                ) {
                    Text("Capture Label")
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
