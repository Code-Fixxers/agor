import { describe, expect, it } from 'vitest';
import { getLegacyMobileRedirectPath } from './legacyMobileRedirect';

describe('getLegacyMobileRedirectPath', () => {
  it('does not redirect canonical routes', () => {
    expect(getLegacyMobileRedirectPath('/')).toBeNull();
    expect(getLegacyMobileRedirectPath('/b/board-1/')).toBeNull();
    expect(getLegacyMobileRedirectPath('/b/board-1/session-1/')).toBeNull();
  });

  it('redirects legacy mobile root routes to canonical root', () => {
    expect(getLegacyMobileRedirectPath('/m')).toBe('/');
    expect(getLegacyMobileRedirectPath('/m/')).toBe('/');
    expect(getLegacyMobileRedirectPath('/m/session/sess-123')).toBe('/');
  });

  it('redirects legacy comments routes to canonical board path', () => {
    expect(getLegacyMobileRedirectPath('/m/comments/board-abc')).toBe('/b/board-abc/');
    expect(getLegacyMobileRedirectPath('/m/comments/board-abc/')).toBe('/b/board-abc/');
  });
});
