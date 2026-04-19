import { useEffect, useRef } from 'react';

/**
 * Binds a drawer's open/close state to the browser history stack so the
 * system back gesture (Android, trackpad swipe, Esc in installed PWAs)
 * closes the drawer instead of navigating away from the board.
 *
 * - When the drawer opens and `enabled` is true, we push a marker state onto
 *   history (`{ agorDrawer: true, id }`).
 * - When `popstate` fires and the popped state carries that marker, we call
 *   `onClose()` and stop there (history already moved back).
 * - When the drawer is closed programmatically (open -> false) while our
 *   marker is still the top of history, we call `history.back()` so the
 *   forward/back stack stays consistent and a second back gesture doesn't
 *   dead-end on a stale marker.
 *
 * `enabled` should be tied to the compact-viewport flag so desktop
 * navigation is untouched.
 *
 * Each drawer passes a stable `id` so overlapping drawers (e.g. comments
 * opened on top of session) don't close one another.
 */
export function useMobileDrawerHistory(
  open: boolean,
  onClose: () => void,
  enabled: boolean,
  id: string
): void {
  const pushedRef = useRef(false);

  useEffect(() => {
    if (!enabled) return;
    if (open && !pushedRef.current) {
      window.history.pushState({ agorDrawer: true, id }, '');
      pushedRef.current = true;
    } else if (!open && pushedRef.current) {
      // Drawer closed programmatically — pop our marker so history stays clean.
      pushedRef.current = false;
      if (window.history.state?.agorDrawer && window.history.state?.id === id) {
        window.history.back();
      }
    }
  }, [open, enabled, id]);

  useEffect(() => {
    if (!enabled) return;
    const handler = (event: PopStateEvent) => {
      if (!pushedRef.current) return;
      // If our marker was the one popped, the new top state no longer has it
      // for our id. Close the drawer.
      const state = event.state as { agorDrawer?: boolean; id?: string } | null;
      if (!state?.agorDrawer || state.id !== id) {
        pushedRef.current = false;
        onClose();
      }
    };
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
  }, [enabled, id, onClose]);

  // Safety: if the component unmounts with the marker still in history,
  // drop the marker so we don't leave it orphaned.
  useEffect(() => {
    return () => {
      if (pushedRef.current && window.history.state?.agorDrawer) {
        window.history.back();
        pushedRef.current = false;
      }
    };
  }, []);
}
