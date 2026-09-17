/**
 * Account details, signing out and deleting the account —
 * `android/.../ui/settings/SettingsScreen.kt`'s web counterpart. No language
 * picker (the web client has no localized strings to switch between yet).
 */
import type { SyncSettings, SyncSettingsPatch } from "@dielys/protocol";
import { useEffect, useState } from "react";
import { deleteAccount, getSyncSettings, patchSyncSettings } from "../api/auth.js";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";
import { getNewItemsOnTop, setNewItemsOnTop } from "../domain/uiPrefs.js";
import { navigate } from "../router.js";
import { BackChevronIcon } from "../ui/icons.js";

/** A handful of common choices — Android's own picker is freeform, so
 * whatever it last sent is added below if it is not already one of these
 * (otherwise the select would silently jump to the nearest option). */
const SYNC_INTERVAL_PRESETS_MINUTES = [15, 30, 60, 120, 360, 720, 1440];

function syncIntervalOptions(current: number): number[] {
  return [...new Set([...SYNC_INTERVAL_PRESETS_MINUTES, current])].sort((a, b) => a - b);
}

function formatSyncInterval(minutes: number): string {
  if (minutes % 60 === 0) {
    const hours = minutes / 60;
    return `${hours} hour${hours === 1 ? "" : "s"}`;
  }
  return `${minutes} minutes`;
}

export function SettingsPage() {
  const session = useSession();
  const [newItemsOnTop, setNewItemsOnTopState] = useState(getNewItemsOnTop);
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // Mobile-only in effect (there is no background worker to schedule here),
  // but held server-side (ADR 0010) so it can be read and changed from
  // whichever client is at hand — this page never acts on it itself.
  const [sync, setSync] = useState<SyncSettings | null>(null);
  const [syncSaving, setSyncSaving] = useState(false);
  const [syncError, setSyncError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getSyncSettings()
      .then((settings) => {
        if (!cancelled) setSync(settings);
      })
      .catch(() => {
        if (!cancelled) setSyncError("Could not load the background sync setting.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  async function updateSync(patch: SyncSettingsPatch): Promise<void> {
    setSyncSaving(true);
    setSyncError(null);
    try {
      setSync(await patchSyncSettings(patch));
    } catch {
      setSyncError("Could not save. Check your connection and try again.");
    } finally {
      setSyncSaving(false);
    }
  }

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

      <section className="settings-section">
        <h2>Mobile background sync</h2>
        {sync !== null && (
          <>
            <div className="settings-segmented">
              <button
                type="button"
                className={sync.enabled ? "segmented-option selected" : "segmented-option"}
                disabled={syncSaving}
                onClick={() => void updateSync({ enabled: true })}
              >
                On
              </button>
              <button
                type="button"
                className={!sync.enabled ? "segmented-option selected" : "segmented-option"}
                disabled={syncSaving}
                onClick={() => void updateSync({ enabled: false })}
              >
                Off
              </button>
            </div>
            {sync.enabled && (
              <select
                className="text-input sync-interval-select"
                value={sync.intervalMinutes}
                disabled={syncSaving}
                onChange={(e) => void updateSync({ intervalMinutes: Number(e.target.value) })}
              >
                {syncIntervalOptions(sync.intervalMinutes).map((minutes) => (
                  <option key={minutes} value={minutes}>
                    Every {formatSyncInterval(minutes)}
                  </option>
                ))}
              </select>
            )}
          </>
        )}
        {syncError !== null && (
          <p className="error-text" role="alert">
            {syncError}
          </p>
        )}
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
