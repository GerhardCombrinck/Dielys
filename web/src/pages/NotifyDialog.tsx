/**
 * Which changes somebody else makes to a list should reach this account's
 * phone as a notification (ADR 0012) — `android/.../ui/tasks/NotifyDialog.kt`'s
 * web counterpart. This browser never shows one: the choice belongs to the
 * account, so it is made here as well as on the phone, and says where they
 * arrive rather than implying this tab will buzz.
 */
import { NOTIFY_EVENTS, type NotifyEvent } from "@dielys/protocol";
import { useState } from "react";
import { useI18n } from "../i18n/I18nContext.js";
import type { MessageKey } from "../i18n/messages/en.js";

const LABELS: Record<NotifyEvent, MessageKey> = {
  added: "notify.added",
  checked: "notify.checked",
  deleted: "notify.deleted",
  updated: "notify.updated",
};

export function NotifyDialog({
  chosen,
  onSave,
  onDismiss,
}: {
  chosen: NotifyEvent[];
  onSave: (events: NotifyEvent[]) => void;
  onDismiss: () => void;
}) {
  const { t } = useI18n();
  const [selected, setSelected] = useState<NotifyEvent[]>(chosen);

  function toggle(event: NotifyEvent, on: boolean): void {
    setSelected((current) => (on ? [...current, event] : current.filter((e) => e !== event)));
  }

  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>{t("notify.title")}</h2>
        <div className="notify-options">
          {NOTIFY_EVENTS.map((event) => (
            <label key={event} className="notify-option">
              <input
                type="checkbox"
                checked={selected.includes(event)}
                onChange={(e) => toggle(event, e.target.checked)}
              />
              {t(LABELS[event])}
            </label>
          ))}
        </div>
        <p className="dialog-note">{t("notify.phoneOnly")}</p>
        <div className="dialog-actions">
          <button type="button" onClick={onDismiss}>
            {t("common.cancel")}
          </button>
          <button className="pill-button" type="button" onClick={() => onSave(selected)}>
            {t("notify.save")}
          </button>
        </div>
      </div>
    </div>
  );
}
