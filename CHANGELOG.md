# Changelog

Written for people, not for parsers. Newest first.

Versions follow [semantic versioning](https://semver.org): the middle number moves
when maik gains something, the last one when something gets fixed.

---

## 2.4.0 — 17 September 2026

**Built like a shipped app.**

- **Gemma is the default again**, with the cooler CPU settings, capped replies and thermal
  pacing from 2.3 now working in its favour.
- **Fixed a crash** when a model was downloading while a chat was open: the progress line
  in the chat had a broken percent sign.
- **A proper launch screen** in maik's own colours, and no more black flash before the
  first frame in light mode.
- **Predictive back** — the Android 14+ gesture that previews where Back goes.
- **Per-app language ready:** Android 13+ can list maik under its language settings once
  translations exist. Counts like "1 message" / "2 messages" are now real plurals, so they
  translate correctly.
- **Releases are shrunk and optimised:** the APK drops from about 59 MB to 24 MB, starts
  faster, and is no longer a debuggable build.
- **Long replies render more efficiently:** finished paragraphs are formatted once instead
  of being re-formatted from the top on every update.

### Under the hood

- Android lint now runs on every push and fails the build on errors.
- New tests check every string resource can actually be formatted, and that a reply parsed
  in pieces while it streams matches the same reply parsed whole. 71 unit tests.

---

## 2.3.0 — 17 September 2026

**Less heat, from the research up.** Writing a reply re-reads the whole model for every
single word, so heat comes from how many bytes the model is and how hard the phone is
pushed — not from anything clever in the code.

- **The small model is now the default.** LFM2.5 reads 0.7 GB per word against Gemma's
  2.6 GB, so it is roughly a third of the heat for most questions. Gemma is still there,
  now marked as the one that warms the phone up.
- **The GPU is off by default.** It reads your message faster, but it writes the reply
  using more power than the processor does, and its heat lands in a smaller spot. The
  switch is still in Settings for anyone who wants it.
- **Fewer cores, on purpose.** Writing a reply is limited by memory speed, not by
  arithmetic, so extra cores add heat without adding words.
- **Replies are capped** at a few paragraphs rather than half the context window.
- **maik now eases off before the phone throttles**, not after. It reads Android's own
  forecast of how close the phone is to overheating and slows down gently, which keeps
  more speed over a long answer than being throttled does. The chat says when it happens.
- **A flat, sustainable clock** is requested for the app where the phone supports it,
  instead of a burst followed by throttling.

---

## 2.2.0 — 17 September 2026

**Cooler, calmer, and ready to speak other languages.**

### Heat

- **Keep the phone cool**, on by default in Settings → Behaviour. maik answers using half
  the processor cores instead of all of them: a little slower, far less heat. Turn it off
  for the fastest possible replies.
- **maik stops if the phone overheats.** It watches Android's own temperature reading and
  ends the reply rather than making things worse, and says so in the chat.
- **Replies can no longer run away.** A reply is capped at half the context window, which
  is more than any answer needs and stops a looping model cooking the phone for minutes.

### Motion

- New messages rise into place instead of appearing; chat history you already had stays
  still when you open a chat.
- The typing dots fade into the first words rather than blinking out.
- "Jump to latest" rises and scales in.
- An empty chat sits in the middle of the screen again.

### Language

- **All text now lives in resource files** — about 200 strings — so maik can be translated.
  English stays the source language; nothing has changed on screen.

---

## 2.1.0 — 17 September 2026

**The chat grows up.**

- **Long-press any message** for Copy, Select text, Share, Delete, and — on your own
  messages — Edit and send again, which asks the question afresh from that point.
- **Copy button on code blocks**, because that is what code is for.
- **Pin a chat to the top**, and swipe a chat sideways to delete it (it still asks first).
- **A proper About page:** version, what maik does and doesn't send, and the licences of
  the runtime, the font and each model.
- **Wide screens read properly.** In landscape, on a tablet or in split view, text keeps
  a comfortable width in the middle instead of stretching across the whole screen.
- **Dark theme pass:** sheets, menus and code blocks now sit on their own surfaces
  instead of blending into the page.
- **Accessibility:** tappable text is announced as buttons, pinned chats say so, and
  everything holds together at 150% font size.
- **Five UI tests** now tap through the chat the way a thumb does, on top of the 66 unit
  tests and the emulator model test.

---

## 2.0.2 — 17 September 2026

- **Follows your phone's theme by default.** New installs match the system's light or
  dark mode; you can still pick one under Settings → Appearance.
- **Vibration you can actually feel.** Taps now use the phone's vibration motor
  directly, since the standard Android tap feedback is muted or faint on many phones,
  Samsung's included. A soft tick also tells you when a reply has finished.

---

## 2.0.1 — 17 September 2026

**Smoother chat, and a likely fix for crashes right after install.**

- **Replies stream smoothly.** The chat is anchored to the bottom, so a reply grows
  upward on its own instead of the list jumping every few words.
- **No flicker when a reply finishes.** The live reply turns into the saved one in
  place, instead of disappearing and fading back in.
- **Your message brings you down; a reply doesn't yank you** away from something you
  scrolled up to read.
- **The loading and download strip slides open and closed** instead of popping in and
  shoving the messages around.
- **Calmer typing dots** that cost almost nothing to draw.
- **GPU on Samsung and other phones:** maik now declares the phone's OpenCL library,
  which Android 12 and newer hide from apps otherwise. Without it, the GPU load could
  crash on the first launches until maik switched to the CPU.

---

## 2.0.0 — 17 September 2026

**A solid base.** Everything from 1.7 to 1.9 — one warm engine, the GPU on capable
Snapdragons, resumable downloads, the chat that stays on screen — plus a full audit of
the ways it could still go wrong.

### Fixed

- **No more crashes when you act mid-reply.** Editing the instructions, deleting the
  open chat, or leaving the app while maik was answering could close the model's
  conversation while it was still writing. It now stops, waits, then closes.
- **Asking again after Stop works.** A stopped reply could leave maik stuck on "answering"
  forever for the next question.
- **Stop keeps every word.** The last fraction of a second of a stopped reply used to
  be lost.
- **New chats use a model you actually have.** Downloading only LFM2.5 used to leave new
  chats asking you to download Gemma.
- **Switching the GPU while a model loads** no longer leaves the app saying Ready with
  nothing loaded.
- **Deleting a model during another model's load** can no longer unload the new one.
- **Downloads can't corrupt themselves.** Each model is pinned to an exact Hugging Face
  commit and checked against its SHA-256 before it's used, so a resumed download can
  never stitch two different files together. A download that was already complete no
  longer fails forever with "server answered 416".
- **A download for one model no longer hijacks a chat using another**, and starting a
  second download while one runs tells you instead of silently doing nothing.
- **Closing the "Show download progress?" box** no longer starts a 2.6 GB download.
- **"Continue" and "Download again" warn about mobile data** like the first download does.
- **Back from a download or settings returns to your chat**, not the chat list.
- **Rotating the phone** keeps what you were typing and no longer jumps to the download.
- **Android 15's download time limit** stops the download cleanly with a message; retry
  picks up where it stopped.
- Headings like `##### Five` lost their text; `#1 priority` became a heading.
- Error bubbles were dark red blocks in the light theme.
- The storage figure now counts partial downloads and the runtime's cache.
- The "ON-DEVICE" dot pulses a few times and then rests instead of animating forever.

### Changed

- You can type while the model is still loading; Send waits until it's ready.
- Chats and settings are included in Android backup; model files are not.

### Under the hood

- 66 unit tests (was 40): download routing, resume decisions, stream copying, back
  navigation, model pinning, markdown edge cases.
- Releases check the version tag in seconds before the emulator gate, and refuse to
  publish without a changelog entry.

---

## 1.9.0 — 17 September 2026

**The chat stays on screen.**

- **No more swapping to a setup page.** Opening a chat while the model is loading,
  missing, downloading or broken now shows a strip above the messages saying what's
  happening, with the one button that helps: Download, View, Try again, or Use CPU
  instead. The message box waits until the model is ready.
- **One Models page.** Each model shows its size, context, whether it's in use, a
  memory warning if your phone looks too small for it, and a single action —
  Download, View download, Use for new chats, or Delete.
- **Deleting asks first**, for both chats and models.
- **"Jump to latest"** appears when you scroll up while a reply is coming in, instead
  of the list pulling you back down.
- **"Try again"** under a reply that failed.
- **First launch** opens on setup, with "Not now" instead of a Back button that led
  nowhere. "Choose a different model" goes straight to the model list.

### Fixed

- The chat list could crash when a reply and an error were stamped in the same
  millisecond.
- Settings said a model was "in use" before anything was downloaded.
- A new chat said the model was running while the strip above said it wasn't there.

### Accessibility

- Secondary text is darker and readable in both themes.
- Buttons and icon buttons are at least 48dp, and icons have spoken labels.

### Under the hood

- The code is organised by purpose — engine, data, and the screens — instead of two
  files holding almost everything.
- New tests cover the bugs that actually happened: a stale download event starting a
  second load, an interrupted download restarting from zero, and a rebuilt
  conversation overflowing its window.
- The release check runs once, from one definition, on the lighter model, so it
  tests the app rather than the limits of CI's emulator.

---

## 1.8.0 — 17 September 2026

**Faster, steadier, and it stops losing things.**

### Faster

- **The GPU is on by default** on Snapdragon 8 Gen 3 and newer. It reads your message
  many times faster than the CPU. If loading ever crashes, it switches itself off.
- **The model stays loaded.** It belongs to the app, not to a screen, so rotating the
  phone or coming back to the app no longer reloads it, and its prepared copy is kept
  where the system won't clear it.
- **Less re-reading.** Stopping a reply no longer throws away the conversation, so the
  next message doesn't make the model read the whole chat again.
- **Smoother streaming.** The reply updates a few times a second instead of on every
  word, and the list only follows along if you're already at the bottom.

### Models

- **LFM2.5 1.2B replaces Qwen3.5 2B** — a third of the download and much lighter on
  the phone. Gemma 4 E2B stays the default.
- **Thinking is gone.** Neither model uses it.

### Fixed

- Two model loads could run at once, holding two copies of a 2.5 GB model in memory.
- A finished download could be replayed to a new screen and start a second load.
- The engine could be closed while it was still writing a reply.
- A crash while saving could wipe every chat.
- Deleting the model in use left the app stuck.
- Every load failure said "download again", even when the file was fine.
- An interrupted download started again from zero. It now continues.

### New

- **Opening a chat that used a different model asks** whether to switch or keep the
  one that's loaded.
- **Show speed** under Settings → Behaviour displays time to first word and speed
  under each reply.

---

## 1.7.0 — 16 September 2026

**New engine, new models.**

maik now runs on **LiteRT-LM**, Google's current on-device runtime, replacing
MediaPipe's LLM Inference, which is deprecated. Every newer model ships only in
LiteRT-LM's format, so this is what made better models possible at all.

- **Gemma 4 E2B** is the new default — Google's model built for phones.
- **Qwen3.5 2B** is the alternative: a smaller download that can think before
  answering.
- DeepSeek-R1 1.5B and Qwen2.5 1.5B are gone. Chats that used them open with the new
  default.

### Better, because of the new engine

- **Replies stop where they should.** The runtime applies each model's own chat
  template and stop tokens, so the rambling and leaked markup of earlier versions are
  handled at the source instead of trimmed afterwards.
- **Chats remember properly.** History is handed to the model as real conversation
  turns rather than summarised into a paragraph.
- **Stop is a real stop.** Generation is cancelled, not just ignored until it ends.
- **Thinking is native.** The thinking switch now turns the model's own reasoning mode
  on and off.
- **Faster reloads.** The runtime keeps a prepared copy of the model, so only the very
  first load is slow.

You will need to download a model again, since the file format has changed.

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
