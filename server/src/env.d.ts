/**
 * Hand-written until `wrangler types` is run against wrangler.jsonc.
 * Keep in sync with the bindings and vars declared there.
 */
export {};

declare global {
  interface Env {
    ENVIRONMENT: "dev" | "prod";
    LIST_ROOM: DurableObjectNamespace;
    USERS_ROOM: DurableObjectNamespace;
    JWT_SIGNING_KEY: string;
    FCM_SERVICE_ACCOUNT_JSON: string;
  }
}
