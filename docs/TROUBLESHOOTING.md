# When something goes wrong

> maik runs a small phone-sized model for offline use — a plane, a tunnel, a foreign
> SIM. It knows far less than a data-centre assistant, can be confidently wrong, and
> nothing it says is guaranteed. See [what to expect](../README.md#what-to-expect).

## "App not installed" when updating

Expected, for now. Releases are debug-signed and every build carries a different key,
so Android refuses to treat the new one as an update. Uninstall the old version first
— which also removes your chats and the downloaded model.

This goes away as soon as a signing key is set up: see
[RELEASING.md](RELEASING.md#signing).

## The answers are poor

Some of this is the model and some of it is fixable:

- **Ask one thing at a time.** A small model loses the thread of a compound question.
- **Give it the context in the question.** It cannot look anything up.
- **Turn off short answers** (the toggle in the chat) when you want reasoning rather
  than a summary.
- **Try Regenerate.** The seed changes every turn, so a second attempt genuinely
  differs.
- **Edit the instructions** in Settings → Instructions if you want a different voice or
  standing rules. Your own wording is never replaced by an update.
- **Start a new chat** for a new subject. Context is 2048 tokens; a long thread pushes
  the beginning out, and the chat says so when that happens.

For anything with an exact answer — a price, a timetable, a command, a citation —
assume it is wrong until checked. That is the trade for having it work offline.

## It crashed, or closed while starting

Almost always the graphics chip. Some drivers crash natively during model load, where
no error handling can catch it. maik writes a marker before each attempt and turns the
GPU off by itself when it finds one at the next launch, so simply reopening usually
fixes it. The processor is the default and is also the cheaper of the two for writing
replies.

If it keeps happening on the processor, the model is probably too big for the phone's
free memory. Try LFM2.5 1.2B in Settings → Models.

## It gets hot, or stops mid-reply

Writing a reply re-reads the entire model for every word, so heat is the cost of the
answer. **Keep the phone cool** (Settings → Behaviour, on by default) uses fewer
cores, caps reply length, refuses to start while the phone is already throttling and
stops a reply if it gets there. Turning it off gives longer, faster replies and a
hotter phone.

A phone in a case, in the sun, on a charger, will throttle far sooner. So will the
graphics chip, if you have switched to it.

## It is slow

The first load after installing a model is the slow one: the runtime prepares its own
copy, once. After that, loads take a few seconds and only happen when the model
changes or the memory is reclaimed.

If every reply is slow, use the smaller model, keep the question short, and leave
short answers on.

## The download will not finish

- It resumes. Reopen maik and press Continue; the partial file is kept and the
  download carries on from where it stopped.
- It verifies. A file whose checksum does not match the catalogue is refused rather
  than installed, so a corrupted download shows as a failure, not as a broken model.
- It needs room: about 3.3 GB free for the default model, counting the runtime's
  prepared copy.
- Mobile data is warned about before it is spent, never after.

## The model file is gone, or maik asks to download again

Android can delete app files when storage runs out. maik checks the file is present
and intact at launch, so it asks rather than crashing. Free some space and download
again.

## Chats disappeared

maik keeps one copy of any history file it cannot read, as
`conversations.corrupt.json` in its private storage, and refuses to save over the
original for the rest of that run. On a rooted phone or via `adb run-as` that file can
be recovered by hand.

Back up from Settings → Storage before anything risky. Note that chats are
deliberately **not** in Android's cloud backup.

## Voice or read-aloud does nothing

Both use the phone's own services. Voice typing needs a speech recogniser with its
offline language pack installed; read-aloud needs a text-to-speech voice. Android's
settings can install either. maik does not ship its own.

## Photos are not offered

Only Gemma 4 E2B can see images. With LFM2.5 selected, the camera button is not there.
