import type { UserCodeError } from '@nasa-jpl/aerie-ts-user-code-runner';

import type { CommandStem, LoadStep, ActivateStep, Sequence } from '../lib/codegen/CommandEDSLPreface.js';
import type { SimulatedActivity } from '../lib/batchLoaders/simulatedActivityBatchLoader';

export interface SeqBuilder {
  (
    sortedActivityInstancesWithCommands: (SimulatedActivity & {
      commands: (CommandStem | ActivateStep | LoadStep)[] | null | string[]; // todo, make less explicit. or make a command interface.
      errors: ReturnType<UserCodeError['toJSON']>[] | null;
    })[],
    seqId: string,
    seqMetadata: Record<string, any>,
    simulationDatasetId: number,
  ): Sequence;
}
