/**
 * One list's tasks (`android/.../ui/tasks/TaskListScreen.kt`'s web
 * counterpart). Fed by `sync/useTaskBoard.ts` — a catch-up plus a live socket
 * for as long as this page stays open.
 */
import type { Task } from "@dielys/protocol";
import { useState } from "react";
import { useSession } from "../auth/SessionContext.js";
import { navigate } from "../router.js";
import { useTaskBoard } from "../sync/useTaskBoard.js";
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
  const [doneExpanded, setDoneExpanded] = useState(() => readDoneExpanded(listId));
  const [dragTaskId, setDragTaskId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function handleAdd(): Promise<void> {
    const title = newTitle.trim();
    if (title === "" || adding) return;
    setAdding(true);
    try {
      await board.add(title, true);
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
    <div className="page">
      <header className="page-header">
        <button
          className="icon-button"
          type="button"
          aria-label="Back to lists"
          onClick={() => navigate("/")}
        >
          ←
        </button>
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
          aria-label="Delete list"
          onClick={() => {
            if (window.confirm("Delete this list? This removes it for everyone on it.")) {
              void board.deleteList().then(() => navigate("/"));
            }
          }}
        >
          🗑
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

      <form
        className="new-row"
        onSubmit={(e) => {
          e.preventDefault();
          void handleAdd();
        }}
      >
        <input
          className="text-input"
          placeholder="New task"
          value={newTitle}
          onChange={(e) => setNewTitle(e.target.value)}
        />
        <button className="pill-button" type="submit" disabled={adding || newTitle.trim() === ""}>
          Add
        </button>
      </form>

      {!board.loaded && <p>Loading…</p>}

      {board.loaded && board.active.length === 0 && board.done.length === 0 && (
        <p>Nothing on this list yet.</p>
      )}

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
            <input
              type="checkbox"
              checked={task.done}
              onChange={(e) => void board.setDone(task, e.target.checked)}
              aria-label={`Mark "${task.title}" done`}
            />

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

            <button
              type="button"
              className={task.starred ? "icon-button starred" : "icon-button"}
              aria-label={task.starred ? "Unstar" : "Star"}
              onClick={() => void board.setStarred(task, !task.starred)}
            >
              {task.starred ? "★" : "☆"}
            </button>
            <button
              type="button"
              className="icon-button"
              aria-label={`Delete "${task.title}"`}
              onClick={() => void board.remove(task)}
            >
              ✕
            </button>
          </li>
        ))}
      </ul>

      {board.done.length > 0 && (
        <div className="done-section">
          <button type="button" className="done-toggle" onClick={toggleDoneExpanded}>
            {doneExpanded ? "▾" : "▸"} Done ({board.done.length})
          </button>
          {doneExpanded && (
            <ul className="row-list">
              {board.done.map((task) => (
                <li key={task.id} className="task-row done">
                  <input
                    type="checkbox"
                    checked={task.done}
                    onChange={(e) => void board.setDone(task, e.target.checked)}
                    aria-label={`Mark "${task.title}" not done`}
                  />
                  <span className="row-title done-title">{task.title}</span>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`Delete "${task.title}"`}
                    onClick={() => void board.remove(task)}
                  >
                    ✕
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
