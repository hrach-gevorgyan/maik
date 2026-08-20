# Changelog

Written for people, not for parsers. Newest first.

Versions follow [semantic versioning](https://semver.org): the middle number moves
when maik gains something, the last one when something gets fixed.

---

## 1.6.0 — 21 August 2026

**Two models, both verified against their own bundles before shipping.**

- **Qwen2.5 1.5B joins as a backup** — it answers straight away instead of reasoning
  first, so its first word arrives sooner. DeepSeek-R1 1.5B stays the default.
- **SmolLM, TinyLlama and Phi-4-mini are gone.** TinyLlama returned empty replies on
  device. Phi-4-mini at 3.8B ran the phone hot enough to throttle, took over a minute
  per answer and then locked up; a size ceiling now stops anything like it being
  offered again.

### Fixed

- **Garbled text.** A mangled emoji was arriving as Latin characters — `ðŁĨ` is the
  bytes `F0 9F 86`, a four-byte emoji cut one byte short. Replies are now decoded
  back through the byte table, and incomplete characters are dropped rather than
  shown as gibberish.
- **The thinking indicator is quiet now** — a dot, a word and a timer, instead of
  streaming reasoning that you start reading and then watch get replaced by a
  different answer. The full reasoning is still there, folded away.
- **A finished download says so**, with the model named and a button, instead of
  leaving you on a screen of logs and a back arrow.

### Changed

- **Every model is now inspected before it can ship.**
  `tools/verify_models.py` reads each bundle's ZIP directory over range requests and
  checks its tokenizer, weights, prompt template, size and context window. It runs in
  CI, so the mistake that broke four releases cannot repeat.
- The golden test runs against the real default model rather than a small fixture.
- The README was rewritten from scratch; it had drifted into describing features that
  were removed and bugs that were fixed.

---

## 1.5.1 — 21 August 2026

**Replies no longer ramble into invented conversations.**

The models never stop when they should. They answer, emit their own end-of-turn
token as ordinary text, and then keep going — writing both halves of a conversation
that never happened until the token budget runs out. That is the trailing garbage in
1.5.0 and everything before it.

The runtime does not cut this off, so maik does: generation now stops the moment a
turn marker appears, and anything past it is discarded. Byte-level tokeniser
artefacts are translated back into the characters they stand for rather than left in
the text.

This was found by running the golden test on a real emulator rather than guessing,
and the test now asserts what a reader actually sees instead of what the engine
happens to emit.

---

## 1.5.0 — 21 August 2026

**Replies were nonsense because maik was formatting every prompt twice.**

Each `.task` bundle carries its own prompt template inside its `METADATA`, and the
engine applies it — DeepSeek uses `<|User|>`, TinyLlama `<|user|>
`, Phi-4-mini
`<|user|>`. maik was wrapping your message in a second, hand-written template on
top. The model saw nested markup, answered questions nobody asked, and leaked
byte-level tokeniser debris like `ĠðŁ` into the text.

Prompts are now sent as plain text. Every hand-written template is deleted. All
three models were verified by reading the template out of their bundles.

The golden test now checks the *answer*: it asks for the capital of France and
fails unless the reply says Paris, and rejects any reply containing tokeniser
debris. Fluent nonsense no longer passes.

### Fixed

- **The app crashed while warming up.** The GPU delegate crashes natively on some
  drivers, which no error handling can catch. It is now off by default and opt-in
  under Behaviour — and if the app dies while loading, it turns itself back off.
- **Deleting a model did nothing visible.** The list read the disk during drawing,
  which is not state Compose watches, so the row stayed put.
- **Returning from the download notification** dropped you on the conversation list
  instead of the download.
- **The icon had black fringing** on the letters. Transparent pixels carry black,
  and downsampling was blending it into every edge. It is now composited from an
  alpha mask, so black never enters the blend.
- **Light is the default theme**, as asked.

### Changed

- **The thinking switch moved into the chat**, under the messages — it changes what
  the next reply will be, so it belongs next to what you are about to send.
- **Haptics has a setting**, and every buzz in the app now respects it.
- **Settings reorganised**: Model, Instructions, Appearance, Behaviour, Storage,
  About — ordered by how often you touch them, each subtitle showing its current
  value rather than repeating its title.
- CI no longer runs the emulator twice per change.

---

## 1.4.1 — 21 August 2026

Trims the download back to **36 MB**. 1.4.0 came out at 68 MB because adding
emulator support for the golden test also added the emulator's processor
architecture to the APK everyone installs. Shipped builds now exclude it; local
debug builds still carry it so the test can run.

Nothing else changed. Same app, half the size.

---

## 1.4.0 — 21 August 2026

**A real device now has to prove the app works before anything ships.**

A new golden test boots an Android emulator in CI, downloads an actual model, loads
it into the engine and makes it answer a question. Releases are blocked on it. Every
failure of the last three versions lived exactly where unit tests cannot see —
between the bundle format and the runtime — and this is the check that sees it.

### Fixed

- **SmolLM failed to download**, and it was my validation rejecting a perfectly good
  file. These archives carry four bytes before the ZIP header, which Java's
  `ZipFile` refuses even though the engine reads them happily. Validation now scans
  the archive's tail for the entries it needs instead of parsing the container.
- **Other models could not be downloaded at all.** Tapping one in Settings marked it
  as selected and then did nothing visible, because nothing navigated to the
  download screen. Picking a model you don't have now takes you straight there.
- **The light theme hid the status bar icons** — the clock and battery stayed white
  on a white background. The system bars now invert with the theme.
- **The default theme is dark**, which is what maik is drawn for. "Follow the
  system" is still there if you prefer it.
- **The notification request now explains itself first**, in plain words, before
  Android's own box appears — and it says outright that declining changes nothing
  about the download.

### Changed

- **SmolLM is gone** from the app and **DeepSeek-R1 1.5B is the default.** SmolLM
  lives on as the golden test's fixture, where being tiny is a virtue.
- **Calmer motion.** Entrances were staggered down lists, which read as the app
  struggling rather than as polish. Rows now appear at once; movement is kept for
  the places it explains something — screen depth, press feedback, the send button
  becoming stop, the bar sweeping while a download connects.

---

## 1.3.0 — 21 August 2026

**The app works now. 1.1.0 and 1.2.0 did not, and this explains why.**

Both shipped models the engine cannot read. A `.task` bundle is a ZIP holding
`METADATA`, `TF_LITE_PREFILL_DECODE` and `TOKENIZER_MODEL` — that last entry is the
SentencePiece tokenizer. The `.litertlm` files those releases used contain no such
thing, which is exactly what "SentencePiece tokenizer not found" was telling you.
Before that, 1.1.0 also picked a GPU-only build that could not load when the GPU
was refused.

Every model is now verified: each bundle's ZIP directory was read before shipping,
and the tests refuse `.litertlm`, GPU-only and web builds, and check that each
declared context window matches the size baked into its filename.

**Downloads are checked before they count.** A finished download is opened and
inspected while you are still on the download screen. A file that isn't a usable
model is rejected there and then, with a plain explanation — instead of being saved
and failing much later with a page of C++ paths.

### Models

Qwen is gone. Four remain, and these are genuinely all that exist: every other
on-device model worth having — the whole Gemma family, Llama — sits behind a
Hugging Face sign-in.

- **SmolLM 135M** (159 MB) — the new default. Downloads in under a minute and proves
  the app works. Too small to be a real assistant; it's there so you never spend
  gigabytes finding out something is broken again.
- **TinyLlama 1.1B** (1.1 GB) — plain but quick.
- **DeepSeek-R1 1.5B** (1.7 GB) — thinks before answering, and shows the working.
- **Phi-4-mini 3.8B** (3.7 GB) — the most capable that will run on a phone.

### New

- **Light, dark, and follow-the-system.** Every colour crossfades when it changes,
  so switching reads as one movement rather than a flash.
- **Settings is a set of pages** — Model, Appearance, Instructions, Storage, About —
  instead of one long scroll. Back steps out one level at a time.
- **Motion throughout**: screens slide by depth, rows arrive in sequence, buttons
  give under your finger, the send button morphs into stop, and the download bar
  sweeps while connecting.
- Storage lists each downloaded model separately, so you can remove one without
  removing all of them.

### Fixed

- Opening a chat pinned to a model you hadn't downloaded quietly changed the model
  every *new* chat would use.
- Sending a message before a model finished loading did nothing at all, silently.
- The "earlier messages were dropped" notice carried over between conversations.
- The download service always fetched the default model, ignoring the one the
  screen was offering.

---

## 1.2.0 — 21 August 2026

**Fixes the app being unusable after a download.** Loading a model failed with
"Unable to create LlmLiteRTXnnpackExecutor, model is null". The cause was a
GPU-only model bundle: when the GPU delegate is refused, the app falls back to the
CPU, and a GPU-only file cannot be read there. Every model now ships in a build
that runs on either. A test now blocks GPU-only bundles from ever being listed
again, and if a model genuinely can't load, the screen says which one and offers
to fetch it again or switch — instead of showing a wall of C++ file paths.

### New

- **Formatted replies.** Bold, italic, headings, lists, inline code and fenced code
  blocks now render properly instead of showing their own asterisks and backticks.
  Code scrolls sideways rather than stretching the message off-screen.
- **Regenerate.** Didn't like the answer? Ask again from the same point.
- **A model per conversation.** Tap the model name in the chat header to switch who
  answers. Each chat remembers the model it started with, so changing your default
  never rewrites the voice of an old conversation.
- **Editable instructions.** A standing note handed to the model before every
  conversation, setting its tone and ground rules. Settings explains what it does
  and can reset it.
- **Haptics** on send, stop, regenerate, long-press and new chat.
- **Tests** — 29 of them, covering reasoning parsing, Markdown, titles, timestamps,
  token budgeting and the model catalogue. CI runs them, and a release cannot ship
  if they fail.

### Changed

- **Qwen is gone.** The line-up is now LFM2.5 1.2B (the default — smallest and
  quickest), LFM2.5 2.6B, and Gemma 4 E2B.
- **First launch no longer demands notification access.** It is requested at the
  moment a download starts, which is the only reason it was ever needed.
- **The download screen tells the truth at every step**: a moving bar while
  connecting rather than a frozen 0%, real progress once bytes arrive, and a
  distinct "warming up" state for first load.
- **Downloads that end early are caught** rather than being saved as a broken model
  you only discover later. Free space is checked before starting, and failures now
  read like sentences: "No connection", "The connection timed out".
- Search appears once you have five or more conversations, instead of sitting above
  an empty list.

---

## 1.1.0 — 20 August 2026

**Four models to choose from, and a much better one by default.**

- **Gemma 4 E2B is the new default** — Google's newest small model, and one of the
  very few in its family you can download without an account or a license
  click-through. It replaces Qwen2.5 1.5B, which was both weaker and a larger
  download.
- **Two Liquid AI models join the picker**: LFM2.5 1.2B (the fastest thing here,
  and only 736 MB) and LFM2.5 2.6B. Both are built specifically for phone
  latency. Qwen2.5 1.5B stays as a fallback in the older file format.
- **The context window tripled**, from 1280 tokens to 4096. Conversations run
  roughly three times longer before anything is forgotten.
- **A thinking indicator.** When a model works through a problem before answering,
  you see it happening — a shimmering label, a running clock, and its reasoning
  scrolling past underneath. Once it answers, the reasoning collapses into a
  "Thought for 8s" line you can expand.

**The app is much smaller.** 57 MB down to **36 MB**, by shipping only the ARM64
build of the inference engine instead of all four processor architectures.

**Builds are much faster too** — around 6 seconds for an incremental build, thanks
to Gradle's configuration cache and parallel execution.

### Fixed

- The 1.0.1 release never published, because the signature check was looking for
  the old v1 JAR signature that modern Android builds no longer produce.

---

## 1.0.1 — 20 August 2026

**Fixes the 1.0.0 download, which could not be installed.** The release build was
published without a signature, and Android refuses unsigned APKs outright. Releases
are now signed — properly when a keystore is configured, otherwise with a debug key
— and the workflow checks for a signature before publishing rather than shipping a
file nobody can open.

If you already have maik installed from an earlier build, uninstall it first: this
one is signed with a different key.

---

## 1.0.0 — 20 August 2026

The first release worth installing.

**maik runs a language model on your phone and nowhere else.** You download the
model once, and after that the app never touches the network again. No account, no
API key, no server, no telemetry. Airplane mode changes nothing.

### What it does

- **Conversations that stick around.** Start as many as you like. They're titled
  from your first message, sorted by most recent, and searchable by title or by
  anything said inside them. Rename or delete any of them with a long press.
- **Replies stream in** word by word instead of landing all at once after a long
  silence — and you can **stop one mid-sentence** if it's going nowhere. Whatever it
  had already written is kept.
- **Long press any message to copy it.**
- **Two models to choose from.** Qwen2.5 1.5B by default, or a 0.5B build that's a
  third of the size if storage is tight. Switch whenever; both stay downloaded.
- **The download survives real life.** It runs as a background service with a
  progress notification, so locking your screen or leaving the app no longer throws
  away 1.5 GB of progress. It warns you first if you're not on Wi-Fi.
- **GPU when your phone allows it**, CPU when it doesn't. Settings tells you which
  one you actually got.

### Why it isn't Gemini Nano

maik was built on Google's AI Edge SDK first, which borrows the Gemini Nano already
living inside Android. On a Galaxy S24 Ultra every call failed with
`error type 2-INFERENCE_ERROR, code 8-NOT_AVAILABLE` — your phone runs Nano for
Samsung's own features, but won't lend it to anyone else's app. Google's supported
device list said the S24 qualified, then quietly narrowed to Pixel 9, and the same
error is filed against Google's own sample app by a Pixel 9 Pro owner.

There is no flag to flip. So maik brings its own model instead — one nobody can
revoke from a dashboard.

### Known limits

- The model is small. It follows instructions and holds a short thread, but it will
  state wrong things confidently. Treat it accordingly.
- Context is 1280 tokens, fixed inside the model file. Long conversations drop their
  oldest messages, and the chat tells you when that happens.
- A cancelled download restarts from zero — there's no resume yet.
- Replies render as plain text, so Markdown shows its own asterisks.
