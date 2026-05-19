#!/usr/bin/env node

import http from 'node:http';

const listenHost = process.env.AGOR_PROXY_HOST || '127.0.0.1';
const listenPort = Number(process.env.AGOR_PROXY_PORT || '3030');
const targetValue = process.env.AGOR_PROXY_TARGET;

if (!targetValue) {
  console.error('Set AGOR_PROXY_TARGET, for example: AGOR_PROXY_TARGET=http://daemon:3030');
  process.exit(1);
}

const target = new URL(targetValue);

const corsHeaders = (origin) => ({
  'access-control-allow-origin': origin || '*',
  'access-control-allow-methods': 'GET,POST,PATCH,DELETE,OPTIONS',
  'access-control-allow-headers': 'authorization,content-type,mcp-session-id,x-requested-with',
  'access-control-expose-headers': 'mcp-session-id',
  'access-control-max-age': '86400',
  vary: 'Origin, Access-Control-Request-Method, Access-Control-Request-Headers',
});

const server = http.createServer((clientReq, clientRes) => {
  const origin = clientReq.headers.origin;

  if (clientReq.method === 'OPTIONS') {
    clientRes.writeHead(204, corsHeaders(origin));
    clientRes.end();
    return;
  }

  const headers = { ...clientReq.headers };
  delete headers.host;
  delete headers.origin;
  delete headers.referer;
  delete headers['sec-fetch-site'];
  delete headers['sec-fetch-mode'];
  delete headers['sec-fetch-dest'];
  // Browser-side smoke tests can carry stale localhost cookies from another
  // WebUI instance. The proxy authenticates with explicit headers only.
  delete headers.cookie;

  const upstreamReq = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || 80,
      method: clientReq.method,
      path: clientReq.url,
      headers,
    },
    (upstreamRes) => {
      if (process.env.AGOR_PROXY_LOG === '1') {
        console.log(`${clientReq.method} ${clientReq.url} -> ${upstreamRes.statusCode}`);
      }
      const responseHeaders = {
        ...upstreamRes.headers,
        ...corsHeaders(origin),
      };
      delete responseHeaders['content-security-policy'];
      delete responseHeaders['x-frame-options'];

      clientRes.writeHead(upstreamRes.statusCode || 502, responseHeaders);
      upstreamRes.pipe(clientRes);
    }
  );

  upstreamReq.on('error', (error) => {
    clientRes.writeHead(502, {
      'content-type': 'application/json',
      ...corsHeaders(origin),
    });
    clientRes.end(JSON.stringify({ error: 'ProxyError', message: error.message }));
  });

  clientReq.pipe(upstreamReq);
});

server.listen(listenPort, listenHost, () => {
  console.log(
    `Agor CORS proxy listening on http://${listenHost}:${listenPort} -> ${target.origin}`
  );
});
