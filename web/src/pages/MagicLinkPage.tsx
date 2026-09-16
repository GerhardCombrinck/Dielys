/**
 * `/magic?token=...` — reached by tapping the link in the email (ADR 0005).
 * Unlike Android, no App Link/deep-link plumbing is needed: it is already a
 * plain https:// URL, so a normal route read is enough
 * (domain/MagicLinkUrl.kt's Android-side parsing has no web equivalent to
 * port).
 */
import { useEffect, useState } from "react";
import { verifyMagicLink } from "../api/auth.js";
import { useSession } from "../auth/SessionContext.js";
import { navigate } from "../router.js";

type Status = "verifying" | "failed";

export function MagicLinkPage() {
  const session = useSession();
  const [status, setStatus] = useState<Status>("verifying");

  const { deviceId, signIn } = session;

  useEffect(() => {
    // A magic link is single-use (ADR 0005: "redeeming either spends both"),
    // so this must run exactly once for the token the page was opened with —
    // deviceId/signIn are pulled out above because the session object itself
    // is a fresh reference every render and would otherwise make this effect
    // think it has new deps to react to.
    const token = new URLSearchParams(window.location.search).get("token");
    if (token === null) {
      setStatus("failed");
      return;
    }
    verifyMagicLink(token, deviceId)
      .then((pair) => {
        signIn(pair);
        navigate("/");
      })
      .catch(() => setStatus("failed"));
  }, [deviceId, signIn]);

  if (status === "verifying") return <p>Signing you in...</p>;
  return (
    <div>
      <p>That link didn't work — it may have expired or already been used.</p>
      <button className="pill-button" type="button" onClick={() => navigate("/")}>
        Back to sign in
      </button>
    </div>
  );
}
