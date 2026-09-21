/**
 * List/task calls, used by `data/syncEngine.ts` — never by a page directly
 * (web/AGENTS.md). Mutations are the outbox draining over plain HTTP
 * (ListRoom.ts's "primary write path"); the socket (`socket.ts`) only carries
 * the "change" pushes a live view wants while someone else is editing.
 */
import type {
  CatchUpResponse,
  Membership,
  MembershipsResponse,
  Mutation,
  MutationAck,
  SetListPositionRequest,
  SetListPositionResponse,
} from "@dielys/protocol";
import { apiFetch } from "./client.js";

export function getMemberships(): Promise<Membership[]> {
  return apiFetch<MembershipsResponse>("/auth/memberships").then((r) => r.memberships);
}

export function setListPosition(
  listId: string,
  position: string,
): Promise<SetListPositionResponse> {
  const body: SetListPositionRequest = { listId, position };
  return apiFetch("/auth/memberships/position", { body });
}

/** Claims a client-generated list id as owner (F5.1) — the first step of
 * creating a list, before any mutation naming it can be accepted. */
export function claimList(listId: string): Promise<{ listId: string; alreadyMember: boolean }> {
  return apiFetch(`/lists/${encodeURIComponent(listId)}`, { method: "POST" });
}

export function catchUp(listId: string, since: number): Promise<CatchUpResponse> {
  return apiFetch(`/lists/${encodeURIComponent(listId)}/changes?since=${since}`);
}

/** A rejected mutation (bad patch, wrong role) is a non-2xx response, so it
 * surfaces the same way any other API failure does — as a thrown `ApiError`
 * (`client.ts`), not a returned error value. */
export function mutate(listId: string, mutation: Mutation): Promise<MutationAck> {
  return apiFetch(`/lists/${encodeURIComponent(listId)}/mutate`, { body: mutation });
}
