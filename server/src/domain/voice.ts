/**
 * Voice cleanup, pure (D1): validating the request, building the prompt, and
 * the guard that throws away an answer it cannot trace to its inputs
 * (docs/VOICE.md "Guard rails"). The Worker does the calling.
 */
import {
  MAX_VOICE_ACTIONS,
  MAX_VOICE_AUDIO_LENGTH,
  MAX_VOICE_CORRECTIONS,
  MAX_VOICE_DICTIONARY,
  MAX_VOICE_GUESSES,
  MAX_VOICE_LISTS,
  MAX_VOICE_TEXT_LENGTH,
  VOICE_LANGUAGES,
  type VoiceAction,
  type VoiceCleanupRequest,
  type VoiceCommandRequest,
  type VoiceCorrection,
  type VoiceCorrectionRecord,
  type VoiceGuess,
} from "@dielys/protocol";
import type { Validated } from "./validate.js";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isText(value: unknown, allowEmpty = false): value is string {
  return (
    typeof value === "string" &&
    value.length <= MAX_VOICE_TEXT_LENGTH &&
    (allowEmpty || value.trim().length > 0)
  );
}

export const VOICE_PROMPT_VERSION = 1;

export function validateVoiceCleanupRequest(input: unknown): Validated<VoiceCleanupRequest> {
  if (!isRecord(input)) return { ok: false, reason: "not an object" };
  const language = VOICE_LANGUAGES.find((l) => l === input.language);
  if (language === undefined) return { ok: false, reason: "language" };
  if (!isText(input.listName, true)) return { ok: false, reason: "listName" };
  const hasAudio = input.audio !== undefined;
  if (hasAudio && (typeof input.audio !== "string" || input.audio.length === 0)) {
    return { ok: false, reason: "audio" };
  }
  if (typeof input.audio === "string" && input.audio.length > MAX_VOICE_AUDIO_LENGTH) {
    return { ok: false, reason: "audio" };
  }
  if (
    !Array.isArray(input.guesses) ||
    (!hasAudio && input.guesses.length < 1) ||
    input.guesses.length > MAX_VOICE_GUESSES
  ) {
    return { ok: false, reason: "guesses" };
  }
  const guesses: VoiceGuess[] = [];
  for (const g of input.guesses) {
    if (!isRecord(g) || !isText(g.text)) return { ok: false, reason: "guesses" };
    if (g.confidence === undefined) {
      guesses.push({ text: g.text });
    } else if (typeof g.confidence === "number" && g.confidence >= 0) {
      guesses.push({ text: g.text, confidence: g.confidence });
    } else {
      return { ok: false, reason: "guesses" };
    }
  }
  if (input.field !== undefined && !isText(input.field, true)) {
    return { ok: false, reason: "field" };
  }
  if (!Array.isArray(input.dictionary) || input.dictionary.length > MAX_VOICE_DICTIONARY) {
    return { ok: false, reason: "dictionary" };
  }
  const dictionary: string[] = [];
  for (const d of input.dictionary) {
    if (!isText(d)) return { ok: false, reason: "dictionary" };
    dictionary.push(d);
  }
  if (!Array.isArray(input.corrections) || input.corrections.length > MAX_VOICE_CORRECTIONS) {
    return { ok: false, reason: "corrections" };
  }
  const corrections: VoiceCorrection[] = [];
  for (const c of input.corrections) {
    if (!isRecord(c) || !isText(c.heard) || !isText(c.saved)) {
      return { ok: false, reason: "corrections" };
    }
    corrections.push({ heard: c.heard, saved: c.saved });
  }
  const value: VoiceCleanupRequest = {
    language,
    listName: input.listName,
    guesses,
    dictionary,
    corrections,
  };
  if (typeof input.field === "string") value.field = input.field;
  if (typeof input.audio === "string") value.audio = input.audio;
  return { ok: true, value };
}

export interface ChatMessage {
  role: "system" | "user";
  content: string;
}

/** The versioned prompt. Any change replays the bake-off set first. */
export function buildVoicePrompt(req: VoiceCleanupRequest): ChatMessage[] {
  const lang = req.language === "af" ? "Afrikaans" : "South African English";
  const system = [
    `You clean up one shopping-list item that was spoken aloud in ${lang}.`,
    "You get the speech recogniser's guesses, best first, the person's existing items and their past corrections.",
    "Rules:",
    "- Output exactly one list item and nothing else: no quotes, no explanation.",
    "- Pick or fix the words using the guesses, the existing items and the corrections.",
    "- If the guess matches an existing item, use that item's exact spelling.",
    "- Copy the style of the existing items (capitals, digits, units), e.g. 'two litres of milk' becomes 'Melk 2L' if the list says 'Melk 2L'.",
    "- If the speaker corrects themselves ('brood, nee, rolls'), keep only the final choice ('Rolls').",
    "- Never translate. Never add words that were not said. Never return more than one item.",
    "- If text is already in the field, return only the new words; never repeat or change the field.",
  ].join("\n");
  const lines = [
    `List: ${req.listName}`,
    `Guesses: ${req.guesses.map((g) => JSON.stringify(g.text)).join(" | ")}`,
  ];
  if (req.field !== undefined && req.field !== "") {
    lines.push(`Field so far: ${JSON.stringify(req.field)}`);
  }
  if (req.dictionary.length > 0) lines.push(`Existing items: ${req.dictionary.join("; ")}`);
  if (req.corrections.length > 0) {
    const pairs = req.corrections.map((c) => `${c.heard} -> ${c.saved}`).join("; ");
    lines.push(`Past corrections: ${pairs}`);
  }
  return [
    { role: "system", content: system },
    { role: "user", content: lines.join("\n") },
  ];
}

function words(s: string): string[] {
  return s
    .toLowerCase()
    .normalize("NFC")
    .split(/[^\p{L}\p{N}.,]+/u)
    .map((w) => w.replace(/^[.,]+|[.,]+$/g, ""))
    .filter((w) => w.length > 0);
}

/** A digit-bearing token: "2l", "500", "500ml". */
const NUMBERISH = /^\d+([.,]\d+)?[a-z]{0,3}$/;
const UNITS = new Set(["x", "l", "ml", "g", "kg", "pk", "cl"]);

/**
 * Every word of [answer] must come from a guess, a dictionary entry or a
 * correction (ignoring case); numbers and common unit abbreviations are free
 * because "two litres" legitimately becomes "2L". Also rejects an empty answer
 * and anything that looks like more than one item (a line break).
 */
export function traceable(answer: string, req: VoiceCleanupRequest): boolean {
  const trimmed = answer.trim();
  if (trimmed === "" || /[\r\n]/.test(trimmed)) return false;
  const allowed = new Set<string>();
  const add = (s: string) => {
    for (const w of words(s)) allowed.add(w);
  };
  for (const g of req.guesses) add(g.text);
  for (const d of req.dictionary) add(d);
  for (const c of req.corrections) {
    add(c.heard);
    add(c.saved);
  }
  for (const w of words(trimmed)) {
    if (allowed.has(w) || UNITS.has(w) || NUMBERISH.test(w)) continue;
    return false;
  }
  return true;
}

/** Tidies the model's raw reply: first line, surrounding quotes and a trailing full stop dropped. */
export function tidy(raw: string): string {
  const first = raw.trim().split(/\r?\n/)[0] ?? "";
  return first
    .trim()
    .replace(/\.$/, "")
    .replace(/^["'“”]+|["'“”]+$/g, "")
    .trim();
}

/** Capitals, surrounding and repeated spaces ignored (docs/VOICE.md "Instant match"). */
export function normalise(text: string): string {
  return text.toLowerCase().trim().split(/\s+/).join(" ");
}

/** The dictionary entry a guess equals, spelled as the person spelled it, or null. */
export function instantMatch(guesses: string[], dictionary: string[]): string | null {
  const byKey = new Map<string, string>();
  for (const item of dictionary) {
    const key = normalise(item);
    if (!byKey.has(key)) byKey.set(key, item);
  }
  for (const guess of guesses) {
    const found = byKey.get(normalise(guess));
    if (found !== undefined) return found;
  }
  return null;
}

/** What Whisper is primed with: the person's own words, most likely first, kept short. */
export function whisperPrompt(dictionary: string[], maxChars = 400): string {
  let out = "";
  for (const item of dictionary) {
    const next = out === "" ? item : `${out}, ${item}`;
    if (next.length > maxChars) break;
    out = next;
  }
  return out;
}

function textList(value: unknown, max: number): string[] | null {
  if (!Array.isArray(value) || value.length > max) return null;
  const out: string[] = [];
  for (const item of value) {
    if (!isText(item)) return null;
    out.push(item);
  }
  return out;
}

export function validateVoiceCommandRequest(input: unknown): Validated<VoiceCommandRequest> {
  if (!isRecord(input)) return { ok: false, reason: "not an object" };
  const language = VOICE_LANGUAGES.find((l) => l === input.language);
  if (language === undefined) return { ok: false, reason: "language" };
  if (!isText(input.listName, true)) return { ok: false, reason: "listName" };
  const lists = textList(input.lists, MAX_VOICE_LISTS);
  if (lists === null) return { ok: false, reason: "lists" };
  if (
    typeof input.audio !== "string" ||
    input.audio.length === 0 ||
    input.audio.length > MAX_VOICE_AUDIO_LENGTH
  ) {
    return { ok: false, reason: "audio" };
  }
  const dictionary = textList(input.dictionary, MAX_VOICE_DICTIONARY);
  if (dictionary === null) return { ok: false, reason: "dictionary" };
  if (!Array.isArray(input.corrections) || input.corrections.length > MAX_VOICE_CORRECTIONS) {
    return { ok: false, reason: "corrections" };
  }
  const corrections: VoiceCorrection[] = [];
  for (const c of input.corrections) {
    if (!isRecord(c) || !isText(c.heard) || !isText(c.saved)) {
      return { ok: false, reason: "corrections" };
    }
    corrections.push({ heard: c.heard, saved: c.saved });
  }
  return {
    ok: true,
    value: {
      language,
      listName: input.listName,
      lists,
      audio: input.audio,
      dictionary,
      corrections,
    },
  };
}

export const VOICE_COMMAND_PROMPT_VERSION = 6;

/** The versioned instruction prompt. The answer is one item per line, parsed by [parseCommand]. */
export function buildCommandPrompt(req: VoiceCommandRequest, heard: string): ChatMessage[] {
  const lang = req.language === "af" ? "Afrikaans" : "South African English";
  const system = [
    `You turn one spoken instruction for a shopping-list app into actions. The speaker uses ${lang}, sometimes mixed with English or Afrikaans.`,
    "Answer with one item per line and nothing else: no JSON, no numbering, no explanation.",
    // Placeholders, not real items: a small model copies a real example answer.
    "Only when the speaker names another of their lists, write that item as <item> @ <list>.",
    "Rules:",
    "- Each line is one thing to buy or do. Split lists into separate lines: commas, 'and' and 'en' separate items. 'eggs, bread, milk' is three lines: Eggs, Bread, Milk. 'add coffee add bread and milk' is three items: Coffee, Bread, Milk. 'voeg melk en brood by' is two. Never put a comma inside a line.",
    "- Add every item that was said, including ones in their known spellings. Known spellings only show how to write an item; they do not mean it is already on a list, so never leave an item out because it is there.",
    "- Do not split names that are one item, like 'salt and pepper chips' or 'mac and cheese', when the known spellings show them as one.",
    "- Leave out command words (add, put, voeg by, sit op, please, asseblief) and the list name from the item text.",
    "- If an item matches a known spelling, use it exactly. Otherwise start it with a capital letter and keep the speaker's words.",
    "- If the speaker corrects themselves ('bread, no, rolls'), keep only the final choice.",
    "- '@ List' only when the speaker names one of their lists other than the one on screen; copy that list name exactly. Never write '@' for the list on screen.",
    "- Whisper often writes Afrikaans words with English spelling or splits them: 'com comer' is Komkommer, 'tomatisauce' is Tamatiesous, 'bill tong' is Biltong. When the speaker used Afrikaans, write each item in correct Afrikaans spelling.",
    "- Never translate between languages. Never invent items that were not said.",
    "- If nothing to add was said, answer NONE.",
  ].join("\n");
  const lines = [
    `Instruction heard: ${JSON.stringify(heard)}`,
    `List on screen: ${req.listName}`,
    `Their lists: ${req.lists.join("; ")}`,
  ];
  if (req.dictionary.length > 0) lines.push(`Known spellings: ${req.dictionary.join("; ")}`);
  if (req.corrections.length > 0) {
    const pairs = req.corrections.map((c) => `${c.heard} -> ${c.saved}`).join("; ");
    lines.push(`Past corrections: ${pairs}`);
  }
  return [
    { role: "system", content: system },
    { role: "user", content: lines.join("\n") },
  ];
}

/**
 * The transcript as items when the model gave nothing usable: split at commas,
 * so "Eggs, bread, milk" is three items rather than one, each with a capital.
 */
export function fallbackActions(heard: string): VoiceAction[] {
  return heard
    .replace(/[.!?]+$/, "")
    .split(",")
    .map((part) => part.trim())
    .filter((part) => part !== "")
    .slice(0, MAX_VOICE_ACTIONS)
    .map((part) => ({ type: "add" as const, text: part.charAt(0).toUpperCase() + part.slice(1) }));
}

/** What the model wrote per item, before the guard looks at it. */
interface Candidate {
  text: string;
  list?: string;
}

/**
 * The answer's items: one per line, `Item` or `Item @ List` (prompt version 4).
 * The JSON shape from earlier prompt versions is still read. Null when unreadable.
 */
function candidates(raw: string): Candidate[] | null {
  const start = raw.indexOf("{");
  const end = raw.lastIndexOf("}");
  if (start >= 0 && end > start && raw.includes('"actions"')) {
    try {
      const parsed: unknown = JSON.parse(raw.slice(start, end + 1));
      if (!isRecord(parsed) || !Array.isArray(parsed.actions)) return null;
      return parsed.actions.flatMap((item: unknown) =>
        isRecord(item) && typeof item.text === "string"
          ? [{ text: item.text, ...(typeof item.list === "string" ? { list: item.list } : {}) }]
          : [],
      );
    } catch {
      return null;
    }
  }
  return raw.split(/\r?\n/).flatMap((line) => {
    // Bullets or numbering the model adds despite being told not to.
    const clean = line.replace(/^\s*(?:[-*•]|\d+[.)])\s*/, "").trim();
    if (clean === "" || /^none$/i.test(clean)) return [];
    const at = clean.lastIndexOf("@");
    const text = at < 0 ? clean : clean.slice(0, at);
    const list = at < 0 ? undefined : clean.slice(at + 1).trim();
    // A comma list the model kept on one line is still several items.
    return text
      .split(",")
      .map((part) => part.trim())
      .filter((part) => part !== "")
      .map((part) => (list === undefined ? { text: part } : { text: part, list }));
  });
}

/**
 * The model's answer as actions, keeping only what the guard can trace: every
 * word of an item's text from the transcript, the dictionary or a correction,
 * and a list only when it is one the person has (returned as they spell it)
 * and not the list on screen. Anything unreadable gives no actions.
 */
export function parseCommand(raw: string, req: VoiceCommandRequest, heard: string): VoiceAction[] {
  const items = candidates(raw);
  if (items === null) return [];
  const context: VoiceCleanupRequest = {
    language: req.language,
    listName: req.listName,
    guesses: [{ text: heard }],
    dictionary: req.dictionary,
    corrections: req.corrections,
  };
  const said: VoiceCleanupRequest = { ...context, dictionary: [] };
  const listsByKey = new Map(req.lists.map((name) => [normalise(name), name]));
  const here = normalise(req.listName);
  const actions: VoiceAction[] = [];
  for (const item of items) {
    if (actions.length >= MAX_VOICE_ACTIONS) break;
    const text = tidy(item.text);
    if (text === "" || text.length > MAX_VOICE_TEXT_LENGTH) continue;
    // Traced to what was said, not to the dictionary: the model invented "Melk,
    // Brood" from known items nobody spoke (benchmark af-3).
    if (!traceable(text, said) && !soundsLikeHeard(text, heard, said)) continue;
    const key = item.list === undefined ? undefined : normalise(item.list);
    const list = key === undefined || key === here ? undefined : listsByKey.get(key);
    actions.push(list === undefined ? { type: "add", text } : { type: "add", text, list });
  }
  // The small model often leaves out '@ List'. When it named none and the speaker
  // said exactly one of their other lists, everything goes there.
  const spoken = spokenList(heard, req);
  if (spoken !== undefined && actions.every((a) => a.list === undefined)) {
    return actions.map((a) => ({ ...a, list: spoken }));
  }
  return actions;
}

/** The one other list named in [heard] as whole words, or undefined for none or several. */
function spokenList(heard: string, req: VoiceCommandRequest): string | undefined {
  const words = ` ${wordsOf(heard)} `;
  const here = wordsOf(req.listName);
  const named = req.lists.filter((name) => {
    const key = wordsOf(name);
    return key !== "" && key !== here && words.includes(` ${key} `);
  });
  return named.length === 1 ? named[0] : undefined;
}

/** Lower case words only, punctuation dropped: "Woolies." and "woolies" match. */
function wordsOf(text: string): string {
  return text
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w !== "")
    .join(" ");
}

/**
 * How a word sounds, roughly, in Afrikaans or English spelling: its consonants,
 * with the spellings that sound alike folded together and doubles collapsed.
 * "komkommer" and "com comer" both give "kmkmr"; "tamatiesous" and "tomatisauce"
 * both give "tmtss" before collapsing.
 */
export function soundSkeleton(word: string): string {
  const folded = word
    .toLowerCase()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[^a-z]/g, "")
    .replace(/ph/g, "f")
    .replace(/c(?=[eiy])/g, "s")
    .replace(/[cq]/g, "k")
    .replace(/x/g, "ks")
    .replace(/w/g, "v")
    .replace(/z/g, "s")
    .replace(/[aeiouyh]/g, "");
  return folded.replace(/(.)\1+/g, "$1");
}

/** Edit distance, small strings only. */
export function editDistance(a: string, b: string): number {
  const row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let diagonal = row[0] ?? 0;
    row[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const above = row[j] ?? 0;
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      row[j] = Math.min(above + 1, (row[j - 1] ?? 0) + 1, diagonal + cost);
      diagonal = above;
    }
  }
  return row[b.length] ?? 0;
}

/**
 * The guard's second chance: an item may respell what Whisper heard into a real
 * word ("com comer" -> "Komkommer"), but not invent one. Every word of [text] of
 * four letters or more must sound like one to three adjacent heard words joined
 * (same consonant skeleton, or one consonant off for longer words) and be spelled
 * within half its length of them. Shorter words must be traceable as before.
 */
export function soundsLikeHeard(
  text: string,
  heard: string,
  context: VoiceCleanupRequest,
): boolean {
  const heardWords = heard
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length > 0);
  const windows: string[] = [];
  for (let i = 0; i < heardWords.length; i++) {
    let joined = "";
    for (let j = i; j < Math.min(i + 3, heardWords.length); j++) {
      joined += heardWords[j];
      windows.push(joined);
    }
  }
  const words = text
    .toLowerCase()
    .split(/[^\p{L}\p{N}]+/u)
    .filter((w) => w.length > 0);
  if (words.length === 0) return false;
  return words.every((word) => {
    if (traceable(word, context)) return true;
    if (word.length < 4) return false;
    const skeleton = soundSkeleton(word);
    return windows.some((candidate) => {
      const other = soundSkeleton(candidate);
      const close =
        skeleton === other || (skeleton.length >= 4 && editDistance(skeleton, other) <= 1);
      return close && editDistance(word, candidate) <= Math.ceil(word.length / 2);
    });
  });
}

/** `POST /voice/corrections`: one record, both texts present and bounded. */
export function validateVoiceCorrectionRecord(input: unknown): Validated<VoiceCorrectionRecord> {
  if (!isRecord(input)) return { ok: false, reason: "not an object" };
  const language = VOICE_LANGUAGES.find((l) => l === input.language);
  if (language === undefined) return { ok: false, reason: "language" };
  if (!isText(input.heard)) return { ok: false, reason: "heard" };
  if (!isText(input.saved)) return { ok: false, reason: "saved" };
  return { ok: true, value: { heard: input.heard.trim(), saved: input.saved.trim(), language } };
}

/**
 * The person's own corrections applied to the model's actions: an item whose
 * text is exactly a corrected mishearing (capitals and spacing aside) becomes
 * what they corrected it to. Deterministic, so a fix made once sticks.
 */
export function applyCorrections(
  actions: VoiceAction[],
  corrections: VoiceCorrection[],
): VoiceAction[] {
  const byHeard = new Map(corrections.map((c) => [normalise(c.heard), c.saved]));
  return actions.map((a) => {
    const saved = byHeard.get(normalise(a.text));
    return saved === undefined ? a : { ...a, text: saved };
  });
}
