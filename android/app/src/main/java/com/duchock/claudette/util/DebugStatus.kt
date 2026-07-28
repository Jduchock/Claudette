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

    fun event(msg: String) { lastEvent = msg }
}
