/**
 * Fractional indexing (F5.5). Pure — no imports from storage/, no env.
 * Positions are strings so a reorder never renumbers siblings. Placeholder
 * midpoint algorithm; replace with a base-62 implementation before shipping
 * real reordering.
 */

export function between(before: string | null, after: string | null): string {
  if (before === null && after === null) return "a0";
  if (before === null) return `${after}!`; // TODO: real predecessor
  if (after === null) return `${before}0`; // TODO: real successor
  // TODO: real midpoint between two fractional-index strings.
  throw new Error("not implemented: midpoint between two existing positions");
}
