import { describe, expect, it } from "vitest";
import { ACCENT_COUNT, accentColor, hashedAccent, leastUsedAccent } from "./accents.js";

describe("accentColor", () => {
  it("wraps rather than throwing past the palette length", () => {
    expect(accentColor(ACCENT_COUNT)).toBe(accentColor(0));
    expect(accentColor(-1)).toBe(accentColor(ACCENT_COUNT - 1));
  });
});

describe("leastUsedAccent", () => {
  it("picks index 0 when nothing is used yet", () => {
    expect(leastUsedAccent(new Map())).toBe(0);
  });

  it("picks the fewest-used index, ties going to the lower one", () => {
    // Every index gets some usage so the tie is the real fewest-used pair,
    // not just "everything else is untouched" (which index 1 would win on
    // its own).
    const usage = new Map([
      [0, 3],
      [1, 1],
      [2, 1],
      [3, 2],
      [4, 2],
      [5, 2],
      [6, 2],
      [7, 2],
    ]);
    expect(leastUsedAccent(usage)).toBe(1);
  });
});

describe("hashedAccent", () => {
  it("is stable for the same id", () => {
    expect(hashedAccent("some-list-id")).toBe(hashedAccent("some-list-id"));
  });

  it("always lands inside the palette", () => {
    for (const id of ["a", "b", "018f2f6c-0000-7000-8000-00000000000a", ""]) {
      const index = hashedAccent(id);
      expect(index).toBeGreaterThanOrEqual(0);
      expect(index).toBeLessThan(ACCENT_COUNT);
    }
  });
});
