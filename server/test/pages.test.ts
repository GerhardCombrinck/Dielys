import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

/**
 * The static pages in `public/` (server/public-static/, served by Workers
 * static assets ahead of the Worker — server/wrangler.jsonc). Google Play
 * links to the privacy policy and the account deletion page, so a deploy
 * that stopped serving either would break the store listing without any
 * other test noticing. Both are plain HTML with their own inline script,
 * deliberately not part of the `web/` SPA (ADR 0007) — Play's crawler is not
 * guaranteed to run client-side JS.
 */
describe("static pages", () => {
  for (const [path, heading] of [
    ["/privacy", "Privacy policy"],
    ["/account/delete", "Delete your Die Lys account"],
    ["/account/delete/confirm", "Delete your account for good?"],
  ] as const) {
    it(`serves ${path}`, async () => {
      // Through the binding: SELF calls the Worker's own fetch directly and
      // skips the asset router that sits in front of it once deployed.
      const response = await env.ASSETS.fetch(`https://dielys.test${path}`);
      expect(response.status).toBe(200);
      expect(response.headers.get("content-type")).toContain("text/html");
      expect(await response.text()).toContain(heading);
    });
  }

  it("leaves the Worker's own routes to the Worker", async () => {
    const health = await SELF.fetch("https://dielys.test/health");
    expect(await health.json()).toMatchObject({ ok: true });

    const assetLinks = await SELF.fetch("https://dielys.test/.well-known/assetlinks.json");
    expect(assetLinks.headers.get("content-type")).toContain("application/json");
  });
});

/**
 * `web/`'s built SPA shell (`web/dist/index.html`, copied into
 * `public/` by scripts/build-web-public.sh) — served at `/` itself and at
 * every browser route the Worker does not otherwise recognise, so
 * `web/src/router.tsx` can take over client-side. Through SELF, not the
 * ASSETS binding directly, because the routing that decides this (the GET
 * fallback in src/index.ts) is the Worker's own job, not the asset router's.
 */
describe("web client shell", () => {
  for (const path of ["/", "/magic", "/invite", "/settings", `/lists/${crypto.randomUUID()}`]) {
    it(`serves the SPA shell for GET ${path}`, async () => {
      const response = await SELF.fetch(`https://dielys.test${path}`);
      expect(response.status).toBe(200);
      expect(response.headers.get("content-type")).toContain("text/html");
      const body = await response.text();
      expect(body).toContain("<title>Die Lys</title>");
      expect(body).toContain('<div id="root">');
    });
  }

  it("still 404s an unrecognised non-GET request", async () => {
    const response = await SELF.fetch("https://dielys.test/this-is-not-a-route", {
      method: "POST",
    });
    expect(response.status).toBe(404);
  });
});
