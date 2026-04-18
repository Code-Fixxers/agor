import type {
  Board,
  BoardComment,
  BoardEntityObject,
  Repo,
  Session,
  Worktree,
} from '@agor-live/client';
import {
  CommentOutlined,
  EnvironmentOutlined,
  HistoryOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  TableOutlined,
} from '@ant-design/icons';
import {
  Badge,
  Button,
  Card,
  Collapse,
  Empty,
  List,
  Segmented,
  Space,
  Tag,
  Typography,
  theme,
} from 'antd';
import { useMemo, useState } from 'react';
import { mapToArray } from '@/utils/mapHelpers';
import { getSessionDisplayTitle } from '@/utils/sessionTitle';

const { Text, Title } = Typography;

type Tab = 'worktrees' | 'sessions' | 'comments';

export interface MobileBoardViewProps {
  board: Board | null;
  worktrees: Worktree[];
  sessionsByWorktree: Map<string, Session[]>;
  commentById: Map<string, BoardComment>;
  boardObjectById: Map<string, BoardEntityObject>;
  repoById: Map<string, Repo>;
  onSessionClick: (sessionId: string) => void;
  onCreateSession: () => void;
  onOpenComments: () => void;
  onOpenWorktree: (worktreeId: string) => void;
  onCreateSessionForWorktree: (worktreeId: string) => void;
}

function statusIcon(status?: string): string {
  if (status === 'running') return '▶';
  if (status === 'completed') return '✓';
  if (status === 'failed') return '✕';
  return '⏸';
}

function statusColor(status?: string): string {
  if (status === 'running') return 'processing';
  if (status === 'completed') return 'success';
  if (status === 'failed') return 'error';
  return 'default';
}

/**
 * Mobile-friendly replacement for the desktop SessionCanvas.
 *
 * Canvas-based UIs are cumbersome on phones (drag, pinch-to-zoom on a
 * heavyweight React Flow scene). We instead expose the same data as plain
 * scrollable lists with a Segmented switcher: worktrees, sessions, comments.
 *
 * Tapping a session triggers the parent to open the SessionPanel as a
 * full-screen drawer (handled in App/App.tsx).
 */
export function MobileBoardView({
  board,
  worktrees,
  sessionsByWorktree,
  commentById,
  onSessionClick,
  onCreateSession,
  onOpenComments,
  onOpenWorktree,
  onCreateSessionForWorktree,
}: MobileBoardViewProps) {
  const { token } = theme.useToken();
  const [tab, setTab] = useState<Tab>('worktrees');

  // Sort worktrees by most recent session activity
  const sortedWorktrees = useMemo(() => {
    return [...worktrees].sort((a, b) => {
      const aMax = Math.max(
        ...(sessionsByWorktree.get(a.worktree_id) || []).map((s) =>
          new Date(s.last_updated).getTime()
        ),
        0
      );
      const bMax = Math.max(
        ...(sessionsByWorktree.get(b.worktree_id) || []).map((s) =>
          new Date(s.last_updated).getTime()
        ),
        0
      );
      return bMax - aMax;
    });
  }, [worktrees, sessionsByWorktree]);

  // Flat session list for the "Sessions" tab — most recent first
  const allSessions = useMemo(() => {
    const sessions: { session: Session; worktree?: Worktree }[] = [];
    for (const wt of worktrees) {
      const wtSessions = sessionsByWorktree.get(wt.worktree_id) || [];
      for (const s of wtSessions) {
        sessions.push({ session: s, worktree: wt });
      }
    }
    return sessions.sort(
      (a, b) =>
        new Date(b.session.last_updated).getTime() - new Date(a.session.last_updated).getTime()
    );
  }, [worktrees, sessionsByWorktree]);

  const activeComments = useMemo(
    () =>
      mapToArray(commentById).filter(
        (c) => c.board_id === board?.board_id && !c.resolved && !c.parent_comment_id
      ),
    [commentById, board?.board_id]
  );

  const headerBadge = (
    <Space size={6} wrap>
      <Tag color="blue" style={{ marginInlineEnd: 0 }}>
        {worktrees.length} worktree{worktrees.length === 1 ? '' : 's'}
      </Tag>
      <Tag color="purple" style={{ marginInlineEnd: 0 }}>
        {allSessions.length} session{allSessions.length === 1 ? '' : 's'}
      </Tag>
      {activeComments.length > 0 && (
        <Tag color="gold" style={{ marginInlineEnd: 0 }}>
          {activeComments.length} comment{activeComments.length === 1 ? '' : 's'}
        </Tag>
      )}
    </Space>
  );

  const renderSessionItem = (session: Session, worktree?: Worktree) => {
    const title = getSessionDisplayTitle(session, {
      fallbackChars: 60,
      includeIdFallback: true,
    });
    const needsAttention = !!worktree?.needs_attention || !!session.ready_for_prompt;
    return (
      <List.Item
        key={session.session_id}
        onClick={() => onSessionClick(session.session_id)}
        style={{
          padding: '12px 12px',
          cursor: 'pointer',
          borderRadius: 8,
          background: needsAttention ? `${token.colorWarningBg}` : undefined,
        }}
      >
        <div style={{ width: '100%' }}>
          <Space size={6} style={{ width: '100%', justifyContent: 'space-between' }}>
            <Space size={6} align="center">
              <Tag color={statusColor(session.status)} style={{ marginInlineEnd: 0 }}>
                {statusIcon(session.status)} {session.status || 'idle'}
              </Tag>
              {session.agentic_tool && (
                <Tag style={{ marginInlineEnd: 0 }}>{session.agentic_tool}</Tag>
              )}
            </Space>
            {needsAttention && (
              <Badge color={token.colorWarning} text={<Text type="warning">Attention</Text>} />
            )}
          </Space>
          <div style={{ marginTop: 6 }}>
            <Text strong style={{ fontSize: 14, lineHeight: 1.3 }}>
              {title}
            </Text>
          </div>
          <div style={{ marginTop: 4 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>
              {worktree?.name && `🌳 ${worktree.name}`}
              {session.model_config?.model && ` • ${session.model_config.model}`}
            </Text>
          </div>
        </div>
      </List.Item>
    );
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        height: '100%',
        overflow: 'hidden',
        background: token.colorBgLayout,
      }}
    >
      {/* Sticky header with board info & tabs */}
      <div
        style={{
          padding: '12px 12px 8px 12px',
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
          background: token.colorBgContainer,
        }}
      >
        <Space
          align="center"
          style={{ width: '100%', justifyContent: 'space-between', marginBottom: 8 }}
        >
          <div style={{ minWidth: 0 }}>
            <Title level={5} style={{ margin: 0, lineHeight: 1.2 }}>
              {board?.icon ? `${board.icon} ` : ''}
              {board?.name || 'Board'}
            </Title>
            <div style={{ marginTop: 4 }}>{headerBadge}</div>
          </div>
        </Space>

        <Segmented<Tab>
          block
          value={tab}
          onChange={(value) => setTab(value as Tab)}
          options={[
            {
              label: (
                <Space size={4}>
                  <EnvironmentOutlined /> Worktrees
                </Space>
              ),
              value: 'worktrees',
            },
            {
              label: (
                <Space size={4}>
                  <HistoryOutlined /> Sessions
                </Space>
              ),
              value: 'sessions',
            },
            {
              label: (
                <Badge count={activeComments.length} size="small" offset={[6, -4]}>
                  <Space size={4}>
                    <CommentOutlined /> Comments
                  </Space>
                </Badge>
              ),
              value: 'comments',
            },
          ]}
        />
      </div>

      {/* Scrollable content area */}
      <div
        style={{
          flex: 1,
          overflow: 'auto',
          padding: '12px 8px 96px 8px', // bottom padding leaves room for FAB
          WebkitOverflowScrolling: 'touch',
        }}
      >
        {tab === 'worktrees' && (
          <>
            {sortedWorktrees.length === 0 ? (
              <Card>
                <Empty description="No worktrees on this board yet." />
                <div style={{ textAlign: 'center', marginTop: 12 }}>
                  <Button type="primary" icon={<PlusOutlined />} onClick={onCreateSession}>
                    New worktree / session
                  </Button>
                </div>
              </Card>
            ) : (
              <Collapse
                accordion={false}
                ghost
                bordered={false}
                style={{ background: 'transparent' }}
                items={sortedWorktrees.map((wt) => {
                  const wtSessions = sessionsByWorktree.get(wt.worktree_id) || [];
                  const lastActivity = wtSessions.reduce(
                    (max, s) => Math.max(max, new Date(s.last_updated).getTime()),
                    0
                  );
                  return {
                    key: wt.worktree_id,
                    label: (
                      <Space
                        size={8}
                        align="center"
                        style={{ width: '100%', justifyContent: 'space-between' }}
                      >
                        <div style={{ minWidth: 0 }}>
                          <Space size={6} align="center">
                            <span>🌳</span>
                            <Text strong>{wt.name}</Text>
                            {wt.needs_attention && <Tag color="warning">Attention</Tag>}
                          </Space>
                          <div>
                            <Text type="secondary" style={{ fontSize: 12 }}>
                              {wtSessions.length} session{wtSessions.length === 1 ? '' : 's'}
                              {lastActivity > 0 &&
                                ` • last ${new Date(lastActivity).toLocaleString(undefined, {
                                  month: 'short',
                                  day: 'numeric',
                                  hour: '2-digit',
                                  minute: '2-digit',
                                })}`}
                            </Text>
                          </div>
                        </div>
                        <Button
                          type="text"
                          size="small"
                          icon={<TableOutlined />}
                          onClick={(e) => {
                            e.stopPropagation();
                            onOpenWorktree(wt.worktree_id);
                          }}
                        />
                      </Space>
                    ),
                    children: (
                      <>
                        {wtSessions.length === 0 ? (
                          <Empty
                            image={Empty.PRESENTED_IMAGE_SIMPLE}
                            description="No sessions yet"
                            style={{ marginBlock: 8 }}
                          />
                        ) : (
                          <List
                            split
                            dataSource={wtSessions
                              .slice()
                              .sort(
                                (a, b) =>
                                  new Date(b.last_updated).getTime() -
                                  new Date(a.last_updated).getTime()
                              )}
                            renderItem={(s) => renderSessionItem(s, wt)}
                          />
                        )}
                        <Button
                          type="dashed"
                          block
                          icon={<PlayCircleOutlined />}
                          style={{ marginTop: 8 }}
                          onClick={() => onCreateSessionForWorktree(wt.worktree_id)}
                        >
                          New session in {wt.name}
                        </Button>
                      </>
                    ),
                  };
                })}
              />
            )}
          </>
        )}

        {tab === 'sessions' && (
          <Card bordered={false} bodyStyle={{ padding: 0 }} style={{ background: 'transparent' }}>
            {allSessions.length === 0 ? (
              <Empty description="No sessions yet on this board" />
            ) : (
              <List
                split
                dataSource={allSessions}
                renderItem={({ session, worktree }) => renderSessionItem(session, worktree)}
              />
            )}
          </Card>
        )}

        {tab === 'comments' && (
          <Card bordered={false}>
            {activeComments.length === 0 ? (
              <Empty description="No active comments" />
            ) : (
              <Space orientation="vertical" size={8} style={{ width: '100%' }}>
                <Text type="secondary">
                  {activeComments.length} active comment
                  {activeComments.length === 1 ? '' : 's'}
                </Text>
                <Button block icon={<CommentOutlined />} onClick={onOpenComments}>
                  Open comments panel
                </Button>
              </Space>
            )}
          </Card>
        )}
      </div>

      {/* Floating action button */}
      <Button
        type="primary"
        shape="circle"
        size="large"
        icon={<PlusOutlined />}
        onClick={onCreateSession}
        style={{
          position: 'absolute',
          right: 16,
          bottom: `calc(16px + env(safe-area-inset-bottom, 0px))`,
          zIndex: 50,
          width: 56,
          height: 56,
          boxShadow: '0 4px 16px rgba(0, 0, 0, 0.4)',
        }}
        aria-label="Create new session"
      />
    </div>
  );
}
