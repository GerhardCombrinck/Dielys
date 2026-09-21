/**
 * Deleting an account from a page nobody is signed in on (ADR 0007, Google
 * Play's account-deletion requirement): `/account/deletion/request` mails a
 * confirmation link when the address has one, and `/account/deletion/confirm`
 * spends the token that link carries. Both public — there is no session here.
 */
import type {
  ConfirmAccountDeletionRequest,
  RequestAccountDeletionRequest,
  RequestAccountDeletionResponse,
} from "@dielys/protocol";
import { apiFetch } from "./client.js";

export function requestAccountDeletion(email: string): Promise<RequestAccountDeletionResponse> {
  const body: RequestAccountDeletionRequest = { email };
  return apiFetch("/account/deletion/request", { body, auth: false });
}

export function confirmAccountDeletion(token: string): Promise<void> {
  const body: ConfirmAccountDeletionRequest = { token };
  return apiFetch("/account/deletion/confirm", { body, auth: false });
}
