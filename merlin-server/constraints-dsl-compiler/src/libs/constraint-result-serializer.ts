// src/libs/constraint-result-serializer.ts
import { Temporal } from 'temporal-polyfill-bundle';

interface ConstraintResult {
  __astNode: object;
}

export default function serializeConstraintResult(result: ConstraintResult): string {
  const stringified = JSON.stringify(
    result.__astNode,
    function replacer(key, value) {
      // replace all instances of (unserializable) Temporal.Duration in AST with microsecond duration number (serializable)
      const originalValue = this[key];
      if (originalValue instanceof Temporal.Duration) {
        return originalValue.total({
          unit: 'microseconds',
        });
      }
      return value;
    }
  );

  if (stringified === undefined) {
    throw new Error('Constraint result was not JSON serializable');
  }

  return stringified;
}
