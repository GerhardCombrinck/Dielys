import { DurableObject } from "cloudflare:workers";

/**
 * The Durable Object is the single-threaded owner of one list (D3). One DO
 * per list, id derived via idFromName(listId) — never a random id, never one
 * DO for multiple lists.
 *
 * Rules this class must hold to (see CODE_STANDARD.md D3):
 * - Sequence numbers assigned here, in the same transaction as the write.
 * - All storage writes for one logical change in one transaction.
 * - No module-level mutable state — everything lives in `this` or storage.
 * - WebSocket Hibernation API (acceptWebSocket), not addEventListener.
 * - blockConcurrencyWhile wraps any init later requests depend on.
 */
export class ListRoom extends DurableObject {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      // TODO: run pending migrations (see server/migrations/AGENTS.md),
      // load into this._state as needed.
    });
  }

  override async fetch(_request: Request): Promise<Response> {
    // TODO: upgrade to WebSocket via this.ctx.acceptWebSocket(pair[1]).
    return new Response("not implemented", { status: 501 });
  }

  override async webSocketMessage(_ws: WebSocket, _message: string | ArrayBuffer): Promise<void> {
    // TODO: validate (F3), apply mutation + assign seq in one transaction, broadcast.
  }

  override async webSocketClose(_ws: WebSocket, _code: number, _reason: string): Promise<void> {
    // TODO: cleanup, nothing to persist — hibernation handles the rest.
  }
}
