# Claudette — Project Log

> Living journal for the Claudette voice-companion Android app. Newest entries at the top.
> Maintained by Claude across sessions. Sister document: `Claudette_Spec.docx` (technical/functional spec).

---

## Project at a glance

| Item | Value |
|------|-------|
| Goal | Installable Android APK: always-on voice companion, wake word **"Nova"** (spoken name; code/model files still named "claudette"), female voice, sense of humor, hands-free |
| Owner | John Duchock (duchockj@gmail.com) |
| Workspace | `C:\a_claudette` |
| Status | **Phase 2 — voice framework in place; wake-model retraining, blocked on a clean training run (see Session 8)** |
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

### 2026-07-22 — Session 6: Wake-word training (browser-driven) + TOGAF assessment
- Wake word set by user: `target_word = "klau_dette"` (phonetic spelling of Claudette) in the openWakeWord "simple" automatic-training Colab.
- Claude drove the run via the Chrome browser tools on the user's own Colab copy: cleared 3 stale Colab sessions, launched Run all.
- **Blocker found & fixed — real dependency conflict.** The notebook (2026-04-11) installs `numba` (needs `numpy<2.1`) *and* `onnx2tf` (needs `numpy==2.2.6`); irreconcilable, so every Run-all reinstalled numpy and re-armed the "restart runtime" prompt — an infinite loop (the same one the user hit earlier). **Fix:** commented out the `!pip install onnx2tf` line via Colab Find & Replace. `onnx2tf` is only for the optional TFLite export; we only need the `.onnx`. After one final settling restart, installs cleared with numpy stable at 2.0.2 → loop broken.
- Non-fatal: the **AudioSet** noise download 404'd (dataset URL moved upstream); notebook skips it gracefully (one fewer background-noise source, still trains well). The 16 GB pre-computed feature set + validation set downloaded fine.
- Training (cell 3) started clean: generating 1,000 synthetic "klau_dette" clips → 10,000 training steps (~30–60 min, CPU runtime). Output → `/content/my_custom_model/klau_dette.onnx`. A harmless TFLite/onnx2tf error is expected at the very end (export disabled); the `.onnx` is written before it.
- **Security note S9:** trained on the notebook's "small sample" datasets → resulting model is **non-commercial personal use** only (mixed dataset licenses, per the notebook's own warning).
- User will self-monitor the run and signal when to resume; the auto check-in was cancelled at user request.
- Created **`docs/Claudette_TOGAF_Assessment.docx`** — TOGAF conformance assessment + prioritized gap analysis (new deliverable, this session).
- **Next (on resume):** collect `klau_dette.onnx` (+ shared `melspectrogram.onnx` / `embedding_model.onnx`) → `android/app/src/main/assets/models/` → implement openWakeWord ONNX inference in `OpenWakeWordDetector` (Phase 1b) → device test.

### 2026-07-24 — Session 7: Rename Claudette → "Nova" + 13k model integrated + Phase 1b inference
- **Identity change:** assistant renamed **Claudette → "Nova"** ("Neural On-demand Voice Assistant"). `Persona.kt` system prompt rewritten so she introduces herself as Nova and states the acronym when asked. User-facing strings across `MainActivity` / `WakeWordService` / `ClaudetteApp` / `SettingsActivity` updated to "Nova" (title, "Listening for Nova", notification text/channel, "Nova settings"). **Internal identifiers left unchanged** — package `com.duchock.claudette`, class names, prefs keys, and the wake-model filename `claudette.onnx` all stay as-is to avoid a churny refactor. So "Nova" is the product/spoken name; "claudette" persists in code paths.
- **Phase 1b done — openWakeWord inference implemented.** `OpenWakeWordDetector.kt` now runs the real 3-model ONNX streaming pipeline: `melspectrogram.onnx` -> `embedding_model.onnx` -> wake model (`claudette.onnx`); 1280-sample (80 ms) frames, mel window 76, 16-embedding wake window, input `[1,16,96]` -> output `[1,1]` probability, threshold 0.5, ~2 s cooldown. Ported faithfully from the Python reference.
- **13k model integrated but DEAD.** User trained a wake model on another computer at only **13,000 examples**, producing `nova.onnx` + `nova.tflite`; placed into `android/app/src/main/assets/models/` as `claudette.onnx`. On device the **wake word does not trigger** — peak wake score ~**0.0009-0.0011** even while speaking "nova" at the mic. Mic path verified healthy (temp debug amplitude meter in `AudioCapture` showed 150 -> 5757 on speech; temp peak-wake-score log added in `OpenWakeWordDetector`). Conclusion: the model is undertrained, not a mic/code bug. -> Retrain at higher example counts.
- **Security:** S7 (GitHub PAT pasted earlier) **still to be revoked** by the user. No new keys introduced.
- **Next:** retrain the wake model with far more examples (aim 30k-50k), swap the new `nova.onnx` in as `claudette.onnx`, rebuild, re-test.

### 2026-07-24 -> 25 — Session 8: Wake-model retraining marathon (40k -> 25k) — four runtime losses, root cause found
**Goal:** produce a *working* "nova" wake model to replace the dead 13k one, via the openWakeWord "simple" auto-training Colab, Claude driving through the Chrome tools.

**Notebook fixes (version drift) — now baked into the user's Colab copy:**
- Colab defaults to Python 3.12 but `piper-phonemize` only ships a **cp311** wheel -> pinned the Colab runtime to the **2025.07** image (Python 3.11).
- `rhasspy/piper-sample-generator` restructured upstream (`generate_samples` moved/renamed, signature changed) -> pinned the clone to commit **`213d4d5`** (last commit with the flat `generate_samples.py` + old signature).
- PyTorch >=2.6 flipped `torch.load(weights_only=True)` by default -> `UnpicklingError` on the Piper voice model. Fixed via a top patch cell setting **`os.environ["TORCH_FORCE_NO_WEIGHTS_ONLY_LOAD"]="1"`** *and* monkeypatching `torch.load`. The **env var is the key** — sample generation runs in a **subprocess** the monkeypatch alone doesn't reach.
- Commented out the `tensorflow-cpu` / `tensorflow_probability` / `onnx_tf` / `onnx2tf` install stack (only needed for the optional `.tflite` export; those installs re-trigger the numpy "restart runtime" loop).
- Runtime = **T4 GPU**. Standing rule during a run: the recurring **"Restart session" / backports** popup -> always **Cancel**, never Restart (Restart wipes the runtime). The AudioSet `.tar` 404 is harmless/expected.

**What happened — the pipeline works end-to-end, but the runtime was lost at the finish four times:**
1. **40k run #1** (user out): completed features + main training (100%), but the **runtime was recycled during the final phase** before `nova.onnx` could be downloaded; reconnect -> fresh empty runtime -> total loss.
2. **40k run #2:** restarted; lost again to a disconnect near the end.
3. **25k run** (home, unstable Wi-Fi): sliders dialed down to **25,150 examples / 25,800 steps** to shorten exposure. Wi-Fi dropped long enough that Colab reclaimed the runtime -> loss.
4. **25k run #2** (home, on mobile **hotspot** next to him — a stable pipe): ran cleanly to **main training 100% (25,799/25,800)** and into the short 2nd phase (~5%), then the frontend froze and the runtime was gone; reconnect -> fresh empty runtime -> loss again.

**Root cause identified:** the **"Automatic saving failed — updated remotely or in another tab"** banner was up the *entire* final run -> the notebook was **open in two browser tabs at once**. Colab lets only one tab own the runtime, so the two fought over the connection, desyncing the display and dropping the runtime right at the buzzer. **This — not the user's network — is the most likely killer** (hotspot was stable and adjacent).

**Model verification (avoids a false win):** an auto-downloaded `nova.onnx` (206,276 B) + `nova.tflite` were found in `Downloads`. Verified on-device: **byte-identical (MD5 `b877...`) to the `claudette.onnx` already installed in the app**, timestamp matching the **13k import** — i.e. the **same dead 13k model**, NOT a product of today's runs. (Structurally valid: input `[1,16,96]` -> output `[1,1]`, passes the ONNX checker — just undertrained.) The older `klau_dette.onnx` (MD5 `c927...`, 07-22) is a distinct earlier model. **Net: no good model was produced today; the app still holds the dead 13k model.**

**Plan for the next attempt (should land cleanly):**
- **Exactly ONE Colab tab open**, nothing else pointed at the notebook — removes the two-tab connection fight (probable root cause).
- Add a **Google Drive mount + save** at the end of the notebook so `nova.onnx` persists to Drive the instant it's written — a fluke disconnect can no longer rob us. (Needs one Google "Allow" click from the user at run start; Claude cannot/should not click it.)
- Keep the machine awake + on a stable connection for the full run; be present for the final ~10 min (the download).
- Sliders: 25k is workable and shorter; 30k-50k is the notebook's recommended sweet spot if sensitivity still feels weak.
- **Swap procedure when a good `nova.onnx` lands:** replace `android/app/src/main/assets/models/claudette.onnx` with the new file **keeping the `claudette.onnx` filename** (the `WW` constant in `OpenWakeWordDetector` expects it) -> rebuild -> test "nova".
- **Fallback:** build the model on a **guaranteed-uptime work VM** (sidesteps the disconnect problem entirely). NOTE: **Claude (this personal account) is not to be used in the work environment** — per the user, work and personal projects stay separate. A work-VM run is done solo; these docs are written to be run without Claude.
- **Cleanup when verified working:** remove the temp debug logs (`DBG mic peak amplitude` in `AudioCapture`; `DBG wake score peak` in `OpenWakeWordDetector`).

**Still open:** S7 — revoke the pasted GitHub PAT. Docs (this log + Word spec/TOGAF) to be pushed to GitHub on the next push.

### 2026-07-27 -> 28 — Session 9: Work-VM run outcome + new Nova requirements (memory, location, search, worldview, personalization, personality)
**Work-VM / Drive-backup training attempt — no model recovered.**
- User set up a separate Colab ("Training Nova", in his personal Google Drive -> Colab Notebooks) and ran the 25k build via the Claude-in-Chrome extension on a work machine, instructing it to copy the finished `nova.onnx` to Google Drive as a safety net.
- Checked the user's Drive directly (Google Drive connector): global title search for nova/onnx/claudette, the Colab Notebooks folder, and most-recent files — **no `.onnx` landed in Drive.** The notebook's last save froze ~22:16 on 07-27; the Drive-copy step did not stick (likely the Drive mount/auth never completed, or the runtime dropped before the copy). User also thinks he "borked" the session trying to reconnect.
- **Status:** treat as another non-result; if the model finished it was on the Colab runtime, which has almost certainly been idle-recycled since. **Plan:** re-run clean in the morning with Claude — single tab — and this time **download `nova.onnx` directly the instant it exists** (do NOT rely on a post-hoc Drive-copy). Lesson reinforced across all attempts: the only reliable capture is a direct download at the moment of creation.

**New product requirements (captured as decisions D12-D17; new security items S10-S12). Blocked on the wake word for the full APK loop, but the persona is implemented now.**
- **D12 — Persistent memory.** Nova remembers across sessions, not just recent turns (extends D9). Design: on-device, encrypted memory store (durable facts + preferences + a rolling conversation summary) injected into context; user can view/clear. -> **S11 (new):** memory / personal-profile store must be encrypted at rest (Keystore-backed) and user-wipeable; sensitive if the device is compromised.
- **D13 — Location awareness.** APK uses Android location services so Nova knows where John is (fused location, on-demand rather than constant tracking; ACCESS_COARSE/FINE_LOCATION). Feeds context (near-me, weather, travel). -> **S10 (new):** location is sensitive PII — keep on-device, use minimally/as-needed, never persist or transmit beyond the immediate query, user can disable.
- **D14 — Web search.** Nova can search the internet to enrich conversations and stay current. Design: Claude tool-use with a search tool backed by a search API (Brave / Bing / SerpAPI / Google Programmable Search — pick during build). -> **S12 (new):** adds back an API credential (store encrypted per S1) and egresses queries to the search provider; disclose in-app.
- **D15 — Worldview / no partisan slant.** John is a God-fearing Christian conservative; Nova respects his faith and values, never talks down to or argues against them, carries **no partisan agenda**, and does not push political opinions. Implemented as *fair, honest, and respectful* — she gives the straight picture rather than swapping one bias for another (a companion that only flatters serves the user worse). Encoded in `Persona.kt`.
- **D16 — Personalization / learn about John.** Nova actively learns John professionally and personally over time (ties to D12) and uses it to tailor guidance and insight. Builds an on-device personal profile (see S11).
- **D17 — Personality: humor + tasteful playful charm.** Warm, witty, genuinely funny, with an occasional light, tasteful flirtatious spark — never crude, explicit, or overdone; helpful first. Encoded in `Persona.kt`.

**Implemented this session:**
- Rewrote `Persona.kt` (the system-prompt "soul") to encode D15, D16, D17 and to make Nova aware of her D12/D13/D14 capabilities, so her voice/character are right from day one. Everything lives in one tunable string; the humor/flirt and worldview lines are plain English the user can dial to taste.

**Deferred (Phase 2b/3, and/or once the wake word lands) — deliberately not rushed:**
- Build the memory + personal-profile store (D12/D16, S11), the location provider + context injection (D13, S10), and the web-search tool (D14, S12) as real modules during the build.
- Still outstanding: land a working `nova.onnx`; revoke the GitHub PAT (S7); sync the Word spec/TOGAF to the Nova rename + these requirements.

**User note:** more requirements to come as he thinks of them.

### 2026-07-28 — Session 9 addendum: off-device backup + restore of Nova's memory (D18)
- User wants Nova's **personal profile (D16) and conversation history (D12)** backed up off-device so they survive a device replacement — proposing GitHub as the store and a periodic (weekly) backup job.
- **D18 — Off-device backup & restore.** Nova periodically backs up her memory (profile + history) to a user-controlled remote and can restore it onto a new device.
  - **Encrypt client-side, always (non-negotiable).** This is the most sensitive data in the app — everything Nova knows about John, plus every conversation. Encrypt on-device with a passphrase-derived key John controls BEFORE it leaves, so the remote only ever holds ciphertext and a repo leak / token compromise is harmless. -> **S13 (new):** off-device memory backup — mandatory client-side encryption; private store only; guard the write credential.
  - **Store choice.** GitHub is viable and fits the single-source-of-truth instinct, with a real bonus: the profile gets version history (watch it evolve / roll back). Caveats: (a) it needs a write token on the phone — use a fine-grained PAT scoped to a **separate private backup repo only**, stored encrypted (per S1/S7), entered in Settings, never hardcoded; (b) keep transcripts compact or git history bloats. **Alternative lean: Google Drive** is arguably cleaner for a data blob (OAuth instead of a stored write-all token; no repo bloat) since John already lives in Google. Decision deferred to build; either works *with* client-side encryption.
  - **What to back up:** profile + rolling memory summary (small, versioned) always; full transcripts as an encrypted archive (GitHub or Drive), optional/capped to control size.
  - **Cadence:** weekly is fine for the slow-changing profile; **nightly (or on-meaningful-change)** for conversation history so a dead device costs at most a day, not a week. Android **WorkManager** for the periodic job (reboot-safe, battery-aware) + a manual "back up now" button in Settings.
  - **Restore is first-class:** new device -> enter passphrase -> pull latest -> decrypt -> Nova remembers John. The backup is only as good as the restore.
- Deferred to the build (with the D12/D16 memory modules); not rushed.

### 2026-07-28 — Session 9 note: future full "claudette" -> "nova" rename (D19, deferred)
- User wants, eventually (explicitly NOT tonight), to replace **all** remaining "claudette" with "nova" across the project.
- **D19 — Full rename to Nova (deferred).** This is a real refactor, not a blind find-replace. Handle with care:
  - **Package + namespace:** `com.duchock.claudette` -> `com.duchock.nova` — every package/import line, the `java/com/duchock/claudette/` directory tree, `applicationId`/`namespace` in build.gradle, and AndroidManifest. Use Android Studio's Rename refactor, not text search-replace.
  - **Class names:** `ClaudetteApp` -> `NovaApp`, etc.
  - **Model filename:** `assets/models/claudette.onnx` AND the `WW` constant in `OpenWakeWordDetector.kt` must change together (both currently "claudette.onnx").
  - **Stored data:** any SharedPreferences file names / keys containing "claudette" -> rename WITH a migration, or the user's saved API keys/voice/settings get orphaned on update.
  - **Repo + docs:** repo (github Jduchock/Claudette), workspace folder `C:\a_claudette`, doc filenames (`Claudette_Spec.docx`, `CLAUDETTE_LOG.md`) — optional, but part of "all".
  - **Best sequencing:** do it AFTER the wake word works (don't rename a moving target), via the IDE refactor + a clean rebuild/test to catch breakage, as its own isolated commit (easy to review/revert).


### 2026-07-30 - Session 10: shoe-inventory demo (Option 2 - on-device tool-use)
- **Purpose:** Nova is a proof-of-concept to pitch leadership for funding a full project. This build makes her a hands-free **store-associate helper** for a live exec demo. If leadership bites, a separate project spins up for the real backend.
- **Data:** `docs/Shoe_Inventory_Simulation_4_Stores_1.xlsx` - SYNTHETIC footwear inventory, 4 stores, ~524 size-level SKUs. Not real retailer data; no confidential info. Home store **0146 Trussville Marketplace** (Hibbett); nearby = 1382 Cartersville, 2071 Southaven (City Gear, deepest), 3719 Florence.
- **Approach = Option 2 (chosen deliberately):** Nova queries an on-device copy of the data via real Anthropic **tool-use**. Not scalable, and that's fine - the goal is to show the *end-user experience*, not the plumbing. Counts/locations are computed **deterministically in Kotlin** (never model arithmetic), so her numbers are always right in front of the suits.
- **Backend (deferred, NOT built tonight):** production source will be an API *or* an MCP server fronting it (undecided), over millions of rows (thousands of shoes x thousands of stores). Plan we aligned on: fast indexed point-lookups stay live; expensive chainwide rollups get **pre-aggregated/cached** behind the layer. Demo tool contract (check_stock / store_rollup / pricing_lookup) is intentionally shaped to survive that swap - demo is the contract, not throwaway.
- **Trigger:** demo mode turns on when Nova hears **"are you ready for a demonstration"** (and off on "end the demonstration"). Process-level flag (a new ConversationManager is built each wake, so state must outlive one conversation).
- **Behavior spec (John's, verbatim intent):** at 0146 = tell them how many + exact back-room shelf location (zone/bay/shelf/bin + reach). Not at 0146 but nearby = name the store + count and **offer to hold**. Nobody = say so + closest alternative.

**Implemented this session (new demo/ package + wiring):**
- `assets/inventory_demo.json` (~363 KB) - compact export of the spreadsheet: 4 stores, 524 items with per-store on-hand (null = not carried, 0 = OOS) + 0146 backroom locations folded in.
- `demo/InventoryRepo.kt` - loads the JSON once at service start (off main thread), deterministic query methods (stock/rollup/pricing, fuzzy model-name matching, exact size).
- `demo/InventoryTools.kt` - 3 Anthropic tool schemas + executor returning compact JSON.
- `demo/DemoMode.kt` - trigger/exit detection + demo system-prompt addendum (home 0146, others nearby, shelf-vs-hold rules).
- `net/ClaudeClient.kt` - added respondWithTools(): full tool_use/tool_result loop (max 4 rounds, then drops tools to force a text reply). Original respond() untouched.
- `conversation/ConversationManager.kt` - routes the turn through tools when demo mode is active; forces **Sonnet** in demo for snappy round-trips.
- `service/WakeWordService.kt` - warms the inventory dataset at startup.
- No new Android permissions; no secrets touched.

**Verified:** re-ran all 3 tools' filter logic in Python against the shipped JSON - all four demo query types return correct answers (in-stock+location, not-here+nearby-hold, brand rollup, clearance pricing). Kotlin compile must happen in Android Studio (no Android SDK in the cloud session).

**Ops note:** device bridge dropped mid-session during the ClaudeClient.kt write; verified afterward the file was untouched (clean original) and re-wrote it. No work lost.

**Still open:** build+run in Android Studio to confirm compile; git add -A / commit / push (mel fix + always-listen from S9 are still uncommitted too); revoke GitHub PAT (S7); D19 rename still deferred; D12-D18 real modules still deferred.


### 2026-07-30 - Session 11: persistent memory (D12/D16) + inventory lookup hardening
**Two asks: (1) make Nova remember across sessions; (2) fix brittle inventory lookup (SKU/UPC/Item ID + fuzzy descriptions, no false out-of-stock).**

**Persistent memory (D12/D16) - built, encrypted from line one (S13):**
- Root cause she forgot: conversation history was RAM-only (12-turn buffer, wiped on 5-min idle / app restart). Nothing was persisted.
- New `memory/` package:
  - `MemoryStore.kt` - EncryptedSharedPreferences (Android Keystore, same scheme as SecretStore) holding two small text fields: `profile` (durable facts) + `summary` (rolling narrative). `memoryBlock()` injects them into Nova's system prompt on the normal (non-demo) path.
  - `MemoryUpdater.kt` - "reflection": after each conversation a cheap Sonnet call distills the new turns, MERGES into profile, refreshes summary, saves. Skipped in demo mode so store Q&A never pollutes John's personal memory. Never stores secrets.
- Wiring: `ConversationManager` injects memory + adds `reflect()`; `WakeWordService` loads memory at startup and fires `reflect()` in the background after each convo (does NOT delay re-listen).
- **Space:** text only - a few KB for the profile/summary; even years of chat = low tens of MB. Non-issue. (Audio is never stored.)
- **Limits (told John):** the real constraint is the context window, not disk -> that's why we distill instead of replaying transcripts; distillation can lose nuance (raw source stays authoritative if we add a transcript log later); memory must revise, not just accumulate; privacy is the big one -> encrypted at rest, and any off-device backup (D18) must be client-side encrypted first.
- Next possible step: add a transcript log (SQLite/Room) under the profile layer + an explicit "Nova, remember that..." command. Deferred.

**Inventory lookup hardening (demo robustness):**
- Regenerated `inventory_demo.json` to ADD `upc` + `itemId` per item (SKU was already there). 524 items.
- `InventoryRepo` rewritten: exact lookup by SKU / UPC / Item ID (identifier param or embedded in the query), plus token-based fuzzy matching over brand+model+description+color that is word-order independent, stopword-stripped, and grey/gray-normalized. Color is folded into the tokens so partial/oddly-phrased colorways match.
- `InventoryTools.check_stock` now returns a pre-computed `summary` (homeInStockSizes, homeUnits, nearbyAvailability, anyInStock) so Nova reads the answer instead of eyeballing rows.
- `DemoMode` addendum: never declare out-of-stock unless anyInStock is false; loosen a failed search before giving up; may search by ID or loose description.
- **Bug it fixes:** John looked up SKU HB-4101174 ("Nike Kobe 5 Protro White/Varsity Purple"). It is stocked 5-deep at 0146 (and all 4 stores) - her "nothing in stock" was a pure narration misread, and "purple" vs "varsity purple" failed on substring color matching. Verified in Python against the shipped JSON: SKU/UPC/Item ID lookups, "white and purple", bare "purple", and grey->gray all now match, and the summary always reports homeUnits=5 / anyInStock=true.

**Build:** needs a fresh Gradle sync (new `memory/` package + new demo fields). No new dependencies (security-crypto already present). Kotlin compile must happen in Android Studio.
**Still open:** build+run to confirm compile; commit/push (S9 + S10 + S11 all uncommitted); revoke GitHub PAT (S7); D19 rename deferred; D13/D14 (location, web search) + D18 backup still deferred.


### 2026-07-31 - Session 12: location awareness + nearby places (D13)
**Nova now knows where she is and can answer "what's around me." Scope chosen: location awareness + nearby places; updates: on-demand (not continuous).**

- **Free layer (no key):** fused location (Google Play Services) fetches a FRESH fix only when a question needs it, reverse-geocoded (Android Geocoder) to address/city/area. Covers "where am I", "what city", near-me context.
- **Places layer (uses the Google Maps key):** Google Places API (New) Text Search, biased to current location, returns nearby places with name/address/distance/rating/openNow, nearest first. This is what needs the key + billing.
- **New files:** `location/LocationProvider.kt` (on-demand fix + geocode), `location/LocationTools.kt` (get_location + nearby_places tool schemas/executor), `net/PlacesRepo.kt` (Places New Text Search + haversine distance).
- **Foundation:** added `com.google.android.gms:play-services-location:21.3.0`; `GOOGLE_MAPS_API_KEY` BuildConfig from local.properties; manifest permissions ACCESS_FINE/COARSE/BACKGROUND_LOCATION; `Secrets.googleMapsKey` + `SecretStore.KEY_GOOGLE_MAPS`; a Google Maps key field in Settings.
- **Wiring:** `ClaudeClient.respondWithTools` exec is now `suspend` (location calls are async); the NORMAL (non-demo) conversation path now attaches the location tools (memory still injected); `WakeWordService` inits LocationProvider + PlacesRepo at startup; `MainActivity` requests fine/coarse location with the mic prompt, then a best-effort background ("Allow all the time") follow-up; Persona updated to use the real tools.
- **Privacy (S10):** on-demand only, no continuous tracking, nothing persisted, reverse-geocode is local. Background-location permission lets her answer while running in the background; the foreground-service type stays `microphone` only (kept location OFF the FGS type so a missing location grant can never crash the always-on mic service).
- **Setup the user needs (told them):** key goes in local.properties as GOOGLE_MAPS_API_KEY (done); Google Cloud must have **Places API (New) enabled** + **billing on** or nearby_places errors (get_location still works free).
- **Build:** needs a Gradle sync (new dependency + new packages). Kotlin compile in Android Studio.
- **Still open:** build+test location; D14 web search is the next capability; commit/push (S9-S12 all uncommitted); D18 backup + D19 rename still deferred; revoke PAT (S7).


### 2026-07-31 - Session 12 note: Teams / Outlook (M365) integration - PARKED (D20)
- User asked to give Nova access to Microsoft Teams + Outlook. Flagged before building: (a) use OAuth ONLY, never raw M365 credentials (ROPC is deprecated, breaks with MFA, exposes the password; will not handle raw passwords); (b) wiring a WORK tenant in is the personal/work mix his employer discourages and would need admin consent (usually blocked on corporate tenants).
- **D20 - M365 (Teams/Outlook) integration: PARKED pending employer approval.** User agreed to get approvals first (good call). When revisited: Microsoft Graph via MSAL (OAuth auth-code flow) + an Entra app registration, least-privilege Graph scopes, tokens only (never passwords), and any send/post gated behind explicit confirmation.
- **S15 (new, latent):** an M365 integration would route work communications through a personal app and a personal Anthropic key - do not build until approved by employer/IT; OAuth + admin consent + revocable tokens only.
- Not built. D13 location still pending build/test on the user's side; S9-S12 work still uncommitted.


### 2026-07-31 - Session 12 note: bank / expense access - PARKED (D21)
- User wants Nova to answer questions about checking-account expenses (read-only; she never moves money). Laid out two paths: (A) live link via an aggregator (Plaid/Teller) - needs a backend to hold the aggregator secret (can't ship in the APK, S1) plus an account/approval/cost; (B) export-based on-device - user drops CSV/OFX exports, Nova queries them locally like the inventory demo. Private, no backend, not live.
- User is not keen on a brokerage/aggregator; asked whether banks offer personal-account API keys. Reality: US retail banks generally do NOT expose consumer API keys to individuals - open banking runs through aggregators or emerging FDX standards - so Option B is the clean personal route.
- **D21 - Bank/expense access: PARKED.** If revisited, default to Option B (export-based, on-device, encrypted at rest, strictly read-only, no money movement ever). -> **S16 (new, latent):** financial data is the most sensitive category yet - read-only, encrypt at rest, and note that relevant transactions egress to the Anthropic API at query time.


### 2026-07-31 - Session 12: "Give Nova a Picture" - feed-a-photo vision (D22)
- Chose stills over live camera (battery, privacy, and Android's background-camera limits). Video dropped - frame-sampling a clip is too resource-heavy for the payoff.
- **Flow:** MainActivity "Give Nova a Picture" -> system camera (ActivityResultContracts.TakePicture + FileProvider, no CAMERA permission needed) -> review screen with **Confirm / Retake / Cancel** -> on Confirm the image is saved to app-internal nova_media/ (ImageStore, staged for the future D18 check-in upload) AND handed to the service for analysis.
- **Vision path:** `ClaudeClient.respondWithImage` (base64 image content block); `ConversationManager.handleImage` analyzes once (Sonnet) and folds Nova's description into the SHARED conversation history as text, so follow-up questions keep context without re-sending the image (cost control). `WakeWordService` gains `ACTION_ANALYZE_IMAGE` + a persistent `currentConversation` (reused across wakes and photos) and speaks her read via ElevenLabs; her description also flows into long-term memory on reflection.
- **New files:** `media/ImageStore.kt`, `res/xml/file_paths.xml`, FileProvider entry in the manifest.
- **Storage/backup note:** raw images live in sandboxed app-internal storage; the actual off-device upload rides on D18 (not built). Raw media is heavy vs text memory - when D18 lands, decide raw-media-backup vs analysis-only, and client-side encrypt first (S13).
- **Build:** Gradle sync + rebuild (new files + manifest provider).
- **Still open:** build/test the picture flow; D18 backup (now also covers images); commit S9-S12 + this; D14 web search; D19 rename; revoke PAT (S7).


### 2026-07-31 - Docs refresh + threat-register note
- Spec -> v0.4 and TOGAF -> v1.2: added Location & Nearby Places (D13) and Vision / "Give Nova a Picture" (D22) sections, refreshed FRs (now FR-1..FR-20), tech stack, permissions, roadmap, and the threat register; noted the parked M365 (D20) and bank/expense (D21) integrations as conscious deferrals.
- **S14 (assigned): photos John feeds Nova** are stored on-device (sandboxed app-internal storage) and sent to Claude only at analysis time. Mitigation: no gallery write; encrypt before any off-device backup (S13). This fills the S14 slot; full register is now S1-S16 (S15 = M365 latent/parked, S16 = bank/expense latent/parked).


### 2026-07-31 - Session 12: KJV Bible reading companion (D23)
- **KJV is public domain** (Crown-copyright only in the UK), so the whole text is bundled on-device: `assets/bible_kjv.json` — 66 books, 31,102 verses (Genesis–Revelation), translators' bracketed words de-bracketed so TTS reads cleanly. Sourced via the `pythonbible-kjv` package, verified.
- **New `bible/` package:**
  - `BibleRepo` — loads the KJV, verse/chapter access, sequential next() (crosses chapters and books, stops at Revelation), and reference parsing ("John 3:16", "Psalm 23", "1 Corinthians 13:4", book-only). Verified in Python.
  - `BibleBookmark` — persists where John left off (resume next time). Plain prefs (not sensitive).
  - `BibleNotes` — passage-aware study notes keyed by book:chapter, ENCRYPTED at rest (personal reflection); surfaced when a passage recurs.
  - `BibleTools` — discussion tools Nova calls: bible_lookup (quote exact KJV), bible_recall_notes, bible_save_note, bible_where. Merged with the location tools on the normal conversation path.
  - `BibleControl` — detects a spoken "read the bible / continue reading / read <ref>" command in an utterance.
- **Reading loop (in WakeWordService):** reads verse-by-verse via ElevenLabs, announces book+chapter at each chapter start, and SAVES THE BOOKMARK as it goes so a stop/interrupt never loses the place. Wake capture stays ON during reading so "Nova" interrupts hands-free (KJV never says "Nova", so no self-trigger). Started by voice ("read the bible", "continue reading", "read John 3") or the "Read the Bible" button (ACTION_START_READING); stopped by the STOP button (ACTION_STOP_READING) or by saying "Nova".
- **STOP-word decision (John asked about training):** v1 uses the **STOP button (instant)** + the **"Nova" wake word as a hands-free interrupt** (pauses reading, opens a chat to say "stop"/"explain that"/"keep going"). Chose this over per-verse spoken-"STOP" detection because that would flap the mic on/off each verse — the exact sloppiness John had me roll back earlier. A dedicated always-listening **"STOP" wake model is the upgrade** (same free Colab as "Nova", with a self-trigger guard); deferred unless John wants instant mid-verse hands-free stop.
- **UI:** "Read the Bible (continue)" and "STOP reading" buttons added to MainActivity. Persona updated so Nova knows she can read + discuss Scripture and use the bible tools.
- **Build:** Gradle sync + rebuild (new `bible/` package + ~4.2 MB asset; no new dependencies). Kept the project compilable.
- **Still open:** build/test; optional trained "STOP" model; D14 web search; D18 backup; D19 rename; commit (S9-S12 + all of tonight uncommitted).

### 2026-08-04 — Session 13: MFCS integration + real-data refit of the inventory demo
**Connected Nova's demo to real Oracle MFCS (PRD1) data and rebuilt the demo dataset from it. Original synthetic workbook left intact.**

MFCS connection (new `mfcs/` folder):
- OAuth2 client_credentials against Oracle IDCS works. Base `…/rgbu-rex-hibb-prd1-mfcs/MerchIntegrations`, scope `rgbu:merch:MFCS-PRD1`. Config in `mfcs/.env` (git-ignored; `.env` cannot be written by remote tools — created locally from `.env.example`).
- Schema-explorer (`mfcs/explore_mfcs_schema.py`) swept all 87 GET endpoints (limit=1). 34 return data; **48 are HTTP 400 "API not enabled"** (server-side enablement needed). See `mfcs/schemas/FINDINGS.md`.

Test stores (`mfcs/Claudette_MFCS_Test_Stores.xlsx`):
- 5 main = tightest real cluster chain-wide: **1511 Homewood (HOME), 33 Western Hills, 54 Sports Additions, 513 Bessemer Rd, 966 Palisades** — all Birmingham, District 25 / Region 1, ≤6.4 mi apart.
- Outlier (negative test) = **107 Sebring FL** (~520 mi, Region 3).

New data source (`docs/Nova_MFCS_Footwear_Inventory.xlsx`) — real footwear across the 6 stores, same tab/column dimensions as the synthetic workbook. 35,283 SKUs / 5,634 styles. REAL: brand/model/color/size/dept/MSRP/retail/on-hand/**image URLs** (Amplience CDN, ~98%). MOCKED: backroom Zone-Bay-Shelf-Bin, unit cost, last-received. NOT available: UPC (blank), class display-names (merch-hierarchy name API disabled in PRD1).

Demo refit (fresh mobile load ready):
- `assets/inventory_demo.json` regenerated from MFCS — 7,265 in-stock SKUs, 6 stores, home 1511 (4.4 MB). Same schema; home backroom `loc` mocked.
- `demo/InventoryRepo.kt`: `HOME="1511"`, `STORE_IDS=["1511","33","54","513","966","107"]`.
- `demo/DemoMode.kt`: ADDENDUM rewritten for home 1511 + 4 nearby Birmingham stores; 107 Sebring flagged distant (transfer, never same-day hold).
- `demo/InventoryTools.kt`: wording updated (six stores, home 1511), `location0146` → `homeLocation`. `.bak0146` backups beside each edited file.
- **Kotlin compile still pending in Android Studio** (no SDK in the cloud session).

Camera feature (D22): MFCS carries real product image URLs per colorway (~98%) — photo→SKU compare now feasible; wiring is a follow-up.

Demo runbook: `docs/Nova_Demo_Runbook.docx` — scripted, verified walkthrough for the leadership demo.

**Open / next:** enable the 48 gated MFCS APIs (server side); backfill UPC + class names; build photo→SKU compare; build+run in Android Studio to confirm compile; revoke GitHub PAT (S7); D19 full claudette→nova rename still deferred.


### 2026-08-05 — Session 14: Demo-mode photo intelligence, conversation continuity, memory, web search OFF
**Fixed the camera "loses focus" problem for the store demo, made photos part of the live conversation, hardened short-term memory, and (after repeated 400s + slowness) fully disabled web search.**

Demo-mode photo handling (the core fix):
- Root cause: `ConversationManager.handleImage()` ignored demo mode — every photo ran the normal persona with a generic "describe what you see" prompt, so mid-demo she broke character (the "worn socks" aside) and never matched to inventory. `handle()` (text) already branched on demo; only the image path was missed.
- `handleImage()` now branches on `DemoMode.active`. In demo mode a photo means **match this item to inventory** and **always be complimentary** (never remark on wear/dirt/feet/socks; a worn shoe is a reason to sell a fresh pair).
- **Two-phase (for speed):** (1) ONE fast **Sonnet** vision pass identifies the item as a short text line (image read exactly once, no tools); (2) a **text-only** inventory lookup + spoken answer. This removed the big time sink (an image re-processed on every tool round) and dropped the photo turn off **Opus** (overkill). The identified item is folded into the conversation history marker so spoken follow-ups know what the picture was.
- Prompts live in `demo/DemoMode.kt`: new `IDENTIFY_SYSTEM` / `IDENTIFY_PROMPT` (phase 1) and a rewritten `IMAGE_ADDENDUM` (phase 2, compliment + match rules).

Conversation continuity (photos are now real turns):
- Rebuilt `WakeWordService` so a photo runs **inside** the conversation loop instead of as a dead-end one-shot. After she describes a photo the loop **listens again automatically** — no "Nova" needed to respond (fixes the "she never re-enabled the mic" bug).
- A photo handed in **mid-conversation** is queued (`pendingImagePath`) and folded into the SAME conversation with full context; the in-progress listen is interrupted instantly via a new `SpeechToText.interrupt()` (implemented in `AndroidSpeechToText`) rather than waiting out the recognizer timeout.
- `onWakeDetected()` refactored into `beginConversation(fromWake)`; shared loop + `speakImageTurn()` helper.

Short-term memory (chosen: in-memory, longer):
- Moved the conversation to a **process-level `heldConversation`** so it survives a mic off/on within the same app run (previously `ACTION_STOP` destroyed the service and wiped context — "turn mic off/on and she forgets the picture").
- Idle-reset window widened **5 → 30 min** (`ConversationManager.idleResetMs`). Trade-off accepted: still lost if Android kills the app process (disk-persistence is the future upgrade if needed).

Demo indicator (on the app):
- New **DEMO MODE** banner on the main screen with readiness lines: **shoe database loaded ✓ / loading**, and **ready for a demo picture / matching a photo**. Driven by new observable `DebugStatus` fields (`demoMode`, `inventoryLoaded`, `analyzingImage`). Also an **`Err:`** line showing the last API error (`DebugStatus.lastError`).

Web search — added, then DISABLED (until further notice):
- Briefly wired Anthropic's server-side `web_search` (allow-listed to hibbett.com + competitors + footwear/sneaker sites, mirrored in Console) so she could confirm make/model and check the site. Image + `web_search` consistently threw **HTTP 400**, and it felt slow, so per decision it is **OFF everywhere**: removed from the text demo path and the photo path; `respondWithTools` fallback removed.
- `ClaudeClient.webSearchTool()` + `WEB_SEARCH_ALLOWED_DOMAINS` are **kept but uncalled** (dormant) with a comment — re-enabling is a one-liner in `ConversationManager`.
- Prompts reworded: hibbett.com is offered only as an **order-online option** ("we can order that on hibbett.com") with no live price/stock claims; competitors are static awareness, no lookups. `respondWithImageAndTools` is now **unused** (left in place, harmless).

Diagnostics added this session:
- `DebugStatus.lastError` surfaced on the Status card; verbose logging under tags **`ClaudeClient`** (POST model/tools, `stop_reason`, full 400 body), **`NovaConvo`** (`ConversationManager`), **`NovaTools`** (`InventoryTools`), **`WakeWordService`** (turn/utterance/image).
- **Logcat filter:** `package:mine (tag:ClaudeClient | tag:NovaConvo | tag:NovaTools | tag:WakeWordService | tag:InventoryRepo | tag:AndroidStt)`.

Files touched: `conversation/ConversationManager.kt`, `demo/DemoMode.kt`, `net/ClaudeClient.kt`, `service/WakeWordService.kt`, `speech/SpeechToText.kt`, `speech/AndroidSpeechToText.kt`, `util/DebugStatus.kt`, `MainActivity.kt`, `demo/InventoryTools.kt`.

**Open / next:** build + run in Android Studio to confirm compile (no SDK in the cloud session); if 400s persist with web search off, capture the `Err:`/`ClaudeClient` log line; consider disk-persistence of short-term memory if OS-kill memory loss bites; refresh the formal `Claudette_Spec.docx` / TOGAF docs to cover demo-photo intelligence + web-search-disabled; remove the now-dead `respondWithImageAndTools`; stale `.git/index.lock` should be cleared before committing; D19 claudette→nova rename still deferred.
