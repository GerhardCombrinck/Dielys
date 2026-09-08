#!/usr/bin/env node
/**
 * Creates one Dielys account. There is no public registration endpoint
 * (see docs/CODE_STANDARD.md L2) — this script, run by hand, is the only way
 * an account is created. Household has exactly two users; this is not meant
 * to scale past that.
 *
 * It calls the Worker's admin route rather than touching storage directly, so
 * it behaves identically against dev and prod and needs no local binding.
 *
 * Usage:
 *   DIELYS_URL=https://dielys-dev.dielys.workers.dev \
 *   DIELYS_ADMIN_TOKEN=<the ADMIN_TOKEN secret> \
 *   node --experimental-strip-types scripts/create-user.ts <email>
 *
 * The password is read from the terminal, not passed as an argument: a
 * password in argv shows up in shell history and in the process list.
 */
import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";

const MIN_PASSWORD_LENGTH = 12;

async function main(): Promise<void> {
  const email = process.argv[2];
  const baseUrl = process.env["DIELYS_URL"];
  const adminToken = process.env["DIELYS_ADMIN_TOKEN"];

  if (!email) {
    console.error("Usage: create-user.ts <email>");
    console.error("Requires DIELYS_URL and DIELYS_ADMIN_TOKEN in the environment.");
    process.exit(1);
  }
  if (!baseUrl || !adminToken) {
    console.error("DIELYS_URL and DIELYS_ADMIN_TOKEN must both be set.");
    console.error("The admin token is the ADMIN_TOKEN Worker secret for that environment.");
    process.exit(1);
  }

  const rl = createInterface({ input: stdin, output: stdout });
  try {
    // A2: print what this is about to do, then ask, before writing anything.
    console.log("About to create a Dielys account.");
    console.log(`  Target:  ${baseUrl}`);
    console.log(`  Email:   ${email}`);
    console.log("This writes to the live UsersRoom Durable Object for that environment.");

    const confirmation = await rl.question("Continue? [y/N] ");
    if (confirmation.trim().toLowerCase() !== "y") {
      console.log("Aborted.");
      return;
    }

    const password = await rl.question("Password (use a generated one): ");
    if (password.length < MIN_PASSWORD_LENGTH) {
      console.error(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`);
      process.exitCode = 1;
      return;
    }
    const again = await rl.question("Repeat password: ");
    if (password !== again) {
      console.error("Passwords do not match. Nothing was created.");
      process.exitCode = 1;
      return;
    }

    const response = await fetch(new URL("/admin/users", baseUrl), {
      method: "POST",
      headers: {
        "content-type": "application/json",
        authorization: `Bearer ${adminToken}`,
      },
      body: JSON.stringify({ email, password }),
    });

    if (response.status === 201) {
      const body = (await response.json()) as { userId: string };
      console.log(`Created. userId: ${body.userId}`);
      return;
    }

    if (response.status === 409) {
      console.error("An account with that email already exists. Nothing was created.");
      process.exitCode = 1;
      return;
    }
    if (response.status === 401) {
      console.error("Admin token rejected. Check DIELYS_ADMIN_TOKEN for this environment.");
      process.exitCode = 1;
      return;
    }

    console.error(`Failed: HTTP ${response.status} ${await response.text()}`);
    process.exitCode = 1;
  } finally {
    rl.close();
  }
}

main().catch((error: unknown) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
