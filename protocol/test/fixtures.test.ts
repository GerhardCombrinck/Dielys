import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import {
  type ChangeEnvelope,
  type ClientMessage,
  MAX_POSITION_LENGTH,
  MAX_TITLE_LENGTH,
  type ServerMessage,
} from "../src/types.js";
import { PROTOCOL_VERSION } from "../src/version.js";

/**
 * F4: every fixture MUST parse and round-trip without data loss. This is the
 * mechanism that fails a build instead of failing in a shop — the Kotlin
 * suite must run the same check against the same files.
 *
 * `fixtures/changes/` holds ChangeEnvelope payloads; `fixtures/messages/`
 * holds whole wire messages, which carry a `type` discriminator.
 */

const fixturesDir = join(import.meta.dirname, "..", "fixtures");

function load(subdir: string): Array<{ file: string; raw: string; parsed: unknown }> {
  const dir = join(fixturesDir, subdir);
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .map((file) => {
      const raw = readFileSync(join(dir, file), "utf-8");
      return { file, raw, parsed: JSON.parse(raw) as unknown };
    });
}

const changeFixtures = load("changes");
const messageFixtures = load("messages");

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

describe("protocol fixtures round-trip (F4)", () => {
  it("has fixtures in both directories", () => {
    expect(changeFixtures.length).toBeGreaterThan(0);
    expect(messageFixtures.length).toBeGreaterThan(0);
  });

  for (const { file, parsed } of [...changeFixtures, ...messageFixtures]) {
    it(`${file} round-trips through JSON without data loss`, () => {
      expect(JSON.parse(JSON.stringify(parsed))).toEqual(parsed);
    });
  }
});

describe("changelog fixtures have the ChangeEnvelope shape", () => {
  for (const { file, parsed } of changeFixtures) {
    it(file, () => {
      expect(isRecord(parsed)).toBe(true);
      // Shape asserted field by field above; the cast only names what the
      // assertions have already established.
      const change = parsed as ChangeEnvelope;
      expect(typeof change.seq).toBe("number");
      expect(typeof change.listId).toBe("string");
      expect(typeof change.idempotencyKey).toBe("string");
      expect(typeof change.deviceId).toBe("string");
      expect(typeof change.serverTimestamp).toBe("string");
      expect(["task", "list"]).toContain(change.entityType);
      expect(isRecord(change.entity)).toBe(true);

      // The discriminator must actually match the payload it claims to
      // describe — a fixture that lies here would let a client sniff the
      // shape and get away with it.
      if (change.entityType === "task") {
        expect(change.entity.listId).toBe(change.listId);
        expect(typeof change.entity.done).toBe("boolean");
        expect(typeof change.entity.position).toBe("string");
      } else {
        expect(change.entity.id).toBe(change.listId);
        expect("backgroundPhotoUrl" in change.entity).toBe(true);
      }

      expect(change.entity.title.length).toBeLessThanOrEqual(MAX_TITLE_LENGTH);
      expect(new Date(change.serverTimestamp).toISOString()).toBe(change.serverTimestamp);
    });
  }
});

describe("message fixtures cover every wire message type (F4)", () => {
  const types = new Set(
    messageFixtures.map((f) => (isRecord(f.parsed) ? f.parsed["type"] : undefined)),
  );

  // Every member of ClientMessage | ServerMessage. Listed by hand: the point
  // is to fail when someone adds a message type and forgets the fixture, and
  // a list derived from the code under test could not do that.
  const required: Array<ClientMessage["type"] | ServerMessage["type"]> = [
    "hello",
    "hello-ok",
    "hello-error",
    "mutate",
    "catch-up",
    "catch-up-response",
    "change",
    "ack",
    "error",
  ];

  for (const type of required) {
    it(`has a fixture for "${type}"`, () => {
      expect(types).toContain(type);
    });
  }
});

describe("message fixtures carry the current protocol version", () => {
  for (const { file, parsed } of messageFixtures) {
    it(file, () => {
      expect(isRecord(parsed)).toBe(true);
      const message = parsed as Record<string, unknown>;
      expect(typeof message["type"]).toBe("string");
      if ("protocolVersion" in message) {
        expect(message["protocolVersion"]).toBe(PROTOCOL_VERSION);
      }
    });
  }
});

describe("edge cases required by F4 are present", () => {
  const names = changeFixtures.map((f) => f.file);

  it("includes a tombstoned task", () => {
    expect(names).toContain("task-tombstoned.json");
  });

  it("includes a unicode title", () => {
    expect(names).toContain("task-unicode-title.json");
  });

  it("includes a maximum-length title that is exactly at the bound", () => {
    const maxTitle = changeFixtures.find((f) => f.file === "task-max-length-title.json");
    expect(maxTitle).toBeDefined();
    const change = maxTitle?.parsed as ChangeEnvelope;
    expect(change.entity.title.length).toBe(MAX_TITLE_LENGTH);
  });

  it("includes a conflicting update pair that ties on server timestamp", () => {
    const a = changeFixtures.find((f) => f.file === "task-conflicting-update-a.json")
      ?.parsed as ChangeEnvelope;
    const b = changeFixtures.find((f) => f.file === "task-conflicting-update-b.json")
      ?.parsed as ChangeEnvelope;
    expect(a).toBeDefined();
    expect(b).toBeDefined();
    // Same entity, same server timestamp, different device — this is the pair
    // that exists purely to pin down the F5.4 device-id tiebreak.
    expect(a.entity.id).toBe(b.entity.id);
    expect(a.serverTimestamp).toBe(b.serverTimestamp);
    expect(a.deviceId).not.toBe(b.deviceId);
    expect(b.deviceId > a.deviceId).toBe(true);
  });

  it("includes an empty collection", () => {
    const empty = messageFixtures.find((f) => f.file === "catch-up-response-empty.json")
      ?.parsed as { changes: unknown[]; maxSeq: number };
    expect(empty).toBeDefined();
    expect(empty.changes).toEqual([]);
    expect(empty.maxSeq).toBe(0);
  });
});

describe("position fixtures (F5.5)", () => {
  // Shared contract between the TypeScript implementation in
  // server/src/domain/position.ts and the Kotlin one that will mirror it.
  // Checked here too so editing the file fails a build even without the server.
  const positions = JSON.parse(readFileSync(join(fixturesDir, "positions.json"), "utf-8")) as {
    between: Array<{ why: string; before: string | null; after: string | null; expected: string }>;
    ordering: { keys: string[] };
    invalid: { keys: string[] };
  };

  it("every vector states what it is for", () => {
    expect(positions.between.length).toBeGreaterThan(0);
    for (const vector of positions.between) {
      expect(typeof vector.why).toBe("string");
      expect(vector.why.length).toBeGreaterThan(0);
      expect(typeof vector.expected).toBe("string");
    }
  });

  it("every expected key lands strictly between its neighbours", () => {
    for (const { before, after, expected } of positions.between) {
      if (before !== null) expect(expected > before).toBe(true);
      if (after !== null) expect(expected < after).toBe(true);
    }
  });

  it("the ordering keys are sorted by plain byte comparison", () => {
    // Not localeCompare — that would put "a" before "B" and silently reorder
    // the list on a client that used it.
    expect([...positions.ordering.keys].sort()).toEqual(positions.ordering.keys);
  });

  it("no expected key exceeds the protocol bound", () => {
    for (const { expected } of positions.between) {
      expect(expected.length).toBeLessThanOrEqual(MAX_POSITION_LENGTH);
    }
  });

  it("has invalid keys to reject", () => {
    expect(positions.invalid.keys.length).toBeGreaterThan(0);
  });
});
