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
  const centers = [25, 85, 145, 205];

  it("stays put until the middle crosses a neighbour's middle", () => {
    expect(dropTarget(centers, 1, 140)).toBe(1);
    expect(dropTarget(centers, 1, 30)).toBe(1);
  });

  it("takes the next slot down once past its middle", () => {
    expect(dropTarget(centers, 1, 150)).toBe(2);
  });

  it("takes the first slot once past the top row's middle", () => {
    expect(dropTarget(centers, 2, 20)).toBe(0);
  });

  it("dragged past the bottom lands last", () => {
    expect(dropTarget(centers, 0, 999)).toBe(3);
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
