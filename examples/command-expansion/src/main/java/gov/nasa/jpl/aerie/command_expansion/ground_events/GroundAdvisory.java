package gov.nasa.jpl.aerie.command_expansion.ground_events;

import gov.nasa.jpl.aerie.command_expansion.generated.ActivityActions;
import gov.nasa.jpl.aerie.command_expansion.model.Mission;
import gov.nasa.jpl.aerie.merlin.framework.annotations.ActivityType;
import gov.nasa.jpl.aerie.merlin.framework.annotations.Export;

import java.util.List;

import static gov.nasa.jpl.aerie.contrib.streamline.debugging.Logging.LOGGER;

@ActivityType("GroundAdvisory")
public class GroundAdvisory extends GroundEvent {
    @Export.Parameter
    public String message;

    @Override
    public List<Object> args() {
        return List.of(message);
    }

    @ActivityType.EffectModel
    public void run(Mission mission) {
        LOGGER.info("Ground Advisory: %s", message);
    }

    @Override
    public void call(Mission mission) {
        ActivityActions.call(mission, this);
    }

    public static GroundAdvisory of(String message) {
        var result = new GroundAdvisory();
        result.message = message;
        return result;
    }
}
