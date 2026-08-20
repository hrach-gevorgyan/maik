<div align="center">

# maik.

**A chat app whose model lives on your phone.**

No account. No API key. No server. Turn on airplane mode — it still answers.

<sub>Kotlin · Jetpack Compose · MediaPipe LiteRT</sub>

</div>

---

## ⚠️ READ THIS IF YOU CAME HERE FOR GEMINI NANO

**THE GALAXY S24 CANNOT RUN GEMINI NANO IN THIRD-PARTY APPS. GOOGLE'S OWN
DOCUMENTATION SAID IT COULD. THAT LIST WAS QUIETLY CHANGED.**

`maik` started out on the Google AI Edge SDK (`com.google.ai.edge.aicore`), which
borrows the Gemini Nano already sitting inside Android's **AICore** system
service. On a Galaxy S24 Ultra, every call dies with:

```
com.google.ai.edge.aicore.UnknownException:
AICore failed with error type 2-INFERENCE_ERROR and error code 8-NOT_AVAILABLE:
Required LLM feature not found
```

Here is what that actually means, and why no amount of updating will fix it:

- Your S24 **does** run Gemini Nano. That is what powers Samsung's Galaxy AI
  features. It is simply **not exposed to apps that aren't Samsung's or Google's.**
- Google's supported-device list **originally included the Galaxy S24 series.** It
  was later narrowed to Pixel 9. A developer on Google's own forum reported buying
  an S24 specifically because of that list, hit this exact error, and
  [received no reply](https://discuss.ai.google.dev/t/google-ai-edge-sdk-supported-android-devices/67403).
- It is not even reliable on Pixels. The same error is filed against **Google's own
  sample app** by a user on a **Pixel 9 Pro** —
  [android/ai-samples#24](https://github.com/android/ai-samples/issues/24) — closed,
  no fix.
- **There is no workaround.** No adb flag, no allowlist, no beta channel, no
  Play Store update. The capability is gated server-side, per device.

**SO MAIK NO LONGER USES GEMINI NANO AT ALL.** It brings its own model instead.
That model is yours, it works on your phone, and no one can revoke it from a
dashboard.

---

## How it works now

`maik` runs a **LiteRT** model through **MediaPipe LLM Inference**, entirely in your
app's own process. Same promise as Nano — nothing leaves the device — minus the
dependency on Google deciding your phone qualifies.

On first launch it fetches the model once (**~521 MB**, one time, Wi-Fi
recommended) and stores it in app-private storage. After that the network is
never touched again. The `INTERNET` permission exists for that single download and
nothing else.

| | |
|---|---|
| **Default model** | Qwen2.5 0.5B Instruct, int8 — ungated, Apache 2.0, no token needed |
| **Runtime** | `com.google.mediapipe:tasks-genai` |
| **Works on** | Any Android 8.0+ phone. Yes, including your S24 Ultra. |

Want something sharper? `Models.DEFAULT` in
[`ModelStore.kt`](app/src/main/java/com/maik/app/ModelStore.kt) also has
**Qwen2.5 1.5B** (~1.5 GB) wired up — change one line.

Gemma 3 1B is the better model at this size, but its Hugging Face repo is **gated**,
which would force every user through a sign-in and a license click before the app
could download anything. Not worth it for a chat toy. If you want it anyway, point
`ModelSpec.url` at the Gemma `.task` and add an `Authorization: Bearer <hf_token>`
header in `ModelStore.download()`.

## Download

Prebuilt APK: **[`dist/maik-debug.apk`](dist/maik-debug.apk)** (~57 MB — the model is
*not* bundled, it downloads on first run).

It's **debug-signed**: fine for sideloading, useless for the Play Store. Enable
"install unknown apps" for whatever you open it from.

## Build it yourself

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and hit Run.

## Look

A deliberately narrow design: near-black, bone white, one acid accent — and
**HK Grotesk** (Hanken Grotesk) everywhere, shipped as one variable font and
instanced per weight rather than bundled six times over.

| | |
|---|---|
| **Wordmark** | `maik.` — text only, 800 weight, tight negative tracking |
| **Palette** | `#08080B` ink · `#EDEDF2` bone · `#D8FF3E` acid |
| **Type** | HK Grotesk 300–800, one 130 KB variable file |
| **Motion** | Pulsing on-device dot, staggered typing dots, animated send state |

## Layout

```
app/src/main/java/com/maik/app/
├── MainActivity.kt    — Compose UI: setup screen, bubbles, typing dots, composer
├── ChatViewModel.kt   — stage machine, model lifecycle, prompt assembly
├── ModelStore.kt      — model catalog + resumable-safe download
└── Theme.kt           — palette + HK Grotesk type scale
```

Two things worth knowing if you fork this:

**The model is stateless between turns.** A fresh `LlmInferenceSession` is created
per message and the whole transcript is replayed, so `clear` genuinely clears and
context never leaks between conversations. Fine for short chats; long ones will hit
the 1280-token window, so start summarizing or windowing.

**Downloads land in a `.part` file** and are only renamed on success — an
interrupted download can never masquerade as a working model. If the bundle fails
to load anyway, it's deleted so the next attempt refetches cleanly.

## Requirements

- Android **8.0 (API 26)** or newer
- ~600 MB free storage
- Android Studio Ladybug+ / JDK 17+
- **No special hardware, no allowlist, no AICore**

## Credits

Type is [Hanken Grotesk](https://github.com/hanken-design/HK-Grotesk) by Hanken
Design Co., SIL Open Font License 1.1.
Model is [Qwen2.5-0.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct),
Apache 2.0, converted to LiteRT by the litert-community.
