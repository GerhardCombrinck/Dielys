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
      /**
       * The HTTPS App Link origin a magic-link email points at (ADR 0005),
       * e.g. "https://dielys.com" — public by nature, so a `vars` value in
       * wrangler.jsonc, not a secret.
       */
      APP_BASE_URL: string;
      /** Secrets — never in wrangler.jsonc `vars` (I1). Set with `wrangler secret put`. */
      JWT_SIGNING_KEY: string;
      ADMIN_TOKEN: string;
      FCM_SERVICE_ACCOUNT_JSON: string;
      /** Brevo transactional API key (ADR 0005). Absent means /auth/magic/* is
       * disabled — see isUsableEmailConfig in index.ts. */
      BREVO_API_KEY: string;
      /** Not a secret, but not a `vars` value either: the address a friend's
       * mail provider sees a sign-in link arrive from, chosen per deployment.
       * Set with `wrangler secret put` for the same reason ADMIN_TOKEN is —
       * it belongs to whoever runs this deployment, not to committed config. */
      EMAIL_FROM: string;
      EMAIL_FROM_NAME: string;
      /**
       * Comma-separated SHA-256 fingerprints of the Android app's signing
       * certificate(s) (ADR 0005) — served back in `/.well-known/assetlinks.json`
       * so Android will verify `dielys.com` as an App Link domain. Not
       * sensitive once published there, but unknown to this repo until the
       * release keystore's fingerprint is generated, so it is a secret
       * rather than a committed `vars` value until then.
       */
      ANDROID_CERT_SHA256_FINGERPRINTS: string;
    }
  }

  interface Env extends Cloudflare.Env {}
}
