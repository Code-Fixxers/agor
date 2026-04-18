/**
 * Temporary compatibility mapping for legacy `/m/*` links.
 *
 * The canonical app surface is now shared across all devices. These mappings
 * let old mobile URLs resolve without preserving a split route ownership model.
 */
export function getLegacyMobileRedirectPath(pathname: string): string | null {
  if (!pathname.startsWith('/m')) {
    return null;
  }

  const commentsMatch = pathname.match(/^\/m\/comments\/([^/]+)\/?$/);
  if (commentsMatch) {
    const boardId = commentsMatch[1];
    return `/b/${boardId}/`;
  }

  return '/';
}
