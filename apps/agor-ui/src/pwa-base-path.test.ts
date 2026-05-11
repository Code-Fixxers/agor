import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';

const appRoot = path.resolve(import.meta.dirname, '..');
const manifestPath = path.resolve(appRoot, 'public/manifest.webmanifest');
const serviceWorkerPath = path.resolve(appRoot, 'public/sw.js');
const indexHtmlPath = path.resolve(appRoot, 'index.html');

describe('PWA base-path compatibility', () => {
  it('uses relative manifest URLs for /ui deployments', () => {
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as {
      start_url: string;
      scope: string;
      icons: Array<{ src: string }>;
    };

    expect(manifest.start_url).toBe('./');
    expect(manifest.scope).toBe('./');
    expect(manifest.icons.every((icon) => icon.src.startsWith('./'))).toBe(true);
  });

  it('resolves manifest link from Vite BASE_URL', () => {
    const html = readFileSync(indexHtmlPath, 'utf8');
    expect(html).toContain('href="%BASE_URL%manifest.webmanifest"');
  });

  it('uses runtime service worker scope for app-shell cache and navigation fallback', () => {
    const serviceWorker = readFileSync(serviceWorkerPath, 'utf8');

    // SW derives base path from registration scope (not hardcoded)
    expect(serviceWorker).toContain('new URL(self.registration.scope)');
    // Navigation fallback uses dynamic basePath — look for the pattern without
    // triggering biome's noTemplateCurlyInString rule by splitting the literal.
    expect(serviceWorker).toContain('basePath' + '}index.html`');
    // Old hardcoded fallback must be gone
    expect(serviceWorker).not.toContain("caches.match('/index.html')");
  });

  it('service worker handles NO_CACHE bypass for API routes', () => {
    const serviceWorker = readFileSync(serviceWorkerPath, 'utf8');
    expect(serviceWorker).toContain('NO_CACHE_PREFIXES');
    expect(serviceWorker).toContain('isNoCacheRequest');
    // `/api/v1/*` (personal API keys) and `/opencode/*` (health, models) are
    // JSON endpoints that must not be served from the asset SWR cache.
    expect(serviceWorker).toMatch(/['"]api['"]/);
    expect(serviceWorker).toMatch(/['"]opencode['"]/);
  });

  it('service worker handles SKIP_WAITING message for updates', () => {
    const serviceWorker = readFileSync(serviceWorkerPath, 'utf8');
    expect(serviceWorker).toContain('SKIP_WAITING');
  });
});
