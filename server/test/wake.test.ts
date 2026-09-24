import { describe, expect, it } from "vitest";
import { planWake, type WakeCandidate } from "../src/domain/wake.js";

/**
 * Who a change wakes, and at what priority (M2, ADR 0012). The send is covered
 * in fcm.test.ts and the fan-out query in devices.test.ts; this is the decision
 * between them.
 */

const owner = "user-owner";
const partner = "user-partner";

function device(deviceId: string, userId: string, notify: boolean): WakeCandidate {
  return { deviceId, userId, fcmToken: `token-${deviceId}`, notify };
}

describe("planWake", () => {
  it("leaves out every device that already heard over a socket (M2)", () => {
    const planned = planWake(
      [device("phone-a", owner, false), device("phone-b", partner, false)],
      new Set(["phone-a"]),
      owner,
      32,
    );
    expect(planned.map((target) => target.deviceId)).toEqual(["phone-b"]);
  });

  it("sends high only to a subscriber who did not make the write", () => {
    const planned = planWake(
      [
        device("owner-phone", owner, true),
        device("partner-phone", partner, true),
        device("partner-tablet", partner, true),
      ],
      new Set(),
      owner,
      32,
    );
    expect(planned).toEqual([
      // Subscribed, but this is its own account's edit: nothing to show.
      { deviceId: "owner-phone", fcmToken: "token-owner-phone", priority: "normal" },
      { deviceId: "partner-phone", fcmToken: "token-partner-phone", priority: "high" },
      { deviceId: "partner-tablet", fcmToken: "token-partner-tablet", priority: "high" },
    ]);
  });

  it("wakes a member who has not subscribed, at normal priority", () => {
    // Notification choice decides urgency, never who syncs — a phone that is
    // not woken falls back to the half-hourly floor (H3.12).
    const planned = planWake([device("partner-phone", partner, false)], new Set(), owner, 32);
    expect(planned).toEqual([
      { deviceId: "partner-phone", fcmToken: "token-partner-phone", priority: "normal" },
    ]);
  });

  it("treats an unknown author as nobody, so a subscriber still gets high", () => {
    const planned = planWake([device("partner-phone", partner, true)], new Set(), null, 32);
    expect(planned[0]?.priority).toBe("high");
  });

  it("stops at the cap", () => {
    const many = Array.from({ length: 40 }, (_, i) => device(`phone-${i}`, partner, false));
    expect(planWake(many, new Set(), owner, 32)).toHaveLength(32);
  });
});
