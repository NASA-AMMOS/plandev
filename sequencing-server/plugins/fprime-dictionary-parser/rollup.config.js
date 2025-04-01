import { defineConfig } from 'rollup';
import { nodeResolve } from '@rollup/plugin-node-resolve';

export default defineConfig({
  input: './build/src/fprime-parser.js',
  output: {
    dir: './',
    preserveModules: true,
  },
  plugins: [nodeResolve()],
});
