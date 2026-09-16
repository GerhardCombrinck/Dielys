/**
 * Deleting an account from a page nobody is signed in on (ADR 0007, Google
 * Play's account-deletion requirement) — `dielys.com/account/delete` mails a
 * confirmation link, and the link opens `/account/delete/confirm?token=...`.
 * Neither route is gated on a session (App.tsx checks them ahead of the
 * sign-in gate, same as `/magic` and `/invite`): Play requires this to work
 * without the app, and it must work without being signed in on the web too.
 */
import { type FormEvent, useEffect, useState } from "react";
import { confirmAccountDeletion, requestAccountDeletion } from "../api/accountDeletion.js";
import { ApiError } from "../api/client.js";

export function RequestAccountDeletionPage() {
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  // No branch for "no such account" — the answer is the same either way
  // (ADR 0007), so this page cannot become a way to ask which emails exist.
  const [sent, setSent] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    const trimmed = email.trim();
    if (trimmed === "" || busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await requestAccountDeletion(trimmed);
      setSent(true);
    } catch (err) {
      setProblem(
        err instanceof ApiError && err.code === "rate-limited"
          ? "Too many attempts — try again later."
          : "Something went wrong. Try again.",
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-page">
      <h1>Delete your Dielys account</h1>
      {sent ? (
        <p>
          If {email.trim()} has a Dielys account, we've sent a link to confirm deleting it. The link
          is good for 15 minutes.
        </p>
      ) : (
        <>
          <p>
            Lists only you are on go with it. Lists you own that others are on pass to whoever has
            been on them longest — they stay, you do not.
          </p>
          <form onSubmit={submit}>
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
              Email me a deletion link
            </button>
          </form>
        </>
      )}
    </div>
  );
}

type ConfirmStatus = "waiting" | "working" | "done" | "failed";

export function ConfirmAccountDeletionPage() {
  const [token, setToken] = useState<string | null>(null);
  const [status, setStatus] = useState<ConfirmStatus>("waiting");
  const [problem, setProblem] = useState<string | null>(null);

  // Read once, on mount: this page must not act on the link just by loading
  // it — mail scanners and link previews open links on their own — so
  // nothing here runs until the button below is pressed.
  useEffect(() => {
    setToken(new URLSearchParams(window.location.search).get("token"));
  }, []);

  async function confirm(): Promise<void> {
    if (token === null) return;
    setStatus("working");
    try {
      await confirmAccountDeletion(token);
      setStatus("done");
    } catch (err) {
      setProblem(
        err instanceof ApiError && err.code === "rate-limited"
          ? "Too many attempts — try again later."
          : "This link has expired or has already been used. Request a new one.",
      );
      setStatus("failed");
    }
  }

  return (
    <div className="auth-page">
      <h1>Delete your Dielys account</h1>
      {token === null && <p className="error-text">This link is missing its token.</p>}
      {token !== null && status === "waiting" && (
        <>
          <p>This permanently deletes your account. It cannot be undone.</p>
          <button className="pill-button" type="button" onClick={() => void confirm()}>
            Delete my account
          </button>
        </>
      )}
      {status === "working" && <p>Deleting your account…</p>}
      {status === "done" && <p>Your account has been deleted.</p>}
      {status === "failed" && problem !== null && <p className="error-text">{problem}</p>}
    </div>
  );
}
