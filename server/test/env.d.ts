/// <reference types="@cloudflare/vitest-pool-workers/types" />

/**
 * Pulls in the `cloudflare:test` module declarations. Its `env` is typed as
 * `Cloudflare.Env`, which `src/env.d.ts` declares — so tests get the real
 * LIST_ROOM namespace rather than an untyped bag.
 *
 * The pool runs against real workerd and real DO storage (H1): a mocked
 * Durable Object cannot reproduce the single-threading guarantee the design
 * depends on.
 */
