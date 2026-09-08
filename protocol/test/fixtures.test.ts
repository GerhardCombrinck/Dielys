import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import type { ChangeEnvelope, Task, TaskList } from "../src/types.js";

/**
 * F4: every fixture MUST parse and round-trip without data loss. This is the
 * mechanism that fails a build instead of failing in a shop — the Kotlin
 * suite must run the same check against the same files.
 */

const fixturesDir = join(import.meta.dirname, "..", "fixtures");
const fixtureFiles = readdirSync(fixturesDir).filter((f) => f.endsWith(".json"));

describe("protocol fixtures round-trip (F4)", () => {
  it("has at least one fixture to check", () => {
    expect(fixtureFiles.length).toBeGreaterThan(0);
  });

  for (const file of fixtureFiles) {
    it(`${file} round-trips through JSON without data loss`, () => {
      const raw = readFileSync(join(fixturesDir, file), "utf-8");
      const parsed: ChangeEnvelope<Task | TaskList> = JSON.parse(raw);
      const roundTripped = JSON.parse(JSON.stringify(parsed));
      expect(roundTripped).toEqual(parsed);
    });

    it(`${file} has the ChangeEnvelope shape`, () => {
      const raw = readFileSync(join(fixturesDir, file), "utf-8");
      const parsed: ChangeEnvelope<Task | TaskList> = JSON.parse(raw);
      expect(typeof parsed.seq).toBe("number");
      expect(typeof parsed.listId).toBe("string");
      expect(typeof parsed.idempotencyKey).toBe("string");
      expect(typeof parsed.deviceId).toBe("string");
      expect(typeof parsed.serverTimestamp).toBe("string");
      expect(typeof parsed.entity).toBe("object");
    });
  }
});
