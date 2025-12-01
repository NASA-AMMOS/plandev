import type { UserCodeError } from '@nasa-jpl/plandev-ts-user-code-runner';
import type { SimulatedActivity } from '../lib/batchLoaders/simulatedActivityBatchLoader';

export type ExpandedActivity<T> = SimulatedActivity & {
  expansionResult: T | null;
  errors: ReturnType<UserCodeError['toJSON']>[] | null;
};

export interface SeqBuilder<Input, Output> {
  (
    expandedActivities: ExpandedActivity<Input>[],
    seqId: string,
    seqMetadata: Record<string, any>,
    simulationDatasetId: number,
  ): Output;
}
