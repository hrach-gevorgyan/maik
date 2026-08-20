<div align="center">

# maik.

### The model lives on your phone. Not on someone's server.

Turn on airplane mode. Ask it something. It answers.

<br>

[![Build](https://github.com/hrach-gevorgyan/maik/actions/workflows/build.yml/badge.svg)](https://github.com/hrach-gevorgyan/maik/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/hrach-gevorgyan/maik?style=flat&labelColor=08080B&color=D8FF3E)](https://github.com/hrach-gevorgyan/maik/releases/latest)
![Android](https://img.shields.io/badge/Android-8.0%2B-08080B?labelColor=08080B&color=D8FF3E)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0-08080B?labelColor=08080B&color=D8FF3E)
![Offline](https://img.shields.io/badge/100%25%20Offline-08080B?labelColor=08080B&color=D8FF3E)

**[Download the latest APK](https://github.com/hrach-gevorgyan/maik/releases/latest)** · [Changelog](CHANGELOG.md) · [Build it](#build-it) · [Why not Gemini Nano](#-read-this-if-you-came-here-for-gemini-nano)

</div>

<br>

```
  no account          no api key          no telemetry
  no rate limit       no server           no subscription
```

<br>

Most "AI chat" apps are a text box wired to somebody else's GPU. `maik` isn't. The
weights sit in your app's private storage, inference runs on your silicon, and the
only network request the app will ever make is the one that fetched the model.

---

## ⚠️ READ THIS IF YOU CAME HERE FOR GEMINI NANO

> **THE GALAXY S24 CANNOT RUN GEMINI NANO IN THIRD-PARTY APPS.**
> **GOOGLE'S OWN DOCUMENTATION SAID IT COULD. THAT LIST WAS QUIETLY CHANGED.**

`maik` began on the Google AI Edge SDK (`com.google.ai.edge.aicore`), which borrows
the Gemini Nano already sitting inside Android's **AICore** service. On a Galaxy S24
Ultra, every single call dies with this:

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

**So maik doesn't use Gemini Nano at all.** It brings its own model — one nobody can
revoke from a dashboard.

---

## What it does

<table>
<tr><td width="180"><b>Conversations</b></td><td>As many as you like, titled from your first message, searchable by title or content. Long-press to rename or delete.</td></tr>
<tr><td><b>Streaming</b></td><td>Replies arrive word by word. Hit stop mid-sentence and it keeps what was already written.</td></tr>
<tr><td><b>Copy</b></td><td>Long-press any message.</td></tr>
<tr><td><b>Four models</b></td><td>Gemma 4 E2B by default, plus two Liquid AI builds and an older fallback. Switch anytime; each stays downloaded.</td></tr>
<tr><td><b>Visible thinking</b></td><td>When a model reasons before answering, you watch it happen — then the reasoning folds away into a line you can expand.</td></tr>
<tr><td><b>Honest downloads</b></td><td>Background service with a progress notification, survives screen lock, warns before spending your mobile data.</td></tr>
<tr><td><b>GPU</b></td><td>Used when the driver allows it, CPU when it doesn't. Settings tells you which you got.</td></tr>
</table>

## How it works

A **LiteRT** model running through **MediaPipe LLM Inference**, entirely inside the
app's own process. Same promise Nano made — nothing leaves the device — minus the
part where Google decides whether your phone qualifies.

First launch fetches the weights once and parks them in app-private storage. After
that the radio is never touched again. `INTERNET` exists for that one download and
nothing else.

<table>
<tr><td><b>Default model</b></td><td>Gemma 4 E2B · Apache 2.0 · ~1.9 GB</td></tr>
<tr><td><b>Runtime</b></td><td><code>com.google.mediapipe:tasks-genai</code>, LiteRT-LM bundles</td></tr>
<tr><td><b>Context</b></td><td>4096 tokens</td></tr>
<tr><td><b>Runs on</b></td><td>Any ARM64 Android 8.0+ phone. Yes, including your S24 Ultra.</td></tr>
</table>

### The models on offer

Every one of these downloads with no account, no token and no license gate — which
rules out most of the obvious names.

| Model | Params | Download | License | Character |
|---|---|---|---|---|
| **Gemma 4 E2B** *(default)* | ~2B effective | 1.9 GB | Apache 2.0 | The sharpest. Google's newest small model |
| **LFM2.5 2.6B** | 2.6B | 1.6 GB | LFM open | More depth, still built for phones |
| **LFM2.5 1.2B** | 1.2B | 736 MB | LFM open | The fastest, and the smallest download |
| Qwen2.5 1.5B | 1.5B | 1.5 GB | Apache 2.0 | Fallback in the older `.task` format |

Switching is two taps in Settings, and each model stays on disk once fetched, so
you can compare them on your own phone rather than taking anyone's word for it.

<details>
<summary><b>Why these four, and not the obvious names?</b></summary>

<br>

Because almost everything else is **gated** on Hugging Face (`gated: auto`) — Gemma
3, Gemma 2, Llama 3.2. Every user would have to create an account, accept a licence
and mint a token before the app could download anything, which is a miserable way to
open an app for the first time.

Gemma **4** is the happy exception: newest of the family, and ungated.

If you want a gated model anyway, point `ModelSpec.url` at its bundle and add an
`Authorization: Bearer <hf_token>` header inside `ModelStore.download()`.

Also considered and passed over: Qwen3 4B (2.5 GB, slow to first word), Phi-4-mini
(3.7 GB), and DeepSeek-R1 1.5B (reasons endlessly at this size).

</details>

## Install

Grab the APK from **[Releases](https://github.com/hrach-gevorgyan/maik/releases/latest)**
and open it on your phone. You'll need "install unknown apps" enabled for whatever
you open it from.

## Build it

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

## Releasing

Versions live in one place — `maikVersionName` in
[`app/build.gradle.kts`](app/build.gradle.kts) — and CI overrides them from the tag.
To cut a release:

```bash
git tag v1.1.0 && git push origin v1.1.0
```

The workflow derives `versionCode` from the tag (`1.1.0` → `10100`), builds a
release APK, pulls that version's section out of [`CHANGELOG.md`](CHANGELOG.md) for
the release notes, and attaches the APK.

<details>
<summary><b>Getting signed builds</b></summary>

<br>

Without a keystore, releases fall back to a **debug-signed** build. It installs and
runs, but every release gets a different throwaway key, so you must uninstall the
previous version first — which also wipes your chats and downloaded model. To fix
that permanently, generate a keystore once:

```bash
keytool -genkey -v -keystore maik.jks -keyalg RSA -keysize 2048 -validity 10000 -alias maik
```

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 maik.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `maik` |
| `KEY_PASSWORD` | the key password |

Keep `maik.jks` somewhere safe and out of the repo. Lose it and you can never ship
an upgrade to anyone who installed a build signed with it.

</details>

---

## The look

Near-black, bone white, and exactly one acid accent. **HK Grotesk** everywhere —
shipped as a single variable font and instanced per weight, not bundled six times
over. The launcher icon is the wordmark itself, rendered from that same font.

<table>
<tr><td><b>Wordmark</b></td><td><code>maik.</code> — text only, 800 weight, tight negative tracking</td></tr>
<tr><td><b>Palette</b></td><td><code>#08080B</code> ink · <code>#EDEDF2</code> bone · <code>#D8FF3E</code> acid</td></tr>
<tr><td><b>Type</b></td><td>HK Grotesk 300–800 from one 130 KB file</td></tr>
<tr><td><b>Motion</b></td><td>Pulsing on-device dot · staggered typing dots · animated send/stop state</td></tr>
</table>

No gradients. No glassmorphism. No purple.

## Layout

```
app/src/main/java/com/maik/app/
├── MainActivity.kt     nav, chat screen, shared components, hand-drawn glyphs
├── Screens.kt          conversation list, settings, first-run setup
├── ChatViewModel.kt    stage machine, model lifecycle, streaming, context budget
├── Thinking.kt         the reasoning indicator and its collapsible trace
├── ModelStore.kt       model catalog + a download that can't half-succeed
├── DownloadService.kt  foreground service so downloads survive the lock screen
├── Conversations.kt    chat model, JSON persistence, relative timestamps
└── Theme.kt            palette + HK Grotesk type scale
```

Four things worth knowing before you fork it:

**Context is budgeted, not truncated by luck.** The KV cache is fixed when the model
is converted, so `buildPrompt` walks backwards through the transcript keeping only
what fits and reports how many messages it dropped. The chat shows that count rather
than silently forgetting.

**Prompt formats are per family.** The bundles ship tokenizers, not chat templates,
so ChatML and Gemma's `<start_of_turn>` format are both built by hand — and Gemma
has no system role, so the instructions ride along with the first user turn.

**Stop doesn't kill the session.** The native call can't be interrupted safely
mid-flight, so each turn carries a generation number; stopping bumps it and late
tokens are discarded. The model finishes into the void and the session closes when
it's done.

**Downloads land in a `.part` file** and are renamed only on success, so a dropped
connection can never leave something that *looks* like a working model. If the
bundle fails to load anyway it's deleted, so the retry refetches clean.

**History is one JSON file**, rewritten on every change. A corrupt file costs you
your chats, not the app — it fails to an empty list rather than a crash loop.

## Known limits

- **The model is small.** It follows instructions and holds a short thread, but it
  will state wrong things confidently.
- **Long chats forget.** 4096 tokens is roughly 45 exchanges.
- **No download resume.** Cancel at 1.4 GB and you start over.
- **Plain text only.** Markdown renders as its own asterisks.
- **No tests.**

## Requirements

- Android **8.0 (API 26)** or newer
- ~2.5 GB free storage for the default model
- An **ARM64** device (everything since roughly 2016)
- Android Studio Ladybug+ / JDK 17+
- **No special hardware. No allowlist. No AICore.**

---

<div align="center">

<sub>

Type: [Hanken Grotesk](https://github.com/hanken-design/HK-Grotesk) · Hanken Design Co. · SIL OFL 1.1<br>
Models: [Gemma 4](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) · [LFM2.5](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct) · [Qwen2.5](https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct) · converted to LiteRT by litert-community

**maik.**

</sub>

</div>
