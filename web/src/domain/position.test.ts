import { MAX_POSITION_LENGTH } from "@dielys/protocol";
import { describe, expect, it } from "vitest";
/**
 * Fixture-verified per web/AGENTS.md: anything ported from server/src/domain/
 * needs the same golden-vector coverage the server has. Full property-based
 * rationale lives in server/test/position.test.ts — this is the floor (H2),
 * not a re-derivation of it.
 */
import fixtures from "../../../protocol/fixtures/positions.json";
import {
  between,
  betweenMany,
  FIRST_POSITION,
  isValid,
  PositionError,
  validate,
} from "./position.js";

describe("golden vectors (F4)", () => {
  for (const vector of fixtures.between) {
    it(`between(${JSON.stringify(vector.before)}, ${JSON.stringify(vector.after)}) = ${vector.expected}`, () => {
      expect(between(vector.before, vector.after)).toBe(vector.expected);
    });
  }

  it("the ordering fixture is sorted by plain byte comparison", () => {
    const keys = fixtures.ordering.keys;
    expect([...keys].sort()).toEqual(keys);
  });

  it("every key in the ordering fixture is valid", () => {
    for (const key of fixtures.ordering.keys) expect(isValid(key)).toBe(true);
  });

  it("every key in the invalid fixture is rejected", () => {
    for (const key of fixtures.invalid.keys) expect(isValid(key)).toBe(false);
  });
});

describe("between", () => {
  it("starts an empty list at the documented first position", () => {
    expect(between(null, null)).toBe(FIRST_POSITION);
  });

  it("always returns something strictly between its neighbours", () => {
    const pairs: Array<[string, string]> = [
      ["a0", "a1"],
      ["a0", "az"],
      ["Zz", "a0"],
      ["a0V", "a0l"],
      ["b00", "b0z"],
    ];
    for (const [before, after] of pairs) {
      const middle = between(before, after);
      expect(middle > before).toBe(true);
      expect(middle < after).toBe(true);
      expect(isValid(middle)).toBe(true);
    }
  });

  it("refuses neighbours that are out of order", () => {
    expect(() => between("a1", "a0")).toThrow(PositionError);
  });

  it("refuses an invalid neighbour rather than producing a key from it", () => {
    expect(() => between("not a position", null)).toThrow(PositionError);
  });
});

describe("appending", () => {
  it("keeps keys short and ordered across the length boundary", () => {
    let cursor: string | null = null;
    const keys: string[] = [];
    for (let i = 0; i < 200; i += 1) {
      cursor = between(cursor, null);
      keys.push(cursor);
    }

    expect([...keys].sort()).toEqual(keys);
    expect(new Set(keys).size).toBe(keys.length);
    expect(keys[61]).toBe("az");
    expect(keys[62]).toBe("b00");
    expect(Math.max(...keys.map((k) => k.length))).toBeLessThanOrEqual(MAX_POSITION_LENGTH);
  });
});

describe("betweenMany", () => {
  it("spreads keys instead of nesting them", () => {
    const keys = betweenMany("a0", "a1", 50);
    expect(keys).toHaveLength(50);
    expect([...keys].sort()).toEqual(keys);
    expect(new Set(keys).size).toBe(50);
  });

  it("refuses a negative or fractional count", () => {
    expect(() => betweenMany("a0", "a1", -1)).toThrow(PositionError);
    expect(() => betweenMany("a0", "a1", 1.5)).toThrow(PositionError);
  });
});

describe("validate", () => {
  it("rejects a trailing zero, which is a second spelling of the same key", () => {
    expect(() => validate("a0V0")).toThrow(PositionError);
  });

  it("rejects an empty key", () => {
    expect(() => validate("")).toThrow(PositionError);
  });
});
