package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;

import static gov.nasa.jpl.aerie.command_expansion.command_activities.CommandConstants.SEQ_COMMAND_DURATION;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

@ActivityType("SEQ_END_IF")
public class SEQ_END_IF extends Command {
    @ActivityType.EffectModel
    public void run(Mission mission) {
        delay(SEQ_COMMAND_DURATION);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
