/**
 * One id per browser, generated once and kept in `localStorage` — this is
 * the identifier a refresh token is scoped to and the F5.4 LWW tie-break
 * uses, so it must survive a reload the same way Android's device id
 * survives a reinstall-free redeploy (AGENTS.md).
 */
const STORAGE_KEY = "dielys.deviceId";

export function deviceId(): string {
  const existing = localStorage.getItem(STORAGE_KEY);
  if (existing !== null) return existing;
  const generated = crypto.randomUUID();
  localStorage.setItem(STORAGE_KEY, generated);
  return generated;
}
