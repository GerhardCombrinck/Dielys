/**
 * The wake push (CODE_STANDARD.md M1). Declared here like everything else that
 * crosses the boundary (F1), because the server writes it and the Android
 * client reads it.
 *
 * FCM exists here for exactly one reason: wake a backgrounded app so its
 * outbox drain and catch-up pull can run. It is not a notification system and
 * it is not a transport for data. Nothing in this file may ever grow a field
 * that carries a task title, a list name, or a note body — FCM's servers are
 * as much "outside this system" as a log aggregator is (D4).
 */

/** The only `type` a wake push carries. There is no second kind of push. */
export const PUSH_TYPE_SYNC = "sync";

/**
 * The entire payload, and the complete list of keys it may ever have.
 *
 * Sent as an FCM **data** message — never a `notification` payload, which
 * would put text on a lock screen that this system is not allowed to send.
 * Any user-visible notification is composed client-side from Room after the
 * sync the push triggered, never from the push itself.
 *
 * `seq` is a decimal string rather than a number because FCM's `data` field is
 * `map<string, string>` at the API level — there is no way to put a JSON
 * number in it. The client parses it back. That is a transport constraint on
 * the encoding of one field, not a relaxation of M1: the payload still carries
 * these three keys and nothing else.
 */
export interface SyncPushPayload {
  type: typeof PUSH_TYPE_SYNC;
  listId: string;
  /** The `seq` this change was assigned. Decimal, see above. */
  seq: string;
}

/**
 * An FCM registration token. Roughly 160 characters today, but Google has
 * changed the shape before, so the bound is generous — and still a bound (F3).
 */
export const MAX_FCM_TOKEN_LENGTH = 4096;

/**
 * `POST /devices/token`. The device the token belongs to is taken from the
 * access token's `deviceId` claim, not from the body: a client must not be
 * able to register a push token against somebody else's device id.
 */
export interface RegisterDeviceRequest {
  fcmToken: string;
}
