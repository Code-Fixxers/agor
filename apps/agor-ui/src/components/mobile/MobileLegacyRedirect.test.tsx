import type { Session, Worktree } from '@agor-live/client';
import { render } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { MobileLegacyRedirect } from './MobileLegacyRedirect';

/**
 * Capture the current location after rendering so we can assert the
 * redirect destination without needing jsdom navigation support.
 */
function LocationCapture({ onCapture }: { onCapture: (path: string) => void }) {
  const location = useLocation();
  onCapture(location.pathname);
  return null;
}

function renderWithRouter(
  initialPath: string,
  sessionById: Map<string, Session>,
  worktreeById: Map<string, Worktree>
): string {
  let captured = '';

  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        {/* The /m/* route mirrors how App.tsx registers MobileLegacyRedirect */}
        <Route
          path="/m/*"
          element={<MobileLegacyRedirect sessionById={sessionById} worktreeById={worktreeById} />}
        />
        {/* Catch-all — records where we ended up after the redirect */}
        <Route path="*" element={<LocationCapture onCapture={(p) => (captured = p)} />} />
      </Routes>
    </MemoryRouter>
  );

  return captured;
}

const emptyMaps = {
  sessionById: new Map<string, Session>(),
  worktreeById: new Map<string, Worktree>(),
};

describe('MobileLegacyRedirect', () => {
  it('redirects /m root to /', () => {
    const dest = renderWithRouter('/m', emptyMaps.sessionById, emptyMaps.worktreeById);
    expect(dest).toBe('/');
  });

  it('redirects /m/ to /', () => {
    const dest = renderWithRouter('/m/', emptyMaps.sessionById, emptyMaps.worktreeById);
    expect(dest).toBe('/');
  });

  it('redirects /m/comments/:boardId to /b/:boardId/', () => {
    const dest = renderWithRouter(
      '/m/comments/board-abc',
      emptyMaps.sessionById,
      emptyMaps.worktreeById
    );
    expect(dest).toBe('/b/board-abc/');
  });

  it('redirects /m/session/:id with known session to /b/:boardId/:sessionId/', () => {
    const sessionId = 'sess-123';
    const worktreeId = 'wt-456';
    const boardId = 'board-789';

    const sessionById = new Map<string, Session>([
      [
        sessionId,
        {
          session_id: sessionId,
          worktree_id: worktreeId,
        } as unknown as Session,
      ],
    ]);

    const worktreeById = new Map<string, Worktree>([
      [
        worktreeId,
        {
          worktree_id: worktreeId,
          board_id: boardId,
        } as unknown as Worktree,
      ],
    ]);

    const dest = renderWithRouter(`/m/session/${sessionId}`, sessionById, worktreeById);
    expect(dest).toBe(`/b/${boardId}/${sessionId}/`);
  });

  it('redirects /m/session/:id with missing session to /', () => {
    const dest = renderWithRouter(
      '/m/session/nonexistent-id',
      emptyMaps.sessionById,
      emptyMaps.worktreeById
    );
    expect(dest).toBe('/');
  });
});
