import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

/** Runs tests in real workerd, real DO storage (H1) — never against a mock. */
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        // Secrets are not in wrangler.jsonc (I1), and `.dev.vars` is gitignored,
        // so tests declare their own. These values are test fixtures — the real
        // ones are set with `wrangler secret put` and never live in the repo.
        bindings: {
          JWT_SIGNING_KEY: "test-signing-key-not-used-anywhere-real",
          ADMIN_TOKEN: "test-admin-token-not-used-anywhere-real",
          FCM_SERVICE_ACCOUNT_JSON: "{}",
          // Present so /auth/magic/* is not gated off by isUsableEmailConfig;
          // real sends are stubbed at `fetch` (see magic-link.test.ts).
          BREVO_API_KEY: "test-brevo-key-not-used-anywhere-real",
          EMAIL_FROM: "dielys@dielys.test",
          EMAIL_FROM_NAME: "Dielys",
          ANDROID_CERT_SHA256_FINGERPRINTS: "",
        },
      },
    }),
  ],
});
