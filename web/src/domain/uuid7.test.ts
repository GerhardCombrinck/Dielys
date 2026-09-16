import { describe, expect, it } from "vitest";
import { uuid7 } from "./uuid7.js";

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

describe("uuid7", () => {
  it("produces a well-formed version-7 UUID", () => {
    expect(uuid7()).toMatch(UUID_RE);
  });

  it("never repeats across many calls", () => {
    const ids = new Set(Array.from({ length: 1000 }, () => uuid7()));
    expect(ids.size).toBe(1000);
  });

  it("sorts with creation time, since the timestamp is the leading bytes", () => {
    const earlier = uuid7(1_700_000_000_000);
    const later = uuid7(1_700_000_000_001);
    expect(earlier < later).toBe(true);
  });
});
