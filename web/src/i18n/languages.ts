/**
 * South Africa's eleven official languages (#42) — the same set and tags as
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
  { tag: "nr", nativeName: "isiNdebele" },
  { tag: "xh", nativeName: "isiXhosa" },
  { tag: "zu", nativeName: "isiZulu" },
  { tag: "nso", nativeName: "Sepedi" },
  { tag: "st", nativeName: "Sesotho" },
  { tag: "ss", nativeName: "siSwati" },
  { tag: "tn", nativeName: "Setswana" },
  { tag: "ve", nativeName: "Tshivenda" },
  { tag: "ts", nativeName: "Xitsonga" },
];

export type LanguageTag = (typeof APP_LANGUAGES)[number]["tag"];
