import { describe, expect, it } from "vitest";
import { dropNeighbors } from "./reorder.js";

describe("dropNeighbors", () => {
  it("dragging the last row to the front lands with no afterId", () => {
    const result = dropNeighbors(["a", "b", "c"], 2, 0);
    expect(result).toEqual({ id: "c", afterId: null, beforeId: "a" });
  });

  it("dragging the first row to the end lands with no beforeId", () => {
    const result = dropNeighbors(["a", "b", "c"], 0, 3);
    expect(result).toEqual({ id: "a", afterId: "c", beforeId: null });
  });

  it("dragging a middle row down one slot", () => {
    const result = dropNeighbors(["a", "b", "c", "d"], 1, 3);
    expect(result).toEqual({ id: "b", afterId: "c", beforeId: "d" });
  });

  it("a single-row list has no neighbours either side", () => {
    const result = dropNeighbors(["only"], 0, 0);
    expect(result).toEqual({ id: "only", afterId: null, beforeId: null });
  });
});
