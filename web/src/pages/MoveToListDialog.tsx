/**
 * Which list a task should go to (ADR 0015) — `android/.../ui/tasks/
 * MoveToListDialog.kt`'s web counterpart. Every other list in use, each in its
 * own colour; one tap moves it, with nothing to confirm, since the item is not
 * lost, only somewhere else.
 */
import { getAccent } from "../domain/accentStore.js";
import { accentColor, hashedAccent } from "../domain/accents.js";
import { useI18n } from "../i18n/I18nContext.js";
import type { MoveTarget } from "../sync/useTaskBoard.js";

export function MoveToListDialog({
  targets,
  onPick,
  onDismiss,
}: {
  targets: MoveTarget[];
  onPick: (listId: string) => void;
  onDismiss: () => void;
}) {
  const { t } = useI18n();

  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>{t("list.moveToTitle")}</h2>
        {targets.length === 0 ? (
          <p>{t("list.moveToNone")}</p>
        ) : (
          <div className="move-targets">
            {targets.map((target) => (
              <button
                key={target.id}
                type="button"
                className="move-target"
                onClick={() => onPick(target.id)}
              >
                <span
                  className="accent-dot"
                  style={{
                    background: accentColor(getAccent(target.id) ?? hashedAccent(target.id)),
                  }}
                />
                {target.title}
              </button>
            ))}
          </div>
        )}
        <div className="dialog-actions">
          <button type="button" onClick={onDismiss}>
            {t("common.cancel")}
          </button>
        </div>
      </div>
    </div>
  );
}
