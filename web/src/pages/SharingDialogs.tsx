/**
 * The two dialogs sharing needs on the lists screen: who a list is shared
 * with, and inviting someone new to it. `android/.../ui/lists/
 * {MembersDialog,ShareDialogs}.kt`'s web counterpart — split out of
 * HomePage.tsx for the same reason Android split these out of
 * ListsScreen.kt: a self-contained conversation, and the screen underneath
 * is already a screen.
 */
import { useState } from "react";
import { useI18n } from "../i18n/I18nContext.js";
import { navigate } from "../router.js";
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
  const { t } = useI18n();

  if (confirming !== null) {
    const leaving = state.status === "loaded" && confirming.userId === state.meUserId;
    return (
      <ConfirmDialog
        title={
          leaving ? t("sharing.leaveTitle") : t("sharing.removeTitle", { name: confirming.email })
        }
        body={leaving ? t("common.leaveBody") : t("sharing.removeBody")}
        confirm={leaving ? t("common.leave") : t("common.remove")}
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
        <h2>{t("sharing.membersTitle")}</h2>
        {state.status === "loading" && <p>{t("sharing.checking")}</p>}
        {state.status === "failed" && (
          <p className="error-text" role="alert">
            {state.message}
          </p>
        )}
        {state.status === "loaded" && (
          <ul className="row-list">
            {state.members.length <= 1 && <p>{t("sharing.noMembersYet")}</p>}
            {state.members.map((member) => {
              const isMe = member.userId === state.meUserId;
              const note = [
                member.isOwner ? t("sharing.owner") : null,
                isMe ? t("sharing.you") : null,
              ]
                .filter((part): part is string => part !== null)
                .join(" · ");
              const canAct = state.working === null;
              return (
                <li key={member.userId} className="member-row">
                  <span className="member-email">{member.email}</span>
                  {note !== "" && <span className="member-note">{note}</span>}
                  {state.working === member.userId ? (
                    <span className="member-note">{t("sharing.working")}</span>
                  ) : canAct && isMe && !member.isOwner ? (
                    <button type="button" onClick={() => setConfirming(member)}>
                      {t("common.leave")}
                    </button>
                  ) : canAct && !isMe && state.iAmOwner ? (
                    <button type="button" onClick={() => setConfirming(member)}>
                      {t("common.remove")}
                    </button>
                  ) : null}
                </li>
              );
            })}
          </ul>
        )}
        <div className="dialog-actions">
          <button className="pill-button" type="button" onClick={onDismiss}>
            {t("common.close")}
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
  const { t } = useI18n();

  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>
          {state.status === "failed" ? t("sharing.shareFailedTitle") : t("sharing.shareListTitle")}
        </h2>
        {state.status === "entering" && (
          <>
            <p>{t("sharing.whoFor", { title: state.listTitle })}</p>
            <p className="dialog-note">{t("sharing.note")}</p>
            <input
              className="text-input"
              type="email"
              placeholder={t("sharing.emailAddressPlaceholder")}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <button
              type="button"
              className="text-button link dialog-help-link"
              onClick={() => {
                onDismiss();
                navigate("/help#sharing");
              }}
            >
              {t("sharing.howItWorks")}
            </button>
          </>
        )}
        {state.status === "working" && (
          <p>{t("sharing.sendingInvite", { title: state.listTitle })}</p>
        )}
        {state.status === "sent" && (
          <p>{t("sharing.invitedTo", { email: state.email, title: state.listTitle })}</p>
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
                {t("common.cancel")}
              </button>
              <button
                className="pill-button"
                type="button"
                disabled={email.trim() === ""}
                onClick={() => onSend(state.listId, state.listTitle, email)}
              >
                {t("sharing.sendInvite")}
              </button>
            </>
          )}
          {state.status !== "entering" && state.status !== "working" && (
            <button className="pill-button" type="button" onClick={onDismiss}>
              {state.status === "sent" ? t("sharing.done") : t("common.close")}
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
  const { t } = useI18n();
  return (
    <div className="dialog-overlay">
      <div className="dialog-box">
        <h2>{title}</h2>
        <p>{body}</p>
        <div className="dialog-actions">
          <button type="button" onClick={onDismiss}>
            {t("common.cancel")}
          </button>
          <button className="pill-button danger" type="button" onClick={onConfirm}>
            {confirm}
          </button>
        </div>
      </div>
    </div>
  );
}
