/**
 * `/invite?t=...` — reached by tapping the link in an invite email (same
 * `https://dielys.com/invite` App Link path Android's `InviteLink.kt` parses,
 * just read as a plain URL here, no deep-link plumbing needed).
 *
 * Accepting only writes a membership on the server; the list itself is
 * fetched fresh the moment this navigates to it, same as every other list
 * open (web/AGENTS.md) — unlike Android there is no local database to wait
 * on a catch-up landing in, so there is no separate "fetching" wait state to
 * show here.
 */
import { useEffect, useState } from "react";
import { ApiError } from "../api/client.js";
import { acceptInvite } from "../api/sharing.js";
import { useSession } from "../auth/SessionContext.js";
import { offerInvite } from "../domain/pendingInvite.js";
import { navigate } from "../router.js";

function describeJoinError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === "forbidden") {
      return "This invite was sent to a different email address than the one you are signed in with.";
    }
    if (err.code === "token-expired" || err.code === "unauthorized") {
      return "That invite has expired. Ask for a new one.";
    }
  }
  return "Could not accept the invite. Check your connection and try again.";
}

export function InvitePage() {
  const session = useSession();
  const [error, setError] = useState<string | null>(null);

  const status = session.status;
  useEffect(() => {
    if (status === "loading") return;

    const token = new URLSearchParams(window.location.search).get("t");
    if (token === null) {
      setError("That does not look like an invite.");
      return;
    }

    if (status === "signed-out") {
      offerInvite(token);
      navigate("/");
      return;
    }

    let cancelled = false;
    acceptInvite(token)
      .then((result) => {
        if (!cancelled) navigate(`/lists/${encodeURIComponent(result.listId)}`);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(describeJoinError(err));
      });
    return () => {
      cancelled = true;
    };
  }, [status]);

  if (error !== null) {
    return (
      <div className="page">
        <p className="error-text" role="alert">
          {error}
        </p>
        <button className="pill-button" type="button" onClick={() => navigate("/")}>
          Back to your lists
        </button>
      </div>
    );
  }

  return (
    <div className="page">
      <p>Joining…</p>
    </div>
  );
}
