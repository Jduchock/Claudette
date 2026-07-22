package com.duchock.claudette

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.duchock.claudette.service.WakeWordService
import com.duchock.claudette.ui.SettingsActivity
import com.duchock.claudette.util.Prefs

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                startListening(); listening = true
            } else listening = false
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Claudette", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (listening) "Listening for \"Claudette\"..." else "Idle",
                style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))

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
            OutlinedButton(onClick = { testWake() }, enabled = listening) {
                Text("Test wake (simulate)")
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }) { Text("Settings (API keys & voice)") }

            Spacer(Modifier.height(24.dp))
            Text(
                "Set your Anthropic + ElevenLabs keys and a voice ID in Settings. Until the " +
                    "openWakeWord model is added, use \"Test wake\" to start a conversation.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
            )
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
}
