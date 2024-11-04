package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.command_expansion.model.PowerModel.HeaterState;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;
import static gov.nasa.jpl.aerie.merlin.protocol.types.Duration.SECOND;

@ActivityType("PWR_Turn_Off_Heater")
public class PWR_Turn_Off_Heater extends Command {
    @Export.Parameter
    public SHUTDOWN_STATE state = SHUTDOWN_STATE.STANDBY;

    @ActivityType.EffectModel
    public void run(Mission mission) {
        set(mission.power.heater, state == SHUTDOWN_STATE.OFF ? HeaterState.OFF : HeaterState.STANDBY);
        delay(1, SECOND);
    }

    @Override
    public List<Object> args() {
        return List.of(state);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }

    public enum SHUTDOWN_STATE {
        OFF,
        STANDBY
    }
}
