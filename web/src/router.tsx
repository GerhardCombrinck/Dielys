/**
 * The whole router: a handful of paths, no library. Android's Compose UI
 * deliberately has "no navigation library — the back stack is one nullable
 * list id" (docs/PLAN.md); this is the same call for the same reason — a
 * handful of screens do not earn a routing dependency (N1).
 */
import { useEffect, useState } from "react";

const NAVIGATE_EVENT = "dielys:navigate";

export function navigate(path: string): void {
  window.history.pushState(null, "", path);
  window.dispatchEvent(new Event(NAVIGATE_EVENT));
}

export function usePath(): string {
  const [path, setPath] = useState(() => window.location.pathname);

  useEffect(() => {
    const onChange = () => setPath(window.location.pathname);
    window.addEventListener("popstate", onChange);
    window.addEventListener(NAVIGATE_EVENT, onChange);
    return () => {
      window.removeEventListener("popstate", onChange);
      window.removeEventListener(NAVIGATE_EVENT, onChange);
    };
  }, []);

  return path;
}
