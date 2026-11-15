# NMC Pharmacy Stock Take App

Android app for sequential stock counting with camera OCR for batch & expiry.

## Features
- Pre-loaded with the 303-item NMC Pharmacy list
- Import CSV of any medication list
- Enter physical count (supports simple sums)
- Snap label photo → ML Kit OCR extracts batch & expiry
- Auto-save progress
- Export to CSV (opens in Excel)

## Setup
1. Open this folder in Android Studio (Hedgehog or newer)
2. Let it sync Gradle
3. Add these dependencies in `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
}
```

4. Add camera permission in `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.CAMERA" />
```

5. Build and run on a device.

## Next steps to complete
- Implement CameraX + ML Kit OCR in the "Snap Label" button
- Add proper expression evaluator for counts
- Add file sharing for the exported CSV
- Optional: Room database for better persistence

The core counting flow and NMC list are already included.
