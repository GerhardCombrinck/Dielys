/**
 * The footer under every web page, signed in or not — for now just the way
 * to the Android app, since the web client is the fallback, not the main
 * event (the phone is where lists get ticked off in the shop).
 */
import { useI18n } from "../i18n/I18nContext.js";
import { GooglePlayIcon } from "./icons.js";

/**
 * The internal-testing opt-in page, not the store listing: until the app is
 * in production, `/store/apps/details?id=za.co.dielys` 404s for anybody who
 * is not already a tester. Point this back at the store listing the day the
 * app goes public.
 */
export const PLAY_STORE_URL = "https://play.google.com/apps/internaltest/4701677917435163487";

export function GooglePlayLink({ className }: { className: string }) {
  const { t } = useI18n();
  return (
    <a className={className} href={PLAY_STORE_URL} target="_blank" rel="noopener noreferrer">
      <GooglePlayIcon />
      <span>{t("play.getApp")}</span>
    </a>
  );
}

export function AppFooter() {
  return (
    <footer className="app-footer">
      <GooglePlayLink className="app-footer-link" />
    </footer>
  );
}
