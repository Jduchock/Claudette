# Claudette — Project Log

> Living journal for the Claudette voice-companion Android app. Newest entries at the top.
> Maintained by Claude across sessions. Sister document: `Claudette_Spec.docx` (technical/functional spec).

---

## Project at a glance

| Item | Value |
|------|-------|
| Goal | Installable Android APK: always-on voice companion, wake word **"Claudette"**, female voice, sense of humor, hands-free |
| Owner | John Duchock (duchockj@gmail.com) |
| Workspace | `C:\a_claudette` |
| Status | **Phase 1 — Listening skeleton (framework delivered)** |
| Started | 2026-07-22 |

---

## Locked decisions

| # | Decision | Choice | Date |
|---|----------|--------|------|
| D1 | Platform / stack | **Native Android (Kotlin)**, built in **Android Studio** on the user's machine | 2026-07-22 |
| D2 | Wake-word engine | ~~Picovoice Porcupine~~ → **openWakeWord** (open-source, on-device, offline), custom trained `Claudette` model. No account, no key, nothing expires. Changed 2026-07-22 because the user's Picovoice account had expired. | 2026-07-22 |
| D3 | Listening mode | **Always-on 24/7** via persistent foreground service | 2026-07-22 |
| D4 | Speech-to-text | **On-device Android `SpeechRecognizer`** (fastest, free) — built as a swappable module (can move to Deepgram / ElevenLabs Scribe later) | 2026-07-22 |
| D5 | LLM | **Anthropic Messages API** — Sonnet as default, Opus for heavier reasoning (model routing) | 2026-07-22 |
| D6 | Text-to-speech | **ElevenLabs streaming TTS**, female voice, tunable stability/style ("programmable voice & attitude") | 2026-07-22 |
| D7 | Personality | Humor + persona defined in Claude's **system prompt**; voice delivery tuned via ElevenLabs settings | 2026-07-22 |

---

## Security & privacy register

Threats are logged here as they are identified during construction. Severity: 🔴 High · 🟠 Medium · 🟡 Low.

| # | Threat | Severity | Status | Mitigation plan |
|---|--------|----------|--------|-----------------|
| S1 | **API keys embedded in the APK** (Anthropic, ElevenLabs). An APK can be decompiled; hardcoded keys are extractable and could be abused (cost, quota theft). *Reduced from 3 keys to 2 — openWakeWord needs no key.* | 🔴 High | Open | Never hardcode. Store in `EncryptedSharedPreferences` (Android Keystore-backed). For a hardened setup, front the APIs with a personal proxy so keys never live on the device. Decide in Phase 1. |
| S2 | **Always-hot microphone.** Continuous audio capture is an inherent privacy exposure; a bug or compromise could stream audio. | 🟠 Medium | Open | The openWakeWord model processes audio **locally**; only post-wake audio is buffered for STT. No raw audio persisted to disk. Clear foreground-service notification so the user always knows the mic is live. |
| S3 | **On-device STT routes audio to Google.** Android `SpeechRecognizer` may send captured speech to Google servers for transcription. | 🟠 Medium | Open | Disclose in-app. Offer an offline/alternative STT module (Vosk on-device, or Deepgram) as a swap. Only the post-wake utterance is sent, never ambient audio. |
| S4 | **Cost / runaway usage from false wakes.** A false "Claudette" trigger could fire an API call; repeated false triggers could accumulate cost. | 🟡 Low | Open | Porcupine sensitivity tuning; short confirmation earcon; per-hour request rate limiting; monthly spend cap check against Anthropic/ElevenLabs dashboards. |
| S5 | **Sensitive info spoken aloud / sent to cloud.** Requests and responses transit the network and are voiced in the open. | 🟡 Low | Open | TLS for all calls (default). Optional "private mode" that mutes TTS / disables logging. No conversation transcripts stored unless the user opts in. |
| S6 | **Auto-start on boot + wake lock.** Broad device permissions increase attack surface and battery/thermal load. | 🟡 Low | Partially mitigated | Request only the minimum permission set; document each permission's purpose. `BootReceiver` now only restarts listening if the user had explicitly enabled it. |
| S7 | **GitHub credential handling during migration.** A Personal Access Token pasted into the cloud session is an exposure point (session logs, transit). | 🟠 Medium | Mitigating | Use a **fine-grained PAT scoped to only this repo** with Contents:read/write and a short expiry; use it in-memory only, never commit or echo it; **revoke immediately after** the push. `.gitignore` blocks `*.token` / `secrets.properties` from ever being committed. |

---

## Open questions (to resolve before/early in Phase 1)

- **Q1.** Do you want to protect the API keys with a personal proxy backend, or is on-device `EncryptedSharedPreferences` acceptable for a single-user app? (Ties to S1.)
- **Q2.** Which ElevenLabs voice should Claudette use? (Name/voice ID — you can pick from your ElevenLabs library, or I can suggest a few female voices.)
- **Q3.** How "much" humor? Dry & witty, warm & playful, or sarcastic? This shapes the system prompt persona.
- **Q4.** Model routing rule: always Sonnet unless you say "think hard," or auto-detect complex requests and escalate to Opus? Any monthly spend ceiling?
- **Q5.** Should Claudette hold short conversational context (remember the last few exchanges), or treat each request as standalone?
- **Q6.** Wake behavior: after she answers, should she keep listening for a follow-up for a few seconds, or always require "Claudette" again?
- **Q7.** Minimum Android version / your phone model? (Affects foreground-service rules; Android 14+ needs `FOREGROUND_SERVICE_MICROPHONE`.)
- **Q8.** How should we produce the custom "Claudette" openWakeWord model — Claude guides you through the free training Colab, or Claude attempts to generate the model file for you in a later session?

---

## Session log

### 2026-07-22 — Session 1: Kickoff & design
- Confirmed project understanding and goals with the user.
- Ran a 4-question scope survey. Captured decisions D1–D7 above.
- Noted user's STT answer ("fastest, with programmable voice & attitude") maps voice→ElevenLabs and attitude→Claude system prompt; chose on-device streaming STT as the fastest transcription step, built swappable.
- Confirmed `C:\a_claudette` is empty — clean greenfield.
- Created this log and the Word spec (`Claudette_Spec.docx`).
- Logged initial security register S1–S6.
- **Next:** user sets up a free Picovoice account and generates a custom `Claudette.ppn` keyword; then Phase 1 = scaffold the Android project (foreground service + Porcupine listening loop).

### 2026-07-22 — Session 2: Wake-word engine change
- User's Picovoice account had expired; user asked to switch to an open-source alternative ("openvoice").
- Clarified naming: "OpenVoice" is a TTS/voice-cloning model, not a wake-word engine. The intended alternative is **openWakeWord**.
- **Decision D2 changed:** Picovoice Porcupine → **openWakeWord**. Rationale: fully open-source, on-device/offline, no account, no API key, nothing expires. Also removes one credential from the security surface (see S1).
- Tradeoffs recorded in spec §8: the custom "Claudette" model is *trained* via openWakeWord's free Colab (synthetic-speech training) rather than made in a web console; and it runs on Android via ONNX Runtime Mobile / TensorFlow Lite rather than an official SDK. Fallback if integration is fiddly: **Vosk** keyword-spotting.
- Updated `Claudette_Spec.docx` to **v0.2** (stack table, pipeline, latency, S1/S2, build & delivery, roadmap).
- **New open question Q8:** how do we want to produce the "Claudette" model — Claude guides the user through the Colab, or Claude attempts to generate the model file in a later session?
- **Next (unchanged otherwise):** Phase 1 = scaffold the Android project (foreground service + openWakeWord listening loop). No external account now gates the start.

### 2026-07-22 — Session 3: Phase 1 framework scaffolded
- User confirmed Android Studio is open and asked to craft the framework; provided the openWakeWord repo link for parallel training.
- Delivered `Claudette_Android_Phase1.zip` (46 files) to chat and to `C:\a_claudette\`. Native Kotlin + Jetpack Compose, package `com.duchock.claudette`.
- Stack pinned: AGP 8.5.2, Kotlin 1.9.24, Gradle 8.9, compileSdk/targetSdk 34, minSdk 26, Compose BOM 2024.06.00 (compiler ext 1.5.14), ONNX Runtime 1.18.0, security-crypto 1.1.0-alpha06.
- Components built: `WakeWordService` (always-on foreground service, `microphone` FGS type, partial wake lock, START_STICKY), `AudioCapture` (16 kHz mono PCM, nothing persisted), `WakeWordDetector` interface + `OpenWakeWordDetector` ONNX skeleton (NO-OP until models present), `BootReceiver` (restart-on-boot guarded by the enabled flag), `Prefs`, `SecretStore` (EncryptedSharedPreferences for keys), Compose UI (permission flow + start/stop + Test-wake).
- Verified: all XML well-formed; every referenced drawable/mipmap/string resource exists. **Not compiled here** — no Android SDK in this environment; Gradle sync/build happens on the user's machine in Android Studio.
- Design choices worth noting:
  - `onWakeDetected()` is the single seam where Phase 2 (STT → Claude → ElevenLabs) will attach.
  - Boot auto-start is gated on the user's saved "listening enabled" flag → partially mitigates S6 (no surprise mic-on after reboot).
  - `OpenWakeWordDetector` degrades gracefully with no models so the pipeline is testable via the "Test wake" button before training completes (addresses the model-availability gap).
- **Open item Q8 still pending:** who produces `claudette.onnx` — user via the Colab, or Claude in a later session.
- **Next:** Phase 1b = implement the three-model ONNX inference in `OpenWakeWordDetector` once `claudette.onnx` exists; then Phase 2 = wire the voice round-trip.

### 2026-07-22 — Session 4: Migrate to GitHub
- User created a GitHub repository and wants the project to live there as the source of truth, then delete the local `C:\a_claudette` working folder.
- Environment findings: this cloud session has **no valid GitHub credentials** (injected `GITHUB_TOKEN`/`GH_TOKEN` are non-functional proxy placeholders, HTTP 502). The device bridge (`device_bash`) has **no network access**, so it cannot `git push`. Therefore the authenticated push must run from the cloud container with a real credential.
- User chose to paste a token; **new threat S7** logged for credential handling (fine-grained, repo-scoped, short expiry, in-memory only, revoke after).
- Repo structure prepared and committed locally on `main`: `README.md`, `android/` (the Kotlin app), `docs/` (this log + the Word spec). Root `.gitignore` excludes build output and secret files.
- **Going forward the GitHub repo is the source of truth** — future sessions clone/pull it; the docs live under `docs/` and are updated there.
- **Outcome:** the pasted token could **not** be used from the cloud container. This environment's git proxy (`CCR_AGENT_PROXY_ENABLED=1`) overrides the Authorization header and gates GitHub by repo ("GitHub access to this repository is not enabled for this session. Use add_repo…"). No `add_repo`/repo-grant tool is exposed to the session, so a push from here is not possible without the repo being enabled at the Claude-app level.
- **Pivot:** delivered a ready-to-push bundle `Claudette-git-repo.zip` (full `.git` history, remote preset to the tokenless HTTPS URL, one commit on `main`). User pushes from their own machine where their GitHub auth + network already work — no token needed in-session. Verified no token string leaked into the repo/`.git`.
- **S7 action:** the pasted PAT was transmitted to the session but never usable; user to **revoke it now** regardless.
- **Future "resume in github":** because the cloud session can't reach the repo directly, either (a) connect GitHub as a connector / enable the repo for the session so I can pull/push directly, or (b) continue the hand-off pattern (I commit in-container, user pushes). To be decided.
- **Next:** user pushes `main`, confirms on github.com/Jduchock/Claudette, revokes the token, deletes the local working folder; then resume Phase 1b / Phase 2.

### 2026-07-22 — Session 5: Phase 2 decisions + framework build
- User confirmed the push to GitHub **landed with no errors**. Repo is live at github.com/Jduchock/Claudette.
- Requested twice-daily push reminders (noon + 4 PM Central); the scheduled-task creation was **declined at the approval prompt** — deferred until the user approves it. (Reminders fire from the cloud and push to reachable devices; cannot be truly gated to "only at this computer.")
- Provided the openWakeWord training link; user training `claudette.onnx` (~1 hr run).
- **Phase 2 decisions captured:**
  - **D8 Model routing:** *auto-escalate* — Sonnet by default, switch to Opus when a complexity heuristic fires.
  - **D9 Memory:** remember recent turns; reset after an idle gap.
  - **D10 Follow-ups:** *stay open until dismissed* — full conversation mode; keeps listening turn-to-turn until a dismiss phrase or Stop.
  - **D11 API keys:** on-device, encrypted (EncryptedSharedPreferences), entered via a settings screen.
- Built the Phase 2 framework (delivered as a repo update to pull in): `ClaudeClient`, `ElevenLabsClient`, `AndroidSpeechToText` (+`SpeechToText` seam), `TtsPlayer`, `Persona`, `Router` (escalation heuristic), `ConversationManager` (memory + idle reset), a rewritten `WakeWordService` conversation loop, and a `SettingsActivity` for keys/voice.
- **Open items to resolve on the device:**
  - **Model IDs** (`Router.SONNET` / `Router.OPUS`) are placeholders — must be set to the current Anthropic model IDs (present-day values; verify against docs).
  - **ElevenLabs voice ID** — user picks a female voice and enters it in Settings.
  - **Persona/humor** — a witty default is shipped in `Persona`; tune to taste.
  - **Streaming** (SSE for Claude, chunked TTS) deferred to Phase 2b; the framework does a correct non-streaming round-trip first.
  - **Security S8 (new):** keys entered in Settings live in EncryptedSharedPreferences; TTS/STT audio and transcripts are not persisted. STT uses Android's recognizer (routes to Google — S3).
- **Next:** user finishes training + drops the 3 ONNX models in `assets/models/`; I implement the openWakeWord inference (Phase 1b) and we test the full loop on-device.
