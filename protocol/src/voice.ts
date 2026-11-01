/**
 * Voice input cleanup (docs/VOICE.md, ADR 0016). Declared here first, like
 * everything else that crosses the boundary (F1).
 *
 * The phone assembles all context and the Worker looks nothing up per request,
 * so the request carries the recognizer's guesses, the dictionary and the
 * person's past corrections. Audio never reaches the server.
 */

export const VOICE_LANGUAGES = ["en", "af"] as const;
export type VoiceLanguage = (typeof VOICE_LANGUAGES)[number];

/** Bounds (F3). The phone sends about 200 dictionary entries and 20 corrections. */
export const MAX_VOICE_GUESSES = 10;
export const MAX_VOICE_DICTIONARY = 300;
export const MAX_VOICE_CORRECTIONS = 30;
export const MAX_VOICE_TEXT_LENGTH = 200;

export interface VoiceGuess {
  text: string;
  /** 0..1 where the recognizer gives one. */
  confidence?: number;
}

/** What was heard, and what the person saved instead. */
export interface VoiceCorrection {
  heard: string;
  saved: string;
}

/** Bounds on the uploaded clip: base64 of a 16 kHz mono 16-bit WAV, about 30 seconds. */
export const MAX_VOICE_AUDIO_LENGTH = 1_400_000;

/**
 * `POST /voice/cleanup`. Either [audio] (the Worker transcribes it with Whisper
 * and the transcript becomes the only guess) or at least one entry in [guesses].
 */
export interface VoiceCleanupRequest {
  language: VoiceLanguage;
  listName: string;
  /** Best first. May be empty only when [audio] is present. */
  guesses: VoiceGuess[];
  /** Base64 of a 16 kHz mono 16-bit PCM WAV file. */
  audio?: string;
  /** Text already in the field, when appending. Context only. */
  field?: string;
  dictionary: string[];
  corrections: VoiceCorrection[];
}

/**
 * `matched`: the transcript was one of the person's own items; `text` is that
 * item's spelling. `cleaned`: `text` is the model's answer and every word of it
 * traced to the input. `rejected`: the model's answer could not be traced (or the
 * model failed); `text` is the top guess as heard. When nothing was heard `text`
 * is empty. [heard] is the transcript when audio was sent.
 */
export interface VoiceCleanupResponse {
  text: string;
  outcome: "matched" | "cleaned" | "rejected";
  heard?: string;
}

/** At most this many actions from one spoken instruction. */
export const MAX_VOICE_ACTIONS = 20;
/** At most this many list names sent as possible targets. */
export const MAX_VOICE_LISTS = 100;

/**
 * `POST /voice/command`: one spoken instruction, possibly several items and a
 * named list ("add coffee, bread and milk to Woolies"). The phone records the
 * hold and sends it as [audio]; the Worker transcribes it and turns it into
 * actions. Nothing here names a list by id: the phone sends the names it shows
 * and maps the answer back itself.
 */
export interface VoiceCommandRequest {
  language: VoiceLanguage;
  /** The list on screen: where items go when no list is named. */
  listName: string;
  /** Every list name the person has, the current one included. */
  lists: string[];
  /** Base64 of a 16 kHz mono 16-bit PCM WAV file. */
  audio: string;
  dictionary: string[];
  corrections: VoiceCorrection[];
}

/**
 * One thing to do. Only `add` exists. [list] is one of the request's `lists`
 * exactly as sent, or absent for the list on screen.
 */
export interface VoiceAction {
  type: "add";
  text: string;
  list?: string;
}

/** [heard] is the transcript; [actions] is empty when nothing usable was heard. */
export interface VoiceCommandResponse {
  heard: string;
  actions: VoiceAction[];
}

/** The server keeps this many corrections per account, newest first. */
export const MAX_STORED_VOICE_CORRECTIONS = 500;

/**
 * One correction the person made on purpose ("Correct dictation" on a row voice
 * added): what voice wrote, and what it should have written. Shared by every
 * account: per mishearing the most submitted correction wins, the latest on a tie.
 */
export interface VoiceCorrectionRecord {
  heard: string;
  saved: string;
  language: VoiceLanguage;
}

/** `GET /voice/corrections`: newest first, at most [MAX_STORED_VOICE_CORRECTIONS]. */
export interface VoiceCorrectionsResponse {
  corrections: VoiceCorrectionRecord[];
}
