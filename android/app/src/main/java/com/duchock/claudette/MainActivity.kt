package com.duchock.claudette

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.duchock.claudette.media.ImageStore
import com.duchock.claudette.service.WakeWordService
import com.duchock.claudette.ui.SettingsActivity
import com.duchock.claudette.util.DebugStatus
import com.duchock.claudette.util.Prefs
import com.duchock.claudette.util.Secrets
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { ClaudetteScreen() }
            }
        }
    }

    @Composable
    private fun ClaudetteScreen() {
        var listening by remember { mutableStateOf(Prefs.isListeningEnabled(this)) }

        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        val bgLocationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* best-effort: Nova works foreground-only without "Allow all the time" */ }

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startListening(); listening = true
            } else listening = false
            // If foreground location was granted, follow up for background ("Allow all the time")
            // so Nova can check location even while running in the background. Best-effort.
            val fgLocation = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fgLocation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

        // ---- camera capture -> review (confirm / retake / cancel) -> feed to Nova ----
        var captureFile by remember { mutableStateOf<File?>(null) }
        var reviewFile by remember { mutableStateOf<File?>(null) }
        val takePicture = rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture()
        ) { ok ->
            if (ok) reviewFile = captureFile else captureFile?.delete()
        }
        fun launchCapture() {
            val f = File.createTempFile("nova_cap_", ".jpg", cacheDir)
            captureFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            takePicture.launch(uri)
        }

        // On first entry, if we don't have the mic permission yet, ask for it.
        LaunchedEffect(Unit) {
            if (!hasAudioPermission()) launcher.launch(permissions)
        }

        // Auto-start listening whenever the app comes to the foreground (ON_RESUME).
        DisposableEffect(Unit) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && hasAudioPermission()) {
                    startListening(); listening = true
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }

        val toReview = reviewFile
        if (toReview != null) {
            PhotoReview(
                file = toReview,
                onConfirm = { sendImageToNova(toReview); reviewFile = null },
                onRetake = { reviewFile = null; launchCapture() },
                onCancel = { toReview.delete(); reviewFile = null }
            )
            return
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            DemoBanner()
            Text("Nova", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (listening) "Listening for \"Nova\"..." else "Idle",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            DebugCard()
            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {
                    if (listening) { stopListening(); listening = false }
                    else if (hasAudioPermission()) { startListening(); listening = true }
                    else launcher.launch(permissions)
                },
                modifier = Modifier.height(56.dp)
            ) {
                Icon(if (listening) Icons.Filled.Mic else Icons.Filled.MicOff, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (listening) "Stop listening" else "Start listening")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { launchCapture() }) {
                Text("Give Nova a Picture")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { readBible() }) {
                Text("Read the Bible (continue)")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { stopReadingBible() }) {
                Text("STOP reading")
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { testWake() }, enabled = listening) {
                Text("Test wake (simulate)")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }) { Text("Settings (API keys & voice)") }

            Spacer(Modifier.height(24.dp))
            Text(
                "Nova is listening in the background whenever the app has run once. Say \"Nova\" to " +
                    "talk, or tap \"Give Nova a Picture\" to show her something.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
            )
        }
    }

    /**
     * Store-demo indicator. Only shows while Nova is in demo mode, so John can see at a glance that
     * she is ready to reference the shoe database and take a demo picture. Reads live Compose state
     * from DebugStatus, so it appears/updates automatically as demo mode toggles.
     */
    @Composable
    private fun DemoBanner() {
        if (!DebugStatus.demoMode) return
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val onColor = MaterialTheme.colorScheme.onPrimaryContainer
                Text("●  DEMO MODE", style = MaterialTheme.typography.titleMedium, color = onColor)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (DebugStatus.inventoryLoaded) "Shoe database: ready ✓" else "Shoe database: loading…",
                    style = MaterialTheme.typography.bodySmall, color = onColor
                )
                Text(
                    if (DebugStatus.analyzingImage) "Matching a demo photo…" else "Ready for a demo picture",
                    style = MaterialTheme.typography.bodySmall, color = onColor
                )
            }
        }
    }

    @Composable
    private fun PhotoReview(file: File, onConfirm: () -> Unit, onRetake: () -> Unit, onCancel: () -> Unit) {
        val bmp = remember(file.absolutePath) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Send this to Nova?", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            if (bmp != null) {
                Image(bmp, contentDescription = "Captured photo",
                    modifier = Modifier.fillMaxWidth().height(360.dp))
            } else {
                Text("Couldn't load the photo.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConfirm) { Text("Confirm") }
                OutlinedButton(onClick = onRetake) { Text("Retake") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }

    @Composable
    private fun DebugCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                fun mark(s: String) = if (s.isNotBlank()) "✓ set" else "✗ NOT set"
                val small = MaterialTheme.typography.bodySmall
                Text("Status", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text("Wake model: " + if (DebugStatus.wakeModelLoaded) "✓ loaded" else "✗ MISSING", style = small)
                Text("Listening: " + if (DebugStatus.listening) "on" else "off", style = small)
                Text("Anthropic key: " + mark(Secrets.anthropicKey(this@MainActivity)), style = small)
                Text("ElevenLabs key: " + mark(Secrets.elevenLabsKey(this@MainActivity)), style = small)
                Text("Voice ID: " + mark(Secrets.voiceId(this@MainActivity)), style = small)
                Text("Wake score: " + "%.2f".format(DebugStatus.lastWakeScore), style = small)
                Spacer(Modifier.height(4.dp))
                Text("Last: " + DebugStatus.lastEvent, style = small)
                if (DebugStatus.lastError.isNotBlank())
                    Text("Err: " + DebugStatus.lastError, style = small)
            }
        }
    }

    private fun hasAudioPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        Prefs.setListeningEnabled(this, true)
        ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
    }

    private fun stopListening() {
        Prefs.setListeningEnabled(this, false)
        startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP))
    }

    private fun testWake() {
        startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_TEST_WAKE))
    }

    /** Persist the confirmed photo and hand it to the service for analysis. */
    private fun sendImageToNova(temp: File) {
        val saved = ImageStore.save(this, temp.readBytes(), System.currentTimeMillis())
        temp.delete()
        startService(
            Intent(this, WakeWordService::class.java)
                .setAction(WakeWordService.ACTION_ANALYZE_IMAGE)
                .putExtra(WakeWordService.EXTRA_IMAGE_PATH, saved.absolutePath)
        )
        DebugStatus.event("Sent a photo to Nova…")
    }

    private fun readBible() {
        startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_START_READING))
    }

    private fun stopReadingBible() {
        startService(Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP_READING))
    }
}
