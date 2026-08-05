package com.duchock.claudette.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared, in-process status surface so the app screen can show what's happening without
 * needing Logcat. The foreground service writes to it; the UI observes it (same process,
 * Compose snapshot state is safe to write from background threads).
 */
object DebugStatus {
    var listening by mutableStateOf(false)
    var wakeModelLoaded by mutableStateOf(false)
    var lastWakeScore by mutableStateOf(0f)
    var lastEvent by mutableStateOf("Idle")

    // Demo-mode readiness surfaced on the main screen so John can see Nova is ready to demo:
    // whether the store-demo persona is active, whether the shoe database has finished loading,
    // and whether she is currently analyzing a demo photo.
    var demoMode by mutableStateOf(false)
    var inventoryLoaded by mutableStateOf(false)
    var analyzingImage by mutableStateOf(false)
    var lastError by mutableStateOf("")   // most recent API error, blank when last call succeeded

    fun event(msg: String) { lastEvent = msg }
}
