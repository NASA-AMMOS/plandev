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
  const convertedCommands: (CommandStem | ActivateStep | LoadStep)[] = []

  // A combined list of all the activity instance commands.
  let allCommands: (CommandStem | ActivateStep | LoadStep)[] = [];
  let activityInstaceCount = 0;
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
       * Look at the first command for each activity and check if it's relative, if so we shouldn't
       * sort later. Also convert any relative commands to absolute.
       */
      // TODO: we argue that this is actually ideal behavior, but it warrants discussion.
      previousTime = ai.startTime //**** */
      for (const command of ai.expansionResult) {

        const currentCommand = command instanceof CommandStem ? command as CommandStem : command instanceof LoadStep ? command as LoadStep : command as ActivateStep;

        // Check conditions related to time properties
        // If the command is epoch-relative, complete, or the first command and relative then short circuit and don't try and sort.
        if (
            currentCommand.GET_EPOCH_TIME() ||
            (!currentCommand.GET_ABSOLUTE_TIME() && !currentCommand.GET_EPOCH_TIME() && !currentCommand.GET_RELATIVE_TIME()) //||
            // (currentCommand.GET_RELATIVE_TIME() && (ai.expansionResult as Command[]).indexOf(command as Command) === 0) // TODO: deal with this later..
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
      activityInstaceCount++;
    }
  }

  // Now that we have all the commands for each activity instance, sort if there's > 1 activity instance and we didn't short circuit above.
  if (activityInstaceCount > 1 && shouldSort) {
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
