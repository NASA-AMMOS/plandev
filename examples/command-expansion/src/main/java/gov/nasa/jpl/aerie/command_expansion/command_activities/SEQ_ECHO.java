package gov.nasa.jpl.aerie.command_expansion.command_activities;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

import static gov.nasa.jpl.aerie.command_expansion.command_activities.CommandConstants.SEQ_COMMAND_DURATION;
import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;
import static gov.nasa.jpl.aerie.merlin.framework.ModelActions.delay;

@ActivityType("SEQ_ECHO")
public class SEQ_ECHO extends Command {
    @Export.Parameter
    public String message;

    @Override
    public List<Object> args() {
        return List.of(message);
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
        LOGGER.info("SEQ_ECHO: %s", message);
        delay(SEQ_COMMAND_DURATION);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }

    public static SEQ_ECHO of(final String message) {
        var result = new SEQ_ECHO();
        result.message = message;
        return result;
    }
}
