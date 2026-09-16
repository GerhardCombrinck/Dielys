import { describe, expect, it } from "vitest";
import { seedPositions } from "./listOrder.js";
import { isValid } from "./position.js";

interface Row {
  id: string;
  position: string | null;
}

describe("seedPositions", () => {
  it("leaves an already-ordered list untouched", () => {
    const rows: Row[] = [
      { id: "a", position: "a0" },
      { id: "b", position: "a1" },
    ];
    const seeded = seedPositions(
      rows,
      (r) => r.position,
      (r, position) => ({ ...r, position }),
    );
    expect(seeded).toEqual(rows);
    expect(seeded[0]).toBe(rows[0]);
  });

  it("gives every keyless row a key in the order it is already shown", () => {
    const rows: Row[] = [
      { id: "a", position: null },
      { id: "b", position: null },
      { id: "c", position: null },
    ];
    const seeded = seedPositions(
      rows,
      (r) => r.position,
      (r, position) => ({ ...r, position }),
    );
    const positions = seeded.map((r) => r.position);
    expect(positions.every((p) => p !== null && isValid(p))).toBe(true);
    expect([...positions].sort()).toEqual(positions);
  });

  it("keeps an existing key and only seeds the rows that have none", () => {
    // seedPositions walks forward with `between(previous, null)` — an append,
    // not a "between the neighbours" insert — so a keyless row after an
    // existing key sorts above it, never between it and the row after.
    const rows: Row[] = [
      { id: "a", position: null },
      { id: "b", position: "b00" },
      { id: "c", position: null },
    ];
    const seeded = seedPositions(
      rows,
      (r) => r.position,
      (r, position) => ({ ...r, position }),
    );
    expect(seeded[1]?.position).toBe("b00");
    expect(seeded.map((r) => r.position)).toEqual([...seeded.map((r) => r.position)].sort());
  });
});
