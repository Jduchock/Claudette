package com.duchock.claudette.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.duchock.claudette.util.Prefs
import com.duchock.claudette.util.SecretStore

/** Enter and store API keys (encrypted) and the ElevenLabs voice id. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { SettingsScreen() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun SettingsScreen() {
        var anthropic by remember {
            mutableStateOf(SecretStore.get(this, SecretStore.KEY_ANTHROPIC) ?: "")
        }
        var eleven by remember {
            mutableStateOf(SecretStore.get(this, SecretStore.KEY_ELEVENLABS) ?: "")
        }
        var voice by remember { mutableStateOf(Prefs.voiceId(this)) }
        var maps by remember {
            mutableStateOf(SecretStore.get(this, SecretStore.KEY_GOOGLE_MAPS) ?: "")
        }
        var saved by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Nova settings", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Keys are stored encrypted on this device (Android Keystore). They are never " +
                    "sent anywhere except directly to Anthropic and ElevenLabs.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = anthropic, onValueChange = { anthropic = it; saved = false },
                label = { Text("Anthropic API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = eleven, onValueChange = { eleven = it; saved = false },
                label = { Text("ElevenLabs API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = voice, onValueChange = { voice = it; saved = false },
                label = { Text("ElevenLabs voice ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = maps, onValueChange = { maps = it; saved = false },
                label = { Text("Google Maps API key (optional — nearby places)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    SecretStore.put(this@SettingsActivity, SecretStore.KEY_ANTHROPIC, anthropic.trim())
                    SecretStore.put(this@SettingsActivity, SecretStore.KEY_ELEVENLABS, eleven.trim())
                    Prefs.setVoiceId(this@SettingsActivity, voice.trim())
                    SecretStore.put(this@SettingsActivity, SecretStore.KEY_GOOGLE_MAPS, maps.trim())
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
            if (saved) Text("Saved.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
        }
    }
}
