import { defineConfig } from 'tsup';

export default defineConfig({
  entry: { main: 'src/main.ts' },
  format: ['esm'],
  target: 'node22',
  clean: true,
  sourcemap: true,
  dts: false,
  splitting: false,
});
