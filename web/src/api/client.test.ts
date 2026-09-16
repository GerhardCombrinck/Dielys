import { describe, expect, it } from "vitest";
import { ApiError } from "./client.js";

describe("ApiError", () => {
  it("carries the status and the server's stable code, not a message to parse", () => {
    const error = new ApiError(401, "unauthorized");
    expect(error.status).toBe(401);
    expect(error.code).toBe("unauthorized");
    expect(error).toBeInstanceOf(Error);
  });
});
