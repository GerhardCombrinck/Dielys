/**
 * Placeholder for the signed-in shell. Lists/tasks/sharing/settings land in
 * later phases (see the tracking issue) — this phase only has to prove sign-in
 * works end to end.
 */
import { useSession } from "../auth/SessionContext.js";

export function HomePage() {
  const session = useSession();
  if (session.status !== "signed-in") return null;

  return (
    <div>
      <h1>Die Lys</h1>
      <p>Signed in{session.userId !== "" ? ` (${session.userId})` : ""}.</p>
      <p>Lists are coming soon.</p>
      <button className="pill-button secondary" type="button" onClick={session.signOut}>
        Sign out
      </button>
    </div>
  );
}
