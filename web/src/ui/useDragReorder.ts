/**
 * Drag-to-reorder for a column of rows — the web counterpart of Android's
 * `ReorderState` (`android/.../ui/reorder/Reorder.kt`), and the same feel:
 * the picked-up row lifts and follows the pointer, the rows it passes slide
 * out of its way as it goes, and on release it settles into its slot.
 *
 * Pointer events rather than HTML5 drag-and-drop: the latter drags a
 * translucent snapshot, never moves the other rows, and does nothing at all on
 * a touch screen. Here the handle starts a drag straight away (mouse or
 * finger); anywhere else on the row takes a small mouse movement, or a long
 * press on touch, so a finger swiping past still scrolls the page.
 *
 * Online-first (web/AGENTS.md) means a drop only reaches the screen once the
 * server acks it — a round trip in which the row would snap back to where it
 * came from. So the dropped order is held as a draft until the server's order
 * agrees with it, exactly like Android's draft-until-Room-agrees.
 */
import {
  type CSSProperties,
  type KeyboardEvent as ReactKeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type PointerEvent as ReactPointerEvent,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { draftStillWanted, dropTarget, moved, neighborsOf } from "./reorder.js";

type MoveFn = (id: string, afterId: string | null, beforeId: string | null) => Promise<void>;

/** What the rows render from; everything else lives in [Session]. */
type DragView = {
  id: string;
  from: number;
  to: number;
  /** Pixels the dragged row is shifted from its own slot. */
  dy: number;
  /** How far a passed-over row slides to make room: the dragged height plus the gap. */
  step: number;
  settling: boolean;
};

type Session = {
  id: string;
  pointerId: number;
  ids: string[];
  from: number;
  startX: number;
  startY: number;
  startScroll: number;
  lastY: number;
  /** Began on the handle, which starts a drag on the slightest movement. */
  onHandle: boolean;
  active: boolean;
  /** Each row's top and height in page coordinates, captured at pickup. */
  rects: { top: number; height: number }[];
  longPress: number | undefined;
  raf: number | undefined;
};

const HANDLE_SLOP_PX = 3;
const MOUSE_SLOP_PX = 6;
const TOUCH_SLOP_PX = 8;
const LONG_PRESS_MS = 350;
const SETTLE_MS = 180;
const EDGE_PX = 64;
const MAX_SCROLL_PX_PER_FRAME = 14;

export function useDragReorder(storedIds: readonly string[], onMove: MoveFn) {
  const [draft, setDraft] = useState<string[] | null>(null);
  const [view, setView] = useState<DragView | null>(null);
  const viewRef = useRef(view);
  viewRef.current = view;
  const rows = useRef(new Map<string, HTMLElement>());
  const session = useRef<Session | null>(null);
  const moveRef = useRef(onMove);
  moveRef.current = onMove;

  const draftWanted = draft !== null && draftStillWanted(draft, storedIds);
  const order = draftWanted ? draft : storedIds;
  const orderRef = useRef(order);
  orderRef.current = order;

  // Once the server agrees (or something else changed the rows), let go of it.
  useEffect(() => {
    if (draft !== null && !draftWanted) setDraft(null);
  }, [draft, draftWanted]);

  const commit = useCallback((ids: readonly string[], from: number, to: number) => {
    if (from === to) return;
    const next = moved(ids, from, to);
    const id = next[to] as string;
    const { afterId, beforeId } = neighborsOf(next, to);
    setDraft(next);
    moveRef.current(id, afterId, beforeId).catch(() => setDraft(null));
  }, []);

  const update = useCallback(() => {
    const s = session.current;
    if (s === null || !s.active) return;
    const { rects, from } = s;
    const self = rects[from];
    const first = rects[0];
    const last = rects[rects.length - 1];
    if (self === undefined || first === undefined || last === undefined) return;
    const raw = s.lastY + window.scrollY - (s.startY + s.startScroll);
    // Kept within the column, so the row never floats off past either end.
    const dy = Math.min(
      Math.max(raw, first.top - self.top),
      last.top + last.height - (self.top + self.height),
    );
    const centers = rects.map((r) => r.top + r.height / 2);
    const to = dropTarget(centers, from, self.top + self.height / 2 + dy);
    setView((v) => (v === null ? v : { ...v, dy, to }));
  }, []);

  const teardown = useCallback(() => {
    const s = session.current;
    if (s !== null) {
      window.clearTimeout(s.longPress);
      if (s.raf !== undefined) cancelAnimationFrame(s.raf);
    }
    session.current = null;
    document.body.classList.remove("reordering");
  }, []);

  /** Runs every frame while a drag is live — a pointer parked at the edge with
   *  no further movement still has to keep the page coming (#45's lesson). */
  const autoScroll = useCallback(() => {
    const s = session.current;
    if (s === null || !s.active) return;
    const intoTop = EDGE_PX - s.lastY;
    const intoBottom = s.lastY - (window.innerHeight - EDGE_PX);
    const delta =
      intoTop > 0
        ? -MAX_SCROLL_PX_PER_FRAME * Math.min(intoTop / EDGE_PX, 1)
        : intoBottom > 0
          ? MAX_SCROLL_PX_PER_FRAME * Math.min(intoBottom / EDGE_PX, 1)
          : 0;
    if (delta !== 0) {
      window.scrollBy(0, delta);
      update();
    }
    s.raf = requestAnimationFrame(autoScroll);
  }, [update]);

  const activate = useCallback(
    (touch: boolean) => {
      const s = session.current;
      if (s === null || s.active) return;
      const rects = s.ids.map((id) => {
        const r = rows.current.get(id)?.getBoundingClientRect();
        return { top: (r?.top ?? 0) + window.scrollY, height: r?.height ?? 0 };
      });
      const self = rects[s.from];
      const next = rects[s.from + 1] ?? rects[s.from - 1];
      if (self === undefined) return;
      const gap =
        next === undefined
          ? 0
          : next.top > self.top
            ? next.top - (self.top + self.height)
            : self.top - (next.top + next.height);
      s.active = true;
      s.rects = rects;
      document.body.classList.add("reordering");
      if (touch) navigator.vibrate?.(10);
      setView({
        id: s.id,
        from: s.from,
        to: s.from,
        dy: 0,
        step: self.height + gap,
        settling: false,
      });
      s.raf = requestAnimationFrame(autoScroll);
    },
    [autoScroll],
  );

  /** Glides the row into [to]'s slot, then swaps the real order in underneath. */
  const settle = useCallback(
    (to: number) => {
      const s = session.current;
      if (s === null) return;
      const { rects, from, ids } = s;
      const self = rects[from];
      const target = rects[to];
      teardown();
      if (self === undefined || target === undefined) {
        setView(null);
        return;
      }
      const dy =
        to > from
          ? target.top + target.height - self.height - self.top
          : to < from
            ? target.top - self.top
            : 0;
      setView((v) => (v === null ? v : { ...v, to, dy, settling: true }));
      window.setTimeout(() => {
        commit(ids, from, to);
        setView(null);
      }, SETTLE_MS);
    },
    [commit, teardown],
  );

  useEffect(() => {
    function onPointerMove(e: PointerEvent): void {
      const s = session.current;
      if (s === null || e.pointerId !== s.pointerId) return;
      s.lastY = e.clientY;
      if (s.active) {
        update();
        return;
      }
      const moved = Math.hypot(e.clientX - s.startX, e.clientY - s.startY);
      if (s.longPress !== undefined) {
        // A touch that travels before the long press lands is a scroll.
        if (moved > TOUCH_SLOP_PX) teardown();
      } else if (moved > (s.onHandle ? HANDLE_SLOP_PX : MOUSE_SLOP_PX)) {
        activate(e.pointerType === "touch");
        update();
      }
    }

    function onPointerUp(e: PointerEvent): void {
      const s = session.current;
      if (s === null || e.pointerId !== s.pointerId) return;
      if (!s.active) {
        teardown();
        return;
      }
      // The release lands on whatever sits under the pointer — usually the
      // row's own title button, which would otherwise open the list.
      window.addEventListener("click", swallow, { capture: true, once: true });
      window.setTimeout(() => window.removeEventListener("click", swallow, true), 0);
      settle(viewRef.current?.to ?? s.from);
    }

    function onCancel(e: PointerEvent | KeyboardEvent): void {
      const s = session.current;
      if (s === null) return;
      if (e instanceof KeyboardEvent && e.key !== "Escape") return;
      if (s.active) settle(s.from);
      else teardown();
    }

    // Once a drag is live, a finger's movement is the drag, not a page scroll.
    function onTouchMove(e: TouchEvent): void {
      if (session.current?.active) e.preventDefault();
    }

    window.addEventListener("pointermove", onPointerMove);
    window.addEventListener("pointerup", onPointerUp);
    window.addEventListener("pointercancel", onCancel);
    window.addEventListener("keydown", onCancel);
    window.addEventListener("touchmove", onTouchMove, { passive: false });
    return () => {
      window.removeEventListener("pointermove", onPointerMove);
      window.removeEventListener("pointerup", onPointerUp);
      window.removeEventListener("pointercancel", onCancel);
      window.removeEventListener("keydown", onCancel);
      window.removeEventListener("touchmove", onTouchMove);
      teardown();
    };
  }, [activate, settle, teardown, update]);

  function onPointerDown(e: ReactPointerEvent<HTMLElement>, id: string): void {
    if (e.button !== 0 || session.current !== null || view !== null) return;
    const target = e.target as Element;
    if (target.closest("input, textarea, .menu-popover, .menu-overlay") !== null) return;
    const ids = [...orderRef.current];
    const from = ids.indexOf(id);
    if (from === -1 || ids.length < 2) return;
    const onHandle = target.closest(".drag-handle") !== null;
    const touch = e.pointerType === "touch";
    session.current = {
      id,
      pointerId: e.pointerId,
      ids,
      from,
      startX: e.clientX,
      startY: e.clientY,
      startScroll: window.scrollY,
      lastY: e.clientY,
      onHandle,
      active: false,
      rects: [],
      longPress:
        touch && !onHandle ? window.setTimeout(() => activate(true), LONG_PRESS_MS) : undefined,
      raf: undefined,
    };
  }

  function onKeyDown(e: ReactKeyboardEvent<HTMLElement>, id: string): void {
    // Alt+↑/↓ — the keyboard's way to reorder, since a drag needs a pointer.
    if (!e.altKey || (e.key !== "ArrowUp" && e.key !== "ArrowDown")) return;
    const ids = orderRef.current;
    const from = ids.indexOf(id);
    const to = from + (e.key === "ArrowUp" ? -1 : 1);
    if (from === -1 || to < 0 || to >= ids.length) return;
    e.preventDefault();
    commit(ids, from, to);
  }

  /** Spread onto each row. */
  function rowProps(id: string) {
    const index = order.indexOf(id);
    let transform: string | undefined;
    let state: "lifted" | "settling" | "shifting" | undefined;
    if (view !== null) {
      if (id === view.id) {
        transform = `translateY(${view.dy}px)`;
        state = view.settling ? "settling" : "lifted";
      } else {
        state = "shifting";
        if (view.from < index && index <= view.to) transform = `translateY(${-view.step}px)`;
        else if (view.to <= index && index < view.from) transform = `translateY(${view.step}px)`;
      }
    }
    return {
      ref: (el: HTMLElement | null) => {
        if (el === null) rows.current.delete(id);
        else rows.current.set(id, el);
      },
      "data-drag": state,
      style: transform === undefined ? undefined : ({ transform } as CSSProperties),
      onPointerDown: (e: ReactPointerEvent<HTMLElement>) => onPointerDown(e, id),
      onKeyDown: (e: ReactKeyboardEvent<HTMLElement>) => onKeyDown(e, id),
      // A long press on touch otherwise opens the browser's own menu.
      onContextMenu: (e: ReactMouseEvent) => {
        if (session.current !== null) e.preventDefault();
      },
    };
  }

  return { order, rowProps };
}

function swallow(e: MouseEvent): void {
  e.preventDefault();
  e.stopPropagation();
}
