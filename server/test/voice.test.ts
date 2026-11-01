import { describe, expect, it } from "vitest";
import {
  applyCorrections,
  buildCommandPrompt,
  buildVoicePrompt,
  instantMatch,
  parseCommand,
  soundSkeleton,
  soundsLikeHeard,
  tidy,
  traceable,
  validateVoiceCleanupRequest,
  validateVoiceCommandRequest,
  whisperPrompt,
} from "../src/domain/voice.js";

const base = {
  language: "af",
  listName: "Inkopies",
  guesses: [{ text: "elk", confidence: 0.6 }, { text: "melk" }],
  dictionary: ["Melk 2L", "Brood"],
  corrections: [{ heard: "bruid", saved: "Brood" }],
};

describe("voice cleanup request", () => {
  it("accepts a well-formed request", () => {
    expect(validateVoiceCleanupRequest(base).ok).toBe(true);
  });
  it("rejects no guesses, bad language and over-long text", () => {
    expect(validateVoiceCleanupRequest({ ...base, guesses: [] }).ok).toBe(false);
    expect(validateVoiceCleanupRequest({ ...base, language: "fr" }).ok).toBe(false);
    expect(validateVoiceCleanupRequest({ ...base, dictionary: ["x".repeat(201)] }).ok).toBe(false);
    expect(validateVoiceCleanupRequest("nope").ok).toBe(false);
  });
});

describe("voice guard", () => {
  const req = validateVoiceCleanupRequest(base);
  if (!req.ok) throw new Error("fixture");
  it("passes words from the dictionary or guesses, case-insensitively", () => {
    expect(traceable("Melk 2L", req.value)).toBe(true);
    expect(traceable("MELK", req.value)).toBe(true);
  });
  it("allows numbers and units", () => {
    expect(traceable("Melk 500ml", req.value)).toBe(true);
  });
  it("rejects invented words, empties and multiple lines", () => {
    expect(traceable("Kaas", req.value)).toBe(false);
    expect(traceable("  ", req.value)).toBe(false);
    expect(traceable("Melk\nBrood", req.value)).toBe(false);
  });
  it("tidies the raw reply", () => {
    expect(tidy('"Melk 2L".\nBecause...')).toBe("Melk 2L");
  });
  it("builds a prompt carrying the context", () => {
    const text = buildVoicePrompt(req.value)[1]?.content ?? "";
    expect(text).toContain("Melk 2L");
    expect(text).toContain("bruid -> Brood");
  });
});

describe("voice audio request", () => {
  it("accepts audio with no guesses, and refuses neither", () => {
    expect(validateVoiceCleanupRequest({ ...base, guesses: [], audio: "AAAA" }).ok).toBe(true);
    expect(validateVoiceCleanupRequest({ ...base, guesses: [] }).ok).toBe(false);
    expect(validateVoiceCleanupRequest({ ...base, audio: "" }).ok).toBe(false);
  });
});

describe("instant match and whisper prompt", () => {
  it("matches ignoring capitals and spacing, with the person's spelling", () => {
    expect(instantMatch(["x", "  MELK   2l "], ["Brood", "Melk 2L"])).toBe("Melk 2L");
    expect(instantMatch(["kaas"], ["Melk"])).toBeNull();
  });
  it("keeps the prompt short and in order", () => {
    expect(whisperPrompt(["Melk", "Brood", "Botter"], 12)).toBe("Melk, Brood");
  });
});

describe("voice command", () => {
  const command = {
    language: "en",
    listName: "Shop",
    lists: ["Shop", "Woolies"],
    audio: "AAAA",
    dictionary: ["Melk 2L", "Coffee"],
    corrections: [],
  };
  const parsed = validateVoiceCommandRequest(command);
  if (!parsed.ok) throw new Error("fixture");
  const heard = "add coffee add bread and milk to woolies";

  it("validates", () => {
    expect(validateVoiceCommandRequest({ ...command, audio: "" }).ok).toBe(false);
    expect(validateVoiceCommandRequest({ ...command, lists: "Shop" }).ok).toBe(false);
  });
  it("keeps traceable items and maps the list to the person's spelling", () => {
    const raw =
      'Sure: {"actions":[{"text":"Coffee"},{"text":"Bread","list":"woolies"},{"text":"Milk","list":"Nowhere"},{"text":"Cheese"}]}';
    expect(parseCommand(raw, parsed.value, heard)).toEqual([
      { type: "add", text: "Coffee" },
      { type: "add", text: "Bread", list: "Woolies" },
      { type: "add", text: "Milk" },
    ]);
  });
  it("reads one item per line, with '@ List' only for another list", () => {
    const raw = "1. Coffee\n- Bread @ woolies\nMilk @ Nowhere\n\nCheese";
    expect(parseCommand(raw, parsed.value, heard)).toEqual([
      { type: "add", text: "Coffee" },
      { type: "add", text: "Bread", list: "Woolies" },
      { type: "add", text: "Milk" },
    ]);
  });
  it("drops '@ List' when it names the list on screen", () => {
    const raw = `Coffee @ ${parsed.value.listName.toUpperCase()}`;
    expect(parseCommand(raw, parsed.value, "add coffee")).toEqual([
      { type: "add", text: "Coffee" },
    ]);
  });
  it("sends everything to the one list the speaker named when the model left it out", () => {
    // heard ends "to woolies"; benchmark en-3 came back with no list at all.
    expect(parseCommand("Coffee\nBread", parsed.value, heard)).toEqual([
      { type: "add", text: "Coffee", list: "Woolies" },
      { type: "add", text: "Bread", list: "Woolies" },
    ]);
  });
  it("leaves lists alone when the speaker named none", () => {
    expect(parseCommand("Coffee", parsed.value, "add coffee")).toEqual([
      { type: "add", text: "Coffee" },
    ]);
  });
  it("reads NONE as nothing to add", () => {
    expect(parseCommand("NONE", parsed.value, heard)).toEqual([]);
  });
  it("gives nothing for an unreadable answer", () => {
    expect(parseCommand("no json here", parsed.value, heard)).toEqual([]);
    expect(parseCommand('{"actions": 3}', parsed.value, heard)).toEqual([]);
  });
  it("puts the transcript and lists in the prompt", () => {
    const text = buildCommandPrompt(parsed.value, heard)[1]?.content ?? "";
    expect(text).toContain(heard);
    expect(text).toContain("Woolies");
  });
  it("tells the model known items are spellings, not things to skip", () => {
    // "Existing items" made the model drop Eggs and Bread from "eggs, bread and
    // peanut butter" as if already on the list (benchmark en-2, en-3).
    const [system, user] = buildCommandPrompt(parsed.value, heard);
    expect(user?.content).toContain("Known spellings:");
    expect(user?.content).not.toContain("Existing items");
    expect(system?.content).toContain("Add every item that was said");
  });
});

describe("afrikaans respelling", () => {
  const context = {
    language: "af" as const,
    listName: "Inkopies",
    guesses: [{ text: "x" }],
    dictionary: [],
    corrections: [],
  };
  it("folds sound-alike spellings", () => {
    expect(soundSkeleton("Komkommer")).toBe(soundSkeleton("comcomer"));
    expect(soundSkeleton("Tamatiesous")).toBe(soundSkeleton("tomatisauce"));
  });
  it("lets the model respell what was heard", () => {
    expect(soundsLikeHeard("Komkommer", "add com comer", context)).toBe(true);
    expect(soundsLikeHeard("Tamatiesous", "voeg tomatisauce by", context)).toBe(true);
    expect(soundsLikeHeard("Biltong", "bill tong asseblief", context)).toBe(true);
  });
  it("still refuses words that were not said", () => {
    expect(soundsLikeHeard("Kaas", "add com comer", context)).toBe(false);
    expect(soundsLikeHeard("Komkommer en Kaas", "add com comer", context)).toBe(false);
  });
  it("keeps the respelling through parseCommand", () => {
    const req = {
      language: "af" as const,
      listName: "Inkopies",
      lists: ["Inkopies"],
      audio: "AAAA",
      dictionary: [],
      corrections: [],
    };
    const raw = '{"actions":[{"text":"Komkommer"},{"text":"Tamatiesous"},{"text":"Kaas"}]}';
    expect(parseCommand(raw, req, "com comer en tomatisauce").map((a) => a.text)).toEqual([
      "Komkommer",
      "Tamatiesous",
    ]);
  });
});

describe("voice corrections", () => {
  it("replaces a corrected mishearing exactly, leaves others", () => {
    const actions = applyCorrections(
      [
        { type: "add", text: "tomatiesauce" },
        { type: "add", text: "Melk", list: "Woolies" },
      ],
      [{ heard: "Tomatiesauce", saved: "Tamatiesous" }],
    );
    expect(actions).toEqual([
      { type: "add", text: "Tamatiesous" },
      { type: "add", text: "Melk", list: "Woolies" },
    ]);
  });
});
