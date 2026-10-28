package app.flux.dj

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.flux.dj.ui.FluxApp

class MainActivity : ComponentActivity() {
    private val controller: Controller by viewModels()
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { try { contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {} }
            controller.addFiles(uris)
        }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            controller.addFolder(uri)
        }
    }
    private val drivePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            controller.connectCloudFolder(uri)
        }
    }
    private var samplePad = 0
    private val samplePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.setSampleFromUri(samplePad, uri)
    }
    private val xmlPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.importRekordbox(uri)
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshLibrary() }
    private val btPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { controller.refreshDevices() }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.micGranted = granted
        if (granted) controller.toast = "Microphone ready — press Measure again"
        else controller.toast = "Microphone denied — you can still align by ear"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        controller.requestMicPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) }
        // Device names are only needed when the Outputs sheet is open, not at launch.
        controller.requestBtPermission = {
            if (Build.VERSION.SDK_INT >= 31) btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        setContent {
            FluxApp(
                controller,
                onAddFiles = { picker.launch(arrayOf("audio/*")) },
                onAddFolder = { folderPicker.launch(null) },
                onPickSample = { k -> samplePad = k; samplePicker.launch(arrayOf("audio/*")) },
                onImportXml = { xmlPicker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                onConnectDrive = { drivePicker.launch(null) },
                onPermission = {
                    permission.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
                },
            )
        }
    }

    override fun onResume() { super.onResume(); controller.refreshDevices(); controller.refreshLibrary(); controller.micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
}
