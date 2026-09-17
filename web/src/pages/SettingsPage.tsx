/**
 * Account details, signing out and deleting the account —
 * `android/.../ui/settings/SettingsScreen.kt`'s web counterpart.
 */
import type { SyncSettings, SyncSettingsPatch } from "@dielys/protocol";
import { useEffect, useState } from "react";
import { deleteAccount, getSyncSettings, patchSyncSettings } from "../api/auth.js";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";
import { getNewItemsOnTop, setNewItemsOnTop } from "../domain/uiPrefs.js";
import { APP_LANGUAGES, useI18n } from "../i18n/I18nContext.js";
import { navigate } from "../router.js";
import { BackChevronIcon } from "../ui/icons.js";

/** A handful of common choices — Android's own picker is freeform, so
 * whatever it last sent is added below if it is not already one of these
 * (otherwise the select would silently jump to the nearest option). */
const SYNC_INTERVAL_PRESETS_MINUTES = [15, 30, 60, 120, 360, 720, 1440];

function syncIntervalOptions(current: number): number[] {
  return [...new Set([...SYNC_INTERVAL_PRESETS_MINUTES, current])].sort((a, b) => a - b);
}

function formatSyncInterval(minutes: number, plural: ReturnType<typeof useI18n>["plural"]): string {
  if (minutes % 60 === 0) {
    const hours = minutes / 60;
    return plural(hours, "settings.everyHours.one", "settings.everyHours.other", { n: hours });
  }
  return plural(minutes, "settings.everyMinutes.one", "settings.everyMinutes.other", {
    n: minutes,
  });
}

export function SettingsPage() {
  const session = useSession();
  const { t, plural, chosenTag, setLanguage } = useI18n();
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
        if (!cancelled) setSyncError(t("settings.syncLoadError"));
      });
    return () => {
      cancelled = true;
    };
  }, [t]);

  async function updateSync(patch: SyncSettingsPatch): Promise<void> {
    setSyncSaving(true);
    setSyncError(null);
    try {
      setSync(await patchSyncSettings(patch));
    } catch {
      setSyncError(t("settings.syncSaveError"));
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
      !window.confirm(`${t("settings.deleteAccountQuestion")} ${t("deleteAccount.explainBody")}`)
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
          ? t("settings.deleteAccountFailed", { code: err.code })
          : t("settings.deleteAccountNetworkError"),
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
          aria-label={t("common.backToLists")}
          onClick={() => navigate("/")}
        >
          <BackChevronIcon />
        </button>
        <h1>{t("settings.title")}</h1>
      </header>

      <section className="settings-section">
        <h2>{t("settings.account")}</h2>
        <p className="settings-detail-label">{t("settings.email")}</p>
        <p>{session.email ?? t("settings.emailUnknown")}</p>
      </section>

      <section className="settings-section">
        <h2>{t("settings.newItemsGoTo")}</h2>
        <div className="settings-segmented">
          <button
            type="button"
            className={newItemsOnTop ? "segmented-option selected" : "segmented-option"}
            onClick={() => choosePlacement(true)}
          >
            {t("settings.top")}
          </button>
          <button
            type="button"
            className={!newItemsOnTop ? "segmented-option selected" : "segmented-option"}
            onClick={() => choosePlacement(false)}
          >
            {t("settings.bottom")}
          </button>
        </div>
      </section>

      <section className="settings-section">
        <h2>{t("settings.language")}</h2>
        <select
          className="text-input"
          value={chosenTag ?? ""}
          onChange={(e) => setLanguage(e.target.value === "" ? null : e.target.value)}
        >
          <option value="">{t("settings.languageSystemDefault")}</option>
          {APP_LANGUAGES.map((language) => (
            <option key={language.tag} value={language.tag}>
              {language.nativeName}
            </option>
          ))}
        </select>
      </section>

      <section className="settings-section">
        <h2>{t("settings.mobileSync")}</h2>
        {sync !== null && (
          <>
            <div className="settings-segmented">
              <button
                type="button"
                className={sync.enabled ? "segmented-option selected" : "segmented-option"}
                disabled={syncSaving}
                onClick={() => void updateSync({ enabled: true })}
              >
                {t("common.on")}
              </button>
              <button
                type="button"
                className={!sync.enabled ? "segmented-option selected" : "segmented-option"}
                disabled={syncSaving}
                onClick={() => void updateSync({ enabled: false })}
              >
                {t("common.off")}
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
                    {formatSyncInterval(minutes, plural)}
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
        {t("settings.signOut")}
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
        {deleting ? t("settings.deletingAccount") : t("settings.deleteAccount")}
      </button>
      {deleteError !== null && (
        <p className="error-text" role="alert">
          {deleteError}
        </p>
      )}
    </div>
  );
}
