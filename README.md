<div align="center">

# maik.

**A chat app whose model lives on your phone.**

Turn on airplane mode. Ask it something. It answers.

[![Build](https://github.com/hrach-gevorgyan/maik/actions/workflows/build.yml/badge.svg)](https://github.com/hrach-gevorgyan/maik/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/hrach-gevorgyan/maik)](https://github.com/hrach-gevorgyan/maik/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-brightgreen)](#requirements)

[**Download**](https://github.com/hrach-gevorgyan/maik/releases/latest) ·
[What to expect](#what-to-expect) ·
[Models](#the-models) ·
[Docs](docs/)

</div>

---

## What it is

Most "AI chat" apps are a text box wired to somebody else's GPU. maik isn't.

The weights sit in your app's private storage and inference runs on your own silicon.
The app makes exactly one network request in its life — the one that downloads the
model. After that the radio is never touched again. No account, no API key, no server,
no telemetry.

## What to expect

> **maik is a small model that fits in a pocket.** It is for the plane, the tunnel,
> the foreign SIM and the dead-zone hike — the moments when something is better than
> nothing. It is far weaker than the assistants that run in a data centre: it knows
> less, it holds a shorter thread, and it can be wrong with complete confidence.
> **Nothing it says is guaranteed. Check anything that matters.**

That is the whole trade. In exchange, it works with the radio off, costs nothing to
run, and no sentence you type is sent anywhere. The app says this once, on the
first-run screen, and never nags about it again.

## Install

Download the APK from [**Releases**](https://github.com/hrach-gevorgyan/maik/releases/latest)
and open it on your phone. You will need "install unknown apps" enabled for whatever
you open it from.

On first launch it fetches a model — around 2.6 GB, once, over Wi-Fi. An interrupted
download continues where it stopped. That is the only download it will ever ask for.

> **Note**
> Releases are currently **debug-signed**, so each one carries a different key.
> Uninstall the previous version before installing a new one — which also clears your
> chats and downloaded models. [How to fix that permanently](docs/RELEASING.md#signing).

## Features

| | |
|---|---|
| **Conversations** | As many as you like, titled from your first message. Search by title or content; long-press to pin, rename or delete, or swipe to delete |
| **Message actions** | Long-press any message to copy it, select part of it, share it, read it aloud or delete it — or edit one of yours and ask again from there. Code blocks have their own copy button |
| **Photos** | Ask about a sign, a menu or a label. The photo is shrunk and kept private on the phone; Gemma 4 E2B can see it |
| **Voice** | Speak your question through the phone's own recogniser, and have replies read back |
| **Streaming replies** | Words arrive as they are generated. Stop mid-sentence and whatever was written is kept |
| **Short or detailed** | A toggle in the chat; short is the default, and it is easier on the battery |
| **Search** | Across conversations, and within one |
| **Markdown** | Headings, lists, bold, inline code, and code blocks that scroll rather than stretch the screen |
| **Regenerate** | Ask again from the same point when an answer misses |
| **A model per chat** | Switch from the chat header. Each conversation keeps the model it started with |
| **Instructions** | A standing note handed to the model before every conversation, editable in Settings |
| **Export and backup** | Share a chat as text, or back every chat up to a file and restore it |
| **Quick launch** | A home-screen shortcut and a Quick Settings tile that open straight into a new question |
| **Light and dark** | Or follow the system. Colours crossfade rather than snap |
| **Honest downloads** | A background service with a progress notification. Survives the lock screen, warns before spending mobile data, and verifies the file before accepting it |
| **Keep the phone cool** | Fewer cores, shorter replies, and a hard stop if the phone overheats — on by default, off for maximum speed |
| **Built for every screen** | Readable width in landscape, on tablets and in split view; large font sizes; TalkBack labels, headings and actions; predictive back |

## The models

Two ungated models, both built to run on a phone.

| Model | Download | License | Character |
|---|---|---|---|
| **Gemma 4 E2B** — default | 2.6 GB | Apache 2.0 | Google's on-device model. The most capable here, and the only one that can look at a photo |
| **LFM2.5 1.2B** | 0.7 GB | LFM Open License | A third of the download, quick, easy on the battery. Text only |

Switch from the chat header or in Settings. Each stays on disk once fetched. Why these
two, why nothing bigger, and how a model is verified before it ships:
[docs/MODELS.md](docs/MODELS.md).

## Privacy

- Your conversations and your photos never leave the phone. They are excluded from
  Android's cloud backup, so not even Google gets a copy.
- Settings do travel through that backup, including the standing instructions you
  write — keep anything private out of them, or switch Android's backup off.
- The only host maik ever contacts is `huggingface.co`, to download a model.
- No analytics, no crash reporting, no account, no advertising ID.
- Uninstalling removes everything, models included.

## Why not Gemini Nano

maik began on the Google AI Edge SDK, which borrows the Gemini Nano already sitting
inside Android's **AICore** service. On a Galaxy S24 Ultra every call died with:

```
AICore failed with error type 2-INFERENCE_ERROR and error code 8-NOT_AVAILABLE:
Required LLM feature not found
```

- **The S24 does run Gemini Nano** — it powers Samsung's own Galaxy AI. It is simply
  not exposed to third-party apps.
- **Google's supported-device list originally included the S24 series**, then narrowed
  to Pixel 9. A developer who bought an S24 because of that list hit this exact error
  and [received no reply](https://discuss.ai.google.dev/t/google-ai-edge-sdk-supported-android-devices/67403).
- **It is not reliable on Pixels either.** The same error is filed against Google's own
  sample app by a Pixel 9 Pro owner —
  [android/ai-samples#24](https://github.com/android/ai-samples/issues/24), closed
  without a fix.
- **There is no workaround.** No adb flag, no allowlist, no beta channel. The
  capability is gated server-side, per device.

So maik brings its own model — one nobody can revoke from a dashboard.

## Documentation

| | |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How it works: the engine, the session, downloads, persistence, heat |
| [docs/MODELS.md](docs/MODELS.md) | The catalogue, the rules an entry must meet, verification, sampling |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Updates, crashes, heat, downloads, poor answers |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | Build, test, house style, adding a model |
| [docs/RELEASING.md](docs/RELEASING.md) | Versioning, tags, signing, what CI runs |
| [CHANGELOG.md](CHANGELOG.md) | What changed, in sentences |

## Building

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run. Details in
[docs/CONTRIBUTING.md](docs/CONTRIBUTING.md).

## Design

One typeface, one accent, no decoration.

| | |
|---|---|
| Wordmark | `maik.` — text only, 800 weight, tight negative tracking |
| Palette | `#08080B` ink · `#EDEDF2` bone · `#D8FF3E` acid |
| Type | HK Grotesk 300–800, one 130 KB variable file |
| Icon | The wordmark itself, rendered from that same font |

## Known limits

- **The model is small** — see [what to expect](#what-to-expect).
- **Long chats forget.** Context is 2048 tokens, roughly 20 short exchanges; older
  turns are dropped first, and the chat says so.
- **One download at a time.** Starting a second while one runs tells you to wait.
- **First load is slow.** The runtime prepares a cached copy of the model once; later
  loads are quick.
- **Few UI tests.** The chat's message actions and composer are covered; most other
  screens are checked by hand.

## Requirements

- Android **8.0 (API 26)** or newer, **ARM64**
- ~3.3 GB free storage for the default model: the 2.6 GB file plus the runtime's
  prepared copy
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
