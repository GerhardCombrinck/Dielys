/**
 * The lists screen (`android/.../ui/lists/ListsScreen.kt`'s web counterpart).
 * Fed by `sync/useListsOverview.ts`, which reads the local replica
 * (`data/replica.ts`) and lets `SyncEngine` catch it up behind the scenes.
 * Visual design: design_handoff_web_auth/Lists.dc.html (2026-09).
 */
import { useEffect, useState } from "react";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";
import { ACCENT_COUNT, accentColor } from "../domain/accents.js";
import { takePendingInvite } from "../domain/pendingInvite.js";
import { useI18n } from "../i18n/I18nContext.js";
import { navigate } from "../router.js";
import { type ListRow, useListsOverview } from "../sync/useListsOverview.js";
import { useSharing } from "../sync/useSharing.js";
import { ChevronDownIcon, GearIcon, HelpIcon, PeopleIcon, Spinner } from "../ui/icons.js";
import { useDragReorder } from "../ui/useDragReorder.js";
import { InviteDialog, MembersDialog } from "./SharingDialogs.js";

type ConfirmTarget = { row: ListRow; kind: "delete" | "leave" };

/** Archived lists fold away under the others (ADR 0014); remembered per browser. */
const ARCHIVED_EXPANDED_KEY = "dielys.archivedExpanded";

function readArchivedExpanded(): boolean {
  try {
    return localStorage.getItem(ARCHIVED_EXPANDED_KEY) === "1";
  } catch {
    return false;
  }
}

export function HomePage() {
  const session = useSession();
  const { t } = useI18n();
  const overview = useListsOverview();
  const sharing = useSharing(session.status === "signed-in" ? session.userId : "");
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [creating, setCreating] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<ConfirmTarget | null>(null);
  /** The archived list a restore is asking about: whether to untick what was
   * ticked last time. Only asked when something is ticked (ADR 0014). */
  const [restoring, setRestoring] = useState<ListRow | null>(null);
  const [archivedExpanded, setArchivedExpanded] = useState(readArchivedExpanded);
  const [error, setError] = useState<string | null>(null);

  // An invite tapped while signed out is stashed (domain/pendingInvite.ts)
  // and resumed once this screen is reached signed in — the same moment
  // Android's `acceptInvitation()` resumes a tapped link that arrived before
  // sign-in finished.
  useEffect(() => {
    const token = takePendingInvite();
    if (token !== null) navigate(`/invite?t=${encodeURIComponent(token)}`);
  }, []);

  const rows = overview.rows?.filter((r) => r.title !== null) ?? null;
  // Archived lists sit in their own fold and are not part of the drag order.
  const active = rows?.filter((r) => !r.archived) ?? [];
  const archived = rows?.filter((r) => r.archived) ?? [];
  const reorder = useDragReorder(
    active.map((r) => r.membership.listId),
    overview.moveList,
  );
  const rowsById = new Map(active.map((r) => [r.membership.listId, r]));
  const shownRows = reorder.order.flatMap((id) => rowsById.get(id) ?? []);

  if (session.status !== "signed-in") return null;

  function toggleArchived(): void {
    const next = !archivedExpanded;
    setArchivedExpanded(next);
    try {
      localStorage.setItem(ARCHIVED_EXPANDED_KEY, next ? "1" : "0");
    } catch {
      // Not remembered this time; the fold still works.
    }
  }

  function restore(row: ListRow): void {
    if (row.doneCount > 0) setRestoring(row);
    else overview.restoreList(row.membership.listId, false);
  }

  async function handleCreate(): Promise<void> {
    const title = newTitle.trim();
    if (title === "" || creating) return;
    setCreating(true);
    try {
      const id = await overview.createList(title);
      setNewTitle("");
      if (id !== "") navigate(`/lists/${encodeURIComponent(id)}`);
    } catch {
      setError(t("home.errorCreate"));
    } finally {
      setCreating(false);
    }
  }

  async function handleConfirm(): Promise<void> {
    const target = confirmTarget;
    if (target === null) return;
    setConfirmTarget(null);
    if (target.kind === "delete") {
      try {
        await overview.deleteList(target.row.membership.listId);
      } catch (err) {
        setError(
          err instanceof ApiError && err.code === "forbidden"
            ? t("home.errorDeleteForbidden")
            : t("home.errorDelete"),
        );
      }
    } else {
      try {
        await sharing.leave(target.row.membership.listId);
        overview.refresh();
      } catch {
        setError(t("home.errorLeave"));
      }
    }
  }

  /** One list's row, active or archived; only an active one is draggable. */
  function renderRow(
    row: ListRow,
    drag: ReturnType<typeof reorder.rowProps> | Record<string, never>,
  ) {
    return (
      <li
        key={row.membership.listId}
        className={row.archived ? "list-row archived" : "list-row"}
        {...drag}
      >
        <span className="drag-handle" aria-hidden="true">
          {row.archived ? "" : "⠿"}
        </span>
        <span className="accent-dot" style={{ background: accentColor(row.accent) }} />

        <div className="row-title-wrap">
          {editingId === row.membership.listId ? (
            <input
              className="text-input inline-edit"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              onBlur={() => {
                void overview.renameList(row.membership.listId, draft);
                setEditingId(null);
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") e.currentTarget.blur();
                if (e.key === "Escape") setEditingId(null);
              }}
            />
          ) : (
            <button
              type="button"
              className="row-title"
              onClick={() => navigate(`/lists/${encodeURIComponent(row.membership.listId)}`)}
            >
              {row.title}
            </button>
          )}
        </div>

        {row.membership.memberCount > 1 && (
          <button
            type="button"
            className="icon-button small"
            aria-label={t("home.sharedMembers")}
            onClick={() => void sharing.openMembers(row.membership.listId)}
          >
            <PeopleIcon />
          </button>
        )}

        <span className="row-count">{row.itemCount > 0 ? row.itemCount : ""}</span>

        <button
          type="button"
          className="icon-button small"
          aria-label={t("home.listOptions")}
          onClick={() =>
            setMenuFor(menuFor === row.membership.listId ? null : row.membership.listId)
          }
        >
          ⋯
        </button>

        {menuFor === row.membership.listId && (
          <>
            <button
              type="button"
              className="menu-overlay"
              aria-label={t("common.closeMenu")}
              onClick={() => setMenuFor(null)}
            />
            <div className="menu-popover">
              <button
                type="button"
                onClick={() => {
                  setDraft(row.title ?? "");
                  setEditingId(row.membership.listId);
                  setMenuFor(null);
                }}
              >
                {t("common.rename")}
              </button>
              <div className="accent-row">
                {Array.from({ length: ACCENT_COUNT }, (_, i) => (
                  <button
                    key={accentColor(i)}
                    type="button"
                    className="accent-swatch"
                    aria-label={t("home.colourOption", { n: i + 1 })}
                    style={{ background: accentColor(i) }}
                    onClick={() => {
                      overview.setAccent(row.membership.listId, i);
                      setMenuFor(null);
                    }}
                  />
                ))}
              </div>
              {/* L3: only the owner may invite, so a list somebody else shared
                does not offer it rather than offering it and being refused. */}
              {row.membership.role === "owner" && (
                <button
                  type="button"
                  onClick={() => {
                    sharing.openInvite(row.membership.listId, row.title ?? t("list.untitled"));
                    setMenuFor(null);
                  }}
                >
                  {t("common.share")}
                </button>
              )}
              {/* ADR 0014: archiving puts the list away for everybody on it, so it
                is the owner's, like a delete — and so is bringing it back. */}
              {row.membership.role === "owner" && (
                <button
                  type="button"
                  onClick={() => {
                    setMenuFor(null);
                    if (row.archived) restore(row);
                    else overview.archiveList(row.membership.listId);
                  }}
                >
                  {row.archived ? t("common.restore") : t("common.archive")}
                </button>
              )}
              {/* One or the other, never both (ADR 0006): a delete takes the
                list off every member's phone, so it is the owner's to make;
                anybody else can still get it off their own, by leaving. */}
              {row.membership.role === "owner" ? (
                <button
                  type="button"
                  className="menu-danger"
                  onClick={() => {
                    setMenuFor(null);
                    setConfirmTarget({ row, kind: "delete" });
                  }}
                >
                  {t("common.delete")}
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => {
                    setMenuFor(null);
                    setConfirmTarget({ row, kind: "leave" });
                  }}
                >
                  {t("common.leave")}
                </button>
              )}
            </div>
          </>
        )}
      </li>
    );
  }

  return (
    <div className="page">
      <header className="page-header">
        <div className="page-header-titles">
          <h1>Die Lys</h1>
          <div className="page-tagline">{t("home.tagline")}</div>
        </div>
        <button
          className="icon-button"
          type="button"
          aria-label={t("help.title")}
          onClick={() => navigate("/help")}
        >
          <HelpIcon />
        </button>
        <button
          className="icon-button"
          type="button"
          aria-label={t("common.settings")}
          onClick={() => navigate("/settings")}
        >
          <GearIcon />
        </button>
      </header>

      {error !== null && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}

      {rows === null && (
        <div className="page-spinner">
          <Spinner muted />
        </div>
      )}
      {rows !== null && rows.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-title">{t("home.emptyTitle")}</div>
          <div className="empty-state-body">{t("home.emptyBody")}</div>
        </div>
      )}

      {active.length > 0 && (
        <ul className="row-list">
          {shownRows.map((row) => renderRow(row, reorder.rowProps(row.membership.listId)))}
        </ul>
      )}

      {archived.length > 0 && (
        <div className="done-section">
          <button
            type="button"
            className="done-toggle"
            aria-expanded={archivedExpanded}
            onClick={toggleArchived}
          >
            <span>{t("home.archivedCount", { n: archived.length })}</span>
            <ChevronDownIcon rotated={archivedExpanded} />
          </button>
          {archivedExpanded && (
            <ul className="row-list">{archived.map((row) => renderRow(row, {}))}</ul>
          )}
        </div>
      )}

      <form
        className="new-row"
        onSubmit={(e) => {
          e.preventDefault();
          void handleCreate();
        }}
      >
        <input
          className="text-input"
          placeholder={t("home.newListPlaceholder")}
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
        />
        <button className="pill-button" type="submit" disabled={creating || newTitle.trim() === ""}>
          {t("home.add")}
        </button>
      </form>

      {confirmTarget !== null && (
        <div className="dialog-overlay">
          <div className="dialog-box">
            <h2>
              {confirmTarget.kind === "delete"
                ? t("home.deleteListTitle", {
                    title: confirmTarget.row.title ?? t("home.untitledFallback"),
                  })
                : t("home.leaveListTitle", {
                    title: confirmTarget.row.title ?? t("home.untitledFallback"),
                  })}
            </h2>
            <p>
              {confirmTarget.kind === "delete" ? t("home.deleteListBody") : t("common.leaveBody")}
            </p>
            <div className="dialog-actions">
              <button type="button" onClick={() => setConfirmTarget(null)}>
                {t("common.cancel")}
              </button>
              <button
                className="pill-button danger"
                type="button"
                onClick={() => void handleConfirm()}
              >
                {confirmTarget.kind === "delete" ? t("common.delete") : t("common.leave")}
              </button>
            </div>
          </div>
        </div>
      )}

      {restoring !== null && (
        <div className="dialog-overlay">
          <div className="dialog-box">
            <h2>
              {t("home.restoreListTitle", {
                title: restoring.title ?? t("home.untitledFallback"),
              })}
            </h2>
            <p>{t("home.restoreListBody")}</p>
            <div className="dialog-actions">
              <button type="button" onClick={() => setRestoring(null)}>
                {t("common.cancel")}
              </button>
              <button
                type="button"
                onClick={() => {
                  overview.restoreList(restoring.membership.listId, false);
                  setRestoring(null);
                }}
              >
                {t("home.keepTicks")}
              </button>
              <button
                className="pill-button"
                type="button"
                onClick={() => {
                  overview.restoreList(restoring.membership.listId, true);
                  setRestoring(null);
                }}
              >
                {t("home.untickAll")}
              </button>
            </div>
          </div>
        </div>
      )}

      {sharing.members !== null && (
        <MembersDialog
          state={sharing.members}
          onRemove={(listId, userId) =>
            void sharing.removeMember(listId, userId).then(() => overview.refresh())
          }
          onDismiss={sharing.dismissMembers}
        />
      )}

      {sharing.invite !== null && (
        <InviteDialog
          state={sharing.invite}
          onSend={sharing.sendInvite}
          onDismiss={sharing.dismissInvite}
        />
      )}
    </div>
  );
}
