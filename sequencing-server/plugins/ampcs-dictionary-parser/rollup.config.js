import { defineConfig } from "rollup";
import commonjs from "@rollup/plugin-commonjs";
import nodeResolve from "@rollup/plugin-node-resolve";

export default defineConfig({
  treeshake: false,
  input: "./build/ampcs-parser.js",
  output: {
    dir: "./",
    preserveModules: false,
  },
  plugins: [commonjs(), nodeResolve({preferBuiltins: true})],
});
