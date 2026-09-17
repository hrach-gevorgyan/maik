# Models

maik runs `.litertlm` bundles on Google's **LiteRT-LM** runtime, entirely inside the
app's own process. The catalogue lives in
[`data/ModelStore.kt`](../app/src/main/java/com/maik/app/data/ModelStore.kt).

> These are phone-sized models, meant for a plane, a tunnel or a foreign SIM. They
> know far less than a data-centre assistant, can be confidently wrong, and guarantee
> nothing. The app says so once on the first-run screen.

## What ships

| Model | Download | Context | License | Character |
|---|---|---|---|---|
| **Gemma 4 E2B** — default | 2.6 GB | 2048 | Apache 2.0 | Google's on-device model. The most capable here, and the only one that can look at a photo |
| **LFM2.5 1.2B** | 0.7 GB | 2048 | LFM Open License | A third of the download, quicker, easier on the battery. Text only |

Each stays on disk once fetched. A conversation remembers the model it started with,
and maik asks before answering an old chat with a different one.

## The rules an entry must satisfy

- **A `.litertlm` bundle.** The older `.task` format is no longer published, and maik
  no longer reads it.
- **Ungated on Hugging Face.** Anything behind a licence click cannot be downloaded by
  an app with no account.
- **The generic build.** The `-gpu`, `-web` and chip-specific variants refuse to load
  anywhere else.
- **Pinned to a commit.** URLs point at a revision, never at `main`, so a file cannot
  change under a published SHA-256.
- **Small enough for a phone.** `Models.MAX_SENSIBLE_BYTES` is the ceiling, and a unit
  test enforces it.

## Why nothing bigger

Phi-4-mini at 3.8B was tried and pulled. It ran the phone hot enough to throttle, took
over a minute per answer, and then locked up. Decoding is memory-bandwidth-bound: every
word written re-reads the whole model, so a bigger file costs proportionally more heat
and battery for each word. On a phone that ceiling arrives long before quality stops
improving.

## Verification

Four early releases were broken by bundles nobody inspected. Two checks now stand in
the way, and both run in CI.

**[`tools/verify_models.py`](../tools/verify_models.py)** range-requests the first
bytes of each bundle — not gigabytes — and confirms it is a real LiteRT-LM file (the
`LITERTLM` magic), of the declared size, a generic build, from an ungated source, and
under the size ceiling.

```bash
python tools/verify_models.py
```

**[`GoldenTest.kt`](../app/src/androidTest/java/com/maik/app/GoldenTest.kt)** boots an
emulator, downloads LFM2.5 1.2B, loads it, and asks for the capital of France. It fails
unless the answer says Paris, the reply stops on its own without leaking template
markup, and a conversation remembers an earlier turn. **A release cannot publish unless
it passes.**

```bash
./gradlew connectedDebugAndroidTest
```

## Adding one

1. Add a `ModelSpec` to `Models` with a pinned URL, the exact byte size, and the
   SHA-256 that Hugging Face lists for the LFS object.
2. Add its one-line blurb to `strings.xml`.
3. Set `vision = true` only if the bundle really carries an image encoder — check for
   a `tf_lite_vision_encoder` section in the first hundred kilobytes.
4. Set `heavy = true` if the phone warms noticeably while it answers.
5. Run `python tools/verify_models.py` and `./gradlew test`.

## How answers are shaped

maik never formats a prompt. Each bundle carries its own chat template and stop
tokens and LiteRT-LM applies them; maik sends plain text plus the conversation
history. Building prompts by hand on the previous runtime is what produced the
garbled replies of 1.4 and 1.5.

Two things maik does control:

- **The standing instructions**, `DEFAULT_SYSTEM_PROMPT`, handed over before every
  conversation. They tell the model it is offline with no tools, ask it to commit to
  an answer rather than hedge, and ask it not to invent exact figures to look
  thorough. Editable in Settings; a prompt the user has written is never replaced by
  an update.
- **Sampling.** `temperature 0.8 / topK 64 / topP 0.95`. Lower makes a small model
  flat and evasive, higher makes it invent commands and prices with total confidence.
  The seed changes every turn so Regenerate genuinely differs.
