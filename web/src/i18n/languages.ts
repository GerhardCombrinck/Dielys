/**
 * The languages the app is translated into (#42) — the same set and tags as
 * `android/.../ui/settings/AppLanguage.kt`, alphabetical by each language's
 * own name so no language reads above another in its own picker.
 */
export interface AppLanguage {
  tag: string;
  nativeName: string;
}

export const APP_LANGUAGES: readonly AppLanguage[] = [
  { tag: "af", nativeName: "Afrikaans" },
  { tag: "en", nativeName: "English" },
];

export type LanguageTag = (typeof APP_LANGUAGES)[number]["tag"];
