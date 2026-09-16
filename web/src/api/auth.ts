/**
 * Auth calls. Magic-link + code only (ADR 0005/0008) — there is no password
 * field anywhere in this client, same as Android.
 */
import type {
  MagicLinkStatusResponse,
  RequestMagicLinkRequest,
  RequestMagicLinkResponse,
  TokenPair,
  VerifyMagicCodeRequest,
  VerifyMagicLinkRequest,
  WsTicketResponse,
} from "@dielys/protocol";
import { apiFetch } from "./client.js";

export function requestMagicLink(email: string): Promise<RequestMagicLinkResponse> {
  const body: RequestMagicLinkRequest = { email };
  return apiFetch("/auth/magic/request", { body, auth: false });
}

export function magicLinkStatus(requestId: string): Promise<MagicLinkStatusResponse> {
  return apiFetch(`/auth/magic/status?requestId=${encodeURIComponent(requestId)}`, {
    auth: false,
  });
}

export function verifyMagicCode(email: string, code: string, deviceId: string): Promise<TokenPair> {
  const body: VerifyMagicCodeRequest = { email, code, deviceId };
  return apiFetch("/auth/magic/verify-code", { body, auth: false });
}

export function verifyMagicLink(token: string, deviceId: string): Promise<TokenPair> {
  const body: VerifyMagicLinkRequest = { token, deviceId };
  return apiFetch("/auth/magic/verify", { body, auth: false });
}

/** ADR 0009 — exchanges the caller's access token for a one-time WebSocket ticket. */
export function mintWsTicket(): Promise<WsTicketResponse> {
  return apiFetch("/auth/ws-ticket", { method: "POST" });
}

/** `DELETE /account` (ADR 0007) — erases the signed-in caller's own account.
 * The access token names whose; there is nothing else to send. */
export function deleteAccount(): Promise<void> {
  return apiFetch("/account", { method: "DELETE" });
}
