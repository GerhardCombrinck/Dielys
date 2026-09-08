import { MAX_TITLE_LENGTH } from "@dielys/protocol";
import { describe, expect, it } from "vitest";
import {
  parseJson,
  validateCatchUpRequest,
  validateClientHello,
  validateMutation,
  validateTaskPatch,
} from "../src/domain/validate.js";

const validMutation = {
  type: "mutate",
  protocolVersion: 2,
  listId: "list-1",
  entityType: "task",
  entityId: "task-1",
  idempotencyKey: "key-1",
  deviceId: "device-1",
  patch: { done: true },
};

describe("parseJson", () => {
  it("rejects non-JSON without throwing", () => {
    expect(parseJson("{oh no")).toEqual({ ok: false, reason: "not json" });
  });
});

describe("validateMutation (F3)", () => {
  it("accepts a well-formed mutation", () => {
    const result = validateMutation(validMutation);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value.entityType).toBe("task");
  });

  for (const field of ["listId", "entityId", "idempotencyKey", "deviceId"]) {
    it(`rejects a missing ${field}`, () => {
      const input: Record<string, unknown> = { ...validMutation };
      delete input[field];
      expect(validateMutation(input).ok).toBe(false);
    });
  }

  it("rejects an unknown entityType", () => {
    expect(validateMutation({ ...validMutation, entityType: "note" }).ok).toBe(false);
  });

  it("rejects a non-object patch", () => {
    expect(validateMutation({ ...validMutation, patch: "done" }).ok).toBe(false);
  });

  it("rejects a fractional protocolVersion", () => {
    expect(validateMutation({ ...validMutation, protocolVersion: 1.5 }).ok).toBe(false);
  });

  it("rejects a string where a number belongs", () => {
    expect(validateMutation({ ...validMutation, protocolVersion: "2" }).ok).toBe(false);
  });

  it("rejects an array — JSON arrays are objects too", () => {
    expect(validateMutation([]).ok).toBe(false);
  });

  it("rejects null", () => {
    expect(validateMutation(null).ok).toBe(false);
  });
});

describe("validateTaskPatch (F2, F3)", () => {
  it("drops unknown keys rather than rejecting them", () => {
    // Forward compatibility is what makes an additive protocol change safe on
    // a server that has not been redeployed yet.
    const result = validateTaskPatch({ done: true, somethingNewer: 42 });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.value).toEqual({ done: true });
  });

  it("keeps an absent key absent rather than defaulting it", () => {
    const result = validateTaskPatch({});
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(Object.keys(result.value)).toEqual([]);
  });

  it("distinguishes deletedAt: null from an absent deletedAt", () => {
    const explicit = validateTaskPatch({ deletedAt: null });
    expect(explicit.ok).toBe(true);
    if (!explicit.ok) return;
    expect("deletedAt" in explicit.value).toBe(true);
  });

  it("accepts a title exactly at the bound", () => {
    expect(validateTaskPatch({ title: "x".repeat(MAX_TITLE_LENGTH) }).ok).toBe(true);
  });

  it("rejects a title one over the bound", () => {
    expect(validateTaskPatch({ title: "x".repeat(MAX_TITLE_LENGTH + 1) }).ok).toBe(false);
  });

  it("counts a title in code points, so emoji cost what a user would say they cost", () => {
    // "🍖".length is 2 in UTF-16; the bound must not mean something different
    // for a list with emoji in it than for one without.
    expect(validateTaskPatch({ title: "🍖".repeat(MAX_TITLE_LENGTH) }).ok).toBe(true);
  });

  it("rejects an empty position", () => {
    expect(validateTaskPatch({ position: "" }).ok).toBe(false);
  });

  it("rejects a non-ISO deletedAt", () => {
    expect(validateTaskPatch({ deletedAt: "yesterday" }).ok).toBe(false);
  });

  it("rejects a date string that does not round-trip", () => {
    // Date.parse is lenient; storage must not accept a value that would come
    // back out as a different string than it went in as.
    expect(validateTaskPatch({ deletedAt: "2026-09-08" }).ok).toBe(false);
  });

  it("rejects a number where a boolean belongs", () => {
    expect(validateTaskPatch({ done: 1 }).ok).toBe(false);
  });
});

describe("validateClientHello (F3)", () => {
  const hello = {
    type: "hello",
    protocolVersion: 2,
    listId: "list-1",
    cursor: 0,
    deviceId: "device-1",
  };

  it("accepts a well-formed hello", () => {
    expect(validateClientHello(hello).ok).toBe(true);
  });

  it("rejects a negative cursor", () => {
    expect(validateClientHello({ ...hello, cursor: -1 }).ok).toBe(false);
  });

  it("rejects the wrong type tag", () => {
    expect(validateClientHello({ ...hello, type: "mutate" }).ok).toBe(false);
  });
});

describe("validateCatchUpRequest (F3)", () => {
  it("accepts since = 0, which is a first-ever sync and not a missing value", () => {
    const result = validateCatchUpRequest({ type: "catch-up", listId: "list-1", since: 0 });
    expect(result.ok).toBe(true);
  });

  it("rejects a negative since", () => {
    expect(validateCatchUpRequest({ type: "catch-up", listId: "list-1", since: -1 }).ok).toBe(
      false,
    );
  });
});
