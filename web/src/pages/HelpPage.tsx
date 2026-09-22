/**
 * How the app works, sharing above all — the one part nobody can work out by
 * poking at it, since half of it happens in somebody else's inbox.
 * `android/.../ui/help/HelpScreen.kt`'s web counterpart, same sections, same
 * strings.
 */
import { useEffect } from "react";
import { useI18n } from "../i18n/I18nContext.js";
import type { MessageKey } from "../i18n/messages/en.js";
import { navigate } from "../router.js";
import { BackChevronIcon } from "../ui/icons.js";

const SECTIONS: { id: string; title: MessageKey; body: MessageKey }[] = [
  { id: "lists", title: "help.listsTitle", body: "help.listsBody" },
  { id: "items", title: "help.itemsTitle", body: "help.itemsBody" },
  { id: "sharing", title: "help.sharingTitle", body: "help.sharingBody" },
  { id: "together", title: "help.togetherTitle", body: "help.togetherBody" },
  { id: "leaving", title: "help.leavingTitle", body: "help.leavingBody" },
  { id: "offline", title: "help.offlineTitle", body: "help.offlineBody" },
  { id: "account", title: "help.accountTitle", body: "help.accountBody" },
];

/** The glyph the lists page's row menu actually shows (HomePage.tsx). */
const MENU_GLYPH = "⋯";

export function HelpPage() {
  const { t } = useI18n();

  // "/help#sharing" from the share dialog lands on that section, not the top.
  useEffect(() => {
    const id = window.location.hash.slice(1);
    if (id !== "") document.getElementById(id)?.scrollIntoView();
  }, []);

  return (
    <div className="page">
      <header className="page-header">
        <button
          className="icon-button"
          type="button"
          aria-label={t("common.backToLists")}
          onClick={() => navigate("/")}
        >
          <BackChevronIcon />
        </button>
        <h1>{t("help.title")}</h1>
      </header>

      <div className="settings-cards">
        {SECTIONS.map((section) => (
          <section key={section.id} id={section.id} className="settings-card help-card">
            <h2>{t(section.title)}</h2>
            <p>{t(section.body, { menu: MENU_GLYPH })}</p>
          </section>
        ))}
      </div>
    </div>
  );
}
