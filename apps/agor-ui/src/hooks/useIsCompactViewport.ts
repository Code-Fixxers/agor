import { useEffect, useState } from 'react';

/**
 * Reactive media-query hook for "compact" viewports — phones and small tablets.
 *
 * A single source of truth so the UI doesn't disagree with itself about whether
 * to show the canvas (desktop) or the drawer-based browse layout (mobile).
 *
 * Threshold mirrors the device-detection breakpoint: <768px, plus an OR with
 * the `pointer: coarse` query so phones in landscape (>768px) also opt in.
 */
const COMPACT_QUERY = '(max-width: 767.5px), (pointer: coarse) and (max-width: 1023.5px)';

export function useIsCompactViewport(): boolean {
  const [compact, setCompact] = useState<boolean>(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia(COMPACT_QUERY).matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mq = window.matchMedia(COMPACT_QUERY);
    const handler = (event: MediaQueryListEvent) => setCompact(event.matches);
    setCompact(mq.matches);
    if (typeof mq.addEventListener === 'function') {
      mq.addEventListener('change', handler);
      return () => mq.removeEventListener('change', handler);
    }
    // Safari < 14 fallback
    mq.addListener(handler);
    return () => mq.removeListener(handler);
  }, []);

  return compact;
}
