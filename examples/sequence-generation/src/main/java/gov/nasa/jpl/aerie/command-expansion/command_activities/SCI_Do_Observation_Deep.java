package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.polynomial.PolynomialEffects.consuming;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECONDS;

@ActivityType("SCI_Do_Observation_Deep")
public class SCI_Do_Observation_Deep extends Command {
    @Export.Parameter
    public int duration = 3600;

    @Override
    public List<Object> args() {
        return List.of(duration);
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
        // "Full" command model would go here.
        // In this case, we're modeling using 5% battery SOC per hour,
        // for however long this command is ordered to execute.
        consuming(mission.power.batterySOC, 5.0 / 3600, () -> {
            delay(duration, SECONDS);
        });
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
