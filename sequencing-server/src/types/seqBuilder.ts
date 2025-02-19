import type { UserCodeError } from '@nasa-jpl/aerie-ts-user-code-runner';

import type { CommandStem, LoadStep, ActivateStep, Sequence } from '../lib/codegen/CommandEDSLPreface.js';
import type { SimulatedActivity } from '../lib/batchLoaders/simulatedActivityBatchLoader';

export type ExpandedActivity<T> = SimulatedActivity & {
  expansionProduct: T,
  errors: ReturnType<UserCodeError['toJSON']>[] | null;
};

export interface SeqBuilder<T> {
  (
    expandedActivities: ExpandedActivity<T>[],
    seqId: string,
    seqMetadata: Record<string, any>,
    simulationDatasetId: number,
  ): T;
}
