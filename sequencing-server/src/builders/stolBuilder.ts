import type { SeqBuilder } from '../types/seqBuilder.js';

export const stolBuilder: SeqBuilder<string, string> = (
  expandedActivities
) => {
  // presently, this is a trivial, string combination case. more complex sorting functionality, etc., should be decided on later
  return expandedActivities.map(act => act.expansionResult).filter(expansion => expansion != null).join("\n");
};
