import type { Session, Worktree } from '@agor-live/client';
import { useEffect } from 'react';
import { Navigate, Route, Routes, useNavigate, useParams } from 'react-router-dom';

/**
 * Handles legacy `/m/...` URLs that pointed at the trimmed-down mobile shell
 * (PWA installs, bookmarks, deep links from Slack/email, etc).
 *
 * The full Agor UI now renders on every device, so we just redirect each old
 * mobile-only path to its canonical desktop equivalent. Anything we don't
 * recognize falls back to the landing route.
 */
interface Props {
  sessionById: Map<string, Session>;
  worktreeById: Map<string, Worktree>;
}

function SessionRedirect({ sessionById, worktreeById }: Props) {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();

  useEffect(() => {
    if (!sessionId) {
      navigate('/', { replace: true });
      return;
    }
    const session = sessionById.get(sessionId);
    const worktree = session?.worktree_id ? worktreeById.get(session.worktree_id) : undefined;
    const boardId = worktree?.board_id;
    if (boardId) {
      navigate(`/b/${boardId}/${sessionId}/`, { replace: true });
    } else {
      // Fall back to landing — session wasn't found in current state yet.
      navigate('/', { replace: true });
    }
  }, [sessionId, sessionById, worktreeById, navigate]);

  return null;
}

function CommentsRedirect() {
  const { boardId } = useParams<{ boardId: string }>();
  return <Navigate to={boardId ? `/b/${boardId}/` : '/'} replace />;
}

export function MobileLegacyRedirect({ sessionById, worktreeById }: Props) {
  return (
    <Routes>
      <Route
        path="session/:sessionId"
        element={<SessionRedirect sessionById={sessionById} worktreeById={worktreeById} />}
      />
      <Route path="comments/:boardId" element={<CommentsRedirect />} />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
