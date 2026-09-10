/**
 * Sends the magic-link email through Brevo's transactional API, over `fetch`.
 * No storage access and no knowledge of tokens or accounts (D1) — it is
 * handed an address and a link and reports whether Brevo accepted the send.
 *
 * Unlike `push/fcm.ts`, a failed send here is not best-effort: a wake push
 * that never arrives is caught up by the next sync, but a magic link that
 * never left this Worker is a login the caller cannot complete. The caller
 * (`UsersRoom.requestMagicLink`) surfaces a failed send as an error rather
 * than swallowing it the way a dead FCM token is swallowed.
 */
import { log } from "../lib/log.js";

const BREVO_SEND_URL = "https://api.brevo.com/v3/smtp/email";

/** The `From` identity every magic-link email is sent as. */
export interface EmailSender {
  email: string;
  name: string;
}

/**
 * Reads `EMAIL_FROM` / `EMAIL_FROM_NAME`, or answers null when there is
 * nothing usable there. Null is what makes `/auth/magic/*` fail closed (ADR
 * 0005) — checked by the Worker before this module is ever reached, the same
 * shape as `isUsableSigningKey`.
 */
export function parseEmailSender(
  rawFrom: string | undefined,
  rawName: string | undefined,
): EmailSender | null {
  if (typeof rawFrom !== "string" || rawFrom.trim().length === 0) return null;
  const name = typeof rawName === "string" && rawName.trim().length > 0 ? rawName.trim() : "Dielys";
  return { email: rawFrom.trim(), name };
}

/**
 * Sends one sign-in link. Never throws — a network failure and a rejection
 * from Brevo answer the same way, and the caller decides what "not sent"
 * means for the request in front of it.
 */
export async function sendMagicLinkEmail(
  apiKey: string,
  sender: EmailSender,
  to: string,
  link: string,
  ttlMinutes: number,
): Promise<boolean> {
  let response: Response;
  try {
    response = await fetch(BREVO_SEND_URL, {
      method: "POST",
      headers: {
        "api-key": apiKey,
        "content-type": "application/json",
        accept: "application/json",
      },
      body: JSON.stringify({
        sender: { email: sender.email, name: sender.name },
        to: [{ email: to }],
        subject: "Sign in to Dielys",
        textContent: `Tap this link on your phone to sign in to Dielys:\n\n${link}\n\nIt expires in ${ttlMinutes} minutes and works once. If you did not request this, ignore this email.`,
      }),
    });
  } catch (error) {
    log("warn", "brevo.send.failed", { error: String(error) });
    return false;
  }

  if (!response.ok) {
    // Never log the body: a Brevo error response echoes the recipient (D4).
    log("warn", "brevo.send.rejected", { status: response.status });
    return false;
  }
  return true;
}
