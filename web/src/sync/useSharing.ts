/**
 * Sharing (#60/L3), for the lists screen: who a list is shared with, taking
 * somebody off it, and inviting someone new. `android/.../MembersViewModel.kt`
 * and `ListsViewModel`'s invite half, combined into one hook since the web
 * client has no dependency injection to split them across.
 *
 * Both pieces are read live from the server rather than cached, same
 * reasoning as Android's: this is only ever on screen for a moment, and a
 * stale answer about who can see a list is worse than a beat of "checking…".
 */
import { useCallback, useState } from "react";
import { ApiError } from "../api/client.js";
import { removeMember as apiRemoveMember, createInvite, listMembers } from "../api/sharing.js";

export interface Member {
  userId: string;
  email: string;
  isOwner: boolean;
}

export type MembersState =
  | { status: "loading"; listId: string }
  | {
      status: "loaded";
      listId: string;
      members: Member[];
      meUserId: string;
      iAmOwner: boolean;
      /** Set while a remove or leave is in flight, so the row it names can
       *  say so instead of the whole sheet going blank. */
      working: string | null;
    }
  | { status: "failed"; listId: string; message: string };

export type InviteState =
  | { status: "entering"; listId: string; listTitle: string }
  | { status: "working"; listTitle: string }
  | { status: "sent"; listTitle: string; email: string }
  | { status: "failed"; message: string };

function describeMembersError(err: unknown): string {
  return err instanceof ApiError && err.code === "forbidden"
    ? "You are no longer on this list."
    : "Could not load who this list is shared with. Check your connection and try again.";
}

function describeInviteError(err: unknown): string {
  if (err instanceof ApiError && err.code === "forbidden") {
    return "Only the person who made this list can share it.";
  }
  return "Could not send the invite. Check your connection and try again.";
}

export function useSharing(myUserId: string) {
  const [members, setMembers] = useState<MembersState | null>(null);
  const [invite, setInvite] = useState<InviteState | null>(null);

  const openMembers = useCallback(
    async (listId: string) => {
      setMembers({ status: "loading", listId });
      try {
        const raw = await listMembers(listId);
        setMembers({
          status: "loaded",
          listId,
          members: raw.map((m) => ({
            userId: m.userId,
            email: m.email,
            isOwner: m.role === "owner",
          })),
          meUserId: myUserId,
          iAmOwner: raw.some((m) => m.userId === myUserId && m.role === "owner"),
          working: null,
        });
      } catch (err) {
        setMembers({ status: "failed", listId, message: describeMembersError(err) });
      }
    },
    [myUserId],
  );

  const dismissMembers = useCallback(() => setMembers(null), []);

  /** The owner removing someone else, or anybody removing themselves — from
   *  the open sheet, which reloads afterwards rather than editing the row
   *  locally: whether the sheet should still be open at all depends on who
   *  just went. */
  const removeMember = useCallback(
    async (listId: string, userId: string) => {
      setMembers((prev) =>
        prev?.status === "loaded" && prev.listId === listId ? { ...prev, working: userId } : prev,
      );
      const leaving = userId === myUserId;
      try {
        await apiRemoveMember(listId, userId);
        if (leaving) setMembers(null);
        else await openMembers(listId);
      } catch (err) {
        // A refusal here means the membership already changed underneath —
        // nothing the sheet offers is something the server would otherwise
        // refuse — so show what's true now rather than an error.
        if (err instanceof ApiError && err.code === "forbidden") {
          await openMembers(listId);
          return;
        }
        setMembers({ status: "failed", listId, message: describeMembersError(err) });
      }
    },
    [myUserId, openMembers],
  );

  /** Leaving a list from its own row menu (ADR 0006), the sheet never having
   *  opened — a plain wire call, since there is no sheet state to reconcile. */
  const leave = useCallback((listId: string) => apiRemoveMember(listId, myUserId), [myUserId]);

  const openInvite = useCallback((listId: string, listTitle: string) => {
    setInvite({ status: "entering", listId, listTitle });
  }, []);

  const sendInvite = useCallback(async (listId: string, listTitle: string, email: string) => {
    const trimmed = email.trim();
    if (trimmed === "") return;
    setInvite({ status: "working", listTitle });
    try {
      await createInvite(listId, trimmed, listTitle);
      setInvite({ status: "sent", listTitle, email: trimmed });
    } catch (err) {
      setInvite({ status: "failed", message: describeInviteError(err) });
    }
  }, []);

  const dismissInvite = useCallback(() => setInvite(null), []);

  return {
    members,
    openMembers,
    dismissMembers,
    removeMember,
    leave,
    invite,
    openInvite,
    sendInvite,
    dismissInvite,
  };
}
