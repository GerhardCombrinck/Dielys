# 0016 — Voice input is the phone's recognizer plus a cleanup on Workers AI

**Status:** Accepted, subject to the phase 0 bake-off
**Date:** 2026-10-26
**Builds on:** F1 (protocol first), [ADR 0001](0001-workers-and-durable-objects.md) (Durable
Objects do not run in Africa), ADR 0007 (account deletion), ADR 0012 (per-member settings in
`UsersRoom`). Full spec: [docs/VOICE.md](../VOICE.md).

## Context

Adding items by voice has to be fast and accurate for South African English and Afrikaans,
and it should get better with use. Plain transcription is not enough: "milk" transcribed
perfectly is still an edit if the list says "Melk". The measure that matters is how often the
person has to fix what voice filled in.

The Android recognizer on most phones is Google's or Samsung's. Samsung's offers neither South
African English nor Afrikaans as an offline pack, and whether Google offers them offline varies
by phone. Workers AI offers Whisper and small LLMs at a fraction of a cent per call.

## Decision

- **Hold the send button to talk; release to finish.** One item per hold. The words land in
  the field and the person taps Done; nothing saves automatically.
- **Google's recognizer turns speech into text on the phone**, asked for by name, offline when
  the language pack is installed and online otherwise. The language is the speaker's choice
  (app language by default, a remembered EN/AF switch), not detected.
- **The phone matches first.** A guess that matches an item already on the person's lists, or a
  mishearing they have corrected the same way twice, fills the field at once with no network
  call.
- **Otherwise a small LLM on Workers AI cleans it up**, using context the phone sends: the
  alternatives the recognizer heard, about 200 of the person's own items, and about 20 of their
  past corrections. It may fix words and copy the style of existing items. It may not translate,
  add words, or return more than one item. An answer it cannot trace to its inputs is discarded.
- **Corrections are personal and synced to the account** in `UsersRoom`, so a reinstall keeps
  the learning. The cleanup request itself needs no Durable Object.
- **Android 13 and up only**, because recording from the press and biasing the recognizer both
  need it.
- **Nothing is built until a bake-off passes:** at least 95% of items needing no edit in each
  language, and p90 waits of 2 s for new items and 0.5 s for known ones.

## Alternatives rejected

- **Send the audio to Whisper on Workers AI.** One engine for Android and web, but slower
  (recognition only starts after upload), no offline, weaker on short Afrikaans clips, prone to
  inventing text on near-silent clips, and Dielys would start handling voice recordings. Kept as
  contestant B in the bake-off; if only B passes, this ADR is revisited.
- **A model inside the app.** Large download, slow on mid-range phones, weak Afrikaans.
- **Live text while speaking.** It would be replaced by the cleaned result anyway.
- **Save on release, fix afterwards.** Faster when right, but the review step is also where the
  corrections the app learns from come from.
- **Several items per hold, split on pauses or on "en"/"and".** Splitting on "and" breaks "salt
  en peper"; pause-splitting fights Android's recognizer restarting. One item per hold is
  simpler.
- **Auto-detecting the language.** Least reliable on one- or two-word phrases, which is what a
  list item usually is.
- **Corrections on the phone only.** Lost on reinstall.

## Consequences

- Audio goes to Google when no offline pack is installed, which will be most phones for SA
  English and Afrikaans. The privacy policy has to say so, and the Play data-safety audio answer
  needs revisiting.
- Item text, the person's own items and their corrections reach Workers AI. Dielys already holds
  all of it.
- A new account-level table, its sync, and a cleanup route all go through `protocol/` first.
- Cleanup costs scale with use: roughly 1,500 free cleanups a day, then about $0.07 per 1,000 on
  Workers Paid. A per-user daily cap protects the shared allowance.
- Web voice and "Hey Google" are later phases. "Hey Google" cannot use the recognizer choice or
  biasing at all, because Google does the listening.
