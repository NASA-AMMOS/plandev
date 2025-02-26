import { seqJsonToSeqn } from '../lib/parsing/seqn/seqJsonToSeqn.js';
import { seqnToSeqJson } from '../lib/parsing/seqn/seqnToSeqJson.js';
import type { SeqBuilder, ExpandedActivity } from '../types/seqBuilder.js';
import { seqJsonBuilder } from './seqJsonBuilder.js';
import type { Command } from './seqJsonBuilder.js';

export const seqnBuilder: SeqBuilder<string, string> = (
  expandedActivities,
  seqId,
  seqMetadata,
  simulationDatasetId,
) => {
  const parsedExpandedActivities = expandedActivities.map(seqnActivityToSeqJson);
  const mergedSequence = seqJsonBuilder(parsedExpandedActivities, seqId, seqMetadata, simulationDatasetId);
  return seqJsonToSeqn(mergedSequence);
};

function seqnActivityToSeqJson(instance: ExpandedActivity<string>): ExpandedActivity<Command[]> {
  const expansionResult = seqnToSeqJson(instance.expansionResult ?? '', '').steps as Command[];
  return {
    ...instance,
    expansionResult
  };
}
