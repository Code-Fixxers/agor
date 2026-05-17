import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { createInitialState } from './harness-model.js';
import { renderHtml } from './render.js';

const root = new URL('.', import.meta.url).pathname;
const port = Number(process.env.PORT || 49880);

const contentTypes: Record<string, string> = {
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.html': 'text/html; charset=utf-8',
};

const server = createServer(async (request, response) => {
  const path = new URL(request.url || '/', `http://${request.headers.host}`).pathname;
  try {
    if (path === '/') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(renderHtml(createInitialState()));
      return;
    }
    if (path === '/health') {
      response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify({ ok: true }));
      return;
    }
    if (path === '/client.js' || path === '/styles.css') {
      const file = await readFile(join(root, path.slice(1)));
      response.writeHead(200, {
        'content-type': contentTypes[extname(path)] || 'text/plain; charset=utf-8',
      });
      response.end(file);
      return;
    }
    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('Not found');
  } catch (error) {
    response.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
    response.end(error instanceof Error ? error.message : 'Internal error');
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log(`Agor JetBrains harness: http://127.0.0.1:${port}/`);
});
