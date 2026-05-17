const app = document.querySelector('#app');
let state = window.__AGOR_HARNESS_STATE__;
let streamTimer = null;

function selectedSession() {
  return state.snapshot.sessions.find((session) => session.sessionId === state.selectedSessionId);
}

function messagesForSelectedSession() {
  return [...(state.messagesBySession[state.selectedSessionId] || [])].sort(
    (a, b) => a.index - b.index
  );
}

function worktreesForBoard(boardId) {
  return state.snapshot.worktrees.filter((worktree) => worktree.boardId === boardId);
}

function sessionsForWorktree(worktreeId) {
  return state.snapshot.sessions.filter((session) => session.worktreeId === worktreeId);
}

function roleLabel(role) {
  if (role === 'USER') return 'You';
  if (role === 'ASSISTANT') return 'Agent';
  if (role === 'SYSTEM') return 'System';
  return 'Message';
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}

function render() {
  const session = selectedSession();
  const layoutClass = state.layout === 'stacked' ? 'stacked' : 'side-by-side';
  app.innerHTML = `
    <section class="shell ${layoutClass}">
      <nav class="rail" aria-label="Plugin actions">
        <button data-action="refresh" title="Background socket refresh">R</button>
        <button data-action="layout" title="Toggle layout">L</button>
      </nav>
      <aside class="navigator">
        <header>
          <h1>Agor</h1>
          <p>${state.snapshot.worktrees.length} worktrees / ${state.snapshot.sessions.length} sessions / sync ${state.syncCount}</p>
          <input data-action="search" value="${escapeHtml(state.search)}" placeholder="Search">
        </header>
        <div class="tree">${renderTree()}</div>
      </aside>
      <section class="inspector">
        <header class="topbar">
          <div>
            <p>AGOR SESSION</p>
            <h2>${escapeHtml(session?.title || 'No session')}</h2>
            <span>${escapeHtml(session?.status || 'UNKNOWN')} / ${escapeHtml(session?.agenticTool || 'agent')}</span>
          </div>
          <div class="actions">
            <button>Stop</button>
            <button>Fork</button>
            <button>Spawn</button>
          </div>
        </header>
        <section class="conversation" aria-label="Selectable conversation transcript">
          ${renderMessages()}
        </section>
        <footer class="composer">
          <textarea data-action="prompt" placeholder="Prompt selected Agor session">Summarize current changes.</textarea>
          <button data-action="send">Send</button>
        </footer>
      </section>
    </section>
  `;
}

function renderTree() {
  const query = state.search.trim().toLowerCase();
  return state.snapshot.boards
    .map((board) => {
      const worktrees = worktreesForBoard(board.boardId)
        .map((worktree) => {
          const sessions = sessionsForWorktree(worktree.worktreeId).filter((session) => {
            if (!query) return true;
            return (
              board.name.toLowerCase().includes(query) ||
              worktree.name.toLowerCase().includes(query) ||
              worktree.ref.toLowerCase().includes(query) ||
              session.title.toLowerCase().includes(query)
            );
          });
          if (query && sessions.length === 0) return '';
          return `
            <details open>
              <summary>${escapeHtml(worktree.name)} <small>${escapeHtml(worktree.ref)}</small></summary>
              ${sessions
                .map(
                  (session) => `
                    <button class="session ${session.sessionId === state.selectedSessionId ? 'selected' : ''}" data-session="${session.sessionId}">
                      <span>${escapeHtml(session.title)}</span>
                      <small>${escapeHtml(session.status.toLowerCase())}</small>
                    </button>
                  `
                )
                .join('')}
            </details>
          `;
        })
        .join('');
      if (query && !worktrees.trim()) return '';
      return `
        <details open class="board">
          <summary>${escapeHtml(board.name)}</summary>
          ${worktrees}
        </details>
      `;
    })
    .join('');
}

function renderMessages() {
  const messages = messagesForSelectedSession()
    .map(
      (message) => `
        <article class="message" data-message="${message.messageId}">
          <header>${roleLabel(message.role)} / ${escapeHtml(message.timestamp)} / ${escapeHtml(message.status || '')}</header>
          <pre>${escapeHtml(message.text)}</pre>
        </article>
      `
    )
    .join('');
  const live =
    state.live && state.live.sessionId === state.selectedSessionId
      ? `
        <article class="message live" data-message="${state.live.messageId}">
          <header>Agent / streaming</header>
          ${state.live.thinking ? `<pre class="thinking">Thinking\n${escapeHtml(state.live.thinking)}</pre>` : ''}
          <pre>${escapeHtml(state.live.text || 'Streaming...')}</pre>
        </article>
      `
      : '';
  const permissions = state.snapshot.permissionRequests
    .filter((permission) => permission.sessionId === state.selectedSessionId)
    .map(
      (permission) => `
        <article class="message permission">
          <header>Permission Required</header>
          <pre>Tool: ${escapeHtml(permission.toolName)}
Request: ${escapeHtml(permission.requestId)}
Input: ${escapeHtml(permission.toolInputJson)}</pre>
          <div class="actions"><button>Approve Once</button><button>Deny</button></div>
        </article>
      `
    )
    .join('');
  return messages || live
    ? `${messages}${live}${permissions}`
    : '<article class="message"><pre>No messages yet.</pre></article>';
}

function sendPrompt() {
  const textarea = document.querySelector('[data-action="prompt"]');
  const text = textarea.value.trim();
  if (!text) return;
  const current = messagesForSelectedSession();
  const index = current.at(-1)?.index || 0;
  state = {
    ...state,
    messagesBySession: {
      ...state.messagesBySession,
      [state.selectedSessionId]: [
        ...current,
        {
          messageId: `local-user-${index + 1}`,
          sessionId: state.selectedSessionId,
          role: 'USER',
          index: index + 1,
          timestamp: '2026-05-18T00:10:00Z',
          text,
          status: 'queued',
        },
      ],
    },
    live: {
      sessionId: state.selectedSessionId,
      messageId: `live-${index + 2}`,
      text: '',
      thinking: '',
      finished: false,
    },
  };
  render();
  const chunks = [
    { thinking: true, text: 'Reading session context. ' },
    { thinking: false, text: 'Realtime update arrived from the Agor stream. ' },
    { thinking: false, text: 'The selected session stayed open during background sync.' },
  ];
  let i = 0;
  clearInterval(streamTimer);
  streamTimer = setInterval(() => {
    const chunk = chunks[i++];
    if (!chunk) {
      finishStream();
      clearInterval(streamTimer);
      return;
    }
    state = {
      ...state,
      live: {
        ...state.live,
        text: chunk.thinking ? state.live.text : state.live.text + chunk.text,
        thinking: chunk.thinking ? state.live.thinking + chunk.text : state.live.thinking,
      },
    };
    render();
  }, 350);
}

function finishStream() {
  if (!state.live) return;
  const current = messagesForSelectedSession();
  const index = current.at(-1)?.index || 0;
  state = {
    ...state,
    live: undefined,
    messagesBySession: {
      ...state.messagesBySession,
      [state.selectedSessionId]: [
        ...current,
        {
          messageId: `persisted-${index + 1}`,
          sessionId: state.selectedSessionId,
          role: 'ASSISTANT',
          index: index + 1,
          timestamp: '2026-05-18T00:10:04Z',
          text: 'Realtime update arrived from the Agor stream. The selected session stayed open during background sync.',
          status: 'complete',
        },
      ],
    },
  };
  render();
}

app.addEventListener('click', (event) => {
  const target = event.target.closest('button');
  if (!target) return;
  const sessionId = target.dataset.session;
  if (sessionId) {
    state = { ...state, selectedSessionId: sessionId, live: undefined };
    render();
    return;
  }
  if (target.dataset.action === 'send') sendPrompt();
  if (target.dataset.action === 'refresh') {
    state = { ...state, syncCount: state.syncCount + 1 };
    render();
  }
  if (target.dataset.action === 'layout') {
    state = { ...state, layout: state.layout === 'side-by-side' ? 'stacked' : 'side-by-side' };
    render();
  }
});

app.addEventListener('input', (event) => {
  if (event.target.dataset.action !== 'search') return;
  state = { ...state, search: event.target.value };
  render();
});

render();
