/**
 * One shared HTML shell for every transactional email this server sends.
 * Inline styles throughout — many mail clients (Gmail chief among them)
 * strip `<style>` blocks or scope them unpredictably, so anything that must
 * render has to sit directly on the element (E1.1's reasoning, applied to
 * HTML mail instead of Compose).
 *
 * Deliberately plain: one accent color, one card, one button. Nothing here
 * needs a design system — it needs to not look like an unstyled `<p>` tag,
 * which is the entire gap this file closes.
 */

const ACCENT = "#2f6f4f";
const TEXT = "#1a1a1a";
const MUTED = "#6b6b6b";
const BORDER = "#e5e5e5";
const BACKGROUND = "#f4f4f2";

/** Escapes text that lands inside the HTML body — `listTitle` is the only
 * caller-supplied string that reaches this template, and it is display text
 * the owner controls, not markup. */
export function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

export interface EmailContent {
  /** Short heading at the top of the card. Not escaped — callers pass a
   * fixed string, never user content. */
  heading: string;
  /** Body paragraphs, already-escaped HTML (use `escapeHtml` on any
   * interpolated value before building this). One `<p>` per paragraph. */
  bodyHtml: string;
  buttonText: string;
  buttonUrl: string;
  /** Small print under the button — why this arrived, what to do if it's
   * unwanted. Already-escaped HTML, same as `bodyHtml`. */
  footerNote: string;
}

export function renderEmailHtml(content: EmailContent): string {
  return `<!doctype html>
<html>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Dielys</title>
  </head>
  <body style="margin:0;padding:0;background:${BACKGROUND};font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;">
    <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:${BACKGROUND};padding:32px 16px;">
      <tr>
        <td align="center">
          <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:480px;background:#ffffff;border:1px solid ${BORDER};border-radius:12px;overflow:hidden;">
            <tr>
              <td style="padding:28px 32px 4px;">
                <span style="font-size:15px;font-weight:700;color:${ACCENT};letter-spacing:0.02em;">Dielys</span>
              </td>
            </tr>
            <tr>
              <td style="padding:12px 32px 0;">
                <h1 style="margin:0;font-size:20px;line-height:1.3;color:${TEXT};">${content.heading}</h1>
              </td>
            </tr>
            <tr>
              <td style="padding:12px 32px 0;font-size:15px;line-height:1.55;color:${TEXT};">
                ${content.bodyHtml}
              </td>
            </tr>
            <tr>
              <td style="padding:24px 32px 8px;">
                <a href="${content.buttonUrl}" style="display:inline-block;background:${ACCENT};color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;padding:12px 24px;border-radius:8px;">${content.buttonText}</a>
              </td>
            </tr>
            <tr>
              <td style="padding:16px 32px 28px;font-size:13px;line-height:1.5;color:${MUTED};border-top:1px solid ${BORDER};margin-top:8px;">
                ${content.footerNote}
              </td>
            </tr>
          </table>
        </td>
      </tr>
    </table>
  </body>
</html>`;
}
