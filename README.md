<div align="center">

# maik.

**A chat app for the model that's already on your phone.**

No account. No API key. No network. Nothing leaves the device.

<sub>Kotlin · Jetpack Compose · Gemini Nano via Android AICore</sub>

</div>

---

## What it is

Most "AI chat" apps are a text box wired to somebody else's server. `maik` isn't.
It talks to **Gemini Nano**, the model Android already ships inside the **AICore**
system service, through Google's [AI Edge SDK](https://developer.android.com/ai/gemini-nano).

Turn on airplane mode. It still answers.

## Look

A deliberately narrow design: near-black, bone white, one acid accent — and
**HK Grotesk** (Hanken Grotesk) set everywhere as a variable font, instanced per
weight rather than bundled six times over.

| | |
|---|---|
| **Wordmark** | `maik.` — text only, 800 weight, tight negative tracking |
| **Palette** | `#08080B` ink · `#EDEDF2` bone · `#D8FF3E` acid |
| **Type** | HK Grotesk 300–800, one 130 KB variable file |
| **Motion** | Pulsing on-device indicator, staggered typing dots, animated send state |

## Run it

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Push it to a device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or just open the folder in Android Studio and hit Run.

## Will it work on your phone?

**Honest answer: maybe not.** Gemini Nano is not a library you ship — it's a
system capability, and Google gates it to a short list of devices.

**Supported today** — Pixel 9 / 9 Pro / 9 Pro XL, Samsung Galaxy S24 and S25
series, and a handful of other flagships.

On a supported device, before your first message:

1. Update **AICore** and **Android System Intelligence** from the Play Store.
2. Give it a moment — the weights stream down in the background on first use, so
   the first answer may take a while or fail once. Retry on Wi-Fi.

Anywhere else, the app installs and opens fine, but the model call fails. `maik`
catches that and prints the reason in a red bubble instead of crashing on you.

### If your device isn't on the list

Swap the backend for **MediaPipe LLM Inference** (`com.google.mediapipe:tasks-genai`)
and ship a Gemma 3 1B `.task` file. Still fully on-device, works on nearly any
modern phone — the tradeoff is that the model rides along in your APK instead of
already living on the system. Only `ChatViewModel` changes; the UI doesn't care.

## How it's put together

```
app/src/main/java/com/maik/app/
├── MainActivity.kt    — Compose UI: wordmark, bubbles, typing dots, composer
├── ChatViewModel.kt   — model lifecycle, prompt assembly, error surfacing
└── Theme.kt           — palette + HK Grotesk type scale
```

One thing worth knowing if you fork this: **Nano is stateless.** It holds no
conversation memory between calls, so `buildPrompt()` replays the whole
transcript on every turn. Fine for a short chat; if yours run long, start
summarizing or windowing — `maxOutputTokens` is 512 and the context isn't
generous.

## Requirements

- Android **12 (API 31)** or newer
- Android Studio Ladybug+ / JDK 17+
- A device with AICore — see above

## Credits

Type is [Hanken Grotesk](https://github.com/hanken-design/HK-Grotesk) by Hanken
Design Co., under the SIL Open Font License 1.1.
