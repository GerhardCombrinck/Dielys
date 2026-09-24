/**
 * Drains the outbox, then pulls whatever the server has that this browser
 * does not — the web port of `android/.../data/sync/SyncEngine.kt`.
 *
 * Transport-agnostic on purpose: it talks to `SyncApi`, so the offline
 * scenarios are plain tests against a fake that throws (H1). `request()` is
 * the only entry point the app uses; it coalesces bursts of taps into one
 * run and retries a failed one with backoff, the job `WorkManager` does on
 * Android. There is no background process on the web — a closed tab simply
 * drains on its next open, since the outbox is in IndexedDB.
 */
import type {
  CatchUpResponse,
  ChangeEnvelope,
  Membership,
  Mutation,
  MutationAck,
  SetListNotifyRequest,
  SetListPositionRequest,
} from "@dielys/protocol";
import { ApiError } from "../api/client.js";
import type { OutboxRow, Replica } from "./replica.js";

export interface SyncApi {
  memberships(): Promise<Membership[]>;
  changes(listId: string, since: number): Promise<CatchUpResponse>;
  mutate(listId: string, mutation: Mutation): Promise<MutationAck>;
  claim(listId: string): Promise<unknown>;
  setListPosition(request: SetListPositionRequest): Promise<unknown>;
  setListNotify(request: SetListNotifyRequest): Promise<unknown>;
}

export type SyncOutcome = "success" | "retry" | "session-expired";

/** How a failed call should be treated. */
type Failure = "rejected" | "retry" | "session-expired";

/**
 * A 4xx is the server refusing this request for good — no retry can fix it
 * — except 401 (the refresh already failed: the session is over), 408 and
 * 429 (try later). Anything else, including a `fetch` that never got an
 * answer, is worth retrying.
 */
export function classify(error: unknown): Failure {
  if (!(error instanceof ApiError)) return "retry";
  if (error.status === 401) return "session-expired";
  if (error.status === 408 || error.status === 429) return "retry";
  if (error.status >= 400 && error.status < 500) return "rejected";
  return "retry";
}

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 60_000;
const LOCK_NAME = "dielys.sync.lock";

type Work = "drain" | "sync";

export class SyncEngine {
  private wanted: Work | null = null;
  private running = false;
  private stopped = false;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;
  private backoff = INITIAL_BACKOFF_MS;
  /** The first sync after start ignores the memberships' heads and pulls every
   * list, which bounds how long a lost head report can leave one behind —
   * the web's stand-in for Android's daily sweep, since a tab rarely lives a day. */
  private sweepDone = false;
  /** One pull per list at a time — the socket's hello, opening the list and
   * a full sync can all ask for the same one at once. */
  private readonly pulling = new Map<string, Promise<SyncOutcome>>();

  constructor(
    private readonly replica: Replica,
    private readonly api: SyncApi,
  ) {}

  /**
   * Asks for work without waiting for it. `"drain"` after a local edit (send
   * what is queued), `"sync"` on open, focus and reconnect (send, then pull).
   * A request made while a run is going folds into one more run after it.
   */
  request(work: Work): void {
    if (this.stopped) return;
    if (this.wanted !== "sync") this.wanted = work;
    if (this.running) return;
    if (this.retryTimer !== null) {
      // Something happened (a tap, the tab coming back) — try now rather
      // than sit out the rest of the backoff.
      clearTimeout(this.retryTimer);
      this.retryTimer = null;
    }
    this.running = true;
    void this.run().finally(() => {
      this.running = false;
    });
  }

  stop(): void {
    this.stopped = true;
    if (this.retryTimer !== null) clearTimeout(this.retryTimer);
    this.retryTimer = null;
  }

  private async run(): Promise<void> {
    while (this.wanted !== null && !this.stopped) {
      const work = this.wanted;
      this.wanted = null;
      const outcome = await withLock(() => (work === "sync" ? this.sync() : this.drain()));
      if (outcome === "success") {
        this.backoff = INITIAL_BACKOFF_MS;
        continue;
      }
      if (outcome === "retry" && !this.stopped) {
        const delay = this.backoff * (0.5 + Math.random());
        this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
        this.retryTimer = setTimeout(() => {
          this.retryTimer = null;
          this.request("sync");
        }, delay);
      }
      // A session that is over stops here; the next request (focus, a tap,
      // signing in again) is what tries again.
      this.wanted = null;
      return;
    }
  }

  async sync(): Promise<SyncOutcome> {
    const drained = await this.drain();
    if (drained !== "success") return drained;

    let memberships: Membership[];
    try {
      memberships = await this.api.memberships();
    } catch (error) {
      const failure = classify(error);
      return failure === "rejected" ? "retry" : failure;
    }
    this.replica.noteMemberships(memberships);

    const heads = new Map<string, number>();
    for (const m of memberships) if (m.maxSeq !== null) heads.set(m.listId, m.maxSeq);
    const sweep = !this.sweepDone;
    for (const list of this.replica.allLists()) {
      if (!sweep && this.isCaughtUp(list.id, heads.get(list.id))) continue;
      const outcome = await this.catchUp(list.id);
      if (outcome !== "success") return outcome;
    }
    this.sweepDone = true;
    return "success";
  }

  private isCaughtUp(listId: string, head: number | undefined): boolean {
    return (
      head !== undefined && this.replica.hasCursor(listId) && this.replica.cursor(listId) >= head
    );
  }

  /**
   * Sends pending rows oldest first, one at a time: two edits to the same
   * entity must reach the server in the order they were made, because it
   * resolves conflicts by the timestamp it stamps on arrival (F5.4). A row is
   * dropped only once acknowledged, in the same write that applies its echo;
   * a tab closed in between resends it with the same key, and the server
   * answers with the original result (F5.2).
   */
  async drain(): Promise<SyncOutcome> {
    const gapped = new Set<string>();
    for (;;) {
      if (this.stopped) return "success";
      const row = this.replica.nextPending();
      if (row === null) break;
      const result = await this.send(row);
      if (result === "gap") gapped.add(row.listId);
      else if (result !== "done") return result;
    }
    for (const listId of gapped) {
      const outcome = await this.catchUp(listId);
      if (outcome !== "success") return outcome;
    }
    return "success";
  }

  /**
   * `GET ?since=cursor`, applied through the same `Replica.apply` the socket
   * uses (F5.6), paging until the server stops truncating. A list the server
   * refuses (403 — no longer ours) is left for the memberships answer to
   * forget, not retried.
   */
  catchUp(listId: string): Promise<SyncOutcome> {
    const existing = this.pulling.get(listId);
    if (existing !== undefined) return existing;
    const pull = this.pull(listId).finally(() => this.pulling.delete(listId));
    this.pulling.set(listId, pull);
    return pull;
  }

  private async pull(listId: string): Promise<SyncOutcome> {
    for (;;) {
      if (this.stopped) return "success";
      let page: CatchUpResponse;
      try {
        page = await this.api.changes(listId, this.replica.cursor(listId));
      } catch (error) {
        const failure = classify(error);
        return failure === "rejected" ? "success" : failure;
      }
      if (this.stopped) return "success";
      this.replica.applyAll(page.changes);
      // An empty page that still claims more would loop forever.
      if (!page.truncated || page.changes.length === 0) return "success";
    }
  }

  /** A change pushed over the socket. A gap pulls the missing range over HTTP. */
  async receive(change: ChangeEnvelope): Promise<void> {
    if (this.replica.apply(change) === "gap") await this.catchUp(change.listId);
  }

  private async send(row: OutboxRow): Promise<"done" | "gap" | SyncOutcome> {
    try {
      if (row.kind === "claim") {
        await this.api.claim(row.listId);
        if (!this.stopped) this.replica.completeRow(row.key, null);
        return "done";
      }
      if (row.kind === "order") {
        // Lands on this account's membership row — no changelog, no echo.
        await this.api.setListPosition(row.body as SetListPositionRequest);
        if (!this.stopped) this.replica.completeRow(row.key, null);
        return "done";
      }
      if (row.kind === "notify") {
        // The same shape as a drag: this account's membership row (ADR 0012).
        await this.api.setListNotify(row.body as SetListNotifyRequest);
        if (!this.stopped) this.replica.completeRow(row.key, null);
        return "done";
      }
      const ack = await this.api.mutate(row.listId, row.body as Mutation);
      if (this.stopped) return "success";
      return this.replica.completeRow(row.key, ack.change) === "gap" ? "gap" : "done";
    } catch (error) {
      const failure = classify(error);
      if (failure !== "rejected") return failure;
      const code = error instanceof ApiError ? `${error.status} ${error.code}` : "rejected";
      this.replica.markDead(row.key, code);
      return "done";
    }
  }
}

/**
 * One tab syncs at a time. Two tabs draining the same outbox at once is not
 * wrong — every row carries its idempotency key (F5.2) — but it doubles the
 * requests and can interleave two tabs' edits to one entity.
 */
function withLock<T>(fn: () => Promise<T>): Promise<T> {
  if (typeof navigator === "undefined" || !("locks" in navigator)) return fn();
  // Same lib.dom typing gap as `client.ts`'s `withRefreshLock`.
  return navigator.locks.request(LOCK_NAME, () => fn()) as unknown as Promise<T>;
}
