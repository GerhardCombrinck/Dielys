/**
 * Display-only redaction for the admin stats page (#84) — never a security
 * boundary, just less to see at a glance. Pure (D1): no I/O.
 */

/** "alice@example.com" -> "alice@…". A string with no "@" is left alone —
 *  it was never an email to begin with, so there is nothing to hide. */
export function obscureEmail(email: string): string {
  const at = email.indexOf("@");
  if (at === -1) return email;
  return `${email.slice(0, at)}@…`;
}
