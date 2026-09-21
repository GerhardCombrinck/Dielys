import { describe, expect, it } from "vitest";
import { draftStillWanted, dropTarget, moved, neighborsOf } from "./reorder.js";

describe("moved", () => {
  it("last row to the front", () => {
    expect(moved(["a", "b", "c"], 2, 0)).toEqual(["c", "a", "b"]);
  });

  it("first row to the end", () => {
    expect(moved(["a", "b", "c"], 0, 2)).toEqual(["b", "c", "a"]);
  });

  it("out of range leaves the order alone", () => {
    expect(moved(["a", "b"], 0, 5)).toEqual(["a", "b"]);
  });
});

describe("neighborsOf", () => {
  it("the front has no afterId", () => {
    expect(neighborsOf(["c", "a", "b"], 0)).toEqual({ afterId: null, beforeId: "a" });
  });

  it("the end has no beforeId", () => {
    expect(neighborsOf(["b", "c", "a"], 2)).toEqual({ afterId: "c", beforeId: null });
  });

  it("a single-row list has no neighbours either side", () => {
    expect(neighborsOf(["only"], 0)).toEqual({ afterId: null, beforeId: null });
  });
});

describe("dropTarget", () => {
  // Four 50px rows with a 10px gap: tops 0, 60, 120, 180.
  const rects = [0, 60, 120, 180].map((top) => ({ top, height: 50 }));

  it("stays put until the middle enters a neighbour's box", () => {
    expect(dropTarget(rects, 1, 115)).toBe(1);
    expect(dropTarget(rects, 1, 55)).toBe(1);
  });

  it("takes the next slot down as soon as the middle is into it", () => {
    expect(dropTarget(rects, 1, 121)).toBe(2);
  });

  it("takes the slot above as soon as the middle is into it", () => {
    expect(dropTarget(rects, 2, 105)).toBe(1);
  });

  it("second-last reaches last well before the drag's clamp", () => {
    // Clamped, row 2's middle can go no lower than 180 + 50 - 25 = 205.
    expect(dropTarget(rects, 2, 185)).toBe(3);
    expect(dropTarget(rects, 2, 205)).toBe(3);
  });

  it("second reaches first well before the drag's clamp", () => {
    // Clamped, row 1's middle can go no higher than 25.
    expect(dropTarget(rects, 1, 45)).toBe(0);
    expect(dropTarget(rects, 1, 25)).toBe(0);
  });
});

describe("draftStillWanted", () => {
  it("wanted while the server still has the old order", () => {
    expect(draftStillWanted(["b", "a"], ["a", "b"])).toBe(true);
  });

  it("dropped once the server agrees", () => {
    expect(draftStillWanted(["b", "a"], ["b", "a"])).toBe(false);
  });

  it("dropped when a row arrived or left meanwhile", () => {
    expect(draftStillWanted(["b", "a"], ["a", "b", "c"])).toBe(false);
    expect(draftStillWanted(["b", "a"], ["a", "c"])).toBe(false);
  });
});
