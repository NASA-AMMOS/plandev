/**
 * declare virtual modules that exist only inside the guest isolate.
 * runtime implementations are supplied separately as precompiled javascript.
 */

declare module 'temporal-polyfill-bundle' {
  export const Temporal: typeof globalThis.Temporal;
}
