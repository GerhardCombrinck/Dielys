/**
 * Pure. Per-field last-write-wins on server timestamp, tie-broken by device
 * id (F5.4). Device clocks never enter this function — only server
 * timestamps, passed in by the caller.
 */

export interface Timestamped {
  serverTimestamp: string; // ISO 8601
  deviceId: string;
}

/** Returns true if `incoming` should overwrite `current` for one field. */
export function incomingWins(current: Timestamped, incoming: Timestamped): boolean {
  if (incoming.serverTimestamp !== current.serverTimestamp) {
    return incoming.serverTimestamp > current.serverTimestamp;
  }
  return incoming.deviceId > current.deviceId;
}
