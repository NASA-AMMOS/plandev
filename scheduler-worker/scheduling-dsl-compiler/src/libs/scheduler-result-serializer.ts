import { Temporal } from 'temporal-polyfill-bundle';

interface SchedulerResult {
  __astNode: object;
}

export default function serializeSchedulerResult(result: SchedulerResult): string {
  const stringified = JSON.stringify(
      result.__astNode,
      function replacer(key, value) {
        // replace all instances of (unserializable) Temporal.Duration in AST with microsecond duration number (serializable)
        const originalValue: any = this[key];
        if (originalValue instanceof Temporal.Duration) {
          return originalValue.total({
            unit: 'microseconds',
          });
        }
        return value;
      }
  );

  if (stringified === undefined) {
    throw new Error('Scheduler result was not JSON serializable');
  }

  return stringified;
}
