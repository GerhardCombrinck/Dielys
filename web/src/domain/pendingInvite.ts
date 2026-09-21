/**
 * An invite token that arrived while nobody was signed in, held until sign-in
 * completes — the web counterpart to `android/.../data/PendingInvite.kt`,
 * backed by `sessionStorage` rather than in-memory state: a magic-link
 * sign-in is a full page navigation (possibly a new tab), which in-memory
 * React state would not survive.
 */
const KEY = "dielys.pendingInvite";

export function offerInvite(token: string): void {
  try {
    sessionStorage.setItem(KEY, token);
  } catch {
    // Private-browsing storage refusal or similar — the invite link still
    // works, it just cannot be resumed automatically after sign-in.
  }
}

/** One-shot: a token taken once must not be acted on again. */
export function takePendingInvite(): string | null {
  try {
    const token = sessionStorage.getItem(KEY);
    if (token !== null) sessionStorage.removeItem(KEY);
    return token;
  } catch {
    return null;
  }
}
