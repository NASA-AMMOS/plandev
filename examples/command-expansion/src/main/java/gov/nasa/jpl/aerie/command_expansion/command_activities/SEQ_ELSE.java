package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;

import static gov.nasa.jpl.aerie.command_expansion.command_activities.CommandConstants.SEQ_COMMAND_DURATION;
import static gov.nasa.jpl.aerie.contrib.streamline.core.Resources.currentValue;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

@ActivityType("SEQ_ELSE")
public class SEQ_ELSE extends Command {
    @ActivityType.EffectModel
    public void run(Mission mission) {
        // TODO - error checking & handling
        var sequence = currentValue(engine.loadedSequence()).orElseThrow();

        // This command is run when we hit the end of the "if" branch, so jump to "else"
        int thisIndex = currentValue(engine.lastDispatchedCommandIndex());
        // If we don't find an "end-if", jump out of the sequence instead.
        int jumpIndex = sequence.commands().size();
        for (int i = thisIndex + 1; i < sequence.commands().size(); ++i) {
            if (sequence.commands().get(i).command() instanceof SEQ_END_IF) {
                jumpIndex = i;
                break;
            }
        }
        set(engine.nextCommandIndex(), jumpIndex);

        delay(SEQ_COMMAND_DURATION);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
