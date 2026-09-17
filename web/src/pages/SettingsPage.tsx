/**
 * Account details, signing out and deleting the account —
 * `android/.../ui/settings/SettingsScreen.kt`'s web counterpart. No language
 * picker (the web client has no localized strings to switch between yet).
 */
import { useState } from "react";
import { deleteAccount } from "../api/auth.js";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";
import { getNewItemsOnTop, setNewItemsOnTop } from "../domain/uiPrefs.js";
import { navigate } from "../router.js";
import { BackChevronIcon } from "../ui/icons.js";

export function SettingsPage() {
  const session = useSession();
  const [newItemsOnTop, setNewItemsOnTopState] = useState(getNewItemsOnTop);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  if (session.status !== "signed-in") return null;

  function choosePlacement(top: boolean): void {
    setNewItemsOnTop(top);
    setNewItemsOnTopState(top);
  }

  async function handleDeleteAccount(): Promise<void> {
    if (
      !window.confirm(
        "Delete your account? Lists only you are on go with it. Lists you own that others " +
          "are on pass to whoever has been on them longest — they stay, you do not.",
      )
    ) {
      return;
    }
    setDeleting(true);
    setDeleteError(null);
    try {
      await deleteAccount();
      session.signOut();
      navigate("/");
    } catch (err) {
      setDeleteError(
        err instanceof ApiError
          ? `Could not delete your account: ${err.code}.`
          : "Could not delete your account. Check your connection and try again.",
      );
      setDeleting(false);
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <button
          className="icon-button"
          type="button"
          aria-label="Back to lists"
          onClick={() => navigate("/")}
        >
          <BackChevronIcon />
        </button>
        <h1>Settings</h1>
      </header>

      <section className="settings-section">
        <h2>Account</h2>
        <p className="settings-detail-label">Email</p>
        <p>{session.email ?? "Unknown"}</p>
      </section>

      <section className="settings-section">
        <h2>New items go to</h2>
        <div className="settings-segmented">
          <button
            type="button"
            className={newItemsOnTop ? "segmented-option selected" : "segmented-option"}
            onClick={() => choosePlacement(true)}
          >
            Top
          </button>
          <button
            type="button"
            className={!newItemsOnTop ? "segmented-option selected" : "segmented-option"}
            onClick={() => choosePlacement(false)}
          >
            Bottom
          </button>
        </div>
      </section>

      <button className="pill-button settings-sign-out" type="button" onClick={session.signOut}>
        Sign out
      </button>

      {/* Below signing out and quieter than it (a text button, not a
          filled one), so the permanent action is never the one a thumb
          lands on by habit. */}
      <button
        type="button"
        className="settings-delete-account"
        disabled={deleting}
        onClick={() => void handleDeleteAccount()}
      >
        {deleting ? "Deleting your account…" : "Delete account"}
      </button>
      {deleteError !== null && (
        <p className="error-text" role="alert">
          {deleteError}
        </p>
      )}
    </div>
  );
}
