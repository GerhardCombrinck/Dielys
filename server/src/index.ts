/**
 * Worker entry. Routing and auth only, no business logic (D1) — authenticate,
 * resolve which DO to talk to, forward.
 */
import { ListRoom } from "./do/ListRoom.js";
import { UsersRoom } from "./do/UsersRoom.js";

export { ListRoom, UsersRoom };

export default {
  async fetch(_request: Request, _env: Env): Promise<Response> {
    // TODO: verify access token (auth/jwt.ts), check membership via
    // UsersRoom (L3), then resolve env.LIST_ROOM.idFromName(listId) and
    // forward.
    return new Response("not implemented", { status: 501 });
  },
} satisfies ExportedHandler<Env>;
