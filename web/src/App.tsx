import { SessionProvider, useSession } from "./auth/SessionContext.js";
import {
  ConfirmAccountDeletionPage,
  RequestAccountDeletionPage,
} from "./pages/DeleteAccountPage.js";
import { HomePage } from "./pages/HomePage.js";
import { InvitePage } from "./pages/InvitePage.js";
import { ListPage } from "./pages/ListPage.js";
import { MagicLinkPage } from "./pages/MagicLinkPage.js";
import { SettingsPage } from "./pages/SettingsPage.js";
import { SignInPage } from "./pages/SignInPage.js";
import { usePath } from "./router.js";

const LIST_PATH = /^\/lists\/([^/]+)$/;

function Routed() {
  const path = usePath();
  const session = useSession();

  if (path === "/magic") return <MagicLinkPage />;
  // Checked ahead of the sign-in gate below: a signed-out visitor still needs
  // this page to stash the token and send them to sign in (InvitePage.tsx).
  if (path === "/invite") return <InvitePage />;
  // Neither of these is gated on a session at all (ADR 0007): Play requires
  // account deletion to work without the app, which means without a session
  // here too — someone reaching either of these has, by definition, no
  // reason to be signed in on this browser.
  if (path === "/account/delete") return <RequestAccountDeletionPage />;
  if (path === "/account/delete/confirm") return <ConfirmAccountDeletionPage />;

  if (session.status === "loading") return <p>Loading...</p>;
  if (session.status === "signed-out") return <SignInPage />;

  if (path === "/settings") return <SettingsPage />;

  const list = LIST_PATH.exec(path);
  if (list !== null) return <ListPage listId={decodeURIComponent(list[1] as string)} />;

  return <HomePage />;
}

export function App() {
  return (
    <SessionProvider>
      <Routed />
    </SessionProvider>
  );
}
