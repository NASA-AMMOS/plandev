package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

@ActivityType("SCI_Warm_Up_Moderate")
public class SCI_Warm_Up_Moderate extends Command {
    @Export.Parameter
    public int duration = 600;

    @Override
    public List<Object> args() {
        return List.of(duration);
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
        // Only duration is modeled
        delay(duration, SECONDS);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
