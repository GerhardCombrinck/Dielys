/**
 * One list's tasks (`android/.../ui/tasks/TaskListScreen.kt`'s web
 * counterpart). Fed by `sync/useTaskBoard.ts` — a catch-up plus a live socket
 * for as long as this page stays open. Visual design:
 * design_handoff_web_auth/Items.dc.html (2026-09) — deleting the list itself
 * moved out of this page's header (now a settings gear, matching Lists) and
 * lives only in the list-row menu on the Lists overview, same as the mockup.
 */
import type { Task } from "@dielys/protocol";
import { type CSSProperties, useState } from "react";
import { useSession } from "../auth/SessionContext.js";
import { getAccent } from "../domain/accentStore.js";
import { accentColor, hashedAccent } from "../domain/accents.js";
import { getNewItemsOnTop } from "../domain/uiPrefs.js";
import { navigate } from "../router.js";
import { useTaskBoard } from "../sync/useTaskBoard.js";
import {
  BackChevronIcon,
  CheckIcon,
  ChevronDownIcon,
  DotsVerticalIcon,
  GearIcon,
  StarIcon,
} from "../ui/icons.js";
import { dropNeighbors } from "../ui/reorder.js";

const DONE_EXPANDED_KEY_PREFIX = "dielys.doneExpanded.";

function readDoneExpanded(listId: string): boolean {
  try {
    return localStorage.getItem(DONE_EXPANDED_KEY_PREFIX + listId) === "1";
  } catch {
    return false;
  }
}

function writeDoneExpanded(listId: string, expanded: boolean): void {
  try {
    localStorage.setItem(DONE_EXPANDED_KEY_PREFIX + listId, expanded ? "1" : "0");
  } catch {
    // Best-effort — the section just starts collapsed next time.
  }
}

export function ListPage({ listId }: { listId: string }) {
  const session = useSession();
  const board = useTaskBoard(listId, session.deviceId);
  const [newTitle, setNewTitle] = useState("");
  const [adding, setAdding] = useState(false);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [editingTaskId, setEditingTaskId] = useState<string | null>(null);
  const [taskDraft, setTaskDraft] = useState("");
  const [menuFor, setMenuFor] = useState<string | null>(null);
  const [doneExpanded, setDoneExpanded] = useState(() => readDoneExpanded(listId));
  const [dragTaskId, setDragTaskId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const accent = accentColor(getAccent(listId) ?? hashedAccent(listId));

  async function handleAdd(): Promise<void> {
    const title = newTitle.trim();
    if (title === "" || adding) return;
    setAdding(true);
    try {
      await board.add(title, getNewItemsOnTop());
      setNewTitle("");
    } catch {
      setError("Could not add the task. Check your connection and try again.");
    } finally {
      setAdding(false);
    }
  }

  function toggleDoneExpanded(): void {
    const next = !doneExpanded;
    setDoneExpanded(next);
    writeDoneExpanded(listId, next);
  }

  function handleTaskDrop(section: Task[], targetIndex: number): void {
    if (dragTaskId === null) return;
    const ids = section.map((t) => t.id);
    const fromIndex = ids.indexOf(dragTaskId);
    if (fromIndex !== -1 && fromIndex !== targetIndex) {
      const { id, afterId, beforeId } = dropNeighbors(ids, fromIndex, targetIndex);
      void board.move(id, afterId, beforeId);
    }
    setDragTaskId(null);
  }

  return (
    <div className="page" style={{ "--list-accent": accent } as CSSProperties}>
      <header className="page-header">
        <button
          className="icon-button"
          type="button"
          aria-label="Back to lists"
          onClick={() => navigate("/")}
        >
          <BackChevronIcon />
        </button>
        <span className="accent-dot" style={{ background: accent, marginLeft: "6px" }} />
        {editingTitle ? (
          <input
            className="text-input inline-edit"
            value={titleDraft}
            onChange={(e) => setTitleDraft(e.target.value)}
            onBlur={() => {
              void board.renameList(titleDraft);
              setEditingTitle(false);
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") e.currentTarget.blur();
              if (e.key === "Escape") setEditingTitle(false);
            }}
          />
        ) : (
          <button
            type="button"
            className="page-title-button"
            onClick={() => {
              setTitleDraft(board.list?.title ?? "");
              setEditingTitle(true);
            }}
          >
            <h1>{board.list?.title ?? (board.loaded ? "Untitled list" : "…")}</h1>
          </button>
        )}
        <button
          type="button"
          className="icon-button"
          aria-label="Settings"
          onClick={() => navigate("/settings")}
        >
          <GearIcon />
        </button>
      </header>

      {board.status !== "connected" && (
        <p className="connection-banner">
          {board.status === "connecting" ? "Connecting…" : "Reconnecting…"}
        </p>
      )}

      {error !== null && (
        <p className="error-text" role="alert">
          {error}
        </p>
      )}

      {!board.loaded && <p>Loading…</p>}

      {board.loaded && board.active.length === 0 && board.done.length === 0 && (
        <div className="empty-state">
          <div className="empty-state-body">Nothing on this list yet.</div>
        </div>
      )}

      {board.active.length > 0 && (
        <ul className="row-list">
          {board.active.map((task, index) => (
            <li
              key={task.id}
              className="task-row"
              draggable
              onDragStart={() => setDragTaskId(task.id)}
              onDragOver={(e) => e.preventDefault()}
              onDrop={() => handleTaskDrop(board.active, index)}
            >
              <span className="drag-handle" aria-hidden="true">
                ⠿
              </span>
              <button
                type="button"
                className="icon-button checkbox"
                aria-label={`Mark "${task.title}" done`}
                onClick={() => void board.setDone(task, true)}
              >
                <span className="checkbox-box" />
              </button>

              <div className="row-title-wrap">
                {editingTaskId === task.id ? (
                  <input
                    className="text-input inline-edit"
                    value={taskDraft}
                    onChange={(e) => setTaskDraft(e.target.value)}
                    onBlur={() => {
                      void board.rename(task, taskDraft);
                      setEditingTaskId(null);
                    }}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") e.currentTarget.blur();
                      if (e.key === "Escape") setEditingTaskId(null);
                    }}
                  />
                ) : (
                  <button
                    type="button"
                    className="row-title"
                    onClick={() => {
                      setTaskDraft(task.title);
                      setEditingTaskId(task.id);
                    }}
                  >
                    {task.title}
                  </button>
                )}
              </div>

              <button
                type="button"
                className={task.starred ? "icon-button starred" : "icon-button"}
                aria-label={task.starred ? "Unstar" : "Star"}
                style={task.starred ? undefined : { opacity: 0.5 }}
                onClick={() => void board.setStarred(task, !task.starred)}
              >
                <StarIcon filled={task.starred} />
              </button>

              <button
                type="button"
                className="icon-button"
                aria-label="Task options"
                onClick={() => setMenuFor(menuFor === task.id ? null : task.id)}
              >
                <DotsVerticalIcon />
              </button>

              {menuFor === task.id && (
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
                        setTaskDraft(task.title);
                        setEditingTaskId(task.id);
                        setMenuFor(null);
                      }}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      className="menu-danger"
                      onClick={() => {
                        setMenuFor(null);
                        void board.remove(task);
                      }}
                    >
                      Delete
                    </button>
                  </div>
                </>
              )}
            </li>
          ))}
        </ul>
      )}

      {board.done.length > 0 && (
        <div className="done-section">
          <button type="button" className="done-toggle" onClick={toggleDoneExpanded}>
            <span>DONE ({board.done.length})</span>
            <ChevronDownIcon rotated={doneExpanded} />
          </button>
          {doneExpanded && (
            <ul className="row-list" style={{ gap: 0 }}>
              {board.done.map((task) => (
                <li key={task.id} className="task-row done">
                  <button
                    type="button"
                    className="icon-button checkbox"
                    aria-label={`Mark "${task.title}" not done`}
                    onClick={() => void board.setDone(task, false)}
                  >
                    <span className="checkbox-box checked">
                      <CheckIcon />
                    </span>
                  </button>
                  <span className="done-title">{task.title}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      <form
        className="add-bar"
        onSubmit={(e) => {
          e.preventDefault();
          void handleAdd();
        }}
      >
        <input
          className="text-input"
          placeholder="Add an item"
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
        />
        <button
          className={newTitle.trim() === "" ? "add-bar-submit" : "add-bar-submit ready"}
          type="submit"
          aria-label="Add item"
          disabled={adding || newTitle.trim() === ""}
        >
          D
        </button>
      </form>
    </div>
  );
}
