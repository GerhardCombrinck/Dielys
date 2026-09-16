import { SessionProvider, useSession } from "./auth/SessionContext.js";
import { HomePage } from "./pages/HomePage.js";
import { MagicLinkPage } from "./pages/MagicLinkPage.js";
import { SignInPage } from "./pages/SignInPage.js";
import { usePath } from "./router.js";

function Routed() {
  const path = usePath();
  const session = useSession();

  if (path === "/magic") return <MagicLinkPage />;

  if (session.status === "loading") return <p>Loading...</p>;
  if (session.status === "signed-out") return <SignInPage />;
  return <HomePage />;
}

export function App() {
  return (
    <SessionProvider>
      <Routed />
    </SessionProvider>
  );
}
