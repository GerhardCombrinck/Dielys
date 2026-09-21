import { describe, expect, it } from "vitest";
import { spotUnderStarred } from "./taskPlacement.js";

describe("spotUnderStarred", () => {
  it("is 0 when nothing at the top is starred", () => {
    expect(spotUnderStarred([{ starred: false }, { starred: false }], (t) => t.starred)).toBe(0);
  });

  it("stops at the first unstarred row", () => {
    const rows = [{ starred: true }, { starred: true }, { starred: false }, { starred: true }];
    expect(spotUnderStarred(rows, (t) => t.starred)).toBe(2);
  });

  it("is the full length when everything is starred", () => {
    const rows = [{ starred: true }, { starred: true }];
    expect(spotUnderStarred(rows, (t) => t.starred)).toBe(2);
  });
});
