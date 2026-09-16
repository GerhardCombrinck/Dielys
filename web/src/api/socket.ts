/**
 * The live-update channel for one open list (ADR 0009). Not the source of
 * truth — `lists.ts`'s `catchUp`/`mutate` are (web/AGENTS.md) — this only
 * tells a page "something changed" while it is open, so it never has to poll.
 *
 * Every (re)connect mints a fresh ticket: a ticket is single-use and short-
 * lived (60s), so one minted for the first attempt cannot be reused for a
 * retry after a drop.
 */
import type { ChangeEnvelope, ClientHello, ServerMessage } from "@dielys/protocol";
import { PROTOCOL_VERSION } from "@dielys/protocol";
import { mintWsTicket } from "./auth.js";
import { API_BASE_URL } from "./client.js";

export type ConnectionStatus = "connecting" | "connected" | "reconnecting";

export interface ListSocketHandlers {
  onStatus(status: ConnectionStatus): void;
  onChange(change: ChangeEnvelope): void;
  /** The server's view of how far the changelog goes, from `hello-ok` — lets
   * the caller notice a gap between its own cursor and the truth and pull it
   * over HTTP. */
  onHelloOk(maxSeq: number): void;
}

const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 15_000;

export class ListSocket {
  private ws: WebSocket | null = null;
  private stopped = true;
  private backoff = INITIAL_BACKOFF_MS;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    private readonly listId: string,
    private readonly deviceId: string,
    private readonly cursor: () => number,
    private readonly handlers: ListSocketHandlers,
  ) {}

  start(): void {
    if (!this.stopped) return;
    this.stopped = false;
    void this.connect();
  }

  stop(): void {
    this.stopped = true;
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.ws?.close();
    this.ws = null;
  }

  private async connect(): Promise<void> {
    if (this.stopped) return;
    this.handlers.onStatus(this.backoff === INITIAL_BACKOFF_MS ? "connecting" : "reconnecting");

    let ticket: string;
    try {
      ticket = (await mintWsTicket()).ticket;
    } catch {
      this.scheduleReconnect();
      return;
    }
    if (this.stopped) return;

    const url = new URL(`${API_BASE_URL}/lists/${encodeURIComponent(this.listId)}/ws`);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    url.searchParams.set("ticket", ticket);

    const ws = new WebSocket(url);
    this.ws = ws;

    ws.addEventListener("open", () => {
      const hello: ClientHello = {
        type: "hello",
        protocolVersion: PROTOCOL_VERSION,
        listId: this.listId,
        cursor: this.cursor(),
        deviceId: this.deviceId,
      };
      ws.send(JSON.stringify(hello));
    });

    ws.addEventListener("message", (event: MessageEvent) => {
      if (typeof event.data !== "string") return;
      let message: ServerMessage;
      try {
        message = JSON.parse(event.data) as ServerMessage;
      } catch {
        return;
      }
      this.onMessage(message);
    });

    ws.addEventListener("close", () => {
      if (this.ws !== ws) return; // superseded by a newer socket already
      this.ws = null;
      this.scheduleReconnect();
    });
  }

  private onMessage(message: ServerMessage): void {
    switch (message.type) {
      case "hello-ok":
        this.backoff = INITIAL_BACKOFF_MS;
        this.handlers.onStatus("connected");
        this.handlers.onHelloOk(message.maxSeq);
        return;
      case "change":
        this.handlers.onChange(message.change);
        return;
      default:
        // hello-error / error / ack / catch-up-response: this client never
        // sends a mutate or catch-up frame over the socket (lists.ts does
        // both over HTTP instead), so none of these are expected here — and
        // a stray one is not fatal, the next catch-up still converges (F5.6).
        return;
    }
  }

  private scheduleReconnect(): void {
    if (this.stopped) return;
    this.handlers.onStatus("reconnecting");
    this.reconnectTimer = setTimeout(() => {
      this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
      void this.connect();
    }, this.backoff);
  }
}
