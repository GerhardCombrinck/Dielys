/**
 * Pulls one list's full changelog into `state`, paging past
 * `CATCH_UP_PAGE_SIZE` (protocol/src/types.ts's `CatchUpResponse.truncated`)
 * when a list has more history than one page holds. Shared by the lists
 * overview (title + item count) and the task board (the whole point).
 */
import { catchUp } from "../api/lists.js";
import { applyChange, type ListState } from "./applyChange.js";

/** Returns the highest seq seen — the cursor a live socket should hello with. */
export async function hydrateList(listId: string, state: ListState): Promise<number> {
  let since = 0;
  let maxSeq = 0;
  for (;;) {
    const response = await catchUp(listId, since);
    for (const change of response.changes) applyChange(state, change);
    maxSeq = response.maxSeq;
    if (!response.truncated) break;
    const last = response.changes.at(-1);
    if (last === undefined) break;
    since = last.seq;
  }
  return maxSeq;
}
