package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.PowerModel;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

@ActivityType("PWR_Turn_On_Heater")
public class PWR_Turn_On_Heater extends Command {
    @ActivityType.EffectModel
    public void run(final Mission mission) {
        // Just model the effects
        set(mission.power.heater, PowerModel.HeaterState.ON);
        delay(1, SECOND);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
