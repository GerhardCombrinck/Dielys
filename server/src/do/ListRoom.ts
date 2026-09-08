import { DurableObject } from "cloudflare:workers";
import {
  CATCH_UP_PAGE_SIZE,
  type CatchUpResponse,
  type ChangeEnvelope,
  type ErrorCode,
  type Mutation,
  type MutationAck,
  PROTOCOL_VERSION,
  type ServerMessage,
  SUPPORTED_PROTOCOL_VERSIONS,
} from "@dielys/protocol";
import { applyListPatch, applyTaskPatch, type FieldMeta } from "../domain/apply.js";
import { nextSeq } from "../domain/sequence.js";
import {
  parseJson,
  validateCatchUpRequest,
  validateClientHello,
  validateMutation,
} from "../domain/validate.js";
import { log } from "../lib/log.js";
import {
  insertChange,
  maxSeq,
  selectChangeByIdempotencyKey,
  selectChangesSince,
  toEnvelope,
} from "../storage/changes.js";
import {
  readRoomMeta,
  selectFieldMeta,
  selectList,
  selectTask,
  upsertFieldMeta,
  upsertList,
  upsertTask,
  writeRoomMeta,
} from "../storage/entities.js";
import { applyPendingMigrations, LIST_MIGRATIONS } from "../storage/migrations.js";

/** What a hibernating socket has to remember about its session. */
interface SocketSession {
  deviceId: string;
  protocolVersion: number;
}

/**
 * The Durable Object is the single-threaded owner of one list (D3). One DO
 * per list, id derived via idFromName(listId) — never a random id, never one
 * DO for multiple lists.
 *
 * Rules this class holds to (see CODE_STANDARD.md D3):
 * - Sequence numbers assigned here, in the same transaction as the write.
 * - All storage writes for one logical change in one transaction.
 * - No module-level mutable state — everything lives in `this` or storage.
 * - WebSocket Hibernation API (acceptWebSocket), not addEventListener.
 * - blockConcurrencyWhile wraps any init later requests depend on.
 *
 * This class deliberately performs **no authorization**. Membership is the
 * Worker's job, checked against UsersRoom before anything reaches here — see
 * docs/adr/0002-authentication.md. Adding a membership check in this class
 * would put the same rule in two places and let them disagree.
 */
export class ListRoom extends DurableObject {
  private readonly sql: SqlStorage;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;

    ctx.blockConcurrencyWhile(async () => {
      ctx.storage.transactionSync(() => {
        applyPendingMigrations(this.sql, LIST_MIGRATIONS);
      });
    });

    // Liveness without waking the DO: the runtime answers the client's ping
    // from the edge while this object stays hibernated. A client that stops
    // seeing pongs is how H3.11 gets noticed; the recovery is a reconnect and
    // a catch-up pull, not a resync.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  override async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const listId = url.searchParams.get("listId");

    if (listId === null) return jsonError("malformed", 400);
    if (!this.bindListId(listId)) return jsonError("list-mismatch", 409);

    try {
      switch (url.pathname) {
        case "/ws":
          return this.handleUpgrade(request, url);
        case "/changes":
          return this.handleCatchUp(url);
        case "/mutate":
          return await this.handleMutate(request);
        default:
          return jsonError("malformed", 404);
      }
    } catch (error) {
      // D4: an unhandled rejection inside a DO must not be left to reset the
      // object silently, and must not leak internals to the client.
      log("error", "listroom.fetch.unhandled", {
        listId,
        path: url.pathname,
        error: String(error),
      });
      return jsonError("internal", 500);
    }
  }

  // --- HTTP ---------------------------------------------------------------

  /**
   * The primary write path. The Android outbox drains from WorkManager with
   * no socket open, so this — not the WebSocket — is what has to be correct.
   */
  private async handleMutate(request: Request): Promise<Response> {
    if (request.method !== "POST") return jsonError("malformed", 405);

    const parsed = parseJson(await request.text());
    if (!parsed.ok) return jsonError("malformed", 400);

    const mutation = validateMutation(parsed.value);
    if (!mutation.ok) {
      log("warn", "listroom.mutate.invalid", { reason: mutation.reason });
      return jsonError("malformed", 400);
    }
    if (!SUPPORTED_PROTOCOL_VERSIONS.includes(mutation.value.protocolVersion)) {
      return jsonError("unsupported-protocol-version", 400, mutation.value.idempotencyKey);
    }

    const result = this.applyMutation(mutation.value);
    if (result.type === "error") {
      return new Response(JSON.stringify(result), {
        status: 422,
        headers: { "content-type": "application/json" },
      });
    }

    // Everyone else finds out over their socket; the caller already has the
    // result in its ack.
    this.broadcast({ type: "change", change: result.change }, null);
    return new Response(JSON.stringify(result), {
      headers: { "content-type": "application/json" },
    });
  }

  private handleCatchUp(url: URL): Response {
    const sinceRaw = url.searchParams.get("since") ?? "0";
    const since = Number(sinceRaw);
    if (!Number.isSafeInteger(since) || since < 0) return jsonError("malformed", 400);

    return new Response(JSON.stringify(this.catchUp(since)), {
      headers: { "content-type": "application/json" },
    });
  }

  private handleUpgrade(request: Request, url: URL): Response {
    if (request.headers.get("Upgrade") !== "websocket") return jsonError("malformed", 426);

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    // Hibernation, not addEventListener (D3): a socket held open by an event
    // listener bills idle duration and breaks the free-tier economics this
    // whole design assumes.
    this.ctx.acceptWebSocket(server);

    const deviceId = url.searchParams.get("deviceId");
    if (deviceId !== null) {
      const session: SocketSession = { deviceId, protocolVersion: PROTOCOL_VERSION };
      server.serializeAttachment(session);
    }

    return new Response(null, { status: 101, webSocket: client });
  }

  // --- WebSocket ----------------------------------------------------------

  override async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    try {
      if (typeof message !== "string") {
        send(ws, error("malformed", null));
        return;
      }

      const parsed = parseJson(message);
      if (!parsed.ok) {
        send(ws, error("malformed", null));
        return;
      }

      switch (messageType(parsed.value)) {
        case "hello":
          this.onHello(ws, parsed.value);
          break;
        case "mutate":
          this.onMutate(ws, parsed.value);
          break;
        case "catch-up":
          this.onCatchUp(ws, parsed.value);
          break;
        default:
          // F3: rejected with a stable code, never by dropping the socket.
          send(ws, error("malformed", null));
          break;
      }
    } catch (err) {
      log("error", "listroom.ws.unhandled", { error: String(err) });
      send(ws, error("internal", null));
    }
  }

  override async webSocketClose(ws: WebSocket, code: number, _reason: string): Promise<void> {
    // Nothing to persist — the cursor lives on the client (F5.8) and
    // hibernation handles the socket itself.
    log("debug", "listroom.ws.close", { code });
    ws.close(code === 1006 ? 1000 : code);
  }

  private onHello(ws: WebSocket, raw: unknown): void {
    const hello = validateClientHello(raw);
    if (!hello.ok) {
      send(ws, { type: "hello-error", code: "malformed" });
      ws.close(1008, "malformed");
      return;
    }

    // H3.12: an old client is told exactly why in a code it can act on,
    // rather than being left to guess from a dropped connection.
    if (!SUPPORTED_PROTOCOL_VERSIONS.includes(hello.value.protocolVersion)) {
      send(ws, { type: "hello-error", code: "unsupported-protocol-version" });
      ws.close(1008, "unsupported-protocol-version");
      return;
    }

    const listId = this.listId();
    if (listId !== null && hello.value.listId !== listId) {
      send(ws, { type: "hello-error", code: "unauthorized" });
      ws.close(1008, "list-mismatch");
      return;
    }

    const session: SocketSession = {
      deviceId: hello.value.deviceId,
      protocolVersion: hello.value.protocolVersion,
    };
    ws.serializeAttachment(session);

    send(ws, {
      type: "hello-ok",
      protocolVersion: PROTOCOL_VERSION,
      listId: hello.value.listId,
      maxSeq: maxSeq(this.sql),
    });
  }

  private onMutate(ws: WebSocket, raw: unknown): void {
    const mutation = validateMutation(raw);
    if (!mutation.ok) {
      log("warn", "listroom.ws.mutate.invalid", { reason: mutation.reason });
      send(ws, error("malformed", null));
      return;
    }
    if (!SUPPORTED_PROTOCOL_VERSIONS.includes(mutation.value.protocolVersion)) {
      send(ws, error("unsupported-protocol-version", mutation.value.idempotencyKey));
      return;
    }
    if (!this.bindListId(mutation.value.listId)) {
      send(ws, error("list-mismatch", mutation.value.idempotencyKey));
      return;
    }

    const result = this.applyMutation(mutation.value);
    send(ws, result);
    if (result.type === "ack") this.broadcast({ type: "change", change: result.change }, ws);
  }

  private onCatchUp(ws: WebSocket, raw: unknown): void {
    const request = validateCatchUpRequest(raw);
    if (!request.ok) {
      send(ws, error("malformed", null));
      return;
    }
    send(ws, this.catchUp(request.value.since));
  }

  // --- Core ---------------------------------------------------------------

  /**
   * Assigns a seq and commits the state change and the changelog row together
   * (D3). Everything below runs inside one `transactionSync`, so a mutation
   * either becomes visible with its seq or does not happen at all — there is
   * no window where a client could read a state row the changelog does not
   * explain.
   */
  private applyMutation(mutation: Mutation): MutationAck | ReturnType<typeof error> {
    // D3: one clock reading per request, passed down. Not three calls that
    // are assumed to agree.
    const serverTimestamp = new Date().toISOString();
    const meta: FieldMeta = { serverTimestamp, deviceId: mutation.deviceId };

    return this.ctx.storage.transactionSync(() => {
      // F5.2 / H3.10: a redelivered key returns the original result. This is
      // the entire answer to "the response was lost after the server
      // committed" — no dedup logic anywhere else in the stack.
      const existing = selectChangeByIdempotencyKey(this.sql, mutation.idempotencyKey);
      if (existing !== null) {
        return {
          type: "ack" as const,
          idempotencyKey: mutation.idempotencyKey,
          duplicate: true,
          change: toEnvelope(existing, mutation.listId),
        };
      }

      const seq = nextSeq(maxSeq(this.sql));
      const envelope = this.applyToState(mutation, seq, meta);
      if (envelope === null) return error("incomplete-create", mutation.idempotencyKey);

      insertChange(this.sql, {
        seq,
        idempotencyKey: mutation.idempotencyKey,
        deviceId: mutation.deviceId,
        serverTimestamp,
        entityType: mutation.entityType,
        entityJson: JSON.stringify(envelope.entity),
      });

      return {
        type: "ack" as const,
        idempotencyKey: mutation.idempotencyKey,
        duplicate: false,
        change: envelope,
      };
    });
  }

  /** Returns the resulting envelope, or null when the patch cannot be applied. */
  private applyToState(mutation: Mutation, seq: number, meta: FieldMeta): ChangeEnvelope | null {
    const base = {
      seq,
      listId: mutation.listId,
      idempotencyKey: mutation.idempotencyKey,
      deviceId: mutation.deviceId,
      serverTimestamp: meta.serverTimestamp,
    };

    if (mutation.entityType === "task") {
      const current = selectTask(this.sql, mutation.entityId);
      const currentMeta = selectFieldMeta(this.sql, "task", mutation.entityId);
      const result = applyTaskPatch(
        current,
        currentMeta,
        mutation.patch,
        { entityId: mutation.entityId, listId: mutation.listId },
        meta,
      );
      if (!result.ok) return null;
      upsertTask(this.sql, result.entity);
      upsertFieldMeta(this.sql, "task", mutation.entityId, result.wrote, meta);
      return { ...base, entityType: "task", entity: result.entity };
    }

    const current = selectList(this.sql, mutation.entityId);
    const currentMeta = selectFieldMeta(this.sql, "list", mutation.entityId);
    const result = applyListPatch(
      current,
      currentMeta,
      mutation.patch,
      { entityId: mutation.entityId },
      meta,
    );
    if (!result.ok) return null;
    upsertList(this.sql, result.entity);
    upsertFieldMeta(this.sql, "list", mutation.entityId, result.wrote, meta);
    return { ...base, entityType: "list", entity: result.entity };
  }

  /**
   * One page of the changelog after `since`. The HTTP and WebSocket catch-up
   * paths both come through here, because F5.6 puts correctness in the cursor
   * and the gap check rather than in the socket.
   */
  private catchUp(since: number): CatchUpResponse {
    // One row more than the page, so "last page" and "one more waiting" are
    // distinguishable without a second COUNT.
    const rows = selectChangesSince(this.sql, since, CATCH_UP_PAGE_SIZE + 1);
    const truncated = rows.length > CATCH_UP_PAGE_SIZE;
    const page = truncated ? rows.slice(0, CATCH_UP_PAGE_SIZE) : rows;
    const listId = this.listId() ?? "";

    return {
      type: "catch-up-response",
      listId,
      since,
      changes: page.map((row) => toEnvelope(row, listId)),
      maxSeq: maxSeq(this.sql),
      truncated,
    };
  }

  // --- List identity ------------------------------------------------------

  private listId(): string | null {
    return readRoomMeta(this.sql, "list_id");
  }

  /**
   * `idFromName` is one-way, so a DO cannot recover the list id it was
   * addressed by. It records the first one it is told and refuses any later
   * disagreement, which turns a Worker routing bug into an error instead of a
   * silent write into someone else's list.
   */
  private bindListId(listId: string): boolean {
    const known = this.listId();
    if (known === null) {
      writeRoomMeta(this.sql, "list_id", listId);
      return true;
    }
    if (known !== listId) {
      log("error", "listroom.list-mismatch", { known, requested: listId });
      return false;
    }
    return true;
  }

  private broadcast(message: ServerMessage, except: WebSocket | null): void {
    const payload = JSON.stringify(message);
    for (const socket of this.ctx.getWebSockets()) {
      if (socket === except) continue;
      try {
        socket.send(payload);
      } catch (err) {
        // A dead socket must not fail the mutation that is being broadcast —
        // it already committed, and the peer will catch up on reconnect.
        log("warn", "listroom.broadcast.failed", { error: String(err) });
      }
    }
  }
}

function messageType(value: unknown): string | null {
  if (typeof value !== "object" || value === null) return null;
  const type = (value as Record<string, unknown>).type;
  return typeof type === "string" ? type : null;
}

function error(code: ErrorCode, idempotencyKey: string | null) {
  return { type: "error" as const, code, idempotencyKey };
}

function send(ws: WebSocket, message: ServerMessage): void {
  ws.send(JSON.stringify(message));
}

function jsonError(
  code: ErrorCode,
  status: number,
  idempotencyKey: string | null = null,
): Response {
  return new Response(JSON.stringify(error(code, idempotencyKey)), {
    status,
    headers: { "content-type": "application/json" },
  });
}
