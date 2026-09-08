/**
 * Wire types. Every message that crosses the client/server boundary is
 * declared here first — see CODE_STANDARD.md F1. Nothing here imports from
 * server/ or android/.
 */

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

/** One row in a list's changelog. Assigned a seq inside the ListRoom DO — D3. */
export interface ChangeEnvelope<T> {
  seq: number;
  listId: string;
  idempotencyKey: string; // F5.2 — redelivery of the same key is a no-op
  deviceId: string;
  serverTimestamp: string; // ISO 8601 — the only clock that matters, F5.9
  entity: T;
}

export interface ClientHello {
  type: "hello";
  protocolVersion: number;
  listId: string;
  cursor: number; // last seq this client has applied for this list
  deviceId: string;
}

export type ServerHelloError = {
  type: "hello-error";
  code: "unsupported-protocol-version" | "unauthorized";
};

export interface CatchUpRequest {
  type: "catch-up";
  listId: string;
  since: number;
}
