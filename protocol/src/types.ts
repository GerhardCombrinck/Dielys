/**
 * Wire types. Every message that crosses the client/server boundary is
 * declared here first — see CODE_STANDARD.md F1. Nothing here imports from
 * server/ or android/.
 */

import type { AuthErrorCode } from "./auth.js";

/**
 * Bounds enforced at the boundary (F3). They exist so a malformed or hostile
 * client cannot push an unbounded string into every replica of the list; the
 * numbers are deliberately generous, because a rejected shopping-list item is
 * a worse bug than a long one.
 */
export const MAX_TITLE_LENGTH = 1000;
export const MAX_POSITION_LENGTH = 256;
export const MAX_URL_LENGTH = 2048;
export const MAX_ID_LENGTH = 64;

/** How many changes one catch-up page carries. See `CatchUpResponse.truncated`. */
export const CATCH_UP_PAGE_SIZE = 500;

export type EntityType = "task" | "list";

export interface Task {
  id: string; // client-generated UUIDv7, F5.1
  listId: string;
  title: string;
  done: boolean;
  starred: boolean;
  position: string; // fractional index, F5.5
  deletedAt: string | null; // tombstone, F5.3 — ISO 8601, null when not deleted
  updatedAt: string; // server timestamp, ISO 8601 — F5.9
}

export interface TaskList {
  id: string; // client-generated UUIDv7, F5.1
  title: string;
  backgroundPhotoUrl: string | null;
  deletedAt: string | null;
  updatedAt: string;
}

/**
 * One row in a list's changelog. Assigned a seq inside the ListRoom DO — D3.
 *
 * `entityType` discriminates the union rather than leaving the client to sniff
 * the shape of `entity`. Structural sniffing would silently start
 * misclassifying the day either entity grows a field the other one has.
 */
interface ChangeEnvelopeBase {
  seq: number;
  listId: string;
  idempotencyKey: string; // F5.2 — redelivery of the same key is a no-op
  deviceId: string;
  serverTimestamp: string; // ISO 8601 — the only clock that matters, F5.9
}

export interface TaskChange extends ChangeEnvelopeBase {
  entityType: "task";
  entity: Task;
}

export interface ListChange extends ChangeEnvelopeBase {
  entityType: "list";
  entity: TaskList;
}

export type ChangeEnvelope = TaskChange | ListChange;

/**
 * A mutation names only the fields it intends to change. This is what makes
 * per-field last-write-wins (F5.4) possible: if one device renames a task
 * while the other ticks it, the two patches touch disjoint fields and both
 * survive. Sending whole entities would make every edit clobber every field.
 *
 * An absent key means "leave alone". `deletedAt: null` means "not deleted" —
 * but a tombstone is sticky, so it never resurrects a deleted row (F5.3).
 */
export interface TaskPatch {
  title?: string;
  done?: boolean;
  starred?: boolean;
  position?: string;
  deletedAt?: string | null;
}

export interface ListPatch {
  title?: string;
  backgroundPhotoUrl?: string | null;
  deletedAt?: string | null;
}

interface MutationBase {
  type: "mutate";
  protocolVersion: number;
  listId: string;
  idempotencyKey: string; // F5.2
  deviceId: string;
  entityId: string; // client-generated UUIDv7, F5.1 — the server never mints one
}

export interface TaskMutation extends MutationBase {
  entityType: "task";
  patch: TaskPatch;
}

export interface ListMutation extends MutationBase {
  entityType: "list";
  patch: ListPatch;
}

export type Mutation = TaskMutation | ListMutation;

export interface ClientHello {
  type: "hello";
  protocolVersion: number;
  listId: string;
  cursor: number; // last seq this client has applied for this list
  deviceId: string;
}

export interface ServerHelloOk {
  type: "hello-ok";
  protocolVersion: number; // the version the server is speaking back
  listId: string;
  maxSeq: number; // lets the client see it is behind without waiting for a push
}

export type ServerHelloError = {
  type: "hello-error";
  code: "unsupported-protocol-version" | "unauthorized" | "malformed";
};

export interface CatchUpRequest {
  type: "catch-up";
  listId: string;
  since: number;
}

export interface CatchUpResponse {
  type: "catch-up-response";
  listId: string;
  since: number;
  changes: ChangeEnvelope[];
  maxSeq: number;
  /** More changes exist past this page — pull again from the last seq received. */
  truncated: boolean;
}

/** A change pushed over the socket as it happens. The latency path, not the correctness path. */
export interface ChangeMessage {
  type: "change";
  change: ChangeEnvelope;
}

/**
 * The result of a mutation. On a redelivered idempotency key this carries the
 * *original* change row, not a new one, with `duplicate: true` (F5.2, H3.10).
 */
export interface MutationAck {
  type: "ack";
  idempotencyKey: string;
  change: ChangeEnvelope;
  duplicate: boolean;
}

export type ErrorCode =
  | "malformed"
  | "unsupported-protocol-version"
  | "unauthorized"
  | "list-mismatch"
  | "incomplete-create"
  | "internal"
  | AuthErrorCode;

/**
 * A rejected message. Never closes the socket and never crashes the DO (F3),
 * and never carries a stack trace or an internal message (D4) — the client
 * decides the wording from `code`.
 */
export interface ServerError {
  type: "error";
  code: ErrorCode;
  /** The mutation this concerns, or null for errors not tied to one. */
  idempotencyKey: string | null;
}

export type ClientMessage = ClientHello | Mutation | CatchUpRequest;

export type ServerMessage =
  | ServerHelloOk
  | ServerHelloError
  | ChangeMessage
  | MutationAck
  | CatchUpResponse
  | ServerError;
