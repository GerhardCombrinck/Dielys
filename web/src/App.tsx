import { SessionProvider, useSession } from "./auth/SessionContext.js";
import { HomePage } from "./pages/HomePage.js";
import { InvitePage } from "./pages/InvitePage.js";
import { ListPage } from "./pages/ListPage.js";
import { MagicLinkPage } from "./pages/MagicLinkPage.js";
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

  if (session.status === "loading") return <p>Loading...</p>;
  if (session.status === "signed-out") return <SignInPage />;

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
