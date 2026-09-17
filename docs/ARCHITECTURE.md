# How maik works

A `.litertlm` model running on Google's **LiteRT-LM** runtime, inside the app's own
process. No server, no account, no telemetry. One network request in the app's life:
the one that downloads a model.

> The model is phone-sized, for offline use — a plane, a tunnel, a foreign SIM. It
> knows far less than a data-centre assistant, can be confidently wrong, and
> guarantees nothing. Said once in the app, on the first-run screen.

## The shape of it

```
app/src/main/java/com/maik/app/
├── MaikApp.kt              the process; gives the model back under memory pressure
├── MainActivity.kt         the activity, splash screen and top-level navigation
├── Navigation.kt           where Back goes
├── ChatViewModel.kt        screen state, model lifecycle, generation
├── AskTileService.kt       the Quick Settings tile
├── engine/
│   ├── LocalEngine.kt      the one loaded model, owned by the process
│   ├── ContextBudget.kt    how much history seeds a conversation
│   └── Thermal.kt          the phone's thermal state and thread count
├── data/
│   ├── ModelStore.kt       model catalogue, settings, resumable verified downloads
│   ├── ResumePlan.kt       whether a download continues or starts again
│   ├── DownloadRouting.kt  what a download event does to the screen
│   ├── DownloadService.kt  foreground download and its event bus
│   ├── Conversations.kt    chats, filtering, tolerant atomic JSON persistence
│   └── Photos.kt           imported photos: shrunk, private, tidied with their chat
└── ui/
    ├── chat/               chat screen, status strip, bubbles, composer, Markdown
    ├── list/               conversation list and search
    ├── setup/              first run and the download screen
    ├── settings/           settings menu and its pages
    ├── components/         shared buttons, bars and hand-drawn glyphs
    └── theme/              colours, type and motion
```

There is no repository layer and no dependency-injection framework. One ViewModel
holds screen state; three small classes own the disk; one object owns the engine.

## The rules that matter

**The runtime owns the prompt format.** Each bundle carries its own chat template and
stop tokens and LiteRT-LM applies them. maik sends plain text and the conversation
history, never a hand-built prompt. Doing that by hand on the previous runtime is what
produced the garbled replies of 1.4 and 1.5.

**One engine for the whole app.** The loaded model belongs to the process, not to a
screen, so rotating the phone or reopening the app does not reload 2.6 GB. Every
native lifecycle call — create, initialise, open a conversation, close — runs on a
single-threaded dispatcher, so two loads can never overlap and nothing is closed
underneath another call.

**The model is given back when memory is short.** `MaikApp.onTrimMemory` releases the
engine at `TRIM_MEMORY_BACKGROUND` and above, unless a reply is being written. Holding
2.6 GB while the user is in the camera only invites the system to kill the process,
which costs the conversation as well as the model.

**One runtime conversation per chat.** It keeps the model's context between turns and
survives Stop. Reopening a chat seeds a fresh one with the most recent turns that fit
the context budget.

**Stop really stops.** Generation is cancelled in the runtime, not merely ignored.
A cancelled conversation is never answered on again, so it is closed straight away and
the next question rebuilds one.

**The GPU cannot be trusted to fail safely.** It crashes natively on some drivers,
where no `catch` can see it. It is off by default, and a breadcrumb written before each
attempt means a crash during load turns it back off by itself.

**Downloads resume.** Bytes land in a `.part` file and continue where they stopped.
Each URL is pinned to a Hugging Face commit, and only a file whose SHA-256 matches the
catalogue and which starts with the `LITERTLM` magic is renamed into place.

**Chats are written atomically, off the main thread.** A crash mid-save leaves the
previous history intact. If the file is present but unreadable, maik keeps a copy
aside and refuses to save over it for the rest of that run — a temporary disk error
must not become permanent loss. A failed write is reported rather than swallowed.

**Nothing personal leaves the phone.** Chats and attached photos are excluded from
Android's cloud backup; only settings travel. A direct phone-to-phone transfer, which
never touches a server, carries everything but the models.

**Heat is a design constraint, not an accident.** Writing a reply re-reads the whole
model for every word, so decoding is memory-bandwidth-bound and heat scales with model
size. maik decodes on the CPU with three threads (four with Keep cool off), caps
replies at 384 tokens (768 with Keep cool off), refuses to start while the phone is
already throttling, and stops a reply if it gets there.

**All user-facing text lives in `res/values/strings.xml`.** English is the source
language, ready for translation.

## State, briefly

`ChatViewModel` holds three independent things and it helps to keep them apart:

| | |
|---|---|
| `Stage` | what the engine is doing — needs a model, downloading, loading, ready, broken |
| `Screen` | which screen is in front — list, chat, settings, setup |
| `busy` | whether a reply is being written right now |

`Stage` is deliberately not a screen: a download continues while you read an old chat,
and the chat's status strip reports it.

## Sampling and instructions

The two things maik controls about answer quality. Both live next to each other on
purpose — see [MODELS.md](MODELS.md#how-answers-are-shaped) for the current values and
the reasoning. In short: sampling sits slightly above the middle so replies have life,
and honesty is asked for in words rather than bought by making the model timid.

## What is tested

Around eighty unit tests cover the things that rot silently: format strings in
`strings.xml`, the model catalogue and its size ceiling, resume arithmetic, context
budgeting, markdown splitting, history decoding and salvage, relative times, download
routing and navigation. On a device, `ChatUiTest` drives the chat screen and
`GoldenTest` makes a real model answer a real question.
