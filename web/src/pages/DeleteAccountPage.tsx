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
import { useI18n } from "../i18n/I18nContext.js";

export function RequestAccountDeletionPage() {
  const { t } = useI18n();
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
          ? t("common.errorRateLimited")
          : t("common.errorGeneric"),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="auth-page">
      <h1>{t("deleteAccount.title")}</h1>
      {sent ? (
        <p>{t("deleteAccount.sentMessage", { email: email.trim() })}</p>
      ) : (
        <>
          <p>{t("deleteAccount.explainBody")}</p>
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
              {t("deleteAccount.submitCta")}
            </button>
          </form>
        </>
      )}
    </div>
  );
}

type ConfirmStatus = "waiting" | "working" | "done" | "failed";

export function ConfirmAccountDeletionPage() {
  const { t } = useI18n();
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
          ? t("common.errorRateLimited")
          : t("deleteAccount.linkExpired"),
      );
      setStatus("failed");
    }
  }

  return (
    <div className="auth-page">
      <h1>{t("deleteAccount.title")}</h1>
      {token === null && <p className="error-text">{t("deleteAccount.missingToken")}</p>}
      {token !== null && status === "waiting" && (
        <>
          <p>{t("deleteAccount.confirmIntro")}</p>
          <button className="pill-button" type="button" onClick={() => void confirm()}>
            {t("deleteAccount.confirmCta")}
          </button>
        </>
      )}
      {status === "working" && <p>{t("deleteAccount.deleting")}</p>}
      {status === "done" && <p>{t("deleteAccount.done")}</p>}
      {status === "failed" && problem !== null && <p className="error-text">{problem}</p>}
    </div>
  );
}
