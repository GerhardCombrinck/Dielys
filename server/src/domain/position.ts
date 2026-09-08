/**
 * Fractional indexing (F5.5). Pure — no imports from storage/, no env, no
 * clock (D1). Positions are strings so a reorder never renumbers siblings.
 *
 * ## Why an order key rather than a plain midpoint
 *
 * The obvious scheme — treat the position as a base-62 fraction and take the
 * midpoint — degrades on exactly the operation a to-do list does most:
 * appending to the end. Each append has to pick a value above everything
 * already there, so keys march toward "z" and then start growing a character
 * at a time.
 *
 * So a key has two parts: an **integer part** whose first character encodes
 * its own length, and an optional **fractional part**. Appending increments
 * the integer, which stays two characters for the first 62 items ("a0".."az"),
 * three for the next 3,844 ("b00".."bzz"), and so on. Inserting *between* two
 * items is the only operation that grows a key, and only by the depth of the
 * insertion.
 *
 * Length prefixes: `a`..`z` are positive integer parts of length 2..27,
 * `A`..`Z` negative of length 2..27. Prepending to the front of a list walks
 * down through `Z`, `Y`, … which is why the negative half exists at all.
 *
 * ## Ordering
 *
 * `BASE62` is in ASCII order, so keys sort correctly under plain byte
 * comparison — `<` in TypeScript, `compareTo` in Kotlin. Never sort these with
 * a locale-aware comparator: `localeCompare` would put "a" before "B" and
 * silently reorder the list.
 *
 * ## What this does not solve
 *
 * Two devices inserting at the same spot while offline can generate the *same*
 * key. Fractional indexing cannot prevent that — nothing shared coordinates
 * them. The list order is therefore defined as `(position, id)`, ascending,
 * with the UUIDv7 breaking the tie. Both rows survive (H3.9), the order is
 * identical on both devices, and neither is a duplicate of the other.
 *
 * The Kotlin client must mirror this exactly. `protocol/fixtures/positions.json`
 * is the shared contract that makes a mismatch fail a build (F1, F4).
 */

import { MAX_POSITION_LENGTH } from "@dielys/protocol";

/** ASCII-ordered, so byte comparison and digit order agree. */
const BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

/** "A" followed by 26 zeros — the smallest integer part the encoding allows. */
const SMALLEST_INTEGER = `A${"0".repeat(26)}`;

/** The first key in an empty list. */
export const FIRST_POSITION = "a0";

export class PositionError extends Error {}

/**
 * Returns a position strictly between `before` and `after`.
 *
 * `null` means "no neighbour on that side": `between(null, null)` is the first
 * item in an empty list, `between(last, null)` appends, `between(null, first)`
 * prepends.
 */
export function between(before: string | null, after: string | null): string {
  if (before !== null) validate(before);
  if (after !== null) validate(after);
  if (before !== null && after !== null && before >= after) {
    throw new PositionError(`positions out of order: ${before} >= ${after}`);
  }

  if (before === null && after === null) return FIRST_POSITION;

  if (before === null) {
    // Prepend. `after` is not null here.
    const key = after as string;
    const integer = integerPart(key);
    const fraction = key.slice(integer.length);

    // A key with a fractional part is already above its own integer part, so
    // the integer alone sits between it and everything below.
    if (integer === SMALLEST_INTEGER) return checkLength(integer + midpoint("", fraction));
    if (integer < key) return integer;

    const decremented = decrementInteger(integer);
    if (decremented === null) throw new PositionError("cannot prepend any further");
    return decremented;
  }

  if (after === null) {
    // Append — the common case, and the one the integer part exists for.
    const integer = integerPart(before);
    const fraction = before.slice(integer.length);
    const incremented = incrementInteger(integer);
    return incremented === null ? checkLength(integer + midpoint(fraction, null)) : incremented;
  }

  const beforeInteger = integerPart(before);
  const afterInteger = integerPart(after);
  if (beforeInteger === afterInteger) {
    return checkLength(
      beforeInteger +
        midpoint(before.slice(beforeInteger.length), after.slice(afterInteger.length)),
    );
  }

  const incremented = incrementInteger(beforeInteger);
  if (incremented === null) throw new PositionError("integer part overflowed");
  // If the next whole integer still lands below `after`, use it: it is the
  // shortest key that fits.
  if (incremented < after) return incremented;
  return checkLength(beforeInteger + midpoint(before.slice(beforeInteger.length), null));
}

/**
 * Repeatedly inserting at the same spot lengthens the key each time. The bound
 * is generous — it takes thousands of nested inserts between one pair to reach
 * it — but a key the server would reject at the boundary (F3) must fail here,
 * where the message can say why, rather than as a rejected mutation later.
 * `betweenMany` is the fix when many keys are needed at once.
 */
function checkLength(key: string): string {
  if (key.length > MAX_POSITION_LENGTH) {
    throw new PositionError(
      `position would be ${key.length} characters, over the ${MAX_POSITION_LENGTH} bound`,
    );
  }
  return key;
}

/**
 * Generates `count` keys in order between `before` and `after`.
 *
 * Not a loop over `between` at the call site, because inserting n items one at
 * a time between the same pair makes each key a character longer than the last
 * — the classic way fractional indexing degrades. This spreads them instead.
 */
export function betweenMany(before: string | null, after: string | null, count: number): string[] {
  if (!Number.isSafeInteger(count) || count < 0) {
    throw new PositionError(`count must be a non-negative integer, got ${count}`);
  }
  if (count === 0) return [];
  if (count === 1) return [between(before, after)];

  if (after === null) {
    // Appending is cheap: walk forward from each new key.
    let cursor = before;
    return Array.from({ length: count }, () => {
      cursor = between(cursor, null);
      return cursor;
    });
  }

  if (before === null) {
    // Prepending, built backwards then reversed so the result is ascending.
    let cursor = after;
    const keys = Array.from({ length: count }, () => {
      cursor = between(null, cursor);
      return cursor;
    });
    return keys.reverse();
  }

  // Bisect: the middle key first, then fill both halves. Splitting this way
  // makes the keys grow with log(count) rather than with count.
  const half = Math.floor(count / 2);
  const middle = between(before, after);
  return [
    ...betweenMany(before, middle, half),
    middle,
    ...betweenMany(middle, after, count - half - 1),
  ];
}

/** Throws unless `key` is a well-formed position. */
export function validate(key: string): void {
  if (key === "") throw new PositionError("position is empty");

  const integer = integerPart(key);
  const fraction = key.slice(integer.length);
  // A trailing zero is a second spelling of the same position; forbidding it
  // keeps the encoding canonical so equal keys are equal strings.
  if (fraction.endsWith("0")) throw new PositionError(`position has a trailing zero: ${key}`);
  for (const char of fraction) {
    if (BASE62.indexOf(char) === -1) throw new PositionError(`invalid digit in position: ${key}`);
  }
}

export function isValid(key: string): boolean {
  try {
    validate(key);
    return true;
  } catch {
    return false;
  }
}

/** The declared length of the integer part, read from its first character. */
function integerLength(head: string): number {
  if (head >= "a" && head <= "z") return head.charCodeAt(0) - "a".charCodeAt(0) + 2;
  if (head >= "A" && head <= "Z") return "Z".charCodeAt(0) - head.charCodeAt(0) + 2;
  throw new PositionError(`invalid position head: ${head}`);
}

function integerPart(key: string): string {
  const length = integerLength(key.charAt(0));
  if (length > key.length) throw new PositionError(`position is too short: ${key}`);
  const integer = key.slice(0, length);
  for (const char of integer.slice(1)) {
    if (BASE62.indexOf(char) === -1) throw new PositionError(`invalid digit in position: ${key}`);
  }
  return integer;
}

/** Returns null when the integer part cannot go any higher. */
function incrementInteger(integer: string): string | null {
  const head = integer.charAt(0);
  const digits = integer.slice(1).split("");

  let carry = true;
  for (let i = digits.length - 1; carry && i >= 0; i -= 1) {
    const next = BASE62.indexOf(digits[i] as string) + 1;
    if (next === BASE62.length) {
      digits[i] = BASE62.charAt(0);
    } else {
      digits[i] = BASE62.charAt(next);
      carry = false;
    }
  }

  if (!carry) return head + digits.join("");

  // Every digit rolled over, so the integer part needs to change length.
  if (head === "Z") return `a${BASE62.charAt(0)}`;
  if (head === "z") return null;

  const nextHead = String.fromCharCode(head.charCodeAt(0) + 1);
  // Crossing from the negative half shortens; staying in the positive half
  // lengthens. The length is encoded in the head, so both stay consistent.
  if (nextHead > "a") digits.push(BASE62.charAt(0));
  else digits.pop();
  return nextHead + digits.join("");
}

/** Returns null when the integer part cannot go any lower. */
function decrementInteger(integer: string): string | null {
  const head = integer.charAt(0);
  const digits = integer.slice(1).split("");

  let borrow = true;
  for (let i = digits.length - 1; borrow && i >= 0; i -= 1) {
    const next = BASE62.indexOf(digits[i] as string) - 1;
    if (next === -1) {
      digits[i] = BASE62.charAt(BASE62.length - 1);
    } else {
      digits[i] = BASE62.charAt(next);
      borrow = false;
    }
  }

  if (!borrow) return head + digits.join("");

  if (head === "a") return `Z${BASE62.charAt(BASE62.length - 1)}`;
  if (head === "A") return null;

  const nextHead = String.fromCharCode(head.charCodeAt(0) - 1);
  if (nextHead < "Z") digits.push(BASE62.charAt(BASE62.length - 1));
  else digits.pop();
  return nextHead + digits.join("");
}

/**
 * The shortest fractional part strictly between `a` and `b`, where both are
 * fractional parts of the same integer part. `b` of null means "no upper
 * bound within this integer".
 */
function midpoint(a: string, b: string | null): string {
  if (b !== null && a >= b) {
    throw new PositionError(`fractions out of order: ${a} >= ${b}`);
  }
  if (a.endsWith("0") || b?.endsWith("0")) {
    throw new PositionError("fraction has a trailing zero");
  }

  if (b !== null) {
    // Strip the shared prefix and recurse — the answer keeps that prefix, and
    // what is left is the same problem on shorter strings. Past the end of
    // `a` the implied digit is "0", which is what makes "ab" and "ab0V"
    // compare the way the encoding says they should.
    let shared = 0;
    while ((a.charAt(shared) || "0") === b.charAt(shared)) shared += 1;
    if (shared > 0) return b.slice(0, shared) + midpoint(a.slice(shared), b.slice(shared));
  }

  const digitA = a === "" ? 0 : BASE62.indexOf(a.charAt(0));
  const digitB = b === null ? BASE62.length : BASE62.indexOf(b.charAt(0));

  if (digitB - digitA > 1) {
    // There is room for a digit strictly between them: one character is enough.
    return BASE62.charAt(Math.round(0.5 * (digitA + digitB)));
  }

  // The digits are consecutive, so the answer has to be longer.
  if (b !== null && b.length > 1) return b.slice(0, 1);
  return BASE62.charAt(digitA) + midpoint(a.slice(1), null);
}
