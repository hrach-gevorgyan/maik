<div align="center">

# maik.

**A chat app whose model lives on your phone.**

Turn on airplane mode. Ask it something. It answers.

[![Build](https://github.com/hrach-gevorgyan/maik/actions/workflows/build.yml/badge.svg)](https://github.com/hrach-gevorgyan/maik/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/hrach-gevorgyan/maik)](https://github.com/hrach-gevorgyan/maik/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](#requirements)

[**Download**](https://github.com/hrach-gevorgyan/maik/releases/latest) ·
[Models](#the-models) ·
[Build](#building) ·
[Why not Gemini Nano](#why-not-gemini-nano)

</div>

---

## What it is

Most "AI chat" apps are a text box wired to somebody else's GPU. maik isn't.

The weights sit in your app's private storage and inference runs on your own
silicon. The app makes exactly one network request in its life — the one that
downloads the model. After that the radio is never touched again. No account, no API
key, no server, no telemetry.

## Install

Download the APK from [**Releases**](https://github.com/hrach-gevorgyan/maik/releases/latest)
and open it on your phone. You will need "install unknown apps" enabled for whatever
you open it from.

On first launch it fetches a model — around 2.6 GB, once, over Wi-Fi. If the download is interrupted it continues where it stopped. That is the
only download it will ever ask for.

> **Note**
> Releases are currently **debug-signed**, so each one carries a different key.
> Uninstall the previous version before installing a new one — which also clears your
> chats and downloaded models. See [signed builds](#signed-builds) to fix this
> permanently.

## Features

| | |
|---|---|
| **Conversations** | As many as you like, titled from your first message. Search by title or content, long-press to rename or delete |
| **Streaming replies** | Words arrive as they are generated. Stop mid-sentence and whatever was written is kept |
| **Markdown** | Headings, lists, bold, inline code, and code blocks that scroll rather than stretch the screen |
| **Regenerate** | Ask again from the same point when an answer misses |
| **A model per chat** | Switch from the chat header. Each conversation keeps the model it started with |
| **Instructions** | A standing note handed to the model before every conversation, editable in Settings |
| **Light and dark** | Or follow the system. Colours crossfade rather than snap |
| **Honest downloads** | A background service with a progress notification. Survives the lock screen, warns before spending mobile data, and verifies the file before accepting it |

## The models

Two ungated models, both built to run on a phone.

| Model | Download | License | Character |
|---|---|---|---|
| **Gemma 4 E2B** — default | 2.6 GB | Apache 2.0 | Google's on-device model. The most capable here |
| **LFM2.5 1.2B** | 0.7 GB | LFM Open License | A third of the download, quick, easy on the battery |

Switch from the chat header or in Settings. Each stays on disk once fetched.

<details>
<summary><b>Why these two — and why nothing bigger</b></summary>

<br>

**Newer models only ship as LiteRT-LM bundles**, so maik runs on Google's LiteRT-LM
runtime. Nobody publishes the older `.task` format anymore.

**Bigger is not better on a phone.** Phi-4-mini at 3.8B was tried and pulled: it ran
the device hot enough to throttle, took over a minute per answer, and then locked up.
`Models.MAX_SENSIBLE_BYTES` caps what may be offered, and a test enforces it.

**Only generic builds are listed.** The `-gpu`, `-web` and chip-specific variants
refuse to load anywhere else — and CPU is the default.

</details>

## Why not Gemini Nano

maik began on the Google AI Edge SDK, which borrows the Gemini Nano already sitting
inside Android's **AICore** service. On a Galaxy S24 Ultra every call died with:

```
AICore failed with error type 2-INFERENCE_ERROR and error code 8-NOT_AVAILABLE:
Required LLM feature not found
```

- **The S24 does run Gemini Nano** — it powers Samsung's own Galaxy AI. It is simply
  not exposed to third-party apps.
- **Google's supported-device list originally included the S24 series**, then
  narrowed to Pixel 9. A developer who bought an S24 because of that list hit this
  exact error and [received no reply](https://discuss.ai.google.dev/t/google-ai-edge-sdk-supported-android-devices/67403).
- **It is not reliable on Pixels either.** The same error is filed against Google's
  own sample app by a Pixel 9 Pro owner —
  [android/ai-samples#24](https://github.com/android/ai-samples/issues/24), closed
  without a fix.
- **There is no workaround.** No adb flag, no allowlist, no beta channel. The
  capability is gated server-side, per device.

So maik brings its own model — one nobody can revoke from a dashboard.

## How it works

A `.litertlm` model running on **LiteRT-LM**, entirely inside the app's own process.

| | |
|---|---|
| Runtime | `com.google.ai.edge.litertlm:litertlm-android` |
| Context | 2048 tokens — the runtime decodes faster with a smaller budget |
| Backend | GPU by default on Snapdragon 8 Gen 3 and newer, CPU elsewhere; switchable under Settings → Behaviour |
| Storage | App-private. Uninstalling removes everything |

<details>
<summary><b>Things that are not obvious</b></summary>

<br>

**The runtime owns the prompt format.** Each bundle carries its own chat template and
stop tokens, and LiteRT-LM applies them. maik sends plain text and the conversation
history; it never builds a prompt by hand. Doing that on the previous runtime is what
produced garbled, rambling replies in 1.4 and 1.5.

**One engine for the whole app.** The loaded model belongs to the process, not to a
screen, so rotating the phone or reopening the app does not reload 2.6 GB. Every native
call runs on a single thread, so two loads can never overlap.

**One runtime conversation per chat.** It keeps the model's context between turns, and
survives Stop. Reopening a chat seeds a fresh one with the most recent turns that fit.

**Stop really stops.** Generation is cancelled in the runtime, not merely ignored.

**The GPU cannot be trusted to fail safely.** It crashes natively on some drivers,
which no `catch` can see. It is off by default, and a breadcrumb written before each
attempt means a crash during load turns it back off by itself.

**Downloads resume.** They land in a `.part` file and continue from where they stopped;
only a complete file with the `LITERTLM` header is renamed into place.

**Chats are saved atomically**, off the main thread. A crash mid-save leaves the previous
history intact instead of wiping it.

</details>

## Verifying models

Four releases were broken by model bundles nobody inspected. Two checks now stand in
the way, and both run in CI.

**[`tools/verify_models.py`](tools/verify_models.py)** reads the first bytes of each
bundle over an HTTP range request — not gigabytes — and confirms it is a real
LiteRT-LM file of the declared size, a generic build, from an ungated source, and
small enough for a phone.

```bash
python tools/verify_models.py
```

**[`GoldenTest.kt`](app/src/androidTest/java/com/maik/app/GoldenTest.kt)** boots an
emulator, downloads LFM2.5 1.2B, loads it and asks for the capital of
France. It fails unless the answer says Paris, the reply stops on its own without
leaking markup, and a conversation remembers an earlier turn. **A release cannot publish unless it passes.**

```bash
./gradlew connectedDebugAndroidTest
```

## Building

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

```bash
./gradlew test                      # 42 unit tests
./gradlew connectedDebugAndroidTest # golden test, needs a device or emulator
```

## Releasing

The version lives in one place — `maikVersionName` in
[`app/build.gradle.kts`](app/build.gradle.kts) — and CI overrides it from the tag:

```bash
git tag v1.6.0 && git push origin v1.6.0
```

The workflow derives `versionCode` from the tag (`1.6.0` → `10600`), verifies the
models, runs the golden test on an emulator, builds the APK, and uses that version's
section of [`CHANGELOG.md`](CHANGELOG.md) as the release notes.

### Signed builds

Without a keystore, releases fall back to a debug-signed build with a throwaway key.
To fix that permanently, generate a keystore once:

```bash
keytool -genkey -v -keystore maik.jks -keyalg RSA -keysize 2048 -validity 10000 -alias maik
```

Then add four repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | output of `base64 -w0 maik.jks` |
| `KEYSTORE_PASSWORD` | the store password |
| `KEY_ALIAS` | `maik` |
| `KEY_PASSWORD` | the key password |

Keep `maik.jks` safe and out of the repository. Lose it and you can never ship an
upgrade to anyone running a build signed with it.

## Design

One typeface, one accent, no decoration.

| | |
|---|---|
| Wordmark | `maik.` — text only, 800 weight, tight negative tracking |
| Palette | `#08080B` ink · `#EDEDF2` bone · `#D8FF3E` acid |
| Type | HK Grotesk 300–800, one 130 KB variable file |
| Icon | The wordmark itself, rendered from that same font |

## Project layout

```
app/src/main/java/com/maik/app/
├── MainActivity.kt      navigation, chat screen, shared components, glyphs
├── Screens.kt           conversation list and first-run setup
├── Settings.kt          the settings menu and its pages
├── ChatViewModel.kt     stage machine, model lifecycle, streaming, context budget
├── ModelStore.kt        the model catalogue and a download that cannot half-succeed
├── DownloadService.kt   foreground service, so downloads survive the lock screen
├── Reply.kt             last-line cleanup of reply text
├── Conversations.kt     chat model, JSON persistence, relative timestamps
├── Markdown.kt          a small Markdown parser and renderer
├── Thinking.kt          the reasoning indicator and its collapsible trace
├── Motion.kt            shared transitions, press feedback, entrances
└── Theme.kt             palette, type scale, light and dark schemes
```

## Known limits

- **The model is small.** It follows instructions and holds a short thread, but it
  will state wrong things confidently.
- **Long chats forget.** 4096 tokens is roughly 45 exchanges; the chat says so when
  older messages fall out of range.
- **No download resume.** Cancel at 1.4 GB and you start over.
- **No UI tests.** The logic and the model pipeline are covered; the screens are not.
- **First load is slow.** The runtime prepares a cached copy of the model once; later loads are quick.

## Requirements

- Android **8.0 (API 26)** or newer, **ARM64**
- ~3 GB free storage for the default model
- Android Studio Ladybug or newer, JDK 17+, to build
- No special hardware, no allowlist, no AICore

---

<div align="center">
<sub>

Typeface [Hanken Grotesk](https://github.com/hanken-design/HK-Grotesk) by Hanken Design Co., SIL OFL 1.1<br>
Models [Gemma 4](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)
and [LFM2.5](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct),
converted to LiteRT-LM by [litert-community](https://huggingface.co/litert-community)

</sub>
</div>
