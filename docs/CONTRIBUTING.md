# Working on maik

> maik runs a small phone-sized model for offline use — a plane, a tunnel, a foreign
> SIM. Answers are weak next to a data-centre assistant and are never guaranteed. Said
> once in the app on the first-run screen, and stated in
> [the README](../README.md#what-to-expect); please do not sprinkle further disclaimers
> through the UI.

## Build and run

```bash
git clone https://github.com/hrach-gevorgyan/maik.git
cd maik
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run. JDK 17+, Android Studio Ladybug or
newer, an ARM64 device or emulator.

## Checks

```bash
./gradlew test                      # unit tests — fast, no device
./gradlew lintDebug lintRelease     # Android lint; CI fails on errors
./gradlew connectedDebugAndroidTest # UI tests and the golden model test; needs a device
python tools/verify_models.py       # the model catalogue, over range requests
```

Run at least `test` and `lintDebug` before pushing. The golden test downloads a
0.7 GB model the first time and takes a few minutes; CI runs it for you on tags.

## House style

The code is written to be read by someone who has never seen it before.

- **Comments explain why, not what.** If a line is surprising, say what would go wrong
  without it. Never narrate the code beneath.
- **Names are ordinary English.** `keepCool`, `replyCap`, `sessionStale` — not
  `mThermalMitigationEnabled`.
- **User-visible text lives in `res/values/strings.xml`.** No string literals in
  composables. English is the source language.
- **Public API is the exception.** Prefer `private`, then `internal`.
- **A test for anything that can silently rot**: format strings, the model catalogue,
  parsing, budget arithmetic, navigation.
- Match the surrounding file. Consistency beats personal preference.

## Where things live

See [ARCHITECTURE.md](ARCHITECTURE.md) for the layout and the rules that matter —
particularly the single engine, the single native thread, and the fact that the
runtime, not maik, formats prompts.

## Adding a model

A model must be a `.litertlm` bundle, ungated, and a generic build. See
[MODELS.md](MODELS.md); `tools/verify_models.py` and a unit test both enforce the
rules, so a bad entry cannot reach a release.

## Pull requests

- One subject per pull request.
- The description says what changed and why; if behaviour changed, say what a user
  will notice.
- Add a `CHANGELOG.md` entry for anything a user would notice. Human sentences.
- Green CI is required.
