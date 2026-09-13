import type { Mutation } from "@dielys/protocol";
import { describe, expect, it } from "vitest";
import { mayApply, parseRole } from "../src/domain/permissions.js";

const base = {
  type: "mutate" as const,
  protocolVersion: 2,
  listId: "list-1",
  entityId: "list-1",
  idempotencyKey: "key-1",
  deviceId: "device-a",
};

const deleteList: Mutation = {
  ...base,
  entityType: "list",
  patch: { deletedAt: "2026-09-13T10:00:00.000Z" },
};
const renameList: Mutation = { ...base, entityType: "list", patch: { title: "Braai" } };
const deleteTask: Mutation = {
  ...base,
  entityType: "task",
  entityId: "task-1",
  patch: { deletedAt: "2026-09-13T10:00:00.000Z" },
};

describe("who may change what on a list", () => {
  it("only the owner deletes the list", () => {
    expect(mayApply("owner", deleteList)).toBe(true);
    expect(mayApply("member", deleteList)).toBe(false);
  });

  it("fails closed for a caller whose role never arrived", () => {
    expect(mayApply(null, deleteList)).toBe(false);
  });

  it("leaves everything else open to every member", () => {
    expect(mayApply("member", renameList)).toBe(true);
    expect(mayApply("member", deleteTask)).toBe(true);
  });

  it("does not refuse a patch that clears deletedAt, which changes nothing (F5.3)", () => {
    const cleared: Mutation = { ...base, entityType: "list", patch: { deletedAt: null } };
    expect(mayApply("member", cleared)).toBe(true);
  });

  it("reads only the two roles there are", () => {
    expect(parseRole("owner")).toBe("owner");
    expect(parseRole("member")).toBe("member");
    expect(parseRole("Owner")).toBeNull();
    expect(parseRole(null)).toBeNull();
  });
});
