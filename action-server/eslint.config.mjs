import { sheriff, tseslint } from "eslint-config-sheriff";

const sheriffOptions = {
  react: false,
  lodash: false,
  remeda: false,
  next: false,
  astro: false,
  playwright: false,
  jest: false,
  vitest: false,
};

export default tseslint.config(sheriff(sheriffOptions), {
  rules: {
    "@typescript-eslint/no-explicit-any": ["off"],
    "@typescript-eslint/no-extraneous-class": "off",
    "@typescript-eslint/naming-convention": "off",
    "func-style": "off",
    "no-restricted-syntax": "off",
  },
});
