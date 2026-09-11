import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

/** Runs tests in real workerd, real DO storage (H1) — never against a mock. */
export default defineConfig({
  test: {
    // One test file at a time. The pool starts a workerd per file, and when it
    // starts them concurrently it sometimes starts one it never stops
    // (cloudflare/workers-sdk#15498): every test passes, then the run holds
    // that child process and waits forever, printing nothing. Measured on this
    // machine: 42% of runs hang with files in parallel, 0 of 20 without.
    // Costs about 8 seconds a run, which is cheaper than one hung run.
    fileParallelism: false,
  },
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        // Secrets are not in wrangler.jsonc (I1), and `.dev.vars` is gitignored,
        // so tests declare their own. These values are test fixtures — the real
        // ones are set with `wrangler secret put` and never live in the repo.
        bindings: {
          // Overrides wrangler.jsonc's "dev", which disables auth rate limits
          // (UsersRoom.consume) — the rate-limit tests need the real behaviour,
          // not the dev-deployment bypass. Deploying still reads "dev" from
          // wrangler.jsonc itself; this only affects the test run.
          ENVIRONMENT: "prod",
          JWT_SIGNING_KEY: "test-signing-key-not-used-anywhere-real",
          ADMIN_TOKEN: "test-admin-token-not-used-anywhere-real",
          FCM_SERVICE_ACCOUNT_JSON: "{}",
          // Present so /auth/magic/* is not gated off by isUsableEmailConfig;
          // real sends are stubbed at `fetch` (see magic-link.test.ts).
          BREVO_API_KEY: "test-brevo-key-not-used-anywhere-real",
          EMAIL_FROM: "dielys@dielys.test",
          EMAIL_FROM_NAME: "Die Lys",
          ANDROID_CERT_SHA256_FINGERPRINTS: "",
        },
      },
    }),
  ],
});
