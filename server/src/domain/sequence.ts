/**
 * Pure. No imports from storage/, no env, no Date.now(), no crypto.randomUUID()
 * (D1). Sequence numbers are assigned by the caller (the DO, in the same
 * transaction as the write) — this module just says what "next" means.
 */

export function nextSeq(currentMaxSeq: number): number {
  return currentMaxSeq + 1;
}
