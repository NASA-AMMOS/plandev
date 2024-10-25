package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

@ActivityType("CMD_NO_OP")
public class CMD_NO_OP extends Command {
    @ActivityType.EffectModel
    public void run(Mission mission) {
        delay(1, SECOND);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
