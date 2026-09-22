/**
 * Magic-link + code sign-in (ADR 0005/0008) — no password anywhere, mirrors
 * android/.../ui/auth/AuthScreen.kt's state machine: email entry, then
 * "check your email" plus a code field shown alongside it, not as a
 * separate step. The link itself is a real https://dielys.com/magic link on
 * the web, so tapping it in a mail client just opens MagicLinkPage — the
 * code field here is for someone who'd rather type six digits than switch
 * apps/tabs. Visual design: design_handoff_web_auth/Auth.dc.html (2026-09).
 */
import { type FormEvent, useEffect, useState } from "react";
import { magicLinkStatus, requestMagicLink, verifyMagicCode } from "../api/auth.js";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";
import { useI18n } from "../i18n/I18nContext.js";
import { GooglePlayLink } from "../ui/AppFooter.js";
import { LogoMarkIcon, Spinner } from "../ui/icons.js";

const POLL_INTERVAL_MS = 10_000;
const MAX_POLL_ATTEMPTS = 18; // ~3 minutes, matching the Android client
const RESEND_COOLDOWN_MS = 60_000;

type Stage = "email" | "sent";

export function SignInPage() {
  const session = useSession();
  const { t } = useI18n();
  const [stage, setStage] = useState<Stage>("email");
  const [email, setEmail] = useState("");
  const [code, setCode] = useState("");
  const [requestId, setRequestId] = useState<string | null>(null);
  const [delivered, setDelivered] = useState(false);
  const [sentAt, setSentAt] = useState<number | null>(null);
  const [busyEmail, setBusyEmail] = useState(false);
  const [busyCode, setBusyCode] = useState(false);
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
    setBusyEmail(true);
    setProblem(null);
    try {
      const response = await requestMagicLink(email_);
      // Before the link even arrives: the server never hands the email back
      // (session.rememberEmail), so this is the only chance to have it on
      // hand for Settings later, and `/magic` may open in a new tab.
      session.rememberEmail(email_);
      setRequestId(response.requestId);
      setDelivered(false);
      setSentAt(Date.now());
      setCode("");
      setStage("sent");
    } catch (error) {
      setProblem(describe(error, t));
    } finally {
      setBusyEmail(false);
    }
  }

  async function submitEmail(event: FormEvent) {
    event.preventDefault();
    if (email.trim().length === 0) return;
    await send(email.trim());
  }

  async function submitCode(event: FormEvent) {
    event.preventDefault();
    if (code.length !== 6) return;
    setBusyCode(true);
    setProblem(null);
    try {
      const pair = await verifyMagicCode(email.trim(), code, session.deviceId);
      session.signIn(pair);
    } catch (error) {
      setProblem(describe(error, t));
    } finally {
      setBusyCode(false);
    }
  }

  const cooldownRemaining = sentAt === null ? 0 : Math.max(0, RESEND_COOLDOWN_MS - (now - sentAt));

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-header">
          <div className="auth-badge">
            <LogoMarkIcon />
          </div>
          <h1>Die Lys</h1>
          <div className="auth-tagline">SIT DIT OP DIE LYS</div>
        </div>

        {stage === "email" && (
          <div className="auth-stage">
            <p className="auth-intro">{t("signin.intro")}</p>
            <form onSubmit={submitEmail}>
              <input
                className="text-input"
                type="email"
                inputMode="email"
                placeholder="you@example.com"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                disabled={busyEmail}
              />
              {problem !== null && <p className="error-text">{problem}</p>}
              <button
                className="pill-button full-width"
                type="submit"
                style={{ marginTop: "1.25rem" }}
                disabled={busyEmail || email.trim() === ""}
              >
                {busyEmail && <Spinner />}
                <span>{busyEmail ? t("signin.sending") : t("signin.emailMeALink")}</span>
              </button>
            </form>

            <div className="auth-divider" />
            <GooglePlayLink className="pill-button secondary full-width play-button" />
          </div>
        )}

        {stage === "sent" && (
          <div className="auth-stage">
            <h2>{t("signin.checkEmailTitle")}</h2>
            <p className="auth-intro">
              {delivered ? t("signin.delivered", { email }) : t("signin.sent", { email })}
            </p>

            {delivered && (
              <div className="auth-delivered">
                <span className="check">✔</span>
                <span>📧</span>
              </div>
            )}

            <div className="auth-divider" />

            <p className="auth-code-hint">{t("signin.codeHint")}</p>
            <form onSubmit={submitCode}>
              <input
                className="text-input code-input"
                inputMode="numeric"
                placeholder={t("signin.codePlaceholder")}
                value={code}
                onChange={(event) => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))}
                disabled={busyCode}
              />
              {problem !== null && <p className="error-text">{problem}</p>}
              <button
                className="pill-button secondary full-width"
                type="submit"
                style={{ marginTop: "0.75rem" }}
                disabled={busyCode || code.length !== 6}
              >
                {busyCode && <Spinner />}
                <span>{busyCode ? t("signin.signingIn") : t("signin.signInWithCode")}</span>
              </button>
            </form>

            <div className="auth-links">
              {!delivered && (
                <button
                  type="button"
                  className="text-button link"
                  disabled={busyEmail || cooldownRemaining > 0}
                  style={cooldownRemaining > 0 ? { opacity: 0.5 } : undefined}
                  onClick={() => send(email)}
                >
                  {cooldownRemaining > 0
                    ? t("signin.resendIn", { s: Math.ceil(cooldownRemaining / 1000) })
                    : t("signin.resendLink")}
                </button>
              )}
              <button
                type="button"
                className="text-button muted"
                onClick={() => {
                  setStage("email");
                  setCode("");
                  setProblem(null);
                }}
              >
                {t("signin.useDifferentEmail")}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function describe(error: unknown, t: ReturnType<typeof useI18n>["t"]): string {
  if (error instanceof ApiError) {
    if (error.code === "rate-limited") return t("common.errorRateLimited");
    if (error.code === "invalid-token") return t("signin.errorCodeInvalid");
    if (error.code === "token-expired") return t("signin.errorCodeExpired");
  }
  return t("common.errorGeneric");
}
