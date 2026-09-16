/**
 * The two dialogs sharing needs on the lists screen: who a list is shared
 * with, and inviting someone new to it. `android/.../ui/lists/
 * {MembersDialog,ShareDialogs}.kt`'s web counterpart — split out of
 * HomePage.tsx for the same reason Android split these out of
 * ListsScreen.kt: a self-contained conversation, and the screen underneath
 * is already a screen.
 */
import { useState } from "react";
import type { InviteState, Member, MembersState } from "../sync/useSharing.js";

export function MembersDialog({
  state,
  onRemove,
  onDismiss,
}: {
  state: MembersState;
  onRemove: (listId: string, userId: string) => void;
  onDismiss: () => void;
}) {
  // Which row is being confirmed, if any. Held here rather than passed in:
  // nothing outside this dialog needs to know a question was asked, and
  // dismissing it is supposed to forget it.
  const [confirming, setConfirming] = useState<Member | null>(null);

  if (confirming !== null) {
    const leaving = state.status === "loaded" && confirming.userId === state.meUserId;
    return (
      <ConfirmDialog
        title={leaving ? "Leave this list?" : `Remove ${confirming.email}?`}
        body={
          leaving
            ? "You will need a new invite to see it again."
            : "They will need a new invite to see this list again."
        }
        confirm={leaving ? "Leave" : "Remove"}
        onConfirm={() => {
          onRemove(state.listId, confirming.userId);
          setConfirming(null);
        }}
        onDismiss={() => setConfirming(null)}
      />
    );
  }

  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>Shared with</h2>
        {state.status === "loading" && <p>Checking…</p>}
        {state.status === "failed" && (
          <p className="error-text" role="alert">
            {state.message}
          </p>
        )}
        {state.status === "loaded" && (
          <ul className="row-list">
            {state.members.length <= 1 && <p>Nobody has accepted an invite to this list yet.</p>}
            {state.members.map((member) => {
              const isMe = member.userId === state.meUserId;
              const note = [member.isOwner ? "owner" : null, isMe ? "you" : null]
                .filter((part): part is string => part !== null)
                .join(" · ");
              const canAct = state.working === null;
              return (
                <li key={member.userId} className="member-row">
                  <span className="member-email">{member.email}</span>
                  {note !== "" && <span className="member-note">{note}</span>}
                  {state.working === member.userId ? (
                    <span className="member-note">Working…</span>
                  ) : canAct && isMe && !member.isOwner ? (
                    <button type="button" onClick={() => setConfirming(member)}>
                      Leave
                    </button>
                  ) : canAct && !isMe && state.iAmOwner ? (
                    <button type="button" onClick={() => setConfirming(member)}>
                      Remove
                    </button>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
        <div className="dialog-actions">
          <button className="pill-button" type="button" onClick={onDismiss}>
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

export function InviteDialog({
  state,
  onSend,
  onDismiss,
}: {
  state: InviteState;
  onSend: (listId: string, listTitle: string, email: string) => void;
  onDismiss: () => void;
}) {
  const [email, setEmail] = useState("");

  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>{state.status === "failed" ? "Couldn't share" : "Share list"}</h2>
        {state.status === "entering" && (
          <>
            <p>Who is "{state.listTitle}" for?</p>
            <input
              className="text-input"
              type="email"
              placeholder="Email address"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </>
        )}
        {state.status === "working" && <p>Sending the invite to "{state.listTitle}"…</p>}
        {state.status === "sent" && (
          <p>
            Invited {state.email} to "{state.listTitle}".
          </p>
        )}
        {state.status === "failed" && (
          <p className="error-text" role="alert">
            {state.message}
          </p>
        )}

        <div className="dialog-actions">
          {state.status === "entering" && (
            <>
              <button type="button" onClick={onDismiss}>
                Cancel
              </button>
              <button
                className="pill-button"
                type="button"
                disabled={email.trim() === ""}
                onClick={() => onSend(state.listId, state.listTitle, email)}
              >
                Send invite
              </button>
            </>
          )}
          {state.status !== "entering" && state.status !== "working" && (
            <button className="pill-button" type="button" onClick={onDismiss}>
              {state.status === "sent" ? "Done" : "Close"}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

function ConfirmDialog({
  title,
  body,
  confirm,
  onConfirm,
  onDismiss,
}: {
  title: string;
  body: string;
  confirm: string;
  onConfirm: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>{title}</h2>
        <p>{body}</p>
        <div className="dialog-actions">
          <button type="button" onClick={onDismiss}>
            Cancel
          </button>
          <button className="pill-button menu-danger-fill" type="button" onClick={onConfirm}>
            {confirm}
          </button>
        </div>
      </div>
    </div>
  );
}
