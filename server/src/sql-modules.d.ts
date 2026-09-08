/**
 * `migrations/*.sql` is imported as a text module (the `Text` rule in
 * wrangler.jsonc). This keeps the migration files themselves the single
 * source of truth required by G1, instead of copying their contents into
 * TypeScript string literals where the two could drift.
 */
declare module "*.sql" {
  const content: string;
  export default content;
}
