import { useEffect, useState } from 'react';

/**
 * Watches the active service worker registration and exposes whether a new
 * version is waiting to take over. Calling `applyUpdate` tells the waiting
 * SW to skip waiting and reloads the page.
 */
export function usePWAUpdate() {
  const [waitingWorker, setWaitingWorker] = useState<ServiceWorker | null>(null);

  useEffect(() => {
    if (!('serviceWorker' in navigator)) return;

    let cancelled = false;

    const trackRegistration = (registration: ServiceWorkerRegistration) => {
      if (cancelled) return;

      if (registration.waiting) {
        setWaitingWorker(registration.waiting);
      }

      registration.addEventListener('updatefound', () => {
        const installing = registration.installing;
        if (!installing) return;
        installing.addEventListener('statechange', () => {
          if (installing.state === 'installed' && navigator.serviceWorker.controller) {
            setWaitingWorker(installing);
          }
        });
      });
    };

    navigator.serviceWorker
      .getRegistration()
      .then((reg) => reg && trackRegistration(reg))
      .catch(() => undefined);

    let reloaded = false;
    const onControllerChange = () => {
      if (reloaded) return;
      reloaded = true;
      window.location.reload();
    };
    navigator.serviceWorker.addEventListener('controllerchange', onControllerChange);

    return () => {
      cancelled = true;
      navigator.serviceWorker.removeEventListener('controllerchange', onControllerChange);
    };
  }, []);

  const applyUpdate = () => {
    waitingWorker?.postMessage('SKIP_WAITING');
  };

  return {
    updateReady: waitingWorker !== null,
    applyUpdate,
  };
}
