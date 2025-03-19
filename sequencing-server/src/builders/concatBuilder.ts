import type { SeqBuilder } from '../types/seqBuilder.js';

export const concatBuilder: SeqBuilder<string, string> = (
  expandedActivities
) => {
  return expandedActivities.map(act => act.expansionResult).join("\n");
};
