package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.command_activities.CommandConstants.SEQ_COMMAND_DURATION;
import static gov.nasa.jpl.aerie.contrib.streamline.modeling.discrete.DiscreteEffects.set;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

@ActivityType("SEQ_SET_STRING")
public class SEQ_SET_STRING extends Command {
    @Export.Parameter
    public String variable;

    @Export.Parameter
    public String newValue;

    @Override
    public List<Object> args() {
        return List.of(variable, newValue);
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
        set(mission.globals.getGlobalString(variable), newValue);
        delay(SEQ_COMMAND_DURATION);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }
}
