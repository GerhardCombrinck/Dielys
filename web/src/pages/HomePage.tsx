/**
 * The lists screen (`android/.../ui/lists/ListsScreen.kt`'s web counterpart).
 * Fed by `sync/useListsOverview.ts` — a fresh `/auth/memberships` plus one
 * catch-up per list, since there is no local database to read instead.
 */
import { useEffect, useState } from "react";
import { ApiError } from "../api/client.js";
import { useSession } from "../auth/SessionContext.js";
import { ACCENT_COUNT, accentColor } from "../domain/accents.js";
import { takePendingInvite } from "../domain/pendingInvite.js";
import { navigate } from "../router.js";
import { type ListRow, useListsOverview } from "../sync/useListsOverview.js";
import { useSharing } from "../sync/useSharing.js";
import { dropNeighbors } from "../ui/reorder.js";
import { InviteDialog, MembersDialog } from "./SharingDialogs.js";

export function HomePage() {
  const session = useSession();
  const overview = useListsOverview(session.deviceId);
  const sharing = useSharing(session.status === "signed-in" ? session.userId : "");
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [newTitle, setNewTitle] = useState("");
  const [creating, setCreating] = useState(false);
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  // An invite tapped while signed out is stashed (domain/pendingInvite.ts)
  // and resumed once this screen is reached signed in — the same moment
  // Android's `acceptInvitation()` resumes a tapped link that arrived before
  // sign-in finished.
  useEffect(() => {
    const token = takePendingInvite();
    if (token !== null) navigate(`/invite?t=${encodeURIComponent(token)}`);
  }, []);

  if (session.status !== "signed-in") return null;

  const rows = overview.rows?.filter((r) => r.title !== null) ?? null;

  async function handleCreate(): Promise<void> {
    const title = newTitle.trim();
    if (title === "" || creating) return;
    setCreating(true);
    try {
      const id = await overview.createList(title);
      setNewTitle("");
      if (id !== "") navigate(`/lists/${encodeURIComponent(id)}`);
    } catch {
      setError("Could not create the list. Check your connection and try again.");
    } finally {
      setCreating(false);
    }
  }

  async function handleDelete(row: ListRow): Promise<void> {
    setMenuFor(null);
    const title = row.title ?? "this list";
    if (!window.confirm(`Delete "${title}"? This removes it for everyone on it.`)) return;
    try {
      await overview.deleteList(row.membership.listId);
    } catch (err) {
      setError(
        err instanceof ApiError && err.code === "forbidden"
          ? "Only the owner can delete this list."
          : "Could not delete the list.",
      );
    }
  }

  async function handleLeave(row: ListRow): Promise<void> {
    setMenuFor(null);
    const title = row.title ?? "this list";
    if (!window.confirm(`Leave "${title}"? You will need a new invite to see it again.`)) return;
    try {
      await sharing.leave(row.membership.listId);
      overview.refresh();
    } catch {
      setError("Could not leave the list. Check your connection and try again.");
    }
  }

  function handleDrop(targetIndex: number): void {
    if (dragIndex === null || rows === null) return;
    const ids = rows.map((r) => r.membership.listId);
    if (dragIndex !== targetIndex) {
      const { id, afterId, beforeId } = dropNeighbors(ids, dragIndex, targetIndex);
      void overview.moveList(id, afterId, beforeId);
    }
    setDragIndex(null);
  }

  return (
    <div className="page">
      <header className="page-header">
        <h1>Die Lys</h1>
        <button
          className="icon-button"
          type="button"
          aria-label="Settings"
          onClick={() => navigate("/settings")}
        >
          ⚙
        </button>
      </header>

      {error !== null && (
        <p className="error-text" role="alert">
          {error}
        </p>
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
          placeholder="New list"
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
        />
        <button className="pill-button" type="submit" disabled={creating || newTitle.trim() === ""}>
          Add
        </button>
      </form>

      {rows === null && <p>Loading…</p>}
      {rows !== null && rows.length === 0 && <p>Make your first list above.</p>}

      <ul className="row-list">
        {rows?.map((row, index) => (
          <li
            key={row.membership.listId}
            className="list-row"
            draggable
            onDragStart={() => setDragIndex(index)}
            onDragOver={(e) => e.preventDefault()}
            onDrop={() => handleDrop(index)}
          >
            <span className="drag-handle" aria-hidden="true">
              ⠿
            </span>
            <span className="accent-dot" style={{ background: accentColor(row.accent) }} />

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
                {row.itemCount > 0 && <span className="row-count"> · {row.itemCount}</span>}
                {row.membership.memberCount > 1 && <span className="row-shared"> · shared</span>}
              </button>
            )}

            {row.membership.memberCount > 1 && (
              <button
                type="button"
                className="icon-button"
                aria-label="Shared list members"
                onClick={() => void sharing.openMembers(row.membership.listId)}
              >
                👥
              </button>
            )}

            <button
              type="button"
              className="icon-button"
              aria-label="List options"
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
                  aria-label="Close menu"
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
                    Rename
                  </button>
                  <div className="accent-row">
                    {Array.from({ length: ACCENT_COUNT }, (_, i) => (
                      <button
                        key={accentColor(i)}
                        type="button"
                        className="accent-swatch"
                        aria-label={`Colour ${i + 1}`}
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
                        sharing.openInvite(row.membership.listId, row.title ?? "Untitled list");
                        setMenuFor(null);
                      }}
                    >
                      Share
                    </button>
                  )}
                  {/* One or the other, never both (ADR 0006): a delete takes the
                      list off every member's phone, so it is the owner's to make;
                      anybody else can still get it off their own, by leaving. */}
                  {row.membership.role === "owner" ? (
                    <button
                      type="button"
                      className="menu-danger"
                      onClick={() => void handleDelete(row)}
                    >
                      Delete
                    </button>
                  ) : (
                    <button type="button" onClick={() => void handleLeave(row)}>
                      Leave
                    </button>
                  )}
                </div>
              </>
            )}
          </li>
        ))}
      </ul>

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
