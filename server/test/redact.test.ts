import { describe, expect, it } from "vitest";
import { obscureEmail } from "../src/domain/redact.js";

describe("obscureEmail (#84)", () => {
  it("keeps the local part and hides the domain", () => {
    expect(obscureEmail("alice@example.com")).toBe("alice@…");
    expect(obscureEmail("a.b+c@sub.example.co.za")).toBe("a.b+c@…");
  });

  it("leaves a string with no @ alone", () => {
    expect(obscureEmail("not-an-email")).toBe("not-an-email");
  });
});
