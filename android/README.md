# Claudette -- Android (Phase 1 skeleton)

Always-on voice companion. Wake word: **"Claudette."** This phase stands up the
listening framework end to end; the wake event is stubbed until the openWakeWord
model is trained and dropped in.

## Open & run
1. Android Studio -> **Open** -> select this `Claudette` folder.
2. Let Gradle sync (it will download the Gradle distribution and dependencies).
3. Run on a device/emulator (Android 8.0 / API 26+).
4. Grant the microphone (and notification) permission when asked.
5. Tap **Start listening** -> a persistent "Claudette is listening" notification appears.
6. Tap **Test wake (simulate)** and check Logcat for `Wake word 'Claudette' detected`.

## What's implemented
- `WakeWordService` -- always-on foreground service (mic type) + wake lock, START_STICKY.
- `AudioCapture` -- 16 kHz mono PCM frames, nothing written to disk.
- `WakeWordDetector` / `OpenWakeWordDetector` -- engine seam + ONNX skeleton (NO-OP until models added).
- `BootReceiver` -- restarts listening after reboot **only if** the user had it enabled.
- `Prefs` / `SecretStore` -- plain prefs + encrypted key storage (keys land here in Phase 4).
- Compose UI -- permission flow, start/stop, and a Test-wake button.

## Add the wake-word model (Phase 1b)
Put `melspectrogram.onnx`, `embedding_model.onnx`, and your trained `claudette.onnx`
in `app/src/main/assets/models/`, then implement the three TODOs in
`OpenWakeWordDetector` (mel -> embedding -> wakeword). Training notebook:
https://github.com/dscripka/openWakeWord

## Not yet wired (next phases)
STT -> Claude (Sonnet/Opus) -> ElevenLabs TTS live behind `onWakeDetected()` in the service.
