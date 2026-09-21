/**
 * Lightweight i18n (#42) — no external library, since the message set is
 * small and fixed at build time. Never synced (`android/.../LocalePrefs`'s
 * web counterpart): two people on the same shared list may read it in
 * different languages.
 */
import { createContext, type ReactNode, useCallback, useContext, useMemo, useState } from "react";
import { APP_LANGUAGES, type LanguageTag } from "./languages.js";
import { af } from "./messages/af.js";
import { en, type MessageKey, type Messages } from "./messages/en.js";
import { nr } from "./messages/nr.js";
import { nso } from "./messages/nso.js";
import { ss } from "./messages/ss.js";
import { st } from "./messages/st.js";
import { tn } from "./messages/tn.js";
import { ts } from "./messages/ts.js";
import { ve } from "./messages/ve.js";
import { xh } from "./messages/xh.js";
import { zu } from "./messages/zu.js";

const MESSAGES: Record<LanguageTag, Messages> = { af, en, nr, xh, zu, nso, st, ss, tn, ve, ts };

const LANGUAGE_KEY = "dielys.languageTag";

function isLanguageTag(value: string): value is LanguageTag {
  return value in MESSAGES;
}

/** The phone/browser's own language list, matched against what this app
 * supports — the same "follow the system" behaviour as a null `LocalePrefs`
 * tag on Android, falling back to English when nothing matches. */
function detectSystemLanguage(): LanguageTag {
  const candidates =
    typeof navigator === "undefined" ? [] : (navigator.languages ?? [navigator.language]);
  for (const candidate of candidates) {
    const base = candidate.split("-")[0]?.toLowerCase() ?? "";
    if (isLanguageTag(base)) return base;
  }
  return "en";
}

/** Null means "system default" — never written to storage as a literal
 * value, so a later change to the phone's own language keeps following it. */
export function getChosenLanguageTag(): LanguageTag | null {
  try {
    const raw = localStorage.getItem(LANGUAGE_KEY);
    return raw !== null && isLanguageTag(raw) ? raw : null;
  } catch {
    return null;
  }
}

function setChosenLanguageTag(tag: LanguageTag | null): void {
  try {
    if (tag === null) localStorage.removeItem(LANGUAGE_KEY);
    else localStorage.setItem(LANGUAGE_KEY, tag);
  } catch {
    // Best-effort — the choice just does not stick past this page load.
  }
}

type Params = Record<string, string | number>;

function format(template: string, params?: Params): string {
  if (params === undefined) return template;
  return template.replace(/\{(\w+)\}/g, (match, key: string) => {
    const value = params[key];
    return value === undefined ? match : String(value);
  });
}

interface I18nValue {
  /** The tag actually in effect — `chosenTag`, or the detected system one. */
  languageTag: LanguageTag;
  /** What the person picked, or null for "System default". */
  chosenTag: LanguageTag | null;
  setLanguage: (tag: LanguageTag | null) => void;
  t: (key: MessageKey, params?: Params) => string;
  /** Android's own plurals only ever define "one"/"other" (see
   * `sync_stuck_detail` etc.), so this does the same rather than modelling
   * every language's full plural-category grammar. */
  plural: (count: number, oneKey: MessageKey, otherKey: MessageKey, params?: Params) => string;
}

const I18nContext = createContext<I18nValue | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  const [chosenTag, setChosenTag] = useState<LanguageTag | null>(getChosenLanguageTag);
  const languageTag = chosenTag ?? detectSystemLanguage();
  const messages = MESSAGES[languageTag] ?? en;

  const setLanguage = useCallback((tag: LanguageTag | null) => {
    setChosenLanguageTag(tag);
    setChosenTag(tag);
  }, []);

  const t = useCallback(
    (key: MessageKey, params?: Params) => format(messages[key], params),
    [messages],
  );

  const plural = useCallback(
    (count: number, oneKey: MessageKey, otherKey: MessageKey, params?: Params) =>
      format(messages[count === 1 ? oneKey : otherKey], { n: count, ...params }),
    [messages],
  );

  const value = useMemo<I18nValue>(
    () => ({ languageTag, chosenTag, setLanguage, t, plural }),
    [languageTag, chosenTag, setLanguage, t, plural],
  );

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18nValue {
  const ctx = useContext(I18nContext);
  if (ctx === null) throw new Error("useI18n must be used within I18nProvider");
  return ctx;
}

export type { LanguageTag };
export { APP_LANGUAGES };
