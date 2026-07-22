# Claudette

An always-on, hands-free voice companion for Android. Wake word: **"Claudette."**
She listens continuously, answers via Anthropic Claude (Sonnet / Opus), speaks back in a
female ElevenLabs voice, and has a sense of humor.

## Repository layout
- **`android/`** — the Android app (Kotlin + Jetpack Compose). Open this folder in Android Studio.
- **`docs/`** — living project documents:
  - `CLAUDETTE_LOG.md` — running journal: decisions, security register, open questions, session history.
  - `Claudette_Spec.docx` — technical & functional specification.

## Status
Phase 1 — listening skeleton (always-on foreground service + openWakeWord seam). See `docs/` for details.

## Stack
Native Android (Kotlin) · openWakeWord (wake word) · on-device STT · Anthropic Claude · ElevenLabs TTS.
