import { DurableObject } from "cloudflare:workers";

/**
 * Singleton Durable Object (idFromName("users-v1")) holding accounts,
 * device-scoped refresh tokens, FCM tokens, and list membership. See
 * docs/adr/0002-authentication.md and CODE_STANDARD.md L1-L3, M2.
 *
 * ListRoom never checks membership itself — the Worker checks it here before
 * forwarding any request to a ListRoom.
 */
export class UsersRoom extends DurableObject {
  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.blockConcurrencyWhile(async () => {
      // TODO: run pending migrations.
    });
  }

  override async fetch(_request: Request): Promise<Response> {
    // TODO: internal RPC surface for the Worker — createUser, verifyPassword,
    // issueRefreshToken, rotateRefreshToken, checkMembership, addMembership,
    // setFcmToken, listMemberDevices.
    return new Response("not implemented", { status: 501 });
  }
}
