/**
 * Hand-written until `wrangler types` is run against wrangler.jsonc.
 * Keep in sync with the bindings and vars declared there.
 *
 * Shaped the way `wrangler types` generates it — `Cloudflare.Env` is the
 * canonical interface and the global `Env` extends it — because the tooling
 * reads the namespaced one. `cloudflare:test` types its `env` as
 * `Cloudflare.Env`, so a bare global would leave every test untyped.
 */
import type { ListRoom } from "./do/ListRoom.js";
import type { UsersRoom } from "./do/UsersRoom.js";

declare global {
  namespace Cloudflare {
    interface Env {
      ENVIRONMENT: "dev" | "prod";
      /** Typed namespaces, so the DOs' RPC methods are checked at the call site. */
      LIST_ROOM: DurableObjectNamespace<ListRoom>;
      USERS_ROOM: DurableObjectNamespace<UsersRoom>;
      /** Secrets — never in wrangler.jsonc `vars` (I1). Set with `wrangler secret put`. */
      JWT_SIGNING_KEY: string;
      ADMIN_TOKEN: string;
      FCM_SERVICE_ACCOUNT_JSON: string;
    }
  }

  interface Env extends Cloudflare.Env {}
}
