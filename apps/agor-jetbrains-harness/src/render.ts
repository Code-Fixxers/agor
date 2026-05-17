import type { HarnessState } from './harness-model.js';

export function renderHtml(state: HarnessState): string {
  const serialized = JSON.stringify(state).replaceAll('<', '\\u003c');
  return `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Agor JetBrains Harness</title>
    <link rel="stylesheet" href="/styles.css">
  </head>
  <body>
    <script>window.__AGOR_HARNESS_STATE__ = ${serialized};</script>
    <main id="app" aria-label="Agor JetBrains plugin browser harness"></main>
    <script type="module" src="/client.js"></script>
  </body>
</html>`;
}
