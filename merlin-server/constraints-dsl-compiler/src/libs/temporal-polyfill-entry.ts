/**
 * entry point for bundling the temporal polyfill into self-contained guest code.
 * the bundle is evaluated inside the isolate so no host-owned temporal objects cross the security boundary.
 */

export { Temporal } from '@js-temporal/polyfill';
