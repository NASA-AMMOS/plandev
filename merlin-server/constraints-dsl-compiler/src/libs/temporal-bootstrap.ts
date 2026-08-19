import { Temporal } from 'temporal-polyfill-bundle';

Object.defineProperty(globalThis, 'Temporal', {
  value: Temporal,
  writable: false,
  configurable: false,
});
