/**
 * Who a change wakes and how urgently (M2, ADR 0012). Pure: the devices and the
 * connected set come in as arguments, the targets go out, and `UsersRoom` does
 * the reading and the sending around it.
 */

/** One member device, as the fan-out query returns it. */
export interface WakeCandidate {
  deviceId: string;
  userId: string;
  fcmToken: string;
  /** Its account has a non-empty `notify` on the list. */
  notify: boolean;
}

export interface PlannedWake {
  deviceId: string;
  fcmToken: string;
  priority: "high" | "normal";
}

/**
 * Every candidate not already told over a socket (M2), capped at `max` so a
 * membership bug cannot turn one write into an unbounded fan-out.
 *
 * `high` goes only to a device whose account asked to be notified about this
 * list and did not make the write — the one case where a notification is
 * likely to follow. Android demotes an app whose high-priority pushes end in
 * nothing on screen, so a silent sync is sent `normal`. A null author (a caller
 * that could not say) is nobody, so a subscriber still gets `high`: a
 * notification it then decides not to show costs less than one that arrives
 * late.
 */
export function planWake(
  candidates: readonly WakeCandidate[],
  connected: ReadonlySet<string>,
  authorUserId: string | null,
  max: number,
): PlannedWake[] {
  return candidates
    .filter((device) => !connected.has(device.deviceId))
    .slice(0, max)
    .map((device) => ({
      deviceId: device.deviceId,
      fcmToken: device.fcmToken,
      priority: device.notify && device.userId !== authorUserId ? "high" : "normal",
    }));
}
