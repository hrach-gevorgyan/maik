# Changelog

Written for people, not for parsers. Newest first.

Versions follow [semantic versioning](https://semver.org): the middle number moves
when maik gains something, the last one when something gets fixed.

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
