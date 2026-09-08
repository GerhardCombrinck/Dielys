import { describe, expect, it } from "vitest";
import { incomingWins } from "../src/domain/conflict.js";

describe("incomingWins (F5.4)", () => {
  it("later server timestamp wins", () => {
    const current = { serverTimestamp: "2026-01-01T00:00:00.000Z", deviceId: "a" };
    const incoming = { serverTimestamp: "2026-01-01T00:00:01.000Z", deviceId: "b" };
    expect(incomingWins(current, incoming)).toBe(true);
  });

  it("earlier server timestamp loses", () => {
    const current = { serverTimestamp: "2026-01-01T00:00:01.000Z", deviceId: "a" };
    const incoming = { serverTimestamp: "2026-01-01T00:00:00.000Z", deviceId: "b" };
    expect(incomingWins(current, incoming)).toBe(false);
  });

  it("tie on timestamp breaks on device id", () => {
    const current = { serverTimestamp: "2026-01-01T00:00:00.000Z", deviceId: "a" };
    const incoming = { serverTimestamp: "2026-01-01T00:00:00.000Z", deviceId: "b" };
    expect(incomingWins(current, incoming)).toBe(true);
  });
});
