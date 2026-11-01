# Voice input

**Status:** Spec agreed 2026-10-26. Nothing is built yet; phase 0 (the bake-off) comes first.
**Decision record:** [ADR 0016](adr/0016-voice-input.md).

Hold the send button on an empty add field, say one item, let go. The item lands in the field,
already cleaned up, and you tap Done as you would after typing it.

The aim, borrowed from Wispr Flow, is **fewest corrections**, not best transcription. A
transcript that is word-perfect but spelled differently from the "Melk" already on your list
still costs you an edit. So recognition is only the first step: the result is matched against
your own items, cleaned up by a small LLM that knows your lists, and every fix you make teaches
it for next time.

## Pass mark

Two numbers, measured from the moment you **let go of the button**, on mobile data (not Wi-Fi):

| Measure | Target |
|---|---|
| **No-edit rate**: items saved without changing what voice filled in | **≥ 95%**, for **English and Afrikaans separately** |
| **Wait for a new item** (field filled after release), 9 in 10 items | **≤ 2.0 s** |
| **Wait for an item you already have**, 9 in 10 items | **≤ 0.5 s** |

95% means about one fix in a 20-item weekly shop. Each language must pass on its own, so strong
English cannot hide weak Afrikaans. List names are not part of the target (see Phases).

## Phases

| Phase | What | Gate |
|---|---|---|
| **0. Bake-off** | Recorded test set, two pipelines, scored against the pass mark | Must pass before phase 1 is built |
| **1. Android, items** | This spec | Ships to everyone once the bake-off passes |
| **2. Web** | Same cleanup endpoint and corrections; the browser's recognizer | Later |
| **3. "Hey Google"** | Adding an item without opening the app | Later, its own spec (notes at the end) |

**Not in phase 1:** list names (new and rename), web, "Hey Google", Android 12 and older, more
than one item per hold.

## The interaction

- **One item per hold.** Press, say "melk", let go. Pauses do not split items, and nothing is
  split on "en"/"and" ("salt en peper" stays one item).
- **The button:**
  - **Field empty:** the send button shows a mic. Hold to talk, release to finish. A quick tap
    shows a "Hold to talk" hint.
  - **Field has text:** the button shows the send arrow; a tap sends as today. Holding still
    works and **adds** the new words to the end of what is there.
  - **Editing an existing item:** no mic. Voice is for adding.
- **Release ends the recording.** There is no silence detection deciding you have finished, so a
  slow speaker is never cut off and the ~0.5 s "has he finished?" pause disappears.
- **Slide off the button before releasing to cancel**, as with WhatsApp voice notes.
- **A short buzz when the mic is actually listening.** While holding, a listening indicator
  shows. **No live text**: the field fills once, with the final result.
- **Nothing is saved automatically.** The words land in the field; you tap Done (or fix them
  first). Every fix made here is a correction the app learns from.
- **Language:** starts in the app's language (Settings). A small one-tap **EN/AF** switch beside
  the mic changes it, and the choice is remembered on the device. Language belongs to the
  speaker, not the list: two people on a shared list may speak different languages. Exact
  placement is settled in implementation, on a screenshot.
- **Mic permission** is asked on the first hold; after allowing it you hold again. If it is
  denied, a tap on the mic explains why and links to the app's settings.
- **TalkBack:** tap to start, tap to stop, instead of hold.
- **No mic at all** on Android 12 and older, or when the phone has no speech recognizer.

## How a hold becomes an item

1. **Press.** The app starts recording at once (its own `AudioRecord`) and feeds that audio to
   the recognizer through `RecognizerIntent.EXTRA_AUDIO_SOURCE` (Android 13+). Recording from
   the press means the first syllable is not lost while the recognizer starts up ("melk"
   arriving as "elk").
2. **The recognizer** gets the language (`en-ZA` or `af-ZA`) and the dictionary as
   `EXTRA_BIASING_STRINGS`, and asks for several alternatives (`EXTRA_MAX_RESULTS`).
3. **Release** ends the audio. The recognizer returns its final guesses.
4. **Instant match** (no network). Only when the field started empty:
   - If a guess, in progress or final, matches a dictionary item (ignoring capitals and
     surrounding or repeated spaces), the field gets **that item's spelling** straight away.
     Matching an in-progress guess is what makes the 0.5 s target possible; it is not shown
     until it matches, and a match is almost never replaced.
   - If the top guess is a **learned shortcut** (the same mishearing corrected to the same text
     at least twice, never contradicted), the field gets the corrected text.
5. **Otherwise, cleanup.** The phone sends the request below to the Worker, which asks a small
   Workers AI model and returns one item's text.
6. **Fallback.** No answer within **1.5 s**, offline, over the daily cap, or cleanup switched off:
   the field gets the recognizer's top guess as heard. The 2 s target holds either way.
7. **The field is filled.** When appending, only the new words are added; what was already in the
   field is never changed.

## The recognizer

- **Google's recognizer, asked for by name** (`SpeechRecognizer.createSpeechRecognizer` with
  Google's component) whenever it is installed; the phone's default only when it is not. Samsung
  phones default to Samsung's own engine, and on the development phone (2026-10-26) Samsung's
  language packs offer neither English (South Africa) nor Afrikaans. Tuning and measuring one
  engine and shipping on another would make the pass mark meaningless.
- **Offline when the language pack is installed, online otherwise.** `checkRecognitionSupport`
  (Android 13+) says which applies per language on each phone. Expect SA English and Afrikaans
  to run **online** on most phones. Online recognition streams while you are still holding, so
  most of the work is done by release.
- **Unproven, checked first in the bake-off:** whether Google's *online* recognizer accepts audio
  fed through `EXTRA_AUDIO_SOURCE`, and whether it honours `EXTRA_BIASING_STRINGS`. If audio
  feeding is refused, the fallback is to let the recognizer open the mic on press, buzz when it
  is ready, and set its silence timeout as long as it allows, accepting some clipped first words.

## The dictionary

Built **on the phone, at press time**, from what Room already holds. Nothing new is stored or
synced.

- **Sources, in order:** this list's items (open, ticked **and deleted**, since deletes are kept
  as tombstones until the list is left or deleted), then items on your other lists, plus your
  list names (archived ones included). Items other members added to shared lists count; they are
  your lists too.
- **Ranking:** how often the item was added (each week's "Melk" is its own row, so the count is
  already there) and how recently. Duplicates fold into one entry, keeping the most recent
  spelling.
- **Cap:** about **200 entries** (about 1,000 tokens).
- **Used three ways:** biasing strings for the recognizer, the instant match, and context for the
  cleanup.

## Corrections: the learning

- **Recorded when** voice filled an **empty** field and you changed the text before tapping
  Done. The pair is *what was heard* (the top guess) → *what you saved*, with the language and
  time.
- **Not recorded:** renaming an item after Done (usually a change of mind, "Melk" → "Lae-vet
  melk", not a mishearing), or edits to text you typed.
- **Personal, not per household.** They teach the app your voice; someone else makes different
  mistakes.
- **Synced to the account from the start**, so a reinstall or a new phone keeps the learning.
  Stored in `UsersRoom` beside the per-list notification choices (ADR 0012) and erased with the
  account (ADR 0007). The phone keeps a copy, writes new pairs through the outbox like any other
  change (so offline works), and pulls on sync. The server keeps the **last 500** per account.
- **Used two ways:** learned shortcuts (above), and about **20 pairs** in each cleanup request:
  the ones most like the current guesses, then the most recent.

## The cleanup

### What the phone sends

The phone assembles all context, so the Worker never looks anything up per request. A
`UsersRoom` call would cost the most time of anything in the pipeline: Durable Objects do not
run in Africa ([ADR 0001](adr/0001-workers-and-durable-objects.md)).

- The recognizer's guesses, with confidence scores where it gives them
- The text already in the field, if appending (context only)
- The language (`en` or `af`) and the list's name
- The dictionary (about 200 entries) and the relevant corrections (about 20)

### What the model may do

- **Fix the words**, choosing between the guesses using the dictionary and corrections.
- **Apply self-corrections**: "brood, nee, rolls" → "Rolls".
- **Copy the style of your existing items** (capitals, digits, units): if the list says "Melk
  2L", "two litres of milk" becomes "Melk 2L". There is no fixed formatting rulebook.
- **Never translate. Never add words that were not said. Always one item.**
- When appending, return only the new words; the existing text is context and is never changed.

### Guard rails

- No randomness (temperature 0), a short output limit, and a versioned prompt. Every prompt or
  model change is replayed against the bake-off set before it ships.
- **An answer the guard cannot trace is thrown away.** Every word of the output must come from a
  guess, a dictionary entry or a correction (ignoring case, allowing number words to become
  digits and common unit abbreviations); otherwise the phone gets the top guess as heard. The
  exact rule is settled in the bake-off.
- **Model:** chosen in the bake-off from two or three small Workers AI models.

## Server

- **A new authenticated route for cleanup.** It uses `authenticate()` only: the access token is
  verified in the Worker, with no `UsersRoom` call. It touches no list, so there is no
  membership check, and `ListRoom` is not involved.
- **Per-user daily cap of about 300 cleanups**, far above any real shop, so a bug or a leaked
  token cannot spend the shared allowance. Planned with the Workers rate-limiting binding keyed
  by user id (no Durable Object call); confirm it is available on the free plan.
- **Off switch:** a Worker var turns cleanup off for everyone without an app release. The phone
  then uses the top guess as heard.
- **Logs carry counts and timings, never the text** (D4).
- **Corrections sync** follows the `Membership.notify` pattern: fetched on sync, written through
  the outbox.

## Protocol first

Per [F1](CODE_STANDARD.md#standard-f1), before any server or app code: the cleanup request and
response, the correction record and its sync shapes go into `protocol/src`, `PROTOCOL.md` and
`protocol/fixtures/`, including the edge cases (empty guesses, appending, an answer the guard
rejects). Additive only, so no protocol version break (F2).

## Measuring in real use

Counts only, **never text**, per voice item: language, which path filled it (instant match,
learned shortcut, cleanup, fallback), whether it was edited before Done, and the wait in coarse
buckets. Aggregated per day and shown on the admin page. These numbers catch a model or prompt
change that makes things worse after launch.

## Timing

Where the wait after release goes, for shaving it stage by stage. Durations only, never text.

- **The Worker** sends a `Server-Timing` header on `/voice/command`: `auth`, `body` (reading the
  upload), `whisper`, `model`, `total`, plus `path` (matched, corrected, model, fallback) and
  `colo` (the Cloudflare data centre that ran it).
- **The phone** times its own stages (mic stop, dictionary, trim+wav, base64, json, token,
  decode, save), OkHttp's network phases (dns, tcp, tls, upload, wait, download, connection
  new or reused) and reads the header. "Round trip minus worker" is latency, transfer and
  Cloudflare's routing together.
- **Debug builds** have a **Voice timings** launcher entry: p50/p90/max per stage across this
  session's holds, a coloured bar per hold, and Copy CSV. Each hold is also one line in
  `adb logcat -s VoiceTiming`. Kept in memory only; release builds collect but never show it.
- **Benchmark set**, on the same screen: six fixed phrases (per language: one item already on
  the list, several items with one new, items for another list), each spoken against fixed
  lists so every replay sends the same request. Hold a row's Rec once to record it; **Replay
  ×3** sends the saved audio through the real client and marks each run pass or FAIL against
  the expected items. Samples sit in the debug app's external files (`adb pull
  /sdcard/Android/data/za.co.dielys.debug/files/voice-bench`) and never in git. Re-record
  only when the phrases change; otherwise numbers before and after a server change compare
  like for like.
- **Comparing speech models** (dev only): the Worker honours an `X-Voice-Stt` header on dev
  (`turbo`, the default and what ships, or `nova3`), and the screen's **Compare speech models**
  button replays every sample through each, interleaved.

### Findings, 2026-11-01 (Wi-Fi, Worker in Johannesburg)

- **The wait is the AI, not the phone or network.** Phone stages total under 70 ms; the network
  round trip minus the Worker is about 120–280 ms. Whisper Turbo is about 1.6 s p50 even on a
  0.7 s clip (mostly fixed overhead on Cloudflare's side), the model about 1.0–1.3 s.
- **The model was dropping items already in the dictionary** ("Existing items" read as "already
  on the list"). Prompt v3 calls them known spellings and says to add every item said.
- **One item per line instead of JSON** (prompt v4) took the answer from JSON with a list on
  every item to about 13 tokens and stopped the 3 s timeouts. Even so 13 tokens take about a
  second, so the model's time is mostly overhead too. A real example answer in the prompt was
  copied (an English speaker got "Koffie"), so v5 uses placeholders. When the model leaves out
  the list but the speaker named exactly one other list, the server applies it.
- **Speech models:** Turbo stays. Turbo without the dictionary prompt and the older `whisper`
  were slower and no more accurate. Deepgram nova-3 hears English in about 0.2 s but refused
  every Afrikaans clip and heard "Milk" as "Look" (key terms were passed as one joined string,
  probably wrongly).
- **Afrikaans accuracy is Whisper's**: "grondboontjiebotter" came back as "In-Horn, Broinky,
  Water" and "skottelgoedtablette" as "Skorl Goe Tablet". That needs its own fix.

## Cost

Estimates to confirm against Cloudflare's pricing page when the model is picked:

- About 1,500 tokens in and 15 out per cleanup: roughly **$0.07 per 1,000 cleanups** on a small
  Llama model. Instant matches, learned shortcuts and fallbacks cost nothing.
- Workers AI's free allowance (10,000 neurons a day) is roughly **1,500 cleanups a day** across
  all users. On the free Workers plan, running out does not bill; cleanup calls fail until the
  next day and the phone falls back to the top guess.
- **Move to Workers Paid ($5 a month) when the allowance is regularly hit.** That also lifts the
  10 ms CPU limit. Even 10,000 voice items a month stays well under a dollar.

## Privacy

- **Audio never reaches Dielys.** It is handled on the phone and passed to Google's recognizer:
  offline when the language pack is installed, otherwise sent to Google under Google's terms
  (as with Gboard voice typing). The privacy policy says this in plain words.
- **Text sent for cleanup is data Dielys already holds:** the item about to be saved, your own
  list items and your corrections. Cloudflare states it does not train on Workers AI inputs;
  confirm against the current terms before release.
- **Play data safety** needs revisiting before release: whether audio passed to Google's
  recognizer counts as "collected" (`docs/play/README.md` currently says audio is not
  collected), and whether the counts count as app interactions.

## Phase 0: the bake-off

Run before building the UI. A debug-only screen in the app, so it uses the real recognizer.

- **Test set:** about 50 phrases per language, taken from real lists. Include about 10 mixed
  phrases ("melk en bread"), about 10 self-corrections ("brood, nee, rolls") and a few items on
  no list yet (where the dictionary cannot help). At least two speakers.
- **Recorded once and replayed** to every pipeline through `EXTRA_AUDIO_SOURCE`, so the
  comparison is fair and repeatable. **Recordings stay out of git**: they are people's voices.
- **Checks first, on the test phone:**
  1. `checkRecognitionSupport` for `en-ZA` and `af-ZA`: offline, online, or neither.
  2. Whether the online recognizer accepts fed audio.
  3. Whether biasing strings change anything (run with and without).
- **Contestants:**
  - **A:** Google's recognizer → instant match → cleanup.
  - **B:** audio uploaded to Whisper large-v3-turbo on Workers AI (dictionary as its prompt) →
    the same cleanup.
- **Scored on** no-edit rate per language and p90 wait on mobile data. Every miss is labelled:
  *the recognizer never heard it* (no guess had the right word) or *the cleanup picked wrong*.
  Those need different fixes.
- **Outcome:**
  - **A passes:** build phase 1.
  - **Only B passes:** revisit ADR 0016. Audio would then go to Dielys's own infrastructure,
    which changes the privacy story and loses offline.
  - **Neither passes:** the miss labels say where to work.

## Open questions

- **Play Console:** what share of current users are on Android 12 or older? If it is large,
  revisit the Android 13+ cut-off.
- **Play data safety:** the audio and counts declarations above.
- **Rate-limiting binding** on the free plan.
- **Whether Workers AI runs inference near South Africa.** It sets most of the cleanup's network
  time; the bake-off measures it.
- **The exact guard rule**, and the EN/AF switch's exact placement.

## Phase 3 notes: "Hey Google"

Recorded for the later spec, as found on 2026-10-26:

- Google Assistant is being replaced by Gemini on Android. The older route for third-party apps,
  **App Actions** with built-in intents, belongs to the Assistant era. The newer one,
  **AppFunctions** (an Android 16 platform feature with a Jetpack library), was still early when
  it was announced in February 2026.
- Either way **Google does the listening and hands Dielys finished text**, so the recognizer
  choice, the language switch and the biasing are out of Dielys's hands. The instant match and
  learned shortcuts can still be applied to the text that arrives. Afrikaans support there is
  unlikely.
- Adding an item already works offline and lands locally first, so an add arriving from an
  assistant fits the existing write path.
