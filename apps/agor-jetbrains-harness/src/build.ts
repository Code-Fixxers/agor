import { copyFile, mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { createInitialState } from './harness-model.js';
import { renderHtml } from './render.js';

const root = new URL('..', import.meta.url).pathname;
const source = new URL('.', import.meta.url).pathname;
const dist = join(root, 'dist');

await mkdir(dist, { recursive: true });
await writeFile(join(dist, 'index.html'), renderHtml(createInitialState()));
await copyFile(join(source, 'client.js'), join(dist, 'client.js'));
await copyFile(join(source, 'styles.css'), join(dist, 'styles.css'));

console.log(`Built Agor JetBrains harness at ${dist}`);
