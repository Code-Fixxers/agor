import assert from 'node:assert/strict';
import test from 'node:test';
import {
  appendStreamingChunk,
  applyBackgroundSync,
  createInitialState,
  finishStreaming,
  messagesForSelectedSession,
  selectSession,
  sendPrompt,
} from './harness-model.js';

test('opening a session exposes previous conversation messages', () => {
  const state = selectSession(createInitialState(), 'sess-transcript');

  assert.equal(messagesForSelectedSession(state).length, 2);
  assert.equal(
    messagesForSelectedSession(state)[0]?.text,
    'The plugin only shows Session context.'
  );
});

test('background sync preserves the selected session', () => {
  const state = selectSession(createInitialState(), 'sess-transcript');
  const synced = applyBackgroundSync(state);

  assert.equal(synced.selectedSessionId, 'sess-transcript');
  assert.equal(synced.syncCount, 1);
});

test('sending a prompt produces live streaming text then persists assistant response', () => {
  const started = sendPrompt(createInitialState(), 'Please continue.');
  const streaming = appendStreamingChunk(
    appendStreamingChunk(started, 'Thinking about the context.', true),
    'Here is the update.'
  );
  const finished = finishStreaming(streaming);

  assert.equal(streaming.live?.thinking, 'Thinking about the context.');
  assert.equal(streaming.live?.text, 'Here is the update.');
  assert.equal(finished.live, undefined);
  assert.equal(messagesForSelectedSession(finished).at(-1)?.text, 'Here is the update.');
});
