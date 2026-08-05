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

