#!/usr/bin/env node
/**
 * Creates one Wonderlys account. There is no public registration endpoint
 * (see docs/CODE_STANDARD.md L2) — this script, run by hand, is the only way
 * an account is created. Household has exactly two users; this is not meant
 * to scale past that.
 *
 * Usage: npm run create-user -- <email> <password>
 *
 * TODO: wire up once server/src/do/UsersRoom.ts and its admin RPC exist.
 * This script calls that RPC over an authenticated admin endpoint — it does
 * not touch storage directly, so it works the same in dev and prod.
 */

async function main() {
  const [email, password] = process.argv.slice(2);
  if (!email || !password) {
    console.error("Usage: create-user.ts <email> <password>");
    process.exit(1);
  }

  console.log(`About to create user: ${email}`);
  console.log("This writes to the live UsersRoom Durable Object. Continue? [y/N]");

  const answer = await new Promise<string>((resolve) => {
    process.stdin.resume();
    process.stdin.once("data", (d) => resolve(d.toString().trim()));
  });

  if (answer.toLowerCase() !== "y") {
    console.log("Aborted.");
    process.exit(0);
  }

  throw new Error(
    "Not implemented yet — server/src/do/UsersRoom.ts has no admin RPC to call. " +
      "See docs/adr/0002-authentication.md.",
  );
}

main();
