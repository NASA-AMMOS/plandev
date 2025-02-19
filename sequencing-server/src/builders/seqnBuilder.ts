import type { CommandStem, LoadStep, ActivateStep } from '../lib/codegen/CommandEDSLPreface.js';
import { sequenceToSeqJson } from '../lib/parsing/seqn/seqnToSeqJson.js';
import type { SeqBuilder, ExpandedActivity } from '../types/seqBuilder.js';
import type {Command} from './seqJsonBuilder.js'

export const seqnBuilder: SeqBuilder<string> = (
  sortedActivityInstancesWithCommands,
  seqId,
  seqMetadata,
  simulationDatasetId,
) => {
  // TODO extract commands as SeqN

  const pasedActivityInstanceCommands = sortedActivityInstancesWithCommands.map(seqnLinesToSeqJson)

  // TODO parse SeqN to SeqJson
  // TODO call seqJsonBuilder
  // TODO write SeqJson to SeqN
};

function seqnLinesToSeqJson(instance: ExpandedActivity<string>): ExpandedActivity<Command> {
  const seqN = instance.commands.join('\n');
  return sequenceToSeqJson(seqN, "").steps ?? [];
}
