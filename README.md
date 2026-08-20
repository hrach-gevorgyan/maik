<div align="center">

# maik.

### The model lives on your phone. Not on someone's server.

Turn on airplane mode. Ask it something. It answers.

<br>

![Android](https://img.shields.io/badge/Android-8.0%2B-08080B?style=for-the-badge&labelColor=08080B&color=D8FF3E)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-08080B?style=for-the-badge&labelColor=08080B&color=D8FF3E)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-08080B?style=for-the-badge&labelColor=08080B&color=D8FF3E)
![Offline](https://img.shields.io/badge/100%25%20Offline-08080B?style=for-the-badge&labelColor=08080B&color=D8FF3E)

**[Download the APK](dist/maik-debug.apk)** · [Build it](#build-it) · [Why not Gemini Nano](#-read-this-if-you-came-here-for-gemini-nano)

</div>

<br>

```
  no account          no api key          no telemetry
  no rate limit       no server           no subscription
```

<br>

**Saved conversations** · **streaming replies** · **swappable models** · **rename & delete** · **airplane mode**

<br>

Most "AI chat" apps are a text box wired to somebody else's GPU. `maik` isn't.
The weights sit in your app's private storage, inference runs on your silicon, and
the only network request the app will ever make is the one that fetched the model.

---

## ⚠️ READ THIS IF YOU CAME HERE FOR GEMINI NANO

> **THE GALAXY S24 CANNOT RUN GEMINI NANO IN THIRD-PARTY APPS.**
> **GOOGLE'S OWN DOCUMENTATION SAID IT COULD. THAT LIST WAS QUIETLY CHANGED.**

`maik` began on the Google AI Edge SDK (`com.google.ai.edge.aicore`), which borrows
the Gemini Nano already sitting inside Android's **AICore** system service. On a
Galaxy S24 Ultra, every single call dies with this:

```
com.google.ai.edge.aicore.UnknownException:
AICore failed with error type 2-INFERENCE_ERROR and error code 8-NOT_AVAILABLE:
Required LLM feature not found
```

What that actually means, and why no update will ever fix it:

- **Your S24 does run Gemini Nano.** It's what powers Samsung's Galaxy AI. It is
  simply not exposed to apps that aren't Samsung's or Google's.
- **The device list originally included the Galaxy S24 series**, then narrowed to
  Pixel 9. A developer on Google's own forum bought an S24 *because of that list*,
  hit this exact error, and [got no reply](https://discuss.ai.google.dev/t/google-ai-edge-sdk-supported-android-devices/67403).
- **It isn't even reliable on Pixels.** The same error is filed against **Google's
  own sample app** by a user on a **Pixel 9 Pro** —
  [android/ai-samples#24](https://github.com/android/ai-samples/issues/24). Closed.
  No fix.
- **There is no workaround.** No adb flag. No allowlist. No beta channel. No Play
  Store update. The capability is gated server-side, per device, at Google's
  discretion.

**So maik doesn't use Gemini Nano at all anymore.** It brings its own model. That
model is yours, it runs on your phone, and nobody can revoke it from a dashboard.

---

## How it works

A **LiteRT** model running through **MediaPipe LLM Inference**, entirely inside the
app's own process. Same promise Nano made — nothing leaves the device — minus the
part where Google decides whether your phone qualifies.

First launch fetches the weights once and parks them in app-private storage. After
that the radio is never touched again. The `INTERNET` permission exists for that
one download and nothing else.

<table>
<tr><td><b>Default model</b></td><td>Qwen2.5 1.5B Instruct · int8 · Apache 2.0</td></tr>
<tr><td><b>Download</b></td><td>~1.5 GB, once, no token, no license gate</td></tr>
<tr><td><b>Runtime</b></td><td><code>com.google.mediapipe:tasks-genai</code></td></tr>
<tr><td><b>Runs on</b></td><td>Any Android 8.0+ phone. Yes, including your S24 Ultra.</td></tr>
</table>

Tight on storage? Settings has a **0.5B** build at ~521 MB. It's three times
smaller and noticeably dumber — the tradeoff is exactly what you'd expect.

<details>
<summary><b>Why not Gemma 3 1B, which is better at this size?</b></summary>

<br>

Because its Hugging Face repo is **gated** (`gated: auto`). Every user would have to
create an account, accept a license, and mint a token before the app could download
anything. That's a terrible first-run experience for a chat toy.

If you want it anyway: point `ModelSpec.url` at the Gemma `.task` bundle and add an
`Authorization: Bearer <hf_token>` header inside `ModelStore.download()`.

</details>

---

## Download

**[`dist/maik-debug.apk`](dist/maik-debug.apk)** — ~57 MB. The model is *not*
bundled; it streams down on first run.

Debug-signed: perfect for sideloading, useless for the Play Store. You'll need
"install unknown apps" enabled for whatever you open it from.

## Build it

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

---

## The look

Near-black, bone white, and exactly one acid accent. **HK Grotesk** everywhere —
shipped as a single variable font and instanced per weight, not bundled six times
over.

<table>
<tr><td><b>Wordmark</b></td><td><code>maik.</code> — text only, 800 weight, tight negative tracking</td></tr>
<tr><td><b>Palette</b></td><td><code>#08080B</code> ink · <code>#EDEDF2</code> bone · <code>#D8FF3E</code> acid</td></tr>
<tr><td><b>Type</b></td><td>HK Grotesk 300–800 from one 130 KB file</td></tr>
<tr><td><b>Motion</b></td><td>Pulsing on-device dot · staggered typing dots · animated send state</td></tr>
</table>

No gradients. No glassmorphism. No purple.

---

## Layout

```
app/src/main/java/com/maik/app/
├── MainActivity.kt    nav, chat screen, shared components, hand-drawn glyphs
├── Screens.kt         conversation list, settings, first-run setup
├── ChatViewModel.kt   stage machine, model lifecycle, streaming, prompt assembly
├── ModelStore.kt      model catalog + a download that can't half-succeed
├── Conversations.kt   chat model, JSON persistence, relative timestamps
└── Theme.kt           palette + HK Grotesk type scale
```

Three things worth knowing before you fork it:

**The model is stateless between turns.** A fresh `LlmInferenceSession` per message,
with the transcript replayed each time — so deleting a chat genuinely deletes it and
context never bleeds between conversations. Fine for short chats; long ones will hit
the 1280-token window, so start summarizing or windowing.

**History is one JSON file**, rewritten on every change. A few hundred KB at worst,
so a database would be ceremony. A corrupt file costs you your chats, not the app —
it fails to an empty list rather than a crash loop.

**Downloads land in a `.part` file** and are renamed only on success. A dropped
connection can never leave behind something that *looks* like a working model. If
the bundle fails to load anyway, it's deleted, so the retry refetches clean.

---

## Requirements

- Android **8.0 (API 26)** or newer
- ~600 MB free storage
- Android Studio Ladybug+ / JDK 17+
- **No special hardware. No allowlist. No AICore.**

---

<div align="center">

<sub>

Type: [Hanken Grotesk](https://github.com/hanken-design/HK-Grotesk) · Hanken Design Co. · SIL OFL 1.1<br>
Model: [Qwen2.5-0.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct) · Apache 2.0 · LiteRT conversion by litert-community

**maik.**

</sub>

</div>
