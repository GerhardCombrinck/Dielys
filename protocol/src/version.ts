/** Single source of truth for the wire protocol version. See CODE_STANDARD.md F2. */
export const PROTOCOL_VERSION = 2;

/**
 * Versions this server still serves. Old clients are assumed to exist forever
 * (F2) — a sideloaded APK in a drawer will reconnect one day. Dropping a
 * version from this list is a deliberate act with a rollout plan, not a
 * side effect of adding a new one.
 */
export const SUPPORTED_PROTOCOL_VERSIONS: readonly number[] = [2];
