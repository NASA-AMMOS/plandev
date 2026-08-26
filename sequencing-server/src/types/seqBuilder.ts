import type { SimulatedActivity } from '../lib/batchLoaders/simulatedActivityBatchLoader';

export type ExpansionError = {
  message: string;
};

export type ExpandedActivity<T> = SimulatedActivity & {
  expansionResult: T | null;
  errors: ExpansionError[] | null;
};

export interface SeqBuilder<Input, Output> {
  (
    expandedActivities: ExpandedActivity<Input>[],
    seqId: string,
    seqMetadata: Record<string, any>,
    simulationDatasetId: number,
  ): Output;
}
