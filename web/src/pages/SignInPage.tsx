/**
 * Magic-link + code sign-in (ADR 0005/0008) — no password anywhere, mirrors
 * android/.../ui/auth/AuthScreen.kt's state machine: email entry, then
 * "check your email" plus a code field shown alongside it, not as a
 * separate step. The link itself is a real https://dielys.com/magic link on
 * the web, so tapping it in a mail client just opens MagicLinkPage — the
 * code field here is for someone who'd rather type six digits than switch
 * apps/tabs.
 */
import { type FormEvent, useEffect, useState } from "react";
import { magicLinkStatus, requestMagicLink, verifyMagicCode } from "../api/auth.js";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";

const POLL_INTERVAL_MS = 10_000;
const MAX_POLL_ATTEMPTS = 18; // ~3 minutes, matching the Android client
const RESEND_COOLDOWN_MS = 60_000;

type Stage = "email" | "sent";

export function SignInPage() {
  const session = useSession();
  const [stage, setStage] = useState<Stage>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [requestId, setRequestId] = useState<string | null>(null);
  const [delivered, setDelivered] = useState(false);
  const [sentAt, setSentAt] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // Ticks the clock once a second while a cooldown or poll window is live —
  // cheap, and simpler than a bespoke timer per piece of derived state.
  useEffect(() => {
    if (stage !== "sent") return;
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [stage]);

  useEffect(() => {
    if (stage !== "sent" || requestId === null || delivered) return;
    let attempts = 0;
    let cancelled = false;
    const id = window.setInterval(async () => {
      attempts += 1;
      if (attempts > MAX_POLL_ATTEMPTS) {
        window.clearInterval(id);
        return;
      }
      const result = await magicLinkStatus(requestId).catch(() => null);
      if (cancelled || result === null) return;
      if (result.delivered) {
        setDelivered(true);
        window.clearInterval(id);
      }
    }, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(id);
    };
  }, [stage, requestId, delivered]);

  async function send(email_: string) {
    setBusy(true);
    setProblem(null);
    try {
      const response = await requestMagicLink(email_);
      setRequestId(response.requestId);
      setDelivered(false);
      setSentAt(Date.now());
      setStage("sent");
    } catch (error) {
      setProblem(describe(error));
    } finally {
      setBusy(false);
    }
  }

  async function submitEmail(event: FormEvent) {
    event.preventDefault();
    if (email.trim().length === 0) return;
    await send(email.trim());
  }

  async function submitCode(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setProblem(null);
    try {
      const pair = await verifyMagicCode(email.trim(), code, session.deviceId);
      session.signIn(pair);
    } catch (error) {
      setProblem(describe(error));
    } finally {
      setBusy(false);
    }
  }

  const cooldownRemaining = sentAt === null ? 0 : Math.max(0, RESEND_COOLDOWN_MS - (now - sentAt));

  return (
    <div className="auth-page">
      <h1>Die Lys</h1>

      {stage === "email" && (
        <form onSubmit={submitEmail}>
          <input
            className="text-input"
            type="email"
            inputMode="email"
            placeholder="you@example.com"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          {problem !== null && <p className="error-text">{problem}</p>}
          <button className="pill-button" type="submit" disabled={busy || email.trim() === ""}>
            Email me a link
          </button>
        </form>
      )}

      {stage === "sent" && (
        <div>
          <p>
            {delivered
              ? `We've sent a link and a code to ${email}.`
              : `Sending a link and a code to ${email}...`}
          </p>
          <form onSubmit={submitCode}>
            <input
              className="text-input"
              inputMode="numeric"
              placeholder="6-digit code"
              value={code}
              onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
            />
            {problem !== null && <p className="error-text">{problem}</p>}
            <button className="pill-button" type="submit" disabled={busy || code.length !== 6}>
              Sign in with code
            </button>
          </form>
          <button
            className="pill-button secondary"
            type="button"
            disabled={busy || cooldownRemaining > 0}
            onClick={() => send(email)}
          >
            {cooldownRemaining > 0 ? `Resend in ${Math.ceil(cooldownRemaining / 1000)}s` : "Resend"}
          </button>
          <button
            className="pill-button secondary"
            type="button"
            onClick={() => {
              setStage("email");
              setCode("");
              setProblem(null);
            }}
          >
            Use a different email
          </button>
        </div>
      )}
    </div>
  );
}

function describe(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "rate-limited") return "Too many attempts — try again later.";
    if (error.code === "invalid-token")
      return "That code didn't match. Check the email and try again.";
    if (error.code === "token-expired") return "That code has expired — resend and try again.";
  }
  return "Something went wrong. Try again.";
}
