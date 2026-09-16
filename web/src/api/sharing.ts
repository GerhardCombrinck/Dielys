/**
 * Sharing calls (#60/L3): who can see a list, and getting somebody new onto
 * it. `POST /lists/{id}/invite`, `GET .../members`, `DELETE .../members/{id}`
 * and `POST /invites/accept` — all authenticated calls made directly rather
 * than through `mutate`, since none of them is a list mutation (an invite
 * creates no change to order, and accepting one is idempotent).
 */
import type {
  AcceptInviteRequest,
  AcceptInviteResponse,
  CreateInviteRequest,
  CreateInviteResponse,
  ListMember,
  ListMembersResponse,
  RemoveMemberResponse,
} from "@dielys/protocol";
import { apiFetch } from "./client.js";

export function createInvite(
  listId: string,
  email: string,
  listTitle: string,
): Promise<CreateInviteResponse> {
  const body: CreateInviteRequest = { listId, email, listTitle };
  return apiFetch(`/lists/${encodeURIComponent(listId)}/invite`, { body });
}

export function listMembers(listId: string): Promise<ListMember[]> {
  return apiFetch<ListMembersResponse>(`/lists/${encodeURIComponent(listId)}/members`).then(
    (r) => r.members,
  );
}

export function removeMember(listId: string, userId: string): Promise<RemoveMemberResponse> {
  return apiFetch(`/lists/${encodeURIComponent(listId)}/members/${encodeURIComponent(userId)}`, {
    method: "DELETE",
  });
}

/**
 * A 401 here can mean the invite token itself is bad rather than the
 * session — `apiFetch` cannot tell the two apart, so it spends one refresh
 * on this the same as any other 401 before the caller sees the real reason
 * (matches `android/.../SharingRepository.kt`'s `join`, same caveat).
 */
export function acceptInvite(inviteToken: string): Promise<AcceptInviteResponse> {
  const body: AcceptInviteRequest = { inviteToken };
  return apiFetch("/invites/accept", { body });
}
