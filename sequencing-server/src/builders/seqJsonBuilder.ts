import { ActivateStep, CommandStem, LoadStep, Sequence } from '../lib/codegen/CommandEDSLPreface.js';
import type { SeqBuilder } from '../types/seqBuilder.js';

export type Command = CommandStem | ActivateStep | LoadStep;

export type SeqJsonBuilder = SeqBuilder<Command[], Sequence>

export const seqJsonBuilder: SeqJsonBuilder = (
    expandedActivities,
    seqId,
    seqMetadata,
    simulationDatasetId,
) => {

  let allCommands: (CommandStem | ActivateStep | LoadStep)[] = [];
  let convertedCommands: (CommandStem | ActivateStep | LoadStep)[] = [];
  let activityInstanceCount = 0;
  let planId;
  // Keep track if we should try and sort the commands.
  let shouldSort = true;
  let timeSorted = false;
  let previousTime: Temporal.Instant | undefined = undefined;

  for (const ai of expandedActivities) {
    // If errors, no associated Expansion
    if (ai.errors !== null) {
      planId = ai?.simulationDataset.simulation?.planId;

      if (ai.errors.length > 0) {
        allCommands = allCommands.concat(
            ai.errors.map(e =>
                CommandStem.new({
                  stem: '$$ERROR$$',
                  arguments: [{ message: e.message }],
                }).METADATA({ simulatedActivityId: ai.id }),
            ),
        );
      }

      // Typeguard only
      if (ai.expansionResult === null) {
        break;
      }

      /**
       * Treat the activity start time as the "previous time" for sorting expanded commands that begin with a command-relative time tag.
       *
       * TODO: we argue that this is actually ideal behavior, but it warrants discussion.
       */
       // TODO: we argue that this is actually ideal behavior, but it warrants discussion.
      previousTime = ai.startTime //**** */
      for (const command of ai.expansionResult) {

        const currentCommand = command instanceof CommandStem ? command as CommandStem : command instanceof LoadStep ? command as LoadStep : command as ActivateStep;

        // If any command is epoch-relative or command complete, we can't sort
        if (
            currentCommand.GET_EPOCH_TIME() ||
            (!currentCommand.GET_ABSOLUTE_TIME() && !currentCommand.GET_EPOCH_TIME() && !currentCommand.GET_RELATIVE_TIME())
        ) {
          shouldSort = false; // Set the sorting flag to false
          break; // No need to continue checking other commands
        }

        // If we come across a relative command, convert it to absolute.
        if (currentCommand.GET_RELATIVE_TIME() && previousTime) {
          const absoluteCommand : CommandStem | LoadStep | ActivateStep = currentCommand.absoluteTiming(previousTime.add(currentCommand.GET_RELATIVE_TIME() as Temporal.Duration));
          convertedCommands.push(absoluteCommand);
          previousTime = absoluteCommand.GET_ABSOLUTE_TIME();
        } else {
          convertedCommands.push(currentCommand);
          previousTime = currentCommand.GET_ABSOLUTE_TIME();
        }
      }

      allCommands = allCommands.concat(ai.expansionResult as Command[]);
      // Keep track of the number of times we add commands to the allCommands list.
      activityInstanceCount++;
    }
  }

  // If we never found a condition that prohibits sorting, then sort the converted commands and assign those as the output
  if (activityInstanceCount > 0 && shouldSort) {
    timeSorted = true;
    allCommands = convertedCommands.sort((a, b) => {
      const aStep = a instanceof CommandStem ? a as CommandStem : a instanceof LoadStep ? a as LoadStep : a as ActivateStep;
      const bStep = b instanceof CommandStem ? b as CommandStem : b instanceof LoadStep ? b as LoadStep : b as ActivateStep;

      const aAbsoluteTime = aStep.GET_ABSOLUTE_TIME();
      const bAbsoluteTime = bStep.GET_ABSOLUTE_TIME();

      if (aAbsoluteTime && bAbsoluteTime) {
        return Temporal.Instant.compare(aAbsoluteTime, bAbsoluteTime);
      }

      return 0;
    });
  }

  return Sequence.new({
    seqId: seqId,
    metadata: {
      ...seqMetadata,
      planId,
      simulationDatasetId,
      timeSorted,
    },
    steps: allCommands,
  });
};
