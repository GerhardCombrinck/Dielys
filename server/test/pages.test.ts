import { env, SELF } from "cloudflare:test";
import { describe, expect, it } from "vitest";

/**
 * The static pages in `public/` (served by Workers static assets, ahead of
 * the Worker). Google Play links to the privacy policy and the account
 * deletion page, so a deploy that stopped serving either would break the
 * store listing without any other test noticing.
 */
describe("static pages", () => {
  for (const [path, heading] of [
    ["/", "Lists you share"],
    ["/privacy", "Privacy policy"],
    ["/account/delete", "Delete your Die Lys account"],
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
